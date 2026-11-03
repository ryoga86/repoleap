package de.pdenis.repoleap.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Transient
import de.pdenis.repoleap.git.HostRule
import de.pdenis.repoleap.scan.ScanOptions
import de.pdenis.repoleap.search.Tags

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
        /** Set in the settings and with the sort button of the popup. */
        var sortMode: SortMode = SortMode.SMART
        /** Parts of a popup row next to the name. Hiding the path does not exclude it from the search. */
        var showBranch: Boolean = true
        var showPath: Boolean = true
        var showWindowState: Boolean = true
        /** Text between the parts of a popup row (name, branch, tags, path, window state); empty = only spaces. */
        var rowSeparator: String = ""
        /** Order of the parts after the name, see [RowPart.parse] (stored as one string, robust against unknown values). */
        var rowOrder: String = RowPart.format(RowPart.DEFAULT_ORDER)
        /** How matched characters are marked in the popup lists. */
        var highlightStyle: HighlightStyle = HighlightStyle.TEXT_COLOR
        /** Use [highlightColorLight] / [highlightColorDark] instead of the theme's color. */
        var customHighlightColor: Boolean = false
        /** RGB, used with light themes. */
        var highlightColorLight: Int = DEFAULT_HIGHLIGHT_LIGHT
        /** RGB, used with dark themes. */
        var highlightColorDark: Int = DEFAULT_HIGHLIGHT_DARK
        var recentRepos: MutableList<String> = mutableListOf()
        /** Repositories that are always listed first. */
        var pinnedRepos: MutableList<String> = mutableListOf()
        /** Repositories that are not listed at all (can be shown again on the settings page). */
        var hiddenRepos: MutableList<String> = mutableListOf()
        /** Ask unknown self-hosted git servers whether they are a Bitbucket Server (one request per host). */
        var probeGitServers: Boolean = true
        /** Host -> type/template rules for "Open Remote in Browser", first match wins. */
        var remoteRules: MutableList<HostRule> = mutableListOf()
        /** Tags of the repositories, searched with `#tag`. Only repositories that have tags are listed. */
        var repoTags: MutableList<RepoTags> = mutableListOf()
        var showTags: Boolean = true
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

    /** Keeps recent/pinned/hidden entries and tags when a repository folder was renamed. */
    fun onRepositoryRenamed(oldPath: String, newPath: String) {
        for (list in listOf(myState.recentRepos, myState.pinnedRepos, myState.hiddenRepos)) {
            val index = list.indexOf(oldPath)
            if (index >= 0) list[index] = newPath
        }
        myState.repoTags.filter { it.path == oldPath }.forEach { it.path = newPath }
    }

    /** Forgets a repository that was moved to the Trash. */
    fun onRepositoryRemoved(path: String) {
        for (list in listOf(myState.recentRepos, myState.pinnedRepos, myState.hiddenRepos)) list.remove(path)
        myState.repoTags.removeIf { it.path == path }
    }

    // --- tags -------------------------------------------------------------------------------------------------

    fun tagsOf(path: String): List<String> = myState.repoTags.firstOrNull { it.path == path }?.tagList.orEmpty()

    /** Replaces the tags of [path]; see [Tags.parse] for the rules. */
    fun setTags(path: String, tags: List<String>) {
        myState.repoTags.removeIf { it.path == path }
        val cleaned = Tags.parse(tags.joinToString(" "))
        if (cleaned.isNotEmpty()) myState.repoTags.add(RepoTags(path, cleaned.joinToString(" ")))
    }

    /** All tags with the number of repositories that have them, by name. */
    fun allTags(): Map<String, Int> = countTags(myState.repoTags)

    companion object {
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 10
        const val DEFAULT_MAX_DEPTH = 3
        const val MAX_RECENT = 30
        const val MAX_SEPARATOR_LENGTH = 3
        const val DEFAULT_HIGHLIGHT_LIGHT = 0xC25A0A
        const val DEFAULT_HIGHLIGHT_DARK = 0xE08855
        const val DEFAULT_EXCLUDED_FOLDER_NAMES =
            "node_modules, build, target, out, dist, vendor, venv, bower_components, Library"

        @JvmStatic
        fun getInstance(): RepoLeapSettings = service()

        fun parseFolderNames(text: String): Set<String> =
            text.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        /** The separator of a popup row as it is used: without white space around it, at most [MAX_SEPARATOR_LENGTH] characters. */
        fun normalizeSeparator(text: String): String = text.trim().take(MAX_SEPARATOR_LENGTH).trim()

        /** Tag -> number of repositories, sorted by name (ignoring case); spelled as first seen. */
        fun countTags(entries: List<RepoTags>): Map<String, Int> {
            val spelling = LinkedHashMap<String, String>()
            val counts = HashMap<String, Int>()
            for (tag in entries.flatMap { it.tagList }) {
                val key = tag.lowercase()
                spelling.putIfAbsent(key, tag)
                counts[key] = (counts[key] ?: 0) + 1
            }
            return spelling.entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value })
                .associate { it.value to counts.getValue(it.key) }
        }

        /** Renames (or with an empty [newName] removes) [tag] in all [entries]; entries without tags are dropped. */
        fun renameTag(entries: List<RepoTags>, tag: String, newName: String): MutableList<RepoTags> =
            entries.mapNotNull { entry ->
                val renamed = entry.tagList.flatMap { if (it.equals(tag, ignoreCase = true)) Tags.parse(newName) else listOf(it) }
                Tags.parse(renamed.joinToString(" ")).takeIf { it.isNotEmpty() }?.let { RepoTags(entry.path, it.joinToString(" ")) }
            }.toMutableList()
    }
}

/**
 * Tags of one repository. Mutable with defaults so that the IntelliJ XML serializer can store it;
 * the tags are kept as one space-separated string.
 */
data class RepoTags(
    var path: String = "",
    var tags: String = "",
) {
    @get:Transient
    val tagList: List<String> get() = Tags.parse(tags)
}
