package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class BetterGitToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        WorkingChangesBadge.install(toolWindow)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BetterGitPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
