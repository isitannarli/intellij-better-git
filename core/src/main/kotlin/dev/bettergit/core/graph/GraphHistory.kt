package dev.bettergit.core.graph

import dev.bettergit.core.git.GitCommit
import java.time.Instant

data class GraphChanges(val files: Int, val added: Long, val removed: Long)
data class GraphCommit(val commit: GitCommit, val changes: GraphChanges, val committedAt: Instant)
enum class GraphRefKind { BRANCH, REMOTE, TAG }
data class GraphReference(val name: String, val fullName: String, val hash: String, val kind: GraphRefKind)
data class GraphHistory(
    val commits: List<GraphCommit>, val references: List<GraphReference>, val head: String?,
    val hasMore: Boolean, val shallow: Boolean,
)

internal object GraphHistoryParser {
    const val MARKER = "BETTER-GIT-GRAPH"
    const val FORMAT = "--format=%x00$MARKER%x00%H%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D%x00%ct%x00"
    private val files = Regex("(\\d+) files? changed")
    private val added = Regex("(\\d+) insertions?\\(\\+\\)")
    private val removed = Regex("(\\d+) deletions?\\(-\\)")

    fun parse(output: String): List<GraphCommit> {
        val fields = output.split('\u0000')
        val result = mutableListOf<GraphCommit>()
        var index = 0
        while (index < fields.size) {
            if (fields[index].isBlank()) { index++; continue }
            require(fields[index++] == MARKER && index + 7 < fields.size) { "Invalid graph history record" }
            val record = fields.subList(index, index + 8)
            index += 8
            val commit = GitCommit(record[0], record[1], record[2], Instant.ofEpochSecond(record[3].toLong()), record[4],
                record[5].split(' ').filter(String::isNotEmpty), record[6])
            val stats = StringBuilder()
            while (index < fields.size && fields[index] != MARKER) stats.append(fields[index++])
            val text = stats.toString()
            result += GraphCommit(commit, GraphChanges(
                files.find(text)?.groupValues?.get(1)?.toInt() ?: 0,
                added.find(text)?.groupValues?.get(1)?.toLong() ?: 0,
                removed.find(text)?.groupValues?.get(1)?.toLong() ?: 0,
            ), Instant.ofEpochSecond(record[7].toLong()))
        }
        return result
    }

    fun references(output: String): List<GraphReference> = output.lineSequence().filter(String::isNotEmpty).mapNotNull { line ->
        val f = line.split('\u0000')
        require(f.size == 6) { "Invalid graph reference record" }
        if (f[5].isNotEmpty()) return@mapNotNull null // origin/HEAD is an alias, not another branch.
        val hash = when { f[1] == "commit" -> f[0]; f[3] == "commit" -> f[2]; else -> return@mapNotNull null }
        val kind = when {
            f[4].startsWith("refs/heads/") -> GraphRefKind.BRANCH
            f[4].startsWith("refs/remotes/") -> GraphRefKind.REMOTE
            f[4].startsWith("refs/tags/") -> GraphRefKind.TAG
            else -> return@mapNotNull null
        }
        val name = f[4].removePrefix(when (kind) {
            GraphRefKind.BRANCH -> "refs/heads/"; GraphRefKind.REMOTE -> "refs/remotes/"; GraphRefKind.TAG -> "refs/tags/"
        })
        GraphReference(name, f[4], hash, kind)
    }.toList()
}

data class ColoredGraphEdge(val edge: GraphEdge, val color: Int)
data class ColoredGraphRow(val row: GraphRow, val color: Int, val edges: List<ColoredGraphEdge>)

/** Preserve color identity through lane compaction; joins retain the target color. */
object ColoredCommitGraph {
    @JvmOverloads
    fun layout(commits: List<GitCommit>, maxEdges: Int = 250_000): List<ColoredGraphRow> {
        require(maxEdges > 0)
        // Check the edge budget before allocation to protect the IDE heap.
        val frontier = linkedSetOf<String>()
        var edgeCount = 0L
        commits.forEach { commit ->
            val incoming = commit.hash in frontier
            edgeCount += frontier.size + (if (incoming) 0 else 1) - 1 + commit.parents.distinct().size
            require(edgeCount <= maxEdges) { "Graph has too many active lanes. Select a narrower branch scope to continue." }
            frontier.remove(commit.hash)
            frontier.addAll(commit.parents)
        }
        var colors = emptyList<Int>()
        var nextColor = 0
        return CommitGraph.layout(commits).map { row ->
            val before = colors.toMutableList()
            while (before.size <= row.lane) before += nextColor++
            val after = arrayOfNulls<Int>(row.edges.maxOfOrNull { it.to + 1 } ?: 0)
            row.edges.filterNot(GraphEdge::startsAtCommit).forEach { after[it.to] = before[it.from] }
            var firstParent = true
            val edges = row.edges.map { edge ->
                if (!edge.startsAtCommit) ColoredGraphEdge(edge, before[edge.from]) else {
                    val inherited = firstParent
                    val color = if (inherited) before[row.lane] else after[edge.to] ?: nextColor++
                    if (after[edge.to] == null) after[edge.to] = color
                    firstParent = false
                    ColoredGraphEdge(edge, color)
                }
            }
            colors = after.map { requireNotNull(it) }
            ColoredGraphRow(row, before[row.lane], edges)
        }
    }
}

object GraphSearch {
    private val terms = Regex("(?:[^\\s\"]|\"[^\"]*\")+")
    fun matches(entry: GraphCommit, references: List<GraphReference>, query: String): Boolean = terms.findAll(query).all { match ->
        val token = match.value
        val field = token.substringBefore(':', "")
        val text = token.substringAfter(':', token).removeSurrounding("\"")
        when (field.lowercase()) {
            "author" -> entry.commit.author.contains(text, true) || entry.commit.email.contains(text, true)
            "sha" -> entry.commit.hash.contains(text, true)
            "ref" -> references.any { it.name.contains(text, true) }
            "message" -> entry.commit.subject.contains(text, true)
            else -> listOf(entry.commit.subject, entry.commit.author, entry.commit.email, entry.commit.hash)
                .any { it.contains(token.removeSurrounding("\""), true) } || references.any { it.name.contains(token, true) }
        }
    }
}
