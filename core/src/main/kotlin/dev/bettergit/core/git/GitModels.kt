package dev.bettergit.core.git

import java.time.Instant
import java.nio.file.Path

data class GitCommit(
    val hash: String,
    val author: String,
    val email: String,
    val timestamp: Instant,
    val subject: String,
    val parents: List<String> = emptyList(),
    val decorations: String = "",
)

data class GitBranch(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
    val upstream: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
)

data class WorkingTreeStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: Int,
    val modified: Int,
    val untracked: Int,
)

data class GitRemote(val name: String, val url: String)

data class FileChange(val path: String, val oldPath: String? = null, val status: String)

data class GitStash(val ref: String, val subject: String, val hash: String)

data class GitWorktree(val path: Path, val branch: String, val head: String, val locked: Boolean)

/** Line numbers are one-based; originalLine refers to the historical commit and path. */
data class BlameLine(
    val line: Int,
    val commit: GitCommit?,
    val originalPath: String = "",
    val originalLine: Int = line,
)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED, INFO }

data class DiffPreviewLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class DiffHunkPreview(
    val header: String,
    val lines: List<DiffPreviewLine>,
    val truncated: Boolean = false,
    val binary: Boolean = false,
)

data class CommitHoverDetails(
    val commit: GitCommit,
    val message: String,
    val change: FileChange?,
    val originalPath: String,
    val originalLine: Int,
    val preview: DiffHunkPreview?,
)

class GitCommandException(
    val operation: String,
    val exitCode: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
