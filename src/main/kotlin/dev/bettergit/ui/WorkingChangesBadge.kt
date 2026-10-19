package dev.bettergit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindow
import dev.bettergit.services.WorkbenchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Follows the IDE's cached local changes even before the tool-window content is opened. */
internal class WorkingChangesBadge private constructor(private val toolWindow: ToolWindow) : Disposable {
    private val project = toolWindow.project
    private val baseIcon = toolWindow.icon ?: IconLoader.getIcon("/icons/betterGit.svg", javaClass)
    private val baseTitle = toolWindow.stripeTitle
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var lastCount = -1
    private val job = WorkbenchService.getInstance(project).scope.launch(Dispatchers.Default) {
        for (ignored in updates) {
            // A single VCS refresh can publish several events; read its latest snapshot once.
            delay(150)
            updates.tryReceive()
            val count = countChangedFiles(project)
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed && !toolWindow.isDisposed && count != lastCount) {
                    lastCount = count
                    toolWindow.setIcon(if (count == 0) baseIcon else ChangeCountIcon(baseIcon, count))
                    toolWindow.stripeTitle = if (count == 0) baseTitle
                        else "$baseTitle ($count changed ${if (count == 1) "file" else "files"})"
                }
            }
        }
    }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() { updates.trySend(Unit) }
        })
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsMappingListener {
            updates.trySend(Unit)
        })
        updates.trySend(Unit)
    }

    override fun dispose() {
        job.cancel()
        updates.close()
    }

    companion object {
        fun install(toolWindow: ToolWindow) {
            Disposer.register(toolWindow.disposable, WorkingChangesBadge(toolWindow))
        }

        private fun countChangedFiles(project: Project): Int {
            val changes = ChangeListManager.getInstance(project)
            val vcs = ProjectLevelVcsManager.getInstance(project)
            val paths = changes.allChanges.mapNotNull { it.afterRevision?.file ?: it.beforeRevision?.file } +
                changes.unversionedFilesPaths
            // A staged and unstaged edit, or both sides of a rename, represent one changed file.
            return paths.asSequence().filter { vcs.getVcsFor(it)?.name == "Git" }.map { it.path }.toSet().size
        }
    }
}
