package dev.bettergit.core.reports

import dev.bettergit.core.git.GitCommit
import java.time.LocalDate
import java.time.ZoneId

data class Contributor(val name: String, val email: String, val commits: Int)
data class ActivityReport(
    val commitCount: Int,
    val mergeCount: Int,
    val contributors: List<Contributor>,
    val dailyActivity: Map<LocalDate, Int>,
) {
    companion object {
        fun from(commits: List<GitCommit>, zone: ZoneId = ZoneId.systemDefault()): ActivityReport {
            val unique = commits.distinctBy(GitCommit::hash)
            return ActivityReport(
                unique.size,
                unique.count { it.parents.size > 1 },
                unique.groupBy { it.email.lowercase() }.map { (_, values) ->
                    Contributor(values.first().author, values.first().email, values.size)
                }.sortedByDescending(Contributor::commits),
                unique.groupingBy { it.timestamp.atZone(zone).toLocalDate() }.eachCount().toSortedMap(),
            )
        }
    }
}
