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
import kotlin.math.roundToInt

/** Keeps the normal stripe icon size, including when the IDE scales it for the new UI. */
internal class ChangeCountIcon(
    private val baseIcon: Icon,
    val count: Int,
    private val factor: Float = 1f,
) : ScalableIcon {
    private val width = (baseIcon.iconWidth * factor).roundToInt()
    private val height = (baseIcon.iconHeight * factor).roundToInt()
    // Prefer the platform's crisp SVG rendering; it does not report a scaled size everywhere, so keep it only when it does.
    private val delegate = ((baseIcon as? ScalableIcon)?.scale(factor) ?: IconUtil.scale(baseIcon, null, factor))
        .takeIf { it.iconWidth == width && it.iconHeight == height }
    val badgeText: String = if (count > 99) "99+" else count.toString()

    override fun getScale() = factor
    override fun scale(scaleFactor: Float): Icon =
        if (scaleFactor == factor) this else ChangeCountIcon(baseIcon, count, scaleFactor)

    override fun getIconWidth() = width
    override fun getIconHeight() = height

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (delegate != null) delegate.paintIcon(component, graphics, x, y)
        else {
            val base = graphics.create() as Graphics2D
            try {
                base.translate(x, y)
                base.scale(factor.toDouble(), factor.toDouble())
                baseIcon.paintIcon(component, base, 0, 0)
            } finally {
                base.dispose()
            }
        }
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, iconHeight * 0.44f)
            val metrics = g.fontMetrics
            val badgeHeight = iconHeight * 0.62f
            val badgeWidth = max(badgeHeight, metrics.stringWidth(badgeText) + iconWidth * 0.15f).coerceAtMost(iconWidth.toFloat())
            val left = x + iconWidth - badgeWidth
            val top = y + iconHeight - badgeHeight
            g.color = JBUI.CurrentTheme.IconBadge.INFORMATION
            g.fill(RoundRectangle2D.Float(left, top, badgeWidth, badgeHeight, badgeHeight, badgeHeight))
            g.color = Color.WHITE
            g.drawString(badgeText, left + (badgeWidth - metrics.stringWidth(badgeText)) / 2,
                top + (badgeHeight - metrics.height) / 2 + metrics.ascent)
        } finally {
            g.dispose()
        }
    }
}
