package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.BranchList
import de.pdenis.repoleap.git.GitBranches
import de.pdenis.repoleap.git.GitSupport
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import javax.swing.JList

/** "Switch Branch…" from the action view: list the branches, let the user pick one, `git switch` in the background. EDT only. */
internal object BranchSwitcher {

    internal data class Item(val name: String, val remoteOnly: Boolean, val current: Boolean)

    internal fun items(branches: BranchList): List<Item> =
        branches.local.map { Item(it, remoteOnly = false, current = it == branches.current) }.sortedByDescending { it.current } +
            branches.remoteOnly.map { Item(it, remoteOnly = true, current = false) }

    fun show(project: Project?, repo: RepoEntry, host: RepoActionHost) {
        val loaded = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Pair<GitBranches, Result<BranchList>>?, RuntimeException> {
                    GitSupport.branches()?.let { git -> git to git.list(repo.path) }
                },
                RepoLeapBundle.message("progress.readingGit", repo.name),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            host.reopen(repo.path)
            return
        }
        val title = RepoLeapBundle.message("branch.title", repo.name)
        if (loaded == null) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("branch.noGit"), title)
            host.reopen(repo.path)
            return
        }
        val (git, result) = loaded
        val branches = result.getOrElse { error ->
            Messages.showErrorDialog(project, RepoLeapBundle.message("branch.listFailed", error.message.orEmpty()), title)
            host.reopen(repo.path)
            return
        }

        var chosen: Item? = null
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items(branches))
            .setTitle(title)
            .setRenderer(Renderer())
            .setNamerForFiltering { it.name }
            .setVisibleRowCount(VISIBLE_ROWS)
            .setItemChosenCallback { chosen = it }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    val item = chosen
                    ApplicationManager.getApplication().invokeLater({
                        if (item == null || item.current) host.reopen(repo.path) else switch(project, repo, git, item, host)
                    }, ModalityState.nonModal())
                }
            })
            .createPopup()
        if (project != null && !project.isDisposed) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }

    private fun switch(project: Project?, repo: RepoEntry, git: GitBranches, item: Item, host: RepoActionHost) {
        object : Task.Backgroundable(project, RepoLeapBundle.message("branch.progress", item.name), false) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                error = git.switch(repo.path, item.name, item.remoteOnly)
            }

            override fun onSuccess() {
                val message = error
                if (message != null) {
                    Messages.showErrorDialog(project, RepoLeapBundle.message("branch.failed", item.name, message),
                        RepoLeapBundle.message("branch.title", repo.name))
                } else if (RepoOpener.findOpenProject(repo.path) != null) {
                    // The files of an open project changed on disk - let the IDE see them right away
                    LocalFileSystem.getInstance().refreshNioFiles(listOf(repo.path), true, true, null)
                }
                RepoIndexService.getInstance().rescan()
                host.reopen(repo.path)
            }
        }.queue()
    }

    private class Renderer : ColoredListCellRenderer<Item>() {
        override fun customizeCellRenderer(list: JList<out Item>, value: Item?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value == null) return
            icon = if (value.current) AllIcons.Actions.Checked else AllIcons.Vcs.Branch
            append(value.name, if (value.current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            when {
                value.current -> append("  " + RepoLeapBundle.message("branch.current"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value.remoteOnly -> append("  " + RepoLeapBundle.message("branch.remote"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private const val VISIBLE_ROWS = 15
}
