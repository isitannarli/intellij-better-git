package dev.bettergit.services

import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.CommitGraph
import dev.bettergit.core.graph.GraphRow
import dev.bettergit.core.graph.WorkflowGraph
import dev.bettergit.core.graph.WorkflowLayout
import dev.bettergit.core.reports.ActivityReport

internal data class HistorySnapshot(
    val commits: List<GitCommit>,
    val graph: Map<String, GraphRow>,
    val workflow: WorkflowGraph,
    val report: ActivityReport,
    val limit: Int,
) {
    companion object {
        fun prepare(commits: List<GitCommit>, limit: Int) = HistorySnapshot(
            commits, commits.map(GitCommit::hash).zip(CommitGraph.layout(commits)).toMap(),
            WorkflowLayout.layout(commits), ActivityReport.from(commits), limit,
        )
    }
}
