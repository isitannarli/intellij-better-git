package dev.bettergit.core.git

import java.nio.charset.StandardCharsets.UTF_8

internal object DiffPreviewParser {
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
    private const val MAX_LINES = 18
    private const val MAX_LINE_BYTES = 600
    private const val MAX_HEADER_BYTES = 512

    fun preview(patch: String, originalLine: Int): DiffHunkPreview? {
        require(originalLine > 0) { "The original blame line must be one based" }
        var selectedHeader: String? = null
        val lines = mutableListOf<DiffPreviewLine>()
        var oldLine = 0
        var newLine = 0
        var binary = false
        for (line in patch.splitToSequence('\n')) {
            val header = hunkHeader.matchEntire(line)
            if (header != null) {
                if (selectedHeader != null) break
                val start = header.groupValues[3].toInt()
                val count = header.groupValues[4].toIntOrNull() ?: 1
                if (originalLine.toLong() in start.toLong() until start.toLong() + count) {
                    selectedHeader = line
                    oldLine = header.groupValues[1].toInt()
                    newLine = start
                }
                continue
            }
            if (selectedHeader == null) {
                if (line.startsWith("Binary files ") || line == "GIT binary patch") binary = true
                continue
            }
            if (line.startsWith("diff --git ")) break
            when (line.firstOrNull()) {
                ' ' -> lines += DiffPreviewLine(DiffLineKind.CONTEXT, line.drop(1), oldLine++, newLine++)
                '-' -> lines += DiffPreviewLine(DiffLineKind.REMOVED, line.drop(1), oldLine = oldLine++)
                '+' -> lines += DiffPreviewLine(DiffLineKind.ADDED, line.drop(1), newLine = newLine++)
                '\\' -> lines += DiffPreviewLine(DiffLineKind.INFO, line)
            }
        }
        if (selectedHeader == null) {
            return if (binary) DiffHunkPreview("Binary file", emptyList(), binary = true) else null
        }

        val focus = lines.indexOfFirst { it.newLine == originalLine }
        if (focus < 0) return null
        val first = (focus - MAX_LINES / 2).coerceIn(0, (lines.size - MAX_LINES).coerceAtLeast(0))
        val selected = lines.subList(first, (first + MAX_LINES).coerceAtMost(lines.size))
        var truncated = first > 0 || selected.size < lines.size
        val bounded = selected.map { line ->
            val text = boundUtf8(line.text, MAX_LINE_BYTES)
            if (text != line.text) truncated = true
            line.copy(text = text)
        }
        val header = boundUtf8(selectedHeader, MAX_HEADER_BYTES)
        if (header != selectedHeader) truncated = true
        return DiffHunkPreview(header, bounded, truncated)
    }

    private fun boundUtf8(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes - 3 
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return String(bytes, 0, end, UTF_8) + "…"
    }
}
