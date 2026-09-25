package de.pdenis.repoleap.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import de.pdenis.repoleap.git.HostRule
import de.pdenis.repoleap.scan.ScanOptions

/**
 * Application-level settings. Paths are machine specific, therefore the file is not roamed/synced.
 */
@Service(Service.Level.APP)
@State(
    name = "RepoLeapSettings",
    storages = [Storage(value = "repoLeap.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.TOOLS,
)
class RepoLeapSettings : PersistentStateComponent<RepoLeapSettings.State> {

    class State {
        var roots: MutableList<String> = mutableListOf()
        var maxDepth: Int = DEFAULT_MAX_DEPTH
        var excludedFolderNames: String = DEFAULT_EXCLUDED_FOLDER_NAMES
        var skipHiddenFolders: Boolean = true
        var openMode: OpenMode = OpenMode.NEW_WINDOW
        var recentRepos: MutableList<String> = mutableListOf()
        /** Repositories that are always listed first. */
        var pinnedRepos: MutableList<String> = mutableListOf()
        /** Repositories that are not listed at all (can be shown again on the settings page). */
        var hiddenRepos: MutableList<String> = mutableListOf()
        /** Ask unknown self-hosted git servers whether they are a Bitbucket Server (one request per host). */
        var probeGitServers: Boolean = true
        /** Host -> type/template rules for "Open Remote in Browser", first match wins. */
        var remoteRules: MutableList<HostRule> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun scanOptions(): ScanOptions = ScanOptions(
        roots = myState.roots.toList(),
        maxDepth = myState.maxDepth.coerceIn(MIN_DEPTH, MAX_DEPTH),
        excludedFolderNames = parseFolderNames(myState.excludedFolderNames),
        skipHiddenFolders = myState.skipHiddenFolders,
    )

    /** Most recently opened repository paths, newest first. */
    val recentRepos: List<String>
        get() = myState.recentRepos.toList()

    fun markRecentlyOpened(path: String) {
        val recent = myState.recentRepos
        recent.remove(path)
        recent.add(0, path)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
    }

    fun isPinned(path: String): Boolean = path in myState.pinnedRepos

    fun setPinned(path: String, pinned: Boolean) {
        myState.pinnedRepos.remove(path)
        if (pinned) myState.pinnedRepos.add(path)
    }

    fun isHidden(path: String): Boolean = path in myState.hiddenRepos

    fun setHidden(path: String, hidden: Boolean) {
        myState.hiddenRepos.remove(path)
        if (hidden) {
            myState.hiddenRepos.add(path)
            myState.pinnedRepos.remove(path)
        }
    }

    /** Keeps recent/pinned/hidden entries when a repository folder was renamed. */
    fun onRepositoryRenamed(oldPath: String, newPath: String) {
        for (list in listOf(myState.recentRepos, myState.pinnedRepos, myState.hiddenRepos)) {
            val index = list.indexOf(oldPath)
            if (index >= 0) list[index] = newPath
        }
    }

    /** Forgets a repository that was moved to the Trash. */
    fun onRepositoryRemoved(path: String) {
        for (list in listOf(myState.recentRepos, myState.pinnedRepos, myState.hiddenRepos)) list.remove(path)
    }

    companion object {
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 10
        const val DEFAULT_MAX_DEPTH = 3
        const val MAX_RECENT = 30
        const val DEFAULT_EXCLUDED_FOLDER_NAMES =
            "node_modules, build, target, out, dist, vendor, venv, bower_components, Library"

        @JvmStatic
        fun getInstance(): RepoLeapSettings = service()

        fun parseFolderNames(text: String): Set<String> =
            text.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}
