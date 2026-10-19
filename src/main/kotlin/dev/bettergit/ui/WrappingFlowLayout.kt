package dev.bettergit.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

internal class WrappingFlowLayout(gap: Int) : FlowLayout(LEFT, gap, gap) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val available = (if (target.width > 0) target.width else Int.MAX_VALUE) - insets.left - insets.right - hgap * 2
        var rowWidth = 0
        var rowHeight = 0
        var width = 0
        var height = 0
        target.components.filter { it.isVisible }.forEach { component ->
            val size = component.preferredSize
            if (rowWidth > 0 && rowWidth + hgap + size.width > available) {
                width = maxOf(width, rowWidth)
                height += rowHeight + vgap
                rowWidth = 0; rowHeight = 0
            }
            rowWidth += (if (rowWidth == 0) 0 else hgap) + size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        Dimension(maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2,
            height + rowHeight + insets.top + insets.bottom + vgap * 2)
    }
}
