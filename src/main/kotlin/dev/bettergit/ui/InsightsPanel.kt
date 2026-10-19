package dev.bettergit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.bettergit.core.reports.InsightsHistory
import dev.bettergit.core.reports.InsightsReport
import dev.bettergit.services.WorkbenchService
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class InsightsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private var root: Path? = null
    private var history: InsightsHistory? = null
    private var dayJob: Job? = null
    private var generation = 0L
    private var disposed = false
    private val title = Ui.heading("Repository insights").apply { font = font.deriveFont(Font.BOLD, font.size2D + 3) }
    private val status = paragraph("Select a repository to load its local commit activity.")
    private val coverage = paragraph("")
    private val calendarCaption = Ui.label("Last 365 days", true)
    private val calendar = ContributionHeatmap()
    private val charts = InsightsCharts()
    private val heroTotal = Ui.label("—").apply { font = font.deriveFont(Font.BOLD, font.size2D + 27) }
    private val heroDelta = DashboardStyles.pill("No comparison yet")
    private val heroPeriod = paragraph("The last 365 days, at a glance.")
    private val heroContext = paragraph("Load a repository to explore its activity.")
    private val activeDays = MetricCard("Active days", "—", "Days with local commits")
    private val currentStreak = MetricCard("Current streak", "—", "Through today or yesterday")
    private val longestStreak = MetricCard("Longest streak", "—", "Within the last 365 days")
    private val contributors = MetricCard("Contributors", "—", "Grouped by author email")
    private val contributorCaption = Ui.label("Top 10 by local commit count", true)
    private var contributorEmails = emptyList<String>()
    private val contributorModel = object : DefaultTableModel(arrayOf("Contributor", "Commits", "Share"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 1) Int::class.javaObjectType else String::class.java
    }
    private val contributorTable = object : JBTable(contributorModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            return if (row < 0) null else contributorEmails.getOrNull(convertRowIndexToModel(row))?.let { "Author email: $it" }
        }
    }.apply {
        setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
        tableHeader.reorderingAllowed = false
        rowHeight = JBUI.scale(32)
        setShowGrid(false)
        intercellSpacing = Dimension(JBUI.scale(10), 0)
        background = DashboardStyles.surface
        emptyText.text = "No contributor activity loaded."
        getAccessibleContext().accessibleName = "Top contributors by local commit count"
        columnModel.getColumn(1).preferredWidth = JBUI.scale(75)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(0).preferredWidth = JBUI.scale(270)
    }
    private val contributorScroll = JBScrollPane(contributorTable).apply {
        preferredSize = Dimension(JBUI.scale(430), JBUI.scale(150))
        border = JBUI.Borders.empty()
        viewport.background = DashboardStyles.surface
    }

    init {
        getAccessibleContext().accessibleName = "Repository insights"
        val content = VerticalContent().apply {
            border = JBUI.Borders.empty(20)
            add(sectionHeader())
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(hero())
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(MetricGrid(activeDays, currentStreak, longestStreak, contributors))
            add(Box.createVerticalStrut(JBUI.scale(20)))
            add(fillWidth(DashboardStyles.card(BorderLayout(0, JBUI.scale(12))).apply {
                add(Ui.row(Ui.heading("Commit calendar"), calendarCaption).apply { isOpaque = false }, BorderLayout.NORTH)
                add(JBScrollPane(calendar).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    preferredSize = Dimension(JBUI.scale(430), calendar.preferredSize.height + JBUI.scale(18))
                    getAccessibleContext().accessibleName = "Scrollable 365-day commit calendar"
                }, BorderLayout.CENTER)
                add(paragraph("Each square is a day. Hover for counts, select to explore its commits."), BorderLayout.SOUTH)
            }))
            add(Box.createVerticalStrut(JBUI.scale(18)))
            add(charts)
            add(Box.createVerticalStrut(JBUI.scale(18)))
            add(fillWidth(DashboardStyles.card(BorderLayout(0, JBUI.scale(12))).apply {
                add(Ui.row(Ui.heading("Top contributors"), contributorCaption).apply { isOpaque = false }, BorderLayout.NORTH)
                add(contributorScroll, BorderLayout.CENTER)
                add(paragraph("Counts describe local commits, not hosted contribution totals or developer productivity."), BorderLayout.SOUTH)
            }))
            add(Box.createVerticalStrut(JBUI.scale(14)))
            add(fillWidth(coverage))
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }, BorderLayout.CENTER)
    }

    fun setData(root: Path, history: InsightsHistory, report: InsightsReport) {
        if (disposed) return
        ++generation
        dayJob?.cancel()
        this.root = root
        this.history = history
        title.text = "${singleLine(root.fileName?.toString() ?: root.toString())} · Insights"
        val incomplete = report.truncated || report.shallow
        status.foreground = if (incomplete) JBColor.namedColor("Label.warningForeground", Ui.muted) else Ui.muted
        status.text = when {
            report.truncated && report.shallow -> "INCOMPLETE SNAPSHOT · History limit reached and repository is shallow."
            report.truncated -> "SAMPLED HISTORY · Limit of ${number(history.limit)} commits reached."
            report.shallow -> "INCOMPLETE HISTORY · This is a shallow clone."
            else -> "Local fetched heads, remotes and tags · activity by committer date"
        }
        val sampleQualifier = if (incomplete) "loaded " else ""
        coverage.text = "Coverage: ${history.startDate} – ${history.endDate} · ${number(history.entries.size)} ${sampleQualifier}records · ${history.zone.id}. " +
            if (incomplete) "Counts and streaks are lower bounds; empty days can contain commits outside this snapshot. Period delta is unavailable."
            else "Latest ${DATE_TIME.withZone(history.zone).format(history.capturedAt)} snapshot. Unfetched remote commits are outside this report."
        heroTotal.text = number(report.currentTotal)
        heroDelta.text = if (incomplete) "Incomplete snapshot" else delta(report)
        heroDelta.toolTipText = if (incomplete) "An exact period delta is unavailable." else
            "${number(report.currentTotal)} commits this period, ${number(report.previousTotal)} in the previous 365 days."
        heroPeriod.text = "${PERIOD_DATE.format(report.periodStart)} – ${PERIOD_DATE.format(report.periodEnd)}"
        heroContext.text = if (incomplete) "Loaded commits only · counts are lower bounds. Exact period comparison is unavailable."
            else "Compared with ${number(report.previousTotal)} commits in the previous 365 days · ${number(report.mergeCount)} merges included."
        activeDays.set("${number(report.activeDays)} / 365", if (incomplete) "Observed active days" else "${number(report.previousActiveDays)} in previous period")
        currentStreak.set("${report.currentStreak}${if (report.currentStreakAtWindowBoundary) "+" else ""} days",
            if (incomplete || report.currentStreakAtWindowBoundary) "Observed · lower bound" else "Active today or yesterday")
        longestStreak.set("${report.longestStreak} days", if (incomplete) "Observed · lower bound" else "Consecutive active days")
        contributors.set(number(report.contributors.size), if (incomplete) "Observed author identities" else "Grouped by author email")
        calendarCaption.text = "${report.periodStart} – ${report.periodEnd}"
        calendar.setIncompleteCoverage(incomplete)
        calendar.setData(report.periodStart, report.periodEnd, report.dailyActivity, ::openDay)
        charts.setData(report.weeklyTrend.map { it.weekStart to it.commits }, report.weekdayActivity, report.periodEnd)
        contributorCaption.text = if (incomplete) "Top 10 within the loaded sample" else "Top 10 by local commit count"
        contributorModel.rowCount = 0
        contributorEmails = report.contributors.take(10).map { singleLine(it.email) }
        report.contributors.take(10).forEach {
            val share = if (report.currentTotal == 0) "0%" else String.format(Locale.getDefault(), "%.1f%%", it.commits * 100.0 / report.currentTotal)
            contributorModel.addRow(arrayOf<Any>(singleLine(it.name), it.commits, share))
        }
        contributorTable.emptyText.text = "No commits in this 365-day period."
        contributorScroll.preferredSize = Dimension(JBUI.scale(430),
            contributorTable.rowHeight * report.contributors.size.coerceIn(1, 10) + JBUI.scale(30))
        getAccessibleContext().accessibleDescription = "${report.currentTotal} local commits; ${report.activeDays} active days. ${status.text} ${coverage.text}"
        revalidate(); repaint()
    }

    fun clear() {
        if (disposed) return
        ++generation
        dayJob?.cancel(); dayJob = null
        root = null; history = null
        title.text = "Repository insights"
        status.foreground = Ui.muted
        status.text = "Select a repository to load its local commit activity."
        coverage.text = ""
        heroTotal.text = "—"; heroDelta.text = "No comparison yet"
        heroPeriod.text = "The last 365 days, at a glance."
        heroContext.text = "Load a repository to explore its activity."
        listOf(activeDays, currentStreak, longestStreak, contributors).forEach { it.reset() }
        calendarCaption.text = "Last 365 days"
        calendar.clear(); charts.clear(); contributorModel.rowCount = 0; contributorEmails = emptyList()
        contributorTable.emptyText.text = "No contributor activity loaded."
        contributorScroll.preferredSize = Dimension(JBUI.scale(430), JBUI.scale(150))
        getAccessibleContext().accessibleDescription = status.text
        revalidate(); repaint()
    }

    fun showLoading() {
        if (disposed) return
        clear()
        status.text = "Loading two years of local commit history…"
        coverage.text = "Calendar and period comparison are calculated from the same repository snapshot."
        getAccessibleContext().accessibleDescription = status.text
    }

    fun showError(message: String) {
        if (disposed) return
        clear()
        status.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
        status.text = "Could not load insights. ${singleLine(message).take(600)}"
        coverage.text = "Refresh the repository to retry."
        getAccessibleContext().accessibleDescription = status.text
    }

    private fun openDay(day: LocalDate) {
        if (disposed || project.isDisposed) return
        val path = root ?: return
        val snapshot = history ?: return
        dayJob?.cancel()
        val requestGeneration = ++generation
        dayJob = WorkbenchService.getInstance(project).submit("Load day activity", onSuccess = { commits ->
            if (disposed || generation != requestGeneration || this.root != path || history !== snapshot) return@submit
            object : DialogWrapper(project, false) {
                init { title = "$day · ${commits.size} local commits"; setModal(false); init() }
                override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
                    preferredSize = Dimension(JBUI.scale(800), JBUI.scale(480))
                    border = JBUI.Borders.empty(8)
                    add(paragraph("Committer date · ${snapshot.zone.id}" +
                        if (snapshot.truncated || snapshot.shallow) " · Incomplete snapshot: additional commits may be missing." else ""), BorderLayout.NORTH)
                    add(HistoryPanel(project).apply { setCommits(path, commits) }, BorderLayout.CENTER)
                }
                override fun createActions() = arrayOf(okAction)
            }.show()
        }) { snapshot.commitsOn(day) }
    }

    override fun dispose() {
        disposed = true
        ++generation
        dayJob?.cancel(); dayJob = null
        root = null; history = null
    }

    private fun sectionHeader() = fillWidth(JPanel(BorderLayout(0, JBUI.scale(7))).apply {
        isOpaque = false
        add(title, BorderLayout.NORTH)
        add(status, BorderLayout.CENTER)
    })

    private fun hero() = fillWidth(DashboardStyles.card(BorderLayout(0, JBUI.scale(10)), emphasized = true).apply {
        border = JBUI.Borders.empty(18, 20)
        add(DashboardStyles.eyebrow("PAST 365 DAYS"), BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(Ui.row(heroTotal, Ui.label("commits", true), heroDelta).apply { isOpaque = false }, BorderLayout.NORTH)
            add(heroPeriod, BorderLayout.CENTER)
            add(heroContext, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    })

    private class MetricCard(private val caption: String, initial: String, private val initialDetail: String) : JPanel(BorderLayout()) {
        private val value = Ui.label(initial).apply { font = font.deriveFont(Font.BOLD, font.size2D + 8) }
        private val detail = paragraph(initialDetail)
        init {
            isOpaque = false
            add(DashboardStyles.card(BorderLayout(0, JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(13)
                add(Ui.label(caption, true), BorderLayout.NORTH)
                add(value, BorderLayout.CENTER)
                add(detail, BorderLayout.SOUTH)
            })
            getAccessibleContext().accessibleName = caption
        }
        override fun getPreferredSize() = Dimension(JBUI.scale(160), JBUI.scale(111))
        fun set(text: String, description: String) {
            value.text = text; detail.text = description; detail.toolTipText = description
            getAccessibleContext().accessibleDescription = "$caption: $text. $description"
        }
        fun reset() = set("—", initialDetail)
    }

    private class MetricGrid(vararg cards: JComponent) : JPanel(null) {
        init {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            cards.forEach(::add)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) { revalidate() }
            })
        }
        private fun columns(available: Int) = when {
            available >= JBUI.scale(640) -> 4
            available >= JBUI.scale(280) -> 2
            else -> 1
        }
        override fun getPreferredSize(): Dimension {
            val available = if (width > 0) width else parent?.width?.takeIf { it > 0 } ?: JBUI.scale(640)
            val rows = (componentCount + columns(available) - 1) / columns(available)
            return Dimension(available, rows * JBUI.scale(111) + (rows - 1).coerceAtLeast(0) * JBUI.scale(10))
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun doLayout() {
            val columns = columns(width)
            val gap = JBUI.scale(10)
            val cellWidth = ((width - (columns - 1) * gap) / columns).coerceAtLeast(1)
            components.forEachIndexed { index, component ->
                component.setBounds(index % columns * (cellWidth + gap), index / columns * (JBUI.scale(111) + gap), cellWidth, JBUI.scale(111))
            }
        }
    }

    private class VerticalContent : JPanel(), Scrollable {
        init { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        override fun getPreferredScrollableViewportSize() = Dimension(JBUI.scale(470), JBUI.scale(700))
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(18)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) (visibleRect.height - JBUI.scale(18)).coerceAtLeast(JBUI.scale(18)) else visibleRect.width
    }

    companion object {
        private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
        private val PERIOD_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
        private fun number(value: Int) = NumberFormat.getIntegerInstance().format(value)
        private fun singleLine(text: String) = text.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
        private fun delta(report: InsightsReport): String {
            val prefix = if (report.delta > 0) "+" else ""
            val count = "$prefix${number(report.delta)} commits"
            return report.deltaPercent?.let { String.format(Locale.getDefault(), "%+.1f%%", it) } ?: count
        }
        private fun paragraph(text: String): JBTextArea = JBTextArea(text).apply {
            isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
            font = Ui.label("").font; foreground = Ui.muted
            border = JBUI.Borders.empty()
            getAccessibleContext().accessibleName = "Insight coverage and context"
        }
        private fun fillWidth(component: JComponent): JPanel = object : JPanel(BorderLayout()) {
            init { isOpaque = false; alignmentX = LEFT_ALIGNMENT; add(component, BorderLayout.CENTER) }
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }
}
