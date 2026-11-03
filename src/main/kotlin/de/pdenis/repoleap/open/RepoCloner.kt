package de.pdenis.repoleap.open

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.ExecutableFinder
import de.pdenis.repoleap.git.GitClone
import de.pdenis.repoleap.git.RemoteUrls
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.scan.RepoScanner
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Where a pasted remote URL can be cloned to: a configured root folder, or ([target] `null`) a folder to choose. */
data class CloneTarget(
    val url: String,
    val name: String,
    /** `root/name`; `null` for "another folder…". */
    val target: Path?,
    /** [target] with `~` for the home folder. */
    val displayTarget: String = target?.let { RepoScanner.abbreviateHome(it.toString()) }.orEmpty(),
    /** Why it can't be used, e.g. the folder exists already. */
    val problem: String? = null,
) {
    val enabled: Boolean get() = problem == null
}

/** Clones a repository from the popup and opens it. EDT only, except [targets]. */
object RepoCloner {

    private const val NOTIFICATION_GROUP = "RepoLeap"

    /** One target per root folder (`root/<repo>`), then "another folder…". Pure. */
    fun targets(url: String, roots: List<String>): List<CloneTarget> {
        val name = RemoteUrls.repositoryName(url) ?: return emptyList()
        val inRoots = roots.mapNotNull { RepoScanner.resolveRoot(it) }.distinct().mapNotNull { root ->
            val target = try {
                root.resolve(name).normalize()
            } catch (_: InvalidPathException) {
                return@mapNotNull null
            }
            CloneTarget(url, name, target, problem = if (Files.exists(target)) RepoLeapBundle.message("clone.exists") else null)
        }
        return inRoots + CloneTarget(url, name, null)
    }

    /** `true` if a repository at [target] is found by the scan of [roots] (below a root within [maxDepth] levels). */
    fun isCovered(target: Path, roots: List<String>, maxDepth: Int): Boolean =
        roots.mapNotNull { RepoScanner.resolveRoot(it) }.any { root ->
            target.startsWith(root) && target != root && root.relativize(target).nameCount <= maxDepth
        }

    /** Lets the user choose the parent folder, then clones there; the folder becomes a root folder if needed. */
    fun chooseFolderAndClone(project: Project?, target: CloneTarget, alternative: Boolean, onFailure: () -> Unit) {
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(RepoLeapBundle.message("clone.choose.title"))
            .withDescription(RepoLeapBundle.message("clone.choose.description", target.name))
        val folder = FileChooser.chooseFile(descriptor, project, null)?.toNioPath()
        if (folder == null) {
            onFailure()
            return
        }
        val destination = folder.resolve(target.name)
        if (Files.exists(destination)) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("clone.existsAt", RepoScanner.abbreviateHome(destination.toString())),
                RepoLeapBundle.message("clone.title", target.name))
            onFailure()
            return
        }
        clone(project, target.url, destination, alternative, addRootIfNeeded = folder, onFailure = onFailure)
    }

    /**
     * Clones [url] into [destination] in the background and opens it with the configured open mode
     * (its alternative with [alternative]). [onFailure] runs after an error was shown.
     */
    fun clone(
        project: Project?,
        url: String,
        destination: Path,
        alternative: Boolean,
        addRootIfNeeded: Path? = null,
        onFailure: () -> Unit = {},
    ) {
        val name = destination.fileName?.toString() ?: url
        val title = RepoLeapBundle.message("clone.title", name)
        val git = ExecutableFinder.find("git")?.toString()
        if (git == null) {
            Messages.showErrorDialog(project, RepoLeapBundle.message("branch.noGit"), title)
            onFailure()
            return
        }
        object : Task.Backgroundable(project, RepoLeapBundle.message("clone.progress", name, url), true) {
            private var result: GitClone.Result = GitClone.Result.Canceled

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = GitClone(git).clone(url, destination, onProgress = { line ->
                    indicator.text2 = line
                    GitClone.fraction(line)?.let {
                        indicator.isIndeterminate = false
                        indicator.fraction = it
                    }
                }, isCanceled = { indicator.isCanceled })
            }

            override fun onSuccess() {
                when (val done = result) {
                    GitClone.Result.Success -> opened(project, destination, alternative, addRootIfNeeded)
                    GitClone.Result.Canceled -> Unit
                    is GitClone.Result.Failed -> {
                        val hint = if (done.isAuthenticationProblem) "\n\n" + RepoLeapBundle.message("clone.authHint") else ""
                        Messages.showErrorDialog(project, RepoLeapBundle.message("clone.failed", url, done.message) + hint, title)
                        onFailure()
                    }
                }
            }
        }.queue()
    }

    private fun opened(project: Project?, destination: Path, alternative: Boolean, addRootIfNeeded: Path?) {
        val settings = RepoLeapSettings.getInstance()
        val state = settings.state
        if (addRootIfNeeded != null && !isCovered(destination, state.roots, settings.scanOptions().maxDepth)) {
            state.roots.add(addRootIfNeeded.toString())
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    RepoLeapBundle.message("clone.rootAdded", RepoScanner.abbreviateHome(addRootIfNeeded.toString())),
                    NotificationType.INFORMATION,
                )
                .notify(project)
        }
        LocalFileSystem.getInstance().refreshNioFiles(listOfNotNull(destination.parent))
        RepoIndexService.getInstance().rescan()
        val entry = RepoEntry(
            destination.fileName.toString(), destination, RepoScanner.abbreviateHome(destination.toString()),
        )
        val mode = if (alternative) state.openMode.alternative else state.openMode
        RepoOpener.open(entry, mode, project?.takeUnless { it.isDisposed })
    }
}
