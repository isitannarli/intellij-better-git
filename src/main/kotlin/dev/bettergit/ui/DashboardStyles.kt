package dev.bettergit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import javax.swing.JPanel

internal object DashboardStyles {
    val accent: Color get() = JBColor.namedColor("BetterGit.Dashboard.accent", JBColor(Color(0x237D68), Color(0x71CCB1)))
    val surface: Color get() = JBColor.namedColor("BetterGit.Dashboard.surface", JBColor.lazy {
        mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.025)
    })
    private val emphasizedSurface: Color get() = JBColor.namedColor("BetterGit.Dashboard.emphasizedSurface", JBColor.lazy { mix(surface, accent, 0.08) })
    private val outline: Color get() = JBColor.namedColor("BetterGit.Dashboard.outline", JBColor.lazy {
        mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.14)
    })

    fun card(layout: LayoutManager = BorderLayout(), emphasized: Boolean = false): JPanel = object : JPanel(layout) {
        init { isOpaque = false; border = JBUI.Borders.empty(16) }
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val radius = JBUI.scale(14)
                g.color = if (emphasized) emphasizedSurface else surface
                g.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
                g.color = outline
                g.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
            } finally { g.dispose() }
        }
    }

    fun eyebrow(text: String): JBLabel = Ui.label(text, true).apply {
        font = font.deriveFont(Font.BOLD, (font.size2D - 1).coerceAtLeast(10f))
    }

    fun pill(text: String): JBLabel = object : JBLabel() {
        init {
            putClientProperty("html.disable", true)
            this.text = text
            border = JBUI.Borders.empty(4, 9)
            font = font.deriveFont(Font.BOLD, (font.size2D - 1).coerceAtLeast(10f))
            isOpaque = false
        }
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = emphasizedSurface
                g.fillRoundRect(0, 0, width, height, JBUI.scale(16), JBUI.scale(16))
            } finally { g.dispose() }
            foreground = accent
            super.paintComponent(graphics)
        }
    }

    private fun mix(base: Color, tint: Color, amount: Double) = Color(
        (base.red * (1 - amount) + tint.red * amount).toInt(),
        (base.green * (1 - amount) + tint.green * amount).toInt(),
        (base.blue * (1 - amount) + tint.blue * amount).toInt(),
    )
}
