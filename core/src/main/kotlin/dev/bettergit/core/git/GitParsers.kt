package dev.bettergit.core.git

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Instant

internal object GitParsers {
    fun status(output: String): WorkingTreeStatus {
        var branch = "HEAD"
        var ahead = 0
        var behind = 0
        var staged = 0
        var modified = 0
        var untracked = 0
        val records = output.split('\u0000')
        var index = 0
        while (index < records.size) {
            val record = records[index++]
            when {
                record.startsWith("# branch.head ") -> branch = record.removePrefix("# branch.head ")
                record.startsWith("# branch.ab ") -> {
                    val counts = record.removePrefix("# branch.ab ").split(' ')
                    ahead = counts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                    behind = counts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                }
                record.startsWith("? ") -> untracked++
                record.startsWith("1 ") || record.startsWith("2 ") || record.startsWith("u ") -> {
                    if (record.getOrNull(2) != '.') staged++
                    if (record.getOrNull(3) != '.') modified++
                    if (record.startsWith("2 ")) index++
                }
            }
        }
        return WorkingTreeStatus(branch, ahead, behind, staged, modified, untracked)
    }

    fun branches(output: String): List<GitBranch> = output.lineSequence().filter { it.isNotEmpty() }.mapNotNull { record ->
        val fields = record.split('\u0000')
        if (fields.size < 5 || fields[4].isNotEmpty()) return@mapNotNull null
        val remote = fields[0].startsWith("refs/remotes/")
        GitBranch(
            name = fields[0].removePrefix(if (remote) "refs/remotes/" else "refs/heads/"),
            current = fields[1] == "*",
            remote = remote,
            upstream = fields[2],
            ahead = Regex("ahead (\\d+)").find(fields[3])?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            behind = Regex("behind (\\d+)").find(fields[3])?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }.toList()

    fun log(output: String): List<GitCommit> {
        if (output.isEmpty()) return emptyList()
        val fields = output.removeSuffix("\u0000").split('\u0000')
        require(fields.size % 7 == 0) { "Unexpected Git log output" }
        return fields.chunked(7).map { record ->
            GitCommit(
                hash = record[0],
                author = record[1],
                email = record[2],
                timestamp = Instant.ofEpochSecond(record[3].toLong()),
                subject = record[4],
                parents = record[5].split(' ').filter(String::isNotEmpty),
                decorations = record[6],
            )
        }
    }

    fun changes(output: String): List<FileChange> {
        val records = output.split('\u0000')
        val result = mutableListOf<FileChange>()
        var index = 0
        while (index < records.size && records[index].isNotEmpty()) {
            val status = records[index++]
            require(index < records.size && records[index].isNotEmpty()) { "Missing path in Git changes" }
            val firstPath = records[index++]
            if (status.startsWith('R') || status.startsWith('C')) {
                require(index < records.size && records[index].isNotEmpty()) { "Missing rename destination in Git changes" }
                result += FileChange(records[index++], firstPath, status)
            } else {
                result += FileChange(firstPath, status = status)
            }
        }
        return result
    }

    fun remotes(output: String): List<GitRemote> = output.split('\u0000').filter(String::isNotEmpty).mapNotNull { record ->
        val separator = record.indexOf('\n')
        if (separator < 0) return@mapNotNull null
        GitRemote(record.substring(0, separator).removePrefix("remote.").removeSuffix(".url"), record.substring(separator + 1))
    }

    fun stashes(output: String): List<GitStash> {
        if (output.isEmpty()) return emptyList()
        val fields = output.removeSuffix("\u0000").split('\u0000')
        require(fields.size % 3 == 0) { "Unexpected Git stash output" }
        return fields.chunked(3).map { GitStash(it[0], it[1], it[2]) }
    }

    fun worktrees(output: String): List<GitWorktree> = output.split("\u0000\u0000").filter(String::isNotEmpty).mapNotNull { record ->
        val fields = record.split('\u0000')
        val path = fields.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapNotNull null
        val branch = fields.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch refs/heads/")
            ?: if ("bare" in fields) "(bare)" else "(detached)"
        GitWorktree(
            path = Path.of(path),
            branch = branch,
            head = fields.firstOrNull { it.startsWith("HEAD ") }?.removePrefix("HEAD ").orEmpty(),
            locked = fields.any { it == "locked" || it.startsWith("locked ") },
        )
    }

    fun blame(output: String): BlameLine? {
        if (output.isEmpty()) return null
        val lines = output.lineSequence().iterator()
        val header = lines.next().split(' ')
        val hash = header.first()
        val originalLine = header.getOrNull(1)?.toIntOrNull() ?: return null
        val line = header.getOrNull(2)?.toIntOrNull() ?: return null
        val metadata = mutableMapOf<String, String>()
        while (lines.hasNext()) {
            val record = lines.next()
            if (record.startsWith('\t')) break
            val separator = record.indexOf(' ')
            if (separator > 0) metadata[record.substring(0, separator)] = record.substring(separator + 1)
        }
        val commit = if (hash.all { it == '0' }) null else GitCommit(
            hash = hash,
            author = metadata["author"].orEmpty(),
            email = metadata["author-mail"].orEmpty().removeSurrounding("<", ">"),
            timestamp = Instant.ofEpochSecond(metadata["author-time"]?.toLongOrNull() ?: 0),
            subject = metadata["summary"].orEmpty(),
        )
        return BlameLine(line, commit, decodeGitPath(metadata["filename"].orEmpty()), originalLine)
    }

    fun blameFile(output: String): List<BlameLine> {
        val result = ArrayList<BlameLine>()
        val commits = HashMap<String, GitCommit>()
        val paths = HashMap<String, String>()
        var header = true
        var hash = ""
        var originalLine = 0
        var finalLine = 0
        var author = ""
        var email = ""
        var timestamp = 0L
        var subject = ""
        var path = ""
        for (record in output.lineSequence()) {
            if (header) {
                if (record.isEmpty()) continue
                val fields = record.split(' ', limit = 4)
                hash = fields[0]
                originalLine = fields.getOrNull(1)?.toIntOrNull() ?: 0
                finalLine = fields.getOrNull(2)?.toIntOrNull() ?: 0
                author = ""; email = ""; timestamp = 0; subject = ""; path = ""
                header = false
            } else if (record.startsWith('\t')) {
                if (originalLine > 0 && finalLine > 0) {
                    val commit = if (hash.all { it == '0' }) null else commits.getOrPut(hash) {
                        GitCommit(hash, author, email, Instant.ofEpochSecond(timestamp), subject)
                    }
                    result += BlameLine(finalLine, commit, paths.getOrPut(path) { decodeGitPath(path) }, originalLine)
                }
                header = true
            } else {
                when {
                    record.startsWith("author ") -> author = record.substring(7)
                    record.startsWith("author-mail ") -> email = record.substring(12).removeSurrounding("<", ">")
                    record.startsWith("author-time ") -> timestamp = record.substring(12).toLongOrNull() ?: 0
                    record.startsWith("summary ") -> subject = record.substring(8)
                    record.startsWith("filename ") -> path = record.substring(9)
                }
            }
        }
        return result
    }

    internal fun decodeGitPath(path: String): String {
        if (!path.startsWith('"') || !path.endsWith('"')) return path
        val bytes = path.substring(1, path.length - 1).toByteArray(UTF_8)
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index++].toInt() and 0xff
            if (value != '\\'.code || index >= bytes.size) {
                output.write(value)
                continue
            }
            val escape = bytes[index++].toInt().toChar()
            if (escape in '0'..'7') {
                var octal = escape.digitToInt()
                var digits = 1
                while (digits < 3 && index < bytes.size && bytes[index].toInt().toChar() in '0'..'7') {
                    octal = octal * 8 + bytes[index++].toInt().toChar().digitToInt()
                    digits++
                }
                output.write(octal)
            } else {
                output.write(when (escape) {
                    'a' -> 7
                    'b' -> 8
                    't' -> 9
                    'n' -> 10
                    'v' -> 11
                    'f' -> 12
                    'r' -> 13
                    else -> escape.code
                })
            }
        }
        return output.toString(UTF_8)
    }
}
