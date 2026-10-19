package dev.bettergit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.bettergit.core.hosting.HostedRemoteChoices
import dev.bettergit.core.hosting.HostedRemoteChoice
import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.HostingClient
import dev.bettergit.services.WorkbenchService
import dev.bettergit.services.HostingCredentials
import kotlinx.coroutines.Job
import java.nio.file.Path
import javax.swing.JComponent

object CommitHostingActions {
    fun openCommit(project: Project, root: Path, hash: String) = chooseRepository(project, root) {
        BrowserUtil.browse(it.commitUrl(hash))
    }

    fun openReviews(project: Project, root: Path, hash: String) = chooseRepository(project, root) {
        RelatedReviewsDialog(project, it, hash).show()
    }

    private fun chooseRepository(project: Project, root: Path, selected: (HostedRepository) -> Unit) {
        val service = WorkbenchService.getInstance(project)
        service.submit("Find commit hosting remote", onSuccess = { choices ->
            when {
                choices.isEmpty() -> Messages.showInfoMessage(project,
                    "No supported GitHub/GitLab remote was found for this repository.", "Better Git · Open on Hosting Service")
                choices.size == 1 && !choices.single().confirmProvider -> selected(choices.single().repository)
                else -> JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                    .setTitle("Select remote and hosting provider")
                    .setRenderer(Ui.renderer { label, choice: HostedRemoteChoice, _ -> label.text = choice.toString() })
                    .setItemChosenCallback { selected(it.repository) }
                    .createPopup().showCenteredInCurrentWindow(project)
            }
        }) { HostedRemoteChoices.from(service.git.remotes(root)) }
    }

    private class RelatedReviewsDialog(
        private val project: Project,
        private val repository: HostedRepository,
        private val hash: String,
    ) : DialogWrapper(project, false) {
        private var job: Job? = null
        private var closed = false
        private var generation = 0L
        private var nextPage = 1
        private val panel = RelatedReviewsPanel(repository, hash,
            onOpen = { BrowserUtil.browse(it.url) },
            onCommit = { BrowserUtil.browse(repository.commitUrl(hash)) },
            onMore = { load(nextPage) })

        init {
            title = "Related PRs / MRs · ${hash.take(8)}"
            setModal(false)
            setOKButtonText("Close")
            init()
            load(1)
        }

        override fun createCenterPanel(): JComponent = panel
        override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
        override fun getDimensionServiceKey() = "BetterGit.RelatedReviews"
        override fun createActions() = arrayOf(okAction)

        private fun load(page: Int) {
            if (closed || project.isDisposed) return
            job?.cancel()
            val expected = ++generation
            nextPage = page
            panel.loading()
            job = WorkbenchService.getInstance(project).submit("Find related pull / merge requests", onSuccess = { result ->
                if (closed || generation != expected) return@submit
                nextPage = result.page + 1
                if (result.page == 1 && result.items.size == 1 && !result.hasNextPage) {
                    BrowserUtil.browse(result.items.single().url)
                    close(OK_EXIT_CODE)
                } else panel.loaded(result)
            }, onFailure = {
                if (!closed && generation == expected) panel.failed(it.message ?: "Related reviews could not be loaded.")
            }) {
                val token = HostingCredentials.resolver().token(repository)
                HostingClient().reviewsForCommit(repository, hash, token, page)
            }
        }

        override fun dispose() {
            closed = true
            ++generation
            job?.cancel()
            super.dispose()
        }
    }
}
