package dev.bettergit.services

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import dev.bettergit.core.git.CommitMessagePolicy
import dev.bettergit.settings.BetterGitSettings

class CommitMessageCheck : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = object : CheckinHandler() {
        override fun beforeCheckin(): ReturnResult {
            val settings = BetterGitSettings.getInstance().state
            if (!settings.conventionalCommits || !panel.vcsIsAffected("Git")) return ReturnResult.COMMIT
            val error = CommitMessagePolicy.validate(panel.commitMessage, settings.subjectLimit) ?: return ReturnResult.COMMIT
            Messages.showWarningDialog(panel.project, error, "Better Git · Commit Message")
            return ReturnResult.CANCEL
        }
    }
}
