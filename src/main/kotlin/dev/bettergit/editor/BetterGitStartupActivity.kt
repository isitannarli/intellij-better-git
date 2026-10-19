package dev.bettergit.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BetterGitStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) project.service<InlineBlameService>().start()
        }
    }
}
