package dev.bettergit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "BetterGitWorkspaceLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class WorkspaceLayoutState : PersistentStateComponent<WorkspaceLayoutState.Options> {
    data class Options(var selectedTab: String = "working")
    private var options = Options()
    override fun getState() = options
    // Legacy XML has only expanded; missing or unknown selectedTab uses the default.
    override fun loadState(state: Options) { options = Options(state.selectedTab.takeIf { it in IDS } ?: "working") }
    fun selectTab(id: String) {
        if (id !in IDS) return
        options.selectedTab = id
    }
    companion object {
        val IDS = setOf("overview", "working", "history", "workflow", "insights", "branches", "reviews", "reports")
        fun getInstance(project: Project): WorkspaceLayoutState = project.service()
    }
}
