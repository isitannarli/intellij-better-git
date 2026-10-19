package dev.bettergit.ui.graph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.bettergit.core.git.GitCommit
import dev.bettergit.services.AuthorAvatarService
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.BetterGitSettings
import dev.bettergit.ui.CommitDetails
import dev.bettergit.ui.CommitHostingActions
import dev.bettergit.ui.Ui
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.Timer

class GraphWorkspacePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private var closed = false
    private var dirty = true
    private var generation = 0L
    private var job: Job? = null
    private var searchJob: Job? = null
    private var repositories = emptyList<RepositorySnapshot>()
    private var requestedRoot: Path? = null
    private var scope = GraphScope("All branches", null)
    private var limit = 500
    private val avatarJobs = mutableMapOf<String, Job>()
    private val attempted = LinkedHashSet<String>()
    private val refreshTimer = Timer(600) { if (isShowing && !closed) refresh() }.apply { isRepeats = false }
    private val avatarTimer = Timer(100) { loadAvatars() }.apply { isRepeats = false }
    private val view: CommitGraphView = CommitGraphView(object : CommitGraphView.Listener {
        override fun repositoryChanged(repository: RepositorySnapshot) {
            if (repository.root == requestedRoot) return
            requestedRoot = repository.root; scope = GraphScope("All branches", null); limit = 500
            load(repository.root)
        }
        override fun scopeChanged(scope: GraphScope) {
            this@GraphWorkspacePanel.scope = scope; limit = 500
            requestedRoot?.let(::load)
        }
        override fun refresh() { this@GraphWorkspacePanel.refresh() }
        override fun fetch() { fetchSelected() }
        override fun push() { Ui.nativeAction(project, this@GraphWorkspacePanel, "Vcs.Push") }
        override fun settings() {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Better Git")
            attempted.clear()
            if (!BetterGitSettings.getInstance().state.authorAvatars) { cancelAvatarWaiters(); view.clearAvatars() }
            else avatarTimer.restart()
        }
        override fun more() { limit = (limit + 500).coerceAtMost(5000); requestedRoot?.let(::load) }
        override fun search(query: String) { this@GraphWorkspacePanel.search(query) }
        override fun inspect(commit: GitCommit) { view.snapshot?.repository?.root?.let { CommitDetails.show(project, it, commit) } }
        override fun copy(commit: GitCommit) { CopyPasteManager.getInstance().setContents(StringSelection(commit.hash)) }
        override fun openCommit(commit: GitCommit) { view.snapshot?.repository?.root?.let { CommitHostingActions.openCommit(project, it, commit.hash) } }
        override fun openReviews(commit: GitCommit) { view.snapshot?.repository?.root?.let { CommitHostingActions.openReviews(project, it, commit.hash) } }
        override fun viewportChanged() { avatarTimer.restart() }
    })

    init {
        add(view, BorderLayout.CENTER)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing && !closed) {
                    if (dirty || view.snapshot == null) refresh() else { search(view.search.text.trim()); avatarTimer.restart() }
                } else suspendWork()
            }
        }
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
            service.scope.launch(Dispatchers.EDT) {
                if (closed || (requestedRoot != null && repository.root.toNioPath() != requestedRoot)) return@launch
                dirty = true
                if (isShowing) refreshTimer.restart()
            }
        })
    }

    private fun refresh() {
        if (closed || !isShowing) { dirty = true; return }
        refreshTimer.stop()
        val root = requestedRoot
        begin()
        val expected = generation
        job = service.submit("Find local repositories", onSuccess = { roots ->
            if (!active(expected)) return@submit
            repositories = roots
            val selected = roots.firstOrNull { it.root == root } ?: roots.firstOrNull()
            view.setRepositories(roots, selected?.root)
            if (selected == null) {
                requestedRoot = null; view.clear(); dirty = false
            } else {
                if (selected.root != root) scope = GraphScope("All branches", null)
                requestedRoot = selected.root
                load(selected.root)
            }
        }, onFailure = { failure -> if (active(expected)) { dirty = true; view.failed(failure.message ?: "Try refreshing the graph.") } }) {
            service.repositories()
        }
    }

    private fun load(root: Path) {
        if (closed || !isShowing) { dirty = true; return }
        refreshTimer.stop(); begin()
        val expected = generation; val selectedScope = scope; val count = limit
        job = service.submit("Read commit graph", onSuccess = { result ->
            if (active(expected)) show(result, selectedScope)
        }, onFailure = { failure -> if (active(expected)) { dirty = true; view.failed(failure.message ?: "Try refreshing the graph.") } }) {
            val git = service.git
            val repo = RepositorySnapshot(root, git.status(root), git.remotes(root))
            GraphSnapshot(repo, git.graphHistory(root, count, selectedScope.revision), git.stashes(root), git.worktrees(root))
        }
    }
    private fun begin() {
        ++generation; job?.cancel(); searchJob?.cancel(); cancelAvatarWaiters(); attempted.clear()
        dirty = true; view.loading()
    }
    private fun show(snapshot: GraphSnapshot, selectedScope: GraphScope) {
        dirty = false
        scope = if (snapshot.repository.root == requestedRoot) selectedScope else GraphScope("All branches", null)
        view.showSnapshot(snapshot, scope)
        search(view.search.text.trim())
        avatarTimer.restart()
    }
    private fun fetchSelected() {
        val root = requestedRoot ?: return
        begin(); val expected = generation
        job = service.submit("Fetch repository", onSuccess = { if (active(expected)) refresh() }, onFailure = {
            if (active(expected)) { view.failed(it.message ?: "Fetch failed."); dirty = true }
        }) { service.git.fetch(root) }
    }
    private fun search(query: String) {
        searchJob?.cancel()
        val snapshot = view.snapshot ?: return
        val expected = generation
        searchJob = service.submit("Search commit graph", onSuccess = { matches ->
            if (active(expected) && view.snapshot === snapshot) view.applyMatches(query, matches)
        }, onFailure = {  }) { snapshot.matches(query) }
    }
    private fun active(expected: Long) = !closed && !project.isDisposed && isShowing && generation == expected
    private fun loadAvatars() {
        if (closed || !isShowing || dirty || !BetterGitSettings.getInstance().state.authorAvatars) return
        val snapshot = view.snapshot ?: return
        val expected = generation
        for (commit in view.visibleCommits()) {
            val key = commit.email.lowercase()
            if (key in attempted || key in avatarJobs) continue
            if (avatarJobs.size >= 8) break
            attempted += key
            while (attempted.size > 512) attempted.remove(attempted.first())
            avatarJobs[key] = service.scope.launch(Dispatchers.EDT, start = CoroutineStart.LAZY) {
                try {
                    val image = project.service<AuthorAvatarService>().avatar(snapshot.repository.root, commit)
                    if (image != null && active(expected) && view.snapshot === snapshot) view.avatar(commit.email, image)
                } finally {
                    if (avatarJobs[key] === currentCoroutineContext()[Job]) avatarJobs.remove(key)
                    if (active(expected)) avatarTimer.restart()
                }
            }.also { it.start() }
        }
    }
    private fun cancelAvatarWaiters() {
        avatarTimer.stop(); avatarJobs.values.toList().forEach { it.cancel() }; avatarJobs.clear()
    }
    private fun suspendWork() {
        ++generation
        if (job?.isActive == true) dirty = true
        job?.cancel(); searchJob?.cancel(); refreshTimer.stop(); cancelAvatarWaiters()
        attempted.clear(); view.clearAvatars(); view.stop(); view.idle()
    }
    override fun dispose() { closed = true; suspendWork(); repositories = emptyList(); attempted.clear(); view.clear() }
}
