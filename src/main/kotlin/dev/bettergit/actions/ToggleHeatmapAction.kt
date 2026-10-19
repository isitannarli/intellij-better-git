package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import dev.bettergit.editor.FileHeatmapService

class ToggleHeatmapAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabledAndVisible = event.project != null && event.currentEditor() != null
        event.presentation.description = "Line age: red < 7 days, amber < 30 days, teal < 90 days, violet older. Hover the scrollbar for age details."
    }

    override fun isSelected(event: AnActionEvent): Boolean {
        val project = event.project ?: return false
        val editor = event.currentEditor() ?: return false
        return project.getServiceIfCreated(FileHeatmapService::class.java)?.isEnabled(editor) == true
    }

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        val project = event.project ?: return
        val editor = event.currentEditor() ?: return
        if (state && FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) {
            Messages.showInfoMessage(project, "Save this file before displaying line ages. The heatmap uses the saved working copy.", "Better Git")
            return
        }
        project.service<FileHeatmapService>().setEnabled(editor, state)
    }
}
