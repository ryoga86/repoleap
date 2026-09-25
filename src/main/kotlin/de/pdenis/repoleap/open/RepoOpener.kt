package de.pdenis.repoleap.open

import com.intellij.ide.GeneralSettings
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.settings.OpenMode
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.nio.file.Files
import java.nio.file.Path

/** Opens a repository folder as a project. EDT only. */
object RepoOpener {

    private const val NOTIFICATION_GROUP = "RepoLeap"

    fun open(repo: RepoEntry, mode: OpenMode, currentProject: Project?) {
        val path = repo.path
        if (!Files.isDirectory(path)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(RepoLeapBundle.message("notification.repoMissing", repo.displayPath), NotificationType.WARNING)
                .notify(currentProject)
            RepoIndexService.getInstance().rescan()
            return
        }

        RepoLeapSettings.getInstance().markRecentlyOpened(repo.pathString)

        // Already open in some window/tab -> bring exactly that window to front, whatever the open mode is.
        // This has to be checked here: openOrImport(..., forceOpenInNewFrame = true) skips the check and would start
        // opening a second instance, which the platform then cancels (leaving the focus on some other window).
        findOpenProject(path)?.let { openProject ->
            ProjectUtil.focusProjectWindow(openProject, true)
            return
        }

        val project = currentProject?.takeUnless { it.isDisposed }
        when (mode) {
            OpenMode.NEW_WINDOW -> ProjectUtil.openOrImport(path, null, true)
            OpenMode.IDE_DEFAULT -> ProjectUtil.openOrImport(path, project, false)
            OpenMode.THIS_WINDOW ->
                if (project == null) ProjectUtil.openOrImport(path, null, true)
                else openInSameWindow(path, project)
        }
    }

    /** The open project whose base directory is [path], if any. */
    fun findOpenProject(path: Path): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { project ->
            !project.isDisposed && ProjectUtil.isSameProject(path, project)
        }

    /**
     * Closes [project] like *File | Close Project*: the platform asks about running processes etc.,
     * remembers the window position and shows the Welcome screen if no project is left. `false` if it was not closed.
     */
    fun close(project: Project): Boolean {
        if (project.isDisposed) return true
        WindowManager.getInstance().updateDefaultFrameInfoOnProjectClose(project)
        if (!ProjectManager.getInstance().closeAndDispose(project)) return false
        RecentProjectsManager.getInstance().updateLastProjectPath()
        WelcomeFrame.showIfNoProjectOpened()
        return true
    }

    /**
     * The public API has no "reuse this frame without asking" switch, so the IDE setting "Open project in"
     * is set to "This window" for the duration of the (synchronous) open call and restored afterwards.
     */
    private fun openInSameWindow(path: Path, project: Project) {
        val generalSettings = GeneralSettings.getInstance()
        val previous = generalSettings.confirmOpenNewProject
        generalSettings.confirmOpenNewProject = GeneralSettings.OPEN_PROJECT_SAME_WINDOW
        try {
            ProjectUtil.openOrImport(path, project, false)
        } finally {
            generalSettings.confirmOpenNewProject = previous
        }
    }
}
