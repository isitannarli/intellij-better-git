package dev.bettergit.core.git

object EditorLineSelection {
    /** Offsets are UTF-16 and end-exclusive; a selection ending at column zero excludes that line. */
    fun range(text: CharSequence, startOffset: Int, endOffset: Int): LineRange? {
        require(startOffset in 0..text.length) { "Selection start is outside the document" }
        require(endOffset in startOffset..text.length) { "Selection end must follow its start and remain inside the document" }
        if (text.isEmpty()) return null
        if (startOffset == endOffset && startOffset == text.length && text.last() == '\n') return null

        val lastOffset = if (startOffset == endOffset) endOffset else endOffset - 1
        var line = 1
        var startLine = 1
        for (offset in 0 until lastOffset) {
            if (text[offset] == '\n') line++
            if (offset + 1 == startOffset) startLine = line
        }
        if (startOffset == lastOffset) startLine = line
        return LineRange(startLine, line)
    }
}
