package dev.bettergit.core.git

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class LineRange(val start: Int, val end: Int) {
    init { require(start > 0 && end >= start) { "Select a valid one-based line range" } }
}

data class SelectionHistoryEntry(val commit: GitCommit, val patch: String)

data class SelectionHistory(
    val path: String,
    val selectedRange: LineRange,
    val headRange: LineRange?,
    val entries: List<SelectionHistoryEntry>,
    val truncated: Boolean,
    val shallow: Boolean,
    val hasLocalChanges: Boolean,
    val mappingApproximate: Boolean,
    val localPatch: String,
)

internal class SelectionHistoryReader(private val runner: GitProcessRunner) {
    fun read(
        root: Path,
        path: String,
        range: LineRange,
        currentText: String,
        limit: Int,
        head: String?,
        renamedFrom: () -> String?,
    ): SelectionHistory {
        val current = normalizedText(currentText)
        require(range.end <= lineCount(current)) { "Selection is outside the editor snapshot or on its empty final line" }
        val shallow = runner.run(root, listOf("rev-parse", "--is-shallow-repository")).stdout.trim() == "true"
        var headPath = path
        var original = head?.let { headContent(root, it, path) }
        if (head != null && original == null) {
            renamedFrom()?.let { previousPath ->
                original = headContent(root, head, previousPath)
                if (original != null) headPath = previousPath
            }
        }
        val before = original?.let(::normalizedText).orEmpty()
        val changed = original == null || before != current || path != headPath
        val patch = if (before == current) "" else snapshotDiff(before, current)
        val mapping = SelectionHistoryParser.map(range, lineCount(current), patch, headPath, path)
        val headRange = mapping.range.takeIf { original != null }
        val entries = if (head != null && headRange != null) {
            val output = runner.run(root, listOf(
                "log", "--max-count=${limit + 1}", "--find-renames", "--no-ext-diff", "--no-textconv", "--no-color", "--no-show-signature", "--no-notes",
                "--output-indicator-new=+", "--output-indicator-old=-", "--output-indicator-context= ",
                "--format=%x00%H%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D%x00",
                "-L", "${headRange.start},${headRange.end}:$headPath", head,
            ), timeout = Duration.ofMinutes(2)).stdout
            SelectionHistoryParser.entries(output)
        } else emptyList()
        return SelectionHistory(path, range, headRange, entries.take(limit), entries.size > limit,
            shallow, changed, mapping.approximate, mapping.patch)
    }

    private fun headContent(root: Path, head: String, path: String): String? {
        val tree = runner.run(root, listOf("ls-tree", "-z", head, "--", path)).stdout
        if (tree.isEmpty()) return null
        val metadata = tree.substringBefore('\t').split(' ')
        require(metadata.size == 3 && metadata[1] == "blob" && metadata[0] in setOf("100644", "100755")) {
            "Selection history requires a regular text file; symbolic links and submodules are not supported"
        }
        return runner.run(root, listOf("cat-file", "blob", "$head:$path")).stdout
    }

    private fun snapshotDiff(before: String, after: String): String {
        val directory = Files.createTempDirectory("better-git-selection-")
        val oldFile = directory.resolve("head.txt")
        val newFile = directory.resolve("editor.txt")
        try {
            Files.writeString(oldFile, before)
            Files.writeString(newFile, after)
            return runner.run(directory, listOf(
                "diff", "--no-index", "--text", "--no-ext-diff", "--no-textconv", "--no-color", "--no-renames",
                "--output-indicator-new=+", "--output-indicator-old=-", "--output-indicator-context= ",
                "--diff-algorithm=myers", "--no-indent-heuristic", "--unified=0", "--inter-hunk-context=0",
                "--", oldFile.toString(), newFile.toString(),
            ), acceptedExitCodes = setOf(0, 1)).stdout
        } finally {
            Files.deleteIfExists(oldFile)
            Files.deleteIfExists(newFile)
            Files.deleteIfExists(directory)
        }
    }

