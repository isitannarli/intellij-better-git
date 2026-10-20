package dev.bettergit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bettergit.core.ai.AiProvider
import java.awt.Component
import java.awt.Container
import java.util.concurrent.TimeUnit
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JTextField

class BetterGitConfigurableTest : BasePlatformTestCase() {
    private lateinit var originalSettings: BetterGitSettings.Options
    private lateinit var originalAi: AiCommitSettings.Options
    private var configurable: BetterGitConfigurable? = null

    override fun setUp() {
        super.setUp()
        originalSettings = BetterGitSettings.getInstance().state.copy()
        originalAi = AiCommitSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        try {
            configurable?.disposeUIResources()
            BetterGitSettings.getInstance().loadState(originalSettings)
            AiCommitSettings.getInstance().loadState(originalAi)
        } finally {
            super.tearDown()
        }
    }

    fun testBackgroundConstructionAndCallbacksBeforeUiCreationPreserveSettings() {
        val settings = BetterGitSettings.getInstance()
        val saved = BetterGitSettings.Options(
            inlineBlame = false,
            historyLimit = 350,
            autoFetch = true,
            fetchIntervalMinutes = 30,
            notifyBehind = false,
            conventionalCommits = true,
            subjectLimit = 90,
            authorAvatars = false,
        )
        settings.loadState(saved)
        val subject = ApplicationManager.getApplication().executeOnPooledThread<BetterGitConfigurable> {
            check(!ApplicationManager.getApplication().isDispatchThread)
            BetterGitConfigurable()
        }.get(10, TimeUnit.SECONDS)
        configurable = subject

        assertEquals("Better Git", subject.getDisplayName())
        assertFalse(subject.isModified())
        subject.reset()
        subject.apply()
        subject.disposeUIResources()
        assertEquals(saved, settings.state)
        assertFalse(subject.isModified())
    }

    fun testResetAndApplyUsePersistedSettingsAndVisibleControls() {
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        val settings = BetterGitSettings.getInstance()
        val saved = BetterGitSettings.Options()
        settings.loadState(saved)
        val subject = BetterGitConfigurable().also { configurable = it }
        val panel = subject.createComponent()
        assertSame(panel, subject.createComponent())
        assertFalse(subject.isModified())

        checkBox(panel, "Show author and commit beside the current line").isSelected = false
        checkBox(panel, "Load author photos from GitHub/GitLab when a blame hover opens").isSelected = false
        checkBox(panel, "Fetch remotes automatically while the project is open").isSelected = true
        checkBox(panel, "Notify when the current branch falls behind its upstream").isSelected = false
        checkBox(panel, "Validate commit messages: Conventional Commits and subject length").isSelected = true
        spinner(panel, "Commits to load:").value = 400
        spinner(panel, "Fetch interval (minutes):").value = 25
        spinner(panel, "Commit subject limit:").value = 100

        assertTrue(subject.isModified())
        assertEquals(saved, settings.state)
        subject.apply()
        val applied = BetterGitSettings.Options(false, 400, true, 25, false, true, 100, false)
        assertEquals(applied, settings.state)
        assertFalse(subject.isModified())

        checkBox(panel, "Show author and commit beside the current line").isSelected = true
        spinner(panel, "Commits to load:").value = 500
        assertTrue(subject.isModified())
        subject.reset()
        assertFalse(checkBox(panel, "Show author and commit beside the current line").isSelected)
        assertEquals(400, spinner(panel, "Commits to load:").value)
        assertFalse(subject.isModified())
        assertEquals(applied, settings.state)
    }

    fun testDisposalDiscardsUnsavedUiAndRecreationReadsCurrentSettings() {
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        val settings = BetterGitSettings.getInstance()
        settings.loadState(BetterGitSettings.Options())
        val subject = BetterGitConfigurable().also { configurable = it }
        val oldPanel = subject.createComponent()
        spinner(oldPanel, "Commits to load:").value = 600
        assertTrue(subject.isModified())

        subject.disposeUIResources()
        val saved = BetterGitSettings.Options(historyLimit = 850, authorAvatars = false)
        settings.loadState(saved)
        subject.reset()
        subject.apply()
        assertFalse(subject.isModified())
        assertEquals(saved, settings.state)

        val newPanel = subject.createComponent()
        assertNotSame(oldPanel, newPanel)
        assertEquals(850, spinner(newPanel, "Commits to load:").value)
        assertFalse(checkBox(newPanel, "Load author photos from GitHub/GitLab when a blame hover opens").isSelected)
        assertFalse(subject.isModified())

        spinner(oldPanel, "Commits to load:").value = 1000
        subject.apply()
        assertEquals(saved, settings.state)
    }

