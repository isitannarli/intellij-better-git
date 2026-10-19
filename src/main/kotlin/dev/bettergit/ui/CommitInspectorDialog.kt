package dev.bettergit.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import dev.bettergit.core.git.BlobPreviewState
import dev.bettergit.core.git.CommitFilePreview
import dev.bettergit.core.git.FileChange
import dev.bettergit.core.git.GitBlob
import dev.bettergit.core.git.GitCommit
import dev.bettergit.services.WorkbenchService
import kotlinx.coroutines.Job
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JComponent

internal class CommitInspectorDialog(
    private val project: Project,
    private val root: Path,
    commit: GitCommit,
    private val initialPath: String? = null,
) : DialogWrapper(project, false) {
    private val service = WorkbenchService.getInstance(project)
    private var resolved = commit
    private var metadataJob: Job? = null
    private var previewJob: Job? = null
    private var generation = 0L
    private var closed = false
    private var currentRequest: DiffRequest? = null
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null).apply {
        putContextHints(DiffUserDataKeys.DO_NOT_CHANGE_WINDOW_TITLE, true)
        putContextHints(DiffUserDataKeys.FORCE_READ_ONLY, true)
    }
    private val panel = CommitInspectorPanel(diff.component, object : CommitInspectorPanel.Listener {
        override fun select(change: FileChange?) = loadPreview(change)
        override fun openDiff() { currentRequest?.let { DiffManager.getInstance().showDiff(project, it) } }
        override fun openCommit() = CommitHostingActions.openCommit(project, root, resolved.hash)
        override fun openReviews() = CommitHostingActions.openReviews(project, root, resolved.hash)
        override fun copyHash() = CopyPasteManager.getInstance().setContents(StringSelection(resolved.hash))
    })

    init {
        title = "Better Git · Commit ${commit.hash.take(8)}"
        setModal(false)
        setOKButtonText("Close")
        init()
        loadMetadata()
    }

    override fun createCenterPanel(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
    override fun getDimensionServiceKey() = "BetterGit.CommitInspector"
    override fun createActions() = arrayOf(okAction)

    private fun loadMetadata() {
        metadataJob = service.submit("Read commit", onSuccess = { inspection ->
            if (closed) return@submit
            resolved = inspection.commit
            panel.setData(inspection, root.fileName?.toString() ?: root.toString(), initialPath)
        }, onFailure = { failure ->
            if (!closed) panel.showMessage("Cannot read this commit", failure.message ?: "Close the inspector and try again.")
        }) { service.git.inspectCommit(root, resolved.hash) }
    }

    private fun loadPreview(change: FileChange?) {
        previewJob?.cancel()
        currentRequest = null
        diff.setRequest(MessageDiffRequest("Loading preview…"))
        val expected = ++generation
        if (closed || change == null) return
        previewJob = service.submit("Read commit file", onSuccess = { preview ->
            if (closed || expected != generation) return@submit
            val unavailable = listOf(preview.before, preview.after).filter { it.state == BlobPreviewState.TOO_LARGE }
            if (unavailable.isNotEmpty()) {
                panel.showMessage("File exceeds the preview limit", "${change.path}\n\nEach revision can be up to 2 MiB. This file has a revision of ${unavailable.maxOf { it.size }} bytes. Inspect larger files in the IDE’s Git log.")
                return@submit
            }
            val request = CommitDiffRequests.create(project, preview)
            diff.setRequest(request)
            currentRequest = request
            panel.showPreview(preview)
        }, onFailure = { failure ->
            if (!closed && expected == generation) panel.showMessage("Preview unavailable", failure.message ?: "The selected revision could not be read.", true)
        }) { service.git.commitFilePreview(root, resolved.hash, change) }
    }

    override fun dispose() {
        closed = true
        generation++
        metadataJob?.cancel()
        previewJob?.cancel()
        currentRequest = null
        super.dispose()
    }
}

internal object CommitDiffRequests {
    fun create(project: Project, preview: CommitFilePreview): DiffRequest {
        val factory = DiffContentFactory.getInstance()
        fun content(blob: GitBlob, path: String): DiffContent {
            if (blob.state == BlobPreviewState.SUBMODULE) return factory.create(project, "Subproject commit ${blob.objectId}\n")
            if (blob.objectId == null) return factory.createEmpty()
            val bytes = requireNotNull(blob.bytes)
            // A symlink named *.png is link text, not an image.
            val type = if (blob.mode == "120000") PlainTextFileType.INSTANCE
                else FileTypeManager.getInstance().getFileTypeByFileName(path)
            return factory.createFromBytes(project, bytes, type, path.substringAfterLast('/'))
        }
        return SimpleDiffRequest(
            "${preview.change.path} · ${preview.commit.hash.take(8)}",
            content(preview.before, preview.change.oldPath ?: preview.change.path), content(preview.after, preview.change.path),
            "Before · ${preview.commit.parents.firstOrNull()?.take(8) ?: "Empty tree"}", "After · ${preview.commit.hash.take(8)}",
        ).apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
        }
    }
}
