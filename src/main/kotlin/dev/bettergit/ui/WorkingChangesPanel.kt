package dev.bettergit.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.tree.ui.Control
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.FileLineStats
import dev.bettergit.core.git.CommitMessagePolicy
import dev.bettergit.core.git.WorkingChange
import dev.bettergit.services.GitMutationService
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.BetterGitSettings
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ToolTipManager
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class WorkingChangesPanel(
    private val project: Project,
    private val onChanged: () -> Unit = {},
    private val onRepositoryActions: (JButton) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(6))), Disposable {
    private var root: Path? = null
    private var branch: String? = null
    private var changes = emptyList<WorkingChange>()
    private var stagedStats = emptyMap<String, FileLineStats>()
    private var workingStats = emptyMap<String, FileLineStats>()
    private var request: Job? = null
    private var generation = 0L
    private var busy = false
    private var disposed = false
    private var filterRequest: Job? = null
    private var filterGeneration = 0L
    private var applyingTree = false
    private var selectionChanges = emptyList<WorkingChange>()
    private val filterTimer = Timer(150) { filter() }.apply { isRepeats = false }
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val files = object : Tree(model) {
        override fun getScrollableTracksViewportWidth() = true

        override fun getToolTipLocation(event: MouseEvent): Point? {
            val text = getToolTipText(event) ?: return null
            val row = getPathForLocation(event.x, event.y)?.let(::getPathBounds) ?: return null
            val tooltipWidth = createToolTip().apply { tipText = text }.preferredSize.width
            val viewport = visibleRect
            val right = (viewport.x + viewport.width - tooltipWidth).coerceAtLeast(viewport.x)
            return Point((event.x + JBUI.scale(12)).coerceIn(viewport.x, right), row.y + row.height)
        }
    }
    private val search = JBTextField().apply { emptyText.text = "Filter files" }
    private val summary = Ui.label("Open a Git project to see its changes.", true)
    private val status = Ui.label("", true).apply {
        isVisible = false
        addPropertyChangeListener("text") { isVisible = !text.isNullOrBlank() }
    }
    private val composer = WorkingCommitPanel(::commitChanges, ::nativeChanges, ::generateCommitMessage)
    private val stage = compactButton(AllIcons.General.Add, "Stage selected files") { mutate(Mutation.STAGE) }
    private val unstage = compactButton(AllIcons.General.Remove, "Unstage selected files") { mutate(Mutation.UNSTAGE) }
    private val discard = compactButton(AllIcons.Actions.Rollback, "Discard selected working changes…") { mutate(Mutation.DISCARD) }
    private val stash = compactButton(AllIcons.Actions.MenuSaveall, "Stash selected files…") { mutate(Mutation.STASH) }
    private val contextActions = createContextActions()

    init {
        border = JBUI.Borders.empty(8)
        val more = compactButton(AllIcons.Actions.More, "Repository actions") {}
        more.addActionListener { onRepositoryActions(more) }
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(composer, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(summary, BorderLayout.CENTER)
                add(more, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(search, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    listOf(stage, unstage, discard, stash).forEach(::add)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        files.isRootVisible = false
        files.showsRootHandles = true
        files.border = JBUI.Borders.empty()
        files.rowHeight = JBUI.scale(22)
        files.isLargeModel = true
        files.putClientProperty(Control.Painter.KEY, ChangesTreePainter)
        files.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        files.cellRenderer = WorkingChangeRenderer(project) { root }
        files.putClientProperty(com.intellij.ui.render.RenderingHelper.SHRINK_LONG_RENDERER, true)
        // Full-width rows already fit the viewport; expanding them on hover creates an overflowing duplicate.
        files.setExpandableItemsEnabled(false)
        files.emptyText.text = "Open a Git project to see its changes."
        files.accessibleContext.accessibleName = "Changed files by folder and staging area"
        files.addTreeSelectionListener {
            if (!applyingTree) { selectionChanges = collectSelectedChanges(); updateActions() }
        }
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2 && files.getPathForLocation(e.x, e.y) != null) openDiff()
            }
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        })
        files.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) { openDiff(); e.consume() }
                if (e.keyCode == KeyEvent.VK_CONTEXT_MENU || (e.keyCode == KeyEvent.VK_F10 && e.isShiftDown)) {
                    val bounds = files.selectionPath?.let(files::getPathBounds)
                    showContextMenu(bounds?.x ?: 0, bounds?.let { it.y + it.height } ?: 0)
                    e.consume()
                }
            }
        })
        ToolTipManager.sharedInstance().registerComponent(files)
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                ++filterGeneration
                filterRequest?.cancel()
                filterTimer.restart()
            }
        })
        add(JBScrollPane(files).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        updateActions()
    }

    fun setRepository(snapshot: RepositorySnapshot) {
        if (root != snapshot.root) clear()
        root = snapshot.root
        branch = snapshot.status.branch
        composer.setRepository(root, branch)
        summary.toolTipText = "${snapshot.root} · ${snapshot.status.branch} · ${snapshot.remotes.size} remotes"
        updateActions()
    }

    fun setChanges(
        root: Path,
        changes: List<WorkingChange>,
        stagedStats: Map<String, FileLineStats> = emptyMap(),
        workingStats: Map<String, FileLineStats> = emptyMap(),
    ) {
        if (this.root == root && this.changes == changes && this.stagedStats == stagedStats && this.workingStats == workingStats && changes.isNotEmpty()) return
        if (this.root != root) clear()
        this.root = root
        composer.setRepository(root, branch)
        this.changes = changes
        this.stagedStats = stagedStats
        this.workingStats = workingStats
        summary.text = if (changes.isEmpty()) "Working tree is clean" else
            "${changes.size} ${if (changes.size == 1) "file" else "files"} · ${changes.count { it.staged }} staged"
        filter()
    }

    fun clear() {
        ++generation
        request?.cancel()
        ++filterGeneration
        filterRequest?.cancel(); filterRequest = null; filterTimer.stop()
        selectionChanges = emptyList()
        root = null; branch = null; changes = emptyList(); stagedStats = emptyMap(); workingStats = emptyMap()
        composer.setRepository(null, null)
        model.setRoot(DefaultMutableTreeNode())
        summary.text = "Open a Git project to see its changes."
        files.emptyText.text = summary.text
        status.text = ""
        updateActions()
    }

    private fun nodes(): List<DefaultMutableTreeNode> = (model.root as DefaultMutableTreeNode)
        .preorderEnumeration().toList().map { it as DefaultMutableTreeNode }

    private fun filter() {
        filterTimer.stop()
        filterRequest?.cancel()
        val expected = ++filterGeneration
        val query = search.text.trim()
        val allChanges = changes
        val staged = stagedStats
        val working = workingStats
        val prepare = {
            val matching = allChanges.filter { query.isEmpty() || it.path.contains(query, true) || it.oldPath?.contains(query, true) == true }
            WorkingChangesTree.build(matching.take(5_000), staged, working) to matching.size
        }
        if (allChanges.size <= 200) {
            val (tree, count) = prepare()
            applyTree(tree, count, query)
        } else {
            filterRequest = WorkbenchService.getInstance(project).submit("Filter changed files", onSuccess = { (tree, count) ->
                if (!disposed && filterGeneration == expected) applyTree(tree, count, query)
            }, onFailure = { failure ->
                if (!disposed && filterGeneration == expected) status.text = failure.message ?: "Could not filter changed files."
            }, task = prepare)
        }
    }

    private fun applyTree(tree: DefaultMutableTreeNode, matchingCount: Int, query: String) {
        // Capture expansion and selection at delivery time so input during loading is preserved.
        val previous = nodes().mapNotNull { node -> (node.userObject as? ChangeTreeEntry)?.let { it.key to files.isExpanded(TreePath(node.path)) } }.toMap()
        val selection = files.selectionPaths.orEmpty().mapNotNull { entry(it)?.key }.toSet()
        val scroll = files.visibleRect
        applyingTree = true
        try {
            model.setRoot(tree)
            val restored = mutableListOf<TreePath>()
            for (node in nodes()) {
                val value = node.userObject as? ChangeTreeEntry ?: continue
                val path = TreePath(node.path)
                val collapsedAncestor = node.path.dropLast(1).any { ancestor ->
                    val key = ((ancestor as DefaultMutableTreeNode).userObject as? ChangeTreeEntry)?.key
                    previous[key] == false
                }
                if (value.change == null && (query.isNotEmpty() || (previous[value.key] != false && !collapsedAncestor))) files.expandPath(path)
                if (value.key in selection) restored += path
            }
            files.selectionPaths = restored.toTypedArray()
            files.scrollRectToVisible(scroll)
        } finally {
            applyingTree = false
            selectionChanges = collectSelectedChanges()
        }
        files.emptyText.text = if (changes.isEmpty()) "Working tree is clean." else "No files match this filter."
        if (!busy) status.text = if (matchingCount > 5_000) "Showing 5,000 files. Filter to see more." else
            ""
        updateActions()
    }

    private fun entry(path: TreePath?): ChangeTreeEntry? = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ChangeTreeEntry
    private fun selectedEntries() = files.selectionPaths.orEmpty().mapNotNull(::entry)
    private fun selectedChanges() = selectionChanges
    private fun collectSelectedChanges() = files.selectionPaths.orEmpty().flatMap {
        WorkingChangesTree.changes(it.lastPathComponent as DefaultMutableTreeNode)
    }.distinctBy { it.path }

    private fun enabled(operation: Mutation): Boolean {
        val selected = selectedChanges()
        if (root == null || busy || selected.isEmpty() || selected.any { it.submodule }) return false
        val areas = selectedEntries().map { it.area }
        return when (operation) {
            Mutation.STAGE -> ChangeArea.STAGED !in areas && selected.all { it.unstaged }
            Mutation.UNSTAGE -> areas.all { it == ChangeArea.STAGED } && selected.all { it.staged }
            Mutation.DISCARD -> areas.all { it == ChangeArea.WORKING } && selected.all { !it.untracked && !it.conflicted }
            Mutation.STASH -> selected.none { it.conflicted }
            Mutation.DELETE -> selected.none { it.conflicted || it.workTreeStatus == 'D' || (it.indexStatus == 'D' && !it.untracked) }
        }
    }

    private fun updateActions() {
        composer.setState(busy, changes.any { it.staged }, changes.isNotEmpty(), changes.any { it.conflicted }, changes.any { it.submodule })
        stage.isEnabled = enabled(Mutation.STAGE)
        unstage.isEnabled = enabled(Mutation.UNSTAGE)
        discard.isEnabled = enabled(Mutation.DISCARD)
        stash.isEnabled = enabled(Mutation.STASH)
    }

    private fun popup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val path = files.getPathForLocation(event.x, event.y) ?: return
        if (!files.isPathSelected(path)) files.selectionPath = path
        showContextMenu(event.x, event.y)
    }

    private fun showContextMenu(x: Int, y: Int) {
        ActionManager.getInstance().createActionPopupMenu("BetterGit.WorkingChanges", contextActions).apply {
            setTargetComponent(files)
        }.component.show(files, x, y)
    }

    private fun createContextActions() = DefaultActionGroup().apply {
        add(contextAction("Show Diff", AllIcons.Actions.Diff, CommonShortcuts.getDiff(),
            { !busy && files.selectionCount == 1 && entry(files.selectionPath)?.change != null }, ::openDiff))
        add(contextAction("Jump to Source", AllIcons.Actions.EditSource, CommonShortcuts.getEditSource(),
            { files.selectionCount == 1 && entry(files.selectionPath)?.change != null }, ::openFile))
        addSeparator()
        for (operation in Mutation.entries.filter { it != Mutation.DELETE }) {
            add(contextAction(operation.title, operation.icon, available = { enabled(operation) }) { mutate(operation) })
        }
        addSeparator()
        add(contextAction(Mutation.DELETE.title, Mutation.DELETE.icon, available = { enabled(Mutation.DELETE) }) { mutate(Mutation.DELETE) })
        addSeparator()
        add(contextAction("Open IDE Commit / Conflicts…", AllIcons.Actions.Commit, available = { !busy }, perform = ::nativeChanges))
    }

    private fun contextAction(
        title: String,
        icon: Icon,
        shortcuts: ShortcutSet? = null,
        available: () -> Boolean,
        perform: () -> Unit,
    ) = object : DumbAwareAction(title, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = !disposed && root != null && available() }
        override fun actionPerformed(e: AnActionEvent) { if (!disposed && root != null && available()) perform() }
    }.apply {
        if (shortcuts != null) registerCustomShortcutSet(shortcuts, files, this@WorkingChangesPanel)
    }

    private fun mutate(operation: Mutation) {
        if (!enabled(operation)) return
        val repository = root ?: return
        val selected = selectedChanges()
        val description = if (selected.size == 1) plain(selected.single().path) else "${selected.size} selected files"
        if (operation == Mutation.DISCARD || operation == Mutation.DELETE) {
            val message = if (operation == Mutation.DISCARD)
                "Discard working changes in $description?\nThe working copy will be restored from the index. Staged changes are kept."
            else "Delete $description from disk?\nStaged content is kept. Untracked files cannot be recovered through Git."
            if (Messages.showYesNoDialog(project, message, operation.title, operation.title.removeSuffix("…"), "Cancel", Messages.getWarningIcon()) != Messages.YES) return
        }
        val message = if (operation == Mutation.STASH) Messages.showInputDialog(project,
            "Stash staged and working changes in $description, including selected untracked files:",
            "Stash Selected Files", null, "WIP: selected files", null)?.takeIf { it.isNotBlank() } ?: return else ""
        busy = true
        status.text = "${operation.title.removeSuffix("…")}…"
        updateActions()
        val git = WorkbenchService.getInstance(project).git
        // The mutation service saves editor buffers, serializes commands, then refreshes VFS and native Git state.
        GitMutationService.getInstance(project).submit(repository, operation.title, onSuccess = {
            busy = false
            if (!disposed) { updateActions(); onChanged() }
        }, onFailure = {
            busy = false
            if (!disposed) {
                status.text = it.message ?: "Operation failed."
                WorkbenchService.getInstance(project).notifyError(operation.title, it)
                updateActions(); onChanged()
            }
        }) {
            when (operation) {
                Mutation.STAGE -> git.stageChanges(repository, selected)
                Mutation.UNSTAGE -> git.unstageChanges(repository, selected)
                Mutation.DISCARD -> git.discardChanges(repository, selected)
                Mutation.STASH -> git.stashChanges(repository, selected, message)
                Mutation.DELETE -> git.deleteWorkingFiles(repository, selected)
            }
        }
    }

    private fun nativeChanges() = Ui.nativeAction(project, this, "CheckinProject", "Git.CheckinProject")

    private fun commitChanges(stageAll: Boolean) {
        val repository = root ?: return
        if (busy || disposed || changes.isEmpty() || changes.any { it.conflicted }) return
        val message = composer.message
        val expectedBranch = branch
        if (message.isBlank()) { composer.focusMessage(); return }
        val settings = BetterGitSettings.getInstance().state
        if (settings.conventionalCommits) {
            CommitMessagePolicy.validate(message.trim(), settings.subjectLimit)?.let {
                composer.showFeedback(it)
                composer.focusMessage()
                return
            }
        }
        val includeAll = stageAll || changes.none { it.staged }
        if (includeAll && Messages.showYesNoDialog(project,
                "Stage and commit all changes in ${repository.fileName}, including untracked files?\n\nThe complete saved content of partially staged files will be included.",
                "Stage All and Commit", "Stage All and Commit", "Cancel", Messages.getQuestionIcon()) != Messages.YES) return
        if (root != repository || branch != expectedBranch) {
            composer.showFeedback("The repository or branch changed. Review the changes and commit again.")
            return
        }
        val git = WorkbenchService.getInstance(project).git
        busy = true
        status.text = "Committing…"
        updateActions()
        GitMutationService.getInstance(project).submit(repository, "Commit changes", onSuccess = {
            busy = false
            if (!disposed) {
                composer.committed(repository, message)
                updateActions()
                onChanged()
            }
        }, onFailure = {
            busy = false
            if (!disposed) {
                if (root == repository) composer.showFeedback(it.message ?: "Commit failed. Your message is kept.")
                WorkbenchService.getInstance(project).notifyError("Commit changes", it)
                updateActions()
                onChanged()
            }
        }) { git.commitChanges(repository, message, includeAll, expectedBranch) }
    }

    private fun generateCommitMessage() {
        val repository = root ?: return
        if (busy || disposed) return
        val originalMessage = composer.message
        AiCommitDialog(project, repository, applyMessage = { draft ->
            if (disposed || busy || root != repository || composer.message != originalMessage) false
            else { composer.message = draft; composer.focusMessage(); true }
        }).show()
    }

    private fun openFile() {
        val repository = root ?: return
        val change = entry(files.selectionPath)?.change ?: return
        val file = LocalFileSystem.getInstance().findFileByNioFile(repository.resolve(change.path))
        if (file != null && !file.isDirectory) OpenFileDescriptor(project, file).navigate(true)
        else status.text = "This file is deleted or unavailable on disk. Open its diff to inspect it."
    }

    private fun openDiff() {
        if (busy || files.selectionCount != 1) return
        val path = root ?: return
        val value = entry(files.selectionPath) ?: return
        val change = value.change ?: return
        if (change.conflicted || change.submodule) { nativeChanges(); return }
        request?.cancel()
        val expected = ++generation
        val index = value.area == ChangeArea.STAGED
        status.text = "Loading ${if (index) "staged" else "working"} diff…"
        val service = WorkbenchService.getInstance(project)
        request = service.submit("Inspect working change", onSuccess = { diff ->
            if (disposed || generation != expected || root != path) return@submit
            val factory = DiffContentFactory.getInstance()
            val type = FileTypeManager.getInstance().getFileTypeByFileName(diff.path)
            val title = if (diff.before == diff.after) "${diff.path} · no text differences (check path or file permissions)" else diff.path
            DiffManager.getInstance().showDiff(project, SimpleDiffRequest(title,
                factory.create(project, diff.before, type), factory.create(project, diff.after, type), diff.beforeLabel, diff.afterLabel))
            status.text = ""
        }, onFailure = {
            if (!disposed && generation == expected) status.text = it.message ?: "Could not read this diff."
        }) { service.git.workingFileDiff(path, change, index) }
    }

    override fun dispose() {
        disposed = true
        ToolTipManager.sharedInstance().unregisterComponent(files)
        clear()
    }

    private fun plain(text: String) = text.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")

    private enum class Mutation(val title: String, val icon: Icon) {
        STAGE("Stage Changes", AllIcons.General.Add),
        UNSTAGE("Unstage Changes", AllIcons.General.Remove),
        DISCARD("Discard Working Changes…", AllIcons.Actions.Rollback),
        STASH("Stash Selected Files…", AllIcons.Vcs.ShelveSilent),
        DELETE("Delete Files…", AllIcons.General.Delete)
    }

    companion object {
        internal fun compactButton(icon: Icon, title: String, action: () -> Unit) = Ui.iconButton(icon, title, action).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2)
        }
    }

    /** Share offsets between native rendering and hit testing, without indenting the group headers. */
    private object ChangesTreePainter : Control.Painter {
        private fun indent(depth: Int) = (depth - 1).coerceAtLeast(0) * JBUI.scale(8)

        override fun getRendererOffset(control: Control, depth: Int, leaf: Boolean): Int = when {
            depth < 0 -> -1
            depth == 0 -> 0
            else -> indent(depth) + control.width + JBUI.scale(2)
        }

        override fun getControlOffset(control: Control, depth: Int, leaf: Boolean) =
            if (depth > 0 && !leaf) indent(depth) else -1

        override fun paint(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int,
                           control: Control, depth: Int, leaf: Boolean, expanded: Boolean, selected: Boolean) {
            val offset = getControlOffset(control, depth, leaf)
            if (offset >= 0) control.paint(component, graphics, x + offset, y, control.width, height, expanded, selected)
        }
    }
}
