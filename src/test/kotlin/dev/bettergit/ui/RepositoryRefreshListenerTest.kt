package dev.bettergit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class RepositoryRefreshListenerTest : BasePlatformTestCase() {
    fun testHiddenWorkspaceDefersRefreshAndBurstsHaveOnlyOneFollowUp() {
        val queue = RepositoryRefreshQueue()
        repeat(1000) { queue.request(RepositoryRefreshScope.WORKTREE) }
        assertNull(queue.take(visible = false))
        queue.request(RepositoryRefreshScope.REPOSITORY)
        queue.request(RepositoryRefreshScope.WORKTREE)
        assertEquals(RepositoryRefreshScope.REPOSITORY, queue.take(visible = true))
        repeat(1000) { queue.request(RepositoryRefreshScope.WORKTREE) }
        assertNull("A running query must not be restarted by subsequent saves", queue.take(visible = true))
        queue.finished()
        assertEquals(RepositoryRefreshScope.WORKTREE, queue.take(visible = true))
        queue.finished()
        assertNull(queue.take(visible = true))
    }

    fun testRepositoryRefreshIsNotLostWhenFollowedByWorkingTreeEvents() {
        val scopes = mutableListOf<RepositoryRefreshScope>()
        val listener = RepositoryRefreshListener(project) { scopes += it }
        Disposer.register(testRootDisposable, listener)
        project.messageBus.syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED).directoryMappingChanged()
        repeat(10) { project.messageBus.syncPublisher(ChangeListListener.TOPIC).changeListUpdateDone() }
        PlatformTestUtil.waitWithEventsDispatching("Repository events should refresh history as well as files", { scopes.isNotEmpty() }, 5)
        assertEquals(listOf(RepositoryRefreshScope.REPOSITORY), scopes)
    }
    fun testNativeChangeListEventsAreCoalescedAndDisposed() {
        var refreshes = 0
        val listener = RepositoryRefreshListener(project) { refreshes++ }
        Disposer.register(testRootDisposable, listener)
        repeat(5) { project.messageBus.syncPublisher(ChangeListListener.TOPIC).changeListUpdateDone() }
        PlatformTestUtil.waitWithEventsDispatching("Native changes should refresh the workspace", { refreshes == 1 }, 5)
        Disposer.dispose(listener)
        project.messageBus.syncPublisher(ChangeListListener.TOPIC).changeListUpdateDone()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, refreshes)
    }

    fun testSavedFileVfsEventsRefreshMappedRoots() {
        val file = myFixture.tempDirFixture.createFile("saved.txt", "original")
        var refreshes = 0
        val listener = RepositoryRefreshListener(project) {
            assertEquals("Saving a working file must not invalidate history", RepositoryRefreshScope.WORKTREE, it)
            refreshes++
        }
        Disposer.register(testRootDisposable, listener)
        listener.setRoots(listOf(Path.of(file.parent.path)))
        ApplicationManager.getApplication().runWriteAction {
            // Use the complete native VFS lifecycle, including the before-change notification.
            file.setBinaryContent("updated".toByteArray())
        }
        PlatformTestUtil.waitWithEventsDispatching("Saved files should refresh the workspace", { refreshes > 0 }, 5)
    }
}
