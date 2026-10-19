package dev.bettergit.core.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class GitClientIntegrationTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var root: Path
    private val client = GitClient()

    @BeforeEach
    fun setUp() {
        root = Files.createDirectory(directory.resolve("working tree"))
        git("init", "--initial-branch=main")
        git("config", "user.name", "Test Developer")
        git("config", "user.email", "developer@example.test")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
    }

    @Test
    fun `empty and non repositories have useful results`() {
        assertEquals(root.toRealPath(), client.root(root)?.toRealPath())
        assertNull(client.root(directory))
        assertEquals(WorkingTreeStatus("main", 0, 0, 0, 0, 0), client.status(root))
        assertTrue(client.log(root).isEmpty())
        assertTrue(client.branches(root).isEmpty())
        assertTrue(client.remotes(root).isEmpty())
        assertNull(client.blame(root, "untracked.txt", 1))
    }

    @Test
    fun `graph includes first parent stats root stats refs and bounded pages`() {
        assertTrue(client.graphHistory(root).commits.isEmpty())
        write("odd\nname.txt", "base\n")
        commit("BETTER-GIT-GRAPH")
        git("tag", "-a", "release,one", "-m", "Annotated")
        git("checkout", "-b", "feature")
        write("feature.txt", "feature\n")
        commit("Feature")
        git("checkout", "main")
        write("main.txt", "main\n")
        commit("Main")
        git("merge", "--no-ff", "feature", "-m", "Merge feature")
        val graph = client.graphHistory(root, 2)
        assertEquals(2, graph.commits.size)
        assertTrue(graph.hasMore)
        assertFalse(graph.shallow)
        assertEquals(graph.head, graph.commits.first().commit.hash)
        assertEquals(dev.bettergit.core.graph.GraphChanges(1, 1L, 0L), graph.commits.first().changes)
        val all = client.graphHistory(root)
        assertFalse(all.hasMore)
        assertEquals(4, all.commits.size)
        assertEquals(dev.bettergit.core.graph.GraphChanges(1, 1L, 0L), all.commits.last().changes)
        assertEquals("BETTER-GIT-GRAPH", all.commits.last().commit.subject)
        assertEquals(all.commits.last().commit.hash, all.references.single { it.name == "release,one" }.hash)
        assertEquals(listOf("Feature", "BETTER-GIT-GRAPH"), client.graphHistory(root, branch = "refs/heads/feature").commits.map { it.commit.subject })
        assertThrows(IllegalArgumentException::class.java) { client.graphHistory(root, branch = "--all") }
    }

    @Test
    fun `graph excludes symbolic remote HEAD from branch counts`() {
        write("file.txt", "base\n")
        commit("Base")
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        val remotes = client.graphHistory(root).references.filter { it.kind == dev.bettergit.core.graph.GraphRefKind.REMOTE }
        assertEquals(listOf("origin/main"), remotes.map { it.name })
    }

    @Test
    fun `graph counts binary and renamed files without parsing paths`() {
        write("old.txt", "unchanged\n")
        commit("Base")
        git("mv", "old.txt", "new\tfile.txt")
        Files.write(root.resolve("image.bin"), byteArrayOf(0, 1, 2, 3))
        commit("Rename and binary")
        val graph = client.graphHistory(root)
        assertEquals(dev.bettergit.core.graph.GraphChanges(2, 0L, 0L), graph.commits.first().changes)
    }

    @Test
    fun `graph keeps author and committer time separate and excludes stash refs`() {
        write("file.txt", "base\n")
        git("add", ".")
        git("commit", "--date=2001-01-01T00:00:00Z", "-m", "Old authored commit")
        write("file.txt", "work in progress\n")
        git("stash", "push", "-m", "Hidden WIP")
        val graph = client.graphHistory(root)
        assertEquals(1, graph.commits.size)
        assertEquals(java.time.Instant.parse("2001-01-01T00:00:00Z"), graph.commits.single().commit.timestamp)
        assertTrue(graph.commits.single().committedAt > graph.commits.single().commit.timestamp)
        assertTrue(graph.references.none { it.fullName == "refs/stash" })
    }

    @Test
    fun `root commit content and path history support literal special characters`() {
        val specialPath = ":(glob) odd\nfile.txt"
        write(specialPath, "first line\nsecond line\n")
        commit("Initial subject")

        val history = client.log(root, specialPath)
        assertEquals(1, history.size)
        assertEquals("Initial subject", history.single().subject)
        assertEquals("Test Developer", history.single().author)
        assertTrue(history.single().parents.isEmpty())
        assertEquals(listOf(FileChange(specialPath, status = "A")), client.changes(root, history.single().hash))
        assertEquals("first line\nsecond line\n", client.content(root, "HEAD", specialPath))
        assertEquals(specialPath, client.blame(root, specialPath, 1)?.originalPath)
        assertEquals(history.single().hash, client.blame(root, specialPath, 1)?.commit?.hash)
    }

    @Test
    fun `rename changes retain the old path and history follows it`() {
        val oldPath = "original\nname.txt"
        val newPath = "new name\t.txt"
        write(oldPath, "stable content\n")
        commit("Initial file")
        git("mv", "--", oldPath, newPath)
        commit("Rename file")

        assertEquals(listOf(FileChange(newPath, oldPath, "R100")), client.changes(root, "HEAD"))
        assertEquals(listOf("Rename file", "Initial file"), client.log(root, newPath).map(GitCommit::subject))
    }

    @Test
    fun `status counts staged modified and nested untracked files separately`() {
        write("staged.txt", "original\n")
        write("modified.txt", "original\n")
        commit("Initial files")
        write("staged.txt", "staged\n")
        git("add", "--", "staged.txt")
        write("modified.txt", "modified\n")
        write("nested/one.txt", "one\n")
        write("nested/two.txt", "two\n")

        assertEquals(WorkingTreeStatus("main", 0, 0, 1, 1, 2), client.status(root))
        assertNull(client.blame(root, "nested/one.txt", 1))
        assertNull(client.blame(root, "modified.txt", 1)?.commit)
        assertNull(client.blame(root, "modified.txt", 99))
    }

    @Test
    fun `merge changes compare with the first parent`() {
        write("base.txt", "base\n")
        commit("Base")
        git("checkout", "-b", "feature")
        write("feature.txt", "feature\n")
        commit("Feature")
        git("checkout", "main")
        write("main.txt", "main\n")
        commit("Main")
        git("merge", "--no-ff", "feature", "-m", "Merge feature")

        assertEquals(2, client.log(root).first().parents.size)
        assertEquals(listOf(FileChange("feature.txt", status = "A")), client.changes(root, "HEAD"))
    }

    @Test
    fun `fetch exposes upstream state and branch checkout works`() {
        write("file.txt", "base\n")
        commit("Base")
        val remote = directory.resolve("remote.git")
        git("init", "--bare", "--initial-branch=main", remote.toString())
        git("remote", "add", "origin", remote.toString())
        git("push", "--set-upstream", "origin", "main")
        git("branch", "feature")
        write("file.txt", "local work\n")
        commit("Local work")
        client.fetch(root)

        assertEquals(listOf(GitRemote("origin", remote.toString())), client.remotes(root))
        assertEquals(1, client.status(root).ahead)
        val main = client.branches(root).single { it.name == "main" }
        assertTrue(main.current)
        assertFalse(main.remote)
        assertEquals("origin/main", main.upstream)
        assertEquals(1, main.ahead)
        assertTrue(client.branches(root).single { it.name == "origin/main" }.remote)

        client.checkout(root, "feature")
        assertEquals("feature", client.status(root).branch)
        assertEquals("base\n", Files.readString(root.resolve("file.txt")))
    }

    @Test
    fun `checkout rejects switches that would lose working tree changes`() {
        write("file.txt", "base\n")
        commit("Base")
        git("branch", "feature")
        write("file.txt", "main\n")
        commit("Main work")
        write("file.txt", "uncommitted\n")

        assertThrows(GitCommandException::class.java) { client.checkout(root, "feature") }
        assertEquals("main", client.status(root).branch)
        assertEquals("uncommitted\n", Files.readString(root.resolve("file.txt")))
    }

    @Test
    fun `revisions and paths cannot become command options or traverse outside the repository`() {
        write("file.txt", "base\n")
        commit("Base")
        assertThrows(IllegalArgumentException::class.java) { client.changes(root, "--help") }
        assertThrows(IllegalArgumentException::class.java) { client.content(root, "HEAD:other", "file.txt") }
        assertThrows(IllegalArgumentException::class.java) { client.content(root, "HEAD", "../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { client.checkout(root, "--detach") }
        assertThrows(GitCommandException::class.java) { client.checkout(root, "@{-1}") }
        assertEquals("main", client.status(root).branch)
    }

    @Test
    fun `interrupted callers cancel without clearing their interrupt flag`() {
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java) { client.status(root) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertNotNull(client.status(root))
    }

    @Test
    fun `large content is rejected with a bounded actionable error`() {
        Files.writeString(root.resolve("large.txt"), "a".repeat(16 * 1024 * 1024 + 1))
        commit("Large content")

        val error = assertThrows(GitCommandException::class.java) { client.content(root, "HEAD", "large.txt") }
        assertTrue(error.message.orEmpty().contains("16 MiB"))
    }

    @Test
    fun `stash restores staged and untracked work while keeping its saved entry`() {
        write("file.txt", "base\n")
        commit("Base")
        write("file.txt", "staged work\n")
        git("add", "--", "file.txt")
        write("untracked.txt", "new work\n")

        client.stash(root, "Before switching branches")
        val stash = client.stashes(root).single()
        assertEquals("stash@{0}", stash.ref)
        assertEquals(stash.hash, client.graphHistory(root, branch = stash.hash).commits.first().commit.hash)
        assertTrue(stash.subject.contains("Before switching branches"))
        assertEquals(0, client.status(root).staged)
        assertFalse(Files.exists(root.resolve("untracked.txt")))

        client.applyStash(root, stash.ref)
        assertEquals(1, client.status(root).staged)
        assertEquals("new work\n", Files.readString(root.resolve("untracked.txt")))
        assertEquals(1, client.stashes(root).size)
        assertThrows(IllegalArgumentException::class.java) { client.applyStash(root, "--help") }
    }

    @Test
    fun `worktree inventory and tags include names without ambiguity`() {
        write("file.txt", "base\n")
        commit("Base")
        client.createBranch(root, "release/v1")
        git("tag", "release/v1")
        val linked = directory.resolve("linked\nworktree")
        git("worktree", "add", linked.toString(), "release/v1")
        git("worktree", "lock", "--reason", "active task", linked.toString())

        assertEquals(listOf("release/v1"), client.tags(root))
        val worktrees = client.worktrees(root)
        assertEquals(2, worktrees.size)
        assertEquals("main", worktrees.single { it.path.toRealPath() == root.toRealPath() }.branch)
        assertTrue(worktrees.single { it.path.toRealPath() == linked.toRealPath() }.locked)
        assertEquals("main", client.status(root).branch)
    }

    @Test
    fun `branch comparisons and full commit metadata use resolved commits`() {
        write("file.txt", "base\n")
        commit("Base")
        val base = client.commit(root, "HEAD")
        client.createBranch(root, "feature")
        client.checkout(root, "feature")
        write("file.txt", "changed\n")
        commit("Feature work")
        val head = client.commit(root, "HEAD")

        assertEquals(listOf(base.hash), head.parents)
        assertEquals(listOf(FileChange("file.txt", status = "M")), client.compare(root, "main", "feature"))
        val patch = client.diff(root, "main", "feature")
        assertTrue(patch.contains("-base"))
        assertTrue(patch.contains("+changed"))
        client.checkout(root, "main")
        assertEquals(1, client.log(root).size)
        assertEquals(2, client.log(root, all = true).size)
    }

    @Test
    fun `file blame returns metadata for every line and respects its cap`() {
        write("file.txt", "first\nsecond\nthird\n")
        commit("Base")
        val base = client.commit(root, "HEAD")
        write("file.txt", "changed\nsecond\nthird\n")
        commit("Change first line")
        val changed = client.commit(root, "HEAD")
        write("file.txt", "changed\nsecond\nuncommitted\n")

        val lines = client.blameFile(root, "file.txt")
        assertEquals(listOf(1, 2, 3), lines.map(BlameLine::line))
        assertEquals(listOf(changed.hash, base.hash, null), lines.map { it.commit?.hash })
        assertEquals("Base", lines[1].commit?.subject)
        assertEquals("Test Developer", lines[1].commit?.author)
        assertEquals(2, client.blameFile(root, "file.txt", limit = 2).size)
        assertTrue(client.blameFile(root, "untracked.txt").isEmpty())
    }

    @Test
    fun `staged diff includes only staged content and summarizes binary data`() {
        write("file.txt", "base\n")
        commit("Base")
        write("file.txt", "staged\n")
        git("add", "--", "file.txt")
        write("file.txt", "unstaged secret\n")
        Files.write(root.resolve("binary.bin"), byteArrayOf(0, 1, 2, 3, 4))
        git("add", "--", "binary.bin")

        val patch = client.stagedDiff(root)
        assertTrue(patch.contains("+staged"))
        assertTrue(patch.contains("Binary files"))
        assertFalse(patch.contains("unstaged secret"))
        assertFalse(patch.contains('\u0000'))
    }

    @Test
    fun `hover shows the full root commit message and an addition hunk at the blamed line`() {
        val path = ":(glob) source\nfile.kt"
        write(path, "first\nsecond\nthird\n")
        val message = "feat: create source file\n\nExplain why this file exists.\n\nKeep this second paragraph."
        commit(message)
        val blame = requireNotNull(client.blame(root, path, 3))

        val details = requireNotNull(client.hoverDetails(root, blame, path))
        assertEquals("$message\n", details.message)
        assertTrue(details.commit.parents.isEmpty())
        assertEquals(FileChange(path, status = "A"), details.change)
        assertEquals(path, details.originalPath)
        assertEquals(3, details.originalLine)
        val preview = requireNotNull(details.preview)
        assertEquals("third", preview.lines.single { it.newLine == 3 }.text)
        assertTrue(preview.lines.all { it.kind == DiffLineKind.ADDED && it.oldLine == null })
        assertFalse(preview.truncated)
    }

    @Test
    fun `hover selects the historical hunk after later edits shift editor line numbers`() {
        val original = (1..100).map { if (it == 9) "" else "original line $it" }
        write("file.txt", original.joinToString("\n", postfix = "\n"))
        commit("Base")
        val changed = original.toMutableList().apply {
            this[9] = "target change"
            this[79] = "distant change"
        }
        write("file.txt", changed.joinToString("\n", postfix = "\n"))
        commit("fix: update two separate regions\n\nThe target belongs to the first region.")
        val targetCommit = client.commit(root, "HEAD")
        val prefix = (1..8).map { "later inserted header $it" }
        write("file.txt", (prefix + changed).joinToString("\n", postfix = "\n"))
        commit("Add a header later")
        git("config", "diff.suppressBlankEmpty", "true")

        val blame = requireNotNull(client.blame(root, "file.txt", 18))
        assertEquals(targetCommit.hash, blame.commit?.hash)
        assertEquals(18, blame.line)
        assertEquals(10, blame.originalLine)
        val details = requireNotNull(client.hoverDetails(root, blame, "file.txt"))
        val lines = requireNotNull(details.preview).lines

        assertEquals(targetCommit.hash, details.commit.hash)
        assertEquals("target change", lines.single { it.kind == DiffLineKind.ADDED && it.newLine == 10 }.text)
        assertEquals("original line 10", lines.single { it.kind == DiffLineKind.REMOVED && it.oldLine == 10 }.text)
        assertTrue(lines.any { it.kind == DiffLineKind.CONTEXT && it.text.isEmpty() && it.newLine == 9 })
        assertFalse(lines.any { it.text.contains("distant change") || it.text.contains("later inserted header") })
    }

    @Test
    fun `hover follows the historical rename and keeps both sides of its modification`() {
        val oldPath = "source/old\nfile.kt"
        val renamedPath = "source/new file.kt"
        val currentPath = "source/current.kt"
        val original = (1..30).map { "original line $it" }
        write(oldPath, original.joinToString("\n", postfix = "\n"))
        commit("Base")
        git("mv", "--", oldPath, renamedPath)
        write(renamedPath, original.toMutableList().apply { this[14] = "changed during rename" }.joinToString("\n", postfix = "\n"))
        commit("refactor: rename and improve file")
        val renameCommit = client.commit(root, "HEAD")
        git("mv", "--", renamedPath, currentPath)
        commit("Rename the file again")

        val blame = requireNotNull(client.blame(root, currentPath, 15))
        assertEquals(renameCommit.hash, blame.commit?.hash)
        assertEquals(renamedPath, blame.originalPath)
        val details = requireNotNull(client.hoverDetails(root, blame, currentPath))
        val change = requireNotNull(details.change)
        assertEquals(renamedPath, change.path)
        assertEquals(oldPath, change.oldPath)
        assertTrue(change.status.startsWith("R"))
        val lines = requireNotNull(details.preview).lines
        assertEquals("original line 15", lines.single { it.kind == DiffLineKind.REMOVED }.text)
        assertEquals("changed during rename", lines.single { it.kind == DiffLineKind.ADDED }.text)
        assertEquals(15, lines.single { it.kind == DiffLineKind.ADDED }.newLine)
    }

    @Test
    fun `hover compares a merge conflict resolution with its first parent`() {
        write("file.txt", "first\nbase value\nlast\n")
        commit("Base")
        git("checkout", "-b", "feature")
        write("file.txt", "first\nfeature value\nlast\n")
        commit("Feature change")
        git("checkout", "main")
        write("file.txt", "first\nmain value\nlast\n")
        commit("Main change")
        val firstParent = client.commit(root, "HEAD")
        assertThrows(IllegalStateException::class.java) { git("merge", "feature", "-m", "Merge feature") }
        write("file.txt", "first\nresolved value\nlast\n")
        commit("fix: resolve both branch changes\n\nUse a combined implementation.")

        val blame = requireNotNull(client.blame(root, "file.txt", 2))
        val details = requireNotNull(client.hoverDetails(root, blame, "file.txt"))
        assertEquals(2, details.commit.parents.size)
        assertEquals(firstParent.hash, details.commit.parents.first())
        val lines = requireNotNull(details.preview).lines
        assertEquals("main value", lines.single { it.kind == DiffLineKind.REMOVED }.text)
        assertEquals("resolved value", lines.single { it.kind == DiffLineKind.ADDED }.text)
        assertFalse(lines.any { it.text == "feature value" })
    }

    @Test
    fun `hover keeps the blamed line visible when a root addition exceeds the preview limit`() {
        write("file.txt", (1..100).joinToString("\n", postfix = "\n") { "line $it" })
        commit("feat: add a larger file")
        val blame = requireNotNull(client.blame(root, "file.txt", 90))

        val details = requireNotNull(client.hoverDetails(root, blame, "file.txt"))
        val preview = requireNotNull(details.preview)
        assertTrue(preview.truncated)
        assertEquals(18, preview.lines.size)
        assertEquals("line 90", preview.lines.single { it.newLine == 90 }.text)
    }

    @Test
    fun `hover omits an unrelated hunk when the provided line was unchanged in that commit`() {
        val original = (1..50).map { "line $it" }
        write("file.txt", original.joinToString("\n", postfix = "\n"))
        commit("Base")
        write("file.txt", original.toMutableList().apply { this[1] = "new second line" }.joinToString("\n", postfix = "\n"))
        commit("fix: change the beginning only")
        val commit = client.commit(root, "HEAD")

        val details = requireNotNull(client.hoverDetails(root, BlameLine(45, commit, "file.txt"), "file.txt"))
        assertNotNull(details.change)
        assertNull(details.preview)
    }

    @Test
    fun `hover identifies binary files and ignores uncommitted blame without invoking Git`() {
        Files.write(root.resolve("binary.bin"), byteArrayOf(0, 1, 2, 3))
        commit("feat: add binary data")
        val commit = client.commit(root, "HEAD")

        val details = requireNotNull(client.hoverDetails(root, BlameLine(1, commit), "binary.bin"))
        assertEquals("binary.bin", details.originalPath)
        assertTrue(requireNotNull(details.preview).binary)
        assertNull(client.hoverDetails(directory.resolve("missing-repository"), BlameLine(1, null), "file.txt"))
    }

    @Test
    fun `inspection preserves literal filenames full messages and root line counts`() {
        val name = ":(glob) odd\tfile\nname.txt"
        write(name, "one\ntwo\n")
        commit("feat: initial files\n\nA detailed explanation.")
        val inspection = client.inspectCommit(root, "HEAD")
        assertEquals("feat: initial files\n\nA detailed explanation.", inspection.message)
        assertEquals(FileLineStats(2, 0), inspection.stats[name])
        val preview = client.commitFilePreview(root, inspection.commit.hash, inspection.changes.single())
        assertEquals(0, preview.before.bytes!!.size)
        assertEquals("one\ntwo\n", preview.after.bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `inspection previews renames modifications and deletions against their historical paths`() {
        write("old\tname.txt", "one\ntwo\nthree\n")
        write("removed.txt", "removed\n")
        write("modified.txt", "before\n")
        commit("Initial")
        git("mv", "--", "old\tname.txt", "new\nname.txt")
        write("modified.txt", "after\nextra\n")
        Files.delete(root.resolve("removed.txt"))
        commit("Update")
        val inspection = client.inspectCommit(root, "HEAD")
        assertEquals(FileLineStats(0, 0), inspection.stats["new\nname.txt"])
        assertEquals(FileLineStats(2, 1), inspection.stats["modified.txt"])
        assertEquals(FileLineStats(0, 1), inspection.stats["removed.txt"])
        val rename = client.commitFilePreview(root, "HEAD", inspection.changes.single { it.oldPath != null })
        org.junit.jupiter.api.Assertions.assertArrayEquals(rename.before.bytes, rename.after.bytes)
        val deleted = client.commitFilePreview(root, "HEAD", inspection.changes.single { it.status == "D" })
        assertEquals("removed\n", deleted.before.bytes!!.toString(Charsets.UTF_8))
        assertNull(deleted.after.objectId)
    }

    @Test
    fun `binary previews preserve all raw bytes without UTF8 replacement or external drivers`() {
        val original = ByteArray(512) { it.toByte() }
        Files.write(root.resolve("data.bin"), original)
        write(".gitattributes", "*.bin diff=hostile\n")
        git("config", "diff.hostile.textconv", "false")
        commit("Binary")
        val inspection = client.inspectCommit(root, "HEAD")
        assertTrue(inspection.stats.getValue("data.bin").binary)
        val preview = client.commitFilePreview(root, "HEAD", inspection.changes.single { it.path == "data.bin" })
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, preview.after.bytes)
        assertEquals(512, preview.after.size)
        assertEquals(BlobPreviewState.AVAILABLE, preview.after.state)
    }

    @Test
    fun `large preview retains metadata without loading the blob`() {
        Files.write(root.resolve("large.bin"), ByteArray(2 * 1024 * 1024 + 1) { 1 })
        commit("Large binary")
        val preview = client.commitFilePreview(root, "HEAD", client.changes(root, "HEAD").single())
        assertEquals(BlobPreviewState.TOO_LARGE, preview.after.state)
        assertNull(preview.after.bytes)
        assertEquals(2 * 1024 * 1024 + 1L, preview.after.size)
    }

    @Test
    fun `symlink preview reads link text rather than its target and gitlinks show object references`() {
        write("secret.txt", "target must never be read via the link\n")
        commit("Base")
        val base = client.commit(root, "HEAD").hash
        Files.createSymbolicLink(root.resolve("link.txt"), Path.of("secret.txt"))
        git("add", "--all")
        git("update-index", "--add", "--cacheinfo", "160000,$base,vendor/module")
        git("commit", "-m", "Link and submodule")
        val changes = client.changes(root, "HEAD")
        val link = client.commitFilePreview(root, "HEAD", changes.single { it.path == "link.txt" })
        assertEquals("120000", link.after.mode)
        assertEquals("secret.txt", link.after.bytes!!.toString(Charsets.UTF_8))
        val submodule = client.commitFilePreview(root, "HEAD", changes.single { it.path == "vendor/module" })
        assertEquals(BlobPreviewState.SUBMODULE, submodule.after.state)
        assertEquals(base, submodule.after.objectId)
        assertNull(submodule.after.bytes)
    }

    @Test
    fun `preview validates file membership and remains independent of working changes`() {
        write("file.txt", "committed\n")
        commit("Base")
        write("file.txt", "unsaved worktree content\n")
        val change = client.changes(root, "HEAD").single()
        val preview = client.commitFilePreview(root, "HEAD", change)
        assertEquals("committed\n", preview.after.bytes!!.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            client.commitFilePreview(root, "HEAD", change.copy(path = "../outside.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.commitFilePreview(root, "HEAD", change.copy(status = "M"))
        }
    }

    @Test
    fun `merge inspector line counts and contents use the first parent`() {
        write("base.txt", "base\n")
        commit("Base")
        git("checkout", "-b", "feature")
        write("feature.txt", "feature\n")
        commit("Feature")
        git("checkout", "main")
        write("main.txt", "main\n")
        commit("Main")
        git("merge", "--no-ff", "feature", "-m", "Merge feature")
        val inspection = client.inspectCommit(root, "HEAD")
        assertEquals(2, inspection.commit.parents.size)
        assertEquals(listOf(FileChange("feature.txt", status = "A")), inspection.changes)
        assertEquals(mapOf("feature.txt" to FileLineStats(1, 0)), inspection.stats)
        val preview = client.commitFilePreview(root, inspection.commit.hash, inspection.changes.single())
        assertNull(preview.before.objectId)
        assertEquals("feature\n", preview.after.bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `encoded content and exact preview size limit retain original bytes`() {
        val encoded = "Unicode content \u03A9\r\n".toByteArray(Charsets.UTF_16)
        Files.write(root.resolve("encoded.txt"), encoded)
        val boundary = ByteArray(2 * 1024 * 1024) { 1 }
        Files.write(root.resolve("boundary.bin"), boundary)
        commit("Encoded files")
        val changes = client.changes(root, "HEAD")
        org.junit.jupiter.api.Assertions.assertArrayEquals(encoded,
            client.commitFilePreview(root, "HEAD", changes.single { it.path == "encoded.txt" }).after.bytes)
        val preview = client.commitFilePreview(root, "HEAD", changes.single { it.path == "boundary.bin" })
        assertEquals(BlobPreviewState.AVAILABLE, preview.after.state)
        org.junit.jupiter.api.Assertions.assertArrayEquals(boundary, preview.after.bytes)
    }

    private fun write(path: String, content: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun commit(subject: String) {
        git("add", "--all")
        git("commit", "--message", subject)
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Git fixture command timed out" }
        check(process.exitValue() == 0) { "Git fixture failed: $output" }
        return output.trimEnd()
    }
}
