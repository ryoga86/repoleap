package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.ui.JBUI
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.search.RepoMatcher
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The branch list of "Switch Branch…": search field on top, branches below - the same fuzzy search and highlighting
 * as in the repository list. Typing filters, Up/Down/PageUp/PageDown move, Enter / click chooses, Esc leaves.
 */
internal class BranchPopup(
    private val title: String,
    private val items: List<BranchSwitcher.Item>,
    /**
     * Gets the chosen branch, or null if the user left the list with Esc (back to where it was opened from).
     * Not called when the popup is closed in some other way (click outside, another window gets the focus).
     */
    private val onDone: (BranchSwitcher.Item?) -> Unit,
) {

    private class Row(val item: BranchSwitcher.Item, val ranges: List<IntRange>)

    private val searchField = SearchTextField(false)
    private val model = CollectionListModel<Row>()
    private val list = JBList(model)
    private var popup: JBPopup? = null
    private var chosen: BranchSwitcher.Item? = null
    private var escaped = false

    fun show(project: Project?) {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = VISIBLE_ROWS
        list.isFocusable = false // the keyboard focus stays in the search field
        list.cellRenderer = Renderer()
        list.emptyText.text = RepoLeapBundle.message("branch.noMatch")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val row = list.locationToIndex(e.point)
                if (row < 0 || !list.getCellBounds(row, row).contains(e.point)) return
                list.selectedIndex = row
                chooseSelected()
            }
        })

        val editor = searchField.textEditor
        editor.emptyText.text = RepoLeapBundle.message("branch.search.placeholder")
        searchField.border = JBUI.Borders.empty(4, 6)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (onKeyPressed(e)) e.consume()
            }
        })
        refilter()

        val panel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
            preferredSize = JBUI.size(520, 380)
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, editor)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false) // Esc is handled in onKeyPressed
            .setDimensionServiceKey(project, DIMENSION_SERVICE_KEY, false)
            .setMinSize(JBUI.size(320, 200))
            .addListener(object : JBPopupListener {
                // Choice and Esc are noted before the popup is closed, so they are known here
                override fun onClosed(event: LightweightWindowEvent) {
                    if (chosen != null || escaped) onDone(chosen)
                }
            })
            .createPopup()
        this.popup = popup
        if (project != null && !project.isDisposed) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }

    /** @return `true` if the key was handled */
    private fun onKeyPressed(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.VK_ENTER -> chooseSelected()
            KeyEvent.VK_ESCAPE -> {
                escaped = true
                popup?.cancel()
            }
            KeyEvent.VK_UP -> ScrollingUtil.moveUp(list, 0)
            KeyEvent.VK_DOWN -> ScrollingUtil.moveDown(list, 0)
            KeyEvent.VK_PAGE_UP -> ScrollingUtil.movePageUp(list)
            KeyEvent.VK_PAGE_DOWN -> ScrollingUtil.movePageDown(list)
            else -> return false
        }
        return true
    }

    private fun chooseSelected() {
        chosen = list.selectedValue?.item ?: return // nothing matches: stay
        popup?.cancel()
    }

    /** Without a query the order stays as given (current branch first), with one the best match comes first. */
    private fun refilter() {
        val tokens = RepoMatcher.tokenize(searchField.text)
        val matches = items.mapNotNull { item ->
            RepoMatcher.match(tokens, item.name, "")?.let { Triple(item, it.nameRanges, it.score) }
        }
        val ordered = if (tokens.isEmpty()) matches else matches.sortedByDescending { it.third } // stable: ties keep their order
        model.replaceAll(ordered.map { (item, ranges) -> Row(item, ranges) })
        if (ordered.isEmpty()) {
            list.clearSelection()
        } else {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
    }

    private class Renderer : ColoredListCellRenderer<Row>() {
        override fun customizeCellRenderer(list: JList<out Row>, value: Row?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value == null) return
            val item = value.item
            icon = if (item.current) AllIcons.Actions.Checked else AllIcons.Vcs.Branch
            SpeedSearchUtil.appendColoredFragments(
                this, item.name, value.ranges.map { TextRange(it.first, it.last + 1) },
                if (item.current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                RepoListCellRenderer.NAME_HIGHLIGHT,
            )
            when {
                item.current -> append("  " + RepoLeapBundle.message("branch.current"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                item.remoteOnly -> append("  " + RepoLeapBundle.message("branch.remote"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    // --- test hooks -------------------------------------------------------------------------------------------

    @TestOnly
    internal fun setSearchTextForTest(text: String) {
        searchField.text = text
    }

    /** The listed branches with the highlighted ranges, in list order. */
    @TestOnly
    internal fun rowsForTest(): List<Pair<String, List<IntRange>>> = model.items.map { it.item.name to it.ranges }

    @TestOnly
    internal fun pressKeyForTest(keyCode: Int) {
        onKeyPressed(KeyEvent(searchField.textEditor, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED))
    }

    @TestOnly
    internal fun closeForTest() {
        popup?.cancel()
    }

    private companion object {
        const val VISIBLE_ROWS = 15
        const val DIMENSION_SERVICE_KEY = "RepoLeap.BranchPopup"
    }
}
