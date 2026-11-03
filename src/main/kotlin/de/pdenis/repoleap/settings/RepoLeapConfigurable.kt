package de.pdenis.repoleap.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ListUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.RemoteBrowserUrls
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.search.Tags
import de.pdenis.repoleap.ui.MatchHighlight
import de.pdenis.repoleap.ui.RepoListCellRenderer
import de.pdenis.repoleap.ui.RepoListItem
import de.pdenis.repoleap.ui.WindowState
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.GridLayout
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.text.AbstractDocument

/** Settings | Tools | RepoLeap */
class RepoLeapConfigurable : BoundConfigurable(RepoLeapBundle.message("settings.displayName")) {

    private val settings = RepoLeapSettings.getInstance()
    private val rootsModel = CollectionListModel<String>()
    private val rootsList = JBList(rootsModel)
    private val hiddenModel = CollectionListModel<String>()
    private val hiddenList = JBList(hiddenModel)
    private val remoteRules = RemoteRulesTable()

    /** The tags as edited on this page (renamed / removed), applied with the page. */
    private var tagsDraft: MutableList<RepoTags> = mutableListOf()
    private var tagCounts: Map<String, Int> = emptyMap()
    private val tagsModel = CollectionListModel<String>()
    private val tagsList = JBList(tagsModel)

    private var highlightStyleBox: JComboBox<HighlightStyle>? = null
    private var customColorBox: JCheckBox? = null

    /** The "Show next to the name" checkboxes, to show the page's current choice in the preview. */
    private val showBoxes = mutableMapOf<RowPart, JCheckBox>()
    private val lightColor = ColorPanel().apply { addActionListener { updatePreview() } }
    private val darkColor = ColorPanel().apply { addActionListener { updatePreview() } }
    private val previewList = JBList<RepoListItem>()
    private val previewRows = listOf(RepoListCellRenderer(), RepoListCellRenderer())
    private var separatorField: JBTextField? = null

