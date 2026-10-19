package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EditorLineSelectionTest {
    @Test fun `selection ending at next line column zero excludes that line`() {
        assertEquals(LineRange(1, 1), EditorLineSelection.range("one\ntwo\nthree", 0, 4))
        assertEquals(LineRange(2, 2), EditorLineSelection.range("one\ntwo\nthree", 4, 8))
    }

    @Test fun `selection spanning partial lines includes both touched lines`() {
        assertEquals(LineRange(1, 2), EditorLineSelection.range("one\ntwo\nthree", 2, 6))
        assertEquals(LineRange(2, 3), EditorLineSelection.range("one\ntwo\nthree", 5, 10))
    }

    @Test fun `equal offsets target the caret line without changing selection`() {
        val text = "one\ntwo\nthree"
        assertEquals(LineRange(1, 1), EditorLineSelection.range(text, 0, 0))
        assertEquals(LineRange(1, 1), EditorLineSelection.range(text, 3, 3))
        assertEquals(LineRange(2, 2), EditorLineSelection.range(text, 4, 4))
        assertEquals(LineRange(3, 3), EditorLineSelection.range(text, text.length, text.length))
    }

    @Test fun `empty document and trailing visual line have no committed line`() {
        assertNull(EditorLineSelection.range("", 0, 0))
        assertNull(EditorLineSelection.range("one\n", 4, 4))
        assertNull(EditorLineSelection.range("one\r\n", 5, 5))
    }

    @Test fun `real blank lines are valid while final virtual line is excluded`() {
        assertEquals(LineRange(1, 1), EditorLineSelection.range("\n", 0, 0))
        assertEquals(LineRange(1, 1), EditorLineSelection.range("\n", 0, 1))
        assertNull(EditorLineSelection.range("\n", 1, 1))
        assertEquals(LineRange(2, 2), EditorLineSelection.range("a\n\n", 2, 2))
        assertEquals(LineRange(2, 2), EditorLineSelection.range("a\n\n", 2, 3))
        assertNull(EditorLineSelection.range("a\n\n", 3, 3))
    }

    @Test fun `whole document selections do not count terminal newline as extra line`() {
        assertEquals(LineRange(1, 2), EditorLineSelection.range("a\nb\n", 0, 4))
        assertEquals(LineRange(1, 2), EditorLineSelection.range("a\nb", 0, 3))
        assertEquals(LineRange(2, 2), EditorLineSelection.range("a\nb", 2, 3))
    }

    @Test fun `CRLF counts as one line boundary without normalizing offsets`() {
        val text = "a\r\nb\r\nc"
        assertEquals(LineRange(1, 1), EditorLineSelection.range(text, 0, 3))
        assertEquals(LineRange(2, 2), EditorLineSelection.range(text, 3, 6))
        assertEquals(LineRange(1, 2), EditorLineSelection.range(text, 2, 4))
        assertEquals(LineRange(2, 2), EditorLineSelection.range(text, 3, 3))
        assertEquals(LineRange(3, 3), EditorLineSelection.range(text, 6, 7))
    }

    @Test fun `UTF16 offsets match editor offsets for nonBMP text`() {
        val text = "\uD83D\uDE80\nnext"
        assertEquals(LineRange(1, 1), EditorLineSelection.range(text, 0, 3))
        assertEquals(LineRange(2, 2), EditorLineSelection.range(text, 3, 3))
        assertEquals(LineRange(1, 2), EditorLineSelection.range(text, 1, 4))
    }

    @Test fun `carriage return without LF is content as in Git line counting`() {
        assertEquals(LineRange(1, 1), EditorLineSelection.range("a\rb", 0, 3))
    }

    @Test fun `invalid offsets fail instead of silently selecting a different line`() {
        assertThrows(IllegalArgumentException::class.java) { EditorLineSelection.range("abc", -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { EditorLineSelection.range("abc", 1, 4) }
        assertThrows(IllegalArgumentException::class.java) { EditorLineSelection.range("abc", 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { EditorLineSelection.range("abc", 4, 4) }
    }
}
