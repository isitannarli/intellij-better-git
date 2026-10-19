package dev.bettergit.ui.graph

import com.intellij.openapi.util.IconLoader

/** Git concepts use stable outlines, independently of the IDE's file-type and markup-tag icons. */
internal object GraphIcons {
    val file = IconLoader.getIcon("/icons/graph/file.svg", javaClass)
    val remote = IconLoader.getIcon("/icons/graph/remote.svg", javaClass)
    val tag = IconLoader.getIcon("/icons/graph/tag.svg", javaClass)
    val stash = IconLoader.getIcon("/icons/graph/stash.svg", javaClass)
}
