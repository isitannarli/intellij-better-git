package dev.bettergit.core.git

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.CancellationException

/** Statistics are separate from status: displaying counts must not change mutation validation. */
data class WorkingChangesSnapshot(
    val changes: List<WorkingChange>,
    val stagedStats: Map<String, FileLineStats>,
    val workingStats: Map<String, FileLineStats>,
)

internal object GitNumstatParser {
    fun parse(output: String): Map<String, FileLineStats> {
        val tokens = output.split('\u0000')
        val stats = linkedMapOf<String, FileLineStats>()
        var index = 0
        while (index < tokens.size) {
            val entry = tokens[index++]
            if (entry.isEmpty()) continue
            val fields = entry.split('\t', limit = 3)
            require(fields.size == 3) { "Invalid Git line statistics" }
            val path = if (fields[2].isEmpty()) {
                require(index + 1 < tokens.size && tokens[index].isNotEmpty() && tokens[index + 1].isNotEmpty()) { "Incomplete rename statistics" }
                index++
                tokens[index++]
            } else fields[2]
            stats[path] = FileLineStats(fields[0].toLongOrNull(), fields[1].toLongOrNull())
        }
        return stats
    }
}

/** Bounded, interruptible reads for untracked files without spawning a process per file. */
internal class UntrackedLineCounter {
    private var remainingBytes = 32L * 1024 * 1024

    fun count(root: Path, relativePath: String): FileLineStats? {
        checkCancelled()
        if (remainingBytes <= 0) return null
        return try {
            val file = root.resolve(relativePath).normalize()
            if (!file.startsWith(root) || !file.parent.toRealPath().startsWith(root)) return null
            // Git stores a symlink's link text, not the target's contents.
            if (Files.isSymbolicLink(file)) {
                val text = Files.readSymbolicLink(file).toString()
                return FileLineStats(text.count { it == '\n' }.toLong() + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0, 0)
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
            if (Files.size(file) > MAX_FILE_BYTES) return null
            var lines = 0L
            var total = 0L
            var last = -1
            val buffer = ByteArray(8192)
            Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count == -1) break
                    remainingBytes -= count
                    total += count
                    if (remainingBytes < 0 || total > MAX_FILE_BYTES) return null
                    for (index in 0 until count) {
                        val byte = buffer[index].toInt()
                        if (byte == 0) return FileLineStats(null, null)
                        if (byte == 10) lines++
                        last = byte
                    }
                }
            }
            FileLineStats(lines + if (last != -1 && last != 10) 1 else 0, 0)
        } catch (_: java.io.IOException) {
            // A save/rename may race with reading this file. The next native refresh retries it.
            null
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Line count cancelled")
    }

    private companion object { const val MAX_FILE_BYTES = 8L * 1024 * 1024 }
}
