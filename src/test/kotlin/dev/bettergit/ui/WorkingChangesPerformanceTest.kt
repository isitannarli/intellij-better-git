package dev.bettergit.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import dev.bettergit.core.git.FileLineStats
import dev.bettergit.core.git.WorkingChange
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

class WorkingChangesPerformanceTest : BasePlatformTestCase() {
    fun testLargeWorkingTree() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        tree.setUI(com.intellij.ui.tree.ui.DefaultTreeUI())
        panel.setSize(720, 700)
        val changes = (0 until 5000).map { WorkingChange("src/module-${it / 20}/file-$it.kt", workTreeStatus = 'M') }
        val stats = changes.associate { it.path to FileLineStats(12, 3) }
        val start = System.nanoTime()
        panel.setChanges(Path.of(project.basePath!!), changes, workingStats = stats)
        val dispatchMs = (System.nanoTime() - start) / 1_000_000.0
        assertEquals("Preparing thousands of nodes must be deferred off the EDT", 0, (tree.model.root as DefaultMutableTreeNode).childCount)
        PlatformTestUtil.waitWithEventsDispatching("Large tree should load", { tree.rowCount >= 5000 }, 30)
        println("working_tree_5000 dispatch_ms=$dispatchMs complete_ms=${(System.nanoTime() - start) / 1_000_000.0} rows=${tree.rowCount}")
        assertEquals(5000, WorkingChangesTree.changes(tree.model.root as DefaultMutableTreeNode).size)
    }

    fun testFilteringSearchesBeyondDisplayLimitAndUsesLatestQuery() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        val search = descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextField>().single()
        val changes = (0 until 6001).map { WorkingChange("src/file-$it.txt", untracked = true) }
        panel.setChanges(Path.of(project.basePath!!), changes)
        search.text = "file-100"
        search.text = "file-6000.txt"
        PlatformTestUtil.waitWithEventsDispatching("Latest query should find files beyond the display limit", {
            WorkingChangesTree.changes(tree.model.root as DefaultMutableTreeNode).map { it.path } == listOf("src/file-6000.txt")
        }, 10)
        assertEquals(1, WorkingChangesTree.changes(tree.model.root as DefaultMutableTreeNode).size)
    }

    fun testSwitchingRepositoryDiscardsPendingLargeTree() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        val root = Path.of(project.basePath!!)
        panel.setChanges(root, (0 until 5000).map { WorkingChange("src/file-$it.txt", untracked = true) })
        panel.setChanges(root.resolve("other"), listOf(WorkingChange("new-root.txt", untracked = true)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf("new-root.txt"), WorkingChangesTree.changes(tree.model.root as DefaultMutableTreeNode).map { it.path })
        panel.clear()
        assertEquals(0, (tree.model.root as DefaultMutableTreeNode).childCount)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
