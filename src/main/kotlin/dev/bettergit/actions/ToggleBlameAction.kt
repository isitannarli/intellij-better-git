package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import dev.bettergit.editor.InlineBlameService
import dev.bettergit.settings.BetterGitSettings

class ToggleBlameAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(event: AnActionEvent) = BetterGitSettings.getInstance().state.inlineBlame
    override fun setSelected(event: AnActionEvent, state: Boolean) {
        BetterGitSettings.getInstance().state.inlineBlame = state
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) project.service<InlineBlameService>().refreshAll()
        }
    }
}
