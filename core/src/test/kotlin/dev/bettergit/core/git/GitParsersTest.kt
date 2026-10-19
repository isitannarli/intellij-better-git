package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.nio.file.Path

class GitParsersTest {
    @Test
    fun `status skips the original rename path even when it resembles a record`() {
        val output = listOf(
            "# branch.head feature/my-work",
            "# branch.ab +3 -2",
            "2 R. N... 100644 100644 100644 aaa bbb R100 new\nname.txt",
            "? original\nname.txt",
            "1 .M N... 100644 100644 100644 aaa aaa tracked.txt",
            "? actual untracked.txt",
            "",
        ).joinToString("\u0000")

        assertEquals(WorkingTreeStatus("feature/my-work", 3, 2, 1, 1, 1), GitParsers.status(output))
    }

    @Test
    fun `changes preserve unusual paths and both sides of a rename`() {
        val output = "R100\u0000old\nfile.txt\u0000new\tfile.txt\u0000M\u0000sp ace.kt\u0000A\u0000:literal.kt\u0000"

        assertEquals(listOf(
            FileChange("new\tfile.txt", "old\nfile.txt", "R100"),
            FileChange("sp ace.kt", status = "M"),
            FileChange(":literal.kt", status = "A"),
        ), GitParsers.changes(output))
    }

    @Test
    fun `log keeps control separators in subjects and parses root parents`() {
        val output = listOf("abc", "A Person", "a@example.test", "1700000000", "subject\u001f text", "", "HEAD -> main", "").joinToString("\u0000")

        assertEquals(listOf(GitCommit("abc", "A Person", "a@example.test", Instant.ofEpochSecond(1700000000), "subject\u001f text", decorations = "HEAD -> main")), GitParsers.log(output))
    }

    @Test
    fun `branches distinguish local and remote refs and omit symbolic remote head`() {
        val output = "refs/heads/main\u0000*\u0000origin/main\u0000[ahead 2, behind 4]\u0000\u0000\n" +
            "refs/remotes/origin/main\u0000 \u0000\u0000\u0000\u0000\n" +
            "refs/remotes/origin/HEAD\u0000 \u0000\u0000\u0000refs/remotes/origin/main\u0000\n"

        assertEquals(listOf(
            GitBranch("main", true, false, "origin/main", 2, 4),
            GitBranch("origin/main", false, true),
        ), GitParsers.branches(output))
    }

    @Test
    fun `blame decodes quoted paths and identifies uncommitted lines`() {
        val output = "${"0".repeat(40)} 7 8 1\nauthor Not Committed Yet\nfilename \"a\\nfile\\t\\303\\251.kt\"\n\tline contents\n"
        val blame = GitParsers.blame(output)

        assertEquals(8, blame?.line)
        assertEquals(7, blame?.originalLine)
        assertEquals("a\nfile\té.kt", blame?.originalPath)
        assertNull(blame?.commit)
    }

    @Test
    fun `file blame shares commit and path identity without changing line metadata`() {
        val hash = "a".repeat(40)
        val output = (1..1000).joinToString("") { line ->
            "$hash ${line + 10} $line 1\nauthor Developer\nauthor-mail <dev@example.test>\n" +
                "author-time 1700000000\nsummary Change\nfilename \"a\\nfile.kt\"\n\tcode $line\n"
        }
        val lines = GitParsers.blameFile(output)
        assertEquals(1000, lines.size)
        assertEquals((1..1000).toList(), lines.map { it.line })
        assertEquals((11..1010).toList(), lines.map { it.originalLine })
        lines.forEach {
            assertSame(lines.first().commit, it.commit)
            assertSame(lines.first().originalPath, it.originalPath)
        }
        assertEquals("a\nfile.kt", lines.first().originalPath)
        assertEquals("dev@example.test", lines.first().commit?.email)
        assertEquals(Instant.ofEpochSecond(1700000000), lines.first().commit?.timestamp)
    }

    @Test
    fun `file blame keeps different commits paths and uncommitted lines separate`() {
        fun record(hash: String, line: Int, path: String, source: String) =
            "$hash $line $line 1\nauthor A\nauthor-mail <a@example.test>\nauthor-time 1\nsummary First\nfilename $path\n\t$source\n"
        val lines = GitParsers.blameFile(record("a".repeat(40), 1, "first.kt", "author fake") +
            record("b".repeat(40), 2, "second.kt", "filename fake") +
            record("0".repeat(40), 3, "second.kt", "not committed"))
        assertEquals(listOf("first.kt", "second.kt", "second.kt"), lines.map { it.originalPath })
        assertNotSame(lines[0].commit, lines[1].commit)
        assertNull(lines[2].commit)
        assertEquals("A", lines[0].commit?.author)
        assertSame(lines[1].originalPath, lines[2].originalPath)
    }

    @Test
    fun `remote keys may include dots and urls may include equals signs`() {
        assertEquals(listOf(GitRemote("team.upstream", "https://git.example/team/repo.git?x=a=b")), GitParsers.remotes(
            "remote.team.upstream.url\nhttps://git.example/team/repo.git?x=a=b\u0000",
        ))
    }

    @Test
    fun `git errors never expose common provider credentials`() {
        val error = redactGitError("fatal: https://user:super-secret@git.example/repo?private_token=private-secret&x=1 ghp_abc123 github_pat_def456 glpat-ghi789\u001b[31m")

        listOf("super-secret", "private-secret", "ghp_abc123", "github_pat_def456", "glpat-ghi789", "\u001b").forEach {
            assertFalse(error.contains(it))
        }
        assertTrue(error.contains("git.example"))
    }

    @Test
    fun `worktrees preserve newlines in paths and lock reasons`() {
        val output = "worktree /tmp/main\nworkspace\u0000HEAD abc\u0000branch refs/heads/main\u0000\u0000" +
            "worktree /tmp/other\u0000HEAD def\u0000detached\u0000locked work in progress\u0000\u0000"

        assertEquals(listOf(
            GitWorktree(Path.of("/tmp/main\nworkspace"), "main", "abc", false),
            GitWorktree(Path.of("/tmp/other"), "(detached)", "def", true),
        ), GitParsers.worktrees(output))
    }
}
