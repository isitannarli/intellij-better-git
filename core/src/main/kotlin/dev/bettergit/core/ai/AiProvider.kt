package dev.bettergit.core.ai

import java.net.URI

enum class AiProvider(val displayName: String, val defaultModel: String, val environmentVariable: String?) {
    OLLAMA("Ollama (local)", LocalCommitMessageGenerator.DEFAULT_MODEL, null),
    OPENAI("OpenAI", "gpt-5-mini", "OPENAI_API_KEY"),
    GEMINI("Gemini", "gemini-3.8-flash", "GEMINI_API_KEY"),
    CLAUDE("Claude", "claude-haiku-5-5", "ANTHROPIC_API_KEY"),
}

data class CommitMessageRequest(
    val provider: AiProvider,
    val model: String,
    val diff: String,
    val style: String = "Conventional Commits",
    val localEndpoint: URI = URI("http://127.0.0.1:11434"),
    val apiKey: String? = null,
    val anthropicWorkspaceId: String? = null,
) {
    override fun toString(): String = "CommitMessageRequest(provider=$provider, model=[redacted], diff=[redacted], " +
        "style=[redacted], localEndpoint=[redacted], apiKey=[redacted], anthropicWorkspaceId=[redacted])"
}

class AiGenerationException(message: String) : RuntimeException(message)
