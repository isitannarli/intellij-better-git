package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class BlameSpansTest {
    private val first = GitCommit("a".repeat(40), "Author", "a@example.test", Instant.EPOCH, "First")
    private val second = first.copy(hash = "b".repeat(40), subject = "Second")

    @Test
    fun `contiguous same-commit lines become a single inclusive span`() {
        val lines = (1..20_000).map { BlameLine(it, first, "file.kt", it) }
        assertEquals(listOf(BlameSpan(1, 20_000, first)), BlameSpans.compact(lines))
    }

    @Test
    fun `uncommitted gaps and different authorship split spans`() {
        val lines = listOf(BlameLine(1, first), BlameLine(2, first), BlameLine(3, null),
            BlameLine(4, first), BlameLine(5, second), BlameLine(6, second))
        assertEquals(listOf(BlameSpan(1, 2, first), BlameSpan(4, 4, first), BlameSpan(5, 6, second)), BlameSpans.compact(lines))
    }

    @Test
    fun `nonadjacent out of order and duplicate line numbers never imply continuous coverage`() {
        assertEquals(listOf(BlameSpan(1, 1, first), BlameSpan(3, 3, first), BlameSpan(2, 2, first), BlameSpan(2, 2, first)),
            BlameSpans.compact(listOf(1, 3, 2, 2).map { BlameLine(it, first) }))
    }

    @Test
    fun `empty uncommitted and alternating worst-case input stays exact`() {
        assertTrue(BlameSpans.compact(emptyList()).isEmpty())
        assertTrue(BlameSpans.compact(listOf(BlameLine(1, null))).isEmpty())
        val alternating = (1..20_000).map { BlameLine(it, if (it % 2 == 0) first else second) }
        val spans = BlameSpans.compact(alternating)
        assertEquals(20_000, spans.size)
        assertEquals(alternating.map { it.line }, spans.map { it.startLine })
        assertTrue(spans.all { it.startLine == it.endLine })
    }
}
