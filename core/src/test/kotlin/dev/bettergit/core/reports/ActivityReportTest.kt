package dev.bettergit.core.reports

import dev.bettergit.core.git.GitCommit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityReportTest {
    @Test
    fun `empty history has no activity or contributors`() {
        val report = ActivityReport.from(emptyList(), ZoneOffset.UTC)

        assertEquals(0, report.commitCount)
        assertEquals(0, report.mergeCount)
        assertTrue(report.contributors.isEmpty())
        assertTrue(report.dailyActivity.isEmpty())
    }

    @Test
    fun `duplicate commits from multiple refs are counted only once`() {
        val root = commit("root")
        val merge = commit("merge", parents = listOf("main", "feature"))
        val report = ActivityReport.from(listOf(merge, root, merge.copy(decorations = "origin/main"), root), ZoneOffset.UTC)

        assertEquals(2, report.commitCount)
        assertEquals(1, report.mergeCount)
        assertEquals(2, report.contributors.single().commits)
        assertEquals(2, report.dailyActivity.values.sum())
    }

    @Test
    fun `different author names and email casing retain one contributor identity`() {
        val report = ActivityReport.from(listOf(
            commit("new", name = "Current Name", email = "Dev@Example.test"),
            commit("old", name = "Former Name", email = "dev@example.test"),
            commit("other", name = "Other Person", email = "other@example.test"),
        ), ZoneOffset.UTC)

        assertEquals(2, report.contributors.size)
        assertEquals(Contributor("Current Name", "Dev@Example.test", 2), report.contributors.first())
        assertEquals(1, report.contributors.last().commits)
    }

    @Test
    fun `identical display names with different emails remain distinct`() {
        val report = ActivityReport.from(listOf(
            commit("one", name = "Alex", email = "alex@first.test"),
            commit("two", name = "Alex", email = "alex@second.test"),
        ), ZoneOffset.UTC)

        assertEquals(2, report.contributors.size)
        assertEquals(setOf("alex@first.test", "alex@second.test"), report.contributors.map(Contributor::email).toSet())
    }

    @Test
    fun `daily activity uses the requested timezone around midnight`() {
        val commits = listOf(
            commit("earlier", timestamp = "2026-10-18T20:59:59Z"),
            commit("later", timestamp = "2026-10-18T21:00:00Z"),
        )

        val utc = ActivityReport.from(commits, ZoneOffset.UTC)
        val istanbul = ActivityReport.from(commits, ZoneId.of("Europe/Istanbul"))

        assertEquals(mapOf(LocalDate.of(2026, 10, 18) to 2), utc.dailyActivity)
        assertEquals(mapOf(LocalDate.of(2026, 10, 18) to 1, LocalDate.of(2026, 10, 19) to 1), istanbul.dailyActivity)
    }

    @Test
    fun `daylight saving repeated hours stay in the same local day`() {
        val report = ActivityReport.from(listOf(
            commit("first-hour", timestamp = "2026-11-01T05:30:00Z"),
            commit("repeated-hour", timestamp = "2026-11-01T06:30:00Z"),
        ), ZoneId.of("America/New_York"))

        assertEquals(mapOf(LocalDate.of(2026, 11, 1) to 2), report.dailyActivity)
    }

    @Test
    fun `activity dates sort chronologically regardless of history order`() {
        val report = ActivityReport.from(listOf(
            commit("latest", timestamp = "2026-10-19T12:00:00Z"),
            commit("oldest", timestamp = "2026-10-17T12:00:00Z"),
            commit("middle", timestamp = "2026-10-18T12:00:00Z"),
        ), ZoneOffset.UTC)

        assertEquals(listOf(17, 18, 19), report.dailyActivity.keys.map(LocalDate::getDayOfMonth))
    }

    @Test
    fun `root and normal commits are separate from merges including octopus merges`() {
        val report = ActivityReport.from(listOf(
            commit("root"),
            commit("normal", parents = listOf("root")),
            commit("merge", parents = listOf("first", "second")),
            commit("octopus", parents = listOf("first", "second", "third")),
        ), ZoneOffset.UTC)

        assertEquals(4, report.commitCount)
        assertEquals(2, report.mergeCount)
    }

    private fun commit(
        hash: String,
        name: String = "Developer",
        email: String = "developer@example.test",
        timestamp: String = "2026-10-18T12:00:00Z",
        parents: List<String> = emptyList(),
    ) = GitCommit(hash, name, email, Instant.parse(timestamp), hash, parents)
}
