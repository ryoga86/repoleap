package de.pdenis.repoleap.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.GitRepoInfo
import de.pdenis.repoleap.git.GitSupport
import de.pdenis.repoleap.git.RemoteBrowserUrls
import de.pdenis.repoleap.open.RepoFileOperations
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.io.IOException

/** Info, rename and trash flows started from the action view. They run after the popup was closed. EDT only. */
internal object RepoDialogs {

    private const val NOTIFICATION_GROUP = "RepoLeap"

    fun showInfo(project: Project?, repo: RepoEntry, host: RepoActionHost) {
        val info = readGitInfo(project, repo, withWebPages = true)
        if (info == null) {
            host.reopen(repo.path)
            return
        }
        val openProject = RepoOpener.findOpenProject(repo.path)
        val openState = when {
            openProject == null -> RepoLeapBundle.message("info.state.closed")
            openProject == project -> RepoLeapBundle.message("info.state.current")
            else -> RepoLeapBundle.message("info.state.open")
        }
        if (RepoInfoDialog(project, repo, info, openState).showAndGet()) {
            RepoOpener.open(repo, RepoLeapSettings.getInstance().state.openMode, project?.takeUnless { it.isDisposed })
        } else {
            host.reopen(repo.path)
        }
    }

    fun rename(project: Project?, repo: RepoEntry, host: RepoActionHost) {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                when (RepoFileOperations.validateNewName(repo.path, inputString)) {
                    RepoFileOperations.NameProblem.EMPTY, RepoFileOperations.NameProblem.UNCHANGED, null -> null
                    RepoFileOperations.NameProblem.INVALID -> RepoLeapBundle.message("rename.error.invalid")
                    RepoFileOperations.NameProblem.ALREADY_EXISTS -> RepoLeapBundle.message("rename.error.exists")
                }

            override fun checkInput(inputString: String): Boolean =
                RepoFileOperations.validateNewName(repo.path, inputString) == null

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
        val newName = Messages.showInputDialog(
            project,
            RepoLeapBundle.message("rename.message", repo.displayPath),
            RepoLeapBundle.message("rename.title"),
            null,
            repo.name,
            validator,
        )
        if (newName == null) {
            host.reopen(repo.path)
            return
        }
        try {
            val newPath = RepoFileOperations.rename(repo.path, newName)
            RepoLeapSettings.getInstance().onRepositoryRenamed(repo.pathString, newPath.toString())
            LocalFileSystem.getInstance().refreshNioFiles(listOfNotNull(repo.path.parent))
            RepoIndexService.getInstance().rescan()
            host.reopen(newPath)
        } catch (e: IOException) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("rename.failed", e.message ?: ""), RepoLeapBundle.message("rename.title"))
            host.reopen(repo.path)
        }
    }

    fun moveToTrash(project: Project?, repo: RepoEntry, host: RepoActionHost) {
        val info = readGitInfo(project, repo, withWebPages = false)
        if (info == null) {
            host.reopen(repo.path)
            return
        }
        val confirmed = MessageDialogBuilder.yesNo(RepoLeapBundle.message("trash.title"), trashMessage(repo, info))
            .yesText(RepoLeapBundle.message("trash.confirm"))
            .noText(Messages.getCancelButton())
            .asWarning()
            .ask(project)
        if (!confirmed) {
            host.reopen(repo.path)
            return
        }

        val moved = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<Boolean, RuntimeException> { RepoFileOperations.moveToTrash(repo.path) },
            RepoLeapBundle.message("trash.progress"),
            false,
            project,
        )
        if (!moved) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("trash.failed", repo.displayPath), RepoLeapBundle.message("trash.title"))
            host.reopen(repo.path)
            return
        }
        RepoLeapSettings.getInstance().onRepositoryRemoved(repo.pathString)
        LocalFileSystem.getInstance().refreshNioFiles(listOfNotNull(repo.path.parent))
        RepoIndexService.getInstance().rescan()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(RepoLeapBundle.message("trash.done", repo.name), NotificationType.INFORMATION)
            .notify(project)
        host.reopen(null)
    }

    internal fun trashMessage(repo: RepoEntry, info: GitRepoInfo): String = buildString {
        append(RepoLeapBundle.message("trash.message", repo.name, repo.displayPath))
        append("\n\n")
        when {
            !info.gitAvailable -> append(RepoLeapBundle.message("trash.noGit"))
            info.error != null -> append(RepoLeapBundle.message("trash.checkFailed", info.error))
            info.risks.isEmpty() -> append(RepoLeapBundle.message("trash.safe"))
            else -> {
                append(RepoLeapBundle.message("trash.risks"))
                for (risk in info.risks) append("\n  \u2022 ").append(riskText(risk))
            }
        }
    }

    internal fun riskText(risk: GitRepoInfo.Risk): String = when (risk) {
        is GitRepoInfo.Risk.UncommittedChanges -> RepoLeapBundle.message("risk.changes", risk.count)
        is GitRepoInfo.Risk.Conflicts -> RepoLeapBundle.message("risk.conflicts", risk.count)
        is GitRepoInfo.Risk.UntrackedFiles -> RepoLeapBundle.message("risk.untracked", risk.count)
        is GitRepoInfo.Risk.UnpushedCommits -> RepoLeapBundle.message("risk.unpushed", risk.count)
        is GitRepoInfo.Risk.Stashes -> RepoLeapBundle.message("risk.stashes", risk.count)
        GitRepoInfo.Risk.NoRemote -> RepoLeapBundle.message("risk.noRemote")
    }

    /** Runs git (and resolves the web pages of the remotes) under a modal progress; `null` if the user cancelled. */
    private fun readGitInfo(project: Project?, repo: RepoEntry, withWebPages: Boolean): GitRepoInfo? =
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<GitRepoInfo, RuntimeException> {
                    val info = GitSupport.reader().read(repo.path)
                    if (withWebPages) info.copy(remotes = RemoteBrowserUrls.getInstance().resolveAll(info.remotes)) else info
                },
                RepoLeapBundle.message("progress.readingGit", repo.name),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            null
        }
}
