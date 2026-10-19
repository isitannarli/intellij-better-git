package dev.bettergit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import dev.bettergit.services.WorkbenchService
import dev.bettergit.settings.BetterGitSettings
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class InlineBlameService(private val project: Project) : Disposable {
    private val workbench get() = project.service<WorkbenchService>()
    private val sessions = mutableMapOf<Editor, Session>()
    private var started = false

    fun start() {
        if (started || project.isDisposed) return
        started = true
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = attach(event.editor)
            override fun editorReleased(event: EditorFactoryEvent) {
                sessions.remove(event.editor)?.let(Disposer::dispose)
            }
        }, this)
        EditorFactory.getInstance().allEditors.forEach(::attach)
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refreshAll() })
        val appConnection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this)
        appConnection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refreshAll() })
        appConnection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun afterDocumentSaved(document: com.intellij.openapi.editor.Document) = refreshAll(document)
            override fun fileContentReloaded(
                file: com.intellij.openapi.vfs.VirtualFile,
                document: com.intellij.openapi.editor.Document,
            ) = refreshAll(document)
        })
    }

    fun refreshAll(document: Document? = null) {
        if (project.isDisposed) return
        workbench.scope.launch(Dispatchers.EDT) {
            sessions.filterKeys { document == null || it.document === document }.values.forEach { it.schedule() }
        }
    }

    private fun attach(editor: Editor) {
        if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR ||
            editor.isViewer || sessions.containsKey(editor)
        ) return
        val session = Session(editor)
        sessions[editor] = session
        Disposer.register(this, session)
        session.schedule()
    }

    override fun dispose() {
        sessions.clear()
    }

    private inner class Session(private val editor: Editor) : Disposable {
        private var pending: Job? = null
        private var request: Job? = null
        private var inlay: Inlay<BlameRenderer>? = null
        private var result: BlameHoverTarget? = null
        private val hover = BlameHoverController(project, editor, { result }, { inlay?.bounds })
        private var generation = 0L
        private var disposed = false
        private val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                hover.dismiss()
                if (event.oldPosition.line != event.newPosition.line) schedule()
            }
        }
        private val hierarchyListener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) schedule()
        }
        private val focusListener = object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = schedule()
        }

        init {
            editor.caretModel.addCaretListener(caretListener)
            editor.contentComponent.addHierarchyListener(hierarchyListener)
            editor.contentComponent.addFocusListener(focusListener)
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = schedule()
            }, this)
        }

        fun schedule() {
            if (disposed || project.isDisposed) return
            if (!com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread) {
                workbench.scope.launch(Dispatchers.EDT) { schedule() }
                return
            }
            pending?.cancel()
            request?.cancel()
            val currentGeneration = ++generation
            pending = workbench.scope.launch(Dispatchers.EDT) {
                clear()
                delay(DEBOUNCE_MILLIS)
                if (disposed || editor.isDisposed || !editor.contentComponent.isShowing ||
                    !BetterGitSettings.getInstance().state.inlineBlame
                ) return@launch
                val document = editor.document
                val files = FileDocumentManager.getInstance()
                if (files.isDocumentUnsaved(document) || document.lineCount > MAX_AUTO_LINES ||
                    document.textLength > MAX_AUTO_CHARACTERS
                ) return@launch
                val file = files.getFile(document) ?: return@launch
                if (!file.isInLocalFileSystem || !file.isValid || file.isDirectory) return@launch
                val line = editor.caretModel.logicalPosition.line
                val stamp = document.modificationStamp
                request = workbench.submit(
                    title = "Read line authorship",
                    onSuccess = { loaded: BlameHoverTarget? ->
                        if (disposed || editor.isDisposed || generation != currentGeneration ||
                            document.modificationStamp != stamp || files.isDocumentUnsaved(document) ||
                            !editor.contentComponent.isShowing ||
                            editor.caretModel.logicalPosition.line != line ||
                            !BetterGitSettings.getInstance().state.inlineBlame
                        ) return@submit
                        loaded ?: return@submit
                        result = loaded
                        val text = loaded.blame.commit?.let { commit ->
                            "${singleLine(commit.author, 35)} · ${DATE.format(commit.timestamp)} · ${singleLine(commit.subject, 95)}"
                        } ?: "Uncommitted changes"
                        inlay = editor.inlayModel.addAfterLineEndElement(
                            document.getLineEndOffset(line), false, BlameRenderer("    $text"),
                        )
                    },
                    onFailure = { if (generation == currentGeneration) clear() },
                ) {
                    val root = workbench.rootFor(file)
                    if (root == null) null else {
                        val path = root.relativize(Path.of(file.path)).toString().replace('\\', '/')
                        workbench.git.blame(root, path, line + 1)?.let { BlameHoverTarget(root, path, it) }
                    }
                }
            }
        }

        private fun clear() {
            hover.invalidate()
            inlay?.let(Disposer::dispose)
            inlay = null
            result = null
        }

        override fun dispose() {
            disposed = true
            ++generation
            pending?.cancel()
            request?.cancel()
            hover.dispose()
            editor.caretModel.removeCaretListener(caretListener)
            editor.contentComponent.removeHierarchyListener(hierarchyListener)
            editor.contentComponent.removeFocusListener(focusListener)
            clear()
        }
    }

    private class BlameRenderer(private val text: String) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val font = inlay.editor.colorsScheme.getFont(EditorFontType.ITALIC)
            return inlay.editor.contentComponent.getFontMetrics(font).stringWidth(text).coerceAtLeast(1)
        }

        override fun paint(inlay: Inlay<*>, graphics: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val copy = graphics.create()
            try {
                copy.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                copy.color = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                copy.drawString(text, targetRegion.x, targetRegion.y + editor.ascent)
            } finally {
                copy.dispose()
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 350L
        private const val MAX_AUTO_LINES = 20_000
        private const val MAX_AUTO_CHARACTERS = 2_000_000
        private val DATE = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault())
        private fun singleLine(value: String, limit: Int): String {
            val text = value.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
            return if (text.length <= limit) text else text.take(limit - 1) + "…"
        }
    }
}
