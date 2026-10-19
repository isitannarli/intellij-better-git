package dev.bettergit.ui

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.git.LineRange
import dev.bettergit.core.git.SelectionHistory
import dev.bettergit.core.git.SelectionHistoryEntry
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

internal class SelectionHistoryPanel @JvmOverloads constructor(
    private val project: Project,
    private val root: Path,
    private val path: String,
    private val range: LineRange,
    onRetry: () -> Unit = {},
    private val schemeProvider: () -> EditorColorsScheme = { EditorColorsManager.getInstance().globalScheme },
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private data class Row(val entry: SelectionHistoryEntry?, val patch: String) {
        val commit: GitCommit? get() = entry?.commit
        val local: Boolean get() = entry == null
        val title: String get() = commit?.subject ?: "Local snapshot"
    }

    private var rows = emptyList<Row>()
    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val search = JBTextField().apply {
        emptyText.text = "Search author, message, SHA or date"
        getAccessibleContext().accessibleName = "Search selected code history"
    }
    val preferredFocus: JComponent get() = search
    private val commitCount = DashboardStyles.pill("… commits")
    private val authorCount = DashboardStyles.pill("… authors")
    private val resultCount = Ui.label("Reading history…", true)
    private val context = Ui.label("Editor lines ${range.start}–${range.end}", true)
    private val notices = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        font = UIUtil.getLabelFont()
        getAccessibleContext().accessibleName = "Selection history scope and limitations"
    }
    private val progress = JProgressBar().apply {
        isIndeterminate = true
        preferredSize = Dimension(JBUI.scale(100), JBUI.scale(3))
        getAccessibleContext().accessibleName = "Loading selection history"
    }
    private val retry = Ui.button("Retry", "Read the same captured editor selection again", onRetry).apply { isVisible = false }
    private val detailTitle = Ui.label("Reading selected code history…").apply { font = font.deriveFont(Font.BOLD, font.size2D + 1) }
    private val detailMeta = Ui.label("Local Git data is loading.", true)
    private val detailKind = DashboardStyles.pill("SELECTION")
    private val additions = Ui.label("", true)
    private val copy = Ui.button("Copy SHA", "Copy the full hash of this commit") {
        list.selectedValue?.commit?.let {
            CopyPasteManager.getInstance().setContents(StringSelection(it.hash))
            copyFeedback.text = "Commit hash copied"
        }
    }.apply { isEnabled = false }
    private val inspect = Ui.button("Inspect commit…", "Open this commit's complete file list") { inspectSelected() }
        .apply { isEnabled = false }
    private val copyFeedback = Ui.label("", true)
    private val patch = object : JTextPane(DefaultStyledDocument()) {
        override fun getScrollableTracksViewportWidth() = false
    }.apply {
        isEditable = false
        border = JBUI.Borders.empty(12)
        getAccessibleContext().accessibleName = "Selected lines patch"
        getAccessibleContext().accessibleDescription = "Read-only patch. Added lines start with plus; removed lines start with minus. Text can be selected and copied."
    }
    private val patchScroll = JBScrollPane(patch)
    private val patchScope = Ui.label("Patch affecting the selected lines", true)

    init {
        preferredSize = Dimension(JBUI.scale(1100), JBUI.scale(720))
        minimumSize = Dimension(JBUI.scale(760), JBUI.scale(520))
        border = JBUI.Borders.empty(10)
        getAccessibleContext().accessibleName = "Selected code history"
        add(DashboardStyles.card(BorderLayout(0, JBUI.scale(8))).apply {
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(DashboardStyles.eyebrow("SELECTED CODE HISTORY"), BorderLayout.NORTH)
                    add(Ui.heading(clean(path)).apply { toolTipText = "File: ${clean(path)}" }, BorderLayout.CENTER)
                    add(context, BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
                add(Ui.row(commitCount, authorCount).apply { isOpaque = false }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(notices, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(progress, BorderLayout.CENTER)
                add(retry, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = list.getFontMetrics(UIUtil.getLabelFont()).height * 3 + JBUI.scale(30)
        list.cellRenderer = EntryRenderer()
        list.getAccessibleContext().accessibleName = "Commits affecting the selected code"
        list.addListSelectionListener { if (!it.valueIsAdjusting) displaySelected() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2) inspectSelected()
            }
        })
        bind(list, "ENTER") { inspectSelected() }
        val historyList = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            minimumSize = Dimension(JBUI.scale(220), JBUI.scale(180))
            border = JBUI.Borders.emptyRight(12)
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
            add(resultCount, BorderLayout.SOUTH)
        }
        val details = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            minimumSize = Dimension(JBUI.scale(340), JBUI.scale(180))
            border = JBUI.Borders.emptyLeft(12)
            add(DashboardStyles.card(BorderLayout(0, JBUI.scale(8)), emphasized = true).apply {
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(DashboardStyles.eyebrow("SELECTED CHANGE"), BorderLayout.WEST)
                    add(detailKind, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                    isOpaque = false
                    add(detailTitle, BorderLayout.NORTH)
                    add(detailMeta, BorderLayout.CENTER)
                    add(additions, BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
                add(Ui.row(copy, inspect, copyFeedback).apply { isOpaque = false }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(patchScroll, BorderLayout.CENTER)
            add(patchScope, BorderLayout.SOUTH)
        }
        add(OnePixelSplitter(false, 0.32f).apply {
            firstComponent = historyList
            secondComponent = details
        }, BorderLayout.CENTER)
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = filter()
        })
        search.addActionListener { if (!model.isEmpty) { list.requestFocusInWindow(); if (list.selectedIndex < 0) list.selectedIndex = 0 } }
        bind(this, "control F", JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) { search.requestFocusInWindow(); search.selectAll() }
        bind(this, "meta F", JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) { search.requestFocusInWindow(); search.selectAll() }
        showLoading()
    }

    fun showLoading() {
        rows = emptyList()
        model.clear()
        search.isEnabled = false
        retry.isVisible = false
        progress.isVisible = true
        progress.isIndeterminate = true
        commitCount.text = "… commits"
        authorCount.text = "… authors"
        notices.foreground = Ui.muted
        notices.text = "Reading the captured editor selection from local Git. You can keep working while this window loads."
        list.emptyText.text = "Reading selected code history…"
        resultCount.text = "Reading history…"
        showPlaceholder("Reading selected code history…", "The editor snapshot was captured when this window opened.")
    }

    fun showHistory(history: SelectionHistory) {
        stopLoading()
        search.isEnabled = true
        retry.isVisible = false
        rows = buildList {
            if (history.localPatch.isNotBlank()) add(Row(null, history.localPatch))
            history.entries.forEach { add(Row(it, it.patch)) }
        }
        val authors = history.entries.map { it.commit.email.ifBlank { it.commit.author }.lowercase(Locale.ROOT) }.distinct().size
        commitCount.text = "${history.entries.map { it.commit.hash }.distinct().size} commits loaded"
        authorCount.text = "$authors ${if (authors == 1) "author" else "authors"} shown"
        context.text = "Editor lines ${history.selectedRange.start}–${history.selectedRange.end}" +
            history.headRange?.let { "  ·  HEAD lines ${it.start}–${it.end}" }.orEmpty()
        notices.foreground = Ui.muted
        notices.text = buildList {
            add("Snapshot of the selection when this window opened. Git follows line changes; content-neutral merges and renames may not appear.")
            if (history.localPatch.isNotBlank()) add("Local snapshot contains uncommitted editor changes; it has no commit author or timestamp.")
            else if (history.hasLocalChanges) add("The editor snapshot differs from HEAD. Historical entries describe committed versions.")
            if (history.headRange == null) add("These selected lines have no committed mapping at HEAD.")
            if (history.mappingApproximate) add("The selection overlaps a changed block. History follows the whole original block, so the HEAD mapping is approximate.")
            if (history.shallow) add("This is a shallow repository. Older commits may be unavailable.")
            if (history.truncated) add("The history limit was reached. Earlier commits may exist.")
        }.joinToString("\n")
        filter()
    }

    fun setData(history: SelectionHistory) = showHistory(history)

    fun showFailure(message: String) {
        stopLoading()
        rows = emptyList()
        model.clear()
        search.isEnabled = false
        retry.isVisible = true
        commitCount.text = "— commits"
        authorCount.text = "— authors"
        notices.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
        notices.text = clean(message).take(1200)
        list.emptyText.text = "History could not be loaded."
        resultCount.text = "Read failed"
        showPlaceholder("Couldn't load this selection", "Retry reads the same captured selection. Close and reopen to capture new editor changes.")
    }

    fun stopLoading() { progress.isIndeterminate = false; progress.isVisible = false }

    fun refreshTheme() {
        list.fixedCellHeight = list.getFontMetrics(UIUtil.getLabelFont()).height * 3 + JBUI.scale(30)
        list.repaint()
        displaySelected()
    }

    private fun filter() {
        val selected = list.selectedValue
        val query = search.text.trim()
        val filtered = rows.filter { row ->
            query.isEmpty() || if (row.local) "Local snapshot uncommitted editor changes".contains(query, true)
            else row.commit!!.let { commit ->
                listOf(commit.subject, commit.author, commit.email, commit.hash, DATE.format(commit.timestamp), commit.timestamp.toString())
                    .any { it.contains(query, true) }
            }
        }
        model.clear()
        filtered.forEach(model::addElement)
        list.emptyText.text = if (rows.isEmpty()) "No commits touch these selected lines." else "No changes match this search."
        resultCount.text = "${filtered.size} of ${rows.size} changes" + if (rows.any { it.local }) " · includes local snapshot" else ""
        if (filtered.isNotEmpty()) list.selectedIndex = filtered.indexOf(selected).takeIf { it >= 0 } ?: 0
        else showPlaceholder(if (rows.isEmpty()) "No committed history for this selection" else "No matching changes",
            if (rows.isEmpty()) "The selected code may be new, untracked, or outside the available repository history." else "Try another author, message, SHA or date.")
    }

    private fun displaySelected() {
        val row = list.selectedValue ?: return
        val commit = row.commit
        copyFeedback.text = ""
        copy.isEnabled = commit != null
        inspect.isEnabled = commit != null
        detailKind.text = if (row.local) "LOCAL SNAPSHOT" else "COMMIT"
        detailTitle.text = clean(row.title)
        detailTitle.toolTipText = "Change: ${clean(row.title)}"
        detailMeta.text = if (commit == null) "Uncommitted editor contents · no commit identity"
            else "${clean(commit.author)}  ·  ${DATE.format(commit.timestamp)}  ·  ${commit.hash.take(10)}"
        detailMeta.toolTipText = "Details: ${detailMeta.text}"
        val classifier = PatchClassifier()
        var added = 0
        var removed = 0
        row.patch.lineSequence().forEach { line ->
            when (classifier.classify(line)) {
                PatchStyle.ADDED -> added++
                PatchStyle.REMOVED -> removed++
                else -> Unit
            }
        }
        additions.text = "+$added added  ·  −$removed removed  ·  selection context"
        renderPatch(row.patch)
    }

    private fun renderPatch(text: String) {
        val scheme = schemeProvider()
        patch.font = scheme.getFont(EditorFontType.PLAIN)
        patch.background = scheme.defaultBackground
        patch.foreground = scheme.defaultForeground
        patch.caretColor = scheme.defaultForeground
        patch.selectionColor = UIUtil.getListSelectionBackground(true)
        patch.selectedTextColor = UIUtil.getListSelectionForeground(true)
        patchScroll.viewport.background = scheme.defaultBackground
        val plain = SimpleAttributeSet().apply { StyleConstants.setForeground(this, scheme.defaultForeground) }
        val inserted = SimpleAttributeSet(plain).apply {
            StyleConstants.setBackground(this, scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor ?: JBColor(Color(0xE5F3E9), Color(0x294535)))
        }
        val removed = SimpleAttributeSet(plain).apply {
            StyleConstants.setBackground(this, scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor ?: JBColor(Color(0xF9E5E5), Color(0x513335)))
        }
        val header = SimpleAttributeSet(plain).apply {
            StyleConstants.setForeground(this, Ui.muted)
            StyleConstants.setBold(this, true)
        }
        val document = DefaultStyledDocument()
        var clipped = false
        var count = 0
        var characters = 0
        val classifier = PatchClassifier()
        for (raw in text.lineSequence()) {
            if (count >= MAX_LINES || characters >= MAX_CHARACTERS) { clipped = true; break }
            val available = (MAX_CHARACTERS - characters).coerceAtMost(MAX_LINE_LENGTH)
            val line = clean(raw.take(available + 1), keepTabs = true).let {
                if (it.length > available) { clipped = true; it.take(available) + " …" } else it
            }
            val attributes = when (classifier.classify(raw)) {
                PatchStyle.HEADER -> header
                PatchStyle.ADDED -> inserted
                PatchStyle.REMOVED -> removed
                PatchStyle.PLAIN -> plain
            }
            document.insertString(document.length, line + "\n", attributes)
            characters += line.length + 1
            count++
        }
        patch.document = document
        patch.caretPosition = 0
        patchScroll.viewport.viewPosition = Point(0, 0)
        patchScope.text = if (clipped) "Preview limited to 2,000 lines / 180,000 characters; long lines are shortened."
            else "Patch affecting the selection · green + added, red − removed · context may extend beyond selected lines"
        if (text.isBlank()) {
            document.insertString(0, "No textual patch is available for this selected change.", plain)
            patchScope.text = "No textual patch"
        }
    }

    private fun showPlaceholder(title: String, explanation: String) {
        copy.isEnabled = false
        inspect.isEnabled = false
        detailKind.text = "SELECTION"
        detailTitle.text = title
        detailTitle.toolTipText = null
        detailMeta.text = explanation
        detailMeta.toolTipText = "Details: $explanation"
        additions.text = ""
        copyFeedback.text = ""
        renderPatch("")
    }

    private fun inspectSelected() {
        val commit = list.selectedValue?.commit
        if (commit != null) CommitDetails.show(project, root, commit) else patch.requestFocusInWindow()
    }

    private enum class PatchStyle { HEADER, ADDED, REMOVED, PLAIN }

    /** Hunk state distinguishes source lines from identical diff-header prefixes. */
    private class PatchClassifier {
        private var inHunk = false
        fun classify(line: String): PatchStyle {
            if (line.startsWith("diff --git ")) { inHunk = false; return PatchStyle.HEADER }
            if (line.startsWith("@@")) { inHunk = true; return PatchStyle.HEADER }
            if (inHunk) return when {
                line.startsWith('+') -> PatchStyle.ADDED
                line.startsWith('-') -> PatchStyle.REMOVED
                else -> PatchStyle.PLAIN
            }
            return if (line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("index ")) PatchStyle.HEADER else PatchStyle.PLAIN
        }
    }

    private inner class EntryRenderer : ListCellRenderer<Row> {
        override fun getListCellRendererComponent(list: JList<out Row>, value: Row, index: Int, selected: Boolean, focus: Boolean): Component =
            JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                background = if (selected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(10, 12)
                val foregroundColor = if (selected) list.selectionForeground else list.foreground
                val secondary = if (selected) list.selectionForeground else Ui.muted
                add(Ui.label(clean(value.title)).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = foregroundColor
                }, BorderLayout.NORTH)
                add(Ui.label(value.commit?.let { clean(it.author) } ?: "Uncommitted editor contents").apply {
                    foreground = secondary
                }, BorderLayout.CENTER)
                add(Ui.label(value.commit?.let { "${it.hash.take(8)}  ·  ${DATE.format(it.timestamp)}" } ?: "LOCAL · no author or timestamp").apply {
                    foreground = secondary
                    font = font.deriveFont((font.size2D - 1).coerceAtLeast(10f))
                }, BorderLayout.SOUTH)
                getAccessibleContext().accessibleName = if (value.local) "Local snapshot, uncommitted editor changes"
                    else "${clean(value.title)}, ${clean(value.commit!!.author)}, ${value.commit!!.hash.take(8)}"
            }
    }

    private fun bind(component: JComponent, key: String, condition: Int = JComponent.WHEN_FOCUSED, run: () -> Unit) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), "selection-history-$key")
        component.actionMap.put("selection-history-$key", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = run()
        })
    }

    companion object {
        private const val MAX_LINES = 2000
        private const val MAX_CHARACTERS = 180_000
        private const val MAX_LINE_LENGTH = 4000
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
        private val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}\\p{Cf}]")
        private val CONTROL_EXCEPT_TABS = Regex("[\\p{Cntrl}&&[^\\t]]|\\p{Cf}")
        private fun clean(value: String, keepTabs: Boolean = false): String =
            value.replace(if (keepTabs) CONTROL_EXCEPT_TABS else CONTROL_CHARACTERS, " ")
    }
}
