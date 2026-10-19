package dev.bettergit.ui.graph

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import dev.bettergit.core.graph.GraphRefKind
import dev.bettergit.ui.Ui
import javax.swing.*

internal data class GraphNavigationTarget(val title: String, val revision: String, val hash: String? = null) {
    override fun toString() = title
}

/** Counts describe repository objects; each named control opens a searchable navigation list. */
internal class GraphRefSidebar(private val navigate: (GraphNavigationTarget) -> Unit) : JPanel() {
    private val buttons = mutableListOf<JButton>()
    var snapshot: GraphSnapshot? = null
        set(value) { field = value; rebuild() }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 0, 0, 8)
        isOpaque = false
        rebuild()
    }

    fun setBusy(busy: Boolean) { buttons.forEach { it.isEnabled = !busy && snapshot != null } }

    private fun rebuild() {
        removeAll(); buttons.clear()
        add(Ui.label("REPOSITORY", true).apply { border = JBUI.Borders.empty(2, 6, 8, 0); alignmentX = LEFT_ALIGNMENT })
        val data = snapshot
        val refs = data?.history?.references.orEmpty()
        fun targets(kind: GraphRefKind) = refs.filter { it.kind == kind }.map { GraphNavigationTarget(it.name, it.fullName, it.hash) }.sortedBy { it.title }
        fun button(title: String, count: Int, icon: Icon, description: String, action: (JButton) -> Unit) {
            val control = JButton("$title  $count", icon).apply {
                horizontalAlignment = SwingConstants.LEFT
                margin = JBUI.insets(6, 8)
                alignmentX = LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
                toolTipText = "$count $description. Click to browse; select an item to navigate its history."
                accessibleContext.accessibleName = "$title: $count"
                accessibleContext.accessibleDescription = toolTipText
                isEnabled = data != null
                addActionListener { action(this) }
            }
            buttons += control; add(control); add(Box.createVerticalStrut(JBUI.scale(6)))
        }
        button("Branches", targets(GraphRefKind.BRANCH).size, AllIcons.Vcs.Branch, "local branches") {
            choose(it, "Local branches", targets(GraphRefKind.BRANCH))
        }
        button("Remotes", data?.repository?.remotes?.size ?: 0, GraphIcons.remote, "configured remotes (not remote branches)") { control ->
            val remotes = data?.repository?.remotes.orEmpty()
            if (remotes.isEmpty()) {
                choose(control, "Remotes", emptyList())
                return@button
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(remotes.map { it.name })
                .setTitle("Remotes · choose to browse branches")
                .setItemChosenCallback { remote ->
                    choose(control, "$remote · remote branches", targets(GraphRefKind.REMOTE).filter { it.revision.startsWith("refs/remotes/$remote/") })
                }.createPopup().showUnderneathOf(control)
        }
        button("Stashes", data?.stashes?.size ?: 0, GraphIcons.stash, "saved stashes") {
            choose(it, "Stashes", data?.stashes.orEmpty().map { stash -> GraphNavigationTarget("${stash.ref} · ${stash.subject}", stash.hash, stash.hash) })
        }
        button("Tags", targets(GraphRefKind.TAG).size, GraphIcons.tag, "tags") {
            choose(it, "Tags", targets(GraphRefKind.TAG))
        }
        button("Worktrees", data?.worktrees?.size ?: 0, AllIcons.Vcs.Folders, "working trees") {
            choose(it, "Worktrees · jump to HEAD", data?.worktrees.orEmpty().filter { tree -> tree.head.any { c -> c != '0' } }.map { tree ->
                GraphNavigationTarget("${tree.branch.removePrefix("refs/heads/").ifBlank { "Detached HEAD" }} · ${tree.path}", tree.head, tree.head)
            })
        }
        revalidate(); repaint()
    }

    private fun choose(anchor: JButton, title: String, items: List<GraphNavigationTarget>) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(if (items.isEmpty()) "$title · no entries" else title)
            .setItemChosenCallback(navigate).createPopup().showUnderneathOf(anchor)
    }
}
