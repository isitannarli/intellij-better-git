package dev.bettergit.core.git

data class FileLineStats(val added: Long?, val removed: Long?) {
    val binary: Boolean get() = added == null || removed == null
}

data class CommitInspection(
    val commit: GitCommit,
    val message: String,
    val changes: List<FileChange>,
    val stats: Map<String, FileLineStats>,
)

enum class BlobPreviewState { AVAILABLE, TOO_LARGE, SUBMODULE }

/** Null bytes mean unavailable content; an absent side is an empty blob. */
class GitBlob(
    val objectId: String?,
    val mode: String?,
    val size: Long,
    val bytes: ByteArray?,
    val state: BlobPreviewState,
) {
    override fun toString() = "GitBlob($objectId, $size bytes, $state)"
    companion object {
        fun absent() = GitBlob(null, null, 0, byteArrayOf(), BlobPreviewState.AVAILABLE)
    }
}

data class CommitFilePreview(
    val commit: GitCommit,
    val change: FileChange,
    val before: GitBlob,
    val after: GitBlob,
)
