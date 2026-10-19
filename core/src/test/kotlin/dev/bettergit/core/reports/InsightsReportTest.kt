package dev.bettergit.core.reports

import dev.bettergit.core.git.GitCommit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class InsightsReportTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    @Test
    fun `empty snapshots include all dates and zero counts in both 365 day windows`() {
        val report = InsightsReport.from(history(emptyList()))

        assertEquals(365, report.dailyActivity.size)
        assertEquals(365, report.previousDailyActivity.size)
        assertEquals(today.minusDays(364), report.dailyActivity.keys.first())
        assertEquals(today, report.dailyActivity.keys.last())
        assertEquals(today.minusDays(729), report.previousDailyActivity.keys.first())
        assertEquals(today.minusDays(365), report.previousDailyActivity.keys.last())
        assertTrue(report.dailyActivity.values.all { it == 0 })
        assertEquals(0, report.currentTotal)
        assertEquals(0, report.currentStreak)
        assertEquals(0, report.longestStreak)
        assertNull(report.deltaPercent)
        assertEquals(DayOfWeek.entries, report.weekdayActivity.keys.toList())
        assertEquals(12, report.weeklyTrend.size)
    }

    @Test
    fun `adjacent windows include their boundaries exactly once and ignore old and future commits`() {
        val entries = listOf(
            entry("too-old", 730), entry("previous-start", 729), entry("previous-end", 365),
            entry("current-start", 364), entry("today", 0), entry("future-day", -1),
            entry("future-second", 0).copy(committedAt = now.plusSeconds(1)),
        )
        val report = InsightsReport.from(history(entries))

        assertEquals(2, report.currentTotal)
        assertEquals(2, report.previousTotal)
        assertEquals(0, report.delta)
        assertEquals(0.0, report.deltaPercent)
        assertEquals(2, report.activeDays)
        assertEquals(2, report.previousActiveDays)
        assertEquals(4, report.dailyActivity.values.sum() + report.previousDailyActivity.values.sum())
    }

    @Test
    fun `duplicate hashes and author time do not inflate committer-date activity`() {
        val commit = entry("same", 1).let { it.copy(commit = it.commit.copy(timestamp = now.plusSeconds(100000))) }
        val duplicate = commit.copy(commit = commit.commit.copy(decorations = "another-ref"))
        val report = InsightsReport.from(history(listOf(commit, duplicate)))

        assertEquals(1, report.currentTotal)
        assertEquals(1, report.dailyActivity.getValue(today.minusDays(1)))
        assertEquals(1, history(listOf(commit, duplicate)).commitsOn(today.minusDays(1)).size)
    }

    @Test
    fun `current streak allows yesterday while longest streak covers the entire current window`() {
        val entries = (1..3).map { entry("recent-$it", it.toLong()) } +
            (30..35).map { entry("longest-$it", it.toLong()) }
        val report = InsightsReport.from(history(entries))

        assertEquals(3, report.currentStreak)
        assertEquals(6, report.longestStreak)
        assertEquals(4, InsightsReport.from(history(entries + entry("today", 0))).currentStreak)
        assertEquals(0, InsightsReport.from(history(listOf(entry("two-days-ago", 2)))).currentStreak)
    }

    @Test
    fun `current streak can span both periods while longest streak describes the current 365 days`() {
        val entries = (0L until 400L).map { entry("daily-$it", it) }
        val report = InsightsReport.from(history(entries))

        assertEquals(400, report.currentStreak)
        assertEquals(365, report.longestStreak)
        assertEquals(365, report.currentTotal)
        assertEquals(35, report.previousTotal)
        assertFalse(report.currentStreakAtWindowBoundary)
    }

    @Test
    fun `a streak reaching the 730 day history boundary is explicitly a lower bound`() {
        val report = InsightsReport.from(history((0L until 731L).map { entry("daily-$it", it) }))

        assertEquals(730, report.currentStreak)
        assertEquals(365, report.longestStreak)
        assertTrue(report.currentStreakAtWindowBoundary)
    }

    @Test
    fun `contributors use canonical email with newest display name and ignore previous period counts`() {
        val older = entry("older", 10, "Old Name", "DEV@example.test")
        val latest = entry("latest", 1, "New Name", "dev@example.test")
        val unrelated = entry("another", 2, "New Name", "another@example.test")
        val previous = entry("previous", 400, "New Name", "dev@example.test")
        val report = InsightsReport.from(history(listOf(older, unrelated, previous, latest)))

        assertEquals(2, report.contributors.size)
        assertEquals(Contributor("New Name", "dev@example.test", 2), report.contributors.first())
        assertEquals(1, report.contributors.last().commits)
    }

    @Test
    fun `weekday counts and twelve Monday based weeks represent current activity only`() {
        val entries = listOf(entry("sunday", 0), entry("monday", 6), entry("last-week", 7), entry("older", 200))
        val report = InsightsReport.from(history(entries))

        assertEquals(4, report.weekdayActivity.values.sum())
        assertEquals(2, report.weekdayActivity.getValue(DayOfWeek.SUNDAY))
        assertTrue(report.weeklyTrend.all { it.weekStart.dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(WeeklyActivity(LocalDate.of(2026, 10, 12), 2), report.weeklyTrend.last())
        assertEquals(3, report.weeklyTrend.sumOf(WeeklyActivity::commits))
        assertEquals(7, report.weekdayActivity.size)
    }

    @Test
    fun `timezone determines local activity dates including daylight saving repeated hours`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val captured = Instant.parse("2026-10-19T00:00:00Z")
        val entries = listOf(
            entry("before-midnight", 0).copy(committedAt = Instant.parse("2026-10-18T20:59:59Z")),
            entry("after-midnight", 0).copy(committedAt = Instant.parse("2026-10-18T21:00:00Z")),
        )
        val report = InsightsReport.from(history(entries, captured, zone))
        assertEquals(1, report.dailyActivity.getValue(LocalDate.of(2026, 10, 18)))
        assertEquals(1, report.dailyActivity.getValue(LocalDate.of(2026, 10, 19)))

        val repeatedHours = listOf(
            entry("first-hour", 0).copy(committedAt = Instant.parse("2026-11-01T05:30:00Z")),
            entry("second-hour", 0).copy(committedAt = Instant.parse("2026-11-01T06:30:00Z")),
        )
        val dst = InsightsReport.from(history(repeatedHours, Instant.parse("2026-11-01T12:00:00Z"), ZoneId.of("America/New_York")))
        assertEquals(2, dst.dailyActivity.getValue(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `leap days still yield fixed length windows and honest incomplete coverage flags`() {
        val captured = Instant.parse("2024-03-01T12:00:00Z")
        val report = InsightsReport.from(history(emptyList(), captured).copy(truncated = true, shallow = true))

        assertEquals(365, report.dailyActivity.size)
        assertTrue(LocalDate.of(2024, 2, 29) in report.dailyActivity)
        assertTrue(report.truncated)
        assertTrue(report.shallow)
    }

    @Test
    fun `delta can decline and merge counts use distinct parents in the current period`() {
        val current = entry("current", 0).let { it.copy(commit = it.commit.copy(parents = listOf("a", "b"))) }
        val report = InsightsReport.from(history(listOf(current, entry("previous-1", 400), entry("previous-2", 401))))

        assertEquals(-1, report.delta)
        assertEquals(-50.0, report.deltaPercent)
        assertEquals(1, report.mergeCount)
        val duplicateParents = current.copy(commit = current.commit.copy(parents = listOf("a", "a")))
        assertEquals(0, InsightsReport.from(history(listOf(duplicateParents))).mergeCount)
    }

    private fun entry(hash: String, daysAgo: Long, name: String = "Developer", email: String = "dev@example.test"): InsightsCommit {
        val timestamp = today.minusDays(daysAgo).atTime(10, 0).toInstant(ZoneOffset.UTC)
        return InsightsCommit(GitCommit(hash, name, email, timestamp, hash), timestamp)
    }

    private fun history(entries: List<InsightsCommit>, capturedAt: Instant = now, zone: ZoneId = ZoneOffset.UTC): InsightsHistory {
        val day = LocalDate.ofInstant(capturedAt, zone)
        return InsightsHistory(entries, day.minusDays(729), day, capturedAt, zone, 50_000, false)
    }
}
