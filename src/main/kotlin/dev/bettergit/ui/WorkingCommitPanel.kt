package dev.bettergit.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/** A compact composer; drafts belong to repositories and survive tree refreshes and failures. */
internal class WorkingCommitPanel(
    private val onCommit: (stageAll: Boolean) -> Unit,
    private val onNativeCommit: () -> Unit,
    private val onGenerateMessage: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private val drafts = mutableMapOf<Path, String>()
    private var root: Path? = null
    private var available = false
    private var busy = false
    private var staged = false
    private var hasChanges = false
    private var conflicts = false
    private var submodules = false
    private val shortcut = if (SystemInfo.isMac) "⌘Enter" else "Ctrl+Enter"
    private val input = JBTextArea(1, 1).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        getAccessibleContext().accessibleName = "Commit message"
    }
    private val commitAction = object : AbstractAction("Commit") {
        override fun actionPerformed(event: ActionEvent) { if (isEnabled) onCommit(false) }
    }
    private val stageAllAction = object : AbstractAction("Stage All and Commit…") {
        override fun actionPerformed(event: ActionEvent) { if (isEnabled) onCommit(true) }
    }
    private val nativeCommitAction = object : AbstractAction("Open IDE Commit…") {
        override fun actionPerformed(event: ActionEvent) { if (isEnabled) onNativeCommit() }
    }
    private val commit = object : JBOptionButton(commitAction, arrayOf(stageAllAction, nativeCommitAction)) {
        // Paint one native outline after both segments, so the disabled main action cannot turn half of it gray.
        override fun paintBorder(graphics: Graphics) = Unit
        override fun paintChildren(graphics: Graphics) {
            super.paintChildren(graphics)
            super.paintBorder(graphics)
        }
    }.apply {
        getAccessibleContext().accessibleName = "Commit changes"
        optionTooltipText = "Commit options"
    }
    private val generate = WorkingChangesPanel.compactButton(
        IconLoader.getIcon("/icons/aiCommit.svg", WorkingCommitPanel::class.java), "Generate commit message from staged changes",
    ) { onGenerateMessage() }
    private val feedback = Ui.label("").apply { isVisible = false }

    var message: String
        get() = input.text
        set(value) { input.text = value }

    init {
        isOpaque = false
        val editor = JPanel(BorderLayout()).apply {
            background = input.background
            border = JBUI.Borders.customLine(JBColor.border())
            add(JBScrollPane(input).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(3)
                add(generate, BorderLayout.NORTH)
            }, BorderLayout.EAST)
        }
        add(editor, BorderLayout.NORTH)
        add(commit, BorderLayout.CENTER)
        add(feedback, BorderLayout.SOUTH)
        commit.addPropertyChangeListener("UI") { updateCommitAppearance() }
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                root?.let { drafts[it] = input.text }
                input.rows = input.lineCount.coerceIn(1, 4)
                feedback.isVisible = false
                updateActions()
                revalidate()
            }
        })
        listOf("meta ENTER", "control ENTER").forEach { key ->
            input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "commit")
        }
        input.actionMap.put("commit", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) { if (commitAction.isEnabled) onCommit(false) }
        })
        setRepository(null, null)
    }

    fun setRepository(root: Path?, branch: String?) {
        if (this.root != root) {
            this.root = root
            input.text = drafts[root].orEmpty()
            feedback.isVisible = false
        }
        val destination = branch?.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")?.let { " on \"$it\"" }.orEmpty()
        input.emptyText.text = "Message ($shortcut to commit$destination)"
        input.toolTipText = input.emptyText.text
        available = root != null
        updateActions()
    }

    fun setState(busy: Boolean, staged: Boolean, hasChanges: Boolean, conflicts: Boolean, submodules: Boolean) {
        this.busy = busy
        this.staged = staged
        this.hasChanges = hasChanges
        this.conflicts = conflicts
        this.submodules = submodules
        updateActions()
    }

    fun committed(repository: Path, submittedMessage: String) {
        // A different repository or a newer draft must not be cleared by an older operation.
        if (drafts[repository] == submittedMessage) {
            drafts.remove(repository)
            if (root == repository) input.text = ""
        }
        if (root == repository) showFeedback("Commit created.", false)
    }

    fun showFeedback(text: String, error: Boolean = true) {
        feedback.putClientProperty("html.disable", false)
        feedback.text = "<html>${Ui.html(text).replace("\n", "<br>")}</html>"
        feedback.foreground = if (error) JBColor.namedColor("Label.errorForeground", JBColor.RED) else Ui.muted
        feedback.isVisible = true
        feedback.toolTipText = text
        revalidate()
    }

    fun focusMessage() = input.requestFocusInWindow()

    private fun updateActions() {
        val ready = available && !busy
        input.isEnabled = ready
        commitAction.isEnabled = ready && hasChanges && !conflicts && input.text.isNotBlank() && (staged || !submodules)
        commitAction.putValue(Action.NAME, if (busy) "Working…" else "Commit")
        commit.toolTipText = when {
            !available -> "Select a repository"
            conflicts -> "Resolve conflicts before committing"
            input.text.isBlank() -> "Enter a commit message"
            !hasChanges -> "No changes to commit"
            staged -> "Commit staged changes ($shortcut)"
            submodules -> "Stage submodule changes through the IDE first"
            else -> "Stage all changes and commit after confirmation ($shortcut)"
        }
        // Keep the menu available even when the primary action needs a message or changes.
        // The native main segment follows commitAction's enabled state independently.
        commit.isEnabled = ready
        stageAllAction.isEnabled = ready && input.text.isNotBlank() && !conflicts && hasChanges && !submodules
        nativeCommitAction.isEnabled = ready
        generate.isEnabled = ready && staged && !conflicts
        updateCommitAppearance()
    }

    private fun updateCommitAppearance() {
        val active = commitAction.isEnabled
        // Keep native geometry, focus, popup, and keyboard behavior; tint both segments together.
        commit.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, active)
        for (button in listOf(commit) + commit.components.filterIsInstance<JButton>()) {
            button.putClientProperty("JButton.backgroundColor", if (active) commitGreen else UIUtil.getPanelBackground())
            button.putClientProperty("JButton.borderColor", if (active) commitGreen else quietGreen)
            button.putClientProperty("JButton.textColor", if (active) Color.WHITE else null)
            if (button !== commit) button.isBorderPainted = false
        }
        commit.repaint()
    }

    companion object {
        private val commitGreen = JBColor(Color(0x1F7A35), Color(0x238636))
        private val quietGreen = JBColor(Color(31, 122, 53, 96), Color(108, 203, 139, 96))
    }
}