    private fun normalizedText(text: String): String {
        require('\u0000' !in text) { "Selection history does not support binary files" }
        require(text.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Selection history supports text files up to 2 MB" }
        return text.replace("\r\n", "\n")
    }

    private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
}

internal data class SelectionMapping(val range: LineRange?, val approximate: Boolean, val patch: String)

internal object SelectionHistoryParser {
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
    private val hash = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    fun entries(output: String): List<SelectionHistoryEntry> {
        val result = mutableListOf<SelectionHistoryEntry>()
        var position = 0
        while (position < output.length) {
            require(output[position] == '\u0000') { "Unexpected selection history record" }
            position++
            val fields = (0 until 7).map {
                val end = output.indexOf('\u0000', position)
                require(end >= position) { "Incomplete selection history metadata" }
                output.substring(position, end).also { position = end + 1 }
            }
            require(hash.matches(fields[0])) { "Invalid selection history commit" }
            val end = output.indexOf('\u0000', position).let { if (it == -1) output.length else it }
            val patch = output.substring(position, end).trimStart('\n')
            result += SelectionHistoryEntry(GitCommit(fields[0], fields[1], fields[2], Instant.ofEpochSecond(fields[3].toLong()),
                fields[4], fields[5].split(' ').filter(String::isNotEmpty), fields[6]), patch)
            position = end
        }
        return result
    }

    /** Replaced spans map to their entire old hunk; only unchanged spans map exactly. */
    fun map(selection: LineRange, currentLines: Int, patch: String, oldPath: String, path: String): SelectionMapping {
        val hunks = hunks(patch)
        val mapped = mutableListOf<LineRange>()
        val relevant = mutableListOf<String>()
        var approximate = false
        var oldCursor = 1
        var newCursor = 1
        fun unchanged(until: Int) {
            val start = maxOf(selection.start, newCursor)
            val end = minOf(selection.end, until)
            if (start <= end) mapped += LineRange(oldCursor + start - newCursor, oldCursor + end - newCursor)
        }
        hunks.forEach { hunk ->
            val oldStart = hunk.oldStart + if (hunk.oldCount == 0) 1 else 0
            val newStart = hunk.newStart + if (hunk.newCount == 0) 1 else 0
            unchanged(newStart - 1)
            val intersects = hunk.newCount > 0 && selection.start <= newStart + hunk.newCount - 1 && selection.end >= newStart
            if (intersects) {
                relevant += hunk.text
                if (hunk.oldCount > 0) {
                    mapped += LineRange(oldStart, oldStart + hunk.oldCount - 1)
                    approximate = true
                }
            } else if (hunk.newCount == 0 && hunk.newStart >= selection.start && hunk.newStart < selection.end) {
                relevant += hunk.text
            }
            oldCursor = oldStart + hunk.oldCount
            newCursor = newStart + hunk.newCount
        }
        unchanged(currentLines)
        if (mapped.zipWithNext().any { (left, right) -> right.start > left.end + 1 }) approximate = true
        val range = mapped.takeIf { it.isNotEmpty() }?.let { LineRange(it.first().start, it.last().end) }
        val selectedPatch = if (relevant.isEmpty()) "" else
            "--- ${quotePath("a/$oldPath")}\n+++ ${quotePath("b/$path")}\n" + relevant.joinToString("")
        return SelectionMapping(range, approximate, selectedPatch)
    }

    private data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val text: String)

    private fun hunks(patch: String): List<Hunk> {
        val result = mutableListOf<Hunk>()
        var header: MatchResult? = null
        var text = StringBuilder()
        fun finish() {
            header?.let {
                result += Hunk(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 1,
                    it.groupValues[3].toInt(), it.groupValues[4].toIntOrNull() ?: 1, text.toString())
            }
        }
        patch.lineSequence().forEach { line ->
            val found = hunkHeader.matchEntire(line)
            if (found != null) { finish(); header = found; text = StringBuilder() }
            if (header != null && line.isNotEmpty()) text.append(line).append('\n')
        }
        finish()
        return result
    }

    private fun quotePath(path: String): String = "\"" + path.flatMap { char ->
        when (char) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> char.toString()
        }.toList()
    }.joinToString("") + "\""
}
