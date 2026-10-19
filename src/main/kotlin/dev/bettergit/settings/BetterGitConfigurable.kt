package dev.bettergit.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class BetterGitConfigurable : Configurable {
    private var ui: SettingsUi? = null

    override fun getDisplayName() = "Better Git"
    override fun createComponent(): JComponent {
        ui?.let { return it.panel }
        return SettingsUi().also {
            ui = it
            reset()
        }.panel
    }

    override fun isModified(): Boolean {
        val currentUi = ui ?: return false
        return currentUi.values() != BetterGitSettings.getInstance().state
    }

    override fun apply() {
        val currentUi = ui ?: return
        BetterGitSettings.getInstance().loadState(currentUi.values())
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) project.service<dev.bettergit.editor.InlineBlameService>().refreshAll()
        }
    }

    override fun reset() {
        val currentUi = ui ?: return
        currentUi.reset(BetterGitSettings.getInstance().state)
    }

    override fun disposeUIResources() {
        ui = null
    }

    private class SettingsUi {
        private val blame = JBCheckBox("Show author and commit beside the current line")
        private val avatars = JBCheckBox("Load author photos from GitHub/GitLab when a blame hover opens")
        private val autoFetch = JBCheckBox("Fetch remotes automatically while the project is open")
        private val behind = JBCheckBox("Notify when the current branch falls behind its upstream")
        private val conventional = JBCheckBox("Validate commit messages: Conventional Commits and subject length")
        private val history = JSpinner(SpinnerNumberModel(200, 50, 2000, 50))
        private val interval = JSpinner(SpinnerNumberModel(15, 5, 120, 5))
        private val subject = JSpinner(SpinnerNumberModel(72, 20, 200, 1))

        val panel: JPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Local Git first. Free, with no Better Git account."))
            .addSeparator(12).addComponent(blame)
            .addComponent(avatars)
            .addLabeledComponent("Commits to load:", history)
            .addSeparator(12).addComponent(autoFetch)
            .addLabeledComponent("Fetch interval (minutes):", interval)
            .addComponent(behind)
            .addSeparator(12).addComponent(conventional)
            .addLabeledComponent("Commit subject limit:", subject)
            .addSeparator(12)
            .addComponent(JBLabel("PR/MR access uses existing gh, glab or supported global Git credentials."))
            .addComponent(JBLabel("No credentials? Public repositories and all local features still work."))
            .addComponentFillVertically(JPanel(), 0).panel.apply { border = JBUI.Borders.empty(12) }

        fun values() = BetterGitSettings.Options(
            blame.isSelected, history.value as Int, autoFetch.isSelected,
            interval.value as Int, behind.isSelected, conventional.isSelected, subject.value as Int,
            avatars.isSelected,
        )

        fun reset(settings: BetterGitSettings.Options) {
            blame.isSelected = settings.inlineBlame
            avatars.isSelected = settings.authorAvatars
            history.value = settings.historyLimit
            autoFetch.isSelected = settings.autoFetch
            interval.value = settings.fetchIntervalMinutes
            behind.isSelected = settings.notifyBehind
            conventional.isSelected = settings.conventionalCommits
            subject.value = settings.subjectLimit
        }
    }
}
