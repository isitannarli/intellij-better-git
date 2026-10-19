package dev.bettergit.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.UIManager

class ContributionHeatmap : JPanel(), Scrollable {
    private var start: LocalDate? = null
    private var end: LocalDate? = null
    private var firstMonday: LocalDate? = null
    private var counts = emptyMap<LocalDate, Int>()
    private var selected: LocalDate? = null
    private var hovered: LocalDate? = null
    private var onDaySelected: (LocalDate) -> Unit = {}
    private var columns = 53
    private var incompleteCoverage = false
    private val cellSize get() = JBUI.scale(12)
    private val step get() = JBUI.scale(15)
    private val leftInset get() = JBUI.scale(38)
    private val topInset get() = JBUI.scale(25)

    init {
        font = UIManager.getFont("Label.font")
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "Local commit calendar"
        getAccessibleContext().accessibleDescription = "Use arrow keys to select a day. Enter opens that day's commits."
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                dateAt(event.x, event.y)?.let {
                    select(it)
                    requestFocusInWindow()
                    onDaySelected(it)
                }
            }
            override fun mouseExited(event: MouseEvent) { hovered = null; repaint() }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val next = dateAt(event.x, event.y)
                if (next != hovered) { hovered = next; repaint() }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) { selected?.let(::select); repaint() }
            override fun focusLost(event: FocusEvent) { repaint() }
        })
        bind("LEFT", "previousWeek") { move(-7) }
        bind("RIGHT", "nextWeek") { move(7) }
        bind("UP", "previousDay") { move(-1) }
        bind("DOWN", "nextDay") { move(1) }
        bind("HOME", "firstDay") { start?.let(::select) }
        bind("END", "lastDay") { end?.let(::select) }
        bind("ENTER", "openDay") { selected?.let(onDaySelected) }
        bind("SPACE", "openDayWithSpace") { selected?.let(onDaySelected) }
    }

    override fun addNotify() {
        super.addNotify()
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun removeNotify() {
        ToolTipManager.sharedInstance().unregisterComponent(this)
        super.removeNotify()
    }

    fun setData(start: LocalDate, end: LocalDate, dailyCounts: Map<LocalDate, Int>, onDaySelected: (LocalDate) -> Unit) {
        require(!end.isBefore(start)) { "Calendar end must not precede its start" }
        require(ChronoUnit.DAYS.between(start, end) < 370) { "Calendar is limited to one year" }
        this.start = start
        this.end = end
        this.firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        this.counts = dailyCounts.filter { (day, count) -> !day.isBefore(start) && !day.isAfter(end) && count > 0 }
        this.onDaySelected = onDaySelected
        this.columns = (ChronoUnit.DAYS.between(firstMonday, end) / 7 + 1).toInt()
        selected = end
        hovered = null
        updateAccessibility()
        revalidate()
        repaint()
    }

    fun clear() {
        start = null; end = null; firstMonday = null
        counts = emptyMap(); selected = null; hovered = null; onDaySelected = {}; incompleteCoverage = false
        getAccessibleContext().accessibleDescription = "No repository activity loaded."
        repaint()
    }

    fun setIncompleteCoverage(incomplete: Boolean) { incompleteCoverage = incomplete; updateAccessibility() }

    override fun getPreferredSize() = Dimension(leftInset + columns * step + JBUI.scale(12), topInset + 7 * step + JBUI.scale(36))
    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableTracksViewportWidth() = false
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = step
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.HORIZONTAL) (visibleRect.width - step).coerceAtLeast(step) else 7 * step

    override fun getToolTipText(event: MouseEvent): String? = dateAt(event.x, event.y)?.let(::description)

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = font
            val from = start ?: run {
                g.color = Ui.muted; g.drawString("Load repository activity to see the calendar.", JBUI.scale(8), topInset)
                return
            }
            val until = end ?: return
            val monday = firstMonday ?: return
            val metrics = g.fontMetrics
            g.color = Ui.muted
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { row, label ->
                g.drawString(label, JBUI.scale(1), topInset + row * step + (cellSize + metrics.ascent - metrics.descent) / 2)
            }
            var lastMonthLabelRight = Int.MIN_VALUE / 2
            for (column in 0 until columns) {
                val weekStart = monday.plusWeeks(column.toLong())
                val visibleStart = maxOf(from, weekStart)
                val visibleEnd = minOf(until, weekStart.plusDays(6))
                val monthStart = visibleStart.withDayOfMonth(1).plusMonths(1)
                val labelDay = when {
                    visibleStart == from && monthStart <= visibleEnd -> monthStart
                    visibleStart == from -> visibleStart
                    visibleStart.dayOfMonth == 1 -> visibleStart
                    monthStart <= visibleEnd -> monthStart
                    else -> null
                }
                if (labelDay != null) {
                    val x = leftInset + column * step
                    val label = MONTH.format(labelDay)
                    if (x >= lastMonthLabelRight + JBUI.scale(3)) {
                        g.drawString(label, x, topInset - JBUI.scale(8))
                        lastMonthLabelRight = x + metrics.stringWidth(label)
                    }
                }
            }
            var day = from
            val maximum = counts.values.maxOrNull() ?: 0
            while (!day.isAfter(until)) {
                val rect = cellBounds(day)
                g.color = levelColor(level(counts[day] ?: 0, maximum))
                g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, JBUI.scale(3), JBUI.scale(3))
                if (day == hovered || (day == selected && hasFocus())) {
                    g.color = Ui.accent
                    g.drawRoundRect(rect.x - 1, rect.y - 1, rect.width + 1, rect.height + 1, JBUI.scale(4), JBUI.scale(4))
                }
                day = day.plusDays(1)
            }
            val baseline = topInset + 7 * step + JBUI.scale(22)
            g.color = Ui.muted
            val legendLabel = "Less"
            g.drawString(legendLabel, leftInset, baseline)
            val legendX = leftInset + metrics.stringWidth(legendLabel) + JBUI.scale(8)
            for (index in 0..4) {
                g.color = levelColor(index)
                g.fillRoundRect(legendX + index * step, baseline - cellSize + JBUI.scale(1), cellSize, cellSize, JBUI.scale(3), JBUI.scale(3))
            }
            g.color = Ui.muted
            g.drawString("More · max $maximum/day", legendX + 5 * step + JBUI.scale(4), baseline)
            val hint = "Select a day to inspect commits"
            val hintX = preferredSize.width - metrics.stringWidth(hint) - JBUI.scale(10)
            g.drawString(hint, hintX, baseline)
        } finally { g.dispose() }
    }

    private fun dateAt(x: Int, y: Int): LocalDate? {
        val monday = firstMonday ?: return null
        val from = start ?: return null
        val until = end ?: return null
        val relativeX = x - leftInset
        val relativeY = y - topInset
        if (relativeX < 0 || relativeY < 0 || relativeX % step >= cellSize || relativeY % step >= cellSize) return null
        val column = relativeX / step
        val row = relativeY / step
        if (column >= columns || row !in 0..6) return null
        return monday.plusDays(column * 7L + row).takeIf { it in from..until }
    }

    private fun cellBounds(day: LocalDate): Rectangle {
        val days = ChronoUnit.DAYS.between(firstMonday, day).toInt()
        return Rectangle(leftInset + days / 7 * step, topInset + days % 7 * step, cellSize, cellSize)
    }

    private fun move(days: Long) {
        val from = start ?: return
        val until = end ?: return
        select((selected ?: until).plusDays(days).coerceIn(from, until))
    }

    private fun select(day: LocalDate) {
        selected = day
        updateAccessibility()
        scrollRectToVisible(cellBounds(day).apply { grow(step, step) })
        repaint()
    }

    private fun description(day: LocalDate): String {
        val count = counts[day] ?: 0
        return "${DAY.format(day)} · $count ${if (incompleteCoverage) "observed " else ""}local commit${if (count == 1) "" else "s"}. Enter or click to inspect."
    }

    private fun updateAccessibility() {
        getAccessibleContext().accessibleDescription = selected?.let(::description)
            ?: "Use arrow keys to select a day; Enter opens its commits."
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = action()
        })
    }

    companion object {
        private val MONTH = DateTimeFormatter.ofPattern("MMM")
        private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy")
        private fun level(count: Int, maximum: Int): Int = if (count == 0 || maximum == 0) 0
            else kotlin.math.ceil(count.toDouble() * 4 / maximum).toInt().coerceIn(1, 4)
        private fun levelColor(level: Int): Color = when (level) {
            0 -> JBColor.namedColor("BetterGit.Heatmap.empty", JBColor(Color(0xE8EBED), Color(0x32363C)))
            1 -> JBColor.namedColor("BetterGit.Heatmap.level1", JBColor(Color(0xB8E3D4), Color(0x1E5146)))
            2 -> JBColor.namedColor("BetterGit.Heatmap.level2", JBColor(Color(0x79C9AB), Color(0x217C66)))
            3 -> JBColor.namedColor("BetterGit.Heatmap.level3", JBColor(Color(0x38A77C), Color(0x2EAA84)))
            else -> JBColor.namedColor("BetterGit.Heatmap.level4", JBColor(Color(0x11734F), Color(0x6DD5AF)))
        }
    }
}
