package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.bettergit.ui.Ui

class AnnotateAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.currentEditor() != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.currentEditor() ?: return
        Ui.nativeAction(project, editor.contentComponent, "Annotate")
    }
}
