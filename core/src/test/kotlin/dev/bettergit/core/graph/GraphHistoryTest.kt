package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class GraphHistoryTest {
    private fun c(hash: String, vararg parents: String) = GitCommit(hash, "Maya Chen", "maya@example.test", Instant.EPOCH, "fix: handle retry", parents.toList())

    @Test fun `colors follow ancestry after lanes compact`() {
        val history = listOf(c("merge", "main", "feature"), c("main", "root"), c("feature", "root"), c("root"))
        val rows = ColoredCommitGraph.layout(history)
        assertEquals(listOf(0, 0, 1, 0), rows.map { it.color })
        assertEquals(listOf(0, 1), rows.first().edges.map { it.color })
        assertEquals(1, rows[2].edges.last().color)
        assertEquals(0, rows.last().color)
    }

    @Test fun `octopus colors retain every edge and independent roots receive new colors`() {
        val heads = (0..23).map { "branch-$it" }
        val history = listOf(c("merge", *heads.toTypedArray())) + heads.map { c(it, "root") } + c("root")
        val rows = ColoredCommitGraph.layout(history)
        assertEquals(24, rows.first().edges.map { it.color }.distinct().size)
        assertEquals(CommitGraph.layout(history), rows.map { it.row })
        assertEquals(listOf(0, 1), ColoredCommitGraph.layout(listOf(c("one"), c("two"))).map { it.color })
    }

    @Test fun `pathological history is rejected before unbounded edge allocation`() {
        val history = (0..99).map { c("tip-$it", "root") } + c("root")
        assertThrows(IllegalArgumentException::class.java) { ColoredCommitGraph.layout(history, 100) }
        assertEquals(history.size, ColoredCommitGraph.layout(history, 10_000).size)
    }

    @Test fun `search combines quoted fields without filtering topology`() {
        val entry = GraphCommit(c("abc123"), GraphChanges(2, 10, 4), Instant.EPOCH)
        val refs = listOf(GraphReference("feature/retry", "refs/heads/feature/retry", "abc123", GraphRefKind.BRANCH))
        assertTrue(GraphSearch.matches(entry, refs, "author:\"Maya Chen\" ref:retry sha:abc message:retry"))
        assertTrue(GraphSearch.matches(entry, refs, "\"handle retry\""))
        assertFalse(GraphSearch.matches(entry, refs, "author:Alex"))
        assertFalse(GraphSearch.matches(entry, refs, "ref:main"))
    }
}
