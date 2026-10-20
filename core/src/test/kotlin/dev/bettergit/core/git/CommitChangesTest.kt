package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CommitChangesTest {
    @TempDir lateinit var root: Path
    private val git = GitClient()

    @BeforeEach fun setup() {
        command("init", "--initial-branch=main")
        command("config", "user.name", "Developer")
        command("config", "user.email", "dev@example.test")
        command("config", "commit.gpgsign", "false")
        command("config", "core.autocrlf", "false")
        command("config", "core.hooksPath", root.resolve(".git/hooks").toString())
    }

    @Test fun `commit preserves partial staging unrelated files and multiline message`() {
        Files.writeString(root.resolve("code.txt"), "original\n")
        command("add", "."); command("commit", "-m", "Initial")
        Files.writeString(root.resolve("code.txt"), "staged\n")
        command("add", ".")
        Files.writeString(root.resolve("code.txt"), "unstaged\n")
        Files.writeString(root.resolve("new.txt"), "untracked\n")
        val message = "fix: update code\n\nKeep #123 and literal $(text) `text`.\n# Body detail\n"

        git.commitChanges(root, message, expectedBranch = "main")

        assertEquals("staged\n", command("show", "HEAD:code.txt"))
        assertEquals(message.trimEnd(), command("log", "-1", "--format=%B").trimEnd())
        assertEquals("unstaged\n", Files.readString(root.resolve("code.txt")))
        assertEquals(setOf("code.txt", "new.txt"), git.workingChanges(root).map { it.path }.toSet())
        assertTrue(git.workingChanges(root).none { it.staged })
        assertEquals("2", command("rev-list", "--count", "HEAD").trim())
    }

    @Test fun `explicit stage all supports initial commits and includes deletions and new files`() {
        Files.writeString(root.resolve("old.txt"), "first\n")
        git.commitChanges(root, "Initial commit", stageAll = true, expectedBranch = "main")
        Files.delete(root.resolve("old.txt"))
        Files.writeString(root.resolve("new.txt"), "new file\n")
        git.commitChanges(root, "Replace file", stageAll = true)
        assertEquals("new.txt", command("ls-tree", "--name-only", "HEAD").trim())
        assertTrue(git.workingChanges(root).isEmpty())
    }

    @Test fun `invalid message missing stage and branch switch never stage or commit`() {
        Files.writeString(root.resolve("file.txt"), "new\n")
        for (message in listOf("  \n", "bad\u0000message")) {
            assertThrows(IllegalArgumentException::class.java) { git.commitChanges(root, message, stageAll = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { git.commitChanges(root, "No staging") }
        assertThrows(IllegalArgumentException::class.java) { git.commitChanges(root, "Wrong branch", true, "different") }
        assertTrue(git.workingChanges(root).single().untracked)
        assertEquals("", command("ls-files"))
    }

    @Test fun `configured commit hooks run and hook rejection keeps staged changes`() {
        Files.writeString(root.resolve("file.txt"), "new\n")
        val hookDirectory = Files.createDirectories(root.resolve(".git/custom hooks"))
        command("config", "core.hooksPath", hookDirectory.toString())
        val hook = hookDirectory.resolve("commit-msg")
        Files.writeString(hook, "#!/bin/sh\nprintf 'Hook checked\\n' >> \"\$1\"\n")
        assertTrue(hook.toFile().setExecutable(true))
        git.commitChanges(root, "Initial\n", stageAll = true)
        assertTrue(command("log", "-1", "--format=%B").contains("Hook checked"))
        val head = command("rev-parse", "HEAD")
        Files.writeString(root.resolve("file.txt"), "edited\n")
        Files.writeString(hook, "#!/bin/sh\necho 'Message rejected by hook' >&2\nexit 1\n")
        val failure = assertThrows(GitCommandException::class.java) { git.commitChanges(root, "Rejected", stageAll = true) }
        assertTrue(failure.message!!.contains("Message rejected by hook"))
        assertEquals(head, command("rev-parse", "HEAD"))
        assertTrue(git.workingChanges(root).single().staged)
    }

    @Test fun `conflicts cannot be silently staged by stage all and commit`() {
        Files.writeString(root.resolve("file.txt"), "base\n")
        git.commitChanges(root, "Initial", stageAll = true)
        command("checkout", "-b", "feature")
        Files.writeString(root.resolve("file.txt"), "feature\n")
        git.commitChanges(root, "Feature", stageAll = true)
        command("checkout", "main")
        Files.writeString(root.resolve("file.txt"), "main\n")
        git.commitChanges(root, "Main", stageAll = true)
        GitProcessRunner("git").run(root, listOf("merge", "feature"), setOf(1))
        val unmerged = command("ls-files", "-u")
        assertThrows(IllegalArgumentException::class.java) { git.commitChanges(root, "Unresolved", stageAll = true) }
        assertEquals(unmerged, command("ls-files", "-u"))
        assertTrue(git.workingChanges(root).single().conflicted)
    }

    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        check(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { output }
        return output
    }
}
