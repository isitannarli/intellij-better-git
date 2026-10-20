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

    @Test fun `stage and unstage literal paths without touching other files`() {
        val literal = ":(glob)* space\nfile.txt"
        Files.writeString(root.resolve(literal), "new\n")
        Files.writeString(root.resolve("other.txt"), "other\n")
        git.stageChanges(root, listOf(git.workingChanges(root).single { it.path == literal }))
        assertTrue(git.workingChanges(root).single { it.path == literal }.staged)
        assertTrue(git.workingChanges(root).single { it.path == "other.txt" }.untracked)
        Files.writeString(root.resolve(literal), "newer working edit\n")
        git.unstageChanges(root, listOf(git.workingChanges(root).single { it.path == literal }))
        assertTrue(git.workingChanges(root).all { it.untracked })
        assertEquals("newer working edit\n", Files.readString(root.resolve(literal)))
    }

    @Test fun `discard restores index content and leaves staged edits and other files intact`() {
        initialFiles()
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", "file.txt")
        Files.writeString(root.resolve("file.txt"), "unstaged\n")
        Files.writeString(root.resolve("other.txt"), "unrelated\n")
        git.discardChanges(root, listOf(git.workingChanges(root).single { it.path == "file.txt" }))
        assertEquals("staged\n", Files.readString(root.resolve("file.txt")))
        assertEquals("staged\n", command("show", ":file.txt"))
        assertEquals("unrelated\n", Files.readString(root.resolve("other.txt")))
        assertFalse(git.workingChanges(root).single { it.path == "file.txt" }.unstaged)
    }

    @Test fun `stage renamed working edit then unstage restores both rename paths`() {
        initialFiles()
        command("mv", "file.txt", "renamed.txt")
        Files.writeString(root.resolve("renamed.txt"), "initial\nextra\n")
        git.stageChanges(root, listOf(git.workingChanges(root).single()))
        assertFalse(git.workingChanges(root).single().unstaged)
        git.unstageChanges(root, git.workingChanges(root))
        assertTrue(git.workingChanges(root).none { it.staged })
        assertEquals("initial\n", command("show", ":file.txt"))
        assertEquals("initial\nextra\n", Files.readString(root.resolve("renamed.txt")))
    }

    @Test fun `selected stash includes staged working and literal untracked files only`() {
        initialFiles()
        val literal = ":(glob)* space\nfile.txt"
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", "file.txt")
        Files.writeString(root.resolve("file.txt"), "working\n")
        Files.writeString(root.resolve(literal), "new\n")
        Files.writeString(root.resolve("other.txt"), "unrelated staged\n")
        command("add", "other.txt")
        git.stashChanges(root, git.workingChanges(root).filter { it.path != "other.txt" }, "Selected changes")
        assertEquals("working\n", command("show", "stash@{0}:file.txt"))
        assertEquals("staged\n", command("show", "stash@{0}^2:file.txt"))
        assertEquals("new\n", command("show", "stash@{0}^3:$literal"))
        assertEquals("initial\n", Files.readString(root.resolve("file.txt")))
        assertFalse(Files.exists(root.resolve(literal)))
        assertEquals("unrelated staged\n", command("show", ":other.txt"))
        assertEquals(listOf("other.txt"), git.workingChanges(root).map { it.path })
    }

    @Test fun `selected stash handles both sides of a staged rename`() {
        initialFiles()
        command("mv", "file.txt", "renamed.txt")
        git.stashChanges(root, git.workingChanges(root), "Rename")
        assertTrue(git.workingChanges(root).isEmpty())
        assertTrue(Files.exists(root.resolve("file.txt")))
        assertFalse(Files.exists(root.resolve("renamed.txt")))
        assertEquals("initial\n", command("show", "stash@{0}:renamed.txt"))
    }

    @Test fun `stashing a staged deletion excludes other modified and untracked files`() {
        initialFiles()
        command("rm", "file.txt")
        Files.writeString(root.resolve("other.txt"), "keep this edit\n")
        Files.writeString(root.resolve("untracked.txt"), "keep this new file\n")
        git.stashChanges(root, listOf(git.workingChanges(root).single { it.path == "file.txt" }), "Deletion")
        assertEquals("initial\n", Files.readString(root.resolve("file.txt")))
        assertEquals("keep this edit\n", Files.readString(root.resolve("other.txt")))
        assertEquals("keep this new file\n", Files.readString(root.resolve("untracked.txt")))
        assertEquals("other\n", command("show", "stash@{0}:other.txt"))
        assertEquals(setOf("other.txt", "untracked.txt"), git.workingChanges(root).map { it.path }.toSet())
    }

    @Test fun `delete removes only selected file and symlink entries leaving index and link target intact`() {
        initialFiles()
        val external = Files.createTempFile("better-git-delete-", ".txt")
        try {
            Files.writeString(external, "outside\n")
            Files.createSymbolicLink(root.resolve("link"), external)
            Files.writeString(root.resolve("file.txt"), "edited\n")
            git.deleteWorkingFiles(root, git.workingChanges(root))
            assertFalse(Files.exists(root.resolve("file.txt")))
            assertFalse(Files.exists(root.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("outside\n", Files.readString(external))
            assertEquals("initial\n", command("show", ":file.txt"))
            assertTrue(Files.exists(root.resolve("other.txt")))
            git.discardChanges(root, git.workingChanges(root))
            assertEquals("initial\n", Files.readString(root.resolve("file.txt")))
        } finally { Files.deleteIfExists(external) }
    }

    @Test fun `mutations reject stale selection unsafe paths and untracked discard`() {
        initialFiles()
        Files.writeString(root.resolve("new.txt"), "untracked\n")
        val selected = git.workingChanges(root)
        assertThrows(IllegalArgumentException::class.java) { git.discardChanges(root, selected) }
        git.stageChanges(root, selected)
        assertThrows(IllegalArgumentException::class.java) { git.deleteWorkingFiles(root, selected) }
        for (path in listOf("../outside", ".git/config", ":(top)/outside", "/outside")) {
            assertThrows(IllegalArgumentException::class.java) { git.stageChanges(root, listOf(WorkingChange(path, untracked = true))) }
        }
        assertThrows(IllegalArgumentException::class.java) { git.stashChanges(root, emptyList(), "Empty") }
    }

    @Test fun `line totals distinguish staged and working content and update without status changes`() {
        Files.writeString(root.resolve("code.kt"), "one\ntwo\nthree\n")
        command("add", "."); command("commit", "-m", "initial")
        Files.writeString(root.resolve("code.kt"), "one\ntwo edited\nthree\nfour\n")
        command("add", ".")
        Files.writeString(root.resolve("code.kt"), "one\ntwo edited\nfour\n")
        val first = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(2, 1), first.stagedStats["code.kt"])
        assertEquals(FileLineStats(0, 1), first.workingStats["code.kt"])
        Files.writeString(root.resolve("code.kt"), "one\ntwo edited\nfour\nfive\nsix\n")
        val second = git.workingChangesWithStats(root)
        assertEquals(first.changes, second.changes)
        assertEquals(FileLineStats(2, 1), second.workingStats["code.kt"])
        assertEquals(first.stagedStats, second.stagedStats)
    }

    @Test fun `line totals support new files empty files missing final newline and binary files`() {
        val literal = ":(glob)* new\nwith\ttabs.txt"
        Files.writeString(root.resolve(literal), "one\r\ntwo")
        Files.writeString(root.resolve("empty.txt"), "")
        Files.write(root.resolve("binary.dat"), byteArrayOf(1, 0, 2))
        val untracked = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(2, 0), untracked.workingStats[literal])
        assertEquals(FileLineStats(0, 0), untracked.workingStats["empty.txt"])
        assertTrue(untracked.workingStats.getValue("binary.dat").binary)
        command("add", ".")
        val staged = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(2, 0), staged.stagedStats[literal])
        assertEquals(FileLineStats(0, 0), staged.stagedStats["empty.txt"])
        assertTrue(staged.stagedStats.getValue("binary.dat").binary)
        assertTrue(staged.workingStats.isEmpty())
    }

    @Test fun `rename deletion and mode-only totals preserve path and stage boundaries`() {
        val old = "old\tname.txt"
        val renamed = "new\nname.txt"
        Files.writeString(root.resolve(old), "one\ntwo\nthree\n")
        command("add", "."); command("commit", "-m", "initial")
        command("mv", old, renamed)
        var snapshot = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(0, 0), snapshot.stagedStats[renamed])
        Files.delete(root.resolve(renamed))
        snapshot = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(0, 3), snapshot.workingStats[renamed])
        command("add", "--all")
        snapshot = git.workingChangesWithStats(root)
        assertEquals(FileLineStats(0, 3), snapshot.stagedStats[old])
        command("reset", "--hard", "HEAD")
        command("update-index", "--chmod=+x", "--", old)
        assertEquals(FileLineStats(0, 0), git.workingChangesWithStats(root).stagedStats[old])
    }

    @Test fun `untracked line totals do not follow symlinks or read oversized files`() {
        val outside = Files.createTempFile("better-git-line-count-", ".txt")
        try {
            Files.writeString(outside, "outside\ncontents\n")
            Files.createSymbolicLink(root.resolve("link"), outside)
            Files.write(root.resolve("large"), ByteArray(8 * 1024 * 1024 + 1) { 65 })
            val snapshot = git.workingChangesWithStats(root)
            assertEquals(FileLineStats(1, 0), snapshot.workingStats["link"])
            assertNull(snapshot.workingStats["large"])
        } finally { Files.deleteIfExists(outside) }
    }

    @Test fun `numstat parser consumes rename paths and keeps literal tabs newlines and binary markers`() {
        val output = "5\t2\t\u0000old\tpath\u0000new\npath\u0000-\t-\tbinary\u00000\t0\tmode\u0000"
        assertEquals(mapOf("new\npath" to FileLineStats(5, 2), "binary" to FileLineStats(null, null), "mode" to FileLineStats(0, 0)), GitNumstatParser.parse(output))
        assertThrows(IllegalArgumentException::class.java) { GitNumstatParser.parse("1\t2\t\u0000missing-target\u0000") }
    }

    private fun initialFiles() {
        Files.writeString(root.resolve("file.txt"), "initial\n")
        Files.writeString(root.resolve("other.txt"), "other\n")
        command("add", "."); command("commit", "-m", "initial")
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
