package dev.bettergit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.BlameSpan
import dev.bettergit.core.git.BlameSpans
import dev.bettergit.services.WorkbenchService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@Service(Service.Level.PROJECT)
class FileHeatmapService(private val project: Project) : Disposable {
    private val workbench get() = project.service<WorkbenchService>()
    private val sessions = mutableMapOf<Editor, HeatmapSession>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                sessions.remove(event.editor)?.let(Disposer::dispose)
            }
        }, this)
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { refreshAll() })
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refreshAll() })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                // Queue the refresh after saving, including on IDEs without the after-save callback.
                ApplicationManager.getApplication().invokeLater { refreshAll(document) }
            }
            override fun fileContentReloaded(file: VirtualFile, document: Document) = refreshAll(document)
        })
    }

    fun isEnabled(editor: Editor): Boolean = sessions.containsKey(editor)

    fun setEnabled(editor: Editor, enabled: Boolean) {
        if (editor.isDisposed || editor.project != project) return
        if (!enabled) {
            sessions.remove(editor)?.let(Disposer::dispose)
        } else if (!sessions.containsKey(editor)) {
            val session = HeatmapSession(editor)
            sessions[editor] = session
            Disposer.register(this, session)
            session.refresh(reportFailure = true)
        }
    }

    private fun refreshAll(document: Document? = null) {
        if (project.isDisposed) return
        workbench.scope.launch(Dispatchers.EDT) {
            sessions.filterKeys { document == null || it.document === document }.values.forEach { it.refresh() }
        }
    }

    override fun dispose() { sessions.clear() }

    private inner class HeatmapSession(private val editor: Editor) : Disposable {
        private val highlighters = mutableListOf<RangeHighlighter>()
        private var debounce: Job? = null
        private var request: Job? = null
        private var generation = 0L
        private var disposed = false
        private val focusListener = object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = refresh()
        }

        private val hierarchyListener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) refresh()
        }

        init {
            editor.contentComponent.addHierarchyListener(hierarchyListener)
            editor.contentComponent.addFocusListener(focusListener)
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = refresh()
            }, this)
        }

        fun refresh(reportFailure: Boolean = false) {
            if (disposed || project.isDisposed) return
            if (!ApplicationManager.getApplication().isDispatchThread) {
                workbench.scope.launch(Dispatchers.EDT) { refresh(reportFailure) }
                return
            }
            debounce?.cancel()
            request?.cancel()
            val version = ++generation
            debounce = workbench.scope.launch(Dispatchers.EDT) {
                clear()
                delay(400)
                if (disposed || editor.isDisposed || !editor.contentComponent.isShowing) return@launch
                val document = editor.document
                val files = FileDocumentManager.getInstance()
                if (files.isDocumentUnsaved(document)) return@launch
                if (document.lineCount > 20_000 || document.textLength > 2_000_000) {
                    if (reportFailure) workbench.notifyError("Age heatmap",
                        IllegalStateException("This file exceeds the automatic heatmap limit (20,000 lines or 2 million characters). Line inspection remains available."))
                    return@launch
                }
                val file = files.getFile(document) ?: return@launch
                if (!file.isInLocalFileSystem || !file.isValid) return@launch
                val stamp = document.modificationStamp
                request = workbench.submit("Read file age", onSuccess = { spans: List<BlameSpan> ->
                    if (disposed || editor.isDisposed || generation != version ||
                        document.modificationStamp != stamp || files.isDocumentUnsaved(document) || !editor.contentComponent.isShowing
                    ) return@submit
                    render(spans)
                }, onFailure = { failure ->
                    if (generation == version) clear()
                    if (reportFailure) workbench.notifyError("Age heatmap", failure)
                }) {
                    val root = workbench.rootFor(file)
                        ?: throw IllegalStateException("No Git repository was found for this file.")
                    BlameSpans.compact(workbench.git.blameFile(
                        root, root.relativize(Path.of(file.path)).toString().replace('\\', '/'),
                    ))
                }
            }
        }

        private fun render(spans: List<BlameSpan>) {
            clear()
            val now = Instant.now()
            val document = editor.document
            for (span in spans) {
                val number = span.startLine - 1
                if (number !in 0 until document.lineCount) continue
                val last = (span.endLine - 1).coerceAtMost(document.lineCount - 1)
                val commit = span.commit
                val days = Duration.between(commit.timestamp, now).toDays().coerceAtLeast(0)
                val color = ageColor(days)
                val attributes = TextAttributes().apply { backgroundColor = Color(color.red, color.green, color.blue, 19) }
                val marker = if (number == last) editor.markupModel.addLineHighlighter(number, HighlighterLayer.ADDITIONAL_SYNTAX - 1, attributes)
                    else editor.markupModel.addRangeHighlighter(
                        document.getLineStartOffset(number), document.getLineEndOffset(last),
                        HighlighterLayer.ADDITIONAL_SYNTAX - 1, attributes, HighlighterTargetArea.LINES_IN_RANGE,
                    )
                marker.setErrorStripeMarkColor(color)
                marker.isThinErrorStripeMark = true
                marker.errorStripeTooltip = "Age: $days days · ${commit.author} · ${commit.hash.take(8)}"
                marker.lineMarkerRenderer = LineMarkerRenderer { _, graphics, bounds ->
                    val copy = graphics.create()
                    try {
                        copy.color = color
                        copy.fillRect(bounds.x, bounds.y, JBUI.scale(3), bounds.height)
                    } finally { copy.dispose() }
                }
                highlighters += marker
            }
        }

        private fun clear() {
            if (!editor.isDisposed) highlighters.forEach { highlighter ->
                if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
            }
            highlighters.clear()
        }

        override fun dispose() {
            disposed = true
            ++generation
            debounce?.cancel()
            request?.cancel()
            editor.contentComponent.removeFocusListener(focusListener)
            editor.contentComponent.removeHierarchyListener(hierarchyListener)
            clear()
        }
    }

    companion object {
        private fun ageColor(days: Long): Color = when {
            days < 7 -> JBColor(Color(188, 66, 49), Color(242, 128, 105))
            days < 30 -> JBColor(Color(177, 114, 25), Color(222, 173, 93))
            days < 90 -> JBColor(Color(55, 126, 142), Color(106, 184, 190))
            else -> JBColor(Color(100, 107, 158), Color(142, 148, 196))
        }
    }
}
