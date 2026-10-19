package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit

data class GraphEdge(val from: Int, val to: Int, val startsAtCommit: Boolean = false)
data class GraphRow(val lane: Int, val lanes: Int, val edges: List<GraphEdge>, val hasIncoming: Boolean)

object CommitGraph {
    fun layout(commits: List<GitCommit>): List<GraphRow> {
        val lanes = mutableListOf<String>()
        return commits.map { commit ->
            val incoming = commit.hash in lanes
            if (!incoming) lanes += commit.hash
            val before = lanes.toList()
            val lane = lanes.indexOf(commit.hash)
            val parents = commit.parents.distinct()
            lanes.removeAt(lane)
            parents.forEachIndexed { index, parent ->
                if (parent !in lanes) lanes.add((lane + index).coerceAtMost(lanes.size), parent)
            }
            val edges = before.mapIndexedNotNull { index, hash ->
                if (hash == commit.hash) null else GraphEdge(index, lanes.indexOf(hash))
            } + parents.map { GraphEdge(lane, lanes.indexOf(it), true) }
            GraphRow(lane, maxOf(before.size, lanes.size), edges, incoming)
        }
    }
}
