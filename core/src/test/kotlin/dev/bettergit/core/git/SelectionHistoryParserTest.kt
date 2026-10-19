package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SelectionHistoryParserTest {
    @Test
    fun `insertions before a selection shift its HEAD coordinates without claiming local changes within it`() {
        val mapping = map(LineRange(4, 5), 6, "@@ -1,0 +2,2 @@\n+inserted\n+inserted again\n")
        assertEquals(LineRange(2, 3), mapping.range)
        assertFalse(mapping.approximate)
        assertTrue(mapping.patch.isEmpty())
    }

    @Test
    fun `deleted prefix shifts current lines forward into HEAD`() {
        val mapping = map(LineRange(1, 2), 3, "@@ -1,2 +0,0 @@\n-old first\n-old second\n")
        assertEquals(LineRange(3, 4), mapping.range)
        assertFalse(mapping.approximate)
        assertTrue(mapping.patch.isEmpty())
    }

    @Test
    fun `fully inserted selection has no inherited range`() {
        val mapping = map(LineRange(2, 3), 4, "@@ -1,0 +2,2 @@\n+new A\n+new B\n")
        assertNull(mapping.range)
        assertFalse(mapping.approximate)
        assertTrue(mapping.patch.contains("+new A\n+new B\n"))
    }

    @Test
    fun `a partial replacement maps the whole old hunk and explicitly marks approximation`() {
        val mapping = map(LineRange(3, 3), 5, "@@ -2,2 +2,3 @@\n-old A\n-old B\n+new A\n+new B\n+new C\n")
        assertEquals(LineRange(2, 3), mapping.range)
        assertTrue(mapping.approximate)
        assertTrue(mapping.patch.contains("-old B"))
    }

    @Test
    fun `selection spanning a deletion discloses the wider inherited range`() {
        val mapping = map(LineRange(1, 2), 2, "@@ -2,2 +1,0 @@\n-deleted A\n-deleted B\n")
        assertEquals(LineRange(1, 4), mapping.range)
        assertTrue(mapping.approximate)
        assertTrue(mapping.patch.contains("-deleted A"))
    }

    @Test
    fun `only overlapping hunks appear in the local patch and filenames are quoted`() {
        val patch = "@@ -1 +1 @@\n-old top\n+new top\n@@ -5 +5 @@\n-old bottom\n+new bottom\n\\ No newline at end of file\n"
        val mapping = SelectionHistoryParser.map(LineRange(5, 5), 5, patch, "old\nname", "new\tname")
        assertFalse(mapping.patch.contains("new top"))
        assertTrue(mapping.patch.contains("new bottom"))
        assertTrue(mapping.patch.contains("\\ No newline at end of file"))
        assertTrue(mapping.patch.startsWith("--- \"a/old\\nname\"\n+++ \"b/new\\tname\"\n"))
    }

    @Test
    fun `NUL metadata separates commits from deceptive source text`() {
        val firstHash = "1".repeat(40)
        val secondHash = "2".repeat(40)
        val patch = "diff --git a/file b/file\n@@ -1 +1 @@\n-commit $secondHash\n+@@ -200 +500 @@\n"
        val output = record(firstHash, "Subject with a tab\tinside", secondHash, patch) + record(secondHash, "Root", "", "@@ -0,0 +1 @@\n+root\n")
        val entries = SelectionHistoryParser.entries(output)
        assertEquals(listOf(firstHash, secondHash), entries.map { it.commit.hash })
        assertEquals(patch, entries.first().patch)
        assertEquals(listOf(secondHash), entries.first().commit.parents)
        assertTrue(entries.last().commit.parents.isEmpty())
        assertEquals("Subject with a tab\tinside", entries.first().commit.subject)
    }

    @Test
    fun `malformed records and invalid coordinates fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) { SelectionHistoryParser.entries("unframed text") }
        assertThrows(IllegalArgumentException::class.java) { SelectionHistoryParser.entries("\u0000" + "1".repeat(40)) }
        assertThrows(IllegalArgumentException::class.java) { LineRange(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { LineRange(2, 1) }
    }

    private fun map(range: LineRange, currentLines: Int, patch: String) = SelectionHistoryParser.map(range, currentLines, patch, "file", "file")
    private fun record(hash: String, subject: String, parents: String, patch: String) =
        "\u0000" + listOf(hash, "Developer", "dev@example.test", "1700000000", subject, parents, "").joinToString("\u0000") + "\u0000\n\n" + patch
}
