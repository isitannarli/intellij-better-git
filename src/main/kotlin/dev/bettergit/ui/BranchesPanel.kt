package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitBranch
import dev.bettergit.core.git.GitStash
import dev.bettergit.core.git.GitWorktree
import dev.bettergit.services.GitMutationService
import dev.bettergit.services.WorkbenchService
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane

data class RepositoryRefs(val branches: List<GitBranch>, val stashes: List<GitStash>, val worktrees: List<GitWorktree>, val tags: List<String>)

class BranchesPanel(private val project: Project, private val refresh: () -> Unit) : JPanel(BorderLayout()) {
    private val service = WorkbenchService.getInstance(project)
    private var root: Path? = null
    private val branches = DefaultListModel<GitBranch>()
    private val stashes = DefaultListModel<GitStash>()
    private val worktrees = DefaultListModel<GitWorktree>()
    private val tags = DefaultListModel<String>()
    private val branchList = JBList(branches)
    private val stashList = JBList(stashes)
    private val mutationButtons = mutableListOf<JButton>()
    private var mutationRunning = false

    init {
        border = JBUI.Borders.empty(12)
        branchList.cellRenderer = Ui.renderer { label, item: GitBranch, _ ->
            label.text = "${if (item.current) "● " else "   "}${item.name}${if (item.remote) "  · remote" else ""}${if (item.ahead + item.behind > 0) "  ↑${item.ahead} ↓${item.behind}" else ""}"
            label.border = JBUI.Borders.empty(7, 10)
        }
        stashList.cellRenderer = Ui.renderer { label, item: GitStash, _ -> label.text = "${item.ref} · ${item.subject}" }
        val worktreeList = JBList(worktrees).apply {
            cellRenderer = Ui.renderer(html = true) { label, item: GitWorktree, _ ->
                label.text = "<html><b>${Ui.html(item.branch.ifBlank { "Detached HEAD" })}</b>${if (item.locked) " · locked" else ""}<br>${Ui.html(item.path.toString())}</html>"
                label.border = JBUI.Borders.empty(8)
            }
        }
        branchList.emptyText.text = "No branches yet."
        stashList.emptyText.text = "Your stash is empty."
        add(JTabbedPane().apply {
            addTab("Branches", JPanel(BorderLayout(0, JBUI.scale(10))).apply {
                add(JBScrollPane(branchList), BorderLayout.CENTER)
                add(Ui.row(mutationButton("Switch") { checkout() }, mutationButton("New branch…") { createBranch() },
                    Ui.button("IDE branch menu…", "Choose a repository and merge or rebase using the native Git menu") { Ui.nativeAction(project, this@BranchesPanel, "Git.Branches") }), BorderLayout.SOUTH)
            })
            addTab("Stashes", JPanel(BorderLayout(0, JBUI.scale(10))).apply {
                add(JBScrollPane(stashList), BorderLayout.CENTER)
                add(Ui.row(mutationButton("Stash changes…") { stash() }, mutationButton("Apply selected") { applyStash() }), BorderLayout.SOUTH)
            })
            addTab("Worktrees", JBScrollPane(worktreeList))
            addTab("Tags", JBScrollPane(JBList(tags)))
        }, BorderLayout.CENTER)
    }

    fun setRefs(root: Path, refs: RepositoryRefs) {
        this.root = root
        branches.clear(); stashes.clear(); worktrees.clear(); tags.clear()
        refs.branches.forEach(branches::addElement); refs.stashes.forEach(stashes::addElement)
        refs.worktrees.forEach(worktrees::addElement); refs.tags.forEach(tags::addElement)
    }
    fun clear() {
        root = null
        branches.clear(); stashes.clear(); worktrees.clear(); tags.clear()
    }
    private fun checkout() {
        val path = root ?: return
        val branch = branchList.selectedValue ?: return
        if (branch.current) return
        if (branch.remote) {
            Ui.nativeAction(project, this, "Git.Branches")
            return
        }
        mutate(path, "Switch branch") { service.git.checkout(path, branch.name) }
    }
    private fun createBranch() {
        val path = root ?: return
        val name = Messages.showInputDialog(project, "New branch name (created from HEAD, without switching):", "Create Branch", null)?.trim()?.takeIf(String::isNotBlank) ?: return
        mutate(path, "Create branch") { service.git.createBranch(path, name) }
    }
    private fun stash() {
        val path = root ?: return
        val message = Messages.showInputDialog(project, "Stash tracked and untracked changes. Give this stash a name:", "Stash Changes", null) ?: return
        mutate(path, "Stash changes") { service.git.stash(path, message) }
    }
    private fun applyStash() {
        val path = root ?: return
        val stash = stashList.selectedValue ?: return
        mutate(path, "Apply stash") { service.git.applyStash(path, stash.ref) }
    }

    private fun mutate(path: Path, title: String, task: () -> Unit) {
        if (mutationRunning) return
        mutationRunning = true
        mutationButtons.forEach { it.isEnabled = false }
        GitMutationService.getInstance(project).submit(path, title, onSuccess = { mutationFinished() }, onFailure = {
            mutationFinished()
            service.notifyError(title, it)
        }, task = task)
    }

    private fun mutationFinished() {
        // Stash indices may have moved. Do not expose old refs while the asynchronous reload is pending.
        clear()
        mutationRunning = false
        mutationButtons.forEach { it.isEnabled = true }
        refresh()
    }

    private fun mutationButton(text: String, action: () -> Unit): JButton =
        Ui.button(text, action = action).also(mutationButtons::add)
}
