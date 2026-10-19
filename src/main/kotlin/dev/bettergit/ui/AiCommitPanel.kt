package dev.bettergit.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.ai.AiProvider
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

internal data class AiCommitConfiguration(
    val provider: AiProvider,
    val models: Map<AiProvider, String>,
    val localEndpoint: String,
    val style: String,
    val anthropicWorkspaceId: String,
)

internal data class AiCommitForm(
    val provider: AiProvider,
    val model: String,
    val localEndpoint: String,
    val style: String,
    val apiKey: String,
    val rememberKey: Boolean,
    val anthropicWorkspaceId: String,
) {
    override fun toString() = "AiCommitForm(provider=$provider, apiKey=<redacted>)"
}

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
        fun inputChanged()
        fun outputEdited()
        fun apply(message: String)
        fun forgetKey(provider: AiProvider)
    }

    private val models = initial.models.toMutableMap()
    private var currentProvider = initial.provider
    private var updating = true
    private var generating = false
    private var diffLoading = false
    private var diffReady = false
    private val provider = ComboBox(AiProvider.entries.toTypedArray()).apply {
        selectedItem = initial.provider
        renderer = Ui.renderer { label, value: AiProvider, _ -> label.text = value.displayName }
        getAccessibleContext().accessibleName = "AI provider"
    }
    private val model = JBTextField(models[initial.provider] ?: initial.provider.defaultModel)
    private val endpoint = JBTextField(initial.localEndpoint)
    private val style = JBTextField(initial.style)
    private val workspace = JBTextField(initial.anthropicWorkspaceId)
    private val key = JPasswordField().apply {
        getAccessibleContext().accessibleName = "API key for the selected provider"
        toolTipText = "Leave blank to use the provider environment variable or a saved key."
    }
    private val remember = JBCheckBox("Remember this key in the IDE password store").apply { isSelected = false }
    private val forget = Ui.button("Forget saved key", "Remove only this provider's saved key; environment variables are unchanged") {
        listener.forgetKey(currentProvider)
    }
    private val providerBadge = DashboardStyles.pill("LOCAL")
    private val notice = textArea(editable = false).apply {
        isFocusable = false
        isOpaque = false
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty()
        getAccessibleContext().accessibleName = "Data destination and provider costs"
    }
    private val keyHelp = Ui.label("", true)
    private val endpointField = field("Local Ollama endpoint", endpoint)
    private val workspaceField = field("Anthropic workspace ID (optional)", workspace)
    private val keyFields = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
        isOpaque = false
        add(field("API key (optional here)", key), BorderLayout.NORTH)
        add(keyHelp, BorderLayout.CENTER)
        add(Ui.row(remember, forget).apply { isOpaque = false }, BorderLayout.SOUTH)
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
    val preferredFocus: JComponent get() = provider

    init {
        preferredSize = Dimension(JBUI.scale(940), JBUI.scale(800))
        minimumSize = Dimension(JBUI.scale(740), JBUI.scale(620))
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
                add(JPanel(GridBagLayout()).apply {
                    isOpaque = false
                    add(field("Provider", provider), constraints(0, 0, 0.35))
                    add(field("Model", model), constraints(1, 0, 0.65))
                    add(field("Message style", style), constraints(0, 1, 0.35))
                    add(JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(endpointField, BorderLayout.NORTH)
                        add(workspaceField, BorderLayout.SOUTH)
                    }, constraints(1, 1, 0.65))
                }, BorderLayout.NORTH)
                add(keyFields, BorderLayout.CENTER)
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
        provider.addActionListener {
            if (!updating) {
                updating = true
                models[currentProvider] = model.text.trim()
                currentProvider = provider.selectedItem as AiProvider
                model.text = models[currentProvider]?.takeIf(String::isNotBlank) ?: currentProvider.defaultModel
                clearKey()
                remember.isSelected = false
                updating = false
                updateProviderPresentation()
                listener.inputChanged()
            }
        }
        listOf(model, endpoint, style, workspace).forEach { input ->
            input.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) { if (!updating) listener.inputChanged() }
            })
        }
        key.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { if (!updating) listener.inputChanged() }
        })
        remember.addActionListener { if (!updating) listener.inputChanged() }
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

    fun form(): AiCommitForm {
        val characters = key.password
        return try {
            AiCommitForm(currentProvider, model.text.trim(), endpoint.text.trim(), style.text.trim(), String(characters),
                remember.isSelected, workspace.text.trim())
        } finally { characters.fill('\u0000') }
    }

    fun selectedProvider(): AiProvider = currentProvider
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
        clearKey()
        progress.isIndeterminate = false
        generating = false
        diffLoading = false
    }

    private fun updateProviderPresentation() {
        val local = currentProvider == AiProvider.OLLAMA
        endpointField.isVisible = local
        workspaceField.isVisible = currentProvider == AiProvider.CLAUDE
        keyFields.isVisible = !local
        providerBadge.text = if (local) "LOCAL · OLLAMA" else "CLOUD · ${currentProvider.displayName}"
        keyHelp.text = "Blank uses ${currentProvider.environmentVariable ?: "the provider environment variable"}, then the IDE password store."
        notice.text = if (local) "Generate uses your local Ollama installation. Only loopback endpoints and installed local models are accepted. The plugin is free; no account is needed."
            else "Generate sends the previewed diff to ${currentProvider.displayName}. API usage may be billed by the provider. The plugin is free and does not require a plugin account."
        workspace.toolTipText = "Optional Anthropic workspace identifier; leave blank unless your API configuration requires it."
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

    private fun clearKey() {
        val characters = key.password
        characters.fill('\u0000')
        key.text = ""
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
        private fun field(label: String, input: JComponent): JPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(Ui.label(label, true).apply { labelFor = input }, BorderLayout.NORTH)
            add(input, BorderLayout.CENTER)
            input.getAccessibleContext().accessibleName = label
        }
        private fun constraints(x: Int, y: Int, weight: Double) = GridBagConstraints().apply {
            gridx = x
            gridy = y
            weightx = weight
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(0, if (x == 0) 0 else 12, 9, 0)
        }
    }
}
