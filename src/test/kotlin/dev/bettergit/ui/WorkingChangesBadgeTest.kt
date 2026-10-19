package dev.bettergit.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.Icon

class WorkingChangesBadgeTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private lateinit var virtualRoot: VirtualFile
    private lateinit var window: ToolWindow

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("better-git-badge-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        command("init", "--initial-branch=main")
        command("config", "user.name", "Test Developer")
        command("config", "user.email", "developer@example.test")
        command("config", "commit.gpgsign", "false")
        Files.writeString(root.resolve("tracked.txt"), "base\n")
        Files.writeString(root.resolve("deleted.txt"), "delete me\n")
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\n")
        command("add", ".")
        command("commit", "-m", "Initial")
        virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        ProjectLevelVcsManager.getInstance(project).setDirectoryMapping(root.toString(), "Git")
        val manager = ToolWindowManager.getInstance(project)
        // The headless platform window discards icons and titles. Record only those presentation
        // writes, delegating all other behavior to the real registered platform tool window.
        window = PresentationWindow(manager.registerToolWindow(RegisterToolWindowTask.notClosable("Better Git badge test")))
        window.setIcon(IconLoader.getIcon("/icons/betterGit.svg", javaClass))
        BetterGitToolWindowFactory().init(window)
    }

    override fun tearDown() {
        try {
            if (::window.isInitialized) {
                Disposer.dispose(window.disposable)
                ToolWindowManager.getInstance(project).unregisterToolWindow(window.id)
            }
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
        } finally {
            try { super.tearDown() }
            finally { if (::root.isInitialized) FileUtil.delete(root) }
        }
    }

    fun testHiddenWindowCountsModifiedAndNestedUntrackedFilesAndExcludesIgnoredFiles() {
        Files.writeString(root.resolve("tracked.txt"), "modified\n")
        Files.createDirectories(root.resolve("new-folder"))
        Files.writeString(root.resolve("new-folder/first.txt"), "new\n")
        Files.writeString(root.resolve("new-folder/second.txt"), "also new\n")
        Files.writeString(root.resolve("ignored.txt"), "ignored\n")
        refreshChanges()

        awaitCount(3)

        assertFalse(window.isVisible)
        assertEquals("Counting must not create the Better Git panel", 0, window.contentManager.contentCount)
        assertEquals("${window.id} (3 changed files)", window.stripeTitle)
    }

    fun testStagedAndUnstagedFileAndRenameCountOnceAndCleanTreeClearsBadge() {
        Files.writeString(root.resolve("tracked.txt"), "staged\n")
        command("add", "tracked.txt")
        Files.writeString(root.resolve("tracked.txt"), "staged and then edited\n")
        command("mv", "deleted.txt", "renamed.txt")
        refreshChanges()
        awaitCount(2)

        command("reset", "--hard", "HEAD")
        refreshChanges()
        awaitCount(0)
        assertEquals(window.id, window.stripeTitle)
    }

    fun testDeletedFileIsIncluded() {
        Files.delete(root.resolve("deleted.txt"))
        refreshChanges()
        awaitCount(1)
        assertEquals("${window.id} (1 changed file)", window.stripeTitle)
    }

    fun testBadgeScalesAndCapsDisplayWithoutLosingExactCount() {
        val icon = ChangeCountIcon(window.icon!!, 125)
        assertEquals("99+", icon.badgeText)
        val scaled = icon.scale(2f) as ChangeCountIcon
        assertEquals(icon.iconWidth * 2, scaled.iconWidth)
        assertEquals(125, scaled.count)
        // Save a paint preview for visual inspection alongside the platform-test reports.
        val preview = BufferedImage(320, 80, BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        try {
            graphics.color = Color(0x2B2D30)
            graphics.fillRect(0, 0, preview.width, preview.height)
            listOf(2, 12, 125).forEachIndexed { index, count ->
                ChangeCountIcon(window.icon!!, count).scale(2f).paintIcon(null, graphics, 24 + index * 96, 24)
            }
        } finally { graphics.dispose() }
        val directory = Path.of("build/reports/tests/badge-preview").toAbsolutePath()
        Files.createDirectories(directory)
        ImageIO.write(preview, "png", directory.resolve("sidebar-badges.png").toFile())
    }

    private fun refreshChanges() {
        PlatformTestUtil.callOnBgtSynchronously({
            VfsUtil.markDirtyAndRefresh(false, true, false, virtualRoot)
        }, 30)
        VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(virtualRoot)
        PlatformTestUtil.callOnBgtSynchronously({
            ChangeListManagerImpl.getInstanceImpl(project).ensureUpToDate()
        }, 30)
    }

    private fun awaitCount(count: Int) {
        try {
            PlatformTestUtil.waitWithEventsDispatching("Badge did not become $count", {
                if (count == 0) window.icon !is ChangeCountIcon
                else (window.icon as? ChangeCountIcon)?.count == count
            }, 10)
        } catch (failure: AssertionError) {
            val changes = ChangeListManagerImpl.getInstanceImpl(project)
            throw AssertionError("Expected $count, icon=${(window.icon as? ChangeCountIcon)?.count}, " +
                "tracked=${changes.allChanges}, untracked=${changes.unversionedFilesPaths}", failure)
        }
    }

    private class PresentationWindow(private val delegate: ToolWindow) : ToolWindow by delegate {
        private var currentIcon: Icon? = null
        private var currentTitle = delegate.id
        private val lifetime = Disposer.newDisposable("Badge test window")
        override fun getIcon() = currentIcon
        override fun setIcon(icon: Icon) { currentIcon = icon }
        override fun getStripeTitle() = currentTitle
        override fun setStripeTitle(title: String) { currentTitle = title }
        override fun getDisposable(): Disposable = lifetime
        override fun isDisposed() = Disposer.isDisposed(lifetime)
    }

    private fun command(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", root.toString()) + arguments)
            .redirectErrorStream(true).start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Git test command timed out" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Git ${arguments.joinToString(" ")} failed: $output" }
    }
}
