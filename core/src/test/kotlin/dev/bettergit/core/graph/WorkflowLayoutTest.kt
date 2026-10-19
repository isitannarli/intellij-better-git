package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkflowLayoutTest {
    @Test
    fun `empty history has no fabricated lanes or nodes`() {
        assertEquals(WorkflowGraph(emptyList(), emptyList(), emptyList(), 0, 0), WorkflowLayout.layout(emptyList()))
    }

    @Test
    fun `linear history runs older to newer in one lane`() {
        val graph = WorkflowLayout.layout(listOf(commit("tip", "middle"), commit("middle", "root"), commit("root")))
        assertEquals(listOf("root", "middle", "tip"), graph.nodes.map { it.commit.hash })
        assertEquals(listOf(0, 1, 2), graph.nodes.map { it.column })
        assertTrue(graph.nodes.all { it.lane == 0 })
        assertEquals(1, graph.laneCount)
        assertEquals(setOf("root" to "middle", "middle" to "tip"), graph.edges.map { it.parentHash to it.childHash }.toSet())
        assertTrue(graph.boundaries.isEmpty())
        assertTrue(graph.edges.all { it.firstParent })
    }

    @Test
    fun `merge paths retain both true parents and converge on their shared root`() {
        val graph = WorkflowLayout.layout(listOf(
            commit("merge", "main", "feature"), commit("main", "root"),
            commit("feature", "root"), commit("root"),
        ))
        val nodes = graph.nodes.associateBy { it.commit.hash }
        assertTrue(nodes.getValue("merge").isMerge)
        assertFalse(nodes.getValue("main").isMerge)
        assertEquals(0, nodes.getValue("main").lane)
        assertEquals(1, nodes.getValue("feature").lane)
        assertEquals(2, graph.laneCount)
        assertEquals(4, graph.edges.size)
        assertEquals(WorkflowEdge("feature", "merge", false), graph.edges.single { !it.firstParent })
        assertAncestry(graph)
    }

    @Test
    fun `unordered input and skewed author times cannot reverse ancestry`() {
        val graph = WorkflowLayout.layout(listOf(
            commit("root").copy(timestamp = Instant.ofEpochSecond(999)),
            commit("tip", "middle"), commit("middle", "root"),
        ))
        assertEquals(listOf("root", "middle", "tip"), graph.nodes.map { it.commit.hash })
        assertAncestry(graph)
    }

    @Test
    fun `unloaded parents are boundary records rather than imaginary nodes`() {
        val graph = WorkflowLayout.layout(listOf(commit("merge", "outside-main", "feature"), commit("feature", "outside-feature")))
        assertEquals(setOf("merge", "feature"), graph.nodes.map { it.commit.hash }.toSet())
        assertEquals(listOf(WorkflowEdge("feature", "merge", false)), graph.edges)
        assertEquals(setOf(
            WorkflowBoundary("merge", "outside-main", true), WorkflowBoundary("feature", "outside-feature", true),
        ), graph.boundaries.toSet())
        assertAncestry(graph)
    }

    @Test
    fun `latest two hundred entries are bounded and their older ancestry remains explicit`() {
        val commits = (210 downTo 1).map { commit("c$it", "c${it - 1}") }
        val graph = WorkflowLayout.layout(commits)
        assertEquals(200, graph.nodes.size)
        assertEquals(10, graph.omittedCommits)
        assertEquals("c11", graph.nodes.first().commit.hash)
        assertEquals("c210", graph.nodes.last().commit.hash)
        assertEquals(listOf(WorkflowBoundary("c11", "c10", true)), graph.boundaries)
        assertAncestry(graph)
    }

    @Test
    fun `duplicates and octopus merges preserve edges once without clipping lanes`() {
        val branches = (1..24).map { "branch$it" }
        val merge = commit("merge", *(branches + branches.first()).toTypedArray())
        val graph = WorkflowLayout.layout(listOf(merge, merge) + branches.map { commit(it, "root") } + commit("root"))
        assertEquals(26, graph.nodes.size)
        assertEquals(24, graph.laneCount)
        assertEquals(48, graph.edges.size)
        assertAncestry(graph)
    }

    @Test
    fun `separate tips share no fictional edge and layout is deterministic`() {
        val history = listOf(commit("main", "root"), commit("feature", "root"), commit("root"), commit("unrelated"))
        val graph = WorkflowLayout.layout(history)
        assertEquals(graph, WorkflowLayout.layout(history))
        assertEquals(2, graph.edges.size)
        assertTrue(graph.edges.none { it.childHash == "unrelated" || it.parentHash == "unrelated" })
        assertAncestry(graph)
    }

    @Test
    fun `refs preserve head tags and names without guessing environments or remotes`() {
        assertEquals(listOf(
            WorkflowRef("main", WorkflowRefKind.BRANCH, true),
            WorkflowRef("v1.2", WorkflowRefKind.TAG), WorkflowRef("origin/main", WorkflowRefKind.REF),
            WorkflowRef("feature/a,b", WorkflowRefKind.REF),
        ), WorkflowLayout.parseRefs("HEAD -> main, tag: v1.2, origin/main, feature/a,b"))
        assertEquals(listOf(WorkflowRef("HEAD", WorkflowRefKind.HEAD, true)), WorkflowLayout.parseRefs("HEAD"))
        assertEquals(listOf(WorkflowRef("release", WorkflowRefKind.BRANCH), WorkflowRef("upstream/main", WorkflowRefKind.REMOTE)),
            WorkflowLayout.parseRefs("refs/heads/release, refs/remotes/upstream/main"))
        assertTrue(WorkflowLayout.parseRefs("").isEmpty())
    }

    @Test
    fun `malformed ancestry cycles are rejected instead of drawing misleading arrows`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowLayout.layout(listOf(commit("a", "b"), commit("b", "a")))
        }
    }

    private fun assertAncestry(graph: WorkflowGraph) {
        val nodes = graph.nodes.associateBy { it.commit.hash }
        graph.edges.forEach {
            val parent = nodes.getValue(it.parentHash)
            val child = nodes.getValue(it.childHash)
            assertTrue(parent.column < child.column)
            assertTrue(it.parentHash in child.commit.parents)
        }
        assertTrue(graph.nodes.all { it.lane in 0 until graph.laneCount })
        graph.boundaries.forEach { assertTrue(it.parentHash !in nodes) }
    }

    private fun commit(hash: String, vararg parents: String) = GitCommit(
        hash, "Developer", "developer@example.test", Instant.EPOCH, hash, parents.toList(),
    )
}
