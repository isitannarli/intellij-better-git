package dev.bettergit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.FileChange
import dev.bettergit.core.git.GitCommit
import dev.bettergit.services.WorkbenchService
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JComponent

object CommitDetails {
    fun show(project: Project, root: Path, commit: GitCommit) {
        CommitInspectorDialog(project, root, commit).show()
    }

    fun showFile(project: Project, root: Path, commit: GitCommit, change: FileChange) {
        CommitInspectorDialog(project, root, commit, change.path).show()
    }
}

object FileHistoryDialog {
    fun show(project: Project, root: Path, path: String) {
        val service = WorkbenchService.getInstance(project)
        service.submit("Load file history", onSuccess = { commits ->
            object : DialogWrapper(project, false) {
                init { title = "History · $path"; setModal(false); init() }
                override fun createCenterPanel(): JComponent = HistoryPanel(project).apply {
                    preferredSize = Dimension(JBUI.scale(800), JBUI.scale(480))
                    setCommits(root, commits)
                }
                override fun createActions() = arrayOf(okAction)
            }.show()
        }) { service.git.log(root, path, 200) }
    }
}
