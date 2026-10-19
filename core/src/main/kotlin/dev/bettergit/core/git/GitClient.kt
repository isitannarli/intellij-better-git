package dev.bettergit.core.git

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import dev.bettergit.core.reports.InsightsHistory
import dev.bettergit.core.graph.GraphHistory
import dev.bettergit.core.graph.GraphHistoryParser

/** Blocking and interruptible; call from a background task. */
class GitClient(executable: String = "git") {
    private val runner = GitProcessRunner(executable)

    fun root(directory: Path): Path? {
        if (!Files.isDirectory(directory)) return null
        val output = runner.run(directory, listOf("rev-parse", "--show-toplevel"), setOf(0, 128))
        return if (output.exitCode == 0) Path.of(output.stdout.removeSuffix("\n")) else null
    }

    fun status(root: Path): WorkingTreeStatus = GitParsers.status(
        runner.run(root, listOf("status", "--porcelain=v2", "--branch", "--untracked-files=all", "-z")).stdout,
    )

    fun workingChanges(root: Path): List<WorkingChange> = WorkingChangesParser.parse(
        runner.run(root, listOf("status", "--porcelain=v2", "--untracked-files=all", "-z")).stdout,
    )

    fun workingFileDiff(root: Path, change: WorkingChange, staged: Boolean): WorkingFileDiff {
        validatePath(change.path)
        change.oldPath?.let(::validatePath)
        require(!change.conflicted && !change.submodule) { "Use the IDE's Changes view to inspect conflicts or submodules." }
        require(if (staged) change.staged else change.unstaged) { "This file has no changes in the selected area. Refresh the file list." }
        val before = if (staged) {
            if (change.indexStatus == 'A') "" else content(root, "HEAD", change.oldPath ?: change.path)
        } else {
            if (change.untracked) "" else runner.run(root, listOf("cat-file", "blob", ":${change.path}")).stdout
        }
        val after = if (staged) {
            if (change.indexStatus == 'D') "" else runner.run(root, listOf("cat-file", "blob", ":${change.path}")).stdout
        } else {
            if (change.workTreeStatus == 'D') "" else readWorkingFile(root, change.path)
        }
        require('\u0000' !in before && '\u0000' !in after) { "Binary file: use the IDE's Changes view to inspect this change." }
        return WorkingFileDiff(change.path, before, after, if (staged) "HEAD" else "Index", if (staged) "Index" else "Working tree (saved)")
    }

