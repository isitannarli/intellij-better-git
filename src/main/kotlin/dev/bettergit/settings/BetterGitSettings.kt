package dev.bettergit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "BetterGitSettings", storages = [Storage("betterGit.xml")])
class BetterGitSettings : PersistentStateComponent<BetterGitSettings.Options> {
    data class Options(
        var inlineBlame: Boolean = true,
        var historyLimit: Int = 200,
        var autoFetch: Boolean = false,
        var fetchIntervalMinutes: Int = 15,
        var notifyBehind: Boolean = true,
        var conventionalCommits: Boolean = false,
        var subjectLimit: Int = 72,
        var authorAvatars: Boolean = true,
    )

    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) {
        options = state.copy(
            historyLimit = state.historyLimit.coerceIn(50, 2000),
            fetchIntervalMinutes = state.fetchIntervalMinutes.coerceIn(5, 120),
            subjectLimit = state.subjectLimit.coerceIn(20, 200),
        )
    }

    companion object { fun getInstance(): BetterGitSettings = service() }
}
