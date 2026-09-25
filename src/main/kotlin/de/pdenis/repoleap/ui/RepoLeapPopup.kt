package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.settings.OpenMode
import de.pdenis.repoleap.settings.RepoLeapConfigurable
import de.pdenis.repoleap.settings.RepoLeapSettings
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The search popup: a text field on top, the matching repositories below.
 *
 * Repository list:
 * - typing filters (multi-word, fuzzy), Up/Down/PageUp/PageDown move the selection
 * - Enter / click opens with the configured [OpenMode], Shift+Enter / Shift+click with its alternative
 * - Tab / right click shows the actions of the selected repository
 *
 * Actions (same popup): Enter / click runs the action, Esc / Tab / Left goes back, typing searches again.
 */
class RepoLeapPopup(
    private val project: Project?,
    private val initialQuery: String = "",
    /** Repository to select once it is listed, e.g. after a rename. */
    initialSelection: Path? = null,
) {

    private enum class Mode { REPOS, ACTIONS }

    private val settings = RepoLeapSettings.getInstance()
    private val index = RepoIndexService.getInstance()

    private val searchField = SearchTextField(false)
    private val model = CollectionListModel<RepoListItem>()
    private val list = JBList(model)
    private val actionModel = CollectionListModel<RepoAction>()
    private val actionList = JBList(actionModel)
    private val actionsHeader = SimpleColoredComponent()
    private val cards = JPanel(CardLayout())
    private val panel = JPanel(BorderLayout())
    private var popup: JBPopup? = null

    private var mode = Mode.REPOS
    /** Normalized base paths of all open projects / of the project the popup was opened from. */
    private var openProjectPaths: Set<String> = emptySet()
    private var currentProjectPath: String? = null
    private var actionsTarget: RepoListItem? = null
    private var pendingSelection: Path? = initialSelection
    private val host = object : RepoActionHost {
        override fun open(item: RepoListItem, alternative: Boolean) = this@RepoLeapPopup.open(item, alternative)
        override fun closeThen(block: () -> Unit) = this@RepoLeapPopup.closeThen(block)
        override fun reopen(selection: Path?) = this@RepoLeapPopup.reopen(selection)
        override fun backToRepositories() = this@RepoLeapPopup.backToRepositories()
    }
    private val actionsProvider = RepoActionsProvider(project, host)

    fun show() {
        openProjectPaths = ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .mapNotNull { it.basePath?.let(::normalizePath) }
            .toSet()
        currentProjectPath = project?.takeUnless { it.isDisposed }?.basePath?.let(::normalizePath)

        setUpRepositoryList(RepoListCellRenderer())
        setUpActionList()
        setUpSearchField()

        cards.add(ScrollPaneFactory.createScrollPane(list, true), Mode.REPOS.name)
        cards.add(JPanel(BorderLayout()).apply {
            add(actionsHeader.apply { border = JBUI.Borders.empty(6, 10, 4, 10) }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(actionList, true), BorderLayout.CENTER)
        }, Mode.ACTIONS.name)

        searchField.border = JBUI.Borders.empty(4, 6)
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(cards, BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(700, 420)

        val alternativeText = when (settings.state.openMode.alternative) {
            OpenMode.NEW_WINDOW -> RepoLeapBundle.message("popup.alt.newWindow")
            else -> RepoLeapBundle.message("popup.alt.thisWindow")
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField.textEditor)
            .setTitle(RepoLeapBundle.message("popup.title"))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false) // Esc is handled in onKeyPressed (it only leaves the action view there)
            .setDimensionServiceKey(project, DIMENSION_SERVICE_KEY, false)
            .setMinSize(JBUI.size(420, 240))
            .setSettingButtons(createToolbar())
            .setAdText(RepoLeapBundle.message("popup.ad", alternativeText), SwingConstants.LEFT)
            .createPopup()
        this.popup = popup

        index.addListener(popup) { refilter(keepSelection = true) }
        searchField.text = initialQuery
        refilter(keepSelection = false)
        index.rescan() // show the cached list immediately, refresh it in the background

        if (project != null && !project.isDisposed) popup.showCenteredInCurrentWindow(project)
        else popup.showInFocusCenter()
    }

    // --- set-up -----------------------------------------------------------------------------------------------

    private fun setUpRepositoryList(renderer: RepoListCellRenderer) {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = VISIBLE_ROWS
        list.isFocusable = false // keep the keyboard focus in the search field
        list.cellRenderer = renderer
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showActionsAt(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showActionsAt(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.isControlDown) return
                val row = rowAt(list, e) ?: return
                list.selectedIndex = row
                openSelected(alternative = e.isShiftDown)
            }
        })
    }

    private fun showActionsAt(e: MouseEvent) {
        val row = rowAt(list, e) ?: return
        list.selectedIndex = row
        showActions()
    }

    private fun setUpActionList() {
        actionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        actionList.isFocusable = false
        actionList.cellRenderer = RepoActionCellRenderer()
        actionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val row = rowAt(actionList, e) ?: return
                actionList.selectedIndex = row
                runSelectedAction()
            }
        })
    }

    private fun setUpSearchField() {
        val editor = searchField.textEditor
        editor.emptyText.text = RepoLeapBundle.message("popup.search.placeholder")
        editor.focusTraversalKeysEnabled = false // we want to receive Tab
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (mode == Mode.ACTIONS) showRepositories()
                refilter(keepSelection = false)
            }
        })
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (onKeyPressed(e)) e.consume()
            }
        })
    }

    /** @return `true` if the key was handled */
    private fun onKeyPressed(e: KeyEvent): Boolean {
        val activeList: JList<*> = if (mode == Mode.ACTIONS) actionList else list
        when (e.keyCode) {
            KeyEvent.VK_ENTER -> if (mode == Mode.ACTIONS) runSelectedAction() else openSelected(e.isShiftDown)
            KeyEvent.VK_TAB -> if (mode == Mode.ACTIONS) showRepositories() else showActions()
            KeyEvent.VK_ESCAPE -> if (mode == Mode.ACTIONS) showRepositories() else popup?.cancel()
            KeyEvent.VK_LEFT -> if (mode == Mode.ACTIONS) showRepositories() else return false
            KeyEvent.VK_UP -> ScrollingUtil.moveUp(activeList, 0)
            KeyEvent.VK_DOWN -> ScrollingUtil.moveDown(activeList, 0)
            KeyEvent.VK_PAGE_UP -> ScrollingUtil.movePageUp(activeList)
            KeyEvent.VK_PAGE_DOWN -> ScrollingUtil.movePageDown(activeList)
            else -> return false
        }
        return true
    }

    // --- repository list --------------------------------------------------------------------------------------

    private fun refilter(keepSelection: Boolean) {
        val repositories = index.repositories.orEmpty().filterNot { settings.isHidden(it.pathString) }
        val tokens = RepoMatcher.tokenize(searchField.text)
        val recentRank = settings.recentRepos.withIndex().associate { (rank, path) -> path to rank }

        val items = repositories.mapNotNull { repo ->
            val match = RepoMatcher.match(tokens, repo.name, repo.displayPath) ?: return@mapNotNull null
            val recentBonus = recentRank[repo.pathString]?.let { (RepoLeapSettings.MAX_RECENT - it) * RECENT_BONUS } ?: 0
            RepoListItem(
                repo, match, match.score + recentBonus,
                pinned = settings.isPinned(repo.pathString),
                windowState = windowStateOf(repo.pathString),
            )
        }.sortedWith(RepoListItem.ORDER)

        val toSelect = pendingSelection ?: if (keepSelection) list.selectedValue?.repo?.path else null
        model.replaceAll(items)
        if (items.isEmpty()) {
            list.clearSelection()
        } else {
            val found = toSelect?.let { selected -> items.indexOfFirst { it.repo.path == selected } }?.takeIf { it >= 0 }
            if (found != null && toSelect == pendingSelection) pendingSelection = null
            val row = found ?: 0
            list.selectedIndex = row
            list.ensureIndexIsVisible(row)
        }
        updateEmptyText()
    }

    /** Repository paths from the scanner are absolute and normalized already. */
    private fun windowStateOf(path: String): WindowState = when (path) {
        currentProjectPath -> WindowState.CURRENT
        in openProjectPaths -> WindowState.OTHER
        else -> WindowState.CLOSED
    }

    private fun updateEmptyText() {
        val emptyText = list.emptyText
        emptyText.clear()
        val repositories = index.repositories
        when {
            settings.state.roots.isEmpty() -> {
                emptyText.appendText(RepoLeapBundle.message("popup.empty.noRoots"))
                emptyText.appendSecondaryText(
                    RepoLeapBundle.message("popup.empty.configure"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { openSettings() }
            }
            repositories.isNullOrEmpty() && index.isScanning ->
                emptyText.appendText(RepoLeapBundle.message("popup.empty.scanning"))
            repositories.isNullOrEmpty() -> {
                emptyText.appendText(RepoLeapBundle.message("popup.empty.noRepos"))
                emptyText.appendSecondaryText(
                    RepoLeapBundle.message("popup.empty.configure"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { openSettings() }
            }
            else -> emptyText.appendText(RepoLeapBundle.message("popup.empty.noMatch"))
        }
    }

    private fun openSelected(alternative: Boolean) {
        val item = list.selectedValue ?: return
        open(item, alternative)
    }

    // --- action view ------------------------------------------------------------------------------------------

    private fun showActions() {
        val item = list.selectedValue ?: return
        actionsTarget = item
        actionModel.replaceAll(actionsProvider.actionsFor(item) { updated ->
            // e.g. the remote's web page after the git server answered - only if these actions are still shown
            if (mode != Mode.ACTIONS || actionsTarget !== item) return@actionsFor
            val index = actionModel.items.indexOfFirst { it.id == updated.id }
            if (index >= 0) actionModel.setElementAt(updated, index)
        })
        actionList.selectedIndex = actionModel.items.indexOfFirst { it.enabled }.coerceAtLeast(0)

        actionsHeader.clear()
        actionsHeader.icon = AllIcons.Nodes.Folder
        actionsHeader.append(item.repo.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        actionsHeader.append("  " + item.repo.displayPath, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        actionsHeader.append("   " + RepoLeapBundle.message("popup.actions.hint"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        mode = Mode.ACTIONS
        (cards.layout as CardLayout).show(cards, Mode.ACTIONS.name)
    }

    private fun showRepositories() {
        mode = Mode.REPOS
        (cards.layout as CardLayout).show(cards, Mode.REPOS.name)
    }

    private fun runSelectedAction() {
        val action = actionList.selectedValue ?: return
        if (action.enabled) action.perform()
    }

    // --- used by the actions ---------------------------------------------------------------------------------

    private fun open(item: RepoListItem, alternative: Boolean) {
        // Shift+Enter on a repository in another window: merge that window into this one (macOS window tabs)
        if (alternative && actionsProvider.mergeIntoThisWindow(item)) return
        val configured = settings.state.openMode
        val openMode = if (alternative) configured.alternative else configured
        closeThen { RepoOpener.open(item.repo, openMode, project?.takeUnless { it.isDisposed }) }
    }

    private fun closeThen(block: () -> Unit) {
        popup?.cancel()
        // Run after the popup is gone and has given the focus back to its window - like the platform's own popups do
        ApplicationManager.getApplication().invokeLater(block, ModalityState.nonModal())
    }

    private fun reopen(selection: Path?) {
        // The project the popup was opened from may have been closed in the meantime (Close Project)
        val target = project?.takeUnless { it.isDisposed } ?: ProjectManager.getInstance().openProjects.lastOrNull()
        RepoLeapPopup(target, searchField.text, selection).show()
    }

    private fun backToRepositories() {
        val selected = actionsTarget?.repo?.path
        showRepositories()
        pendingSelection = selected
        refilter(keepSelection = true)
    }

    // --- settings / toolbar -----------------------------------------------------------------------------------

    private fun openSettings() {
        closeThen {
            val target = project?.takeUnless { it.isDisposed }
            ShowSettingsUtil.getInstance().showSettingsDialog(target, RepoLeapConfigurable::class.java)
        }
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup(
            PopupAction(RepoLeapBundle.message("popup.action.refresh"), AllIcons.Actions.Refresh) {
                index.rescan()
                updateEmptyText()
            },
            PopupAction(RepoLeapBundle.message("popup.action.settings"), AllIcons.General.GearPlain) {
                openSettings()
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = panel
        return toolbar.component.apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
    }

    private fun rowAt(list: JList<*>, e: MouseEvent): Int? {
        val row = list.locationToIndex(e.point)
        if (row < 0 || !list.getCellBounds(row, row).contains(e.point)) return null
        return row
    }

    // --- test hooks -------------------------------------------------------------------------------------------

    @TestOnly
    internal fun setSearchTextForTest(text: String) {
        searchField.text = text
    }

    @TestOnly
    internal fun visibleRepoNamesForTest(): List<String> = model.items.map { it.repo.name }

    @TestOnly
    internal fun selectRepoForTest(name: String) {
        list.selectedIndex = model.items.indexOfFirst { it.repo.name == name }
    }

    @TestOnly
    internal fun pressKeyForTest(keyCode: Int, modifiers: Int = 0) {
        val event = KeyEvent(searchField.textEditor, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
        onKeyPressed(event)
    }

    @TestOnly
    internal fun isShowingActionsForTest(): Boolean = mode == Mode.ACTIONS

    @TestOnly
    internal fun actionsForTest(): List<RepoAction> = actionModel.items

    @TestOnly
    internal fun runActionForTest(id: String) {
        actionList.selectedIndex = actionModel.items.indexOfFirst { it.id == id }
        runSelectedAction()
    }

    @TestOnly
    internal fun emptyTextForTest(): String = list.emptyText.text

    @TestOnly
    internal fun closeForTest() {
        popup?.cancel()
    }

    private class PopupAction(
        text: String,
        icon: Icon,
        private val perform: () -> Unit,
    ) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private companion object {
        const val DIMENSION_SERVICE_KEY = "de.pdenis.repoleap.popup"
        const val TOOLBAR_PLACE = "RepoLeapPopup"
        const val VISIBLE_ROWS = 15
        const val RECENT_BONUS = 5
    }
}
