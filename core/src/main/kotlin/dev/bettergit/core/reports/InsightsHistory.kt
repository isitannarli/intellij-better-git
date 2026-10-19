package dev.bettergit.core.reports

import dev.bettergit.core.git.GitCommit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Activity uses committer time; commit retains author time. */
data class InsightsCommit(val commit: GitCommit, val committedAt: Instant)

data class InsightsHistory(
    val entries: List<InsightsCommit>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val capturedAt: Instant,
    val zone: ZoneId,
    val limit: Int,
    val truncated: Boolean,
    val shallow: Boolean = false,
) {
    fun commitsOn(day: LocalDate): List<GitCommit> = entries.asSequence()
        .filter { !it.committedAt.isAfter(capturedAt) && it.committedAt.atZone(zone).toLocalDate() == day }
        .distinctBy { it.commit.hash }
        .map(InsightsCommit::commit)
        .toList()

    internal companion object {
        fun parse(
            output: String,
            startDate: LocalDate,
            endDate: LocalDate,
            capturedAt: Instant,
            zone: ZoneId,
            limit: Int,
            shallow: Boolean,
        ): InsightsHistory {
            val fields = if (output.isEmpty()) emptyList() else output.removeSuffix("\u0000").split('\u0000')
            require(fields.size % 8 == 0) { "Unexpected Git insights output" }
            val entries = fields.chunked(8).map { record ->
                InsightsCommit(
                    GitCommit(
                        hash = record[0], author = record[1], email = record[2],
                        timestamp = Instant.ofEpochSecond(record[3].toLong()), subject = record[4],
                        parents = record[5].split(' ').filter(String::isNotEmpty), decorations = record[6],
                    ),
                    Instant.ofEpochSecond(record[7].toLong()),
                )
            }
            return InsightsHistory(entries.take(limit), startDate, endDate, capturedAt, zone, limit, entries.size > limit, shallow)
        }
    }
}
