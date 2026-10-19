package dev.bettergit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.bettergit.core.reports.InsightsReport
import dev.bettergit.services.HistorySnapshot
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.BetterGitSettings
import dev.bettergit.settings.WorkspaceLayoutState
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

class BetterGitPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val repositories = ComboBox<RepositorySnapshot>()
    private val status = JBLabel("Discovering Git repositories…")
    private val layoutState = WorkspaceLayoutState.getInstance(project)
    private var latestSnapshot: RepositorySnapshot? = null
    private var overviewRendered: RepositorySnapshot? = null
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
    private val workingView = lazy { WorkingChangesPanel(project).also { Disposer.register(this, it) } }
    private val workingChanges by workingView
    private val overviewView = lazy { ScrollableColumnPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(16) } }
    private val overview by overviewView
    private var preparedHistory: HistorySnapshot? = null
    private val renderedHistoryTabs = mutableSetOf<String>()
    private val workspace = WorkspaceTabs(listOf(
        WorkspaceTab("overview", "Overview", "Repository overview") { JBScrollPane(overview).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(24)
        } },
        WorkspaceTab("working", "Changes", "Working changes") { workingChanges },
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
    private var refreshDebounce: Job? = null
    private var insightsJob: Job? = null
    private var insightsLoadedRoot: Path? = null
    private var loadGeneration = 0L
    private var updating = false
    private var selectedRoot: Path? = null
    private var reviewContext: Pair<Path, List<dev.bettergit.core.git.GitRemote>>? = null
    private var disposed = false

    init {
        border = JBUI.Borders.empty(8, 0)
        repositories.renderer = Ui.renderer { label, item: RepositorySnapshot, _ ->
            label.text = "${item.name}  /  ${item.status.branch}"
            label.toolTipText = item.root.toString()
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(10, 16)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(Ui.label("").apply { icon = IconLoader.getIcon("/icons/betterGit.svg", BetterGitPanel::class.java) }, BorderLayout.WEST)
                    add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                        isOpaque = false
                        add(Ui.heading("Better Git"), BorderLayout.NORTH)
                        add(DashboardStyles.eyebrow("YOUR LOCAL GIT WORKSPACE"), BorderLayout.SOUTH)
                    }, BorderLayout.CENTER)
                }, BorderLayout.WEST)
                add(Ui.row(Ui.iconButton(AllIcons.Actions.Refresh, "Refresh local Git data") { refresh() }, Ui.iconButton(AllIcons.General.Settings, "Better Git settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Better Git")
                }), BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(repositories, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(workspace, BorderLayout.CENTER)
        repositories.addActionListener { if (!updating) loadSelected() }
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            refreshDebounce?.cancel()
            refreshDebounce = service.scope.launch(Dispatchers.EDT) { delay(500); if (!disposed) refresh() }
        })
        refresh()
    }

    private fun selectTab(id: String) {
        if (disposed) return
        layoutState.selectTab(id)
        if (id != "insights") { insightsJob?.cancel(); insightsJob = null }
        loadTab(id)
    }

    private fun loadTab(id: String) {
        when (id) {
            "overview" -> {
                val snapshot = latestSnapshot
                if (snapshot == null) renderEmptyOverview()
                else if (snapshot !== overviewRendered) { renderOverview(snapshot); overviewRendered = snapshot }
            }
            "history", "workflow", "reports" -> loadHistory()
            "working" -> loadWorkingChanges()
            "branches" -> loadRefs()
            "insights" -> loadInsights()
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        status.text = "Refreshing local Git state…"
        refreshJob = service.submit("Refresh repositories", onSuccess = { found ->
            if (disposed) return@submit
            val previous = selectedRoot
            updating = true
            repositories.removeAllItems()
            found.forEach(repositories::addItem)
            found.firstOrNull { it.root == previous }?.let { repositories.selectedItem = it }
            updating = false
            if (found.isEmpty()) showEmpty() else loadSelected()
        }, onFailure = { status.text = it.message ?: "Could not read Git state. Check the IDE Git executable setting." }) { service.repositories() }
    }

    private fun showEmpty() {
        ++loadGeneration
        selectedRoot = null; reviewContext = null; latestSnapshot = null; overviewRendered = null
        insightsJob?.cancel(); insightsLoadedRoot = null
        resetLoads(); clearViews()
        if (reviewsView.isInitialized()) reviews.setRepository(null)
        workspace.clearSummaries()
        status.text = "No Git repository found."
        if (overviewView.isInitialized() || workspace.selectedId == "overview") renderEmptyOverview()
    }

    private fun renderEmptyOverview() {
        overview.removeAll()
        overview.add(Ui.heading("Your next commit starts here"))
        overview.add(Box.createVerticalStrut(JBUI.scale(12)))
        overview.add(Ui.label("Open a Git project or a folder containing repositories."))
        overview.add(Box.createVerticalStrut(JBUI.scale(8)))
        overview.add(Ui.label("Repositories are discovered directly from .git, including worktrees.", true))
        overview.revalidate(); overview.repaint()
    }

    private fun loadSelected() {
        val snapshot = repositories.selectedItem as? RepositorySnapshot ?: return
        ++loadGeneration
        selectedRoot = snapshot.root
        latestSnapshot = snapshot
        val context = snapshot.root to snapshot.remotes
        if (context != reviewContext) { if (reviewsView.isInitialized()) reviews.setRepository(snapshot); reviewContext = context }
        resetLoads()
        clearViews()
        workspace.setSummary("overview", snapshot.status.branch)
        workspace.setSummary("working", "${snapshot.status.staged} staged · ${snapshot.status.modified} modified")
        workspace.setSummary("reviews", "${snapshot.remotes.size} remotes")
        listOf("history", "workflow", "branches", "reports").forEach { workspace.setSummary(it, "Not loaded") }
        workspace.setSummary("insights", "Last 365 days")
        status.text = "${snapshot.status.branch}  ·  ↑ ${snapshot.status.ahead}  ↓ ${snapshot.status.behind}"
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
                listOf("history", "workflow", "reports").forEach { id -> workspace.setSummary(id, "Refresh to retry") }
                status.text = it.message ?: "History could not be loaded."
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
        workingJob = service.submit("Load working changes", onSuccess = { changes ->
            if (disposed || selectedRoot != path || loadGeneration != generation) return@submit
            workingLoadedRoot = path
            workingChanges.setChanges(path, changes)
            workspace.setSummary("working", "${changes.size} files")
        }, onFailure = {
            if (!disposed && loadGeneration == generation) status.text = it.message ?: "Working changes could not be loaded."
        }) { service.git.workingChanges(path) }
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
                workspace.setSummary("branches", "Refresh to retry")
                status.text = it.message ?: "Repository refs could not be loaded."
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
                insights.showError(it.message ?: "Could not load insights. Use Refresh to try again.")
            }
        }) {
            val history = service.git.insightsHistory(path)
            history to InsightsReport.from(history)
        }
    }

    private fun renderOverview(snapshot: RepositorySnapshot) {
        overview.removeAll()
        overview.add(DashboardStyles.eyebrow("REPOSITORY OVERVIEW"))
        overview.add(Box.createVerticalStrut(JBUI.scale(6)))
        overview.add(Ui.heading(snapshot.name))
        overview.add(Box.createVerticalStrut(JBUI.scale(4)))
        overview.add(Ui.label(snapshot.root.toString(), true))
        overview.add(Box.createVerticalStrut(JBUI.scale(18)))
        overview.add(JPanel(GridLayout(1, 3, JBUI.scale(10), 0)).apply {
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(110))
            add(stat("Staged", snapshot.status.staged)); add(stat("Modified", snapshot.status.modified)); add(stat("Untracked", snapshot.status.untracked))
        })
        overview.add(Box.createVerticalStrut(JBUI.scale(16)))
        overview.add(Ui.section("${snapshot.status.branch}  ·  ↑ ${snapshot.status.ahead}  ↓ ${snapshot.status.behind}", JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(Ui.label(if (snapshot.status.staged + snapshot.status.modified + snapshot.status.untracked == 0) "Working tree is clean." else "Your changes are ready to inspect."), BorderLayout.NORTH)
            add(Ui.row(Ui.button("Commit…") { Ui.nativeAction(project, this@BetterGitPanel, "CheckinProject", "Git.CheckinProject") },
                Ui.button("Push…") { Ui.nativeAction(project, this@BetterGitPanel, "Vcs.Push") },
                Ui.button("Fetch") { fetch(snapshot.root) }), BorderLayout.CENTER)
        }))
        overview.add(Box.createVerticalStrut(JBUI.scale(12)))
        overview.add(Ui.section("Code inspection", Ui.singleLineActions(
            Ui.button("Inspect current line") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.InspectLine") },
            Ui.button("Selection history") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.SelectionHistory") },
            Ui.button("File history") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.FileHistory") },
            Ui.button("File heatmap") { Ui.nativeAction(project, this@BetterGitPanel, "BetterGit.ToggleHeatmap") },
            Ui.button("Compare refs…") { compare(snapshot.root) },
            Ui.button("Generate commit message…") { AiCommitDialog(project, snapshot.root).show() },
        )))
        overview.add(Box.createVerticalStrut(JBUI.scale(12)))
        overview.add(Ui.label(if (snapshot.remotes.isEmpty()) "No remote · local features work offline." else "${snapshot.remotes.size} remotes · ${snapshot.remotes.joinToString { it.name }}", true))
        overview.add(Box.createVerticalStrut(JBUI.scale(12)))
        overview.add(Box.createVerticalGlue())
        overview.components.filterIsInstance<javax.swing.JComponent>().forEach { it.alignmentX = 0f }
        overview.revalidate(); overview.repaint()
    }

    private fun stat(title: String, count: Int) = DashboardStyles.card(BorderLayout(0, JBUI.scale(6)), count > 0).apply {
        add(DashboardStyles.eyebrow(title.uppercase()), BorderLayout.NORTH)
        add(Ui.label(count.toString()).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 14)
            foreground = if (count > 0) DashboardStyles.accent else Ui.muted
        }, BorderLayout.CENTER)
    }

    private fun fetch(root: Path) {
        status.text = "Fetching remotes…"
        service.submit("Fetch remotes", onSuccess = { refresh() }, onFailure = { status.text = it.message ?: "Fetch failed." }) { service.git.fetch(root) }
    }

    private fun compare(root: Path) {
        val base = Messages.showInputDialog(project, "Base revision (branch, tag or commit):", "Compare Revisions", null) ?: return
        val head = Messages.showInputDialog(project, "Target revision (branch, tag or commit):", "Compare Revisions", null, "HEAD", null) ?: return
        service.submit("Compare revisions", onSuccess = { patch ->
            FileEditorManager.getInstance(project).openFile(LightVirtualFile("comparison.diff", patch).apply { isWritable = false }, true)
        }) { service.git.diff(root, base, head) }
    }

    override fun dispose() {
        disposed = true; ++loadGeneration
        refreshJob?.cancel(); refreshDebounce?.cancel(); resetLoads()
        latestSnapshot = null; overviewRendered = null
        clearViews()
    }
}
