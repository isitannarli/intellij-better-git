package dev.bettergit.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.bettergit.core.git.GitClient
import dev.bettergit.core.git.GitRemote
import dev.bettergit.core.git.WorkingTreeStatus
import git4idea.config.GitExecutableManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path

data class RepositorySnapshot(val root: Path, val status: WorkingTreeStatus, val remotes: List<GitRemote>) {
    val name: String get() = root.fileName?.toString() ?: root.toString()
    override fun toString() = name
}

@Service(Service.Level.PROJECT)
class WorkbenchService(private val project: Project, val scope: CoroutineScope) {
    private val workerDispatcher = Dispatchers.IO.limitedParallelism(4)
    val git: GitClient get() = GitClient(GitExecutableManager.getInstance().getPathToGit(project))

    @RequiresBackgroundThread
    fun rootFor(file: VirtualFile): Path? = git.root(Path.of(if (file.isDirectory) file.path else file.parent.path))

    @RequiresBackgroundThread
    fun repositories(): List<RepositorySnapshot> {
        val client = git
        val candidates = linkedSetOf<Path>()
        project.basePath?.let { base ->
            val path = Path.of(base)
            client.root(path)?.let(candidates::add)
            if (Files.isDirectory(path)) Files.list(path).use { children ->
                children.filter { Files.isDirectory(it) && Files.exists(it.resolve(".git")) }
                    .limit(100).forEach { client.root(it)?.let(candidates::add) }
            }
        }
        val mappedRoots = ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()
            .filter { it.vcs?.name == "Git" }
            .map { Path.of(it.path.path) }
        mappedRoots.forEach { client.root(it)?.let(candidates::add) }
        return candidates.map { root -> RepositorySnapshot(root, client.status(root), client.remotes(root)) }
    }

    fun <T> submit(
        title: String,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { notifyError(title, it) },
        task: () -> T,
    ): Job = scope.launch(Dispatchers.EDT) {
        try {
            val result = runInterruptible(workerDispatcher) { task() }
            if (!project.isDisposed) onSuccess(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!project.isDisposed) onFailure(failure)
        }
    }

    fun notifyError(title: String, failure: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup("Better Git")
            .createNotification(title, failure.message ?: "The operation could not be completed.", NotificationType.WARNING)
            .notify(project)
    }

    companion object { fun getInstance(project: Project): WorkbenchService = project.service() }
}
