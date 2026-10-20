package dev.bettergit.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import dev.bettergit.core.git.FileLineStats
import dev.bettergit.core.git.WorkingChange
import dev.bettergit.settings.WorkspaceLayoutState
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class WorkingChangesPanelTest : BasePlatformTestCase() {
    fun testCompactFoldersKeepIndependentStagedAndWorkingEntries() {
        val both = WorkingChange("src/main/kotlin/code.kt", indexStatus = 'M', workTreeStatus = 'M')
        val conflict = WorkingChange("conflict.txt", indexStatus = 'U', workTreeStatus = 'U', conflicted = true)
        val root = WorkingChangesTree.build(listOf(both, conflict, WorkingChange("new.txt", untracked = true)))
        val groups = root.children().toList().map { it as DefaultMutableTreeNode }
        assertEquals(listOf(ChangeArea.CONFLICTS, ChangeArea.STAGED, ChangeArea.WORKING), groups.map { entry(it).area })
        assertEquals("src/main/kotlin", entry(groups[1].firstChild as DefaultMutableTreeNode).label)
        assertEquals(listOf(both), WorkingChangesTree.changes(groups[1]))
        assertEquals(listOf(both, WorkingChange("new.txt", untracked = true)), WorkingChangesTree.changes(groups[2]))
        assertEquals(2, entry(groups[2]).count)
    }

    fun testAutoRefreshKeepsSelectionAndCollapsedFolders() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val changes = listOf(WorkingChange("src/code.kt", workTreeStatus = 'M'), WorkingChange("docs/readme.txt", untracked = true))
        val root = Path.of(project.basePath!!)
        panel.setChanges(root, changes)
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        fun nodes() = (tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList().map { it as DefaultMutableTreeNode }
        val src = nodes().single { (it.userObject as? ChangeTreeEntry)?.path == "src" }
        val file = nodes().single { (it.userObject as? ChangeTreeEntry)?.change?.path == "docs/readme.txt" }
        tree.collapsePath(TreePath(src.path))
        tree.selectionPath = TreePath(file.path)

        panel.setChanges(root, changes + WorkingChange("src/sub/new.kt", untracked = true))

        val updatedSrc = nodes().single { (it.userObject as? ChangeTreeEntry)?.path == "src" }
        assertFalse("Refresh must not reopen a collapsed folder when new children arrive", tree.isExpanded(TreePath(updatedSrc.path)))
        assertEquals("docs/readme.txt", entry(tree.selectionPath.lastPathComponent as DefaultMutableTreeNode).path)
        panel.clear()
        assertEquals(0, (tree.model.root as DefaultMutableTreeNode).childCount)
    }

    fun testLineCountsRefreshWithoutChangingStatusOrLosingSelection() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val root = Path.of(project.basePath!!)
        val change = WorkingChange("src/code.kt", indexStatus = 'M', workTreeStatus = 'M')
        val staged = mapOf(change.path to FileLineStats(55, 29))
        panel.setChanges(root, listOf(change), staged, mapOf(change.path to FileLineStats(6, 1)))
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        fun leaves() = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList()
            .map { it as DefaultMutableTreeNode }.filter { (it.userObject as? ChangeTreeEntry)?.change != null }
        assertEquals(listOf(FileLineStats(55, 29), FileLineStats(6, 1)), leaves().map { entry(it).stats })
        tree.selectionPath = TreePath(leaves().last().path)

        panel.setChanges(root, listOf(change), staged, mapOf(change.path to FileLineStats(29, 13)))

        assertEquals(listOf(FileLineStats(55, 29), FileLineStats(29, 13)), leaves().map { entry(it).stats })
        assertEquals(ChangeArea.WORKING, entry(tree.selectionPath.lastPathComponent as DefaultMutableTreeNode).area)
    }

    fun testLineCountsStayRightAlignedAndVisibleInNarrowNativeTree() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val changes = listOf(
            WorkingChange("app/domain/stocks/controllers/_notification.py", workTreeStatus = 'M'),
            WorkingChange("app/domain/stocks/services/very-long-filename-that-needs-to-be-clipped.py", workTreeStatus = 'M'),
            WorkingChange("README.md", workTreeStatus = 'M'),
        )
        panel.setChanges(Path.of(project.basePath!!), changes, workingStats = changes.associate { it.path to FileLineStats(55, 29) })
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        val ui = com.intellij.ui.tree.ui.DefaultTreeUI()
        tree.setUI(ui)
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        panel.setSize(320, 500)
        fun layout(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layout) } }
        repeat(4) { layout(panel) }
        // Check actual painting on every supported tree UI, without relying on newer layout-only APIs.
        val preview = java.awt.image.BufferedImage(320, 500, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        try { panel.paint(graphics) } finally { graphics.dispose() }
        val output = Path.of("build/reports/tests/working-changes").toAbsolutePath()
        java.nio.file.Files.createDirectories(output)
        javax.imageio.ImageIO.write(preview, "png", output.resolve("line-counts-narrow.png").toFile())
        val treeOrigin = javax.swing.SwingUtilities.convertPoint(tree, 0, 0, panel)
        val paintedRightEdges = mutableSetOf<Int>()
        var renderedFiles = 0
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row)
            if (entry(path.lastPathComponent as DefaultMutableTreeNode).change == null) continue
            val bounds = tree.getPathBounds(path)!!
            val redPixels = mutableListOf<Int>()
            var greenPixels = 0
            for (y in treeOrigin.y + bounds.y until treeOrigin.y + bounds.y + bounds.height) {
                for (x in treeOrigin.x until treeOrigin.x + tree.visibleRect.width) {
                    val color = java.awt.Color(preview.getRGB(x, y))
                    if (color.red > color.green * 1.4 && color.red > color.blue * 1.4) redPixels += x
                    if (color.green > color.red * 1.4 && color.green > color.blue * 1.4) greenPixels++
                }
            }
            assertTrue("The actual painted row must show additions", greenPixels > 0)
            assertTrue("The actual painted row must show removals", redPixels.isNotEmpty())
            assertTrue("Painted counts must leave space inside the right edge",
                redPixels.max() < treeOrigin.x + tree.visibleRect.width - com.intellij.util.ui.JBUI.scale(8))
            paintedRightEdges += redPixels.max()
            renderedFiles++
        }
        assertEquals(3, renderedFiles)
        assertEquals("Painted counts must align across folder depths", 1, paintedRightEdges.size)
    }

    fun testRendererKeepsColoredNumericCountsAndLabelsBinaryOrUnavailableFiles() {
        val renderer = WorkingChangeRenderer(project) { Path.of(project.basePath!!) }
        val tree = Tree()
        val previousDark = !com.intellij.ui.JBColor.isBright()
        try {
            for (dark in listOf(false, true)) {
                com.intellij.ui.JBColor.setDark(dark)
                for (selected in listOf(false, true)) {
                    val entry = ChangeTreeEntry(ChangeArea.WORKING, "file.kt", "file.kt", WorkingChange("file.kt", workTreeStatus = 'M'), stats = FileLineStats(0, 79))
                    renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(entry), selected, false, true, 0, false)
                    val counts = renderer.components.filterIsInstance<com.intellij.ui.SimpleColoredComponent>().single { it.name == "Line change counts" }
                    val iterator = counts.iterator()
                    assertEquals("+0", iterator.next())
                    val green = iterator.textAttributes.fgColor!!
                    iterator.next()
                    assertEquals("−79", iterator.next())
                    val red = iterator.textAttributes.fgColor!!
                    assertTrue(green.green > green.red)
                    assertTrue(red.red > red.green)
                }
            }
            for ((stats, label) in listOf(FileLineStats(null, null) to "Binary", null to "—")) {
                val entry = ChangeTreeEntry(ChangeArea.WORKING, "file", "file", WorkingChange("file", untracked = true), stats = stats)
                renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(entry), false, false, true, 0, false)
                val counts = renderer.components.filterIsInstance<com.intellij.ui.SimpleColoredComponent>().single { it.name == "Line change counts" }
                assertEquals(label, counts.iterator().next())
            }
        } finally { com.intellij.ui.JBColor.setDark(previousDark) }
    }

    fun testHoverKeepsRowsAndLongFileTooltipsWithinTheViewport() {
        val panel = WorkingChangesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val change = WorkingChange("src/<tag>&/" + "long-filename-".repeat(16) + ".kt", indexStatus = 'M')
        panel.setChanges(Path.of(project.basePath!!), listOf(change), stagedStats = mapOf(change.path to FileLineStats(1234, 567)))
        val tree = descendants(panel).filterIsInstance<Tree>().single()
        tree.setUI(com.intellij.ui.tree.ui.DefaultTreeUI())
        fun layout(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layout) } }
        for (width in listOf(240, 320, 720)) {
            panel.setSize(width, 500)
            repeat(4) { layout(panel) }
            val row = tree.rowCount - 1
            val path = tree.getPathForRow(row)
            tree.selectionPath = path
            val bounds = tree.getPathBounds(path)!!
            val viewport = tree.visibleRect
            val hover = java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0,
                viewport.x + viewport.width - 2, bounds.y + bounds.height / 2, 0, false)
            val text = requireNotNull(tree.getToolTipText(hover))
            val tooltip = tree.createToolTip().apply { tipText = text }
            val location = requireNotNull(tree.getToolTipLocation(hover))
            assertFalse("Hover must not show an expanded copy of the full-width row", tree.expandableItemsHandler.isEnabled)
            assertTrue("Long paths must wrap inside the viewport", tooltip.preferredSize.width <= viewport.width)
            assertTrue("Tooltip must stay to the right of the viewport's left edge", location.x >= viewport.x)
            assertTrue("Hovering the right edge must not push the tooltip outside", location.x + tooltip.preferredSize.width <= viewport.x + viewport.width)
            assertEquals(bounds.y + bounds.height, location.y)
            assertTrue("Path markup must be displayed literally", text.contains("&lt;tag&gt;&amp;"))
            val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, true, false, true, row, true)
            assertTrue("Accessibility must retain the complete plain-text path", renderer.accessibleContext.accessibleName.contains(change.path))
            if (width == 320) {
                tooltip.setSize(tooltip.preferredSize)
                val image = java.awt.image.BufferedImage(tooltip.width, tooltip.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { tooltip.paint(graphics) } finally { graphics.dispose() }
                val output = Path.of("build/reports/tests/working-changes").toAbsolutePath()
                java.nio.file.Files.createDirectories(output)
                javax.imageio.ImageIO.write(image, "png", output.resolve("hover-tooltip-narrow.png").toFile())
            }
        }
    }

    fun testLegacyOverviewSelectionMigratesToFirstChangesTab() {
        val state = WorkspaceLayoutState()
        state.loadState(WorkspaceLayoutState.Options("overview"))
        assertEquals("working", state.state.selectedTab)
        state.loadState(WorkspaceLayoutState.Options("history"))
        assertEquals("history", state.state.selectedTab)
    }

    private fun entry(node: DefaultMutableTreeNode) = node.userObject as ChangeTreeEntry
    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
