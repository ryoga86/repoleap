package de.pdenis.repoleap.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ListUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.RemoteBrowserUrls
import de.pdenis.repoleap.scan.RepoIndexService
import org.jetbrains.annotations.TestOnly
import javax.swing.ListSelectionModel

/** Settings | Tools | RepoLeap */
class RepoLeapConfigurable : BoundConfigurable(RepoLeapBundle.message("settings.displayName")) {

    private val settings = RepoLeapSettings.getInstance()
    private val rootsModel = CollectionListModel<String>()
    private val rootsList = JBList(rootsModel)
    private val hiddenModel = CollectionListModel<String>()
    private val hiddenList = JBList(hiddenModel)
    private val remoteRules = RemoteRulesTable()

    override fun createPanel(): DialogPanel {
        val state = settings.state

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

            group(RepoLeapBundle.message("settings.group.opening")) {
                buttonsGroup {
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
            remoteRules.rules != settings.state.remoteRules

    override fun apply() {
        super.apply()
        settings.state.roots = rootsModel.items.toMutableList()
        settings.state.hiddenRepos = hiddenModel.items.toMutableList()
        settings.state.remoteRules = remoteRules.rules.toMutableList()
        RemoteBrowserUrls.getInstance().clearCache()
        RepoIndexService.getInstance().invalidate()
    }

    override fun reset() {
        super.reset()
        rootsModel.replaceAll(settings.state.roots)
        hiddenModel.replaceAll(settings.state.hiddenRepos)
        remoteRules.reset(settings.state.remoteRules)
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
    }
}
