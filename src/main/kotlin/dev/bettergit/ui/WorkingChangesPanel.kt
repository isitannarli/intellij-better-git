package dev.bettergit.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.WorkingChange
import dev.bettergit.services.WorkbenchService
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent

class WorkingChangesPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private var root: Path? = null
    private var changes = emptyList<WorkingChange>()
    private var request: Job? = null
    private var generation = 0L
    private var disposed = false
    private val model = DefaultListModel<WorkingChange>()
    private val files = JBList(model)
    private val search = JBTextField().apply { emptyText.text = "Filter changed files" }
    private val scope = ComboBox(arrayOf("All changes", "Staged", "Working tree", "Conflicts"))
    private val summary = Ui.label("Select a repository to inspect working changes.", true)
    private val status = Ui.label("Saved files on disk · select a file to inspect its diff", true)
    private val staged = Ui.button("Staged diff") { openDiff(true) }
    private val working = Ui.button("Working diff") { openDiff(false) }

    init {
        border = JBUI.Borders.empty(12)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(DashboardStyles.card(BorderLayout(0, JBUI.scale(8)), emphasized = true).apply {
                add(DashboardStyles.eyebrow("READY FOR YOUR NEXT COMMIT"), BorderLayout.NORTH)
                add(Ui.heading("Working changes"), BorderLayout.CENTER)
                add(summary, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(search, BorderLayout.CENTER)
                add(scope, BorderLayout.EAST)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        files.selectionMode = ListSelectionModel.SINGLE_SELECTION
        files.emptyText.text = "Working tree is clean."
        files.cellRenderer = ChangeRenderer()
        files.addListSelectionListener { updateActions() }
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openPreferred() }
        })
        files.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) openPreferred() }
        })
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        scope.addActionListener { filter() }
        add(JBScrollPane(files).apply {
            border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border())
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(status, BorderLayout.NORTH)
            add(Ui.row(staged, working,
                Ui.button("Commit / stage…") { Ui.nativeAction(project, this@WorkingChangesPanel, "CheckinProject", "Git.CheckinProject") },
                Ui.button("Compose message…") { root?.let { AiCommitDialog(project, it).show() } },
            ), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        updateActions()
    }

    fun setChanges(root: Path, changes: List<WorkingChange>) {
        clear()
        this.root = root
        this.changes = changes
        summary.text = "${changes.size} files · ${changes.count { it.staged }} staged · ${changes.count { it.unstaged }} working · ${changes.count { it.conflicted }} conflicts"
        filter()
    }

    fun clear() {
        ++generation
        request?.cancel()
        root = null; changes = emptyList(); model.clear()
        summary.text = "Select a repository to inspect working changes."
        status.text = "Saved files on disk · select a file to inspect its diff"
        updateActions()
    }

    private fun filter() {
        val query = search.text.trim()
        val selected = files.selectedValue?.path
        val filtered = changes.filter {
            (query.isEmpty() || it.path.contains(query, true) || it.oldPath?.contains(query, true) == true) && when (scope.selectedIndex) {
                1 -> it.staged
                2 -> it.unstaged
                3 -> it.conflicted
                else -> true
            }
        }
        model.clear(); model.addAll(filtered.take(5_000))
        files.emptyText.text = if (changes.isEmpty()) "Working tree is clean." else "No changed files match this filter."
        if (filtered.size > 5_000) status.text = "Showing the first 5,000 matching files. Narrow the filter."
        else status.text = "Saved files on disk · double-click a file or press Enter"
        val index = (0 until model.size).firstOrNull { model[it].path == selected } ?: 0
        if (!model.isEmpty) files.selectedIndex = index
        updateActions()
    }

    private fun updateActions() {
        val value = files.selectedValue
        val supported = value != null && !value.conflicted && !value.submodule
        staged.isEnabled = supported && value.staged
        working.isEnabled = supported && value.unstaged
    }

    private fun openPreferred() {
        val value = files.selectedValue ?: return
        openDiff(!value.unstaged)
    }

    private fun openDiff(index: Boolean) {
        val path = root ?: return
        val change = files.selectedValue ?: return
        if (change.conflicted || change.submodule) {
            status.text = "Use Commit / stage… to inspect conflicts and submodules in the IDE."
            return
        }
        request?.cancel()
        val expected = ++generation
        status.text = "Loading ${if (index) "staged" else "working"} diff…"
        val service = WorkbenchService.getInstance(project)
        request = service.submit("Inspect working change", onSuccess = { diff ->
            if (disposed || generation != expected || root != path) return@submit
            val factory = DiffContentFactory.getInstance()
            val type = FileTypeManager.getInstance().getFileTypeByFileName(diff.path)
            val title = if (diff.before == diff.after) "${diff.path} · no text differences (check path or file permissions)" else diff.path
            DiffManager.getInstance().showDiff(project, SimpleDiffRequest(title,
                factory.create(project, diff.before, type), factory.create(project, diff.after, type), diff.beforeLabel, diff.afterLabel))
            status.text = if (diff.before == diff.after) "No text differences. File permissions or the path may have changed."
                else "Saved files on disk · select a file to inspect its diff"
        }, onFailure = {
            if (!disposed && generation == expected) status.text = it.message ?: "Could not read this diff. Refresh the file list."
        }) { service.git.workingFileDiff(path, change, index) }
    }

    override fun dispose() { disposed = true; clear() }

    private fun plain(text: String) = text.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")

    private inner class ChangeRenderer : ListCellRenderer<WorkingChange> {
        override fun getListCellRendererComponent(list: JList<out WorkingChange>, value: WorkingChange, index: Int, selected: Boolean, focus: Boolean): Component {
            val badge = when {
                value.conflicted -> "Conflict"
                value.untracked -> "New file"
                else -> listOfNotNull(if (value.staged) "Staged ${value.indexStatus}" else null,
                    if (value.unstaged) "Working ${value.workTreeStatus}" else null).joinToString("  ·  ")
            }
            return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                background = if (selected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(9, 12)
                toolTipText = "File: ${plain(value.path)}"
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(Ui.label(plain(value.path.substringAfterLast('/'))).apply {
                        font = font.deriveFont(Font.BOLD)
                        if (selected) foreground = list.selectionForeground
                    }, BorderLayout.NORTH)
                    add(Ui.label(plain(value.oldPath?.let { "$it → ${value.path}" }
                        ?: value.path.substringBeforeLast('/', "Repository root")), true).apply {
                        if (selected) foreground = list.selectionForeground
                    }, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                add(Ui.label(badge, true).apply {
                    if (selected) foreground = list.selectionForeground
                    else if (value.staged) foreground = DashboardStyles.accent
                }, BorderLayout.EAST)
            }
        }
    }
}
