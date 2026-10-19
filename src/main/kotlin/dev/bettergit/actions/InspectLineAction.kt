package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.bettergit.core.git.GitCommit
import dev.bettergit.services.WorkbenchService
import dev.bettergit.ui.CommitDetails
import java.nio.file.Path

class InspectLineAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.currentEditor() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.currentEditor() ?: return
        val files = FileDocumentManager.getInstance()
        val file = files.getFile(editor.document) ?: return
        if (files.isDocumentUnsaved(editor.document)) {
            Messages.showInfoMessage(project, "Save this file before inspecting line authorship so the line matches the working copy.", "Better Git")
            return
        }
        val line = editor.caretModel.logicalPosition.line + 1
        val stamp = editor.document.modificationStamp
        val workbench = project.service<WorkbenchService>()
        workbench.submit("Inspect line commit", onSuccess = { result: Pair<Path, GitCommit?>? ->
            if (editor.isDisposed || editor.document.modificationStamp != stamp) return@submit
            if (result?.second != null) {
                CommitDetails.show(project, result.first, result.second!!)
            } else {
                Messages.showInfoMessage(project,
                    if (result == null) "No Git repository was found for this file." else "This line has no committed history yet.",
                    "Better Git")
            }
        }) {
            workbench.rootFor(file)?.let { root ->
                val path = root.relativize(Path.of(file.path)).toString().replace('\\', '/')
                root to workbench.git.blame(root, path, line)?.commit
            }
        }
    }
}
