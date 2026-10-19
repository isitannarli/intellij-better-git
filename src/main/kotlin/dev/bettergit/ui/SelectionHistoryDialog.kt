package dev.bettergit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import dev.bettergit.core.git.LineRange
import dev.bettergit.services.WorkbenchService
import kotlinx.coroutines.Job
import java.nio.file.Path
import javax.swing.JComponent

object SelectionHistoryDialog {
    fun show(project: Project, root: Path, path: String, range: LineRange, currentText: String) {
        val application = ApplicationManager.getApplication()
        val open = Runnable {
            if (!project.isDisposed) SelectionDialog(project, root, path, range, currentText).open()
        }
        if (application.isDispatchThread) open.run() else application.invokeLater(open)
    }

    private class SelectionDialog(
        private val project: Project,
        private val root: Path,
        private val path: String,
        private val range: LineRange,
        private val currentText: String,
    ) : DialogWrapper(project, false) {
        private var request: Job? = null
        private var generation = 0L
        private var closed = false
        private val panel = SelectionHistoryPanel(project, root, path, range, ::load)

        init {
            title = "Selection history · ${path.substringAfterLast('/')} : ${range.start}–${range.end}"
            setModal(false)
            setOKButtonText("Close")
            init()
            ApplicationManager.getApplication().messageBus.connect(disposable)
                .subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                    ApplicationManager.getApplication().invokeLater {
                        if (!closed && !project.isDisposed) panel.refreshTheme()
                    }
                })
        }

        fun open() {
            show()
            if (!closed) load()
        }

        private fun load() {
            if (closed || project.isDisposed) return
            request?.cancel()
            val expected = ++generation
            panel.showLoading()
            val service = WorkbenchService.getInstance(project)
            request = service.submit(
                title = "Read selected code history",
                onSuccess = { history ->
                    if (!closed && generation == expected && !project.isDisposed) panel.showHistory(history)
                },
                onFailure = { failure ->
                    if (!closed && generation == expected && !project.isDisposed) {
                        panel.showFailure(failure.message ?: "Git could not read this selection's history. Check that the file belongs to this repository.")
                    }
                },
            ) { service.git.selectionHistory(root, path, range, currentText, limit = 200) }
        }

        override fun createCenterPanel(): JComponent = panel
        override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
        override fun getDimensionServiceKey(): String = "BetterGit.SelectionHistory"
        override fun createActions() = arrayOf(okAction)

        override fun dispose() {
            closed = true
            ++generation
            request?.cancel()
            panel.stopLoading()
            super.dispose()
        }
    }
}
