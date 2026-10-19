package dev.bettergit.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.WorkflowGraph
import dev.bettergit.core.graph.WorkflowNode
import dev.bettergit.core.graph.WorkflowRef
import dev.bettergit.core.graph.WorkflowRefKind
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ToolTipManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal class WorkflowCanvas(
    private val onSelection: (GitCommit?) -> Unit,
    private val onOpen: (GitCommit) -> Unit,
) : JPanel() {
    private var graph = WorkflowGraph(emptyList(), emptyList(), emptyList(), 0, 0)
    private var nodesByHash = emptyMap<String, WorkflowNode>()
    private var selectedHash: String? = null
    private var hoveredHash: String? = null
    private var query = ""
    private var matches = emptySet<String>()
    var zoom = 1.0
        private set
    val selectedCommit: GitCommit? get() = nodesByHash[selectedHash]?.commit
    val matchingCount: Int get() = matches.size
    val nodeCount: Int get() = graph.nodes.size

    init {
        isFocusable = true
        isOpaque = true
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(400))
        getAccessibleContext().accessibleName = "Git workflow graph"
        getAccessibleContext().accessibleDescription = "Older commits are on the left. Arrow keys select commits; Enter opens commit details."
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                requestFocusInWindow()
                val node = hit(event.point) ?: return
                select(node, reveal = false)
                if (event.clickCount == 2) onOpen(node.commit)
            }
            override fun mouseMoved(event: MouseEvent) {
                val hash = hit(event.point)?.commit?.hash
                if (hash != hoveredHash) {
                    hoveredHash = hash
                    cursor = Cursor.getPredefinedCursor(if (hash == null) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR)
                    repaint()
                }
            }
            override fun mouseExited(event: MouseEvent) { hoveredHash = null; repaint() }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        bind("LEFT") { move(-1) }
        bind("RIGHT") { move(1) }
        bind("UP") { moveLane(-1) }
        bind("DOWN") { moveLane(1) }
        bind("HOME") { graph.nodes.firstOrNull()?.let(::select) }
        bind("END") { graph.nodes.lastOrNull()?.let(::select) }
        bind("ENTER") { selectedCommit?.let(onOpen) }
    }

    override fun addNotify() {
        super.addNotify()
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun removeNotify() {
        ToolTipManager.sharedInstance().unregisterComponent(this)
        super.removeNotify()
    }

    fun setGraph(value: WorkflowGraph) {
        graph = value
        nodesByHash = value.nodes.associateBy { it.commit.hash }
        selectedHash = selectedHash?.takeIf(nodesByHash::containsKey) ?: value.nodes.lastOrNull()?.commit?.hash
        hoveredHash = null
        setSearch(query)
        updateSelection()
        revalidate()
        repaint()
    }

    fun setSearch(text: String) {
        query = text.trim()
        matches = graph.nodes.filter { node ->
            query.isEmpty() || listOf(node.commit.hash, node.commit.subject, node.commit.author,
                node.commit.email, node.commit.decorations).any { it.contains(query, ignoreCase = true) }
        }.mapTo(mutableSetOf()) { it.commit.hash }
        repaint()
    }

    fun updateZoom(value: Double) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        revalidate()
        repaint()
    }

    fun fitZoom(available: Dimension): Double {
        val natural = naturalSize()
        return minOf((available.width - JBUI.scale(24)).coerceAtLeast(1).toDouble() / natural.width,
            (available.height - JBUI.scale(24)).coerceAtLeast(1).toDouble() / natural.height).coerceIn(MIN_ZOOM, 1.0)
    }

    fun revealSelected() { nodesByHash[selectedHash]?.let { scrollRectToVisible(screenBounds(it)) } }

    fun selectNextMatch() {
        if (matches.isEmpty()) return
        val current = graph.nodes.indexOfFirst { it.commit.hash == selectedHash }
        val ordered = graph.nodes.drop(current + 1) + graph.nodes.take(current + 1)
        ordered.firstOrNull { it.commit.hash in matches }?.let(::select)
    }

    override fun getPreferredSize(): Dimension {
        val natural = naturalSize()
        return Dimension(max(JBUI.scale(400), ceil(natural.width * zoom).toInt()),
            max(JBUI.scale(400), ceil(natural.height * zoom).toInt()))
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val node = hit(event.point) ?: return null
        val refs = node.refs.joinToString(" · ") { refLabel(it) }
        val outside = graph.boundaries.count { it.childHash == node.commit.hash }
        return "<html><b>${Ui.html(clean(node.commit.subject))}</b><br>" +
            "${node.commit.hash.take(12)} · ${Ui.html(clean(node.commit.author))} · ${Ui.date(node.commit.timestamp)}" +
            (if (refs.isEmpty()) "" else "<br>${Ui.html(clean(refs))}") +
            (if (outside == 0) "" else "<br>$outside parent(s) outside the loaded history") +
            "<br>Double-click or press Enter to inspect</html>"
    }

    override fun paintComponent(graphics: Graphics) {
        background = JBColor.namedColor("Tree.background", UIUtil.getTreeBackground())
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = UIUtil.getLabelFont()
            if (graph.nodes.isEmpty()) {
                g.color = Ui.muted
                g.drawString("No commits to visualize.", JBUI.scale(24), JBUI.scale(40))
                return
            }
            g.scale(zoom, zoom)
            val natural = naturalSize()
            g.color = Ui.muted
            if (zoom >= LABEL_ZOOM) {
                g.drawString("OLDER", marginX() - JBUI.scale(38), JBUI.scale(28))
                g.drawString("NEWER  →", natural.width - marginX(), JBUI.scale(28))
            }
            g.stroke = BasicStroke((1.0 / zoom).toFloat())
            if (zoom >= LABEL_ZOOM) {
                g.color = Ui.muted
                g.composite = AlphaComposite.SrcOver.derive(0.055f)
                graph.nodes.forEach { node ->
                    g.draw(Line2D.Double(nodeX(node), JBUI.scale(44).toDouble(), nodeX(node), (natural.height - JBUI.scale(28)).toDouble()))
                }
            }
            for (lane in 0 until graph.laneCount) {
                g.composite = AlphaComposite.SrcOver.derive(0.08f)
                g.color = Ui.muted
                val y = marginY() + lane * laneStep()
                g.draw(Line2D.Double(JBUI.scale(24).toDouble(), y.toDouble(), (natural.width - JBUI.scale(24)).toDouble(), y.toDouble()))
            }
            graph.edges.forEach { edge ->
                val parent = nodesByHash.getValue(edge.parentHash)
                val child = nodesByHash.getValue(edge.childHash)
                g.composite = AlphaComposite.SrcOver.derive(if (query.isEmpty() || parent.commit.hash in matches || child.commit.hash in matches) 0.85f else 0.14f)
                g.color = laneColor(parent.lane)
                g.stroke = BasicStroke(max(JBUI.scale(2).toDouble(), 1.2 / zoom).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val x1 = nodeX(parent)
                val x2 = nodeX(child)
                val bend = (x1 + x2) / 2
                g.draw(CubicCurve2D.Double(x1, nodeY(parent), bend, nodeY(parent), bend, nodeY(child), x2, nodeY(child)))
            }
            graph.boundaries.groupBy { it.childHash }.forEach { (hash, boundaries) ->
                val node = nodesByHash.getValue(hash)
                g.composite = AlphaComposite.SrcOver.derive(if (hash in matches) 0.75f else 0.14f)
                g.color = laneColor(node.lane)
                g.stroke = BasicStroke(max(JBUI.scale(2).toDouble(), 1.0 / zoom).toFloat(), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 10f, floatArrayOf(JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()), 0f)
                val hasVisibleParent = graph.edges.any { it.childHash == hash }
                boundaries.take(3).forEachIndexed { index, _ ->
                    // Keep missing merge parents distinct from loaded first-parent edges.
                    val offset = if (hasVisibleParent) -JBUI.scale(16 + index * 8).toDouble()
                        else (index - (boundaries.take(3).size - 1) / 2.0) * JBUI.scale(14)
                    val y = nodeY(node) + offset
                    g.draw(CubicCurve2D.Double(nodeX(node) - JBUI.scale(52), y, nodeX(node) - JBUI.scale(26), y,
                        nodeX(node) - JBUI.scale(20), nodeY(node), nodeX(node), nodeY(node)))
                }
                if (zoom >= LABEL_ZOOM) g.drawString("…", (nodeX(node) - JBUI.scale(64)).toFloat(), (nodeY(node) + JBUI.scale(4)).toFloat())
            }
            graph.nodes.forEach { drawNode(g, it) }
        } finally { g.dispose() }
    }

    private fun drawNode(g: Graphics2D, node: WorkflowNode) {
        val selected = node.commit.hash == selectedHash
        val hovered = node.commit.hash == hoveredHash
        val matched = node.commit.hash in matches
        g.composite = AlphaComposite.SrcOver.derive(if (matched || selected || hovered) 1f else 0.23f)
        val color = laneColor(node.lane)
        val x = nodeX(node)
        val y = nodeY(node)
        val overviewRadius = when { zoom < 0.1 -> 0.8; zoom < 0.3 -> 1.6; else -> 3.0 }
        val radius = max(JBUI.scale(if (node.isMerge) 9 else 7).toDouble(), overviewRadius / zoom)
        if (selected && zoom >= LABEL_ZOOM) {
            val original = g.composite
            g.color = color
            g.composite = AlphaComposite.SrcOver.derive(0.075f)
            g.fillRoundRect((x - columnStep() / 2 + JBUI.scale(5)).roundToInt(), (y - JBUI.scale(67)).roundToInt(),
                columnStep() - JBUI.scale(10), JBUI.scale(144), JBUI.scale(14), JBUI.scale(14))
            g.composite = original
        }
        if (selected || hovered) {
            val halo = radius + max(JBUI.scale(6).toDouble(), 3.0 / zoom)
            g.color = color
            val original = g.composite
            g.composite = AlphaComposite.SrcOver.derive(if (selected) 0.22f else 0.12f)
            g.fill(Ellipse2D.Double(x - halo, y - halo, halo * 2, halo * 2))
            g.composite = original
        }
        g.color = background
        g.fill(Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2))
        g.color = color
        g.stroke = BasicStroke(max(JBUI.scale(2).toDouble(), 1.2 / zoom).toFloat())
        g.draw(Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2))
        if (selected || node.isMerge || node.refs.any { it.current }) {
            val inner = radius * if (node.isMerge) 0.48 else 0.55
            if (node.isMerge) g.draw(Ellipse2D.Double(x - inner, y - inner, inner * 2, inner * 2))
            else g.fill(Ellipse2D.Double(x - inner, y - inner, inner * 2, inner * 2))
        }
        if (zoom < LABEL_ZOOM) return
        val labelWidth = columnStep() - JBUI.scale(20)
        g.font = UIUtil.getLabelFont().deriveFont(if (selected) Font.BOLD else Font.PLAIN)
        g.color = UIUtil.getLabelForeground()
        centered(g, clean(node.commit.subject), x, y + JBUI.scale(30), labelWidth)
        g.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1)
        g.color = Ui.muted
        centered(g, "${node.commit.hash.take(7)} · ${clean(node.commit.author)}", x, y + JBUI.scale(49), labelWidth)
        node.refs.take(2).forEachIndexed { index, ref ->
            drawRef(g, ref, x, y - JBUI.scale(54) + index * JBUI.scale(24), labelWidth, color)
        }
        if (node.refs.size > 2) {
            g.color = Ui.muted
            centered(g, "+${node.refs.size - 2} refs", x, y + JBUI.scale(67), labelWidth)
        }
    }

    private fun drawRef(g: Graphics2D, ref: WorkflowRef, x: Double, y: Double, maximum: Int, color: Color) {
        g.font = UIUtil.getLabelFont().deriveFont(if (ref.current) Font.BOLD else Font.PLAIN)
        val text = ellipsize(g, clean(refLabel(ref)), maximum - JBUI.scale(16))
        val pillWidth = g.fontMetrics.stringWidth(text) + JBUI.scale(14)
        val pillHeight = g.fontMetrics.height + JBUI.scale(4)
        val left = (x - pillWidth / 2.0).roundToInt()
        g.color = background
        g.fillRoundRect(left, y.roundToInt(), pillWidth, pillHeight, JBUI.scale(8), JBUI.scale(8))
        g.color = if (ref.kind == WorkflowRefKind.TAG) Ui.muted else color
        g.stroke = BasicStroke(JBUI.scale(if (ref.current) 2 else 1).toFloat())
        g.drawRoundRect(left, y.roundToInt(), pillWidth, pillHeight, JBUI.scale(8), JBUI.scale(8))
        g.drawString(text, left + JBUI.scale(7), y.roundToInt() + JBUI.scale(2) + g.fontMetrics.ascent)
    }

    private fun centered(g: Graphics2D, value: String, x: Double, baseline: Double, maximum: Int) {
        val text = ellipsize(g, value, maximum)
        g.drawString(text, (x - g.fontMetrics.stringWidth(text) / 2.0).toFloat(), baseline.toFloat())
    }

    private fun ellipsize(g: Graphics2D, value: String, maximum: Int): String {
        if (g.fontMetrics.stringWidth(value) <= maximum) return value
        var end = value.length.coerceAtMost(80)
        while (end > 0 && g.fontMetrics.stringWidth(value.take(end) + "…") > maximum) end--
        return value.take(end) + "…"
    }

    private fun naturalSize() = Dimension(
        marginX() * 2 + (graph.nodes.size - 1).coerceAtLeast(0) * columnStep(),
        marginY() + JBUI.scale(106) + (graph.laneCount - 1).coerceAtLeast(0) * laneStep(),
    )

    private fun nodeX(node: WorkflowNode): Double = (marginX() + node.column * columnStep()).toDouble()
    private fun nodeY(node: WorkflowNode): Double = (marginY() + node.lane * laneStep()).toDouble()
    private fun hit(point: Point): WorkflowNode? {
        val x = point.x / zoom
        val y = point.y / zoom
        return graph.nodes.filter { node ->
            if (zoom < LABEL_ZOOM) abs(nodeX(node) - x) < max(JBUI.scale(10).toDouble(), 6 / zoom) && abs(nodeY(node) - y) < max(JBUI.scale(10).toDouble(), 6 / zoom)
            else abs(nodeX(node) - x) < columnStep() / 2 - JBUI.scale(6) && y in nodeY(node) - JBUI.scale(58)..nodeY(node) + JBUI.scale(72)
        }.minByOrNull { abs(nodeX(it) - x) + abs(nodeY(it) - y) }
    }

    private fun screenBounds(node: WorkflowNode) = Rectangle(
        ((nodeX(node) - columnStep() / 2) * zoom).roundToInt(),
        ((nodeY(node) - JBUI.scale(68)) * zoom).roundToInt(),
        max(JBUI.scale(32), (columnStep() * zoom).roundToInt()),
        max(JBUI.scale(32), (laneStep() * zoom).roundToInt()),
    )

    private fun select(node: WorkflowNode, reveal: Boolean = true) {
        selectedHash = node.commit.hash
        updateSelection()
        repaint()
        if (reveal) revealSelected()
    }

    private fun updateSelection() {
        val commit = selectedCommit
        getAccessibleContext().accessibleDescription = commit?.let {
            "Selected ${it.hash.take(8)}. ${clean(it.subject)}. ${clean(it.author)}. ${clean(it.decorations)}. " +
                "Arrow keys select commits. Enter opens details."
        } ?: "No commits to visualize."
        onSelection(commit)
    }

    private fun move(delta: Int) {
        if (graph.nodes.isEmpty()) return
        val index = graph.nodes.indexOfFirst { it.commit.hash == selectedHash }.coerceAtLeast(0)
        select(graph.nodes[(index + delta).coerceIn(0, graph.nodes.lastIndex)])
    }

    private fun moveLane(delta: Int) {
        val current = nodesByHash[selectedHash] ?: return
        graph.nodes.filter { if (delta < 0) it.lane < current.lane else it.lane > current.lane }
            .minByOrNull { abs(it.lane - current.lane) * graph.nodes.size + abs(it.column - current.column) }
            ?.let(::select)
    }

    private fun bind(key: String, run: () -> Unit) {
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key)
        actionMap.put(key, object : AbstractAction() { override fun actionPerformed(event: ActionEvent?) = run() })
    }

    companion object {
        const val MIN_ZOOM = 0.01
        const val MAX_ZOOM = 2.5
        private const val LABEL_ZOOM = 0.55
        private fun marginX() = JBUI.scale(92)
        private fun marginY() = JBUI.scale(114)
        private fun columnStep() = JBUI.scale(164)
        private fun laneStep() = JBUI.scale(150)
        private val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}\\p{Cf}]")
        private fun clean(value: String) = value.replace(CONTROL_CHARACTERS, " ")
        private fun refLabel(ref: WorkflowRef): String = when {
            ref.current && ref.kind != WorkflowRefKind.HEAD -> "HEAD → ${ref.name}"
            ref.kind == WorkflowRefKind.TAG -> "tag: ${ref.name}"
            else -> ref.name
        }
        private val palette = listOf(
            JBColor(Color(0x3574F0), Color(0x6B9BFA)), JBColor(Color(0x208A5A), Color(0x59B881)),
            JBColor(Color(0xA447BA), Color(0xC58DDD)), JBColor(Color(0xB87812), Color(0xD9A957)),
            JBColor(Color(0xB54769), Color(0xE683A2)), JBColor(Color(0x1E858F), Color(0x61BCC6)),
        )
        private fun laneColor(lane: Int): Color = palette[lane % palette.size]
    }
}
