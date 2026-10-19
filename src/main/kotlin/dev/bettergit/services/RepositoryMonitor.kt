package dev.bettergit.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.bettergit.settings.BetterGitSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant

class RepositoryMonitor : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkbenchService.getInstance(project)
        service.scope.launch { monitor(project, service) }
    }

    private suspend fun monitor(project: Project, service: WorkbenchService) {
        var lastFetch = Instant.EPOCH
        val lastBehind = mutableMapOf<Pair<Path, String>, Int>()
        while (!project.isDisposed) {
            delay(60_000)
            val settings = BetterGitSettings.getInstance().state.copy()
            if (!settings.autoFetch || Instant.now().isBefore(lastFetch.plusSeconds(settings.fetchIntervalMinutes * 60L))) continue
            lastFetch = Instant.now()
            try {
                val repositories = runInterruptible(Dispatchers.IO) { service.repositories() }
                for (repository in repositories.filter { it.remotes.isNotEmpty() }) {
                    val after = runInterruptible(Dispatchers.IO) {
                        service.git.fetch(repository.root)
                        service.git.status(repository.root)
                    }
                    val key = repository.root to after.branch
                    val previous = lastBehind.put(key, after.behind) ?: 0
                    if (settings.notifyBehind && after.behind > previous && !project.isDisposed) {
                        NotificationGroupManager.getInstance().getNotificationGroup("Better Git").createNotification(
                            "${repository.name} · ${after.branch}",
                            "${after.behind} incoming commit(s). Review them before updating your branch.",
                            NotificationType.INFORMATION,
                        ).notify(project)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
}
