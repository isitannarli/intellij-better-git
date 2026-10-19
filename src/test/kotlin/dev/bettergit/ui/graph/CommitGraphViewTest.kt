package dev.bettergit.ui.graph

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.IconManager
import dev.bettergit.core.git.*
import dev.bettergit.core.graph.*
import dev.bettergit.services.RepositorySnapshot
import java.awt.Container
import java.awt.image.BufferedImage
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.*

class CommitGraphViewTest : BasePlatformTestCase() {
    override fun setUp() {
        IconManager.activate(null)
        super.setUp()
    }

    override fun tearDown() {
        try { super.tearDown() } finally { IconManager.deactivate() }
    }

    private fun snapshot(): GraphSnapshot {
        val start = Instant.parse("2026-10-01T12:00:00Z")
        val commits = (0..24).map { index ->
            val timestamp = start.plusSeconds(((24 - index) / 2) * 86400L)
            GraphCommit(GitCommit("hash-$index", "Maya Chen", "maya@example.test", timestamp,
                listOf("feat: support multiple product mappings", "fix: preserve branch selection", "Update pipeline.yml")[index % 3],
                if (index == 24) emptyList() else listOf("hash-${index + 1}")),
                GraphChanges(if (index == 3) 125 else index % 7 + 1, 235L + index * 23, 15L + index * 7), timestamp)
        }
        val refs = listOf(
            GraphReference("main", "refs/heads/main", "hash-0", GraphRefKind.BRANCH),
            GraphReference("origin/main", "refs/remotes/origin/main", "hash-0", GraphRefKind.REMOTE),
            GraphReference("origin/feature", "refs/remotes/origin/feature", "hash-5", GraphRefKind.REMOTE),
            GraphReference("v1.0", "refs/tags/v1.0", "hash-15", GraphRefKind.TAG),
        )
        return GraphSnapshot(RepositorySnapshot(Path.of("/tmp/example"), WorkingTreeStatus("main", 0, 0, 0, 0, 0),
            listOf(GitRemote("origin", "https://example.test/team/repo.git"))),
            GraphHistory(commits, refs, "hash-0", true, false), listOf(GitStash("stash@{0}", "Saved work", "stash-hash")),
            listOf(GitWorktree(Path.of("/tmp/example"), "refs/heads/main", "hash-0", false)))
    }

    fun testReferenceNavigationJumpsToLoadedCommitAndLoadsMissingHistory() {
        var requested: GraphScope? = null
        val view = CommitGraphView(object : CommitGraphView.Listener {
            override fun scopeChanged(scope: GraphScope) { requested = scope }
        })
        try {
            view.showSnapshot(snapshot(), GraphScope("All branches", null))
            view.navigateToReference(GraphNavigationTarget("v1.0", "refs/tags/v1.0", "hash-15"))
            assertEquals(15, view.table.selectedRow)
            assertNull(requested)
            view.navigateToReference(GraphNavigationTarget("Saved work", "stash-hash", "stash-hash"))
            assertEquals("stash-hash", requested?.revision)
            view.showSnapshot(snapshot(), requested!!)
            val combos = children(view).filterIsInstance<JComboBox<*>>()
            assertTrue(combos.any { (it.selectedItem as? GraphScope)?.revision == "stash-hash" })
            view.loading()
            requested = null
            view.navigateToReference(GraphNavigationTarget("Other", "refs/heads/other"))
            assertNull(requested)
        } finally { view.stop() }
    }

    fun testSidebarCountsRemotesAndExposesNamesAndNumbersWithoutHover() {
        val rail = GraphRefSidebar { }.apply { snapshot = snapshot() }
        val names = children(rail).filterIsInstance<JButton>().map { it.accessibleContext.accessibleName }.toList()
        assertEquals(listOf("Branches: 1", "Remotes: 1", "Stashes: 1", "Tags: 1", "Worktrees: 1"), names)
    }

    fun testMinimapEmptyIntervalsNavigateAndCommitAndLineTotalsRemainDistinct() {
        val data = snapshot()
        assertEquals(25, data.activity.sumOf { it.count })
        assertEquals(data.history.commits.sumOf { it.changes.added + it.changes.removed }, data.activity.sumOf { it.linesChanged })
        val empty = data.activity.indexOfFirst { it.count == 0 }
        val nearest = data.nearestRow(empty)!!
        assertTrue(nearest in data.history.commits.indices)
        var selected = -1
        val map = GraphMinimap { selected = it }.apply { snapshot = data }
        val timeline = children(map).filterIsInstance<JPanel>().single { it.accessibleContext.accessibleName == "Commit activity timeline" }
        timeline.setSize(966, 68)
        timeline.dispatchEvent(MouseEvent(timeline, MouseEvent.MOUSE_PRESSED, 0, 0, 8 + empty * 10, 20, 1, false, MouseEvent.BUTTON1))
        assertEquals(nearest, selected)
        val allAtOnce = data.copy(history = data.history.copy(commits = data.history.commits.map { it.copy(committedAt = Instant.EPOCH) }))
        assertEquals(25, allAtOnce.activity.first().count)
        assertNotNull(allAtOnce.nearestRow(95))
        val emptyData = data.copy(history = data.history.copy(commits = emptyList()))
        assertNull(emptyData.nearestRow(50))
    }

    fun testGraphPaintsWithReadableControlsAtWideAndNarrowSizes() {
        val originalLaf = UIManager.getLookAndFeel()
        val dark = JBColor.isBright().not()
        IconLoader.activate()
        try {
            val folder = Path.of("build/reports/tests/graph-preview").toAbsolutePath()
            Files.createDirectories(folder)
            listOf(false, true).forEach { isDark ->
                UIManager.setLookAndFeel(if (isDark) DarculaLaf() else originalLaf)
                JBColor.setDark(isDark)
                IconLoader.setUseDarkIcons(isDark)
                val view = CommitGraphView(object : CommitGraphView.Listener {})
                try {
                    val data = snapshot()
                    view.setRepositories(listOf(data.repository), data.repository.root)
                    view.showSnapshot(data, GraphScope("All branches", null))
                    view.search.text = "pipeline"
                    view.applyMatches("pipeline", data.history.commits.indices.filter { it % 3 == 2 })
                    view.table.setRowSelectionInterval(3, 3)
                    listOf(1750, 1000).forEach { width ->
                        view.setSize(width, 650)
                        layout(view)
                        layout(view)
                        val image = BufferedImage(width, 650, BufferedImage.TYPE_INT_RGB)
                        val graphics = image.createGraphics()
                        try { view.paint(graphics) } finally { graphics.dispose() }
                        ImageIO.write(image, "png", folder.resolve("graph-${if (isDark) "dark" else "light"}-$width.png").toFile())
                    }
                } finally { view.stop() }
            }
        } finally {
            UIManager.setLookAndFeel(originalLaf)
            JBColor.setDark(dark)
            IconLoader.setUseDarkIcons(dark)
            IconLoader.deactivate()
        }
    }

    private fun children(root: Container): Sequence<java.awt.Component> = root.components.asSequence().flatMap {
        sequenceOf(it) + if (it is Container) children(it) else emptySequence()
    }
    private fun layout(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layout)
    }
}
