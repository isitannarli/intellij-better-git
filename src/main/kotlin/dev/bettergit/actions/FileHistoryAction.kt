package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.bettergit.services.WorkbenchService
import dev.bettergit.ui.FileHistoryDialog
import java.nio.file.Path

class FileHistoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val file = event.currentFile()
        event.presentation.isEnabledAndVisible = event.project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.currentFile() ?: return
        val workbench = project.service<WorkbenchService>()
        workbench.submit("Find file repository", onSuccess = { root: Path? ->
            if (root == null) {
                Messages.showInfoMessage(project, "No Git repository was found for this file.", "Better Git")
            } else {
                FileHistoryDialog.show(project, root, root.relativize(Path.of(file.path)).toString().replace('\\', '/'))
            }
        }) { workbench.rootFor(file) }
    }
}
