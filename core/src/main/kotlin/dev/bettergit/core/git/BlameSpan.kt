package dev.bettergit.core.git

data class BlameSpan(val startLine: Int, val endLine: Int, val commit: GitCommit)

object BlameSpans {
    fun compact(lines: List<BlameLine>): List<BlameSpan> {
        val result = ArrayList<BlameSpan>()
        var start = 0
        var end = 0
        var current: GitCommit? = null
        for (line in lines) {
            val commit = line.commit
            if (commit != null && current != null && line.line == end + 1 && commit == current) {
                end = line.line
            } else {
                current?.let { result += BlameSpan(start, end, it) }
                current = commit
                start = line.line
                end = line.line
            }
        }
        current?.let { result += BlameSpan(start, end, it) }
        return result
    }
}
