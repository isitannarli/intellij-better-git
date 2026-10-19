package dev.bettergit.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile

internal fun AnActionEvent.currentEditor(): Editor? = getData(CommonDataKeys.EDITOR)
    ?: project?.let { FileEditorManager.getInstance(it).selectedTextEditor }

internal fun AnActionEvent.currentFile(): VirtualFile? = getData(CommonDataKeys.VIRTUAL_FILE)
    ?: currentEditor()?.document?.let { FileDocumentManager.getInstance().getFile(it) }
