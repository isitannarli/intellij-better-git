package dev.bettergit.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JList
import javax.swing.ScrollPaneConstants
import com.intellij.ui.SimpleListCellRenderer

internal object Ui {
    val muted get() = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
    val accent get() = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
    fun label(text: String, muted: Boolean = false): JBLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        this.text = text
        if (muted) foreground = Ui.muted
    }
    fun heading(text: String): JBLabel = label(text).apply { font = font.deriveFont(Font.BOLD, font.size2D + 3) }
    fun <T : Any> renderer(html: Boolean = false, customize: (JBLabel, T, Boolean) -> Unit): SimpleListCellRenderer<T> =
        object : SimpleListCellRenderer<T>() {
            init { putClientProperty("html.disable", !html) }
            override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = ""; icon = null; toolTipText = null
                if (value != null) customize(this, value, selected)
            }
        }
    fun button(text: String, tooltip: String = text, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        getAccessibleContext().accessibleName = tooltip
        addActionListener { action() }
    }
    fun iconButton(icon: Icon, tooltip: String, action: () -> Unit) = object : JButton(icon) {
        override fun getPreferredSize(): Dimension {
            val side = maxOf(JBUI.scale(28), super.getPreferredSize().height, icon.iconWidth + insets.left + insets.right)
            return Dimension(side, side)
        }
        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize
    }.apply {
        margin = JBUI.insets(4)
        toolTipText = tooltip
        getAccessibleContext().accessibleName = tooltip
        addActionListener { action() }
    }
    fun singleLineActions(vararg components: JComponent): JBScrollPane {
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            components.forEachIndexed { index, component ->
                if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(component)
                component.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(event: FocusEvent) { scrollRectToVisible(component.bounds) }
                })
            }
        }
        return object : JBScrollPane(actions) {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                val overflow = width > 0 && actions.preferredSize.width > width
                return Dimension(size.width, actions.preferredSize.height + if (overflow) horizontalScrollBar.preferredSize.height else 0)
            }
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }.apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            getAccessibleContext().accessibleName = "Code inspection actions; scroll horizontally if needed"
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) { revalidate() }
            })
        }
    }
    fun row(vararg components: Component): JPanel = JPanel(WrappingFlowLayout(JBUI.scale(6))).apply {
        isOpaque = false
        components.forEach(::add)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { revalidate() }
        })
    }
    fun section(title: String, body: JComponent): JPanel = object : JPanel(BorderLayout(0, JBUI.scale(10))) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        body.isOpaque = false
        add(DashboardStyles.card(BorderLayout(0, JBUI.scale(12))).apply {
            add(heading(title), BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM · HH:mm")
    fun date(instant: Instant): String = dateFormat.format(instant.atZone(ZoneId.systemDefault()))
    fun html(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun nativeAction(project: Project, component: JComponent, vararg ids: String) {
        val manager = ActionManager.getInstance()
        val action = ids.firstNotNullOfOrNull { manager.getAction(it) }
        if (action == null) {
            Messages.showInfoMessage(project, "This action is unavailable in this IDE context. Open the native Git tool window to continue.", "Better Git")
            return
        }
        manager.tryToExecute(action, null, component, "BetterGit", true)
    }
}
