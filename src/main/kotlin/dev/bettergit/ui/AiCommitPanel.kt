package dev.bettergit.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.ai.AiProvider
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

internal data class AiCommitConfiguration(
    val provider: AiProvider,
    val model: String,
    val localEndpoint: String,
    val style: String,
    val anthropicWorkspaceId: String,
)

internal class AiCommitPanel(
    initial: AiCommitConfiguration,
    scopeLabel: String,
    private val canApply: Boolean,
    private val listener: Listener,
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    interface Listener {
        fun generate()
        fun cancel()
        fun retryDiff()
        fun outputEdited()
        fun apply(message: String)
        fun settings()
    }

    private var configuration = initial
    private var updating = true
    private var generating = false
    private var diffLoading = false
    private var diffReady = false
    private val configurationSummary = Ui.label("")
    private val styleSummary = Ui.label("", true)
    private val settings = Ui.button("Settings…", "Configure AI in Better Git settings") { listener.settings() }
    private val providerBadge = DashboardStyles.pill("LOCAL")
    private val notice = textArea(editable = false).apply {
        isFocusable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty()
        getAccessibleContext().accessibleName = "Data destination and provider costs"
    }
    private val status = textArea(editable = false).apply {
        isFocusable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty()
        getAccessibleContext().accessibleName = "AI commit message status"
    }
    private val progress = JProgressBar().apply {
        isVisible = false
        preferredSize = Dimension(JBUI.scale(100), JBUI.scale(3))
        getAccessibleContext().accessibleName = "AI operation in progress"
    }
    private val output = textArea(editable = true).apply {
        border = JBUI.Borders.empty(14)
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D + 1)
        getAccessibleContext().accessibleName = "Editable commit message draft"
        getAccessibleContext().accessibleDescription = "Review and edit the draft. No commit is created automatically."
    }
    private val preview = textArea(editable = false).apply {
        lineWrap = false
        wrapStyleWord = false
        border = JBUI.Borders.empty(12)
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
        getAccessibleContext().accessibleName = "Captured diff to be sent on Generate"
    }
    private val diffSummary = Ui.label("Reading the selected diff…", true)
    private val tabs = JBTabbedPane()
    private val generate = Ui.button("Generate draft", "Send the captured diff to the selected provider") { listener.generate() }
    private val cancel = Ui.button("Cancel", "Cancel the current read or generation") { listener.cancel() }
    private val retry = Ui.button("Retry diff", "Try reading the captured commit selection again") { listener.retryDiff() }.apply { isVisible = false }
    private val copy = Ui.button("Copy", "Copy the current draft to the clipboard") {
        CopyPasteManager.getInstance().setContents(StringSelection(output.text))
        showStatus("Draft copied. Review it before committing.")
    }
    private val applyButton = Ui.button("Use in commit", "Place this draft in the captured commit message field; does not commit") {
        listener.apply(output.text)
    }.apply { isVisible = canApply }
    val preferredFocus: JComponent get() = generate

    init {
        preferredSize = Dimension(JBUI.scale(820), JBUI.scale(640))
        minimumSize = Dimension(JBUI.scale(620), JBUI.scale(480))
        border = JBUI.Borders.empty(10)
        getAccessibleContext().accessibleName = "AI commit message composer"
        add(DashboardStyles.card(BorderLayout(0, JBUI.scale(10))).apply {
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(DashboardStyles.eyebrow("AI COMMIT MESSAGE"), BorderLayout.NORTH)
                    add(Ui.heading("Describe your changes"), BorderLayout.CENTER)
                    add(Ui.label(clean(scopeLabel), true).apply { toolTipText = "Scope: ${clean(scopeLabel)}" }, BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
                add(providerBadge, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(9))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    isOpaque = false
                    add(configurationSummary, BorderLayout.CENTER)
                    add(settings, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(styleSummary, BorderLayout.CENTER)
                add(notice, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        tabs.addTab("Draft", JBScrollPane(output))
        tabs.addTab("Selected diff", JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(diffSummary, BorderLayout.NORTH)
            add(JBScrollPane(preview), BorderLayout.CENTER)
            add(Ui.label("Captured snapshot · only Generate sends it to a provider", true), BorderLayout.SOUTH)
        })
        tabs.selectedIndex = 1
        add(tabs, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(7))).apply {
            add(progress, BorderLayout.NORTH)
            add(status, BorderLayout.CENTER)
            add(Ui.row(generate, cancel, retry, applyButton, copy), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        output.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!updating) listener.outputEdited()
                updateButtons()
            }
        })
        listOf("control ENTER", "meta ENTER").forEach { shortcut ->
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(shortcut), "generate")
        }
        actionMap.put("generate", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) { if (generate.isEnabled) listener.generate() }
        })
        updating = false
        updateProviderPresentation()
        setDiffLoading()
    }

    fun setConfiguration(value: AiCommitConfiguration): Boolean {
        if (configuration == value) return false
        configuration = value
        updateProviderPresentation()
        return true
    }

    fun draft(): String = output.text

    fun setDiffLoading() {
        diffReady = false
        diffLoading = true
        retry.isVisible = false
        showStatus("Reading the selected diff. No provider has been contacted.")
        updateBusy()
    }

    fun setDiff(text: String, bytes: Int, usable: Boolean, explanation: String) {
        diffLoading = false
        diffReady = usable
        retry.isVisible = false
        preview.text = if (text.length <= MAX_PREVIEW_CHARACTERS) text else text.take(MAX_PREVIEW_CHARACTERS) + "\n… Preview shortened; generation is disabled for this oversized diff."
        preview.caretPosition = 0
        diffSummary.text = "${text.lineSequence().count()} lines  ·  ${formatBytes(bytes)}  ·  captured diff"
        showStatus(explanation)
        updateBusy()
    }

    fun diffFailed(message: String) {
        diffLoading = false
        diffReady = false
        retry.isVisible = true
        showStatus(message)
        updateBusy()
    }

    fun setGenerating(value: Boolean, message: String) {
        generating = value
        showStatus(message)
        updateBusy()
    }

    fun showDraft(message: String) {
        updating = true
        output.text = message
        output.caretPosition = 0
        updating = false
        generating = false
        tabs.selectedIndex = 0
        showStatus("Draft ready. Review and edit it, then ${if (canApply) "use it in the commit field or " else ""}copy it. Nothing has been committed.")
        updateBusy()
    }

    fun showStatus(message: String) { status.text = clean(message).take(1200) }

    fun disposePanel() {
        updating = true
        progress.isIndeterminate = false
        generating = false
        diffLoading = false
    }

    private fun updateProviderPresentation() {
        val selected = configuration.provider
        val local = selected == AiProvider.OLLAMA
        providerBadge.text = if (local) "LOCAL · OLLAMA" else "CLOUD · ${selected.displayName}"
        configurationSummary.text = "${selected.displayName} · ${clean(configuration.model)}"
        configurationSummary.toolTipText = configurationSummary.text
        styleSummary.text = "Message style: ${clean(configuration.style)}"
        notice.text = if (local) "Generate uses your local Ollama installation and an installed local model."
            else "Generate sends the previewed diff to ${selected.displayName}. API usage may be billed by the provider."
        revalidate()
        repaint()
    }

    private fun updateBusy() {
        val busy = generating || diffLoading
        output.isEditable = !generating
        progress.isVisible = busy
        progress.isIndeterminate = busy
        cancel.isEnabled = busy
        updateButtons()
    }

    private fun updateButtons() {
        generate.isEnabled = diffReady && !generating && !diffLoading
        copy.isEnabled = output.text.isNotBlank()
        applyButton.isEnabled = canApply && output.text.isNotBlank() && !generating && !diffLoading
    }

    companion object {
        private const val MAX_PREVIEW_CHARACTERS = 102_400
        private val CONTROLS = Regex("[\\p{Cntrl}\\p{Cf}]")
        private fun clean(text: String) = text.replace(CONTROLS, " ")
        private fun formatBytes(bytes: Int) = if (bytes < 1024) "$bytes B" else "${(bytes * 10L / 1024).toDouble() / 10} KiB"
        private fun textArea(editable: Boolean) = JBTextArea().apply {
            isEditable = editable
            lineWrap = true
            wrapStyleWord = true
        }
    }
}
