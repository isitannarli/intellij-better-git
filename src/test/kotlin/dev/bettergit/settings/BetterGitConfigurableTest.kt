package dev.bettergit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.util.concurrent.TimeUnit
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSpinner

class BetterGitConfigurableTest : BasePlatformTestCase() {
    private lateinit var originalSettings: BetterGitSettings.Options
    private var configurable: BetterGitConfigurable? = null

    override fun setUp() {
        super.setUp()
        originalSettings = BetterGitSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        try {
            configurable?.disposeUIResources()
            BetterGitSettings.getInstance().loadState(originalSettings)
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

    private fun checkBox(root: Component, text: String): JCheckBox =
        components(root).filterIsInstance<JCheckBox>().single { it.text == text }

    private fun spinner(root: Component, label: String): JSpinner =
        components(root).filterIsInstance<JLabel>().single { it.text == label }.labelFor as JSpinner

    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(components(it)) }
    }
}
