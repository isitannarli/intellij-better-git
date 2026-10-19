package dev.bettergit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.BlameLine
import dev.bettergit.services.WorkbenchService
import dev.bettergit.services.AuthorAvatarService
import dev.bettergit.settings.BetterGitSettings
import kotlinx.coroutines.CancellationException
import dev.bettergit.ui.BlameHoverCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

internal data class BlameHoverTarget(val root: Path, val path: String, val blame: BlameLine)

internal fun EditorMouseEvent.contentPoint(editor: Editor): Point? {
    if (area != EditorMouseEventArea.EDITING_AREA) return null
    return SwingUtilities.convertPoint(mouseEvent.component, mouseEvent.point, editor.contentComponent)
}

/** State is confined to the EDT; Git work runs in cancellable background tasks. */
internal class BlameHoverController(
    private val project: Project,
    private val editor: Editor,
    private val target: () -> BlameHoverTarget?,
    private val bounds: () -> Rectangle?,
) : Disposable {
    private val workbench = WorkbenchService.getInstance(project)
    private var opening: Job? = null
    private var loading: Job? = null
    private var avatarLoading: Job? = null
    private var closing: Job? = null
    private var popup: JBPopup? = null
    private var generation = 0L
    private var disposed = false
    private var dismissedUntilExit = false
    private val tracked = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())

    private val motionListener = object : EditorMouseMotionListener {
        override fun mouseMoved(event: EditorMouseEvent) {
            if (disposed || event.isConsumed) return
            val point = event.contentPoint(editor)
            if (point != null && bounds()?.contains(point) == true && target()?.blame?.commit != null) {
                closing?.cancel()
                if (!dismissedUntilExit && popup == null && opening?.isActive != true) {
                    val expected = target() ?: return
                    opening = workbench.scope.launch(Dispatchers.EDT) {
                        delay(OPEN_DELAY)
                        if (!disposed && target() == expected && pointerOverInlay()) show(expected)
                    }
                }
            } else {
                dismissedUntilExit = false
                opening?.cancel()
                scheduleClose()
            }
        }

        override fun mouseDragged(event: EditorMouseEvent) = dismiss()
    }
    private val mouseListener = object : EditorMouseListener {
        override fun mouseExited(event: EditorMouseEvent) {
            dismissedUntilExit = false
            opening?.cancel()
            scheduleClose()
        }

        override fun mousePressed(event: EditorMouseEvent) = dismiss()
    }
    private val popupMouseListener = object : MouseAdapter() {
        override fun mouseEntered(event: MouseEvent) { closing?.cancel() }
        override fun mouseExited(event: MouseEvent) = scheduleClose()
    }
    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) = dismiss()
    }
    private val scrollListener = VisibleAreaListener { event ->
        if (event.oldRectangle != event.newRectangle) dismiss()
    }

    init {
        editor.addEditorMouseMotionListener(motionListener)
        editor.addEditorMouseListener(mouseListener)
        editor.scrollingModel.addVisibleAreaListener(scrollListener)
        editor.contentComponent.addKeyListener(keyListener)
    }

    fun invalidate() {
        dismissedUntilExit = false
        close()
    }

    fun dismiss() {
        dismissedUntilExit = true
        close()
    }

    private fun show(expected: BlameHoverTarget) {
        if (disposed || project.isDisposed || editor.isDisposed || !editor.contentComponent.isShowing) return
        val anchor = bounds() ?: return
        val currentGeneration = ++generation
        val card = BlameHoverCard(project, editor.colorsScheme, expected.root, expected.path, expected.blame, ::dismiss)
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(card, null)
            .setProject(project)
            .setFocusable(true)
            .setRequestFocus(false)
            .setCancelKeyEnabled(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelOnWindowDeactivation(true)
            .setBelongsToGlobalPopupStack(true)
            .setLocateWithinScreenBounds(true)
            .setShowShadow(true)
            .createPopup()
        popup = created
        created.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === created) dismiss()
            }
        })
        trackMouse(card)
        val visible = editor.scrollingModel.visibleArea
        val x = anchor.x.coerceIn(visible.x, (visible.x + visible.width - JBUI.scale(40)).coerceAtLeast(visible.x))
        created.show(RelativePoint(editor.contentComponent, Point(x, anchor.y + anchor.height + JBUI.scale(3))))
        if (BetterGitSettings.getInstance().state.authorAvatars) {
            avatarLoading = workbench.scope.launch(Dispatchers.EDT) {
                try {
                    val image = project.service<AuthorAvatarService>().avatar(expected.root, requireNotNull(expected.blame.commit))
                    if (image != null && generation == currentGeneration && popup === created && !created.isDisposed &&
                        target() == expected && BetterGitSettings.getInstance().state.authorAvatars) card.avatarLoaded(image)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {  }
            }
        }
        loading = workbench.submit(
            title = "Load line commit preview",
            onSuccess = { details ->
                if (generation == currentGeneration && popup === created && !created.isDisposed && target() == expected) {
                    if (details != null) card.loaded(details) else card.unavailable()
                    trackMouse(card)
                    created.pack(true, true)
                    created.moveToFitScreen()
                }
            },
            onFailure = {
                if (generation == currentGeneration && popup === created && !created.isDisposed) {
                    card.unavailable()
                    trackMouse(card)
                    created.pack(true, true)
                    created.moveToFitScreen()
                }
            },
        ) { workbench.git.hoverDetails(expected.root, expected.blame, expected.path) }
    }

    private fun trackMouse(component: Component) {
        if (tracked.add(component)) component.addMouseListener(popupMouseListener)
        if (component is Container) component.components.forEach(::trackMouse)
    }

    private fun scheduleClose() {
        if (popup == null || closing?.isActive == true) return
        closing = workbench.scope.launch(Dispatchers.EDT) {
            delay(CLOSE_DELAY)
            val current = popup ?: return@launch
            if (!pointerOverInlay() && !pointerOver(current.content) && !current.isFocused) close()
        }
    }

    private fun pointerOverInlay(): Boolean {
        if (editor.isDisposed || !editor.contentComponent.isShowing) return false
        val point = pointerIn(editor.contentComponent) ?: return false
        return bounds()?.contains(point) == true
    }

    private fun pointerOver(component: Component): Boolean =
        component.isShowing && pointerIn(component)?.let(component::contains) == true

    private fun pointerIn(component: Component): Point? {
        if (!component.isShowing) return null
        val point = MouseInfo.getPointerInfo()?.location ?: return null
        SwingUtilities.convertPointFromScreen(point, component)
        return point
    }

    private fun close() {
        ++generation
        opening?.cancel()
        loading?.cancel()
        avatarLoading?.cancel()
        closing?.cancel()
        opening = null
        loading = null
        avatarLoading = null
        closing = null
        val previous = popup
        popup = null
        if (previous != null && !previous.isDisposed) previous.cancel()
        tracked.forEach { it.removeMouseListener(popupMouseListener) }
        tracked.clear()
    }

    override fun dispose() {
        disposed = true
        close()
        editor.removeEditorMouseMotionListener(motionListener)
        editor.removeEditorMouseListener(mouseListener)
        editor.scrollingModel.removeVisibleAreaListener(scrollListener)
        editor.contentComponent.removeKeyListener(keyListener)
    }

    companion object {
        private const val OPEN_DELAY = 400L
        private const val CLOSE_DELAY = 350L
    }
}
