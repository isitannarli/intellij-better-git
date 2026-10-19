package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import dev.bettergit.core.ai.AiProvider
import dev.bettergit.core.ai.CommitMessageGenerator
import dev.bettergit.core.ai.CommitMessageRequest
import dev.bettergit.services.AiCredentials
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.AiCommitSettings
import kotlinx.coroutines.Job
import java.net.URI
import java.nio.file.Path
import javax.swing.JComponent

class AiCommitDialog(
    private val project: Project,
    private val root: Path,
    private val diffSupplier: (() -> String)? = null,
    private val applyMessage: ((String) -> Boolean)? = null,
    scopeLabel: String = "Staged changes · captured snapshot",
) : DialogWrapper(project, false) {
    private var diff: String? = null
    private var job: Job? = null
    private var diffJob: Job? = null
    private var keyJob: Job? = null
    private var generation = 0L
    private var diffGeneration = 0L
    private var closed = false
    private val panel = AiCommitPanel(configuration(), scopeLabel, applyMessage != null, object : AiCommitPanel.Listener {
        override fun generate() = generateDraft()
        override fun cancel() = cancelOperation()
        override fun retryDiff() = loadDiff()
        override fun inputChanged() {
            if (job?.isActive == true) cancelGeneration("Configuration changed. Generate again with the selected provider.")
        }
        override fun outputEdited() = Unit
        override fun apply(message: String) {
            if (!closed && !project.isDisposed && message.isNotBlank() && applyMessage?.invoke(message) == true) {
                close(OK_EXIT_CODE)
            }
        }
        override fun forgetKey(provider: AiProvider) = forget(provider)
    })

    init {
        title = "Better Git · AI Commit Message"
        setModal(false)
        setOKButtonText("Close")
        init()
        loadDiff()
    }

    override fun createCenterPanel(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
    override fun getDimensionServiceKey() = "BetterGit.AiCommitMessage"
    override fun createActions() = arrayOf(okAction)

    private fun loadDiff() {
        if (closed || project.isDisposed) return
        cancelGeneration()
        diffJob?.cancel()
        diff = null
        val expected = ++diffGeneration
        panel.setDiffLoading()
        val service = WorkbenchService.getInstance(project)
        diffJob = service.submit("Read commit changes", onSuccess = { snapshot ->
            if (closed || diffGeneration != expected) return@submit
            val bytes = snapshot.toByteArray(Charsets.UTF_8).size
            val usable = snapshot.isNotBlank() && bytes <= CommitMessageGenerator.MAX_DIFF_BYTES && '\u0000' !in snapshot
            diff = snapshot.takeIf { usable }
            panel.setDiff(snapshot, bytes, usable, when {
                snapshot.isBlank() -> "No text changes are included. Select or stage changes, then reopen the composer."
                bytes > CommitMessageGenerator.MAX_DIFF_BYTES -> "The selected diff exceeds 100 KiB. Include a smaller change and reopen the composer."
                '\u0000' in snapshot -> "Binary data cannot be summarized. Select text changes and reopen the composer."
                else -> "Preview the captured diff, choose a provider, then Generate draft."
            })
        }, onFailure = {
            if (!closed && diffGeneration == expected) panel.diffFailed(it.message ?: "Cannot read the selected changes.")
        }) { diffSupplier?.invoke() ?: service.git.stagedDiff(root) }
    }

    private fun generateDraft() {
        val snapshot = diff ?: return
        if (closed || project.isDisposed) return
        val form = panel.form()
        if (form.model.isBlank() || form.style.isBlank()) {
            panel.showStatus("Enter a model ID and a message style before generating.")
            return
        }
        val endpoint = try {
            if (form.provider == AiProvider.OLLAMA) URI(form.localEndpoint) else URI("http://127.0.0.1:11434")
        } catch (_: Exception) {
            panel.showStatus("Enter a valid local Ollama endpoint, such as http://127.0.0.1:11434.")
            return
        }
        job?.cancel()
        val expected = ++generation
        persist(form)
        panel.setGenerating(true, if (form.provider == AiProvider.OLLAMA) "Generating with the installed local model…"
            else "Generating with ${form.provider.displayName}; the previewed diff is sent to this provider…")
        job = WorkbenchService.getInstance(project).submit("Generate commit message", onSuccess = { message ->
            if (!closed && generation == expected) panel.showDraft(message)
        }, onFailure = {
            if (!closed && generation == expected) panel.setGenerating(false, it.message ?: "AI generation failed. Check the selected provider.")
        }) {
            val key = AiCredentials.resolve(form.provider, form.apiKey, form.rememberKey)
            CommitMessageGenerator().generate(CommitMessageRequest(form.provider, form.model, snapshot,
                form.style, endpoint, key, form.anthropicWorkspaceId.takeIf(String::isNotBlank)))
        }
    }

    private fun forget(provider: AiProvider) {
        keyJob?.cancel()
        keyJob = WorkbenchService.getInstance(project).submit("Remove saved AI key", onSuccess = {
            if (!closed && panel.selectedProvider() == provider) panel.showStatus("Saved key removed. Environment variables are unchanged.")
        }, onFailure = {
            if (!closed) panel.showStatus(it.message ?: "Cannot remove the saved key.")
        }) { AiCredentials.forget(provider) }
    }

    private fun cancelGeneration(message: String? = null) {
        ++generation
        job?.cancel()
        job = null
        panel.setGenerating(false, message ?: "Ready to read changes.")
    }

    private fun cancelOperation() {
        cancelGeneration("Generation cancelled. Your existing draft is preserved.")
        if (diffJob?.isActive == true) {
            ++diffGeneration
            diffJob?.cancel()
            panel.diffFailed("Diff read cancelled. Retry to read the same captured selection.")
        }
    }

    override fun dispose() {
        closed = true
        ++generation
        ++diffGeneration
        job?.cancel()
        diffJob?.cancel()
        keyJob?.cancel()
        panel.disposePanel()
        super.dispose()
    }

    private fun persist(form: AiCommitForm) {
        AiCommitSettings.getInstance().state.apply {
            provider = form.provider.name
            when (form.provider) {
                AiProvider.OLLAMA -> ollamaModel = form.model
                AiProvider.OPENAI -> openaiModel = form.model
                AiProvider.GEMINI -> geminiModel = form.model
                AiProvider.CLAUDE -> claudeModel = form.model
            }
            localEndpoint = form.localEndpoint
            style = form.style
            anthropicWorkspaceId = form.anthropicWorkspaceId
        }
    }

    companion object {
        private fun configuration(): AiCommitConfiguration {
            val settings = AiCommitSettings.getInstance().state
            return AiCommitConfiguration(AiProvider.entries.firstOrNull { it.name == settings.provider } ?: AiProvider.OLLAMA,
                mapOf(AiProvider.OLLAMA to settings.ollamaModel, AiProvider.OPENAI to settings.openaiModel,
                    AiProvider.GEMINI to settings.geminiModel, AiProvider.CLAUDE to settings.claudeModel),
                settings.localEndpoint, settings.style, settings.anthropicWorkspaceId)
        }
    }
}
