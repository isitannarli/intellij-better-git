package dev.bettergit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitRemote
import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.HostingClient
import dev.bettergit.core.hosting.HostingProvider
import dev.bettergit.core.hosting.RemoteParser
import dev.bettergit.core.hosting.ReviewRequest
import dev.bettergit.core.hosting.ReviewState
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.services.WorkbenchService
import dev.bettergit.services.HostingCredentials
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel

class ReviewsPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = WorkbenchService.getInstance(project)
    private val client = HostingClient()
    private val credentials = HostingCredentials.resolver()
    private val remote = ComboBox<GitRemote>()
    private val provider = ComboBox(arrayOf("Detect provider", "GitHub", "GitLab"))
    private val state = ComboBox(ReviewState.entries.toTypedArray())
    private val model = DefaultListModel<ReviewRequest>()
    private val list = JBList(model)
    private val detail = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(10) }
    private val status = JBLabel("Uses existing global Git credentials. No sign-in required for public repositories.")
    private val more = Ui.button("Load more") { load(page + 1) }.apply { isEnabled = false }
    private var page = 1
    private var repository: HostedRepository? = null
    private var job: Job? = null
    private var diffJob: Job? = null
    private var generation = 0L
    private var disposed = false

    init {
        border = JBUI.Borders.empty(12)
        remote.renderer = Ui.renderer { label, value: GitRemote, _ -> label.text = value.name }
        add(Ui.row(remote, provider, state, Ui.button("Load PRs / MRs") { load(1) }), BorderLayout.NORTH)
        list.emptyText.text = "Load reviews for the selected remote."
        list.cellRenderer = Ui.renderer(html = true) { label, review: ReviewRequest, _ ->
            label.text = "<html><b>${if (review.draft) "Draft · " else ""}${Ui.html(review.title)}</b><br><small>#${review.number} · ${Ui.html(review.author)} · ${Ui.html(review.sourceBranch)} → ${Ui.html(review.targetBranch)}</small></html>"
            label.border = JBUI.Borders.empty(9, 10)
        }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) detail.text = list.selectedValue?.let { item ->
                "#${item.number}  ${item.title}\n${item.sourceBranch} → ${item.targetBranch}\n${item.author} · ${Ui.date(item.updatedAt)}\n\n${item.description.ifBlank { "No description provided." }}"
            }.orEmpty()
            detail.caretPosition = 0
        }
        add(OnePixelSplitter(true, 0.55f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = JBScrollPane(detail)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(Ui.row(Ui.button("View changes") { openDiff() }, Ui.button("Open in browser") {
                list.selectedValue?.let { BrowserUtil.browse(it.url) }
            }, more), BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        remote.addActionListener { invalidateReviews() }
        provider.addActionListener { invalidateReviews() }
        state.addActionListener { invalidateReviews() }
    }

    fun setRepository(snapshot: RepositorySnapshot?) {
        invalidateReviews()
        remote.removeAllItems()
        snapshot?.remotes?.forEach(remote::addItem)
        status.text = if (snapshot?.remotes.isNullOrEmpty()) "No remote configured. Local features are available offline."
            else "Ready. Existing gh / glab / global Git credentials are reused automatically."
    }

    private fun invalidateReviews() {
        ++generation
        job?.cancel(); diffJob?.cancel()
        repository = null; model.clear(); detail.text = ""; more.isEnabled = false
        status.text = "Load reviews for the selected remote."
    }

    private fun load(nextPage: Int) {
        if (disposed) return
        val expected = generation
        val selectedRemote = remote.selectedItem as? GitRemote ?: return
        val hint = when (provider.selectedIndex) { 1 -> HostingProvider.GITHUB; 2 -> HostingProvider.GITLAB; else -> null }
        val repo = RemoteParser.parse(selectedRemote.url, hint)
        if (repo == null) { status.text = "For a self-hosted remote, select GitHub or GitLab above."; return }
        val selectedState = state.selectedItem as ReviewState
        job?.cancel()
        status.text = "Loading ${repo.fullPath}…"
        more.isEnabled = false
        if (nextPage == 1) { model.clear(); detail.text = "" }
        job = service.submit("Load reviews", onSuccess = { result ->
            if (disposed || generation != expected) return@submit
            repository = repo; page = result.page
            model.addAll(result.items.take((MAX_LOADED_REVIEWS - model.size()).coerceAtLeast(0)))
            more.isEnabled = result.hasNextPage && model.size() < MAX_LOADED_REVIEWS
            list.emptyText.text = "No ${selectedState.name.lowercase()} reviews found."
            status.text = "${model.size()} reviews · ${repo.host} · page $page" +
                if (model.size() >= MAX_LOADED_REVIEWS) " · Display limit reached; change filters to narrow results." else ""
        }, onFailure = {
            if (disposed || generation != expected) return@submit
            status.text = it.message ?: "Reviews could not be loaded. Local Git remains available."
            more.isEnabled = nextPage > 1
        }) { client.reviewsPage(repo, credentials.token(repo), selectedState, nextPage) }
    }

    private fun openDiff() {
        if (disposed) return
        val expected = generation
        val repo = repository ?: return
        val review = list.selectedValue ?: return
        diffJob?.cancel()
        diffJob = service.submit("Load review changes", onSuccess = { patch ->
            if (disposed || generation != expected) return@submit
            val file = LightVirtualFile("${repo.name}-${review.number}.diff", patch).apply { isWritable = false }
            FileEditorManager.getInstance(project).openFile(file, true)
        }) { client.diff(repo, review.number, credentials.token(repo)) }
    }

    override fun dispose() {
        disposed = true; ++generation
        job?.cancel(); diffJob?.cancel()
        repository = null; model.clear(); detail.text = ""
    }

    private companion object { const val MAX_LOADED_REVIEWS = 500 }
}
