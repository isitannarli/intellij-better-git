package dev.bettergit.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.bettergit.core.ai.AiProvider
import dev.bettergit.core.ai.localEndpoint
import dev.bettergit.ui.Ui
import java.awt.CardLayout
import java.awt.Dimension
import java.net.URI
import javax.swing.JPanel
import javax.swing.JPasswordField

/** Edits the existing AI state; credentials are only written when Settings is applied. */
internal class AiCommitSettingsUi {
    private val provider = ComboBox(AiProvider.entries.toTypedArray()).apply {
        renderer = Ui.renderer { label, value: AiProvider, _ -> label.text = value.displayName }
        accessibleContext.accessibleName = "AI provider"
    }
    private val style = JBTextField()
    private val endpoint = JBTextField()
    private val workspace = JBTextField()
    private val models = AiProvider.entries.associateWith { JBTextField() }
    private val keys = AiProvider.entries.filter { it != AiProvider.OLLAMA }.associateWith {
        JPasswordField().apply { accessibleContext.accessibleName = "${it.displayName} API key" }
    }
    private val removals = keys.keys.associateWith { JBCheckBox("Remove saved ${it.displayName} key on Apply") }
    private val cards = object : JPanel(CardLayout()) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(size.width, components.firstOrNull { it.isVisible }?.preferredSize?.height ?: size.height)
        }
    }.apply {
        for (choice in AiProvider.entries) {
            val form = FormBuilder.createFormBuilder().addLabeledComponent("${choice.displayName} model:", models.getValue(choice))
            if (choice == AiProvider.OLLAMA) {
                form.addLabeledComponent("Local Ollama endpoint:", endpoint)
                    .addComponent(help("Uses an installed local model. No API key is needed."))
            } else {
                form.addLabeledComponent("${choice.displayName} API key:", keys.getValue(choice))
                    .addComponent(removals.getValue(choice))
                    .addComponent(help("Leave blank to keep the saved key. New keys are saved in the IDE password store on Apply. ${choice.environmentVariable} takes precedence over a saved key."))
                if (choice == AiProvider.CLAUDE) form.addLabeledComponent("Anthropic workspace ID (optional):", workspace)
            }
            add(form.panel, choice.name)
        }
    }
    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("AI provider:", provider)
        .addLabeledComponent("Message style:", style)
        .addComponent(cards)
        .addComponent(help("Cloud providers receive the previewed diff only when you choose Generate draft. Provider API charges may apply."))
        .panel

    init {
        provider.addActionListener {
            (cards.layout as CardLayout).show(cards, (provider.selectedItem as AiProvider).name)
            panel.revalidate()
            panel.repaint()
        }
        removals.forEach { (choice, checkbox) ->
            checkbox.addItemListener { keys.getValue(choice).isEnabled = !checkbox.isSelected }
        }
    }

    fun values() = AiCommitSettings.Options(
        provider = (provider.selectedItem as AiProvider).name,
        ollamaModel = models.getValue(AiProvider.OLLAMA).text.trim(),
        openaiModel = models.getValue(AiProvider.OPENAI).text.trim(),
        geminiModel = models.getValue(AiProvider.GEMINI).text.trim(),
        claudeModel = models.getValue(AiProvider.CLAUDE).text.trim(),
        localEndpoint = endpoint.text.trim(),
        style = style.text.trim(),
        anthropicWorkspaceId = workspace.text.trim(),
    )

    fun validate() {
        val values = values()
        fun checkText(text: String, limit: Int, label: String, optional: Boolean = false) {
            if ((!optional && text.isBlank()) || text.length > limit || text.any(Char::isISOControl)) {
                throw ConfigurationException("Enter a valid $label (at most $limit characters).")
            }
        }
        AiProvider.entries.forEach { checkText(values.model(it), 128, "${it.displayName} model") }
        checkText(values.style, 200, "message style")
        checkText(values.anthropicWorkspaceId, 128, "Anthropic workspace ID", optional = true)
        if (values.localEndpoint.length > 256 || runCatching { localEndpoint(URI(values.localEndpoint)) }.isFailure) {
            throw ConfigurationException("Enter a local Ollama endpoint, such as http://127.0.0.1:11434.")
        }
        keys.forEach { (choice, field) ->
            val characters = field.password
            try {
                if (!removals.getValue(choice).isSelected && characters.isNotEmpty() &&
                    (characters.size > 8192 || characters.any { it.code !in 33..126 })) {
                    throw ConfigurationException("The ${choice.displayName} API key contains invalid characters or is too long.")
                }
            } finally { characters.fill('\u0000') }
        }
    }

    fun hasCredentialEdits(): Boolean = removals.values.any { it.isSelected } || keys.values.any { field ->
        val characters = field.password
        try { characters.isNotEmpty() } finally { characters.fill('\u0000') }
    }

    /** Snapshots Swing inputs on EDT. Call the writer on a background thread. */
    fun credentialEdits(): Map<AiProvider, String?> = buildMap {
        this@AiCommitSettingsUi.keys.forEach { (choice, field) ->
            val characters = field.password
            try {
                if (removals.getValue(choice).isSelected) put(choice, null)
                else if (characters.isNotEmpty()) put(choice, String(characters))
            } finally { characters.fill('\u0000') }
        }
    }

    fun reset(values: AiCommitSettings.Options) {
        models.forEach { (choice, input) -> input.text = values.model(choice) }
        style.text = values.style
        endpoint.text = values.localEndpoint
        workspace.text = values.anthropicWorkspaceId
        provider.selectedItem = values.selectedProvider()
        clearCredentials()
    }

    fun clearCredentials() {
        keys.values.forEach { field ->
            field.password.fill('\u0000')
            field.text = ""
        }
        removals.values.forEach { it.isSelected = false }
    }

    companion object {
        fun help(text: String) = JBTextArea(text).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(2, 0, 6, 0)
        }
    }
}
