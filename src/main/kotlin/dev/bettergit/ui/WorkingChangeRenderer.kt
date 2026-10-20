package dev.bettergit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.git.WorkingChange
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/** The native tree UI shrinks this full-width renderer to the viewport after indentation. */
internal class WorkingChangeRenderer(project: Project, repositoryRoot: () -> Path?) : JPanel(BorderLayout(JBUI.scale(12), 0)), TreeCellRenderer {
    private var tree: JTree? = null
    private var tooltipDetails = emptyList<String>()
    private val name = NameRenderer(project, repositoryRoot)
    private val counts = SimpleColoredComponent().apply {
        isOpaque = false
        // Native hover/selection ends 12 px before the viewport edge; leave another 8 px inside it.
        // Keep the same inset in every state so the count column does not jump on hover.
        ipad = JBUI.insets(0, 0, 0, 20)
        name = "Line change counts"
    }

    init {
        isOpaque = false
        add(name, BorderLayout.CENTER)
        add(counts, BorderLayout.EAST)
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        return Dimension(maxOf(preferred.width, tree?.visibleRect?.width ?: 0), preferred.height)
    }

    // CellRendererPane reuses this panel at different widths for each folder depth.
    override fun validate() = doLayout()

    override fun getTreeCellRendererComponent(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
        this.tree = tree
        name.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
        counts.clear()
        counts.font = tree.font
        val item = (value as? DefaultMutableTreeNode)?.userObject as? ChangeTreeEntry
        val change = item?.change
        val stats = item?.stats
        val description = when {
            change == null -> ""
            change.conflicted -> "Resolve conflicts to see line counts"
            change.submodule -> "Submodule change"
            stats == null -> "Line counts unavailable"
            stats.binary -> "Binary file"
            else -> "${stats.added} added lines, ${stats.removed} removed lines"
        }
        when {
            change == null -> Unit
            stats == null -> counts.append("—", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            stats.binary -> counts.append("Binary", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            else -> {
                counts.append("+${stats.added}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addedColor))
                counts.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                counts.append("−${stats.removed}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, removedColor))
            }
        }
        val details = listOfNotNull(name.toolTipText, description.takeIf { it.isNotEmpty() })
        getAccessibleContext().accessibleName = details.joinToString(" · ").ifEmpty { item?.label }
        tooltipDetails = details
        counts.toolTipText = description
        counts.getAccessibleContext().accessibleName = description
        counts.invalidate()
        invalidate()
        return this
    }

    // Wrapping requires font measurement; do it only for the hovered row, never during layout/painting.
    override fun getToolTipText(): String? {
        val owner = tree ?: return null
        if (tooltipDetails.isEmpty()) return null
        val width = minOf(JBUI.scale(360), owner.visibleRect.width - JBUI.scale(40)).coerceAtLeast(1)
        val metrics = owner.getFontMetrics(UIUtil.getToolTipFont())
        return tooltipDetails.flatMap { UIUtil.splitText(it, metrics, width, ' ').toList() }
            .joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    }

    private class NameRenderer(private val project: Project, private val repositoryRoot: () -> Path?) : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? ChangeTreeEntry ?: return
            val change = item.change
            toolTipText = null
            if (item.isGroup) {
                icon = null
                append(item.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            } else if (change == null) {
                icon = AllIcons.Nodes.Folder
                append(plain(item.label))
            } else {
                val code = when {
                    change.conflicted -> 'U'
                    change.untracked -> '?'
                    item.area == ChangeArea.STAGED -> change.indexStatus
                    else -> change.workTreeStatus
                }
                val color = when (code) {
                    'A', '?' -> FileStatus.ADDED.color
                    'D' -> FileStatus.DELETED.color
                    'U' -> FileStatus.MERGED_WITH_CONFLICTS.color
                    else -> FileStatus.MODIFIED.color
                }
                icon = fileIcon(change)
                append(plain(item.label), SimpleTextAttributes(if (code == 'D') SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN, if (selected) null else color))
                append("   ${if (change.untracked) "U" else code}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, if (selected) null else color))
                change.oldPath?.let { append("  ← ${plain(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                toolTipText = plain("${change.path} · ${if (change.untracked) "Untracked" else if (change.conflicted) "Conflict" else "${item.area.title}: $code"}")
            }
        }

        private fun fileIcon(change: WorkingChange): Icon {
            val root = repositoryRoot()
            if (root != null && !project.isDisposed) {
                if (change.submodule) return AllIcons.Nodes.Folder
                // Native deferred icons honor file icon providers and patchers across supported IDEs.
                // A renderer must not scan disk to populate VFS for an uncached/deleted path.
                LocalFileSystem.getInstance().findFileByPathIfCached(root.resolve(change.path).toString().replace('\\', '/'))
                    ?.takeIf { it.isValid }?.let { return IconUtil.getIcon(it, 0, project) }
            }
            // Deleted files still use the IDE's filename associations, including exact-name rules.
            return FileTypeManager.getInstance().getFileTypeByFileName(change.path.substringAfterLast('/')).icon
                ?: AllIcons.FileTypes.Unknown
        }
    }

    companion object {
        // Preserve distinct green/red counts in both themes, including selected rows.
        private val addedColor = JBColor(Color(0x1A7F37), Color(0x6CCB8B))
        private val removedColor = JBColor(Color(0xC53636), Color(0xF28585))
        private val controlCharacters = Regex("[\\p{Cntrl}\\p{Cf}]")
        private fun plain(text: String) = text.replace(controlCharacters, " ")
    }
}
