package dev.bettergit.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.bettergit.core.ai.AiProvider
import dev.bettergit.services.AiCredentials
import dev.bettergit.ui.ScrollableColumnPanel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel

class BetterGitConfigurable internal constructor(private val writeCredential: (AiProvider, String?) -> Unit) : SearchableConfigurable {
    constructor() : this({ provider, key ->
        if (key == null) AiCredentials.forget(provider) else AiCredentials.store(provider, key)
    })

    private var ui: SettingsUi? = null

    override fun getId() = "dev.bettergit.settings"
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
        return currentUi.values() != BetterGitSettings.getInstance().state ||
            currentUi.ai.values() != AiCommitSettings.getInstance().state || currentUi.ai.hasCredentialEdits()
    }

    override fun apply() {
        val currentUi = ui ?: return
        currentUi.ai.validate()
        val credentials = currentUi.ai.credentialEdits()
        if (credentials.isNotEmpty()) {
            try {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(ThrowableComputable<Unit, Exception> {
                    credentials.forEach { (provider, key) -> writeCredential(provider, key) }
                }, "Save Better Git AI credentials", false, null)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (_: Exception) {
                throw ConfigurationException("Cannot update AI credentials. Check the IDE password store and apply again.")
            }
        }
        BetterGitSettings.getInstance().loadState(currentUi.values())
        AiCommitSettings.getInstance().loadState(currentUi.ai.values())
        currentUi.ai.clearCredentials()
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) project.service<dev.bettergit.editor.InlineBlameService>().refreshAll()
        }
    }

    override fun reset() {
        val currentUi = ui ?: return
        currentUi.reset(BetterGitSettings.getInstance().state)
        currentUi.ai.reset(AiCommitSettings.getInstance().state)
    }

    override fun disposeUIResources() {
        ui?.ai?.clearCredentials()
        ui = null
    }

    private class SettingsUi {
        val ai = AiCommitSettingsUi()
        private val blame = JBCheckBox("Show author and commit beside the current line")
        private val avatars = JBCheckBox("Load author photos from GitHub/GitLab when a blame hover opens")
        private val autoFetch = JBCheckBox("Fetch remotes automatically while the project is open")
        private val behind = JBCheckBox("Notify when the current branch falls behind its upstream")
        private val conventional = JBCheckBox("Validate commit messages: Conventional Commits and subject length")
        private val history = JSpinner(SpinnerNumberModel(200, 50, 2000, 50))
        private val interval = JSpinner(SpinnerNumberModel(15, 5, 120, 5))
        private val subject = JSpinner(SpinnerNumberModel(72, 20, 200, 1))

        private val form = FormBuilder.createFormBuilder()
            .addComponent(section("Editor and history"))
            .addComponent(blame)
            .addComponent(avatars)
            .addLabeledComponent("Commits to load:", history)
            .addSeparator(12).addComponent(section("Remotes"))
            .addComponent(autoFetch)
            .addLabeledComponent("Fetch interval (minutes):", interval)
            .addComponent(behind)
            .addSeparator(12).addComponent(section("Commit checks"))
            .addComponent(conventional)
            .addLabeledComponent("Commit subject limit:", subject)
            .addSeparator(12)
            .addComponent(section("AI commit messages"))
            .addComponent(ai.panel)
            .addSeparator(12).addComponent(section("Pull and merge requests"))
            .addComponent(AiCommitSettingsUi.help("Uses existing gh, glab or supported global Git credentials. Public repositories and all local features work without credentials."))
            .panel

        val panel: JComponent = JBScrollPane(ScrollableColumnPanel().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(12)
            add(form, BorderLayout.NORTH)
        }).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        init {
            autoFetch.addItemListener { interval.isEnabled = autoFetch.isSelected }
            conventional.addItemListener { subject.isEnabled = conventional.isSelected }
        }

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
            interval.isEnabled = autoFetch.isSelected
            subject.isEnabled = conventional.isSelected
        }

        private fun section(title: String) = JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }
    }

    companion object {
        fun show(project: Project?) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, BetterGitConfigurable::class.java)
        }
    }
}
