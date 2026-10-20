package dev.bettergit.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBOptionButton
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke

class WorkingCommitPanelTest : BasePlatformTestCase() {
    fun testMessageRequiredShortcutsCommitAndBusyOrConflictBlocksSubmission() {
        var submissions = 0
        val panel = WorkingCommitPanel({ submissions++ }, {}, {})
        val input = descendants(panel).filterIsInstance<JBTextArea>().single()
        val commit = button(panel, "Commit changes")
        panel.setRepository(Path.of("/repo"), "feature")
        panel.setState(false, true, true, false, false)
        assertFalse(commit.action.isEnabled)
        assertTrue("The menu remains available with an empty message", commit.isEnabled)
        panel.message = "feat: new workflow\n\nDetails"
        assertTrue(commit.action.isEnabled)
        assertTrue(input.emptyText.text.contains("feature"))
        assertEquals(3, input.rows)
        for (shortcut in listOf("meta ENTER", "control ENTER")) {
            val action = input.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(shortcut))
            assertNotNull(action)
            input.actionMap.get(action).actionPerformed(ActionEvent(input, 0, "commit"))
        }
        assertEquals(2, submissions)
        panel.setState(true, true, true, false, false)
        assertFalse(commit.isEnabled)
        assertFalse(commit.action.isEnabled)
        assertFalse(input.isEnabled)
        input.actionMap.get("commit").actionPerformed(ActionEvent(input, 0, "commit"))
        assertEquals(2, submissions)
        panel.setState(false, true, true, true, false)
        assertFalse(commit.action.isEnabled)
        panel.setState(false, false, true, false, false)
        assertTrue(commit.action.isEnabled)
        assertTrue(commit.toolTipText.contains("confirmation"))
    }

    fun testDraftSurvivesRefreshFailureAndRepositorySwitchAndOnlySuccessfulDraftIsCleared() {
        val panel = WorkingCommitPanel({}, {}, {})
        val first = Path.of("/first")
        val second = Path.of("/second")
        panel.setRepository(first, "main")
        panel.message = "fix: first draft"
        panel.setRepository(first, "main")
        panel.showFeedback("Hook rejected the message")
        panel.setState(false, true, true, false, false)
        assertEquals("fix: first draft", panel.message)
        panel.setRepository(null, null)
        panel.setRepository(second, "other")
        assertEquals("", panel.message)
        panel.message = "fix: second draft"
        panel.committed(first, "fix: first draft")
        assertEquals("fix: second draft", panel.message)
        panel.setRepository(first, "main")
        assertEquals("", panel.message)
        panel.message = "fix: newer draft"
        panel.committed(first, "fix: first draft")
        assertEquals("fix: newer draft", panel.message)
        panel.committed(first, "fix: newer draft")
        assertEquals("", panel.message)
        panel.setRepository(second, "other")
        assertEquals("fix: second draft", panel.message)
    }

    fun testComposerFitsNarrowPanelsAndMultilineMessageIsBounded() {
        val panel = WorkingCommitPanel({}, {}, {})
        panel.setRepository(Path.of("/repo"), "master")
        panel.setState(false, true, true, false, false)
        panel.message = "feat: inline commit"
        val input = descendants(panel).filterIsInstance<JBTextArea>().single()
        fun layout(component: Component) {
            if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
        }
        for (width in listOf(280, 640)) {
            panel.setSize(width, panel.preferredSize.height)
            repeat(3) { layout(panel) }
            val commit = button(panel, "Commit changes")
            val segments = commit.components.filterIsInstance<JButton>().sortedBy { it.x }
            assertEquals(2, segments.size)
            assertEquals("Both segments form one control", segments[0].x + segments[0].width, segments[1].x)
            assertEquals(segments[0].height, segments[1].height)
            assertEquals(width, commit.width)
            assertTrue(input.width > width - 70)
        }
        val preview = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        try { panel.paint(graphics) } finally { graphics.dispose() }
        val output = Path.of("build/reports/tests/working-changes").toAbsolutePath()
        java.nio.file.Files.createDirectories(output)
        javax.imageio.ImageIO.write(preview, "png", output.resolve("commit-composer.png").toFile())
        panel.message = (1..20).joinToString("\n") { "Message line $it" }
        assertEquals(4, input.rows)
        assertTrue(panel.preferredSize.height < 200)
    }

    private fun button(panel: WorkingCommitPanel, name: String) = descendants(panel).filterIsInstance<JBOptionButton>()
        .single { it.accessibleContext.accessibleName == name }
    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
