package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.bettergit.core.git.EditorLineSelection
import dev.bettergit.services.WorkbenchService
import dev.bettergit.ui.SelectionHistoryDialog
import java.nio.file.Path

class SelectionHistoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        event.presentation.isVisible = event.project != null && editor != null &&
            editor.editorKind == EditorKind.MAIN_EDITOR && !editor.isViewer &&
            file?.isInLocalFileSystem == true && !file.isDirectory
        event.presentation.isEnabled = event.presentation.isVisible && editor?.caretModel?.caretCount == 1
        val prefix = if (event.place == ActionPlaces.EDITOR_POPUP) "" else "Better Git: "
        event.presentation.text = prefix + if (editor?.selectionModel?.hasSelection() == true)
            "Selection History" else "Line History"
        event.presentation.description = if (editor?.caretModel?.caretCount != 1)
            "Use one contiguous selection to inspect code history"
        else "See who changed this code, when, and the changes in each commit"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (editor.isDisposed || editor.caretModel.caretCount != 1) return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem || file.isDirectory || !file.isValid) return
        if (document.textLength > 2_000_000) {
            Messages.showInfoMessage(project, "Selection history supports text files up to 2 million characters.", "Better Git")
            return
        }
        // Capture on EDT so Git work never reads a changing Document.
        val snapshot = document.text
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
        val end = if (selection.hasSelection()) selection.selectionEnd else start
        val range = EditorLineSelection.range(snapshot, start, end)
        if (range == null) {
            Messages.showInfoMessage(project, "This empty trailing line has no code history. Select existing lines to inspect their changes.", "Better Git")
            return
        }
        val filePath = Path.of(file.path)
        val service = project.service<WorkbenchService>()
        service.submit("Find selection repository", onSuccess = { root: Path? ->
            if (editor.isDisposed) return@submit
            if (root == null) {
                Messages.showInfoMessage(project, "No Git repository was found for this file.", "Better Git")
            } else {
                val relativePath = root.relativize(filePath).joinToString("/") { it.toString() }
                SelectionHistoryDialog.show(project, root, relativePath, range, snapshot)
            }
        }) { service.git.root(filePath.parent) }
    }
}
