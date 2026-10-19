package dev.bettergit.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.CommitFilePreview
import dev.bettergit.core.git.CommitInspection
import dev.bettergit.core.git.FileChange
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

internal class CommitInspectorPanel(preview: JComponent, private val listener: Listener) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    interface Listener {
        fun select(change: FileChange?)
        fun openDiff()
        fun openCommit()
        fun openReviews()
        fun copyHash()
    }

    private var data: CommitInspection? = null
    private var refreshing = false
    private val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply { isOpaque = false }
    private val messageDetails = JPanel(BorderLayout()).apply { isOpaque = false; isVisible = false }
    private val messageToggle = JToggleButton("Details ▾").apply {
        toolTipText = "Show the full commit message"
        getAccessibleContext().accessibleName = "Show full commit message"
        margin = JBUI.insets(2, 7)
        addActionListener { toggleMessage() }
    }
    private val model = DefaultListModel<FileChange>()
    private val files = object : JBList<FileChange>(model) {
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "Loading changed files…"
        accessibleContext.accessibleName = "Changed files; use arrow keys to preview"
    }
    private val search = JBTextField().apply {
        emptyText.text = "Filter files by name or path…"
        accessibleContext.accessibleName = "Filter changed files"
    }
    private val filter = JComboBox(arrayOf("All changes", "Added", "Modified", "Deleted", "Renamed", "Other")).apply {
        accessibleContext.accessibleName = "Filter by change type"
    }
    private val count = Ui.label("Loading…", true)
    private val previewTitle = Ui.label("Commit preview").apply { font = font.deriveFont(Font.BOLD) }
    private val previewMeta = Ui.label("Select a file to inspect its changes", true)
    private val cards = CardLayout()
    private val previewBody = JPanel(cards)
    private val stateTitle = Ui.heading("Loading commit…")
    private val stateText = textArea("Reading local Git history.", 4).apply { foreground = Ui.muted }
    private val retry = Ui.button("Retry preview") { listener.select(files.selectedValue) }.apply { isVisible = false }
    private val open = Ui.button("Open diff ↗", "Open the selected diff in a separate IDE window (Enter)") { listener.openDiff() }
    val preferredFocus: JComponent get() = search

    init {
        preferredSize = Dimension(JBUI.scale(1180), JBUI.scale(700))
        minimumSize = Dimension(JBUI.scale(760), JBUI.scale(460))
        border = JBUI.Borders.empty(8)
        add(header, BorderLayout.NORTH)
        val filePanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.emptyRight(10)
            minimumSize = Dimension(JBUI.scale(230), 0)
            add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                isOpaque = false
                add(Ui.row(Ui.heading("Changed files"), count), BorderLayout.NORTH)
                add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(search, BorderLayout.CENTER)
                    add(filter, BorderLayout.EAST)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JBScrollPane(files).apply {
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            add(Ui.label("↑ ↓ Preview   ·   Enter Open diff", true), BorderLayout.SOUTH)
        }
        val previewPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            minimumSize = Dimension(JBUI.scale(360), 0)
            add(JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(previewTitle, BorderLayout.CENTER)
                    add(open, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(previewMeta, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(previewBody, BorderLayout.CENTER)
        }
        previewBody.add(preview, "diff")
        previewBody.add(DashboardStyles.card(BorderLayout(0, JBUI.scale(12))).apply {
            add(stateTitle, BorderLayout.NORTH)
            add(stateText, BorderLayout.CENTER)
            add(Ui.row(retry), BorderLayout.SOUTH)
        }, "message")
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, previewPanel).apply {
            border = JBUI.Borders.empty()
            resizeWeight = 0.28
            dividerLocation = JBUI.scale(325)
            isContinuousLayout = true
        }, BorderLayout.CENTER)
        files.cellRenderer = FileRowRenderer()
        fun sizeFileRows() { files.fixedCellHeight = maxOf(JBUI.scale(26), files.getFontMetrics(files.font).height + JBUI.scale(8)) }
        sizeFileRows()
        files.addPropertyChangeListener("font") { sizeFileRows() }
        files.addListSelectionListener {
            if (!it.valueIsAdjusting && !refreshing) selectionChanged()
        }
        files.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && open.isEnabled) { e.consume(); listener.openDiff() }
            }
        })
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && open.isEnabled && files.locationToIndex(e.point).let { it >= 0 && files.getCellBounds(it, it).contains(e.point) }) listener.openDiff()
            }
        })
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filterFiles()
        })
        search.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN && !model.isEmpty) { e.consume(); files.requestFocusInWindow(); if (files.selectedIndex < 0) files.selectedIndex = 0 }
            }
        })
        filter.addActionListener { filterFiles() }
        open.isEnabled = false
        cards.show(previewBody, "message")
    }

    fun setData(inspection: CommitInspection, repository: String, initialPath: String? = null) {
        data = inspection
        header.removeAll()
        val commit = inspection.commit
        messageToggle.isSelected = false
        messageToggle.text = "Details ▾"
        messageToggle.toolTipText = "Show the full commit message"
        messageToggle.getAccessibleContext().accessibleDescription = "Collapsed"
        messageToggle.isVisible = inspection.message.trim() != commit.subject.trim()
        messageDetails.removeAll()
        messageDetails.isVisible = false
        val details = DashboardStyles.card(BorderLayout(0, JBUI.scale(4)), true).apply {
            border = JBUI.Borders.empty(8, 10)
        }
        val subject = Ui.label(commit.subject.ifBlank { "(No commit message)" }).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1)
            toolTipText = "Commit: ${commit.subject}"
        }
        val copy = Ui.button(commit.hash.take(8), "Copy full commit hash") { listener.copyHash() }.apply {
            font = font.deriveFont(Font.BOLD)
            foreground = DashboardStyles.accent
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(Ui.button("Open commit ↗") { listener.openCommit() })
            add(Ui.button("Open PR / MR ↗") { listener.openReviews() })
            add(messageToggle)
        }
        val titleRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(copy, BorderLayout.WEST)
            add(subject, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
        val date = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm").withZone(ZoneId.systemDefault()).format(commit.timestamp)
        val comparison = when {
            commit.parents.size > 1 -> "Merge · first parent"
            commit.parents.isEmpty() -> "Root commit · empty tree"
            else -> "First parent"
        }
        val metadata = Ui.label("${commit.author} · $date · $repository · $comparison", true).apply {
            toolTipText = "Author: ${commit.author} · ${commit.email} · ${commit.timestamp.atZone(ZoneId.systemDefault())} · Repository: $repository · $comparison"
        }
        details.add(titleRow, BorderLayout.NORTH)
        details.add(metadata, BorderLayout.CENTER)
        details.add(messageDetails, BorderLayout.SOUTH)
        header.add(details, BorderLayout.CENTER)
        val stats = inspection.stats.values
        header.add(Ui.row(
            DashboardStyles.pill("${inspection.changes.size} files"),
            colored("+${stats.sumOf { it.added ?: 0 }} additions", 'A'),
            colored("−${stats.sumOf { it.removed ?: 0 }} deletions", 'D'),
            Ui.label("${stats.count { it.binary }} binary · ${inspection.changes.count { it.oldPath != null }} renamed", true),
        ), BorderLayout.SOUTH)
        filterFiles(initialPath)
        revalidate(); repaint()
    }

    fun showMessage(title: String, message: String, retryable: Boolean = false) {
        stateTitle.text = title
        stateText.text = message
        stateText.caretPosition = 0
        retry.isVisible = retryable
        open.isEnabled = false
        cards.show(previewBody, "message")
        previewBody.revalidate(); previewBody.repaint()
    }

    fun showPreview(preview: CommitFilePreview) {
        val kind = when {
            preview.before.mode == "160000" || preview.after.mode == "160000" -> "Submodule references"
            preview.before.mode == "120000" || preview.after.mode == "120000" -> "Symlink · ${preview.before.size} B → ${preview.after.size} B"
            data?.stats?.get(preview.change.path)?.binary == true -> "Binary · ${preview.before.size} B → ${preview.after.size} B"
            else -> "${preview.before.size} B → ${preview.after.size} B"
        }
        previewMeta.text = "$kind  ·  ${preview.commit.parents.firstOrNull()?.take(8) ?: "Empty tree"} → ${preview.commit.hash.take(8)}" +
            if (preview.before.mode != null && preview.after.mode != null && preview.before.mode != preview.after.mode) "  ·  mode ${preview.before.mode} → ${preview.after.mode}" else ""
        open.isEnabled = true
        cards.show(previewBody, "diff")
    }

    private fun filterFiles(initialPath: String? = null) {
        val inspection = data ?: return
        val previous = initialPath ?: files.selectedValue?.path
        val query = search.text.trim()
        val status = when (filter.selectedIndex) { 1 -> 'A'; 2 -> 'M'; 3 -> 'D'; 4 -> 'R'; else -> null }
        val matches = inspection.changes.filter { change ->
            (change.path.contains(query, true) || change.oldPath?.contains(query, true) == true) &&
                (status?.let { change.status.startsWith(it) } ?: (filter.selectedIndex != 5 || change.status.first() !in "AMDR"))
        }
        refreshing = true
        try {
            model.clear(); model.addAll(matches)
            files.emptyText.text = if (inspection.changes.isEmpty()) "No file changes in this comparison." else "No files match these filters."
            if (matches.isNotEmpty()) files.selectedIndex = matches.indexOfFirst { it.path == previous }.takeIf { it >= 0 } ?: 0
            count.text = "${matches.size} / ${inspection.changes.size}"
        } finally { refreshing = false }
        selectionChanged()
    }

    private fun selectionChanged() {
        val selected = files.selectedValue
        previewTitle.text = selected?.path ?: "Commit preview"
        previewTitle.toolTipText = selected?.let { it.oldPath?.let { old -> "$old → ${it.path}" } ?: it.path }
        previewMeta.text = selected?.let { changeName(it.status.first()) + (it.oldPath?.let { old -> " · from $old" } ?: "") } ?: "Select a file to inspect its changes"
        if (selected == null) showMessage("No file selected", "Choose a file from the list, or clear the filters to see more changes.")
        else showMessage("Loading preview…", "Reading the selected file from this commit.")
        listener.select(selected)
    }

    private fun toggleMessage() {
        val expanded = messageToggle.isSelected
        if (expanded && messageDetails.componentCount == 0) {
            val message = textArea(data?.message.orEmpty(), 4).apply {
                caretPosition = 0
                accessibleContext.accessibleName = "Full commit message"
            }
            messageDetails.add(JBScrollPane(message).apply {
                border = JBUI.Borders.emptyTop(6)
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }
        messageDetails.isVisible = expanded
        messageToggle.text = if (expanded) "Details ▴" else "Details ▾"
        messageToggle.toolTipText = if (expanded) "Hide the full commit message" else "Show the full commit message"
        messageToggle.getAccessibleContext().accessibleDescription = if (expanded) "Expanded" else "Collapsed"
        revalidate(); repaint()
    }

    private inner class FileRowRenderer : JPanel(BorderLayout(JBUI.scale(7), 0)), ListCellRenderer<FileChange> {
        private val status = Ui.label("").apply { preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18)) }
        private val path = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
        private val stats = Ui.label("", true)

        init {
            border = JBUI.Borders.empty(3, 8)
            add(status, BorderLayout.WEST)
            add(path, BorderLayout.CENTER)
            add(stats, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(list: JList<out FileChange>, change: FileChange, index: Int, selected: Boolean, focus: Boolean): Component {
            background = if (selected) list.selectionBackground else list.background
            val text = if (selected) list.selectionForeground else list.foreground
            val secondary = if (selected) list.selectionForeground else Ui.muted
            status.text = change.status.take(1)
            status.font = list.font.deriveFont(Font.BOLD)
            status.foreground = changeColor(change.status.first())
            path.clear()
            path.font = list.font
            path.append(change.path.substringAfterLast('/'), SimpleTextAttributes(Font.BOLD, text))
            change.path.substringBeforeLast('/', "").takeIf(String::isNotEmpty)?.let {
                path.append("  $it", SimpleTextAttributes(Font.PLAIN, secondary))
            }
            val numbers = data?.stats?.get(change.path)
            stats.text = numbers?.let { if (it.binary) "Binary" else "+${it.added} −${it.removed}" }.orEmpty()
            stats.foreground = secondary
            stats.font = list.font
            stats.isVisible = numbers != null
            toolTipText = "${changeName(change.status.first())}: ${change.oldPath?.let { "$it → " } ?: ""}${change.path}"
            getAccessibleContext().accessibleName = toolTipText
            // Undisplayed renderer trees defer validation; recalculate bounds for each file.
            stats.invalidate()
            invalidate()
            return this
        }
    }

    companion object {
        private fun textArea(text: String, rows: Int) = JTextArea(text, rows, 0).apply {
            isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
            font = Ui.label("").font
        }
        private fun colored(text: String, status: Char) = Ui.label(text).apply { foreground = changeColor(status) }
        private fun changeName(status: Char) = when (status) {
            'A' -> "Added"; 'D' -> "Deleted"; 'R' -> "Renamed"; 'C' -> "Copied"; 'T' -> "Type changed"; else -> "Modified"
        }
        private fun changeColor(status: Char): Color = when (status) {
            'A' -> JBColor.namedColor("VersionControl.FileStatus.ADDED", JBColor(Color(0x267541), Color(0x79C78A)))
            'D' -> JBColor.namedColor("VersionControl.FileStatus.DELETED", JBColor(Color(0xB32636), Color(0xF08790)))
            'R', 'C' -> JBColor.namedColor("VersionControl.FileStatus.RENAMED", JBColor(Color(0x8A6300), Color(0xE5BD70)))
            else -> JBColor.namedColor("VersionControl.FileStatus.MODIFIED", JBColor(Color(0x245FB0), Color(0x86B6F6)))
        }
    }
}