    /** Order of the parts after the name, as edited on this page. */
    private val orderModel = CollectionListModel<RowPart>().apply {
        addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = updatePreview()
            override fun intervalRemoved(e: ListDataEvent) = updatePreview()
            override fun contentsChanged(e: ListDataEvent) = updatePreview()
        })
    }
    private val orderList = JBList(orderModel)
    private val preview = JPanel(GridLayout(previewRows.size, 1)).apply {
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        previewRows.forEach { add(it) }
    }

    override fun createPanel(): DialogPanel {
        val state = settings.state
        resetColors()
        resetOrder()
        updatePreview()

        rootsList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        rootsList.emptyText.text = RepoLeapBundle.message("settings.folders.empty")
        rootsModel.replaceAll(state.roots)

        val rootsPanel = ToolbarDecorator.createDecorator(rootsList)
            .setAddAction { addFolders() }
            .setEditAction { editSelectedFolder() }
            .setRemoveAction { ListUtil.removeSelectedItems(rootsList) }
            .setPreferredSize(JBUI.size(500, 180))
            .createPanel()

        hiddenList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        hiddenList.emptyText.text = RepoLeapBundle.message("settings.hiddenRepos.empty")
        hiddenModel.replaceAll(state.hiddenRepos)
        val hiddenPanel = ToolbarDecorator.createDecorator(hiddenList)
            .disableAddAction()
            .disableUpDownActions()
            .setRemoveAction { ListUtil.removeSelectedItems(hiddenList) }
            .setRemoveActionName(RepoLeapBundle.message("settings.hiddenRepos.show"))
            .setPreferredSize(JBUI.size(500, 90))
            .createPanel()

        orderList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        orderList.cellRenderer = object : ColoredListCellRenderer<RowPart>() {
            override fun customizeCellRenderer(list: JList<out RowPart>, value: RowPart?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                append("${index + 2}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES) // the name is always the first part
                append(value.displayName)
            }
        }
        val orderPanel = ToolbarDecorator.createDecorator(orderList)
            .disableAddAction()
            .disableRemoveAction()
            .setPreferredSize(JBUI.size(260, 100))
            .createPanel()

        tagsList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        tagsList.emptyText.text = RepoLeapBundle.message("settings.tags.empty")
        tagsList.cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                append("#$value", RepoListCellRenderer.TAG)
                append("  " + RepoLeapBundle.message("settings.tags.count", tagCounts[value] ?: 0), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        resetTags()
        val tagsPanel = ToolbarDecorator.createDecorator(tagsList)
            .disableAddAction()
            .disableUpDownActions()
            .setEditAction { renameSelectedTag() }
            .setEditActionName(RepoLeapBundle.message("settings.tags.rename"))
            .setRemoveAction { removeSelectedTags() }
            .setPreferredSize(JBUI.size(500, 90))
            .createPanel()

        return panel {
            group(RepoLeapBundle.message("settings.group.folders")) {
                row {
                    cell(rootsPanel)
                        .align(Align.FILL)
                        .comment(RepoLeapBundle.message("settings.folders.comment"))
                }.resizableRow()

                row(RepoLeapBundle.message("settings.depth.label")) {
                    spinner(RepoLeapSettings.MIN_DEPTH..RepoLeapSettings.MAX_DEPTH)
                        .bindIntValue(state::maxDepth)
                        .comment(RepoLeapBundle.message("settings.depth.comment"))
                }

                row(RepoLeapBundle.message("settings.excluded.label")) {
                    expandableTextField()
                        .bindText(state::excludedFolderNames)
                        .align(AlignX.FILL)
                        .comment(RepoLeapBundle.message("settings.excluded.comment"))
                }

                row {
                    checkBox(RepoLeapBundle.message("settings.hidden.label"))
                        .bindSelected(state::skipHiddenFolders)
                }
            }

            group(RepoLeapBundle.message("settings.group.popup")) {
                buttonsGroup(RepoLeapBundle.message("settings.sortMode.label")) {
                    SortMode.entries.forEach { mode ->
                        row { radioButton(mode.displayName, mode) }
                    }
                }.bind(state::sortMode)
                row {
                    comment(RepoLeapBundle.message("settings.sortMode.comment"))
                }
                row(RepoLeapBundle.message("settings.show.label")) {
                    showBox(RowPart.BRANCH, state::showBranch)
                    showBox(RowPart.PATH, state::showPath)
                    showBox(RowPart.WINDOW_STATE, state::showWindowState)
                    showBox(RowPart.TAGS, state::showTags)
                }
                row {
                    comment(RepoLeapBundle.message("settings.show.comment"))
                }
                row(RepoLeapBundle.message("settings.order.label")) {
                    cell(orderPanel)
                        .comment(RepoLeapBundle.message("settings.order.comment"))
                }
                row(RepoLeapBundle.message("settings.separator.label")) {
                    textField()
                        .columns(4)
                        .bindText({ state.rowSeparator }, { state.rowSeparator = RepoLeapSettings.normalizeSeparator(it) })
                        .comment(RepoLeapBundle.message("settings.separator.comment", RepoLeapSettings.MAX_SEPARATOR_LENGTH))
                        .applyToComponent {
                            separatorField = this
                            (document as? AbstractDocument)?.documentFilter = MaxLengthFilter(RepoLeapSettings.MAX_SEPARATOR_LENGTH)
                            document.addDocumentListener(object : DocumentAdapter() {
                                override fun textChanged(e: DocumentEvent) = updatePreview()
                            })
                        }
                }
                row(RepoLeapBundle.message("settings.highlight.label")) {
                    comboBox(HighlightStyle.entries, object : ColoredListCellRenderer<HighlightStyle?>() {
                        override fun customizeCellRenderer(
                            list: JList<out HighlightStyle?>, value: HighlightStyle?, index: Int, selected: Boolean, hasFocus: Boolean,
                        ) {
                            append(value?.displayName.orEmpty())
                        }
                    })
                        .bindItem({ state.highlightStyle }, { state.highlightStyle = it ?: HighlightStyle.TEXT_COLOR })
                        .applyToComponent {
                            highlightStyleBox = this
                            addActionListener { updatePreview() }
                        }
                }
                row {
                    val custom = checkBox(RepoLeapBundle.message("settings.highlight.customColor"))
                        .bindSelected(state::customHighlightColor)
                        .applyToComponent {
                            customColorBox = this
                            addActionListener { updatePreview() }
                        }
                        .component.selected
                    label(RepoLeapBundle.message("settings.highlight.light")).enabledIf(custom)
                    cell(lightColor).enabledIf(custom)
                    label(RepoLeapBundle.message("settings.highlight.dark")).enabledIf(custom)
                    cell(darkColor).enabledIf(custom)
                }
                row(RepoLeapBundle.message("settings.highlight.preview")) {
                    cell(preview)
                }
                row {
                    comment(RepoLeapBundle.message("settings.highlight.comment"))
                }
            }

            group(RepoLeapBundle.message("settings.group.opening")) {
                buttonsGroup(RepoLeapBundle.message("settings.openMode.label")) {
                    OpenMode.entries.forEach { mode ->
                        row { radioButton(mode.displayName, mode) }
                    }
                }.bind(state::openMode)
                row {
                    comment(RepoLeapBundle.message("settings.openMode.comment"))
                }
            }

            group(RepoLeapBundle.message("settings.group.remotes")) {
                row {
                    checkBox(RepoLeapBundle.message("settings.remotes.probe"))
                        .bindSelected(state::probeGitServers)
                        .comment(RepoLeapBundle.message("settings.remotes.probe.comment"))
                }
                row {
                    cell(remoteRules.component)
                        .align(Align.FILL)
                        .label(RepoLeapBundle.message("settings.remotes.rules"), LabelPosition.TOP)
                        .comment(RepoLeapBundle.message("settings.remotes.rules.comment", TEMPLATE_PLACEHOLDERS, TEMPLATE_EXAMPLE))
                }
            }

            collapsibleGroup(RepoLeapBundle.message("settings.group.tags")) {
                row {
                    cell(tagsPanel)
                        .align(Align.FILL)
                        .comment(RepoLeapBundle.message("settings.tags.comment"))
                }
            }.apply { expanded = state.repoTags.isNotEmpty() }

            collapsibleGroup(RepoLeapBundle.message("settings.group.hiddenRepos")) {
                row {
                    cell(hiddenPanel)
                        .align(Align.FILL)
                        .comment(RepoLeapBundle.message("settings.hiddenRepos.comment"))
                }
            }.apply { expanded = state.hiddenRepos.isNotEmpty() }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            rootsModel.items != settings.state.roots ||
            hiddenModel.items != settings.state.hiddenRepos ||
            remoteRules.rules != settings.state.remoteRules ||
            rgb(lightColor) != settings.state.highlightColorLight ||
            rgb(darkColor) != settings.state.highlightColorDark ||
            orderModel.items != RowPart.parse(settings.state.rowOrder) ||
            tagsDraft != settings.state.repoTags

    override fun apply() {
        super.apply()
        settings.state.roots = rootsModel.items.toMutableList()
        settings.state.hiddenRepos = hiddenModel.items.toMutableList()
        settings.state.remoteRules = remoteRules.rules.toMutableList()
        settings.state.highlightColorLight = rgb(lightColor)
        settings.state.highlightColorDark = rgb(darkColor)
        settings.state.rowOrder = RowPart.format(orderModel.items)
        settings.state.repoTags = tagsDraft.map { it.copy() }.toMutableList()
        separatorField?.text = settings.state.rowSeparator // shown as it is used, e.g. without the spaces around it
        RemoteBrowserUrls.getInstance().clearCache()
        RepoIndexService.getInstance().invalidate()
    }

    override fun reset() {
        super.reset()
        rootsModel.replaceAll(settings.state.roots)
        hiddenModel.replaceAll(settings.state.hiddenRepos)
        remoteRules.reset(settings.state.remoteRules)
        resetColors()
        resetOrder()
        resetTags()
        updatePreview()
    }

    private fun resetTags() {
        tagsDraft = settings.state.repoTags.map { it.copy() }.toMutableList()
        refreshTags()
    }

    private fun refreshTags() {
        tagCounts = RepoLeapSettings.countTags(tagsDraft)
        tagsModel.replaceAll(tagCounts.keys.toList())
    }

    private fun renameSelectedTag() {
        val tag = tagsList.selectedValue ?: return
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                if (inputString.isBlank() || Tags.parse(inputString).size == 1) null else RepoLeapBundle.message("settings.tags.rename.invalid")
            override fun checkInput(inputString: String): Boolean = Tags.parse(inputString).size == 1
            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
        val newName = Messages.showInputDialog(
            tagsList, RepoLeapBundle.message("settings.tags.rename.message", tag), RepoLeapBundle.message("settings.tags.rename"),
            null, tag, validator,
        ) ?: return
        renameTag(tag, newName)
    }

    private fun removeSelectedTags() {
        tagsList.selectedValuesList.forEach { renameTag(it, "") }
    }

    /** Renames [tag] on the page; an empty [newName] removes it. */
    private fun renameTag(tag: String, newName: String) {
        tagsDraft = RepoLeapSettings.renameTag(tagsDraft, tag, newName)
        refreshTags()
    }

    @TestOnly
    internal fun renameTagForTest(tag: String, newName: String) = renameTag(tag, newName)

    @TestOnly
    internal fun tagsForTest(): Map<String, Int> = tagCounts

    private fun resetOrder() {
        orderModel.replaceAll(RowPart.parse(settings.state.rowOrder))
    }

    private fun resetColors() {
        lightColor.selectedColor = Color(settings.state.highlightColorLight)
        darkColor.selectedColor = Color(settings.state.highlightColorDark)
    }

    private fun rgb(panel: ColorPanel): Int = (panel.selectedColor?.rgb ?: 0) and 0xFFFFFF

    /** A "Show next to the name" checkbox for [part]; the preview follows it at once. */
    private fun Row.showBox(part: RowPart, property: KMutableProperty0<Boolean>) =
        checkBox(part.displayName)
            .bindSelected(property)
            .applyToComponent {
                showBoxes[part] = this
                addActionListener { updatePreview() }
            }

    /** Whether [part] is switched on as currently chosen on the page (not applied yet). */
    private fun isShown(part: RowPart, saved: Boolean): Boolean = showBoxes[part]?.isSelected ?: saved

    /** The highlight as currently chosen on the page (not applied yet). */
    private fun chosenHighlight(): MatchHighlight = MatchHighlight(
        highlightStyleBox?.selectedItem as? HighlightStyle ?: settings.state.highlightStyle,
        if (customColorBox?.isSelected ?: settings.state.customHighlightColor) JBColor(rgb(lightColor), rgb(darkColor)) else null,
    )

    /** A repository row as the popup shows it while searching for `scapi dev`, once unselected and once selected. */
    private fun updatePreview() {
        val entry = RepoEntry("shop-customer-api", Path.of("dev", "shop-customer-api"), "~/dev/shop-customer-api", branch = "main")
        val match = RepoMatcher.match(RepoMatcher.tokenize(PREVIEW_QUERY), entry.name, entry.displayPath) ?: return
        val item = RepoListItem(entry, match, match.score, windowState = WindowState.OTHER, tags = listOf("work", "java"))
        val highlight = chosenHighlight()
        val separator = separatorField?.text ?: settings.state.rowSeparator
        val state = settings.state
        previewRows.forEachIndexed { index, renderer ->
            renderer.showBranch = isShown(RowPart.BRANCH, state.showBranch)
            renderer.showTags = isShown(RowPart.TAGS, state.showTags)
            renderer.showPath = isShown(RowPart.PATH, state.showPath)
            renderer.showWindowState = isShown(RowPart.WINDOW_STATE, state.showWindowState)
            renderer.highlight = highlight
            renderer.separator = separator
            renderer.order = orderModel.items.toList()
            renderer.getListCellRendererComponent(previewList, item, index, index == 1, true)
        }
        preview.revalidate()
        preview.repaint()
    }

    @TestOnly
    internal fun previewHighlightForTest(): MatchHighlight = previewRows.first().highlight

    @TestOnly
    internal fun previewSeparatorForTest(): String = previewRows.first().separator

    @TestOnly
    internal fun setSeparatorForTest(text: String) {
        separatorField!!.text = text
    }

    @TestOnly
    internal fun separatorFieldTextForTest(): String = separatorField!!.text

    @TestOnly
    internal fun separatorFieldForTest(): JBTextField = separatorField!!

    @TestOnly
    internal fun previewOrderForTest(): List<RowPart> = previewRows.first().order

    /** The text of the first preview row as painted. */
    @TestOnly
    internal fun previewTextForTest(): String {
        val iterator = previewRows.first().iterator()
        return buildList { while (iterator.hasNext()) add(iterator.next()) }.joinToString("")
    }

    /** Clicks the "Show next to the name" checkbox of [part] if it is not in the wanted state yet. */
    @TestOnly
    internal fun setShownForTest(part: RowPart, shown: Boolean) {
        val box = showBoxes.getValue(part)
        if (box.isSelected != shown) box.doClick(0)
    }

    @TestOnly
    internal fun orderForTest(): List<RowPart> = orderModel.items.toList()

    /** What the arrow buttons next to the order list do. */
    @TestOnly
    internal fun moveOrderForTest(from: Int, to: Int) {
        orderModel.exchangeRows(from, to)
    }

    @TestOnly
    internal fun remoteRulesTableForTest(): RemoteRulesTable = remoteRules

    private fun addFolders() {
        val descriptor = FileChooserDescriptorFactory.multiDirs()
            .withTitle(RepoLeapBundle.message("settings.folders.choose.title"))
            .withDescription(RepoLeapBundle.message("settings.folders.choose.description"))
        val chosen = FileChooser.chooseFiles(descriptor, rootsList, null, null)
        for (file in chosen) {
            val path = file.presentablePath()
            if (path !in rootsModel.items) rootsModel.add(path)
        }
    }

    private fun editSelectedFolder() {
        val index = rootsList.selectedIndex
        if (index < 0) return
        val current = rootsModel.getElementAt(index)
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(RepoLeapBundle.message("settings.folders.choose.title"))
        val toSelect = LocalFileSystem.getInstance().findFileByPath(current)
        val chosen = FileChooser.chooseFiles(descriptor, rootsList, null, toSelect).firstOrNull() ?: return
        rootsModel.setElementAt(chosen.presentablePath(), index)
    }

    private fun VirtualFile.presentablePath(): String = toNioPath().toString()

    private companion object {
        const val TEMPLATE_PLACEHOLDERS = "{base} {scheme} {host} {port} {path} {owner} {OWNER} {repo}"
        const val TEMPLATE_EXAMPLE = "{base}/browse/{OWNER}/{repo}"
        const val PREVIEW_QUERY = "scapi dev"
    }
}
