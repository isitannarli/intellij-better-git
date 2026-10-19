package dev.bettergit.ui.graph

import dev.bettergit.core.graph.*
import dev.bettergit.core.git.GitStash
import dev.bettergit.core.git.GitWorktree
import dev.bettergit.services.RepositorySnapshot
import java.time.Instant

/** Build on a worker; painting reuses this snapshot without Git or history traversal. */
data class GraphSnapshot(
    val repository: RepositorySnapshot,
    val history: GraphHistory,
    val stashes: List<GitStash> = emptyList(),
    val worktrees: List<GitWorktree> = emptyList(),
) {
    val rows = ColoredCommitGraph.layout(history.commits.map { it.commit })
    val references = history.references.groupBy { it.hash }
    val maxChanges = history.commits.maxOfOrNull { it.changes.added + it.changes.removed }?.coerceAtLeast(1) ?: 1
    val branches = history.references.filter { it.kind != GraphRefKind.TAG }.sortedBy { it.name }
    val activity: List<ActivityBucket>
    val activityIndices = IntArray(history.commits.size)
    val firstTime = history.commits.minOfOrNull { it.committedAt } ?: Instant.EPOCH
    val lastTime = history.commits.maxOfOrNull { it.committedAt } ?: Instant.EPOCH
    val rowByHash = history.commits.mapIndexed { index, entry -> entry.commit.hash to index }.toMap()
    init {
        val buckets = Array(96) { mutableListOf<Int>() }
        val span = (lastTime.epochSecond - firstTime.epochSecond).coerceAtLeast(1)
        history.commits.forEachIndexed { index, entry ->
            val bucket = ((entry.committedAt.epochSecond - firstTime.epochSecond).toDouble() / span * 95).toInt().coerceIn(0, 95)
            buckets[bucket] += index
            activityIndices[index] = bucket
        }
        activity = buckets.map { indices ->
            ActivityBucket(indices, indices.sumOf { history.commits[it].changes.added + history.commits[it].changes.removed },
                indices.flatMap { references[history.commits[it].commit.hash].orEmpty() })
        }
    }
    /** Empty time intervals still navigate to the closest actual commit. */
    fun nearestRow(bucket: Int): Int? = activity.indices.filter { activity[it].count > 0 }
        .minByOrNull { kotlin.math.abs(it - bucket) }?.let { activity[it].firstRow }
    fun matches(query: String): List<Int> = if (query.isBlank()) emptyList() else history.commits.indices.filter {
        GraphSearch.matches(history.commits[it], references[history.commits[it].commit.hash].orEmpty(), query)
    }
}
data class ActivityBucket(val rows: List<Int>, val linesChanged: Long, val references: List<GraphReference>) {
    val count get() = rows.size
    val firstRow get() = rows.firstOrNull()
    val referenceKinds = references.map { it.kind }.toSet()
}
data class GraphScope(val title: String, val revision: String?) {
    override fun toString() = title
}
