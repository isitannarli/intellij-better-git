package dev.bettergit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import dev.bettergit.services.CommitAiContext
import dev.bettergit.ui.AiCommitDialog
import java.nio.file.Path

class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null &&
            event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val context = try {
            CommitAiContext.capture(event)
        } catch (failure: IllegalArgumentException) {
            offerStagedFallback(event, failure.message ?: "The native commit selection is unavailable.")
            return
        } catch (failure: IllegalStateException) {
            offerStagedFallback(event, failure.message ?: "The native commit selection is not ready.")
            return
        }
        try {
            val dialog = AiCommitDialog(project, context.root, context::readDiff, context::applyMessage, context.scopeLabel)
            context.bind(dialog)
            dialog.show()
        } catch (failure: Exception) {
            Disposer.dispose(context)
            throw failure
        }
    }

    private fun offerStagedFallback(event: AnActionEvent, reason: String) {
        val project = event.project ?: return
        val root = ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()
            .filter { it.vcs?.name == "Git" }.singleOrNull()?.path
        if (root == null) {
            Messages.showInfoMessage(project, "$reason\n\nOpen Better Git's AI dialog for the repository whose staged changes you want to summarize.",
                "Better Git · Commit Selection")
            return
        }
        val open = Messages.showYesNoDialog(project,
            "$reason\n\nOpen a separate preview of all staged changes in this repository? It uses a different scope and will not write into the native commit field.",
            "Better Git · Commit Selection", "Preview Staged Changes", "Cancel", Messages.getInformationIcon())
        if (open == Messages.YES) AiCommitDialog(project, Path.of(root.path)).show()
    }
}
