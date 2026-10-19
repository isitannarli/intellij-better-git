package dev.bettergit.ui

import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

internal class ScrollableColumnPanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 20
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
}
