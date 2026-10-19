package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import java.util.PriorityQueue

enum class WorkflowRefKind { BRANCH, REMOTE, TAG, HEAD, REF }

data class WorkflowRef(val name: String, val kind: WorkflowRefKind, val current: Boolean = false)

data class WorkflowNode(
    val commit: GitCommit,
    val column: Int,
    val lane: Int,
    val refs: List<WorkflowRef>,
) {
    val isMerge: Boolean get() = commit.parents.distinct().size > 1
}

data class WorkflowEdge(val parentHash: String, val childHash: String, val firstParent: Boolean)

data class WorkflowBoundary(val childHash: String, val parentHash: String, val firstParent: Boolean)

data class WorkflowGraph(
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val boundaries: List<WorkflowBoundary>,
    val laneCount: Int,
    val omittedCommits: Int,
)

object WorkflowLayout {
    const val MAX_COMMITS = 200

    fun layout(commits: List<GitCommit>): WorkflowGraph {
        val unique = commits.distinctBy(GitCommit::hash)
        val selected = unique.take(MAX_COMMITS)
        if (selected.isEmpty()) return WorkflowGraph(emptyList(), emptyList(), emptyList(), 0, 0)
        val byHash = selected.associateBy(GitCommit::hash)
        val inputOrder = selected.mapIndexed { index, commit -> commit.hash to index }.toMap()
        val childrenRemaining = selected.associate { it.hash to 0 }.toMutableMap()
        selected.forEach { commit ->
            commit.parents.distinct().filter(byHash::containsKey).forEach { parent ->
                childrenRemaining[parent] = childrenRemaining.getValue(parent) + 1
            }
        }
        val ready = PriorityQueue<String>(compareBy(inputOrder::getValue))
        childrenRemaining.filterValues { it == 0 }.keys.forEach(ready::add)
        val newestFirst = mutableListOf<GitCommit>()
        while (ready.isNotEmpty()) {
            val commit = byHash.getValue(ready.remove())
            newestFirst += commit
            commit.parents.distinct().filter(byHash::containsKey).forEach { parent ->
                val remaining = childrenRemaining.getValue(parent) - 1
                childrenRemaining[parent] = remaining
                if (remaining == 0) ready.add(parent)
            }
        }
        require(newestFirst.size == selected.size) { "Commit history contains an ancestry cycle" }

        val slots = mutableListOf<String?>()
        val nodes = mutableListOf<WorkflowNode>()
        val edges = mutableListOf<WorkflowEdge>()
        val boundaries = mutableListOf<WorkflowBoundary>()
        fun vacantLane(): Int {
            val vacant = slots.indexOf(null)
            if (vacant >= 0) return vacant
            slots.add(null)
            return slots.lastIndex
        }
        newestFirst.forEachIndexed { index, commit ->
            val lane = slots.indexOf(commit.hash).takeIf { it >= 0 } ?: vacantLane()
            nodes += WorkflowNode(commit, newestFirst.lastIndex - index, lane, parseRefs(commit.decorations))
            slots[lane] = null
            commit.parents.distinct().forEachIndexed { parentIndex, parent ->
                if (parent in byHash) {
                    edges += WorkflowEdge(parent, commit.hash, parentIndex == 0)
                    if (parent !in slots) {
                        val parentLane = if (parentIndex == 0 && slots[lane] == null) lane else vacantLane()
                        slots[parentLane] = parent
                    }
                } else {
                    boundaries += WorkflowBoundary(commit.hash, parent, parentIndex == 0)
                }
            }
        }
        return WorkflowGraph(nodes.sortedBy(WorkflowNode::column), edges, boundaries,
            nodes.maxOf { it.lane } + 1, unique.size - selected.size)
    }

    fun parseRefs(decorations: String): List<WorkflowRef> = decorations.trim().removeSurrounding("(", ")")
        .split(", ").map(String::trim).filter(String::isNotEmpty).map { ref ->
            when {
                ref.startsWith("HEAD -> ") -> WorkflowRef(ref.removePrefix("HEAD -> "), WorkflowRefKind.BRANCH, true)
                ref == "HEAD" -> WorkflowRef(ref, WorkflowRefKind.HEAD, true)
                ref.startsWith("tag: ") -> WorkflowRef(ref.removePrefix("tag: "), WorkflowRefKind.TAG)
                ref.startsWith("refs/heads/") -> WorkflowRef(ref.removePrefix("refs/heads/"), WorkflowRefKind.BRANCH)
                ref.startsWith("refs/remotes/") -> WorkflowRef(ref.removePrefix("refs/remotes/"), WorkflowRefKind.REMOTE)
                else -> WorkflowRef(ref, WorkflowRefKind.REF)
            }
        }.distinct()
}
