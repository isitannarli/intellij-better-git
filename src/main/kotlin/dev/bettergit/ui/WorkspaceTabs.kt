package dev.bettergit.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

internal class WorkspaceTab(val id: String, val title: String, val description: String, createContent: () -> JComponent) {
    val content: JComponent by lazy(createContent)
    constructor(id: String, title: String, description: String, content: JComponent) : this(id, title, description, { content })
}

internal class WorkspaceTabs(
    private val pages: List<WorkspaceTab>,
    initialTab: String,
    onSelected: (String) -> Unit,
) : JPanel(BorderLayout()) {
    // The 262 tab delegate can hide JBTabbedPane labels; use native title-based tabs.
    private val tabs = JTabbedPane(JTabbedPane.TOP, JTabbedPane.WRAP_TAB_LAYOUT)
    private val slots = pages.map { JPanel(BorderLayout()) }
    private val mounted = mutableSetOf<Int>()
    val selectedId: String get() = pages[tabs.selectedIndex].id

    init {
        require(pages.isNotEmpty() && pages.map { it.id }.distinct().size == pages.size)
        border = JBUI.Borders.empty()
        tabs.accessibleContext.accessibleName = "Better Git workspace views"
        pages.forEachIndexed { index, page ->
            tabs.addTab(page.title, null, slots[index], page.description)
        }
        tabs.selectedIndex = pages.indexOfFirst { it.id == initialTab }.coerceAtLeast(0)
        mountSelected()
        tabs.addChangeListener { mountSelected(); onSelected(selectedId) }
        add(tabs, BorderLayout.CENTER)
    }

    private fun mountSelected() {
        val index = tabs.selectedIndex
        if (mounted.add(index)) slots[index].add(pages[index].content, BorderLayout.CENTER)
    }

    fun select(id: String) {
        pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { tabs.selectedIndex = it }
    }

    fun setSummary(id: String, text: String) {
        val index = pages.indexOfFirst { it.id == id }
        if (index >= 0) tabs.setToolTipTextAt(index, pages[index].description + if (text.isBlank()) "" else " · $text")
    }

    fun clearSummaries() = pages.forEach { setSummary(it.id, "") }
}