    fun testAiPreferencesKeepAllProviderModelsAndResetWithoutSaving() {
        val ai = AiCommitSettings.getInstance()
        val saved = AiCommitSettings.Options(provider = "OPENAI", openaiModel = "saved-model", style = "Short subject")
        ai.loadState(saved)
        val subject = BetterGitConfigurable().also { configurable = it }
        val panel = subject.createComponent()
        val provider = components(panel).filterIsInstance<JComboBox<*>>().single()
        assertEquals(AiProvider.OPENAI, provider.selectedItem)
        assertEquals("saved-model", textField(panel, "OpenAI model:").text)
        textField(panel, "OpenAI model:").text = "openai-custom"
        provider.selectedItem = AiProvider.CLAUDE
        textField(panel, "Claude model:").text = "claude-custom"
        textField(panel, "Anthropic workspace ID (optional):").text = "workspace-test"
        textField(panel, "Message style:").text = "Conventional Commits, in Turkish"
        assertTrue(subject.isModified())
        assertEquals(saved, ai.state)
        subject.apply()
        assertEquals("CLAUDE", ai.state.provider)
        assertEquals("openai-custom", ai.state.openaiModel)
        assertEquals("claude-custom", ai.state.claudeModel)
        assertEquals(saved.geminiModel, ai.state.geminiModel)
        assertEquals("workspace-test", ai.state.anthropicWorkspaceId)
        assertFalse(subject.isModified())

        provider.selectedItem = AiProvider.OLLAMA
        textField(panel, "Local Ollama endpoint:").text = "http://127.0.0.2:11434"
        subject.reset()
        assertEquals(AiProvider.CLAUDE, provider.selectedItem)
        assertEquals(saved.localEndpoint, textField(panel, "Local Ollama endpoint:").text)
        assertFalse(subject.isModified())
    }

    fun testCredentialEditsAreDeferredAndCancelNeverTouchesPasswordStore() {
        val writes = mutableMapOf<AiProvider, String?>()
        val subject = BetterGitConfigurable { provider, key ->
            assertFalse("Password store access must not block EDT", ApplicationManager.getApplication().isDispatchThread)
            writes[provider] = key
        }.also { configurable = it }
        val panel = subject.createComponent()
        val key = textField(panel, "OpenAI API key:") as JPasswordField
        key.text = "test-key-not-a-secret"
        assertTrue(subject.isModified())
        assertTrue(writes.isEmpty())
        subject.reset()
        assertEquals(0, key.password.size)
        assertFalse(subject.isModified())
        assertTrue(writes.isEmpty())

        key.text = "test-key-not-a-secret"
        checkBox(panel, "Remove saved Gemini key on Apply").isSelected = true
        subject.apply()
        assertEquals(mapOf(AiProvider.OPENAI to "test-key-not-a-secret", AiProvider.GEMINI to null), writes)
        assertEquals(0, key.password.size)
        assertFalse(subject.isModified())
        key.text = "cancelled-key"
        subject.disposeUIResources()
        assertEquals(0, key.password.size)
        assertEquals(2, writes.size)
    }

    fun testInvalidAiSettingsDoNotApplyGeneralPreferencesOrCredentials() {
        val before = BetterGitSettings.getInstance().state.copy()
        val beforeAi = AiCommitSettings.getInstance().state.copy()
        var credentialWrites = 0
        val subject = BetterGitConfigurable { _, _ -> credentialWrites++ }.also { configurable = it }
        val panel = subject.createComponent()
        spinner(panel, "Commits to load:").value = 900
        textField(panel, "Local Ollama endpoint:").text = "https://example.com"
        textField(panel, "OpenAI API key:").text = "test-key"
        try {
            subject.apply()
            fail("Remote endpoints must not be saved as local Ollama")
        } catch (_: ConfigurationException) { }
        assertEquals(before, BetterGitSettings.getInstance().state)
        assertEquals(beforeAi, AiCommitSettings.getInstance().state)
        assertEquals(0, credentialWrites)
        assertTrue(subject.isModified())
    }

    fun testPasswordStoreFailureKeepsPendingEditsAndDoesNotApplyPreferences() {
        val before = AiCommitSettings.getInstance().state.copy()
        val subject = BetterGitConfigurable { _, _ -> throw IllegalStateException("Store unavailable") }.also { configurable = it }
        val panel = subject.createComponent()
        textField(panel, "Message style:").text = "Changed style"
        textField(panel, "OpenAI API key:").text = "pending-key"
        try {
            subject.apply()
            fail("Password store failures must be reported")
        } catch (_: ConfigurationException) { }
        assertEquals(before, AiCommitSettings.getInstance().state)
        assertTrue(subject.isModified())
        assertEquals("pending-key", String((textField(panel, "OpenAI API key:") as JPasswordField).password))
    }

    fun testSettingsPageScrollsAndRendersAtNarrowWidths() {
        val subject = BetterGitConfigurable().also { configurable = it }
        val panel = subject.createComponent() as javax.swing.JScrollPane
        fun layout(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layout) } }
        for (width in listOf(620, 900)) {
            panel.setSize(width, 720)
            repeat(5) { layout(panel) }
            assertEquals(panel.viewport.width, panel.viewport.view.width)
            assertFalse("Settings should scroll vertically", panel.horizontalScrollBar.isVisible)
        }
        panel.setSize(760, 1100)
        repeat(5) { layout(panel) }
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { panel.paint(graphics) } finally { graphics.dispose() }
        val output = java.nio.file.Path.of("build/reports/tests/settings")
        java.nio.file.Files.createDirectories(output)
        javax.imageio.ImageIO.write(image, "png", output.resolve("unified-settings.png").toFile())
    }

    private fun textField(root: Component, label: String): JTextField =
        components(root).filterIsInstance<JLabel>().single { it.text == label }.labelFor as JTextField

    private fun checkBox(root: Component, text: String): JCheckBox =
        components(root).filterIsInstance<JCheckBox>().single { it.text == text }

    private fun spinner(root: Component, label: String): JSpinner =
        components(root).filterIsInstance<JLabel>().single { it.text == label }.labelFor as JSpinner

    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(components(it)) }
    }
}
