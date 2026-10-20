package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import java.util.concurrent.CancellationException

data class GraphEdge(val from: Int, val to: Int, val startsAtCommit: Boolean = false)
data class GraphRow(val lane: Int, val lanes: Int, val edges: List<GraphEdge>, val hasIncoming: Boolean)

object CommitGraph {
    fun layout(commits: List<GitCommit>): List<GraphRow> {
        val lanes = mutableListOf<String>()
        return commits.map { commit ->
            if (Thread.currentThread().isInterrupted) throw CancellationException("Commit graph cancelled")
            val incoming = commit.hash in lanes
            if (!incoming) lanes += commit.hash
            val before = lanes.toList()
            val lane = lanes.indexOf(commit.hash)
            val parents = commit.parents.distinct()
            lanes.removeAt(lane)
            parents.forEachIndexed { index, parent ->
                if (parent !in lanes) lanes.add((lane + index).coerceAtMost(lanes.size), parent)
            }
            // Resolve each lane once; indexOf for every edge was quadratic in active branch count.
            val destinations = lanes.withIndex().associate { it.value to it.index }
            val edges = before.mapIndexedNotNull { index, hash ->
                if (hash == commit.hash) null else GraphEdge(index, destinations.getValue(hash))
            } + parents.map { GraphEdge(lane, destinations.getValue(it), true) }
            GraphRow(lane, maxOf(before.size, lanes.size), edges, incoming)
        }
    }
}
