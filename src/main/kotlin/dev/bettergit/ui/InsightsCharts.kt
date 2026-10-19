package dev.bettergit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.JPanel
import javax.swing.ToolTipManager
import javax.swing.UIManager

internal class InsightsCharts : JPanel(GridLayout(2, 1, 0, JBUI.scale(16))) {
    private val weekly = CommitBarChart()
    private val weekday = CommitBarChart()

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(chartSection("Weekly trend", "Last 12 Monday-based weeks · current week may be incomplete", weekly))
        add(chartSection("Day of week", "Local commit counts across the selected year", weekday))
    }

    fun setData(weeks: List<Pair<LocalDate, Int>>, weekdays: Map<DayOfWeek, Int>, through: LocalDate) {
        weekly.setData(weeks.map { (start, count) ->
            BarDatum(SHORT_DATE.format(start), count,
                "${start} – ${minOf(start.plusDays(6), through)} · $count local commits")
        })
        weekday.setData(DayOfWeek.entries.map { day ->
            val count = weekdays[day] ?: 0
            BarDatum(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), count,
                "${day.getDisplayName(TextStyle.FULL, Locale.getDefault())} · $count local commits")
        })
    }

    fun clear() { weekly.setData(emptyList()); weekday.setData(emptyList()) }

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun chartSection(title: String, subtitle: String, chart: CommitBarChart) = DashboardStyles.card(BorderLayout(0, JBUI.scale(10))).apply {
        add(JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            add(Ui.heading(title), BorderLayout.NORTH)
            add(JBTextArea(subtitle).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = Ui.label("").font; foreground = Ui.muted
                border = JBUI.Borders.empty()
                getAccessibleContext().accessibleName = "$title coverage"
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(chart, BorderLayout.CENTER)
    }

    companion object { private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM") }
}

private data class BarDatum(val label: String, val count: Int, val description: String)

private class CommitBarChart : JPanel() {
    private var data = emptyList<BarDatum>()
    private var hovered = -1
    private val topInset get() = JBUI.scale(20)
    private val bottomInset get() = JBUI.scale(26)
    private val leftInset get() = JBUI.scale(34)
    private val rightInset get() = JBUI.scale(10)

    init {
        font = UIManager.getFont("Label.font")
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(430), JBUI.scale(142))
        minimumSize = Dimension(JBUI.scale(200), JBUI.scale(115))
        getAccessibleContext().accessibleName = "Local commit counts"
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val next = barAt(event.x, event.y)
                if (next != hovered) { hovered = next; repaint() }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { hovered = -1; repaint() }
        })
    }

    override fun addNotify() {
        super.addNotify()
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun removeNotify() {
        ToolTipManager.sharedInstance().unregisterComponent(this)
        super.removeNotify()
    }

    fun setData(values: List<BarDatum>) {
        data = values
        hovered = -1
        getAccessibleContext().accessibleDescription = values.joinToString("; ") { it.description }
            .ifEmpty { "No repository activity loaded." }
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? = data.getOrNull(barAt(event.x, event.y))?.description

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = font
            if (data.isEmpty()) {
                g.color = Ui.muted
                g.drawString("Load repository activity to see the trend.", JBUI.scale(8), topInset + JBUI.scale(10))
                return
            }
            val maximum = data.maxOf(BarDatum::count)
            val chartHeight = (height - topInset - bottomInset).coerceAtLeast(1)
            val baseline = topInset + chartHeight
            val chartWidth = (width - leftInset - rightInset).coerceAtLeast(1)
            val slot = chartWidth.toDouble() / data.size
            val metrics = g.fontMetrics
            g.color = JBColor.namedColor("Component.borderColor", JBColor(Color(0xDDE1E5), Color(0x45494E)))
            g.drawLine(leftInset, topInset, width - rightInset, topInset)
            g.drawLine(leftInset, topInset + chartHeight / 2, width - rightInset, topInset + chartHeight / 2)
            g.drawLine(leftInset, baseline, width - rightInset, baseline)
            g.color = Ui.muted
            g.drawString(maximum.toString(), (leftInset - metrics.stringWidth(maximum.toString()) - JBUI.scale(6)).coerceAtLeast(0), topInset + metrics.ascent / 2)
            g.drawString("0", leftInset - metrics.stringWidth("0") - JBUI.scale(6), baseline)
            var lastLabelRight = Int.MIN_VALUE / 2
            for ((index, datum) in data.withIndex()) {
                val center = leftInset + (slot * (index + 0.5)).toInt()
                val barWidth = (slot * 0.56).toInt().coerceIn(JBUI.scale(3), JBUI.scale(40))
                val barHeight = if (datum.count == 0 || maximum == 0) 0 else
                    (datum.count.toDouble() / maximum * chartHeight).toInt().coerceAtLeast(JBUI.scale(2))
                g.color = if (index == hovered) Ui.accent else JBColor.namedColor("BetterGit.Insights.bar", JBColor(Color(0x378E76), Color(0x60BDA3)))
                if (barHeight > 0) g.fillRoundRect(center - barWidth / 2, baseline - barHeight, barWidth, barHeight, JBUI.scale(4), JBUI.scale(4))
                val value = datum.count.toString()
                if (metrics.stringWidth(value) < slot - JBUI.scale(3)) {
                    g.color = Ui.muted
                    g.drawString(value, center - metrics.stringWidth(value) / 2, (baseline - barHeight - JBUI.scale(4)).coerceAtLeast(metrics.ascent))
                }
                val labelWidth = metrics.stringWidth(datum.label)
                val labelX = (center - labelWidth / 2).coerceIn(0, (width - labelWidth).coerceAtLeast(0))
                if (labelX >= lastLabelRight + JBUI.scale(8)) {
                    g.color = Ui.muted
                    g.drawString(datum.label, labelX, baseline + metrics.ascent + JBUI.scale(6))
                    lastLabelRight = labelX + labelWidth
                }
            }
        } finally { g.dispose() }
    }

    private fun barAt(x: Int, y: Int): Int {
        if (data.isEmpty() || y < topInset || y > height - bottomInset) return -1
        val chartWidth = width - leftInset - rightInset
        if (chartWidth <= 0 || x < leftInset || x >= width - rightInset) return -1
        return ((x - leftInset).toDouble() / chartWidth * data.size).toInt().coerceIn(0, data.lastIndex)
    }
}
