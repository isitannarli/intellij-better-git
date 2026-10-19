package dev.bettergit.services

import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListChange
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.InclusionListener
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.PartialChangesUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.vcs.commit.CommitWorkflowUi
import java.io.StringWriter
import java.nio.file.Path
import java.util.concurrent.CancellationException

class CommitAiContext private constructor(
    private val project: Project,
    private val workflow: CommitWorkflowUi,
    private val messageControl: CommitMessageI,
    private val messageDocument: Document,
    private val originalMessage: String,
    val root: Path,
    private val changes: List<CapturedChange>,
    private val selectionKeys: List<String>,
) : Disposable {
    @Volatile private var closed = false
    @Volatile private var selectionChanged = false
    private val originalMessageStamp = messageDocument.modificationStamp
    val scopeLabel: String = "Native commit selection · ${changes.size} files · captured snapshot"

    init {
        workflow.addInclusionListener(object : InclusionListener {
            override fun inclusionChanged() { selectionChanged = true }
        }, this)
    }

    private val patch: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        checkActive()
        val immutable = changes.map { captured ->
            checkActive()
            Change(captured.before?.load(), captured.after?.load())
        }
        checkActive()
        val patches = IdeaTextPatchBuilder.buildPatch(null, immutable, root, false)
        require(patches.all { it is TextFilePatch }) {
            "This commit selection contains binary data. Select text changes before generating a message."
        }
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, root, patches, writer, "\n", null, emptyList())
        writer.toString().also {
            require(it.isNotBlank()) { "The selected changes have no text diff to summarize." }
        }
    }

    fun readDiff(): String { checkActive(); return patch }

    fun applyMessage(message: String): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!isCurrentSelection()) {
            Messages.showInfoMessage(project,
                "The native commit selection or its contents changed. Reopen Generate Commit Message to capture it again. Your draft remains available to copy.",
                "Better Git · Commit Selection Changed")
            return false
        }
        if (messageDocument.modificationStamp != originalMessageStamp || messageDocument.text != originalMessage) {
            val replace = Messages.showYesNoDialog(project,
                "The commit message was edited while this draft was being prepared. Replace the current message with this draft?",
                "Better Git · Keep Your Edits?", "Replace with Draft", "Keep Current Message", Messages.getQuestionIcon())
            if (replace != Messages.YES) return false
        }
        if (closed || project.isDisposed) return false
        WriteCommandAction.runWriteCommandAction(project, "Use AI Commit Message", null, Runnable {
            messageControl.setCommitMessage(message)
        })
        return true
    }

    fun bind(dialog: DialogWrapper) {
        val nativeLifetime = object : Disposable {
            override fun dispose() {
                val wasClosed = closed
                closed = true
                if (!wasClosed) ApplicationManager.getApplication().invokeLater {
                    if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            }
        }
        Disposer.register(workflow, nativeLifetime)
        Disposer.register(dialog.disposable, Disposable {
            closed = true
            Disposer.dispose(nativeLifetime)
            Disposer.dispose(this)
        })
    }

    override fun dispose() { closed = true }

    private fun checkActive() {
        if (closed || project.isDisposed || Thread.currentThread().isInterrupted) throw CancellationException("Commit draft closed")
    }

    private fun isCurrentSelection(): Boolean {
        if (closed || project.isDisposed || selectionChanged) return false
        if (keys(workflow) != selectionKeys) return false
        return changes.all { change -> change.isCurrent() }
    }

    private data class CapturedChange(
        val before: CapturedRevision?,
        val after: CapturedRevision?,
        val partialCheck: (() -> Boolean)? = null,
    ) {
        fun isCurrent() = (before?.isCurrent() != false) && (after?.isCurrent() != false) && (partialCheck?.invoke() != false)
    }

    private class CapturedRevision(
        private val delegate: ContentRevision,
        private val capturedText: String?,
        private val document: Document?,
        private val documentStamp: Long?,
        private val localFile: VirtualFile?,
        private val fileStamp: Long?,
    ) {
        private val path = delegate.file
        private val revision = delegate.revisionNumber
        fun isCurrent(): Boolean = (document == null || document.modificationStamp == documentStamp) &&
            (localFile == null || (localFile.isValid && localFile.modificationStamp == fileStamp))

        fun load(): ContentRevision {
            check(isCurrent()) { "A selected file changed before its snapshot was read. Reopen the commit draft." }
            val text = capturedText ?: delegate.content
                ?: error("Cannot read the selected revision of ${path.name}. Reopen the commit draft after Git refreshes.")
            requireText(text)
            check(isCurrent()) { "A selected file changed while its snapshot was read. Reopen the commit draft." }
            return object : ContentRevision {
                override fun getContent(): String = text
                override fun getFile(): FilePath = path
                override fun getRevisionNumber(): VcsRevisionNumber = revision
            }
        }
    }

    companion object {
        private const val MAX_FILES = 200
        private const val MAX_FILE_CHARS = 2_000_000

        /** Capture on EDT: included changes differ from highlighted rows. */
        fun capture(event: AnActionEvent): CommitAiContext {
            ApplicationManager.getApplication().assertIsDispatchThread()
            val project = requireNotNull(event.project) { "Open a project's native Commit panel." }
            val workflow = requireNotNull(event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)) {
                "This commit panel does not expose its included changes. Use the separate staged-changes dialog instead."
            }
            val control = requireNotNull(event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)) { "No native commit message field is available." }
            val document = requireNotNull(event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)) { "Focus the native commit message field and try again." }
            val included = workflow.getIncludedChanges().toList()
            val unversioned = workflow.getIncludedUnversionedFiles().toList()
            require(included.isNotEmpty() || unversioned.isNotEmpty()) { "Include changes in the Commit panel before generating a message." }
            require(included.size + unversioned.size <= MAX_FILES) { "Select at most $MAX_FILES files for one commit draft." }
            val allPaths = included.flatMap { listOfNotNull(it.beforeRevision?.file, it.afterRevision?.file) } + unversioned
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val roots = allPaths.map { path ->
                require(!path.isDirectory && !path.fileType.isBinary) { "Select regular text files for AI commit messages." }
                require(path.virtualFile?.`is`(VFileProperty.SYMLINK) != true) { "Symbolic links are not supported in native AI commit drafts." }
                val vcsRoot = vcsManager.getVcsRootObjectFor(path)
                require(vcsRoot?.vcs?.name == "Git") { "The commit selection must belong to Git repositories." }
                Path.of(vcsRoot.path.path)
            }.distinct()
            require(roots.size == 1) { "Select files from one Git repository for each commit draft." }

            val captured = included.groupBy { pathKey(it) }.values.map { group ->
                val change = group.first()
                val tracker = PartialChangesUtil.getPartialTracker(project, change)
                val ids = group.filterIsInstance<ChangeListChange>().map { it.changeListId }.distinct()
                var partialText: String? = null
                var partialCheck: (() -> Boolean)? = null
                if (tracker != null) {
                    require(tracker.isOperational() && tracker.isValid()) {
                        "Git is still updating a selected file's partial changes. Wait for the Commit panel to refresh and try again."
                    }
                    if (tracker.hasPartialChangesToCommit()) {
                        require(ids.isNotEmpty()) { "This panel does not identify the selected change list. Use staged changes instead." }
                        partialText = tracker.getChangesToBeCommitted(Side.LEFT, ids, true)
                            ?: error("The selected partial changes are not ready. Refresh the Commit panel and try again.")
                        requireText(partialText)
                        val expected = partialText
                        partialCheck = {
                            !tracker.isReleased && tracker.isOperational() && tracker.isValid() &&
                                tracker.getChangesToBeCommitted(Side.LEFT, ids, true) == expected
                        }
                    } else {
                        // A newly excluded hunk may leave the file checkbox selected.
                        partialCheck = { !tracker.isReleased && tracker.isOperational() && !tracker.hasPartialChangesToCommit() }
                    }
                }
                CapturedChange(captureRevision(change.beforeRevision), captureRevision(change.afterRevision, partialText), partialCheck)
            } + unversioned.map { CapturedChange(null, captureRevision(CurrentContentRevision.create(it))) }
            return CommitAiContext(project, workflow, control, document, document.text, roots.single(), captured, keys(workflow))
        }

        private fun captureRevision(revision: ContentRevision?, overrideText: String? = null): CapturedRevision? {
            if (revision == null) return null
            val local = (revision as? CurrentContentRevision)?.virtualFile
            val document = local?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            require(document == null || document.textLength <= MAX_FILE_CHARS) { "A selected file exceeds the 2 million character limit." }
            val snapshot = overrideText ?: document?.text
            snapshot?.let(::requireText)
            return CapturedRevision(revision, snapshot, document, document?.modificationStamp, local, local?.modificationStamp)
        }

        private fun requireText(text: String) {
            require(text.length <= MAX_FILE_CHARS && '\u0000' !in text) { "A selected revision is binary or exceeds the 2 million character limit." }
        }

        private fun pathKey(change: Change): String = "${change.beforeRevision?.file?.path}\u0000${change.afterRevision?.file?.path}"
        private fun keys(workflow: CommitWorkflowUi): List<String> = (workflow.getIncludedChanges().map { change ->
            "${pathKey(change)}\u0000${change.beforeRevision?.revisionNumber?.asString()}\u0000${change.afterRevision?.revisionNumber?.asString()}\u0000${(change as? ChangeListChange)?.changeListId}"
        } + workflow.getIncludedUnversionedFiles().map { "unversioned\u0000${it.path}" }).sorted()
    }
}
