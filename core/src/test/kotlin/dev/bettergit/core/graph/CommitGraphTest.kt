package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class CommitGraphTest {
    @Test
    fun `wide graph keeps edge destinations valid as lanes collapse`() {
        val parents = (0 until 600).map { "branch-$it" }
        val commits = listOf(commit("merge", *parents.toTypedArray())) + parents.map { commit(it, "root") } + commit("root")
        val rows = CommitGraph.layout(commits)
        assertEquals(600, rows.first().lanes)
        assertEquals(1, rows.last().lanes)
        assertEquals(parents.indices.toList(), rows.first().edges.map { it.to })
        assertValidCoordinates(rows)
    }

    @Test
    fun `cancelled graph preparation stops promptly`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                CommitGraph.layout(listOf(commit("tip", "root")))
            }
        } finally { Thread.interrupted() }
    }

    @Test
    fun `empty history has no lanes`() {
        assertTrue(CommitGraph.layout(emptyList()).isEmpty())
    }

    @Test
    fun `linear history stays in a single lane and root has no outgoing edge`() {
        val rows = CommitGraph.layout(listOf(commit("new", "middle"), commit("middle", "root"), commit("root")))

        assertEquals(listOf(0, 0, 0), rows.map(GraphRow::lane))
        assertEquals(listOf(1, 1, 1), rows.map(GraphRow::lanes))
        assertEquals(listOf(false, true, true), rows.map(GraphRow::hasIncoming))
        assertEquals(listOf(GraphEdge(0, 0, true)), rows.first().edges)
        assertTrue(rows.last().edges.isEmpty())
    }

    @Test
    fun `merge retains both parent lanes until their shared ancestor`() {
        val rows = CommitGraph.layout(listOf(
            commit("merge", "main", "feature"),
            commit("main", "root"),
            commit("feature", "root"),
            commit("root"),
        ))

        assertEquals(listOf(GraphEdge(0, 0, true), GraphEdge(0, 1, true)), rows[0].edges)
        assertEquals(listOf(GraphEdge(1, 1), GraphEdge(0, 0, true)), rows[1].edges)
        assertEquals(1, rows[2].lane)
        assertEquals(listOf(GraphEdge(0, 0), GraphEdge(1, 0, true)), rows[2].edges)
        assertEquals(1, rows.last().lanes)
        assertTrue(rows.last().hasIncoming)
        assertValidCoordinates(rows)
    }

    @Test
    fun `independent branch tip starts a new lane without a fictional incoming edge`() {
        val rows = CommitGraph.layout(listOf(commit("main", "root"), commit("feature", "root"), commit("root")))

        assertEquals(1, rows[1].lane)
        assertFalse(rows[1].hasIncoming)
        assertEquals(listOf(GraphEdge(0, 0), GraphEdge(1, 0, true)), rows[1].edges)
        assertTrue(rows.last().hasIncoming)
    }

    @Test
    fun `duplicate parents do not duplicate edges or consume extra lanes`() {
        val rows = CommitGraph.layout(listOf(
            commit("merge", "main", "main", "feature", "feature"),
            commit("main", "root"),
            commit("feature", "root"),
            commit("root"),
        ))

        assertEquals(2, rows.first().lanes)
        assertEquals(listOf(GraphEdge(0, 0, true), GraphEdge(0, 1, true)), rows.first().edges)
        assertValidCoordinates(rows)
    }

    @Test
    fun `octopus merge preserves more than eighteen lanes without losing ancestry`() {
        val heads = (0 until 24).map { "branch-$it" }
        val history = listOf(commit("merge", *heads.toTypedArray())) + heads.map { commit(it, "root") } + commit("root")
        val rows = CommitGraph.layout(history)

        assertEquals(24, rows.first().lanes)
        assertEquals((0 until 24).toList(), rows.first().edges.map(GraphEdge::to))
        assertEquals(24, rows.first().edges.size)
        assertEquals(1, rows.last().lanes)
        assertValidCoordinates(rows)
    }

    @Test
    fun `truncated history keeps parent edges open at its boundary`() {
        val row = CommitGraph.layout(listOf(commit("tip", "parent-outside-window"))).single()

        assertEquals(listOf(GraphEdge(0, 0, true)), row.edges)
        assertEquals(1, row.lanes)
    }

    @Test
    fun `unrelated root commits reuse the vacant lane`() {
        val rows = CommitGraph.layout(listOf(commit("first-root"), commit("second-root")))

        assertEquals(listOf(0, 0), rows.map(GraphRow::lane))
        assertTrue(rows.all { !it.hasIncoming && it.edges.isEmpty() })
    }

    private fun assertValidCoordinates(rows: List<GraphRow>) {
        rows.forEach { row ->
            assertTrue(row.lane in 0 until row.lanes)
            row.edges.forEach { edge ->
                assertTrue(edge.from in 0 until row.lanes, "Edge starts outside the visible lanes: $edge")
                assertTrue(edge.to in 0 until row.lanes, "Edge ends outside the visible lanes: $edge")
            }
        }
    }

    private fun commit(hash: String, vararg parents: String) = GitCommit(
        hash, "Developer", "developer@example.test", Instant.EPOCH, hash, parents.toList(),
    )
}
