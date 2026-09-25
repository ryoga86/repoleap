package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.GitSupport
import de.pdenis.repoleap.git.Remote
import de.pdenis.repoleap.git.RemoteBrowserUrls
import de.pdenis.repoleap.open.RepoFileOperations
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.open.WindowMerger
import de.pdenis.repoleap.open.WindowTabs
import de.pdenis.repoleap.settings.OpenMode
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.Icon

/** One entry in the action view of the popup. */
internal class RepoAction(
    val id: String,
    val text: String,
    val icon: Icon,
    /** Shortcut or additional information shown in grey. */
    val hint: String? = null,
    /** If set, the action is shown greyed out with this explanation and can't be run. */
    val disabledReason: String? = null,
    val perform: () -> Unit,
) {
    val enabled: Boolean get() = disabledReason == null
}

/** What the actions need from the popup. */
internal interface RepoActionHost {
    fun open(item: RepoListItem, alternative: Boolean)

    /** Closes the popup, then runs [block] on the EDT. */
    fun closeThen(block: () -> Unit)

    /** Shows a new popup with the same search text, selecting [selection] once it is listed. */
    fun reopen(selection: Path?)

    /** Leaves the action view and refreshes the repository list (e.g. after pin/hide). */
    fun backToRepositories()
}

/** Builds the actions for a repository. EDT only. */
internal class RepoActionsProvider(
    private val project: Project?,
    private val host: RepoActionHost,
    private val merger: WindowMerger = WindowMerger(WindowTabs.forThisSystem()),
    private val findOpenProject: (Path) -> Project? = RepoOpener::findOpenProject,
) {

    private val settings get() = RepoLeapSettings.getInstance()

    private fun mergePlan(openProject: Project?): Pair<Project, WindowMerger.Way>? {
        val tabHost = project?.takeUnless { it.isDisposed } ?: return null
        val other = openProject?.takeIf { it != tabHost } ?: return null
        return tabHost to merger.wayToMerge(tabHost, other)
    }

    /**
     * Shift+Enter on a repository that is open in another window: bring that window into this one as a tab.
     * @return `false` if that is not possible (then Shift+Enter behaves as usual)
     */
    fun mergeIntoThisWindow(item: RepoListItem): Boolean {
        if (item.windowState != WindowState.OTHER) return false
        val other = findOpenProject(item.repo.path) ?: return false
        val (tabHost, way) = mergePlan(other) ?: return false
        if (way != WindowMerger.Way.MERGE_ALL_WINDOWS && way != WindowMerger.Way.REOPEN_AS_TAB) return false
        host.closeThen { merger.merge(tabHost, other, way) }
        return true
    }

    /** [onUpdate] receives actions whose details were completed in the background (the remote's web page). */
    fun actionsFor(item: RepoListItem, onUpdate: (RepoAction) -> Unit = {}): List<RepoAction> {
        val repo = item.repo
        val path = repo.path
        val openProject = findOpenProject(path)
        val state = when {
            openProject == null -> WindowState.CLOSED
            openProject == project -> WindowState.CURRENT
            else -> WindowState.OTHER
        }
        val isOpen = openProject != null
        val closeFirst = RepoLeapBundle.message("action.disabled.open")
        val pinned = settings.isPinned(repo.pathString)
        val remote = GitSupport.preferredRemote(path)

        val windowActions = buildList {
            add(RepoAction(
                ID_OPEN,
                RepoLeapBundle.message("action.open"),
                AllIcons.Actions.MenuOpen,
                hint = when (state) {
                    WindowState.CLOSED -> "Enter"
                    WindowState.OTHER -> "Enter \u00b7 " + RepoLeapBundle.message("action.open.hint.other")
                    WindowState.CURRENT -> "Enter \u00b7 " + RepoLeapBundle.message("action.open.hint.current")
                },
            ) { host.open(item, alternative = false) })

            if (state == WindowState.CLOSED) {
                add(RepoAction(
                    ID_OPEN_ALTERNATIVE,
                    if (settings.state.openMode.alternative == OpenMode.NEW_WINDOW) RepoLeapBundle.message("action.open.newWindow")
                    else RepoLeapBundle.message("action.open.thisWindow"),
                    AllIcons.Actions.MoveToWindow,
                    hint = "Shift+Enter",
                ) { host.open(item, alternative = true) })
            }

            val plan = if (state == WindowState.OTHER) mergePlan(openProject) else null
            if (plan != null && openProject != null) {
                val (tabHost, way) = plan
                when (way) {
                    WindowMerger.Way.MERGE_ALL_WINDOWS, WindowMerger.Way.REOPEN_AS_TAB -> add(RepoAction(
                        ID_MERGE,
                        RepoLeapBundle.message("action.merge"),
                        AllIcons.Actions.OpenNewTab,
                        hint = if (way == WindowMerger.Way.REOPEN_AS_TAB) "Shift+Enter \u00b7 " + RepoLeapBundle.message("action.merge.reopens")
                        else "Shift+Enter",
                    ) { host.closeThen { merger.merge(tabHost, openProject, way) } })
                    WindowMerger.Way.NOT_POSSIBLE -> add(RepoAction(
                        ID_MERGE,
                        RepoLeapBundle.message("action.merge"),
                        AllIcons.Actions.OpenNewTab,
                        disabledReason = RepoLeapBundle.message("action.disabled.tabsPreference"),
                    ) {})
                    WindowMerger.Way.ALREADY_TAB, WindowMerger.Way.UNSUPPORTED -> Unit
                }
            }
        }

        return windowActions + listOf(
            RepoAction(
                ID_CLOSE,
                RepoLeapBundle.message("action.close"),
                AllIcons.Actions.Close,
                hint = when (state) {
                    WindowState.CLOSED -> null
                    WindowState.CURRENT -> RepoLeapBundle.message("popup.tag.current")
                    WindowState.OTHER -> RepoLeapBundle.message("popup.tag.open")
                },
                disabledReason = if (isOpen) null else RepoLeapBundle.message("action.disabled.notOpen"),
            ) {
                host.closeThen {
                    val target = findOpenProject(path)
                    if (target != null) RepoOpener.close(target)
                    // Start at the top again: the closed repository moves from the open ones down into the rest,
                    // selecting it there would scroll the list to somewhere in the middle
                    host.reopen(null)
                }
            },
            RepoAction(ID_INFO, RepoLeapBundle.message("action.info"), AllIcons.General.Information) {
                host.closeThen { RepoDialogs.showInfo(project, item.repo, host) }
            },
            RepoAction(
                ID_REVEAL,
                RevealFileAction.getActionName(),
                AllIcons.Actions.ProjectDirectory,
                disabledReason = if (RevealFileAction.isSupported()) null else RepoLeapBundle.message("action.disabled.unsupported"),
            ) { host.closeThen { RevealFileAction.openDirectory(path) } },
            RepoAction(ID_COPY_PATH, RepoLeapBundle.message("action.copyPath"), AllIcons.Actions.Copy, hint = repo.displayPath) {
                CopyPasteManager.getInstance().setContents(StringSelection(repo.pathString))
                host.closeThen {}
            },
            browserAction(remote, onUpdate),
            RepoAction(
                ID_PIN,
                if (pinned) RepoLeapBundle.message("action.unpin") else RepoLeapBundle.message("action.pin"),
                AllIcons.General.Pin_tab,
            ) {
                settings.setPinned(repo.pathString, !pinned)
                host.backToRepositories()
            },
            RepoAction(ID_HIDE, RepoLeapBundle.message("action.hide"), AllIcons.General.HideToolWindow,
                hint = RepoLeapBundle.message("action.hide.hint")) {
                settings.setHidden(repo.pathString, true)
                host.backToRepositories()
            },
            RepoAction(
                ID_RENAME,
                RepoLeapBundle.message("action.rename"),
                AllIcons.Actions.Edit,
                disabledReason = if (isOpen) closeFirst else null,
            ) { host.closeThen { RepoDialogs.rename(project, repo, host) } },
            RepoAction(
                ID_TRASH,
                RepoLeapBundle.message("action.trash"),
                AllIcons.General.Delete,
                disabledReason = when {
                    isOpen -> closeFirst
                    !RepoFileOperations.isTrashSupported() -> RepoLeapBundle.message("action.disabled.noTrash")
                    else -> null
                },
            ) { host.closeThen { RepoDialogs.moveToTrash(project, repo, host) } },
        )
    }

    /**
     * The hint shows the complete web page. If the server has to be asked for its type first, the remote URL is
     * shown until the answer is there (background, then [onUpdate]).
     */
    private fun browserAction(remote: Remote?, onUpdate: (RepoAction) -> Unit): RepoAction {
        fun create(hint: String?) = RepoAction(
            ID_BROWSER,
            RepoLeapBundle.message("action.browser"),
            AllIcons.General.Web,
            hint = hint,
            disabledReason = if (remote == null) RepoLeapBundle.message("action.disabled.noRemote") else null,
        ) {
            // Resolving may ask the git server for its type -> background task after the popup is closed
            host.closeThen { RemoteBrowserUrls.getInstance().openInBrowser(project, remote!!) }
        }
        if (remote == null) return create(null)
        val links = RemoteBrowserUrls.getInstance()
        links.resolveWithoutNetwork(remote.url)?.let { return create(it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = links.resolve(remote.url) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ onUpdate(create(resolved)) }, ModalityState.any())
        }
        return create(remote.url)
    }

    companion object {
        const val ID_OPEN = "open"
        const val ID_OPEN_ALTERNATIVE = "openAlternative"
        const val ID_MERGE = "merge"
        const val ID_CLOSE = "close"
        const val ID_INFO = "info"
        const val ID_REVEAL = "reveal"
        const val ID_COPY_PATH = "copyPath"
        const val ID_BROWSER = "browser"
        const val ID_PIN = "pin"
        const val ID_HIDE = "hide"
        const val ID_RENAME = "rename"
        const val ID_TRASH = "trash"
    }
}
