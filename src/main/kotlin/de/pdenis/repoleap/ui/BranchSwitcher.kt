package de.pdenis.repoleap.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.BranchList
import de.pdenis.repoleap.git.GitBranches
import de.pdenis.repoleap.git.GitSupport
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService

/** "Switch Branch…" from the action view: list the branches, let the user pick one, `git switch` in the background. EDT only. */
internal object BranchSwitcher {

    internal data class Item(val name: String, val remoteOnly: Boolean, val current: Boolean)

    internal fun items(branches: BranchList): List<Item> =
        branches.local.map { Item(it, remoteOnly = false, current = it == branches.current) }.sortedByDescending { it.current } +
            branches.remoteOnly.map { Item(it, remoteOnly = true, current = false) }

    /** Back to the actions of [repo], on the action that opened the branch list. */
    private fun back(host: RepoActionHost, repo: RepoEntry) = host.reopenActions(repo.path, RepoActionsProvider.ID_SWITCH_BRANCH)

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
            back(host, repo)
            return
        }
        val title = RepoLeapBundle.message("branch.title", repo.name)
        if (loaded == null) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("branch.noGit"), title)
            back(host, repo)
            return
        }
        val (git, result) = loaded
        val branches = result.getOrElse { error ->
            Messages.showErrorDialog(project, RepoLeapBundle.message("branch.listFailed", error.message.orEmpty()), title)
            back(host, repo)
            return
        }

        BranchPopup(title, items(branches)) { item ->
            ApplicationManager.getApplication().invokeLater({
                if (item == null || item.current) back(host, repo) else switch(project, repo, git, item, host)
            }, ModalityState.nonModal())
        }.show(project)
    }

    private fun switch(project: Project?, repo: RepoEntry, git: GitBranches, item: Item, host: RepoActionHost) {
        object : Task.Backgroundable(project, RepoLeapBundle.message("branch.progress", item.name), false) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                error = git.switch(repo.path, item.name, item.remoteOnly)
            }

            override fun onSuccess() {
                RepoIndexService.getInstance().rescan()
                val message = error
                if (message == null) {
                    // Done - the popup stays closed. The files of an open project changed on disk: let the IDE see them right away
                    if (RepoOpener.findOpenProject(repo.path) != null) {
                        LocalFileSystem.getInstance().refreshNioFiles(listOf(repo.path), true, true, null)
                    }
                    return
                }
                Messages.showErrorDialog(project, RepoLeapBundle.message("branch.failed", item.name, message),
                    RepoLeapBundle.message("branch.title", repo.name))
                back(host, repo) // try another branch
            }
        }.queue()
    }
}
