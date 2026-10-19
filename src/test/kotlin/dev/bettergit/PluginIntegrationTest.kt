package dev.bettergit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bettergit.actions.FileHistoryAction
import dev.bettergit.actions.InspectLineAction
import dev.bettergit.actions.currentEditor
import dev.bettergit.actions.currentFile
import dev.bettergit.settings.BetterGitConfigurable
import dev.bettergit.ui.graph.GraphWorkspacePanel

class PluginIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredHistoryActionsUseSelectedEditorFromToolWindowContext() {
        myFixture.configureByText("selected.txt", "A committed line\n")
        assertSame(myFixture.editor, FileEditorManager.getInstance(project).selectedTextEditor)
        val context = SimpleDataContext.getProjectContext(project)

        val history = ActionManager.getInstance().getAction("BetterGit.FileHistory")
        assertInstanceOf(history, FileHistoryAction::class.java)
        val historyEvent = AnActionEvent.createFromAnAction(history, null, ActionPlaces.TOOLWINDOW_CONTENT, context)
        history.update(historyEvent)

        assertTrue(historyEvent.presentation.isEnabledAndVisible)
        assertSame(myFixture.editor, historyEvent.currentEditor())
        assertSame(myFixture.file.virtualFile, historyEvent.currentFile())

        val inspect = ActionManager.getInstance().getAction("BetterGit.InspectLine")
        assertInstanceOf(inspect, InspectLineAction::class.java)
        val inspectEvent = AnActionEvent.createFromAnAction(inspect, null, ActionPlaces.TOOLWINDOW_CONTENT, context)
        inspect.update(inspectEvent)
        assertTrue(inspectEvent.presentation.isEnabledAndVisible)
    }

    fun testFileHistoryHonorsExplicitFileContextAndRequiresProject() {
        myFixture.configureByText("selected.txt", "Selected editor content\n")
        val directory = myFixture.tempDirFixture.findOrCreateDir("folder")
        val history = ActionManager.getInstance().getAction("BetterGit.FileHistory")
        assertInstanceOf(history, FileHistoryAction::class.java)

        val directoryContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, directory)
            .build()
        val directoryEvent = AnActionEvent.createFromAnAction(history, null, ActionPlaces.PROJECT_VIEW_POPUP, directoryContext)
        history.update(directoryEvent)
        assertSame(directory, directoryEvent.currentFile())
        assertFalse("A selected editor must not make a directory eligible for file history", directoryEvent.presentation.isEnabledAndVisible)

        val noProjectContext = SimpleDataContext.builder()
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
            .build()
        val noProjectEvent = AnActionEvent.createFromAnAction(history, null, ActionPlaces.EDITOR_POPUP, noProjectContext)
        history.update(noProjectEvent)
        assertFalse(noProjectEvent.presentation.isEnabledAndVisible)
    }

    fun testSettingsExtensionCreatesUsableConfigurable() {
        val extension = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.id == "dev.bettergit.settings" }
        val configurable = requireNotNull(extension.createConfigurable())
        assertInstanceOf(configurable, BetterGitConfigurable::class.java)
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testGraphToolWindowDisposesItsPanelAndChildrenOnClose() {
        val extension = ToolWindowEP.EP_NAME.extensionList.single { it.id == "Better Git Graph" }
        val factory = extension.getToolWindowFactory(extension.pluginDescriptor)
        val manager = ToolWindowManager.getInstance(project)
        val windowId = "Better Git lifecycle test"
        val window = manager.registerToolWindow(RegisterToolWindowTask.notClosable(windowId))
        try {
            // Keep the graph hidden: creating and disposing it should not require a repository or network.
            factory.createToolWindowContent(project, window)
            assertEquals(1, window.contentManager.contentCount)
            val panel = window.contentManager.contents.single().component
            assertInstanceOf(panel, GraphWorkspacePanel::class.java)
            val disposablePanel = panel as GraphWorkspacePanel
            var childDisposed = false
            Disposer.register(disposablePanel, Disposable { childDisposed = true })

            manager.unregisterToolWindow(windowId)

            assertTrue("Closing the tool window must dispose its graph panel", Disposer.isDisposed(disposablePanel))
            assertTrue("Resources owned by the panel must be released with it", childDisposed)
        } finally {
            manager.unregisterToolWindow(windowId)
        }
    }
}
