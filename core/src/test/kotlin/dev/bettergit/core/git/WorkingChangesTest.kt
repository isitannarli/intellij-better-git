package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class WorkingChangesTest {
    @TempDir lateinit var root: Path
    private val git = GitClient()

    @BeforeEach fun setup() {
        command("init", "--initial-branch=main")
        command("config", "user.name", "Developer")
        command("config", "user.email", "dev@example.test")
        command("config", "commit.gpgsign", "false")
    }

    @Test fun `staged and saved working changes have independent diffs`() {
        Files.writeString(root.resolve("code.kt"), "original\n")
        command("add", "."); command("commit", "-m", "initial")
        Files.writeString(root.resolve("code.kt"), "staged\n"); command("add", ".")
        Files.writeString(root.resolve("code.kt"), "working\n")
        val change = git.workingChanges(root).single()
        assertTrue(change.staged); assertTrue(change.unstaged)
        val staged = git.workingFileDiff(root, change, true)
        val working = git.workingFileDiff(root, change, false)
        assertEquals("original\n", staged.before); assertEquals("staged\n", staged.after)
        assertEquals("staged\n", working.before); assertEquals("working\n", working.after)
        assertEquals("staged\n", command("show", ":code.kt"))
    }

    @Test fun `unborn additions and literal untracked paths show empty before content`() {
        val path = ":(glob) space\nand tab\t.kt"
        Files.writeString(root.resolve(path), "new file\n")
        val untracked = git.workingChanges(root).single()
        assertTrue(untracked.untracked)
        assertEquals("", git.workingFileDiff(root, untracked, false).before)
        command("add", ".")
        val staged = git.workingChanges(root).single()
        assertEquals(path, staged.path)
        assertEquals("new file\n", git.workingFileDiff(root, staged, true).after)
        assertEquals("", git.workingFileDiff(root, staged, true).before)
    }

    @Test fun `rename and deletion previews preserve their index boundaries`() {
        Files.writeString(root.resolve("old file.txt"), "stable\n")
        command("add", "."); command("commit", "-m", "initial")
        command("mv", "old file.txt", "new file.txt")
        val rename = git.workingChanges(root).single()
        assertEquals("old file.txt", rename.oldPath)
        assertEquals("stable\n", git.workingFileDiff(root, rename, true).before)
        Files.delete(root.resolve("new file.txt"))
        val deleted = git.workingChanges(root).single()
        assertEquals("", git.workingFileDiff(root, deleted, false).after)
    }

    @Test fun `symlink preview reads link text and never its target`() {
        val external = Files.createTempFile("better-git-preview-", ".txt")
        try {
            Files.writeString(external, "private target content")
            Files.createSymbolicLink(root.resolve("link"), external)
            val preview = git.workingFileDiff(root, git.workingChanges(root).single(), false)
            assertEquals(external.toString(), preview.after)
            assertFalse(preview.after.contains("private target"))
        } finally { Files.deleteIfExists(external) }
    }

    @Test fun `binary and oversized files fail with bounded preview errors`() {
        Files.write(root.resolve("binary"), byteArrayOf(0, 1, 2))
        assertThrows(IllegalArgumentException::class.java) { git.workingFileDiff(root, git.workingChanges(root).single(), false) }
        Files.delete(root.resolve("binary"))
        Files.write(root.resolve("large"), ByteArray(2_000_001) { 65 })
        assertThrows(IllegalArgumentException::class.java) { git.workingFileDiff(root, git.workingChanges(root).single(), false) }
    }

    @Test fun `porcelain parser handles rename record markers conflict and submodule`() {
        val input = listOf(
            "2 R. N... 100644 100644 100644 aaa bbb R100 new\nname",
            "? old name",
            "u UU N... 100644 100644 100644 100644 aaa bbb ccc conflict file",
            "1 .M S.M. 160000 160000 160000 aaa aaa module",
            "? real untracked", "",
        ).joinToString("\u0000")
        val changes = WorkingChangesParser.parse(input)
        assertEquals(4, changes.size)
        assertEquals("? old name", changes[0].oldPath)
        assertTrue(changes[0].staged)
        assertTrue(changes[1].conflicted); assertFalse(changes[1].staged)
        assertTrue(changes[2].submodule)
        assertTrue(changes[3].untracked)
    }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=${root.resolve(".git/disabled-hooks")}", "-C", root.toString()) + args)
            .redirectErrorStream(true).apply {
                environment()["GIT_CONFIG_GLOBAL"] = root.resolve(".git/nonexistent-global-config").toString()
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        return output
    }
}
