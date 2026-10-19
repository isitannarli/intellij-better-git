package dev.bettergit.services

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Keeps external Git writes and the IDE's documents, VFS and repository model in sync. */
@Service(Service.Level.PROJECT)
class GitMutationService(private val project: Project, private val scope: CoroutineScope) {
    private val mutationLock = Mutex()

    fun <T> submit(
        root: Path,
        title: String,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { WorkbenchService.getInstance(project).notifyError(title, it) },
        task: () -> T,
    ): Job = scope.launch(Dispatchers.EDT) {
        try {
            val result = mutate(root, task)
            if (!project.isDisposed) onSuccess(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!project.isDisposed) onFailure(failure)
        }
    }

    private suspend fun <T> mutate(root: Path, task: () -> T): T = mutationLock.withLock {
        // Save after taking the lock: edits can change while another mutation is running.
        withContext(Dispatchers.EDT) {
            currentCoroutineContext().ensureActive()
            if (project.isDisposed) throw CancellationException("Project closed")
            val documents = FileDocumentManager.getInstance()
            documents.saveAllDocuments()
            val repositoryPath = root.toAbsolutePath().normalize()
            check(documents.unsavedDocuments.none { document ->
                val file = documents.getFile(document)
                file != null && file.isInLocalFileSystem && Path.of(file.path).normalize().startsWith(repositoryPath)
            }) { "Save the unsaved files in this repository before changing its Git state." }
        }

        var commandFailure: Throwable? = null
        try {
            runInterruptible(Dispatchers.IO) { task() }
        } catch (failure: Throwable) {
            commandFailure = failure
            throw failure
        } finally {
            // A failed or cancelled Git command can still leave changed files (for example merge conflicts).
            // Keep the lock until refresh completes, and never perform a synchronous refresh under a read lock.
            try {
                withContext(NonCancellable + Dispatchers.IO) { refresh(root) }
            } catch (refreshFailure: Throwable) {
                if (commandFailure != null) commandFailure.addSuppressed(refreshFailure) else throw refreshFailure
            }
        }
    }

    private fun refresh(root: Path) {
        if (project.isDisposed || !scope.isActive) return
        VfsUtil.markDirtyAndRefresh(false, true, false, root)
        if (project.isDisposed || !scope.isActive) return
        val virtualRoot = LocalFileSystem.getInstance().findFileByNioFile(root) ?: return
        try {
            GitRepositoryManager.getInstance(project).getRepositoryForRoot(virtualRoot)?.let { repository ->
                if (!repository.isDisposed) repository.update()
            }
        } finally {
            if (!project.isDisposed && scope.isActive) VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(virtualRoot)
        }
    }

    companion object { fun getInstance(project: Project): GitMutationService = project.service() }
}
