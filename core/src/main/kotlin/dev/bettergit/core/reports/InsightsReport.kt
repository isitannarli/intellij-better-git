package dev.bettergit.core.reports

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class WeeklyActivity(val weekStart: LocalDate, val commits: Int)

/** Counts are lower bounds when truncated or shallow. */
data class InsightsReport(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currentTotal: Int,
    val previousTotal: Int,
    val delta: Int,
    val deltaPercent: Double?,
    val activeDays: Int,
    val previousActiveDays: Int,
    val longestStreak: Int,
    val currentStreak: Int,
    val mergeCount: Int,
    val contributors: List<Contributor>,
    val dailyActivity: Map<LocalDate, Int>,
    val previousDailyActivity: Map<LocalDate, Int>,
    val weekdayActivity: Map<DayOfWeek, Int>,
    val weeklyTrend: List<WeeklyActivity>,
    val truncated: Boolean,
    val shallow: Boolean,
    val currentStreakAtWindowBoundary: Boolean = false,
) {
    companion object {
        fun from(history: InsightsHistory): InsightsReport {
            val today = history.endDate
            val currentStart = today.minusDays(364)
            val previousStart = today.minusDays(729)
            val entries = history.entries.asSequence()
                .filter { !it.committedAt.isAfter(history.capturedAt) }
                .map { it to it.committedAt.atZone(history.zone).toLocalDate() }
                .filter { (_, date) -> date >= previousStart && date <= today }
                .distinctBy { it.first.commit.hash }
                .toList()
            val counts = entries.groupingBy { it.second }.eachCount()
            val daily = days(currentStart, counts)
            val previousDaily = days(previousStart, counts)
            val current = entries.filter { it.second >= currentStart }
            val currentTotal = daily.values.sum()
            val previousTotal = previousDaily.values.sum()

            var longest = 0
            var running = 0
            daily.values.forEach { count ->
                running = if (count > 0) running + 1 else 0
                longest = maxOf(longest, running)
            }
            var streakDay = if (daily.getValue(today) > 0) today else today.minusDays(1)
            var currentStreak = 0
            while (streakDay >= previousStart && (counts[streakDay] ?: 0) > 0) {
                currentStreak++
                streakDay = streakDay.minusDays(1)
            }

            val contributors = current.groupBy { it.first.commit.email.trim().lowercase(Locale.ROOT) }
                .map { (_, values) ->
                    val latest = values.maxBy { it.first.committedAt }.first.commit
                    Contributor(latest.author, latest.email.trim(), values.size)
                }
                .sortedWith(compareByDescending<Contributor> { it.commits }.thenBy { it.email.lowercase(Locale.ROOT) })
            val weekdayCounts = current.groupingBy { it.second.dayOfWeek }.eachCount()
            val firstWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(11)
            val weeks = (0L until 12L).map { offset ->
                val start = firstWeek.plusWeeks(offset)
                WeeklyActivity(start, (0L until 7L).sumOf { daily[start.plusDays(it)] ?: 0 })
            }
            return InsightsReport(
                currentStart, today, currentTotal, previousTotal, currentTotal - previousTotal,
                if (previousTotal == 0) null else (currentTotal - previousTotal).toDouble() * 100 / previousTotal,
                daily.values.count { it > 0 }, previousDaily.values.count { it > 0 }, longest, currentStreak,
                current.count { it.first.commit.parents.distinct().size > 1 }, contributors, daily, previousDaily,
                DayOfWeek.entries.associateWith { weekdayCounts[it] ?: 0 }, weeks, history.truncated, history.shallow,
                currentStreak > 0 && streakDay < previousStart,
            )
        }

        private fun days(start: LocalDate, counts: Map<LocalDate, Int>): Map<LocalDate, Int> =
            (0L until 365L).associate { offset -> start.plusDays(offset).let { it to (counts[it] ?: 0) } }
    }
}
