package de.pdenis.repoleap.open

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Native window tabs: several project windows as tabs of one window (macOS). The IDE draws its own tab bar for
 * them and only keeps it up to date for its own operations - so windows are never re-grouped with native calls
 * here, only through IntelliJ's own *Window | Merge All Windows* or by letting macOS open a window as a tab.
 */
internal interface WindowTabs {
    /** Native window tabs exist on this system at all. */
    val isSupported: Boolean

    /** Are the windows of [a] and [b] tabs of the same window? */
    fun areInSameGroup(a: Project, b: Project): Boolean

    /** Does macOS open new project windows as tabs of [host]'s window ("Prefer tabs when opening documents")? */
    fun newWindowsBecomeTabsOf(host: Project): Boolean

    /** Runs IntelliJ's *Window | Merge All Windows* for [host]'s window. */
    fun mergeAllWindowsInto(host: Project): Boolean

    companion object {
        fun forThisSystem(): WindowTabs = if (SystemInfo.isMac) MacWindowTabs else NoWindowTabs
    }
}

internal object NoWindowTabs : WindowTabs {
    override val isSupported = false
    override fun areInSameGroup(a: Project, b: Project) = false
    override fun newWindowsBecomeTabsOf(host: Project) = false
    override fun mergeAllWindowsInto(host: Project) = false
}

internal object MacWindowTabs : WindowTabs {

    private const val MERGE_ALL_WINDOWS_ACTION = "MergeAllWindowsAction"
    private const val NS_NOT_FOUND = Long.MAX_VALUE

    // NSWindowUserTabbingPreference
    private const val TABBING_ALWAYS = 1
    private const val TABBING_IN_FULL_SCREEN = 2

    override val isSupported: Boolean
        get() = ActionManager.getInstance().getAction(MERGE_ALL_WINDOWS_ACTION) != null

    override fun areInSameGroup(a: Project, b: Project): Boolean = safely(false) {
        val windowA = nsWindow(a) ?: return@safely false
        val windowB = nsWindow(b) ?: return@safely false
        val tabs = Foundation.invoke(windowA, "tabbedWindows").takeUnless { it.toLong() == 0L } ?: return@safely false
        Foundation.invoke(tabs, "indexOfObject:", windowB).toLong() != NS_NOT_FOUND
    }

    override fun newWindowsBecomeTabsOf(host: Project): Boolean = safely(false) {
        when (Foundation.invoke("NSWindow", "userTabbingPreference").toInt()) {
            TABBING_ALWAYS -> true
            TABBING_IN_FULL_SCREEN -> WindowManager.getInstance().getIdeFrame(host)?.isInFullScreen == true
            else -> false
        }
    }

    override fun mergeAllWindowsInto(host: Project): Boolean {
        val action = ActionManager.getInstance().getAction(MERGE_ALL_WINDOWS_ACTION) ?: return false
        val frame = WindowManager.getInstance().getFrame(host) ?: return false
        ActionManager.getInstance().tryToExecute(action, null, frame.rootPane, "RepoLeap", true)
        return true
    }

    private fun nsWindow(project: Project): ID? {
        if (project.isDisposed) return null
        val frame = WindowManager.getInstance().getFrame(project) ?: return null
        return MacUtil.getWindowFromJavaWindow(frame).takeUnless { it.toLong() == 0L }
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            thisLogger().warn("Native window tabs are not available", e)
            fallback
        }
}

/** Brings a project that is open in another window into [host]'s window as a tab. */
internal class WindowMerger(
    val tabs: WindowTabs,
    private val openProjects: () -> List<Project> = { ProjectManager.getInstance().openProjects.filterNot { it.isDisposed } },
) {
    enum class Way {
        /** All other windows are exactly [other]'s window -> IntelliJ's *Merge All Windows*, nothing is reloaded. */
        MERGE_ALL_WINDOWS,

        /** macOS opens new windows as tabs -> close [other] and open it again from [host]'s window. */
        REOPEN_AS_TAB,

        /** Already a tab of [host]'s window. */
        ALREADY_TAB,

        /** Not possible with the current macOS tab setting. */
        NOT_POSSIBLE,

        /** No native window tabs on this system. */
        UNSUPPORTED,
    }

    fun wayToMerge(host: Project, other: Project): Way {
        if (!tabs.isSupported || host == other) return Way.UNSUPPORTED
        if (tabs.areInSameGroup(host, other)) return Way.ALREADY_TAB
        val outside = openProjects().filter { it != host && !tabs.areInSameGroup(host, it) }
        return when {
            outside == listOf(other) -> Way.MERGE_ALL_WINDOWS
            tabs.newWindowsBecomeTabsOf(host) -> Way.REOPEN_AS_TAB
            else -> Way.NOT_POSSIBLE
        }
    }

    /** EDT. */
    fun merge(host: Project, other: Project, way: Way) {
        when (way) {
            Way.MERGE_ALL_WINDOWS -> {
                if (tabs.mergeAllWindowsInto(host)) selectLater(other)
            }
            Way.REOPEN_AS_TAB -> {
                val path = other.basePath?.let { Path.of(it) } ?: return
                if (!RepoOpener.close(other)) return
                ProjectUtil.focusProjectWindow(host, true)
                // Opened while host's window is the active one -> macOS adds the new window as its tab
                ApplicationManager.getApplication().invokeLater({
                    if (!host.isDisposed) ProjectUtil.openOrImport(path, null, true)
                }, ModalityState.nonModal())
            }
            Way.ALREADY_TAB, Way.NOT_POSSIBLE, Way.UNSUPPORTED -> Unit
        }
    }

    /** Select the merged tab once the IDE has rebuilt its tab bar. */
    private fun selectLater(project: Project) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) ProjectUtil.focusProjectWindow(project, true)
            }, ModalityState.nonModal())
        }, SELECT_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val SELECT_DELAY_MILLIS = 400L
    }
}
