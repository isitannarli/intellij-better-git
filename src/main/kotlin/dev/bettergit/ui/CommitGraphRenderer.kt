package dev.bettergit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.GraphRow
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

internal class CommitGraphRenderer(private val rows: () -> Map<String, GraphRow>) : JPanel(BorderLayout(JBUI.scale(8), 0)), ListCellRenderer<GitCommit> {
    private val graph = GraphCell()
    private val subject = Ui.label("")
    private val metadata = Ui.label("")

    init {
        val labels = JPanel(java.awt.GridLayout(2, 1)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 8, 10)
            add(subject); add(metadata)
        }
        add(graph, BorderLayout.WEST)
        add(labels, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out GitCommit>, value: GitCommit, index: Int, selected: Boolean, focus: Boolean): Component {
        background = if (selected) list.selectionBackground else list.background
        val textColor = if (selected) list.selectionForeground else list.foreground
        subject.foreground = textColor
        metadata.foreground = if (selected) textColor else Ui.muted
        subject.font = list.font.deriveFont(Font.BOLD)
        metadata.font = list.font
        subject.text = value.subject
        metadata.text = "${value.hash.take(8)} · ${value.author} · ${Ui.date(value.timestamp)}" +
            value.decorations.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        graph.setRow(rows()[value.hash])
        getAccessibleContext().accessibleName = "${value.subject}, ${value.author}, ${value.hash.take(8)}, ${value.parents.size} parents"
        toolTipText = "Commit: ${value.subject} · ${value.hash}"
        return this
    }
}

private class GraphCell : JComponent() {
    private var row: GraphRow? = null
    fun setRow(value: GraphRow?) {
        row = value
        isVisible = value != null
        preferredSize = Dimension(((value?.lanes ?: 0) + 1) * step, JBUI.scale(52))
    }
    private val step = JBUI.scale(14)
    private fun x(lane: Int) = (lane + 1) * step
    override fun paintComponent(g: Graphics) {
        val row = row ?: return
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.stroke = BasicStroke(JBUI.scale(2).toFloat())
            val middle = height / 2
            row.edges.forEach { edge ->
                graphics.color = palette[edge.from % palette.size]
                if (edge.startsAtCommit) graphics.drawLine(x(edge.from), middle, x(edge.to), height)
                else {
                    graphics.drawLine(x(edge.from), 0, x(edge.from), middle)
                    graphics.drawLine(x(edge.from), middle, x(edge.to), height)
                }
            }
            graphics.color = palette[row.lane % palette.size]
            if (row.hasIncoming) graphics.drawLine(x(row.lane), 0, x(row.lane), middle)
            val radius = JBUI.scale(4)
            graphics.fillOval(x(row.lane) - radius, middle - radius, radius * 2, radius * 2)
        } finally { graphics.dispose() }
    }
    companion object {
        private val palette = listOf(
            JBColor(Color(0x3574F0), Color(0x6B9BFA)), JBColor(Color(0x208A5A), Color(0x59B881)),
            JBColor(Color(0xA447BA), Color(0xC58DDD)), JBColor(Color(0xB87812), Color(0xD9A957)),
        )
    }
}
