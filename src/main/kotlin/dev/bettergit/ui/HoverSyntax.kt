package dev.bettergit.ui

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.bettergit.core.git.DiffLineKind
import dev.bettergit.core.git.DiffPreviewLine
import java.awt.Color
import java.awt.Font
import java.util.concurrent.CancellationException

internal data class HoverCodeFragment(val text: String, val foreground: Color, val fontStyle: Int)

internal object HoverSyntax {
    private const val MAX_TOKENS = 4096
    private data class Token(val start: Int, val end: Int, val color: Color, val style: Int)

    fun resolve(project: Project, path: String): SyntaxHighlighter? = safely {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val type = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        if (type.isBinary) null else SyntaxHighlighterFactory.getSyntaxHighlighter(type, project, null)
    }

    fun highlight(
        lines: List<DiffPreviewLine>,
        before: SyntaxHighlighter?,
        after: SyntaxHighlighter?,
        scheme: EditorColorsScheme,
    ): List<List<HoverCodeFragment>> {
        fun version(removed: Boolean, highlighter: SyntaxHighlighter?): Map<Int, List<HoverCodeFragment>> {
            val offsets = mutableMapOf<Int, Int>()
            val source = buildString {
                lines.forEachIndexed { index, line ->
                    if (line.kind != DiffLineKind.INFO && line.kind != if (removed) DiffLineKind.ADDED else DiffLineKind.REMOVED) {
                        offsets[index] = length
                        append(line.text).append('\n')
                    }
                }
            }
            val tokens = safely { tokenize(source, highlighter, scheme) }.orEmpty()
            var cursor = 0
            return offsets.mapValues { (index, start) ->
                val text = lines[index].text
                val end = start + text.length
                val fragments = mutableListOf<HoverCodeFragment>()
                var position = start
                while (cursor < tokens.size && tokens[cursor].end <= start) cursor++
                var tokenIndex = cursor
                while (tokenIndex < tokens.size && tokens[tokenIndex].start < end) {
                    val token = tokens[tokenIndex++]
                    val from = maxOf(position, token.start, start)
                    val until = minOf(end, token.end)
                    if (from > position) fragments += HoverCodeFragment(source.substring(position, from), scheme.defaultForeground, Font.PLAIN)
                    if (until > from) {
                        fragments += HoverCodeFragment(source.substring(from, until), token.color, token.style)
                        position = until
                    }
                }
                if (position < end) fragments += HoverCodeFragment(source.substring(position, end), scheme.defaultForeground, Font.PLAIN)
                fragments
            }
        }
        val old = version(true, before)
        val new = version(false, after)
        return lines.mapIndexed { index, line ->
            (if (line.kind == DiffLineKind.REMOVED) old[index] else new[index])
                ?: listOf(HoverCodeFragment(line.text, scheme.defaultForeground, Font.PLAIN))
        }
    }

    private fun tokenize(source: String, highlighter: SyntaxHighlighter?, scheme: EditorColorsScheme): List<Token> {
        if (highlighter == null) return emptyList()
        val lexer = highlighter.highlightingLexer
        lexer.start(source)
        val tokens = ArrayList<Token>()
        var previousEnd = 0
        var count = 0
        while (lexer.tokenType != null && count++ < MAX_TOKENS) {
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            // Guard against third-party lexers that fail to advance or exceed the excerpt.
            if (start < previousEnd || end <= start || end > source.length) break
            var color = scheme.defaultForeground
            var style = Font.PLAIN
            for (key in highlighter.getTokenHighlights(lexer.tokenType!!)) {
                val attributes = scheme.getAttributes(key) ?: continue
                attributes.foregroundColor?.let { color = it }
                style = style or (attributes.fontType and (Font.BOLD or Font.ITALIC))
            }
            tokens += Token(start, end, color, style)
            previousEnd = end
            lexer.advance()
        }
        return tokens
    }

    private fun <T> safely(action: () -> T): T? = try { action() }
        catch (cancelled: ProcessCanceledException) { throw cancelled }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { null } 
}
