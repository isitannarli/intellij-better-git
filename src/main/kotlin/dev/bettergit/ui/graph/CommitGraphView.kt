package dev.bettergit.ui.graph

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.graph.GraphRefKind
import dev.bettergit.services.RepositorySnapshot
import dev.bettergit.ui.Ui
import java.awt.*
import java.awt.event.*
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import kotlin.math.ln
import kotlin.math.roundToInt

class CommitGraphView(private val listener: Listener) : JPanel(BorderLayout()) {
    interface Listener {
        fun repositoryChanged(repository: RepositorySnapshot) {}
        fun scopeChanged(scope: GraphScope) {}
        fun refresh() {}
        fun fetch() {}
        fun push() {}
        fun settings() {}
        fun more() {}
        fun search(query: String) {}
        fun inspect(commit: GitCommit) {}
        fun copy(commit: GitCommit) {}
        fun openCommit(commit: GitCommit) {}
        fun openReviews(commit: GitCommit) {}
        fun viewportChanged() {}
    }
    private val repositories = JComboBox<RepositorySnapshot>()
    private val scopes = JComboBox<GraphScope>()
    val search = JBTextField().apply {
        emptyText.text = "Search commits · message, author:name, sha:… or ref:branch"
        accessibleContext.accessibleName = "Search commit graph"
    }
    private val results = Ui.label("").apply { preferredSize = JBUI.size(100, 24) }
    private val summary = Ui.label("Open a local Git repository to explore its graph.", true)
    private val status = Ui.label("", true)
    private val more = Ui.button("Load older commits") { listener.more() }
    private val refresh = Ui.iconButton(AllIcons.Actions.Refresh, "Refresh local commit graph") { listener.refresh() }
    private val push = Ui.button("Push ↑", "Open the IDE's push dialog") { listener.push() }
    private val fetch = Ui.button("Fetch ↓", "Fetch remote refs explicitly") { listener.fetch() }
    private val previous = Ui.iconButton(AllIcons.Actions.MoveUp, "Previous search result (Shift+Enter)") { navigate(-1) }
    private val next = Ui.iconButton(AllIcons.Actions.MoveDown, "Next search result (Enter)") { navigate(1) }
    private val model = GraphTableModel()
    val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val entry = snapshot?.history?.commits?.getOrNull(row) ?: return null
            return when (columnAtPoint(event.point)) {
                0 -> snapshot?.references?.get(entry.commit.hash)?.joinToString(" · ") { it.name }
                1 -> "${entry.commit.hash} · ${entry.commit.parents.size} parent(s)"
                3 -> "<html>${entry.changes.files} changed files · +${entry.changes.added} added / −${entry.changes.removed} deleted lines" +
                    "<br>Green: added lines · red: deleted lines · bar length: relative change size (logarithmic)" +
                    "<br>Merges compared with first parent; binary files count as files, without line counts</html>"
                4 -> "${entry.commit.author} <${entry.commit.email}>"
                5 -> "Committed ${date.format(entry.committedAt.atZone(ZoneId.systemDefault()))} · authored ${date.format(entry.commit.timestamp.atZone(ZoneId.systemDefault()))}"
                6 -> entry.commit.hash
                else -> entry.commit.subject
            }
        }
    }
    private val scroll = object : JBScrollPane(table) {
        override fun doLayout() { super.doLayout(); fitMessageColumn() }
    }
    private val activity = GraphMinimap(::select)
    private val rail = GraphRefSidebar(::navigateToReference)
    private val timer = Timer(180) { listener.search(search.text.trim()) }.apply { isRepeats = false }
    private var adjusting = false
    private var busy = false
    private var matches: List<Int> = emptyList()
    private var matchedRows = emptySet<Int>()
    var snapshot: GraphSnapshot? = null
        private set
    private val avatars = object : LinkedHashMap<String, BufferedImage>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?) = size > 128
    }

    init {
        border = JBUI.Borders.empty(8, 10, 4, 10)
        repositories.maximumRowCount = 15
        repositories.preferredSize = JBUI.size(230, 30)
        repositories.renderer = Ui.renderer { label, value: RepositorySnapshot, _ -> label.text = value.name; label.toolTipText = value.root.toString() }
        repositories.addActionListener { if (!adjusting) (repositories.selectedItem as? RepositorySnapshot)?.let(listener::repositoryChanged) }
        scopes.preferredSize = JBUI.size(200, 30)
        scopes.maximumRowCount = 18
        scopes.addActionListener { if (!adjusting) (scopes.selectedItem as? GraphScope)?.let(listener::scopeChanged) }
        scopes.renderer = Ui.renderer { label, scope: GraphScope, _ -> label.text = scope.title; label.toolTipText = scope.title }
        val tools = horizontal(push, fetch, refresh, Ui.iconButton(AllIcons.General.Settings, "Better Git settings") { listener.settings() })
        val top = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(horizontal(Ui.label("COMMIT GRAPH").apply { font = font.deriveFont(Font.BOLD) }, repositories), BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
            add(tools, BorderLayout.EAST)
        }
        val find = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(scopes, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(horizontal(results, previous, next), BorderLayout.EAST)
        }
        add(JPanel(BorderLayout(0, JBUI.scale(7))).apply {
            isOpaque = false
            add(top, BorderLayout.NORTH)
            add(find, BorderLayout.CENTER)
            add(activity, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.tableHeader.defaultRenderer = GraphHeaderRenderer()
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.resizingAllowed = true
        table.rowHeight = maxOf(JBUI.scale(34), table.getFontMetrics(table.font).height + JBUI.scale(10))
        table.emptyText.text = "No local commits yet."
        table.accessibleContext.accessibleName = "Commit graph; Enter inspects the selected commit, right click for actions"
        val widths = intArrayOf(205, 140, 440, 260, 170, 175, 100)
        val renderer = GraphCellRenderer()
        widths.forEachIndexed { i, width -> table.columnModel.getColumn(i).apply {
            preferredWidth = JBUI.scale(width); this.width = preferredWidth
            minWidth = JBUI.scale(if (i == 1) 70 else 65)
            cellRenderer = renderer
        } }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 2 && table.rowAtPoint(event.point) >= 0) selected()?.let(listener::inspect)
            }
            override fun mousePressed(event: MouseEvent) { popup(event) }
            override fun mouseReleased(event: MouseEvent) { popup(event) }
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "inspect")
        table.actionMap.put("inspect", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { selected()?.let(listener::inspect) } })
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) { updateResults(); activity.selectedRow = table.selectedRow } }
        scroll.viewport.addChangeListener { listener.viewportChanged(); updateVisibleRange() }
        scroll.setColumnHeaderView(table.tableHeader)
        scroll.border = JBUI.Borders.customLine(JBColor.border())
        add(JPanel(BorderLayout()).apply { add(rail, BorderLayout.WEST); add(scroll, BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(4)
            add(summary, BorderLayout.CENTER); add(more, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { timer.restart() } })
        search.addActionListener { if (timer.isRunning) { timer.stop(); listener.search(search.text.trim()) } else navigate(1) }
        search.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous")
        search.actionMap.put("previous", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { navigate(-1) } })
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { fitMessageColumn() }
        })
        adjusting = true
        scopes.model = DefaultComboBoxModel(arrayOf(GraphScope("All branches", null), GraphScope("Current branch · HEAD", "HEAD")))
        adjusting = false
        idle()
        more.isVisible = false
        previous.isEnabled = false; next.isEnabled = false
    }

    fun setRepositories(values: List<RepositorySnapshot>, selectedRoot: java.nio.file.Path?) {
        adjusting = true
        repositories.model = DefaultComboBoxModel(values.toTypedArray())
        values.firstOrNull { it.root == selectedRoot }?.let { repositories.selectedItem = it }
        adjusting = false
    }
    fun showSnapshot(value: GraphSnapshot, scope: GraphScope) {
        val selectedHash = selected()?.hash
        if (snapshot?.repository?.root != value.repository.root) avatars.clear()
        snapshot = value
        matches = emptyList(); matchedRows = emptySet()
        adjusting = true
        val standardChoices = listOf(GraphScope("All branches", null), GraphScope("Current branch · HEAD", "HEAD")) +
            value.branches.map { GraphScope(it.name, it.fullName) }
        val choices = if (standardChoices.any { it.revision == scope.revision }) standardChoices else standardChoices + scope
        scopes.model = DefaultComboBoxModel(choices.toTypedArray())
        scopes.selectedItem = choices.firstOrNull { it.revision == scope.revision } ?: choices.first()
        adjusting = false
        status.text = "${value.repository.status.branch}   ↑ ${value.repository.status.ahead}   ↓ ${value.repository.status.behind}"
        model.fireTableDataChanged()
        val lanes = value.rows.maxOfOrNull { it.row.lanes } ?: 1
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(maxOf(120, 24 + lanes * 24))
        table.columnModel.getColumn(1).width = table.columnModel.getColumn(1).preferredWidth
        val index = value.history.commits.indexOfFirst { it.commit.hash == selectedHash }
        if (index >= 0) table.setRowSelectionInterval(index, index)
        summary.text = "${value.history.commits.size} commits · ${scope.title} · local refs" +
            (if (value.history.hasMore) " · older history available" else "") + (if (value.history.shallow) " · shallow clone" else "")
        summary.toolTipText = "Loaded history only. Line counts compare merges with their first parent; root commits with an empty tree."
        more.isVisible = value.history.hasMore
        more.isEnabled = !busy && value.history.commits.size < 5000
        more.text = if (value.history.commits.size >= 5000) "5,000 commit limit" else "Load older commits"
        activity.snapshot = value; activity.matches = emptySet(); activity.selectedRow = table.selectedRow
        rail.snapshot = value
        updateVisibleRange(); updateResults(); fitMessageColumn()
        idle(); listener.viewportChanged()
    }
    fun loading() {
        busy = true; refresh.isEnabled = false; more.isEnabled = false
        repositories.isEnabled = false; scopes.isEnabled = false
        fetch.isEnabled = false; push.isEnabled = false
        rail.setBusy(true)
        summary.text = "Reading local Git history and change statistics…"
    }
    fun idle() {
        busy = false; refresh.isEnabled = true; repositories.isEnabled = true; scopes.isEnabled = true
        fetch.isEnabled = snapshot != null; push.isEnabled = snapshot != null
        more.isEnabled = snapshot?.history?.commits?.size?.let { it < 5000 } == true
        rail.setBusy(false)
    }
    fun failed(message: String) { idle(); summary.text = "Graph unavailable · $message"; summary.toolTipText = message }
    fun clear() {
        snapshot = null; avatars.clear(); matches = emptyList(); matchedRows = emptySet()
        model.fireTableDataChanged(); activity.snapshot = null; activity.matches = emptySet(); rail.snapshot = null; more.isVisible = false
        status.text = ""; updateResults(); idle(); scopes.isEnabled = false
        summary.text = "No Git repository found in this project. Open a repository, then refresh."
    }
    fun applyMatches(query: String, rows: List<Int>) {
        if (query != search.text.trim()) return
        matches = rows; matchedRows = rows.toSet(); activity.matches = matchedRows; table.repaint(); updateResults()
        if (rows.isNotEmpty() && table.selectedRow !in matchedRows) select(rows.first())
    }
    fun visibleCommits(): List<GitCommit> {
        val entries = snapshot?.history?.commits ?: return emptyList()
        if (entries.isEmpty()) return emptyList()
        val rect = table.visibleRect
        val first = table.rowAtPoint(Point(0, rect.y)).coerceAtLeast(0)
        val last = table.rowAtPoint(Point(0, (rect.y + rect.height - 1).coerceAtLeast(0)))
            .let { if (it < 0) entries.lastIndex else it }.coerceAtMost(first + 100)
        return (first..last).mapNotNull { entries.getOrNull(it)?.commit }.distinctBy { it.email.lowercase() }
    }
    fun avatar(email: String, image: BufferedImage) { avatars[email.lowercase()] = image; table.repaint() }
    fun clearAvatars() { avatars.clear(); table.repaint() }
    fun stop() { timer.stop() }
    private fun selected() = snapshot?.history?.commits?.getOrNull(table.selectedRow)?.commit
    private fun select(row: Int) {
        if (row !in 0 until table.rowCount) return
        table.setRowSelectionInterval(row, row)
        table.scrollRectToVisible(Rectangle(scroll.viewport.viewPosition.x, row * table.rowHeight, 1, table.rowHeight))
    }
    internal fun navigateToReference(target: GraphNavigationTarget) {
        if (busy) return
        val row = snapshot?.rowByHash?.get(target.hash)
        if (row != null) select(row)
        else listener.scopeChanged(GraphScope(target.title, target.revision))
    }
    private fun updateVisibleRange() {
        val rect = table.visibleRect
        val first = table.rowAtPoint(Point(0, rect.y))
        val last = table.rowAtPoint(Point(0, (rect.y + rect.height - 1).coerceAtLeast(0)))
        activity.visibleRows = if (first < 0 || rect.height <= 0) IntRange.EMPTY else first..(if (last < 0) table.rowCount - 1 else last)
    }
    private fun navigate(direction: Int) {
        if (matches.isEmpty()) return
        val current = table.selectedRow
        val target = if (direction > 0) matches.firstOrNull { it > current } ?: matches.first()
        else matches.lastOrNull { it < current } ?: matches.last()
        select(target)
    }
    private fun updateResults() {
        val active = matches.indexOf(table.selectedRow)
        results.text = if (search.text.isBlank()) "" else if (matches.isEmpty()) "No results" else "${if (active < 0) "–" else active + 1} / ${matches.size}"
        previous.isEnabled = matches.isNotEmpty(); next.isEnabled = matches.isNotEmpty()
    }
    private fun fitMessageColumn() {
        val others = (0 until table.columnCount).filter { it != 2 }.sumOf { table.columnModel.getColumn(it).width }
        val column = table.columnModel.getColumn(2)
        val width = maxOf(JBUI.scale(300), scroll.viewport.width - others)
        if (column.preferredWidth != width) {
            column.preferredWidth = width; column.width = width; table.revalidate()
        }
    }
    private fun popup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val index = table.rowAtPoint(event.point)
        if (index < 0) return
        table.setRowSelectionInterval(index, index)
        val commit = selected() ?: return
        JPopupMenu().apply {
            fun item(title: String, action: () -> Unit) { add(JMenuItem(title).apply { addActionListener { action() } }) }
            item("Inspect commit") { listener.inspect(commit) }
            item("Copy SHA") { listener.copy(commit) }
            addSeparator()
            item("Open commit on GitHub / GitLab ↗") { listener.openCommit(commit) }
            item("Open related PR / MR ↗") { listener.openReviews(commit) }
        }.show(table, event.x, event.y)
    }
    private fun horizontal(vararg components: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false
        components.forEachIndexed { i, c -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(c) }
    }

    private inner class GraphTableModel : AbstractTableModel() {
        private val columns = arrayOf("BRANCH / TAG", "GRAPH", "COMMIT MESSAGE", "CHANGES", "AUTHOR", "COMMIT DATE / TIME", "SHA")
        override fun getRowCount() = snapshot?.history?.commits?.size ?: 0
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val e = snapshot?.history?.commits?.getOrNull(row) ?: return ""
            return when (column) {
                0 -> snapshot?.references?.get(e.commit.hash).orEmpty().joinToString { it.name }
                1 -> "${e.commit.parents.size} parent(s)"
                2 -> e.commit.subject
                3 -> "${e.changes.files} files +${e.changes.added} −${e.changes.removed}"
                4 -> e.commit.author
                5 -> date.format(e.committedAt.atZone(ZoneId.systemDefault()))
                else -> e.commit.hash.take(8)
            }
        }
    }

    private class GraphHeaderRenderer : JComponent(), TableCellRenderer {
        private var text = ""
        override fun getPreferredSize() = Dimension(0, getFontMetrics(font).height + JBUI.scale(12))
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            text = value.toString(); font = table.font.deriveFont(Font.BOLD, table.font.size2D - 1)
            background = JBColor.namedColor("TableHeader.background", table.background)
            return this
        }
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.color = background; g.fillRect(0, 0, width, height)
                g.font = font; g.color = Ui.muted
                g.drawString(elide(g, text, width - JBUI.scale(16)), JBUI.scale(8), (height - g.fontMetrics.height) / 2 + g.fontMetrics.ascent)
                g.color = JBColor.border(); g.drawLine(0, height - 1, width, height - 1)
                g.drawLine(width - 1, JBUI.scale(5), width - 1, height - JBUI.scale(5))
            } finally { g.dispose() }
        }
    }

    private inner class GraphCellRenderer : JComponent(), TableCellRenderer {
        private var row = 0
        private var column = 0
        private var selected = false
        override fun getTableCellRendererComponent(t: JTable, value: Any?, isSelected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            this.row = row; this.column = column; selected = isSelected
            font = t.font; foreground = if (isSelected) t.selectionForeground else t.foreground
            background = if (isSelected) t.selectionBackground else t.background
            return this
        }
        override fun paintComponent(graphics: Graphics) {
            val snapshot = snapshot ?: return
            val entry = snapshot.history.commits.getOrNull(row) ?: return
            val layout = snapshot.rows[row]
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = background; g.fillRect(0, 0, width, height)
                if (!selected && row in matchedRows) {
                    g.color = alpha(palette(layout.color), 32); g.fillRect(0, 0, width, height)
                }
                g.font = font
                val baseline = (height - g.fontMetrics.height) / 2 + g.fontMetrics.ascent
                when (column) {
                    0 -> {
                        var x = JBUI.scale(8)
                        val refs = snapshot.references[entry.commit.hash].orEmpty()
                        val labels = (if (snapshot.history.head == entry.commit.hash) listOf(Triple("HEAD", 0, null)) else emptyList()) +
                            refs.map { Triple(it.name, when (it.kind) { GraphRefKind.TAG -> 4; GraphRefKind.REMOTE -> 1; else -> layout.color },
                                if (it.kind == GraphRefKind.TAG) GraphIcons.tag else AllIcons.Vcs.Branch) }
                        var shown = 0
                        for ((index, pair) in labels.withIndex()) {
                            val (label, color, icon) = pair
                            val iconWidth = icon?.let { it.iconWidth + JBUI.scale(4) } ?: 0
                            val reserve = if (index < labels.lastIndex) JBUI.scale(29) else JBUI.scale(8)
                            val available = width - x - reserve - JBUI.scale(14) - iconWidth
                            if (available < JBUI.scale(22)) break
                            val text = elide(g, label, available)
                            val w = g.fontMetrics.stringWidth(text) + JBUI.scale(14) + iconWidth
                            g.color = alpha(palette(color), if (selected) 105 else 55)
                            g.fillRoundRect(x, JBUI.scale(5), w, height - JBUI.scale(10), JBUI.scale(8), JBUI.scale(8))
                            g.color = if (selected) foreground else palette(color)
                            icon?.paintIcon(this, g, x + JBUI.scale(7), (height - icon.iconHeight) / 2)
                            g.drawString(text, x + JBUI.scale(7) + iconWidth, baseline)
                            x += w + JBUI.scale(4); shown++
                        }
                        if (shown < labels.size) {
                            g.color = if (selected) foreground else Ui.muted
                            g.drawString("+${labels.size - shown}", x, baseline)
                        }
                    }
                    1 -> {
                        val middle = height / 2.0
                        fun lane(n: Int) = JBUI.scale(20 + n * 24).toDouble()
                        g.color = alpha(palette(layout.color), if (selected) 45 else 20)
                        g.fillRect(lane(layout.row.lane).toInt() - JBUI.scale(16), 0, width, height)
                        g.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        layout.edges.forEach { colored ->
                            val e = colored.edge
                            g.color = palette(colored.color)
                            val fromX = lane(e.from); val toX = lane(e.to)
                            val path = Path2D.Double()
                            if (e.startsAtCommit) path.moveTo(fromX, middle) else { path.moveTo(fromX, 0.0); path.lineTo(fromX, middle) }
                            path.curveTo(fromX, height * .85, toX, height * .65, toX, height.toDouble())
                            g.draw(path)
                        }
                        val x = lane(layout.row.lane)
                        if (layout.row.hasIncoming) { g.color = palette(layout.color); g.drawLine(x.toInt(), 0, x.toInt(), middle.toInt()) }
                        val diameter = JBUI.scale(22)
                        val circle = Ellipse2D.Double(x - diameter / 2.0, middle - diameter / 2.0, diameter.toDouble(), diameter.toDouble())
                        g.color = background; g.fill(circle)
                        val image = avatars[entry.commit.email.lowercase()]
                        if (image != null) {
                            val clip = g.clip; g.clip(circle)
                            g.drawImage(image, (x - diameter / 2).toInt(), (middle - diameter / 2).toInt(), diameter, diameter, null)
                            g.clip = clip
                        } else {
                            g.color = alpha(palette(layout.color), 65); g.fill(circle)
                            g.font = font.deriveFont(Font.BOLD, font.size2D * .72f)
                            val initials = entry.commit.author.split(' ').filter { it.isNotEmpty() }.take(2).map { it.first() }.joinToString("")
                            g.color = foreground; g.drawString(initials, (x - g.fontMetrics.stringWidth(initials) / 2).toFloat(), (middle + g.fontMetrics.ascent / 2.6).toFloat())
                        }
                        g.color = palette(layout.color); g.draw(circle)
                    }
                    2 -> { g.color = foreground; g.drawString(elide(g, entry.commit.subject, width - JBUI.scale(18)), JBUI.scale(9), baseline) }
                    3 -> {
                        g.color = if (selected) foreground else Ui.muted
                        val icon = GraphIcons.file
                        icon.paintIcon(this, g, JBUI.scale(8), (height - icon.iconHeight) / 2)
                        val files = "${entry.changes.files} ${if (entry.changes.files == 1) "file" else "files"}"
                        val textX = JBUI.scale(12) + icon.iconWidth
                        g.drawString(files, textX, baseline)
                        val barX = maxOf(JBUI.scale(90), textX + g.fontMetrics.stringWidth(files) + JBUI.scale(10))
                        val barWidth = JBUI.scale(55)
                        val total = entry.changes.added + entry.changes.removed
                        val fill = if (total == 0L) 0 else maxOf(3, (ln(total + 1.0) / ln(snapshot.maxChanges + 1.0) * barWidth).roundToInt())
                        val addWidth = if (total == 0L) 0 else (fill * entry.changes.added.toDouble() / total).roundToInt()
                        g.color = green; g.fillRect(barX, height / 2 - JBUI.scale(4), addWidth, JBUI.scale(8))
                        g.color = red; g.fillRect(barX + addWidth, height / 2 - JBUI.scale(4), fill - addWidth, JBUI.scale(8))
                        var x = barX + barWidth + JBUI.scale(8)
                        g.color = green; val plus = "+${compact(entry.changes.added)}"; g.drawString(plus, x, baseline)
                        x += g.fontMetrics.stringWidth(plus) + JBUI.scale(6)
                        g.color = red; g.drawString("−${compact(entry.changes.removed)}", x, baseline)
                    }
                    else -> {
                        g.color = if (selected || column == 4) foreground else Ui.muted
                        val text = when (column) { 4 -> entry.commit.author; 5 -> date.format(entry.committedAt.atZone(ZoneId.systemDefault())); else -> entry.commit.hash.take(8) }
                        g.drawString(elide(g, text, width - JBUI.scale(16)), JBUI.scale(8), baseline)
                    }
                }
            } finally { g.dispose() }
        }
    }

    companion object {
        private val date = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")
        private val green = JBColor(Color(0x267B48), Color(0x7BC795))
        private val red = JBColor(Color(0xB5374B), Color(0xE07B89))
        private val colors = arrayOf(
            JBColor(Color(0x00877F), Color(0x32BDB0)), JBColor(Color(0x216FD2), Color(0x519BFF)),
            JBColor(Color(0x8543C0), Color(0xB87BF5)), JBColor(Color(0xBC329E), Color(0xE367CE)),
            JBColor(Color(0x936000), Color(0xDDB05F)), JBColor(Color(0xC14445), Color(0xEC7779)),
            JBColor(Color(0x367B3B), Color(0x8AC46C)),
        )
        private fun palette(index: Int): Color = colors[index % colors.size]
        private fun alpha(color: Color, opacity: Int) = Color(color.red, color.green, color.blue, opacity)
        private fun compact(n: Long) = if (n < 1000) "$n" else if (n < 1_000_000) "${n / 1000}k" else "${n / 1_000_000}m"
        private fun elide(g: Graphics2D, text: String, width: Int): String {
            if (g.fontMetrics.stringWidth(text) <= width) return text
            if (width <= g.fontMetrics.stringWidth("…")) return ""
            var low = 0; var high = text.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (g.fontMetrics.stringWidth(text.substring(0, mid) + "…") <= width) low = mid else high = mid - 1
            }
            return text.take(low) + "…"
        }
    }
}
