package dev.bettergit.ui.graph

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.bettergit.core.graph.GraphRefKind
import dev.bettergit.ui.Ui
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import kotlin.math.ln
import kotlin.math.roundToInt

/** A time overview of the loaded history, independent of topological row order. */
internal class GraphMinimap(private val select: (Int) -> Unit) : JPanel(BorderLayout()) {
    var snapshot: GraphSnapshot? = null
        set(value) { field = value; chart.repaint() }
    var matches: Set<Int> = emptySet()
        set(value) { field = value; chart.repaint() }
    var selectedRow = -1
        set(value) { field = value; chart.repaint() }
    var visibleRows: IntRange = IntRange.EMPTY
        set(value) { field = value; chart.repaint() }
    private val metric = JComboBox(arrayOf("Commits", "Lines changed"))
    private val chart = Timeline()

    init {
        isOpaque = false
        val legend = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            listOf("│ HEAD" to green, "○ Selected" to teal, "│ Matches" to amber,
                "▪ Branches" to blue, "▪ Tags" to amber, "▰ Visible dates" to Ui.muted).forEach { (text, color) ->
                add(Ui.label(text).apply { foreground = color })
            }
            toolTipText = "Green line: HEAD · outlined circle: selected commit · yellow lines: search results · " +
                "blue squares: remote branches above / local branches below · amber squares: tags · shaded area: visible dates"
        }
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(Ui.label("MINIMAP").apply { font = font.deriveFont(Font.BOLD, font.size2D - 1) })
                add(metric)
                add(Ui.label("Loaded history", true))
            }, BorderLayout.WEST)
            add(legend, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(chart, BorderLayout.CENTER)
        metric.accessibleContext.accessibleName = "Minimap metric"
        metric.toolTipText = "Activity per time interval: commit count or added + deleted lines (logarithmic height)"
        metric.addActionListener { chart.repaint() }
    }

    private inner class Timeline : JPanel() {
        init {
            isOpaque = false
            preferredSize = JBUI.size(0, 68)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to jump to the nearest loaded commit"
            getAccessibleContext().accessibleName = "Commit activity timeline"
            getAccessibleContext().accessibleDescription = "Use the commit table or search to navigate with the keyboard."
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(event)) jump(event)
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    if (event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK != 0) jump(event)
                }
            })
        }

        private fun bucketAt(x: Int) = ((x - JBUI.scale(8)).toDouble() /
            (width - JBUI.scale(16)).coerceAtLeast(1) * 95).roundToInt().coerceIn(0, 95)
        private fun jump(event: MouseEvent) { snapshot?.nearestRow(bucketAt(event.x))?.let(select) }

        override fun getToolTipText(event: MouseEvent): String {
            val data = snapshot ?: return "No loaded history"
            if (data.history.commits.isEmpty()) return "No loaded commits"
            val bucket = data.activity[bucketAt(event.x)]
            val dates = bucket.rows.map { data.history.commits[it].committedAt }
            val dateText = if (dates.isEmpty()) "No commits in this interval" else
                "${time.format(dates.min().atZone(ZoneId.systemDefault()))} – ${time.format(dates.max().atZone(ZoneId.systemDefault()))}"
            val refs = bucket.references
            return "<html>${Ui.html(dateText)}<br>${bucket.count} commits · ${bucket.linesChanged} lines changed" +
                (if (refs.isEmpty()) "" else "<br>" + Ui.html(refs.take(8).joinToString { it.name }) + if (refs.size > 8) " …" else "") +
                "<br>Click or drag to jump to the nearest loaded commit</html>"
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val data = snapshot ?: return
            if (data.history.commits.isEmpty()) return
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val left = JBUI.scale(8).toDouble()
                val bottom = height - JBUI.scale(24).toDouble()
                val step = (width - JBUI.scale(16)).coerceAtLeast(1).toDouble() / 95
                fun x(bucket: Int) = left + bucket * step
                val values = data.activity.map { if (metric.selectedIndex == 0) it.count.toLong() else it.linesChanged }
                val max = values.max().coerceAtLeast(1)
                fun y(bucket: Int) = bottom - ln(values[bucket] + 1.0) / ln(max + 1.0) * JBUI.scale(30)

                // Topological order need not match date order. Shade the min/max of all visible rows.
                val visible = visibleRows.mapNotNull { data.activityIndices.getOrNull(it) }
                if (visible.isNotEmpty()) {
                    val start = x(visible.min()) - 3; val end = x(visible.max()) + 3
                    g.color = Color(teal.red, teal.green, teal.blue, 32)
                    g.fillRect(start.toInt(), 0, (end - start).toInt().coerceAtLeast(6), bottom.toInt() + JBUI.scale(8))
                }
                val path = Path2D.Double()
                values.indices.forEach { if (it == 0) path.moveTo(x(it), y(it)) else path.lineTo(x(it), y(it)) }
                g.color = teal; g.stroke = BasicStroke(JBUI.scale(1).toFloat()); g.draw(path)
                path.lineTo(x(95), bottom); path.lineTo(left, bottom); path.closePath()
                g.color = Color(teal.red, teal.green, teal.blue, 20); g.fill(path)

                data.activity.forEachIndexed { index, bucket ->
                    val kinds = bucket.referenceKinds
                    fun square(color: Color, atY: Int, offset: Int = 0) {
                        g.color = color; g.fillRect(x(index).toInt() - JBUI.scale(2) + offset, atY, JBUI.scale(4), JBUI.scale(4))
                    }
                    if (GraphRefKind.REMOTE in kinds) square(blue, JBUI.scale(2))
                    if (GraphRefKind.BRANCH in kinds) square(blue, bottom.toInt() + JBUI.scale(4))
                    if (GraphRefKind.TAG in kinds) square(amber, JBUI.scale(2), JBUI.scale(5))
                }
                fun marker(row: Int, color: Color) {
                    val bucket = data.activityIndices.getOrNull(row) ?: return
                    g.color = color; val atX = x(bucket).toInt()
                    g.drawLine(atX, 0, atX, bottom.toInt() + JBUI.scale(8))
                }
                matches.forEach { marker(it, amber) }
                data.rowByHash[data.history.head]?.let { marker(it, green) }
                data.activityIndices.getOrNull(selectedRow)?.let { bucket ->
                    val radius = JBUI.scale(4)
                    g.color = background; g.fillOval(x(bucket).toInt() - radius, y(bucket).toInt() - radius, radius * 2, radius * 2)
                    g.color = teal; g.stroke = BasicStroke(JBUI.scale(2).toFloat())
                    g.drawOval(x(bucket).toInt() - radius, y(bucket).toInt() - radius, radius * 2, radius * 2)
                }
                g.font = font.deriveFont(font.size2D - 2); g.color = Ui.muted
                val start = day.format(data.firstTime.atZone(ZoneId.systemDefault()))
                val end = day.format(data.lastTime.atZone(ZoneId.systemDefault()))
                g.drawString(start, left.toInt(), height - JBUI.scale(2))
                if (width > g.fontMetrics.stringWidth(start + end) + JBUI.scale(30)) {
                    g.drawString(end, width - g.fontMetrics.stringWidth(end) - JBUI.scale(8), height - JBUI.scale(2))
                }
            } finally { g.dispose() }
        }
    }

    companion object {
        private val time = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
        private val day = DateTimeFormatter.ofPattern("dd MMM yyyy")
        private val teal = JBColor(Color(0x00877F), Color(0x32BDB0))
        private val blue = JBColor(Color(0x216FD2), Color(0x519BFF))
        private val amber = JBColor(Color(0x936000), Color(0xDDB05F))
        private val green = JBColor(Color(0x267B48), Color(0x7BC795))
    }
}
