package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class OpenCommitGraphAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Better Git Graph")?.activate(null) }
    }
}
