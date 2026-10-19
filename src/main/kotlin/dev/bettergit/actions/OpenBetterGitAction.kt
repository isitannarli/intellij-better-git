package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class OpenBetterGitAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Better Git")?.activate(null)
    }
}
