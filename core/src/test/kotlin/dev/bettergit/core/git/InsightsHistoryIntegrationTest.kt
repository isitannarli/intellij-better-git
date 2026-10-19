package dev.bettergit.core.git

import dev.bettergit.core.reports.InsightsReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class InsightsHistoryIntegrationTest {
    @TempDir lateinit var directory: Path
    private lateinit var root: Path
    private lateinit var hooks: Path
    private val client = GitClient()
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.ofInstant(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        root = Files.createDirectory(directory.resolve("repo"))
        hooks = Files.createDirectory(directory.resolve("empty-hooks"))
        git("init", "--initial-branch=main")
        git("config", "user.name", "Old Name")
        git("config", "user.email", "alias@example.test")
        git("config", "commit.gpgsign", "false")
    }

    @Test
    fun `history includes exact time boundaries and ignores future descendants`() {
        val start = today.minusDays(729).atStartOfDay().toInstant(ZoneOffset.UTC)
        val currentStart = today.minusDays(364).atStartOfDay().toInstant(ZoneOffset.UTC)
        commitAt("outside-window", start.minusSeconds(1))
        commitAt("previous-start", start)
        commitAt("previous-end", currentStart.minusSeconds(1))
        commitAt("current-start", currentStart)
        commitAt("now", now)
        commitAt("future", now.plusSeconds(1))

        val history = client.insightsHistory(root, clock, ZoneOffset.UTC)
        assertEquals(setOf("previous-start", "previous-end", "current-start", "now"), history.entries.map { it.commit.subject }.toSet())
        assertFalse(history.truncated)
        assertFalse(history.shallow)
        assertEquals(today.minusDays(729), history.startDate)
        assertEquals(today, history.endDate)
        val report = InsightsReport.from(history)
        assertEquals(2, report.currentTotal)
        assertEquals(2, report.previousTotal)
    }

    @Test
    fun `since as filter traverses old dated descendants to discover recent ancestors`() {
        commitAt("recent-ancestor", now.minusSeconds(5 * 86400))
        commitAt("old-bridge", today.minusDays(800).atStartOfDay().toInstant(ZoneOffset.UTC))
        commitAt("recent-head", now.minusSeconds(86400))

        val history = client.insightsHistory(root, clock, ZoneOffset.UTC)
        assertEquals(setOf("recent-ancestor", "recent-head"), history.entries.map { it.commit.subject }.toSet())
    }

    @Test
    fun `local branch remote and tag roots contribute unique commits and report truncation accurately`() {
        commitAt("shared", now.minusSeconds(5 * 86400))
        val shared = git("rev-parse", "HEAD")
        git("branch", "side")
        git("tag", "shared-tag")
        git("checkout", "side")
        commitAt("side-only", now.minusSeconds(3 * 86400))
        git("checkout", "--detach", shared)
        commitAt("remote-only", now.minusSeconds(2 * 86400))
        git("update-ref", "refs/remotes/origin/remote-only", "HEAD")
        git("checkout", "--detach", shared)
        commitAt("tag-only", now.minusSeconds(2 * 86400))
        git("tag", "tag-only")
        git("checkout", "main")
        commitAt("main-only", now.minusSeconds(86400))

        val complete = client.insightsHistory(root, clock, ZoneOffset.UTC, maxCommits = 5)
        assertEquals(setOf("shared", "side-only", "remote-only", "tag-only", "main-only"), complete.entries.map { it.commit.subject }.toSet())
        assertEquals(5, complete.entries.size)
        assertEquals(5, complete.entries.map { it.commit.hash }.distinct().size)
        assertFalse(complete.truncated)
        val limited = client.insightsHistory(root, clock, ZoneOffset.UTC, maxCommits = 4)
        assertEquals(4, limited.entries.size)
        assertTrue(limited.truncated)
        assertEquals(4, limited.limit)
    }

    @Test
    fun `stash and notes auxiliary commits do not count as repository activity`() {
        commitAt("project-commit", now.minusSeconds(86400))
        Files.writeString(root.resolve("saved-work.txt"), "unfinished work\n")
        val auxiliaryTime = now.minusSeconds(3600).toString()
        val dates = mapOf("GIT_AUTHOR_DATE" to auxiliaryTime, "GIT_COMMITTER_DATE" to auxiliaryTime)
        runGit(listOf("stash", "push", "--include-untracked", "--message", "saved-work"), dates)
        runGit(listOf("notes", "add", "--message", "review annotation", "HEAD"), dates)
        assertTrue(git("rev-list", "--all").lines().size > 1, "Fixture must contain auxiliary commits")

        val history = client.insightsHistory(root, clock, ZoneOffset.UTC, maxCommits = 1)
        assertEquals(listOf("project-commit"), history.entries.map { it.commit.subject })
        assertFalse(history.truncated)
        assertEquals(0, InsightsReport.from(history).mergeCount)
    }

    @Test
    fun `detached HEAD contributes commits without a named branch or tag`() {
        commitAt("branch-commit", now.minusSeconds(2 * 86400))
        git("checkout", "--detach")
        commitAt("detached-commit", now.minusSeconds(86400))

        val history = client.insightsHistory(root, clock, ZoneOffset.UTC)
        assertEquals(setOf("branch-commit", "detached-commit"), history.entries.map { it.commit.subject }.toSet())
        assertEquals(2, history.entries.size)
        assertFalse(history.truncated)
    }

    @Test
    fun `mailmap canonicalizes contributors while author and committer dates stay distinct`() {
        val authorTime = Instant.parse("2020-01-01T00:00:00Z")
        commitAt("recently-integrated", now.minusSeconds(3600), authorTime)
        Files.writeString(root.resolve(".mailmap"), "Canonical Person <canonical@example.test> Old Name <alias@example.test>\n")

        val history = client.insightsHistory(root, clock, ZoneOffset.UTC)
        val entry = history.entries.single()
        assertEquals("Canonical Person", entry.commit.author)
        assertEquals("canonical@example.test", entry.commit.email)
        assertEquals(authorTime, entry.commit.timestamp)
        assertEquals(now.minusSeconds(3600), entry.committedAt)
        assertEquals(1, InsightsReport.from(history).currentTotal)
    }

    @Test
    fun `local day boundaries and the injected clock determine the loaded range`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val localToday = LocalDate.ofInstant(now, zone)
        val start = localToday.minusDays(729).atStartOfDay(zone).toInstant()
        commitAt("before-local-window", start.minusSeconds(1))
        commitAt("local-window-start", start)

        val history = client.insightsHistory(root, clock, zone)
        assertEquals(listOf("local-window-start"), history.entries.map { it.commit.subject })
        assertEquals(zone, history.zone)
        assertEquals(now, history.capturedAt)
        assertEquals(1, history.commitsOn(localToday.minusDays(729)).size)
    }

    @Test
    fun `shallow history explicitly reports incomplete coverage even below the row cap`() {
        commitAt("older", now.minusSeconds(2 * 86400))
        commitAt("latest", now.minusSeconds(86400))
        val clone = directory.resolve("shallow-clone")
        git("clone", "--depth=1", root.toUri().toString(), clone.toString())

        val history = client.insightsHistory(clone, clock, ZoneOffset.UTC)
        assertTrue(history.shallow)
        assertFalse(history.truncated)
        assertEquals(1, history.entries.size)
        assertTrue(InsightsReport.from(history).shallow)
    }

    @Test
    fun `empty history remains useful and invalid limits are rejected before launching Git`() {
        val history = client.insightsHistory(root, clock, ZoneOffset.UTC)
        assertTrue(history.entries.isEmpty())
        assertFalse(history.truncated)
        assertThrows(IllegalArgumentException::class.java) { client.insightsHistory(root, clock, ZoneOffset.UTC, 0) }
        assertThrows(IllegalArgumentException::class.java) { client.insightsHistory(root, clock, ZoneOffset.UTC, 50_001) }
    }

    private fun commitAt(subject: String, committedAt: Instant, authorAt: Instant = committedAt) {
        runGit(listOf("commit", "--allow-empty", "--message", subject), mapOf(
            "GIT_AUTHOR_DATE" to authorAt.toString(), "GIT_COMMITTER_DATE" to committedAt.toString(),
        ))
    }

    private fun git(vararg arguments: String): String = runGit(arguments.toList())

    private fun runGit(arguments: List<String>, extraEnvironment: Map<String, String> = emptyMap()): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=$hooks", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = directory.resolve("no-global-config").toString()
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().putAll(extraEnvironment)
            }.start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Git fixture timed out" }
        check(process.exitValue() == 0) { "Git fixture failed: $output" }
        return output.trimEnd()
    }
}
