package dev.bettergit.ui

import dev.bettergit.core.git.FileLineStats
import dev.bettergit.core.git.WorkingChange
import javax.swing.tree.DefaultMutableTreeNode
import java.util.concurrent.CancellationException

internal enum class ChangeArea(val title: String) {
    CONFLICTS("Conflicts"), STAGED("Staged Changes"), WORKING("Changes")
}

internal data class ChangeTreeEntry(
    val area: ChangeArea,
    val path: String,
    val label: String,
    val change: WorkingChange? = null,
    val count: Int = 0,
    val stats: FileLineStats? = null,
) {
    val key: String get() = "${area.name}:$path"
    val isGroup: Boolean get() = path.isEmpty()
}

internal object WorkingChangesTree {
    fun build(
        changes: List<WorkingChange>,
        stagedStats: Map<String, FileLineStats> = emptyMap(),
        workingStats: Map<String, FileLineStats> = emptyMap(),
    ): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        for (area in ChangeArea.entries) {
            val entries = changes.filter { when (area) {
                ChangeArea.CONFLICTS -> it.conflicted
                ChangeArea.STAGED -> it.staged
                ChangeArea.WORKING -> it.unstaged && !it.conflicted
            } }
            if (entries.isEmpty()) continue
            val group = DefaultMutableTreeNode(ChangeTreeEntry(area, "", area.title, count = entries.size))
            val folders = mutableMapOf("" to group)
            for (change in entries.sortedBy { it.path }) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Changed-file tree cancelled")
                val segments = change.path.split('/')
                var parent = group
                var directory = ""
                for (segment in segments.dropLast(1)) {
                    directory = if (directory.isEmpty()) segment else "$directory/$segment"
                    parent = folders.getOrPut(directory) {
                        DefaultMutableTreeNode(ChangeTreeEntry(area, directory, segment)).also(parent::add)
                    }
                }
                val stats = when (area) {
                    ChangeArea.STAGED -> stagedStats[change.path]
                    ChangeArea.WORKING -> workingStats[change.path]
                    ChangeArea.CONFLICTS -> null
                }
                parent.add(DefaultMutableTreeNode(ChangeTreeEntry(area, change.path, segments.last(), change, stats = stats)))
            }
            compactAndSort(group)
            root.add(group)
        }
        return root
    }

    private fun compactAndSort(node: DefaultMutableTreeNode) {
        val entry = node.userObject as ChangeTreeEntry
        if (entry.change != null) return
        while (!entry.isGroup && node.childCount == 1) {
            val child = node.firstChild as DefaultMutableTreeNode
            val childEntry = child.userObject as ChangeTreeEntry
            if (childEntry.change != null) break
            val current = node.userObject as ChangeTreeEntry
            node.userObject = current.copy(path = childEntry.path, label = "${current.label}/${childEntry.label}")
            node.removeAllChildren()
            child.children().toList().forEach { node.add(it as DefaultMutableTreeNode) }
        }
        val children = node.children().toList().map { it as DefaultMutableTreeNode }
        children.forEach(::compactAndSort)
        node.removeAllChildren()
        children.sortedWith(compareBy<DefaultMutableTreeNode> { (it.userObject as ChangeTreeEntry).change != null }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { (it.userObject as ChangeTreeEntry).label }).forEach(node::add)
    }

    fun changes(node: DefaultMutableTreeNode): List<WorkingChange> = node.depthFirstEnumeration().toList()
        .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? ChangeTreeEntry)?.change }
}
