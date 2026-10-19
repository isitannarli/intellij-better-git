package dev.bettergit.ui

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.HostingProvider
import dev.bettergit.core.hosting.ReviewPage
import dev.bettergit.core.hosting.ReviewRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel

internal class RelatedReviewsPanel(
    repository: HostedRepository,
    hash: String,
    private val onOpen: (ReviewRequest) -> Unit,
    onCommit: () -> Unit,
    onMore: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private val providerName = if (repository.provider == HostingProvider.GITHUB) "GitHub" else "GitLab"
    private val reviewKind = if (repository.provider == HostingProvider.GITHUB) "PR" else "MR"
    private val model = DefaultListModel<ReviewRequest>()
    private val list = JBList(model)
    private val status = JBTextArea().apply {
        isEditable = false; isFocusable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
        font = UIUtil.getLabelFont(); border = JBUI.Borders.empty()
        getAccessibleContext().accessibleName = "Related review lookup status"
    }
    private val more = Ui.button("Load more", "Load another page of reviews associated by the hosting service", onMore)
    private val open = Ui.button("Open $reviewKind on $providerName", "Open the selected associated review in your browser") { openSelected() }
        .apply { isEnabled = false }
    val preferredFocus: JComponent get() = list

    init {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(400))
        border = JBUI.Borders.empty(10)
        add(DashboardStyles.card(BorderLayout(0, JBUI.scale(8))).apply {
            add(DashboardStyles.eyebrow("RELATED PULL / MERGE REQUESTS"), BorderLayout.NORTH)
            add(Ui.heading(repository.fullPath), BorderLayout.CENTER)
            add(Ui.label("${hash.take(8)} · ${repository.host} · $providerName", true), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.getAccessibleContext().accessibleName = "Reviews associated with this commit"
        list.cellRenderer = Ui.renderer(html = true) { label, review: ReviewRequest, _ ->
            label.text = "<html><b>#${review.number} · ${Ui.html(review.title)}</b><br><small>${Ui.html(review.author)} · ${Ui.html(review.sourceBranch)} → ${Ui.html(review.targetBranch)}</small></html>"
            label.border = JBUI.Borders.empty(9, 10)
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) open.isEnabled = list.selectedValue != null }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) { if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2) openSelected() }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) { if (event.keyCode == KeyEvent.VK_ENTER) openSelected() }
        })
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(status, BorderLayout.NORTH)
            add(Ui.row(open, Ui.button("Open commit on $providerName", "Open this commit in your browser", onCommit), more), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        loading()
    }

    fun loading() {
        status.text = "Finding related ${reviewKind}s using existing Git/CLI credentials…"
        list.emptyText.text = "Looking up this commit…"
        more.isEnabled = false
    }

    fun loaded(page: ReviewPage) {
        if (page.page == 1) model.clear()
        val numbers = (0 until model.size()).map { model[it].url }.toMutableSet()
        page.items.filter { numbers.add(it.url) }.forEach(model::addElement)
        if (model.size() > 0 && list.selectedIndex < 0) list.selectedIndex = 0
        list.emptyText.text = "No associated $reviewKind was returned by $providerName."
        status.text = if (model.isEmpty) "No associated $reviewKind was found for this commit in this remote. Try another remote if this is a fork."
            else "${model.size()} associated ${reviewKind}s · page ${page.page}" + if (page.hasNextPage) " · more results available" else ""
        more.text = "Load more"
        more.isEnabled = page.hasNextPage
    }

    fun failed(message: String) {
        status.text = message
        list.emptyText.text = "Related reviews could not be loaded."
        more.text = "Retry"
        more.isEnabled = true
    }

    private fun openSelected() { list.selectedValue?.let(onOpen) }
}
