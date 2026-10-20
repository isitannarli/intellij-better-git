package dev.bettergit.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bettergit.core.ai.AiProvider
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPasswordField

class AiCommitPanelTest : BasePlatformTestCase() {
    fun testSharedSettingsUpdateSummaryWithoutLosingDraftOrSendingDiff() {
        var requests = 0
        var settingsOpened = 0
        var applied: String? = null
        val initial = AiCommitConfiguration(AiProvider.OLLAMA, "local-model", "http://127.0.0.1:11434", "Conventional Commits", "")
        val panel = AiCommitPanel(initial, "Staged changes", true, object : AiCommitPanel.Listener {
            override fun generate() { requests++ }
            override fun cancel() = Unit
            override fun retryDiff() = Unit
            override fun outputEdited() = Unit
            override fun apply(message: String) { applied = message }
            override fun settings() { settingsOpened++ }
        })
        try {
            assertTrue(components(panel).none { it is JComboBox<*> || it is JPasswordField })
            val generate = button(panel, "Generate draft")
            assertFalse(generate.isEnabled)
            panel.setDiff("+change", 7, true, "Ready")
            panel.showDraft("feat: preserve this draft")
            button(panel, "Settings…").doClick()
            val changed = initial.copy(provider = AiProvider.CLAUDE, model = "configured-model", style = "Short subject")
            assertTrue(panel.setConfiguration(changed))
            assertFalse(panel.setConfiguration(changed))
            assertEquals(1, settingsOpened)
            assertEquals(0, requests)
            assertEquals("feat: preserve this draft", panel.draft())
            assertTrue(components(panel).filterIsInstance<JLabel>().any { it.text == "Claude · configured-model" })
            assertTrue(generate.isEnabled)
            generate.doClick()
            assertEquals(1, requests)
            button(panel, "Use in commit").doClick()
            assertEquals(panel.draft(), applied)
        } finally { panel.disposePanel() }
    }

    private fun button(root: Component, text: String) = components(root).filterIsInstance<JButton>().single { it.text == text }
    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(components(it)) }
    }
}
