package dev.bettergit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.bettergit.core.ai.AiProvider

@Service(Service.Level.APP)
@State(name = "BetterGitAiCommitSettings", storages = [Storage("betterGitAi.xml")])
class AiCommitSettings : PersistentStateComponent<AiCommitSettings.Options> {
    data class Options(
        var provider: String = "OLLAMA",
        var ollamaModel: String = "qwen2.5-coder:7b",
        var openaiModel: String = "gpt-5-mini",
        var geminiModel: String = "gemini-3.8-flash",
        var claudeModel: String = "claude-haiku-5-5",
        var localEndpoint: String = "http://127.0.0.1:11434",
        var style: String = "Conventional Commits",
        var anthropicWorkspaceId: String = "",
    )

    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) {
        val defaults = Options()
        options = state.copy(
            provider = state.provider.takeIf { name -> AiProvider.entries.any { it.name == name } } ?: defaults.provider,
            ollamaModel = text(state.ollamaModel, defaults.ollamaModel, 128),
            openaiModel = text(state.openaiModel, defaults.openaiModel, 128),
            geminiModel = text(state.geminiModel, defaults.geminiModel, 128),
            claudeModel = text(state.claudeModel, defaults.claudeModel, 128),
            localEndpoint = text(state.localEndpoint, defaults.localEndpoint, 256),
            style = text(state.style, defaults.style, 200),
            anthropicWorkspaceId = text(state.anthropicWorkspaceId, "", 128),
        )
    }

    private fun text(value: String, fallback: String, limit: Int): String = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= limit && it.none(Char::isISOControl) } ?: fallback

    companion object { fun getInstance(): AiCommitSettings = service() }
}
