package dev.bettergit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.bettergit.core.reports.InsightsReport
import dev.bettergit.services.HistorySnapshot
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.BetterGitSettings
import dev.bettergit.settings.BetterGitConfigurable
import dev.bettergit.settings.WorkspaceLayoutState
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JPanel

class BetterGitPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(2))), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val repositories = ComboBox<RepositorySnapshot>()
    private val status = JBLabel("Syncing…")
    private val layoutState = WorkspaceLayoutState.getInstance(project)
    private var latestSnapshot: RepositorySnapshot? = null
    private val historyView = lazy { HistoryPanel(project) }
    private val history by historyView
    private val branchesView = lazy { BranchesPanel(project, ::refresh) }
    private val branches by branchesView
    private val reviewsView = lazy { ReviewsPanel(project).also { view ->
        Disposer.register(this, view)
        view.setRepository(latestSnapshot)
    } }
    private val reviews by reviewsView
    private val reportsView = lazy { ReportsPanel(project) }
    private val reports by reportsView
    private val workflowView = lazy { WorkflowPanel(project) }
    private val workflow by workflowView
    private val insightsView = lazy { InsightsPanel(project).also { Disposer.register(this, it) } }
    private val insights by insightsView
    private val workingView = lazy { WorkingChangesPanel(project, ::refresh, ::showRepositoryActions).also { Disposer.register(this, it) } }
    private val workingChanges by workingView
    private var preparedHistory: HistorySnapshot? = null
    private val renderedHistoryTabs = mutableSetOf<String>()
    private val workspace = WorkspaceTabs(listOf(
        WorkspaceTab("working", "Changes", "Repository overview and working changes") { workingChanges },
        WorkspaceTab("history", "History", "Commit graph and history") { history },
        WorkspaceTab("workflow", "Workflow", "Branch and merge workflow") { workflow },
        WorkspaceTab("insights", "Insights", "Activity insights and heatmap") { insights },
        WorkspaceTab("branches", "Branches", "Branches, tags, stashes and worktrees") { branches },
        WorkspaceTab("reviews", "PR / MR", "GitHub pull requests and GitLab merge requests") { reviews },
        WorkspaceTab("reports", "Reports", "Repository reports") { reports },
    ), layoutState.state.selectedTab, ::selectTab)
    private var refreshJob: Job? = null
    private var detailsJob: Job? = null
    private var refsJob: Job? = null
    private var workingJob: Job? = null
    private var historyLoadedRoot: Path? = null
    private var refsLoadedRoot: Path? = null
    private var workingLoadedRoot: Path? = null
    private val refreshQueue = RepositoryRefreshQueue()
    private var workingReload = false
    private val refreshListener: RepositoryRefreshListener
    private var insightsJob: Job? = null
    private var insightsLoadedRoot: Path? = null
    private var loadGeneration = 0L
    private var updating = false
    private var selectedRoot: Path? = null
    private var reviewContext: Pair<Path, List<dev.bettergit.core.git.GitRemote>>? = null
    private var disposed = false

    init {
        border = JBUI.Borders.empty()
        repositories.renderer = Ui.renderer { label, item: RepositorySnapshot, _ ->
            label.icon = AllIcons.Vcs.BranchNode
            label.text = "${item.status.branch}  ·  ${item.name}"
            label.toolTipText = item.root.toString()
        }
        repositories.minimumSize = java.awt.Dimension(JBUI.scale(80), repositories.preferredSize.height)
        repositories.toolTipText = "Select repository · current branch"
        repositories.accessibleContext.accessibleName = "Repository and current branch"
        status.foreground = Ui.muted
        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(repositories, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                isOpaque = false
                add(status)
                add(WorkingChangesPanel.compactButton(AllIcons.Vcs.BranchNode, "Switch branch…") { workspace.select("branches") })
                add(WorkingChangesPanel.compactButton(AllIcons.General.Settings, "Better Git settings") {
                    BetterGitConfigurable.show(project)
                    refresh()
                })
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(workspace, BorderLayout.CENTER)
        repositories.addActionListener { if (!updating) loadSelected(RepositoryRefreshScope.REPOSITORY) }
        refreshListener = RepositoryRefreshListener(project, ::requestRefresh).also { Disposer.register(this, it) }
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) drainRefresh() else suspendWork()
            }
        }
        refresh()
    }

    private fun selectTab(id: String) {
        if (disposed) return
        layoutState.selectTab(id)
        if (id != "insights") { insightsJob?.cancel(); insightsJob = null }
        loadTab(id)
    }

    private fun loadTab(id: String) {
        if (disposed || !isShowing) return
        when (id) {
            "history", "workflow", "reports" -> loadHistory()
            "working" -> loadWorkingChanges()
            "branches" -> loadRefs()
            "insights" -> loadInsights()
        }
    }

    private fun refresh() {
        requestRefresh(RepositoryRefreshScope.REPOSITORY)
    }

    private fun requestRefresh(scope: RepositoryRefreshScope) {
        if (disposed) return
        refreshQueue.request(scope)
        drainRefresh()
    }

    private fun drainRefresh() {
        if (disposed) return
        val scope = refreshQueue.take(isShowing) ?: return
        val knownRepositories = (0 until repositories.itemCount).map(repositories::getItemAt)
        status.toolTipText = "Syncing local Git state…"
        refreshJob = service.submit("Refresh repositories", onSuccess = { found ->
            if (disposed) return@submit
            refreshJob = null
            refreshQueue.finished()
            refreshListener.setRoots(found.map { it.root })
            val previous = selectedRoot
            updating = true
            repositories.removeAllItems()
            found.forEach(repositories::addItem)
            found.firstOrNull { it.root == previous }?.let { repositories.selectedItem = it }
            updating = false
            if (found.isEmpty()) showEmpty() else loadSelected(scope)
            drainRefresh()
        }, onFailure = {
            refreshJob = null
            refreshQueue.finished()
            status.text = "Sync failed"
            status.toolTipText = it.message ?: "Could not read Git state. Check the IDE Git executable setting."
            drainRefresh()
        }) {
            if (scope == RepositoryRefreshScope.WORKTREE && knownRepositories.isNotEmpty()) {
                val git = service.git
                knownRepositories.map { it.copy(status = git.status(it.root)) }
            } else service.repositories()
        }
    }

    private fun showEmpty() {
        ++loadGeneration
        selectedRoot = null; reviewContext = null; latestSnapshot = null
        insightsJob?.cancel(); insightsLoadedRoot = null
        resetLoads(); clearViews()
        if (reviewsView.isInitialized()) reviews.setRepository(null)
        workspace.clearSummaries()
        status.text = "No repository"
    }

    private fun loadSelected(scope: RepositoryRefreshScope) {
        val snapshot = repositories.selectedItem as? RepositorySnapshot ?: return
        val rootChanged = selectedRoot != snapshot.root
        val fullRefresh = rootChanged || scope == RepositoryRefreshScope.REPOSITORY || latestSnapshot?.status?.branch != snapshot.status.branch
        if (fullRefresh) {
            ++loadGeneration
            resetLoads()
        } else {
            workingLoadedRoot = null
            // Let an in-flight diff finish, then read once more; repeated saves must not starve it.
            workingReload = workingJob?.isActive == true
        }
        selectedRoot = snapshot.root
        latestSnapshot = snapshot
        val context = snapshot.root to snapshot.remotes
        if (context != reviewContext) { if (reviewsView.isInitialized()) reviews.setRepository(snapshot); reviewContext = context }
        if (rootChanged) clearViews()
        workingChanges.setRepository(snapshot)
        workspace.setSummary("working", "${snapshot.status.staged} staged · ${snapshot.status.modified} modified")
        workspace.setSummary("reviews", "${snapshot.remotes.size} remotes")
        if (fullRefresh) {
            listOf("history", "workflow", "branches", "reports").forEach { workspace.setSummary(it, "Not loaded") }
            workspace.setSummary("insights", "Last 365 days")
        }
        status.text = "↑${snapshot.status.ahead} ↓${snapshot.status.behind}"
        status.toolTipText = "${snapshot.status.branch} · ${snapshot.status.ahead} outgoing · ${snapshot.status.behind} incoming · automatic sync"
        loadTab(workspace.selectedId)
    }

    private fun clearViews() {
        if (historyView.isInitialized()) history.clear()
        if (branchesView.isInitialized()) branches.clear()
        if (reportsView.isInitialized()) reports.clear()
        if (workflowView.isInitialized()) workflow.clear()
        if (workingView.isInitialized()) workingChanges.clear()
        if (insightsView.isInitialized()) insights.clear()
    }

    private fun resetLoads() {
        workingReload = false
        preparedHistory = null; renderedHistoryTabs.clear()
        detailsJob?.cancel(); refsJob?.cancel(); workingJob?.cancel(); insightsJob?.cancel()
        detailsJob = null; refsJob = null; workingJob = null; insightsJob = null
        historyLoadedRoot = null; refsLoadedRoot = null; workingLoadedRoot = null; insightsLoadedRoot = null
    }

    private fun loadHistory() {
        val path = selectedRoot ?: return
        if (disposed) return
        if (historyLoadedRoot == path) { renderHistoryTab(); return }
        if (detailsJob?.isActive == true) return
        val generation = loadGeneration
        val limit = BetterGitSettings.getInstance().state.historyLimit
        workspace.setSummary("history", "Loading…")
        workspace.setSummary("workflow", "Loading…")
        workspace.setSummary("reports", "Loading…")
        detailsJob = service.submit("Load repository history", onSuccess = { snapshot ->
            if (disposed || selectedRoot != path || loadGeneration != generation) return@submit
            historyLoadedRoot = path
            preparedHistory = snapshot
            renderHistoryTab()
            val commits = snapshot.commits
            workspace.setSummary("history", "${commits.size} commits")
            workspace.setSummary("workflow", "${commits.size.coerceAtMost(200)} commits")
            workspace.setSummary("reports", "${commits.size} loaded commits")
        }, onFailure = {
            if (!disposed && loadGeneration == generation) {
                listOf("history", "workflow", "reports").forEach { id -> workspace.setSummary(id, "Resync from the Changes menu to retry") }
                showError("History unavailable", it)
            }
        }) { HistorySnapshot.prepare(service.git.log(path, limit = limit, all = true), limit) }
    }

    private fun renderHistoryTab() {
        val path = selectedRoot ?: return
        val snapshot = preparedHistory ?: return
        val tab = workspace.selectedId
        if (tab !in setOf("history", "workflow", "reports") || !renderedHistoryTabs.add(tab)) return
        when (tab) {
            "history" -> history.setCommits(path, snapshot.commits, snapshot.graph)
            "workflow" -> workflow.setCommits(path, snapshot.commits, snapshot.workflow)
            "reports" -> reports.setReport(path, snapshot.report, snapshot.limit)
        }
    }

    private fun loadWorkingChanges() {
        val path = selectedRoot ?: return
        if (disposed || workingLoadedRoot == path || workingJob?.isActive == true) return
        val generation = loadGeneration
        workingReload = false
        workingJob = service.submit("Load working changes", onSuccess = { snapshot ->
            if (disposed || selectedRoot != path || loadGeneration != generation) return@submit
            workingJob = null
            workingLoadedRoot = if (workingReload) null else path
            workingChanges.setChanges(path, snapshot.changes, snapshot.stagedStats, snapshot.workingStats)
            workspace.setSummary("working", "${snapshot.changes.size} files")
            if (workingReload && workspace.selectedId == "working") loadTab("working")
        }, onFailure = {
            if (!disposed && loadGeneration == generation) showError("Changes unavailable", it)
        }) { service.git.workingChangesWithStats(path) }
    }

    private fun loadRefs() {
        val path = selectedRoot ?: return
        if (disposed || refsLoadedRoot == path || refsJob?.isActive == true) return
        val generation = loadGeneration
        workspace.setSummary("branches", "Loading…")
        refsJob = service.submit("Load repository refs", onSuccess = { refs ->
            if (disposed || selectedRoot != path || loadGeneration != generation) return@submit
            refsLoadedRoot = path
            branches.setRefs(path, refs)
            workspace.setSummary("branches", "${refs.branches.size} branches")
        }, onFailure = {
            if (!disposed && loadGeneration == generation) {
                workspace.setSummary("branches", "Resync from the Changes menu to retry")
                showError("Refs unavailable", it)
            }
        }) {
            val git = service.git
            RepositoryRefs(git.branches(path), git.stashes(path), git.worktrees(path), git.tags(path))
        }
    }

    private fun loadInsights() {
        val path = selectedRoot ?: return
        if (disposed || insightsLoadedRoot == path || insightsJob?.isActive == true) return
        val generation = loadGeneration
        insights.showLoading()
        insightsJob = service.submit("Read repository insights", onSuccess = { (history, report) ->
            if (disposed || loadGeneration != generation || selectedRoot != path) return@submit
            insightsLoadedRoot = path
            insights.setData(path, history, report)
            workspace.setSummary("insights", "Local activity")
        }, onFailure = {
            if (!disposed && loadGeneration == generation && selectedRoot == path) {
                insights.showError(it.message ?: "Could not load insights. Use Resync repository in the Changes menu to try again.")
            }
        }) {
            val history = service.git.insightsHistory(path)
            history to InsightsReport.from(history)
        }
    }

    private fun showRepositoryActions(anchor: JButton) {
        val snapshot = latestSnapshot
        JPopupMenu().apply {
            fun action(title: String, enabled: Boolean = snapshot != null, run: () -> Unit) {
                add(JMenuItem(title).apply { isEnabled = enabled; addActionListener { run() } })
            }
            action("Push…") { Ui.nativeAction(project, this@BetterGitPanel, "Vcs.Push") }
            action("Fetch") { snapshot?.let { fetch(it.root) } }
            action("Generate commit message…") { snapshot?.let { AiCommitDialog(project, it.root).show() } }
            addSeparator()
            action("Inspect current line") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.InspectLine") }
            action("Selection history") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.SelectionHistory") }
            action("File history") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.FileHistory") }
            action("File heatmap") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.ToggleHeatmap") }
            action("Compare refs…") { snapshot?.let { compare(it.root) } }
            addSeparator()
            action("Resync repository", true) { refresh() }
        }.show(anchor, 0, anchor.height)
    }

    private fun suspendWork() {
        ++loadGeneration
        refreshJob?.cancel(); refreshJob = null
        refreshQueue.finished()
        refreshQueue.request(RepositoryRefreshScope.REPOSITORY)
        resetLoads()
    }

    private fun fetch(root: Path) {
        status.text = "Fetching…"
        service.submit("Fetch remotes", onSuccess = { refresh() }, onFailure = { showError("Fetch failed", it) }) { service.git.fetch(root) }
    }

    private fun showError(title: String, failure: Throwable) {
        status.text = title
        status.toolTipText = failure.message ?: title
    }

    private fun compare(root: Path) {
        val base = Messages.showInputDialog(project, "Base revision (branch, tag or commit):", "Compare Revisions", null) ?: return
        val head = Messages.showInputDialog(project, "Target revision (branch, tag or commit):", "Compare Revisions", null, "HEAD", null) ?: return
        service.submit("Compare revisions", onSuccess = { patch ->
            OpenFileDescriptor(project, LightVirtualFile("comparison.diff", patch).apply { isWritable = false }).navigate(true)
        }) { service.git.diff(root, base, head) }
    }

    override fun dispose() {
        disposed = true; ++loadGeneration
        refreshJob?.cancel(); resetLoads()
        latestSnapshot = null
        clearViews()
    }
}
