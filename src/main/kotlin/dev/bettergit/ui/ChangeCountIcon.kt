package dev.bettergit.ui

import com.intellij.openapi.util.ScalableIcon
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import kotlin.math.max

/** Keeps the normal stripe icon size, including when the IDE scales it for the new UI. */
internal class ChangeCountIcon(
    private val baseIcon: Icon,
    val count: Int,
    private val factor: Float = 1f,
) : ScalableIcon {
    private val delegate = (baseIcon as? ScalableIcon)?.scale(factor) ?: IconUtil.scale(baseIcon, null, factor)
    val badgeText: String = if (count > 99) "99+" else count.toString()

    override fun getScale() = factor
    override fun scale(scaleFactor: Float): Icon =
        if (scaleFactor == factor) this else ChangeCountIcon(baseIcon, count, scaleFactor)

    override fun getIconWidth() = delegate.iconWidth
    override fun getIconHeight() = delegate.iconHeight

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        delegate.paintIcon(component, graphics, x, y)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, iconHeight * 0.44f)
            val metrics = g.fontMetrics
            val height = iconHeight * 0.62f
            val width = max(height, metrics.stringWidth(badgeText) + iconWidth * 0.15f).coerceAtMost(iconWidth.toFloat())
            val left = x + iconWidth - width
            val top = y + iconHeight - height
            g.color = JBUI.CurrentTheme.IconBadge.INFORMATION
            g.fill(RoundRectangle2D.Float(left, top, width, height, height, height))
            g.color = Color.WHITE
            g.drawString(badgeText, left + (width - metrics.stringWidth(badgeText)) / 2,
                top + (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g.dispose()
        }
    }
}
