package dev.bettergit.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.reports.ActivityReport
import dev.bettergit.services.WorkbenchService
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer

class ReportsPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private var root: Path? = null
    private var report = ActivityReport.from(emptyList())
    private val summary = JBLabel("Load a repository to see its activity.")
    private val contributors = readOnlyModel(arrayOf("Contributor", "Commits", "Share"))
    private val activity = readOnlyModel(arrayOf("Date", "Commits", "Activity"))
    init {
        border = JBUI.Borders.empty(12)
        add(summary, BorderLayout.NORTH)
        add(JTabbedPane().apply {
            addTab("Contributors", JBScrollPane(JBTable(contributors).apply {
                setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
            }))
            addTab("Daily activity", JBScrollPane(JBTable(activity)))
        }, BorderLayout.CENTER)
        add(Ui.row(Ui.button("Export report…") { export() }, Ui.label("Counts describe commits, not developer productivity.", true)), BorderLayout.SOUTH)
    }

    fun setCommits(root: Path, commits: List<GitCommit>, limit: Int) {
        setReport(root, ActivityReport.from(commits), limit)
    }

    fun setReport(root: Path, preparedReport: ActivityReport, limit: Int) {
        this.root = root; report = preparedReport
        summary.text = "${report.commitCount} commits · ${report.contributors.size} contributors · ${report.mergeCount} merges · latest $limit across refs"
        contributors.rowCount = 0; activity.rowCount = 0
        report.contributors.forEach { contributor ->
            contributors.addRow(arrayOf<Any>(contributor.name, contributor.commits, "${contributor.commits * 100 / report.commitCount.coerceAtLeast(1)}%"))
        }
        val maximum = report.dailyActivity.values.maxOrNull() ?: 1
        report.dailyActivity.entries.reversed().forEach { (date, count) ->
            activity.addRow(arrayOf<Any>(date.toString(), count, "▰".repeat((count * 24 / maximum).coerceAtLeast(1))))
        }
    }

    fun clear() {
        root = null; report = ActivityReport.from(emptyList())
        contributors.rowCount = 0; activity.rowCount = 0
        summary.text = "Load a repository to see its activity."
    }

    private fun export() {
        val path = root ?: return
        val target = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Git activity report", "Save the currently loaded repository activity", "html"), project,
        ).save("${path.fileName}-activity.html") ?: return
        val html = buildString {
            append("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Git activity</title>")
            append("<style>body{font:16px system-ui;margin:48px;max-width:1000px}table{border-collapse:collapse;width:100%}td,th{padding:10px;text-align:left;border-bottom:1px solid #bbb}small{color:#666}</style>")
            append("<h1>${Ui.html(path.fileName.toString())} · Git activity</h1><p>${Ui.html(summary.text)}</p><small>Exported ${LocalDate.now()}. Counts describe commits, not developer productivity.</small>")
            append("<h2>Contributors</h2><table><tr><th>Name</th><th>Commits</th></tr>")
            report.contributors.forEach { append("<tr><td>${Ui.html(it.name)}</td><td>${it.commits}</td></tr>") }
            append("</table><h2>Daily activity</h2><table><tr><th>Date</th><th>Commits</th></tr>")
            report.dailyActivity.forEach { (date, count) -> append("<tr><td>$date</td><td>$count</td></tr>") }
            append("</table></html>")
        }
        WorkbenchService.getInstance(project).submit("Export report", onSuccess = { summary.text = "Report saved to ${target.file.name}" }) {
            Files.writeString(target.file.toPath(), html)
        }
    }

    private fun readOnlyModel(columns: Array<String>) = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
}
