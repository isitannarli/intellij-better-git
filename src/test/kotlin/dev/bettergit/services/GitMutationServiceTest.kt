package dev.bettergit.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bettergit.core.git.GitClient
import dev.bettergit.ui.WorkingChangesPanel
import dev.bettergit.ui.WorkingCommitPanel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryImpl
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitMutationServiceTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private lateinit var virtualRoot: VirtualFile
    private lateinit var repository: GitRepository
    private val git = GitClient()

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("better-git-mutation-test-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        command("init", "--initial-branch=main")
        command("config", "user.name", "Test Developer")
        command("config", "user.email", "developer@example.test")
        command("config", "commit.gpgsign", "false")
        command("config", "core.autocrlf", "false")
        command("config", "core.hooksPath", root.resolve(".git/hooks").toString())
        Files.writeString(root.resolve("file.txt"), "base\n")
        command("add", ".")
        command("commit", "-m", "Initial")
        virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        repository = PlatformTestUtil.callOnBgtSynchronously({
            GitRepositoryImpl.createInstance(virtualRoot, project, testRootDisposable)
        }, 30)!!
        GitRepositoryManager.getInstance(project).addExternalRepository(virtualRoot, repository)
    }

    override fun tearDown() {
        try {
            if (::virtualRoot.isInitialized) GitRepositoryManager.getInstance(project).removeExternalRepository(virtualRoot)
        } finally {
            try {
                super.tearDown()
            } finally {
                if (::root.isInitialized) FileUtil.delete(root)
            }
        }
    }

    fun testStashIncludesUnsavedEditorChangesAndReloadsDocument() {
        val document = document()
        edit(document, "only in the editor\n")
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals("base\n", Files.readString(root.resolve("file.txt")))

        assertNull(mutate { git.stash(root, "Editor changes") })

        assertEquals("only in the editor\n", command("show", "stash@{0}:file.txt"))
        assertEquals("base\n", document.text)
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals("base\n", virtualRoot.findChild("file.txt")!!.contentsToByteArray().toString(Charsets.UTF_8))
    }

    fun testSelectedDiscardSavesThenRestoresEditorFromIndex() {
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", "file.txt")
        Files.writeString(root.resolve("file.txt"), "working\n")
        virtualRoot.refresh(false, true)
        val selected = git.workingChanges(root)
        val document = document()
        edit(document, "unsaved working edit\n")

        assertNull(mutate { git.discardChanges(root, selected) })

        assertEquals("staged\n", document.text)
        assertEquals("staged\n", command("show", ":file.txt"))
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    fun testInlineCommitSavesDocumentsCommitsOnlyIndexAndRefreshesRepository() {
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", ".")
        val document = document()
        edit(document, "unsaved working edit\n")
        var completed = false
        val panel = WorkingChangesPanel(project, { completed = true })
        Disposer.register(testRootDisposable, panel)
        panel.setRepository(RepositorySnapshot(root, git.status(root), emptyList()))
        panel.setChanges(root, git.workingChanges(root))
        val composer = descendants(panel).filterIsInstance<WorkingCommitPanel>().single()
        composer.message = "fix: commit staged content"
        descendants(composer).filterIsInstance<javax.swing.JButton>()
            .single { it.accessibleContext.accessibleName == "Commit changes" }.doClick()
        PlatformTestUtil.waitWithEventsDispatching("Inline commit did not finish", { completed }, 30)

        assertEquals("staged\n", command("show", "HEAD:file.txt"))
        assertEquals("unsaved working edit\n", document.text)
        assertEquals(document.text, Files.readString(root.resolve("file.txt")))
        assertEquals(command("rev-parse", "HEAD").trim(), repository.currentRevision)
        assertEquals("", composer.message)
    }

    fun testInlineStageAllRequiresConfirmationAndKeepsDraftOnCancel() {
        val document = document()
        edit(document, "new content\n")
        FileDocumentManager.getInstance().saveAllDocuments()
        var completed = false
        val panel = WorkingChangesPanel(project, { completed = true })
        Disposer.register(testRootDisposable, panel)
        panel.setRepository(RepositorySnapshot(root, git.status(root), emptyList()))
        panel.setChanges(root, git.workingChanges(root))
        val composer = descendants(panel).filterIsInstance<WorkingCommitPanel>().single()
        val commit = descendants(composer).filterIsInstance<javax.swing.JButton>()
            .single { it.accessibleContext.accessibleName == "Commit changes" }
        composer.message = "fix: include working content"
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.NO, testRootDisposable)
        commit.doClick()
        assertEquals("1", command("rev-list", "--count", "HEAD").trim())
        assertFalse(completed)
        assertEquals("fix: include working content", composer.message)
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.YES, testRootDisposable)
        commit.doClick()
        PlatformTestUtil.waitWithEventsDispatching("Stage All and Commit did not finish", { completed }, 30)
        assertEquals("new content\n", command("show", "HEAD:file.txt"))
        assertEquals("", composer.message)
    }

    fun testInlineCommitKeepsDraftAndStagedContentWhenHookFails() {
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", ".")
        val hook = root.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\necho 'Project checks failed' >&2\nexit 1\n")
        assertTrue(hook.toFile().setExecutable(true))
        var completed = false
        val panel = WorkingChangesPanel(project, { completed = true })
        Disposer.register(testRootDisposable, panel)
        panel.setRepository(RepositorySnapshot(root, git.status(root), emptyList()))
        panel.setChanges(root, git.workingChanges(root))
        val composer = descendants(panel).filterIsInstance<WorkingCommitPanel>().single()
        composer.message = "fix: retry after checks"
        descendants(composer).filterIsInstance<javax.swing.JButton>()
            .single { it.accessibleContext.accessibleName == "Commit changes" }.doClick()
        PlatformTestUtil.waitWithEventsDispatching("Failed commit did not finish", { completed }, 30)
        panel.setRepository(RepositorySnapshot(root, git.status(root), emptyList()))
        panel.setChanges(root, git.workingChanges(root))

        assertEquals("1", command("rev-list", "--count", "HEAD").trim())
        assertEquals("fix: retry after checks", composer.message)
        assertTrue(git.workingChanges(root).single().staged)
        assertTrue(descendants(composer).filterIsInstance<javax.swing.JLabel>().any {
            it.isVisible && it.toolTipText.orEmpty().contains("Project checks failed")
        })
    }

    fun testInlineCommitHonorsConventionalCommitSetting() {
        Files.writeString(root.resolve("file.txt"), "staged\n")
        command("add", ".")
        val settings = dev.bettergit.settings.BetterGitSettings.getInstance()
        val previous = settings.state
        try {
            settings.loadState(previous.copy(conventionalCommits = true))
            val panel = WorkingChangesPanel(project)
            Disposer.register(testRootDisposable, panel)
            panel.setRepository(RepositorySnapshot(root, git.status(root), emptyList()))
            panel.setChanges(root, git.workingChanges(root))
            val composer = descendants(panel).filterIsInstance<WorkingCommitPanel>().single()
            composer.message = "Not a conventional subject"
            descendants(composer).filterIsInstance<javax.swing.JButton>()
                .single { it.accessibleContext.accessibleName == "Commit changes" }.doClick()
            assertEquals("1", command("rev-list", "--count", "HEAD").trim())
            assertEquals("Not a conventional subject", composer.message)
            assertTrue(descendants(composer).filterIsInstance<javax.swing.JLabel>().any {
                it.isVisible && it.toolTipText.orEmpty().contains("Conventional Commit")
            })
        } finally { settings.loadState(previous) }
    }

    private fun descendants(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
        if (component is java.awt.Container) component.components.flatMap(::descendants) else emptyList()

    fun testRefusedDocumentSavePreventsGitMutation() {
        val document = document()
        edit(document, "must not be omitted from the stash\n")
        val vetoLifetime = Disposer.newDisposable()
        FileDocumentSynchronizationVetoer.EP_NAME.point.registerExtension(object : FileDocumentSynchronizationVetoer() {
            override fun maySaveDocument(candidate: Document, isSaveExplicit: Boolean): Boolean = candidate !== document
        }, vetoLifetime)
        try {
            var commandRan = false
            val failure = mutate { commandRan = true; git.stash(root, "Must not run") }
            assertNotNull(failure)
            assertFalse(commandRan)
            assertTrue(git.stashes(root).isEmpty())
            assertEquals("base\n", Files.readString(root.resolve("file.txt")))
        } finally {
            Disposer.dispose(vetoLifetime)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    fun testCheckoutRefreshesLoadedFileAndGitRepository() {
        command("checkout", "-b", "feature")
        Files.writeString(root.resolve("file.txt"), "feature branch content\n")
        command("add", ".")
        command("commit", "-m", "Feature")
        command("checkout", "main")
        val document = document()

        assertNull(mutate { git.checkout(root, "feature") })

        assertEquals("feature", repository.currentBranchName)
        assertEquals("feature branch content\n", document.text)
        assertEquals("feature branch content\n", virtualRoot.findChild("file.txt")!!.contentsToByteArray().toString(Charsets.UTF_8))
    }

    fun testApplySelectedStashReloadsDocumentAndKeepsStashes() {
        Files.writeString(root.resolve("file.txt"), "older selected stash\n")
        command("stash", "push", "-m", "Older")
        Files.writeString(root.resolve("file.txt"), "newer stash\n")
        command("stash", "push", "-m", "Newer")
        val document = document()

        assertNull(mutate { git.applyStash(root, "stash@{1}") })

        assertEquals("older selected stash\n", document.text)
        assertEquals(2, git.stashes(root).size)
    }

    fun testFailedStashApplyRefreshesConflictContentBeforeFailureCallback() {
        Files.writeString(root.resolve("file.txt"), "stashed edit\n")
        command("stash", "push", "-m", "Conflicting change")
        Files.writeString(root.resolve("file.txt"), "committed edit\n")
        command("add", ".")
        command("commit", "-m", "Change on branch")
        val document = document()
        var textAtFailure: String? = null

        val failure = mutate(onFailure = { textAtFailure = document.text }) {
            git.applyStash(root, "stash@{0}")
        }

        assertNotNull(failure)
        assertTrue(command("status", "--porcelain").contains("UU file.txt"))
        assertTrue(document.text.contains("<<<<<<<"))
        assertEquals(Files.readString(root.resolve("file.txt")), textAtFailure)
    }

    fun testQueuedMutationSavesEditsAfterPreviousMutationFinishes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val document = document()
        val failures = mutableListOf<Throwable>()
        val service = GitMutationService.getInstance(project)
        val first = service.submit(root, "First", onSuccess = {}, onFailure = failures::add) {
            started.countDown()
            check(release.await(30, TimeUnit.SECONDS))
        }
        PlatformTestUtil.waitWithEventsDispatching("First mutation did not start", { started.count == 0L }, 30)
        val second = service.submit(root, "Queued stash", onSuccess = {}, onFailure = failures::add) {
            git.stash(root, "Saved after waiting")
        }
        try {
            edit(document, "edited while first mutation was running\n")
        } finally {
            release.countDown()
        }
        await(first)
        await(second)

        assertEmpty(failures)
        assertEquals("edited while first mutation was running\n", command("show", "stash@{0}:file.txt"))
    }

    fun testCancellationStillRefreshesPartialDiskChanges() {
        val document = document()
        val started = CountDownLatch(1)
        var callbackInvoked = false
        val job = GitMutationService.getInstance(project).submit(root, "Cancelled operation",
            onSuccess = { callbackInvoked = true }, onFailure = { callbackInvoked = true }) {
            Files.writeString(root.resolve("file.txt"), "changed before cancellation\n")
            started.countDown()
            CountDownLatch(1).await()
        }
        PlatformTestUtil.waitWithEventsDispatching("Mutation did not start", { started.count == 0L }, 30)
        job.cancel()
        await(job)

        assertTrue(job.isCancelled)
        assertFalse(callbackInvoked)
        assertEquals("changed before cancellation\n", document.text)
    }

    private fun document(): Document {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("file.txt"))!!
        return FileDocumentManager.getInstance().getDocument(file)!!
    }

    private fun edit(document: Document, text: String) = WriteCommandAction.runWriteCommandAction(project) {
        document.setText(text)
    }

    private fun mutate(onFailure: () -> Unit = {}, task: () -> Unit): Throwable? {
        var failure: Throwable? = null
        var callbackInvoked = false
        val job = GitMutationService.getInstance(project).submit(root, "Test mutation", onSuccess = {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            callbackInvoked = true
        }, onFailure = {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            callbackInvoked = true
            failure = it
            onFailure()
        }, task = task)
        await(job)
        assertTrue("Mutation did not call its completion callback", callbackInvoked)
        return failure
    }

    private fun await(job: Job) =
        PlatformTestUtil.waitWithEventsDispatching("Git mutation did not finish", { job.isCompleted }, 30)

    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", root.toString()) + arguments)
            .redirectErrorStream(true).start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Git test command timed out" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}
