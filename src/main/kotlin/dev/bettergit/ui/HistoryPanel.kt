package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.CommitGraph
import dev.bettergit.core.graph.GraphRow
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class HistoryPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private var root: Path? = null
    private var commits = emptyList<GitCommit>()
    private val model = DefaultListModel<GitCommit>()
    private val list = JBList(model)
    private val search = JBTextField().apply { emptyText.text = "Search commit, author or SHA" }
    private val summary = JBLabel("Select a commit to inspect its changed files.")
    private var graph = emptyMap<String, GraphRow>()

    init {
        border = JBUI.Borders.empty(12)
        add(search, BorderLayout.NORTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No commits yet."
        list.cellRenderer = CommitGraphRenderer { if (search.text.isBlank()) graph else emptyMap() }
        fun sizeRows() { list.fixedCellHeight = maxOf(JBUI.scale(52), list.getFontMetrics(list.font).height * 2 + JBUI.scale(16)) }
        sizeRows()
        list.addPropertyChangeListener("font") { sizeRows() }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) summary.text = list.selectedValue?.let { c -> "${c.hash.take(8)} · ${c.author}" }
                ?: "Select a commit to inspect its changed files."
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openSelected() }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) openSelected() }
        })
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(summary, BorderLayout.NORTH)
            add(Ui.row(
                Ui.button("Inspect commit") { openSelected() },
                Ui.button("Commit graph ↗") {
                    com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Better Git Graph")?.activate(null)
                },
                Ui.button("Advanced Git log", "Open the IDE's Git log for rebase, cherry-pick and comparisons") {
                    Ui.nativeAction(project, this@HistoryPanel, "ActivateVersionControlToolWindow")
                },
            ), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { filter() }
        })
    }

    @JvmOverloads
    fun setCommits(root: Path, commits: List<GitCommit>, preparedGraph: Map<String, GraphRow>? = null) {
        this.root = root
        this.commits = commits
        graph = preparedGraph ?: commits.map(GitCommit::hash).zip(CommitGraph.layout(commits)).toMap()
        filter()
    }
    fun clear() {
        commits = emptyList(); graph = emptyMap(); root = null; model.clear()
        summary.text = "Select a commit to inspect its changed files."
    }
    private fun filter() {
        val query = search.text.trim()
        val selectedHash = list.selectedValue?.hash
        // Hide edges across filtered-out commits to avoid false ancestry.
        val matches = if (query.isEmpty()) commits else commits.filter {
            it.subject.contains(query, true) || it.author.contains(query, true) ||
                it.hash.contains(query, true) || it.decorations.contains(query, true)
        }
        model.clear()
        model.addAll(matches)
        if (selectedHash != null) list.selectedIndex = matches.indexOfFirst { it.hash == selectedHash }
        list.emptyText.text = if (commits.isEmpty()) "No commits yet." else "No commits match your search."
    }
    private fun openSelected() {
        val path = root ?: return
        list.selectedValue?.let { CommitDetails.show(project, path, it) }
    }
}
