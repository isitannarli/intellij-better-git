package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffPreviewParserTest {
    @Test
    fun `preview uses historical new-side coordinates instead of the first hunk`() {
        val patch = """
            diff --git a/code.kt b/code.kt
            --- a/code.kt
            +++ b/code.kt
            @@ -1,2 +1,2 @@
            -unrelated old
            +unrelated new
             context
            @@ -30,3 +31,4 @@ function example
             context before
            -old value
            +new value
            +inserted value
             context after
        """.trimIndent()

        val preview = requireNotNull(DiffPreviewParser.preview(patch, 32))
        assertEquals("@@ -30,3 +31,4 @@ function example", preview.header)
        assertEquals(listOf(
            DiffPreviewLine(DiffLineKind.CONTEXT, "context before", 30, 31),
            DiffPreviewLine(DiffLineKind.REMOVED, "old value", oldLine = 31),
            DiffPreviewLine(DiffLineKind.ADDED, "new value", newLine = 32),
            DiffPreviewLine(DiffLineKind.ADDED, "inserted value", newLine = 33),
            DiffPreviewLine(DiffLineKind.CONTEXT, "context after", 32, 34),
        ), preview.lines)
        assertFalse(preview.truncated)
    }

    @Test
    fun `single-line hunks with omitted counts retain missing-newline markers`() {
        val patch = "@@ -8 +9 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
        val preview = requireNotNull(DiffPreviewParser.preview(patch, 9))

        assertEquals(8, preview.lines.first().oldLine)
        assertEquals(9, preview.lines.single { it.kind == DiffLineKind.ADDED }.newLine)
        assertEquals(2, preview.lines.count { it.kind == DiffLineKind.INFO })
    }

    @Test
    fun `deletion-only or unrelated hunks are never substituted for the requested line`() {
        assertNull(DiffPreviewParser.preview("@@ -8,2 +7,0 @@\n-old\n-old2\n", 7))
        assertNull(DiffPreviewParser.preview("@@ -1 +1 @@\n-old\n+new\n", 10))
    }

    @Test
    fun `large hunks are clipped around the target line preserving original numbers`() {
        val patch = "@@ -0,0 +1,100 @@\n" + (1..100).joinToString("\n", postfix = "\n") { "+line $it" }
        val preview = requireNotNull(DiffPreviewParser.preview(patch, 90))

        assertEquals(18, preview.lines.size)
        assertTrue(preview.truncated)
        assertEquals("line 90", preview.lines.single { it.newLine == 90 }.text)
        assertTrue(preview.lines.all { requireNotNull(it.newLine) > 1 && it.oldLine == null })
    }

    @Test
    fun `long Unicode lines and function headers have a UTF8 budget without broken characters`() {
        val text = "👩‍💻\u00E9".repeat(1000)
        val patch = "@@ -0,0 +1,20 @@ $text\n" + (1..20).joinToString("\n", postfix = "\n") { "+$text" }
        val preview = requireNotNull(DiffPreviewParser.preview(patch, 10))

        assertTrue(preview.truncated)
        assertTrue(preview.lines.size <= 18)
        assertTrue(preview.header.toByteArray().size + preview.lines.sumOf { it.text.toByteArray().size } < 12 * 1024)
        assertTrue(preview.lines.all { it.text.endsWith("…") && '\uFFFD' !in it.text })
        assertTrue(preview.lines.any { it.newLine == 10 })
    }

    @Test
    fun `binary changes have an explicit unavailable-text state`() {
        val preview = requireNotNull(DiffPreviewParser.preview("Binary files a/image.png and b/image.png differ\n", 1))

        assertTrue(preview.binary)
        assertTrue(preview.lines.isEmpty())
        assertFalse(preview.truncated)
    }
}
