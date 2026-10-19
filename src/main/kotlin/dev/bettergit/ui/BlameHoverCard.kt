package dev.bettergit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.BlameLine
import dev.bettergit.core.git.CommitHoverDetails
import dev.bettergit.core.git.DiffLineKind
import dev.bettergit.core.git.GitCommit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

internal class BlameHoverCard @JvmOverloads constructor(
    private val project: Project,
    private val scheme: EditorColorsScheme,
    private val root: Path,
    private val currentPath: String,
    blame: BlameLine,
    private val dismiss: () -> Unit,
    private val syntaxForPath: (String) -> SyntaxHighlighter? = { HoverSyntax.resolve(project, it) },
) : JPanel(BorderLayout(0, JBUI.scale(10))) {
    private val commit: GitCommit = requireNotNull(blame.commit)
    private var details: CommitHoverDetails? = null
    private val body = JPanel(BorderLayout(0, JBUI.scale(10))).apply { isOpaque = false }
    private val preview = JPanel(BorderLayout(0, JBUI.scale(6))).apply { isOpaque = false }
    private val diffButton = action("Open diff", "Open this file's change in the IDE diff viewer") {
        val loaded = details ?: return@action
        val change = loaded.change ?: return@action
        dismiss()
        CommitDetails.showFile(project, root, loaded.commit, change)
    }.apply { isEnabled = false }
    private val cardWidth = JBUI.scale(540)
    private val authorAvatar = Ui.label("").apply {
        icon = InitialsIcon(commit.author)
        toolTipText = "Commit author: ${clean(commit.author)}"
        getAccessibleContext().accessibleName = "Commit author avatar"
    }

    init {
        border = JBUI.Borders.empty(12)
        getAccessibleContext().accessibleName = "Line commit details"
        add(header(), BorderLayout.NORTH)
        body.add(message(commit.subject), BorderLayout.NORTH)
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(action(commit.hash.take(8), "Copy full commit hash").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(commit.hash))
                    toolTipText = "Commit hash copied"
                }
            })
            add(diffButton)
            add(action("Commit", "Inspect every file changed by this commit") {
                dismiss()
                CommitDetails.show(project, root, details?.commit ?: commit)
            })
            add(action("File history", "Open the current file's history") {
                dismiss()
                FileHistoryDialog.show(project, root, currentPath)
            })
            add(iconAction(AllIcons.General.Web, "Open this commit on GitHub or GitLab in your browser") {
                dismiss()
                CommitHostingActions.openCommit(project, root, commit.hash)
            })
            add(iconAction(AllIcons.Vcs.Merge, "Find and open the pull or merge request associated with this commit") {
                dismiss()
                CommitHostingActions.openReviews(project, root, commit.hash)
            })
        }
        for (index in 4 downTo 0) actions.add(Box.createHorizontalStrut(JBUI.scale(4)), index + 1)
        body.add(object : JBScrollPane(actions) {
            override fun getPreferredSize(): Dimension {
                val available = width.takeIf { it > 0 } ?: (cardWidth - JBUI.scale(24))
                val extra = if (actions.preferredSize.width > available) horizontalScrollBar.preferredSize.height else 0
                return Dimension(cardWidth - JBUI.scale(24), actions.preferredSize.height + extra)
            }
        }.apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            getAccessibleContext().accessibleName = "Commit actions; scroll horizontally if needed"
        }, BorderLayout.CENTER)
        preview.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.emptyTop(8),
        )
        preview.add(Ui.label("Loading change from local Git…", true), BorderLayout.CENTER)
        body.add(preview, BorderLayout.SOUTH)
        add(body, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().let {
        Dimension(cardWidth, it.height)
    }

    fun loaded(value: CommitHoverDetails) {
        details = value
        body.remove((body.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH))
        body.add(message(value.message), BorderLayout.NORTH)
        diffButton.isEnabled = value.change != null
        preview.removeAll()
        val path = Ui.label("${clean(value.originalPath)} : ${value.originalLine}", true).apply {
            toolTipText = "Path and line at ${value.commit.hash.take(8)}"
        }
        preview.add(path, BorderLayout.NORTH)
        val hunk = value.preview
        when {
            hunk == null -> preview.add(Ui.label("No line change in this commit's first-parent diff.", true), BorderLayout.CENTER)
            hunk.binary -> preview.add(Ui.label("Binary change — preview unavailable.", true), BorderLayout.CENTER)
            else -> {
                val lines = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = scheme.defaultBackground
                }
                val syntax = HoverSyntax.highlight(hunk.lines,
                    syntaxForPath(value.change?.oldPath ?: value.originalPath),
                    syntaxForPath(value.originalPath), scheme)
                hunk.lines.forEachIndexed { index, line ->
                    val sign = when (line.kind) {
                        DiffLineKind.ADDED -> "+"
                        DiffLineKind.REMOVED -> "−"
                        DiffLineKind.CONTEXT -> " "
                        DiffLineKind.INFO -> "⋯"
                    }
                    val number = (line.newLine ?: line.oldLine)?.toString().orEmpty().padStart(5)
                    val active = line.newLine == value.originalLine
                    lines.add(SimpleColoredComponent().apply {
                        font = scheme.getFont(EditorFontType.PLAIN)
                        background = when (line.kind) {
                            DiffLineKind.ADDED -> scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor
                            DiffLineKind.REMOVED -> scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor
                            else -> null
                        } ?: scheme.defaultBackground
                        isOpaque = true
                        ipad = JBUI.insets(2, 5)
                        append("${if (active) "›" else " "} $number  $sign ", SimpleTextAttributes(
                            if (active) Font.BOLD else Font.PLAIN, Ui.muted,
                        ))
                        syntax[index].forEach { fragment ->
                            append(clean(fragment.text.replace("\t", "    ")), SimpleTextAttributes(
                                fragment.fontStyle, fragment.foreground,
                            ))
                        }
                        getAccessibleContext().accessibleName = "$number $sign ${clean(line.text)}"
                        alignmentX = LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    })
                }
                val rowHeight = lines.components.firstOrNull()?.preferredSize?.height ?: JBUI.scale(20)
                val scroll = JBScrollPane(lines).apply {
                    border = JBUI.Borders.empty()
                    viewport.background = scheme.defaultBackground
                    preferredSize = Dimension(cardWidth - JBUI.scale(24), rowHeight * hunk.lines.size.coerceIn(1, 8) + JBUI.scale(12))
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    toolTipText = clean(hunk.header)
                    getAccessibleContext().accessibleName = "Commit diff preview; marked row is the annotated line"
                }
                preview.add(scroll, BorderLayout.CENTER)
                val focus = hunk.lines.indexOfFirst { it.newLine == value.originalLine }
                SwingUtilities.invokeLater {
                    if (scroll.isShowing && focus >= 0) {
                        val y = ((focus - 3).coerceAtLeast(0) * rowHeight)
                            .coerceAtMost((lines.height - scroll.viewport.height).coerceAtLeast(0))
                        scroll.viewport.viewPosition = Point(0, y)
                    }
                }
                preview.add(Ui.label(
                    if (hunk.truncated) "Excerpt around this line · Open diff for the full change"
                    else "Changes in ${value.commit.hash.take(8)} · compared with ${value.commit.parents.firstOrNull()?.take(8) ?: "empty tree"}",
                    true,
                ), BorderLayout.SOUTH)
            }
        }
        revalidate()
        repaint()
    }

    fun unavailable() {
        preview.removeAll()
        preview.add(Ui.label("Preview unavailable. Open Commit to inspect the change.", true), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun avatarLoaded(image: BufferedImage) {
        authorAvatar.icon = PhotoIcon(image)
        authorAvatar.repaint()
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        add(authorAvatar, BorderLayout.WEST)
        add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
            isOpaque = false
            add(Ui.label(clean(commit.author)).apply {
                font = font.deriveFont(Font.BOLD)
                toolTipText = clean(commit.email)
            }, BorderLayout.NORTH)
            add(Ui.label("${relativeTime(commit.timestamp)} · ${DATE.format(commit.timestamp)}", true), BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    }

    private fun message(text: String): JComponent {
        val displayed = if (text.length > MAX_MESSAGE) text.take(MAX_MESSAGE) + "\n… Message truncated" else text
        return JBScrollPane(JBTextArea(clean(displayed, keepNewlines = true)).apply {
            font = Ui.label("").font
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            caretPosition = 0
            getAccessibleContext().accessibleName = "Commit message"
        }).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            val rows = displayed.lines().sumOf { (it.length / 65 + 1) }.coerceIn(1, 4)
            preferredSize = Dimension(cardWidth - JBUI.scale(24), getFontMetrics(Ui.label("").font).height * rows + JBUI.scale(4))
        }
    }

    private fun iconAction(icon: Icon, tooltip: String, run: () -> Unit): JButton = action("", tooltip, run).apply {
        this.icon = icon
        val height = diffButton.preferredSize.height
        preferredSize = Dimension(maxOf(JBUI.scale(28), icon.iconWidth + JBUI.scale(12)), height)
        minimumSize = preferredSize
    }

    private fun action(text: String, tooltip: String, run: () -> Unit = {}): JButton =
        Ui.button(text, tooltip, run).apply {
            putClientProperty("html.disable", true)
            margin = JBUI.insets(3, 8)
            getAccessibleContext().accessibleName = tooltip
        }

    private class PhotoIcon(private val image: BufferedImage) : Icon {
        override fun getIconWidth() = JBUI.scale(36)
        override fun getIconHeight() = iconWidth
        override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.clip(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), iconWidth.toDouble(), iconHeight.toDouble(), JBUI.scale(10).toDouble(), JBUI.scale(10).toDouble()))
                g.drawImage(image, x, y, iconWidth, iconHeight, null)
            } finally { g.dispose() }
        }
    }

    private class InitialsIcon(author: String) : Icon {
        private val initials = author.trim().split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull() }.joinToString("").uppercase().ifBlank { "?" }
        override fun getIconWidth() = JBUI.scale(36)
        override fun getIconHeight() = iconWidth
        override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = JBColor.namedColor("Component.infoForeground", JBColor(Color(0x3366BB), Color(0x82AAFF)))
                g.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(10), JBUI.scale(10))
                g.color = JBColor.namedColor("Label.selectedForeground", JBColor.WHITE)
                g.font = component.font.deriveFont(Font.BOLD)
                val metrics = g.fontMetrics
                g.drawString(initials, x + (iconWidth - metrics.stringWidth(initials)) / 2,
                    y + (iconHeight - metrics.height) / 2 + metrics.ascent)
            } finally { g.dispose() }
        }
    }

    companion object {
        private const val MAX_MESSAGE = 12_000
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
        private fun clean(text: String, keepNewlines: Boolean = false): String =
            text.replace(if (keepNewlines) Regex("[\\p{Cntrl}&&[^\\n\\t]]|\\p{Cf}") else Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
        private fun relativeTime(timestamp: Instant): String {
            val age = Duration.between(timestamp, Instant.now())
            val days = age.toDays()
            val (count, unit) = when {
                age.isNegative -> return "In the future"
                days >= 365 -> days / 365 to "year"
                days >= 30 -> days / 30 to "month"
                days >= 1 -> days to "day"
                age.toHours() >= 1 -> age.toHours() to "hour"
                age.toMinutes() >= 1 -> age.toMinutes() to "minute"
                else -> return "Just now"
            }
            return "$count $unit${if (count == 1L) "" else "s"} ago"
        }
    }
}
