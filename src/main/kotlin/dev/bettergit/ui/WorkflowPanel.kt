package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.WorkflowGraph
import dev.bettergit.core.graph.WorkflowLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseWheelEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlin.math.roundToInt

class WorkflowPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(10))) {
    private var root: Path? = null
    private var graph = EMPTY_GRAPH
    private var viewportRequest = 0L
    private val search = JBTextField().apply {
        emptyText.text = "Find commit, author, branch or tag"
        getAccessibleContext().accessibleName = "Find commits in the workflow"
    }
    private val selectedTitle = Ui.label("Select a commit").apply { font = font.deriveFont(Font.BOLD, font.size2D + 1) }
    private val summary = Ui.label("Click a node, or use the arrow keys to explore.", true)
    private val selectedRefs = Ui.label("Branch and tag pointers appear above their actual commits.", true)
    private val selectedKind = DashboardStyles.pill("COMMIT")
    private val graphStatus = Ui.label("No repository selected.", true)
    private val zoomLabel = Ui.label("100%")
    private val inspect = Ui.button("Inspect commit", "Open the selected commit's changed files") { openSelected() }
        .apply { isEnabled = false }
    private val canvas = WorkflowCanvas(
        onSelection = { commit ->
            inspect.isEnabled = commit != null && root != null
            selectedTitle.text = commit?.let { clean(it.subject) } ?: "Select a commit"
            selectedTitle.toolTipText = commit?.let { "Commit ${it.hash.take(12)}: ${clean(it.subject)}" }
            selectedKind.text = when {
                commit == null -> "COMMIT"
                commit.parents.distinct().size > 1 -> "MERGE"
                commit.parents.isEmpty() -> "ROOT"
                else -> "COMMIT"
            }
            summary.text = commit?.let { "${it.hash.take(8)}  ·  ${clean(it.author)}  ·  ${Ui.date(it.timestamp)}" }
                ?: "Click a node, or use the arrow keys to explore."
            selectedRefs.text = commit?.let {
                val kind = if (it.parents.distinct().size > 1) "Merge · ${it.parents.distinct().size} parents" else if (it.parents.isEmpty()) "Root commit" else "Commit"
                "$kind${it.decorations.takeIf(String::isNotBlank)?.let { refs -> "  ·  ${clean(refs)}" }.orEmpty()}"
            } ?: "Branch and tag pointers appear above their actual commits."
            summary.toolTipText = "Commit details: ${summary.text}"
            selectedRefs.toolTipText = "References: ${selectedRefs.text}"
        },
        onOpen = { commit -> root?.let { CommitDetails.show(project, it, commit) } },
    )
    private val scroll = JBScrollPane(canvas).apply {
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(400))
        preferredSize = Dimension(JBUI.scale(800), JBUI.scale(440))
        horizontalScrollBar.unitIncrement = JBUI.scale(32)
        verticalScrollBar.unitIncrement = JBUI.scale(32)
    }

    init {
        border = JBUI.Borders.empty(12)
        getAccessibleContext().accessibleName = "Git workflow"
        add(JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                    add(Ui.heading("Repository workflow"), BorderLayout.NORTH)
                    add(Ui.label("Explore branches, merges and tags.", true), BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(search, BorderLayout.CENTER)
                add(Ui.button("Next match", "Select the next matching commit without hiding graph paths") {
                    canvas.selectNextMatch()
                    canvas.requestFocusInWindow()
                }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(Ui.row(
                Ui.button("−", "Zoom out") { zoom(canvas.zoom / 1.25) }, zoomLabel,
                Ui.button("+", "Zoom in") { zoom(canvas.zoom * 1.25) },
                Ui.button("Fit", "Show an overview of the loaded history; zoom in to read labels") { fit() },
                Ui.button("100%", "Read the selected commit at full size") { readableZoom() },
                Ui.label("○ Commit", true), Ui.label("◎ Merge", true),
                Ui.label("┄ Earlier history", true), Ui.label("Older → newer", true),
            ), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(DashboardStyles.card(BorderLayout(JBUI.scale(16), JBUI.scale(7)), emphasized = true).apply {
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(DashboardStyles.eyebrow("SELECTED COMMIT"), BorderLayout.WEST)
                    add(selectedKind, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                    isOpaque = false
                    add(selectedTitle, BorderLayout.NORTH)
                    add(summary, BorderLayout.CENTER)
                    add(selectedRefs, BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(Ui.label("Double-click or Enter", true), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        add(inspect)
                    }, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(graphStatus, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                canvas.setSearch(search.text)
                updateStatus()
            }
        })
        search.addActionListener {
            canvas.selectNextMatch()
            canvas.requestFocusInWindow()
        }
        canvas.addMouseWheelListener { event ->
            if (event.isControlDown || event.isMetaDown) {
                zoomAtPointer(event)
                event.consume()
            } else {
                scroll.dispatchEvent(SwingUtilities.convertMouseEvent(canvas, event, scroll))
            }
        }
    }

    @JvmOverloads
    fun setCommits(root: Path, commits: List<GitCommit>, preparedGraph: WorkflowGraph? = null) {
        val changedRepository = this.root != root
        this.root = root
        graph = preparedGraph ?: WorkflowLayout.layout(commits)
        if (changedRepository) {
            canvas.setGraph(EMPTY_GRAPH)
            canvas.updateZoom(1.0)
        }
        canvas.setGraph(graph)
        canvas.setSearch(search.text)
        zoomLabel.text = "${(canvas.zoom * 100).roundToInt()}%"
        updateStatus()
        val request = ++viewportRequest
        SwingUtilities.invokeLater {
            if (request == viewportRequest && this.root == root && !project.isDisposed) canvas.revealSelected()
        }
    }

    fun clear() {
        ++viewportRequest
        root = null
        graph = EMPTY_GRAPH
        canvas.setGraph(graph)
        graphStatus.text = "No repository selected."
    }

    private fun updateStatus() {
        if (root == null) { graphStatus.text = "No repository selected."; return }
        val count = graph.nodes.size
        val limit = if (graph.omittedCommits > 0) "Latest $count of ${count + graph.omittedCommits} loaded commits" else "$count commits"
        val boundary = if (graph.boundaries.isEmpty()) "" else " · ${graph.boundaries.size} parent links outside the window"
        val found = if (search.text.isBlank()) "" else " · ${canvas.matchingCount} matches (others dimmed)"
        graphStatus.text = "$limit · ${graph.laneCount} paths$boundary$found"
        graphStatus.toolTipText = "Graph: ${graphStatus.text}"
    }

    private fun openSelected() {
        val path = root ?: return
        canvas.selectedCommit?.let { CommitDetails.show(project, path, it) }
    }

    private fun fit() {
        ++viewportRequest
        canvas.updateZoom(canvas.fitZoom(scroll.viewport.extentSize))
        zoomLabel.text = "${(canvas.zoom * 100).roundToInt()}%"
        scroll.viewport.viewPosition = Point(0, 0)
    }

    private fun readableZoom() {
        zoom(1.0)
        val request = viewportRequest
        SwingUtilities.invokeLater {
            if (request == viewportRequest && root != null && !project.isDisposed) canvas.revealSelected()
        }
    }

    private fun zoomAtPointer(event: MouseWheelEvent) {
        val factor = if (event.preciseWheelRotation < 0) 1.15 else 1 / 1.15
        val point = SwingUtilities.convertPoint(canvas, event.point, scroll.viewport)
        zoom(canvas.zoom * factor, point)
    }

    private fun zoom(value: Double, anchor: Point? = null) {
        val viewport = scroll.viewport
        val focus = anchor ?: Point(viewport.extentSize.width / 2, viewport.extentSize.height / 2)
        val contentX = (viewport.viewPosition.x + focus.x) / canvas.zoom
        val contentY = (viewport.viewPosition.y + focus.y) / canvas.zoom
        canvas.updateZoom(value)
        zoomLabel.text = "${(canvas.zoom * 100).roundToInt()}%"
        val request = ++viewportRequest
        SwingUtilities.invokeLater {
            if (request == viewportRequest && root != null && !project.isDisposed) {
                val bounds = canvas.preferredSize
                viewport.viewPosition = Point(
                    (contentX * canvas.zoom - focus.x).roundToInt().coerceIn(0, (bounds.width - viewport.extentSize.width).coerceAtLeast(0)),
                    (contentY * canvas.zoom - focus.y).roundToInt().coerceIn(0, (bounds.height - viewport.extentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }

    companion object {
        private val EMPTY_GRAPH = WorkflowGraph(emptyList(), emptyList(), emptyList(), 0, 0)
        private fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
    }
}