    private fun readWorkingFile(root: Path, path: String): String {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val file = normalizedRoot.resolve(path).normalize()
        require(file.startsWith(normalizedRoot)) { "Path is outside the repository" }
        // Read symlink content, never its target outside the repository.
        if (Files.isSymbolicLink(file)) return Files.readSymbolicLink(file).toString()
        require(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Only regular files can be previewed." }
        require(file.toRealPath().startsWith(normalizedRoot.toRealPath())) { "Path resolves outside the repository" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(2_000_001) }
        require(bytes.size <= 2_000_000) { "File exceeds the 2 MB preview limit. Open it in the IDE's Changes view." }
        return bytes.toString(Charsets.UTF_8)
    }

    fun branches(root: Path): List<GitBranch> = GitParsers.branches(
        runner.run(root, listOf(
            "for-each-ref", "--sort=-committerdate",
            "--format=%(refname)%00%(HEAD)%00%(upstream:short)%00%(upstream:track)%00%(symref)%00",
            "refs/heads/", "refs/remotes/",
        )).stdout,
    )

    fun remotes(root: Path): List<GitRemote> = GitParsers.remotes(
        runner.run(root, listOf("config", "--null", "--get-regexp", "^remote\\..*\\.url$"), setOf(0, 1)).stdout,
    )

    fun log(root: Path, path: String? = null, limit: Int = 150, all: Boolean = false): List<GitCommit> {
        require(limit in 1..10_000) { "History limit must be between 1 and 10000" }
        path?.let(::validatePath)
        if (!all && !hasHead(root)) return emptyList()
        val arguments = mutableListOf(
            "log", "--date-order", "--max-count=$limit", "--decorate=short", "-z",
            COMMIT_FORMAT,
        )
        if (all) arguments += "--all"
        if (path != null) arguments += "--follow"
        arguments += "--"
        if (path != null) arguments += path
        return GitParsers.log(runner.run(root, arguments).stdout)
    }

    fun graphHistory(root: Path, limit: Int = 500, branch: String? = null): GraphHistory {
        require(limit in 1..5000) { "Graph history limit must be between 1 and 5000" }
        val head = runner.run(root, listOf("rev-parse", "--verify", "--quiet", "HEAD"), setOf(0, 1)).stdout.trim().ifEmpty { null }
        val references = GraphHistoryParser.references(runner.run(root, listOf(
            "for-each-ref", "--format=%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00%(refname)%00%(symref)",
            "refs/heads/", "refs/remotes/", "refs/tags/",
        )).stdout)
        val shallow = runner.run(root, listOf("rev-parse", "--is-shallow-repository")).stdout.trim() == "true"
        if (head == null && references.isEmpty()) return GraphHistory(emptyList(), references, null, false, shallow)
        val arguments = mutableListOf("log", "--topo-order", "--max-count=${limit + 1}", "-z", GraphHistoryParser.FORMAT,
            "--shortstat", "--root", "--diff-merges=first-parent", "--find-renames", "--no-ext-diff", "--no-textconv",
            "--no-notes", "--no-show-signature")
        if (branch != null) arguments += resolveCommit(root, branch) else {
            arguments += listOf("--branches", "--remotes", "--tags")
            head?.let { arguments += it }
        }
        arguments += "--"
        val entries = GraphHistoryParser.parse(runner.run(root, arguments, timeout = Duration.ofMinutes(2)).stdout)
        return GraphHistory(entries.take(limit), references, head, entries.size > limit, shallow)
    }

    /** --since-as-filter must traverse old-dated descendants to reach newer ancestors. */
    fun insightsHistory(
        root: Path,
        clock: Clock = Clock.systemUTC(),
        zone: ZoneId = ZoneId.systemDefault(),
        maxCommits: Int = 50_000,
    ): InsightsHistory {
        require(maxCommits in 1..50_000) { "Insights history limit must be between 1 and 50000" }
        val capturedAt = clock.instant()
        val today = LocalDate.ofInstant(capturedAt, zone)
        val start = today.minusDays(729)
        val shallow = runner.run(root, listOf("rev-parse", "--is-shallow-repository")).stdout.trim() == "true"
        val arguments = mutableListOf(
            "log", "--branches", "--remotes", "--tags", "--date-order", "--max-count=${maxCommits + 1}",
            "--since-as-filter=${start.atStartOfDay(zone).toInstant()}", "--until=$capturedAt",
            "-z", "--format=%H%x00%aN%x00%aE%x00%at%x00%s%x00%P%x00%D%x00%ct",
        )
        if (hasHead(root)) arguments += "HEAD"
        arguments += "--"
        val output = runner.run(root, arguments, timeout = Duration.ofMinutes(2)).stdout
        return InsightsHistory.parse(output, start, today, capturedAt, zone, maxCommits, shallow)
    }

    fun commit(root: Path, revision: String): GitCommit {
        val resolved = resolveCommit(root, revision)
        return GitParsers.log(runner.run(root, listOf(
            "log", "--max-count=1", "--decorate=short", "-z", COMMIT_FORMAT, resolved, "--",
        )).stdout).single()
    }

    fun hoverDetails(root: Path, blame: BlameLine, currentPath: String): CommitHoverDetails? {
        val blamedCommit = blame.commit ?: return null
        val path = blame.originalPath.ifEmpty { currentPath }
        validatePath(path)
        require(blame.originalLine > 0) { "The original blame line must be one based" }
        val resolved = commit(root, blamedCommit.hash)
        val message = runner.run(root, listOf(
            "log", "--max-count=1", "-z", "--format=%B", resolved.hash, "--",
        )).stdout.removeSuffix("\u0000")
        val change = changedFiles(root, resolved.hash, resolved.parents.firstOrNull()).firstOrNull { it.path == path }
        val preview = if (change == null) null else {
            val arguments = mutableListOf(
                "diff-tree", "--root", "--no-commit-id", "-r", "--patch", "--unified=3",
                "--find-renames", "--no-ext-diff", "--no-textconv", "--no-color",
            )
            resolved.parents.firstOrNull()?.let { arguments += it }
            arguments += resolved.hash
            arguments += "--"
            change.oldPath?.let { arguments += it }
            arguments += change.path
            DiffPreviewParser.preview(runner.run(root, arguments).stdout, blame.originalLine)
        }
        return CommitHoverDetails(resolved, message, change, path, blame.originalLine, preview)
    }

    /** Compare merges with their first parent and root commits with the empty tree. */
    fun changes(root: Path, hash: String): List<FileChange> {
        val commit = resolveCommit(root, hash)
        val ancestry = runner.run(root, listOf("rev-list", "--parents", "--max-count=1", commit, "--")).stdout.trim().split(' ')
        return changedFiles(root, commit, ancestry.getOrNull(1))
    }

    private fun changedFiles(root: Path, hash: String, firstParent: String?): List<FileChange> {
        val arguments = mutableListOf(
            "diff-tree", "--root", "--no-commit-id", "-r", "--name-status", "-z", "--find-renames",
            "--no-ext-diff", "--no-textconv",
        )
        firstParent?.let { arguments += it }
        arguments += hash
        arguments += "--"
        return GitParsers.changes(runner.run(root, arguments).stdout)
    }

    fun inspectCommit(root: Path, revision: String): CommitInspection {
        val resolved = commit(root, revision)
        val changes = changedFiles(root, resolved.hash, resolved.parents.firstOrNull())
        val arguments = mutableListOf(
            "diff-tree", "--root", "--no-commit-id", "-r", "--numstat", "-z", "--find-renames",
            "--no-ext-diff", "--no-textconv",
        )
        resolved.parents.firstOrNull()?.let { arguments += it }
        arguments += listOf(resolved.hash, "--")
        val tokens = runner.run(root, arguments).stdout.split('\u0000')
        val stats = mutableMapOf<String, FileLineStats>()
        var index = 0
        while (index < tokens.size) {
            val entry = tokens[index++]
            if (entry.isEmpty()) continue
            val fields = entry.split('\t', limit = 3)
            require(fields.size == 3) { "Invalid Git line statistics" }
            val path = if (fields[2].isEmpty()) {
                require(index + 1 < tokens.size) { "Incomplete rename statistics" }
                index++ 
                tokens[index++]
            } else fields[2]
            stats[path] = FileLineStats(fields[0].toLongOrNull(), fields[1].toLongOrNull())
        }
        val message = runner.run(root, listOf("log", "-1", "-z", "--format=%B", resolved.hash, "--"))
            .stdout.removeSuffix("\u0000").trimEnd()
        return CommitInspection(resolved, message, changes, stats)
    }

    fun commitFilePreview(root: Path, revision: String, change: FileChange): CommitFilePreview {
        val resolved = commit(root, revision)
        require(change in changedFiles(root, resolved.hash, resolved.parents.firstOrNull())) {
            "The selected file is not part of this commit"
        }
        val before = if (resolved.parents.isEmpty() || change.status.startsWith("A")) GitBlob.absent()
            else previewBlob(root, resolved.parents.first(), change.oldPath ?: change.path)
        val after = if (change.status.startsWith("D")) GitBlob.absent()
            else previewBlob(root, resolved.hash, change.path)
        return CommitFilePreview(resolved, change, before, after)
    }

    private fun previewBlob(root: Path, commit: String, path: String): GitBlob {
        validatePath(path)
        val entry = runner.run(root, listOf("ls-tree", "-z", commit, "--", path)).stdout
            .split('\u0000').firstOrNull { it.substringAfter('\t', "") == path }
            ?: error("The file does not exist in this revision")
        val metadata = entry.substringBefore('\t').split(' ')
        val mode = metadata[0]
        val type = metadata[1]
        val objectId = metadata[2]
        if (type == "commit") return GitBlob(objectId, mode, 0, null, BlobPreviewState.SUBMODULE)
        require(type == "blob") { "Only file objects can be previewed" }
        val size = runner.run(root, listOf("cat-file", "-s", objectId)).stdout.trim().toLong()
        if (size > MAX_PREVIEW_BYTES) return GitBlob(objectId, mode, size, null, BlobPreviewState.TOO_LARGE)
        val bytes = runner.run(root, listOf("cat-file", "blob", objectId)).stdoutBytes
        return GitBlob(objectId, mode, size, bytes, BlobPreviewState.AVAILABLE)
    }

    fun content(root: Path, revision: String, path: String): String {
        validatePath(path)
        val commit = resolveCommit(root, revision)
        return runner.run(root, listOf("cat-file", "blob", "$commit:$path")).stdout
    }

    fun compare(root: Path, base: String, head: String): List<FileChange> {
        val baseCommit = resolveCommit(root, base)
        val headCommit = resolveCommit(root, head)
        return GitParsers.changes(runner.run(root, listOf(
            "diff-tree", "-r", "--no-commit-id", "--name-status", "-z", "--find-renames", "--no-ext-diff", "--no-textconv",
            baseCommit, headCommit, "--",
        )).stdout)
    }

    fun diff(root: Path, base: String, head: String): String {
        val baseCommit = resolveCommit(root, base)
        val headCommit = resolveCommit(root, head)
        return runner.run(root, listOf(
            "diff", "--no-ext-diff", "--no-textconv", "--no-color", "--find-renames", baseCommit, headCommit, "--",
        )).stdout
    }

    fun stagedDiff(root: Path): String = runner.run(root, listOf(
        "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-color", "--find-renames", "--",
    )).stdout

    fun stashes(root: Path): List<GitStash> = GitParsers.stashes(
        runner.run(root, listOf("stash", "list", "-z", "--format=%gd%x00%s%x00%H")).stdout,
    )

    fun worktrees(root: Path): List<GitWorktree> = GitParsers.worktrees(
        runner.run(root, listOf("worktree", "list", "--porcelain", "-z")).stdout,
    )

    fun tags(root: Path): List<String> = runner.run(root, listOf(
        "for-each-ref", "--sort=-creatordate", "--format=%(refname:strip=2)", "refs/tags/",
    )).stdout.lineSequence().filter(String::isNotEmpty).toList()

    fun stash(root: Path, message: String) {
        require(message.isNotBlank() && message.length <= 4096 && '\u0000' !in message) { "A stash message of 1–4096 characters is required" }
        // Stash uses a glob to clean untracked files; global literal pathspec mode breaks it.
        runner.run(root, listOf("stash", "push", "--include-untracked", "--message", message, "--"), literalPathspecs = false)
    }

    fun applyStash(root: Path, ref: String) {
        require(Regex("stash@\\{[0-9]+}").matches(ref)) { "Invalid stash reference" }
        runner.run(root, listOf("stash", "apply", "--index", ref))
    }

    fun createBranch(root: Path, name: String) {
        validateBranch(root, name)
        runner.run(root, listOf("branch", "--", name))
    }

    fun selectionHistory(
        root: Path,
        path: String,
        range: LineRange,
        currentText: String,
        limit: Int = 200,
    ): SelectionHistory {
        validatePath(path)
        require(limit in 1..200) { "Selection history limit must be between 1 and 200" }
        val head = if (hasHead(root)) resolveCommit(root, "HEAD") else null
        return SelectionHistoryReader(runner).read(root, path, range, currentText, limit, head) {
            workingChanges(root).firstOrNull { it.path == path }?.oldPath
        }
    }

    fun blame(root: Path, path: String, line: Int): BlameLine? {
        validatePath(path)
        require(line > 0) { "Blame line must be one based" }
        if (!hasHead(root)) return null
        if (runner.run(root, listOf("ls-files", "--error-unmatch", "--", path), setOf(0, 1)).exitCode != 0) return null
        val output = runner.run(root, listOf("blame", "--line-porcelain", "-L", "$line,$line", "--", path), setOf(0, 128))
        if (output.exitCode != 0) {
            if (output.stderr.contains("has only") && output.stderr.contains("line")) return null
            throw GitCommandException("blame", output.exitCode, "Git blame failed: ${redactGitError(output.stderr)}")
        }
        return GitParsers.blame(output.stdout)
    }

    fun blameFile(root: Path, path: String, limit: Int = 20_000): List<BlameLine> {
        validatePath(path)
        require(limit in 1..20_000) { "Blame limit must be between 1 and 20000 lines" }
        if (!hasHead(root)) return emptyList()
        if (runner.run(root, listOf("ls-files", "--error-unmatch", "--", path), setOf(0, 1)).exitCode != 0) return emptyList()
        val output = runner.run(root, listOf("blame", "--line-porcelain", "-L", "1,$limit", "--", path), setOf(0, 128))
        if (output.exitCode != 0) {
            if (output.stderr.contains("has only") && output.stderr.contains("line")) return emptyList()
            throw GitCommandException("blame", output.exitCode, "Git blame failed: ${redactGitError(output.stderr)}")
        }
        return GitParsers.blameFile(output.stdout)
    }

    fun fetch(root: Path) {
        runner.run(root, listOf("fetch", "--all", "--prune", "--no-recurse-submodules"), timeout = Duration.ofMinutes(2))
    }

    fun checkout(root: Path, branch: String) {
        validateBranch(root, branch)
        // Disable DWIM remote branch creation and '-' / '@{-1}' shortcuts.
        runner.run(root, listOf("show-ref", "--verify", "--quiet", "refs/heads/$branch"))
        runner.run(root, listOf("checkout", branch, "--"))
    }

    private fun hasHead(root: Path): Boolean =
        runner.run(root, listOf("rev-parse", "--verify", "--quiet", "HEAD"), setOf(0, 1)).exitCode == 0

    private fun validateBranch(root: Path, branch: String) {
        require(branch.isNotEmpty() && !branch.startsWith('-') && branch.none(Char::isISOControl)) { "Invalid branch name" }
        runner.run(root, listOf("check-ref-format", "refs/heads/$branch"))
    }

    private fun resolveCommit(root: Path, revision: String): String {
        require(
            revision.isNotEmpty() && !revision.startsWith('-') &&
                revision.none { it.isWhitespace() || it.isISOControl() || it == ':' || it == '\\' } &&
                !revision.contains("..") && !revision.contains("@{"),
        ) { "Invalid single-commit revision" }
        return runner.run(root, listOf("rev-parse", "--verify", "--end-of-options", "$revision^{commit}")).stdout.trim()
    }

    private fun validatePath(path: String) {
        require(
            path.isNotEmpty() && '\u0000' !in path && !path.startsWith('/') &&
                !Regex("^[A-Za-z]:").containsMatchIn(path) && path.split('/').none { it == ".." },
        ) { "Git paths must be relative to the repository root" }
    }

    private companion object {
        const val COMMIT_FORMAT = "--format=%H%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
    }
}

private const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024
