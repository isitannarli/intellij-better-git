package dev.bettergit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.bettergit.services.WorkbenchService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RepositoryRefreshScope { WORKTREE, REPOSITORY }

/** EDT-owned queue: keep at most one follow-up, and never lose a full refresh to a file event. */
internal class RepositoryRefreshQueue {
    private var pending: RepositoryRefreshScope? = null
    private var running = false

    fun request(scope: RepositoryRefreshScope) {
        if (pending != RepositoryRefreshScope.REPOSITORY) pending = scope
    }

    fun take(visible: Boolean): RepositoryRefreshScope? {
        if (!visible || running) return null
        val scope = pending ?: return null
        pending = null
        running = true
        return scope
    }

    fun finished() { running = false }
}

/** Coalesces native IDE events; no polling, document saving, or filesystem rescanning. */
internal class RepositoryRefreshListener(project: Project, refresh: (RepositoryRefreshScope) -> Unit) : Disposable {
    private val basePath = project.basePath
    @Volatile private var roots = listOfNotNull(basePath)
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val repositoryChanged = AtomicBoolean()
    private val job = WorkbenchService.getInstance(project).scope.launch(Dispatchers.EDT) {
        for (ignored in updates) {
            delay(300)
            updates.tryReceive()
            val scope = if (repositoryChanged.getAndSet(false)) RepositoryRefreshScope.REPOSITORY else RepositoryRefreshScope.WORKTREE
            if (!project.isDisposed) refresh(scope)
        }
    }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { request(RepositoryRefreshScope.REPOSITORY) })
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = request(RepositoryRefreshScope.WORKTREE)
        })
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsMappingListener { request(RepositoryRefreshScope.REPOSITORY) })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var scope: RepositoryRefreshScope? = null
                for (event in events) {
                    if (roots.none { root -> event.path == root || event.path.startsWith("$root/") }) continue
                    if (event.path.contains("/.git/") || event.path.endsWith("/.git")) {
                        scope = RepositoryRefreshScope.REPOSITORY
                        break
                    }
                    // Ignore generated/build files already classified by the IDE. No disk or Git calls here.
                    if (event.file?.let { ChangeListManager.getInstance(project).isIgnoredFile(it) } == true) continue
                    scope = RepositoryRefreshScope.WORKTREE
                }
                scope?.let(::request)
            }
        })
    }

    fun setRoots(paths: List<Path>) { roots = (paths.map { it.toAbsolutePath().normalize().toString().replace('\\', '/') } + listOfNotNull(basePath)).distinct() }
    private fun request(scope: RepositoryRefreshScope) {
        if (scope == RepositoryRefreshScope.REPOSITORY) repositoryChanged.set(true)
        updates.trySend(Unit)
    }
    override fun dispose() { updates.close(); job.cancel() }
}
