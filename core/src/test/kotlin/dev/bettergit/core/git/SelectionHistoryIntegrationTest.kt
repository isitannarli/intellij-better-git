package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SelectionHistoryIntegrationTest {
    @TempDir lateinit var directory: Path
    private lateinit var root: Path
    private lateinit var hooks: Path
    private val client = GitClient()

    @BeforeEach
    fun setUp() {
        root = Files.createDirectory(directory.resolve("repository"))
        hooks = Files.createDirectory(directory.resolve("empty-hooks"))
        git("init", "--initial-branch=main")
        git("config", "user.name", "Selection Developer")
        git("config", "user.email", "selection@example.test")
        git("config", "core.autocrlf", "false")
    }

    @Test
    fun `unsaved inserted prefix maps exactly and unrelated commit edits are excluded`() {
        write("file.txt", "one\ntwo\nthree\n"); commit("Initial")
        write("file.txt", "one\nupdated two\nthree\n"); commit("Selected change")
        write("file.txt", "updated one\nupdated two\nthree\n"); commit("Other line")
        val snapshot = "unsaved A\nunsaved B\nupdated one\nupdated two\nthree\n"
        val before = git("status", "--porcelain=v2", "-z")

        val history = client.selectionHistory(root, "file.txt", LineRange(4, 4), snapshot)

        assertEquals(LineRange(2, 2), history.headRange)
        assertEquals(listOf("Selected change", "Initial"), history.entries.map { it.commit.subject })
        assertTrue(history.entries.first().patch.contains("+updated two"))
        assertTrue(history.hasLocalChanges)
        assertFalse(history.mappingApproximate)
        assertTrue(history.localPatch.isEmpty())
        assertEquals(before, git("status", "--porcelain=v2", "-z"))
        assertEquals("updated one\nupdated two\nthree\n", Files.readString(root.resolve("file.txt")))
    }

    @Test
    fun `unsaved deleted lines before selection map to their original coordinates`() {
        write("file.txt", "first\nremoved A\nremoved B\ntarget\nlast\n"); commit("Initial")
        val history = client.selectionHistory(root, "file.txt", LineRange(2, 2), "first\ntarget\nlast\n")
        assertEquals(LineRange(4, 4), history.headRange)
        assertFalse(history.mappingApproximate)
        assertEquals("Initial", history.entries.single().commit.subject)
        assertTrue(history.entries.single().patch.contains("+target"))
    }

    @Test
    fun `entirely new local lines have a local patch and no invented inherited history`() {
        write("file.txt", "first\nlast\n"); commit("Initial")
        val history = client.selectionHistory(root, "file.txt", LineRange(2, 3), "first\nnew A\nnew B\nlast\n")
        assertNull(history.headRange)
        assertTrue(history.entries.isEmpty())
        assertTrue(history.hasLocalChanges)
        assertFalse(history.mappingApproximate)
        assertTrue(history.localPatch.contains("+new A\n+new B"))
        assertFalse(history.localPatch.contains("better-git-selection-"))
    }

    @Test
    fun `overlapping replacement returns the complete old hunk with approximate mapping`() {
        write("file.txt", "first\nold A\nold B\nlast\n"); commit("Initial")
        val history = client.selectionHistory(root, "file.txt", LineRange(2, 2), "first\nreplacement\nlast\n")
        assertEquals(LineRange(2, 3), history.headRange)
        assertTrue(history.mappingApproximate)
        assertTrue(history.localPatch.contains("-old A\n-old B\n+replacement"))
        assertEquals("Initial", history.entries.single().commit.subject)
    }

    @Test
    fun `unborn and untracked editor snapshots remain local only without creating files`() {
        val unborn = client.selectionHistory(root, "new.txt", LineRange(1, 1), "new code\n")
        assertNull(unborn.headRange)
        assertTrue(unborn.entries.isEmpty())
        assertTrue(unborn.localPatch.contains("+new code"))
        assertFalse(Files.exists(root.resolve("new.txt")))
        write("tracked.txt", "existing\n"); commit("Initial")
        val untracked = client.selectionHistory(root, "new.txt", LineRange(1, 1), "new code\n")
        assertNull(untracked.headRange)
        assertTrue(untracked.entries.isEmpty())
        assertTrue(untracked.hasLocalChanges)
        assertFalse(Files.exists(root.resolve("new.txt")))
    }

    @Test
    fun `staged rename resolves the HEAD path and keeps literal special filename characters`() {
        val original = ":(glob) old\nname:part.txt"
        val renamed = "renamed\tname:part.txt"
        write(original, "first\ntarget\nlast\n"); commit("Initial")
        git("mv", "--", original, renamed)
        val status = git("status", "--porcelain=v2", "-z")
        val history = client.selectionHistory(root, renamed, LineRange(3, 3), "prefix\nfirst\ntarget\nlast\n")
        assertEquals(renamed, history.path)
        assertEquals(LineRange(2, 2), history.headRange)
        assertEquals("Initial", history.entries.single().commit.subject)
        assertTrue(history.entries.single().patch.contains("+target"))
        assertTrue(history.hasLocalChanges)
        assertEquals(status, git("status", "--porcelain=v2", "-z"))
    }

    @Test
    fun `committed renames follow line ancestry and unchanged rename commits are omitted`() {
        write("old.txt", "target\nlast\n"); commit("Initial")
        git("mv", "old.txt", "new.txt"); commit("Rename only")
        val history = client.selectionHistory(root, "new.txt", LineRange(1, 1), "target\nlast\n")
        assertEquals(listOf("Initial"), history.entries.map { it.commit.subject })
        assertFalse(history.hasLocalChanges)
        assertFalse(history.mappingApproximate)
    }

    @Test
    fun `merge line history follows the contributing parent rather than inventing a merge patch`() {
        write("file.txt", "old\nlast\n"); commit("Initial")
        git("checkout", "-b", "feature")
        write("file.txt", "feature code\nlast\n"); commit("Feature change")
        git("checkout", "main")
        write("other.txt", "main\n"); commit("Main unrelated")
        git("merge", "--no-ff", "feature", "--message", "Merge feature")
        val history = client.selectionHistory(root, "file.txt", LineRange(1, 1), "feature code\nlast\n")
        assertEquals(listOf("Feature change", "Initial"), history.entries.map { it.commit.subject })
        assertTrue(history.entries.first().patch.contains("-old\n+feature code"))
    }

    @Test
    fun `CRLF HEAD and LF editor snapshot represent the same lines`() {
        write("file.txt", "first\r\ntarget\r\nlast\r\n"); commit("CRLF file")
        val history = client.selectionHistory(root, "file.txt", LineRange(2, 2), "first\ntarget\nlast\n")
        assertEquals(LineRange(2, 2), history.headRange)
        assertFalse(history.hasLocalChanges)
        assertFalse(history.mappingApproximate)
        assertTrue(history.localPatch.isEmpty())
    }

    @Test
    fun `history limits count matching commits and shallow clones expose incomplete coverage`() {
        repeat(4) { write("file.txt", "version $it\n"); commit("Version $it") }
        repeat(3) { git("commit", "--allow-empty", "--message", "Unrelated $it") }
        val exact = client.selectionHistory(root, "file.txt", LineRange(1, 1), "version 3\n", 4)
        assertEquals(4, exact.entries.size)
        assertFalse(exact.truncated)
        val limited = client.selectionHistory(root, "file.txt", LineRange(1, 1), "version 3\n", 2)
        assertEquals(2, limited.entries.size)
        assertTrue(limited.truncated)
        val clone = directory.resolve("shallow")
        git("clone", "--depth=1", root.toUri().toString(), clone.toString())
        val shallow = client.selectionHistory(clone, "file.txt", LineRange(1, 1), "version 3\n")
        assertTrue(shallow.shallow)
    }

    @Test
    fun `invalid ranges binary huge snapshots and symlink HEAD files are rejected`() {
        write("file.txt", "text\n"); commit("Initial")
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(2, 2), "text\n") }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(1, 1), "") }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(1, 1), "binary\u0000data") }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(1, 1), "x".repeat(2_000_001)) }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(1, 1), "text\n", 0) }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "file.txt", LineRange(1, 1), "text\n", 201) }
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "../outside", LineRange(1, 1), "text\n") }
        assumeTrue(Files.exists(Path.of("/bin/sh")))
        Files.createSymbolicLink(root.resolve("link.txt"), Path.of("file.txt")); commit("Symbolic link")
        assertThrows(IllegalArgumentException::class.java) { client.selectionHistory(root, "link.txt", LineRange(1, 1), "text\n") }
    }

    @Test
    fun `custom diff indicators cannot corrupt selection mapping or returned patches`() {
        write("file.txt", "first\nold\nlast\n"); commit("Initial")
        git("config", "diff.outputIndicatorNew", ">")
        git("config", "diff.outputIndicatorOld", "<")
        git("config", "diff.outputIndicatorContext", "=")
        git("config", "diff.context", "20")
        val history = client.selectionHistory(root, "file.txt", LineRange(2, 2), "first\nnew\nlast\n")
        assertEquals(LineRange(2, 2), history.headRange)
        assertTrue(history.localPatch.contains("-old\n+new"))
        assertTrue(history.entries.single().patch.contains("+old"))
        val hunkLines = history.localPatch.lineSequence().dropWhile { !it.startsWith("@@ ") }
            .drop(1).filter(String::isNotEmpty).toList()
        assertEquals(listOf("-old", "+new"), hunkLines)
    }

    @Test
    fun `interrupting a running snapshot diff cancels the process and removes temporary files`() {
        assumeTrue(Files.exists(Path.of("/bin/sh")))
        write("file.txt", "old\n"); commit("Initial")
        val marker = directory.resolve("diff-arguments.txt")
        val wrapper = directory.resolve("slow-git")
        Files.writeString(wrapper, "#!/bin/sh\ncase \" $* \" in\n*\" diff --no-index \"*) printf '%s\\n' \"$@\" > ${shellQuote(marker.toString())}; sleep 30;;\nesac\nexec git \"$@\"\n")
        check(wrapper.toFile().setExecutable(true))
        val failure = AtomicReference<Throwable>()
        val worker = Thread.ofPlatform().start {
            try { GitClient(wrapper.toString()).selectionHistory(root, "file.txt", LineRange(1, 1), "new\n") }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(marker) && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(marker), "Snapshot diff must start before cancellation")
            val temporaryFile = Path.of(Files.readAllLines(marker).last())
            assertTrue(Files.exists(temporaryFile))
            worker.interrupt(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertInstanceOf(CancellationException::class.java, failure.get())
            assertFalse(Files.exists(temporaryFile.parent), "Cancelled snapshot files must be removed")
            assertEquals("old\n", Files.readString(root.resolve("file.txt")))
        } finally { worker.interrupt(); worker.join(5000) }
    }

    private fun write(path: String, text: String) { Files.writeString(root.resolve(path), text) }
    private fun commit(subject: String) { git("add", "--all"); git("commit", "--message", subject) }
    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--literal-pathspecs", "-c", "core.hooksPath=$hooks", "-c", "commit.gpgsign=false", "-C", root.toString()) + args)
            .redirectErrorStream(true).apply {
                environment()["GIT_CONFIG_GLOBAL"] = directory.resolve("no-global-config").toString()
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }.start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Git fixture timed out" }
        check(process.exitValue() == 0) { "Git fixture failed: $output" }
        return output
    }
}
