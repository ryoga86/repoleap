package de.pdenis.repoleap.ui

import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.settings.SortMode

/** Whether a repository is open in an IDE window. Declaration order = sort order. */
enum class WindowState { CURRENT, OTHER, CLOSED }

/** One row in the popup. Pure Kotlin. */
data class RepoListItem(
    val repo: RepoEntry,
    val match: RepoMatcher.Result,
    /** Search score incl. recency bonus - only used inside the groups of [ORDER]. */
    val score: Int,
    val pinned: Boolean = false,
    val windowState: WindowState = WindowState.CLOSED,
    /** 0 = opened most recently via RepoLeap, `null` = not among the recently opened ones. */
    val recentRank: Int? = null,
) {
    companion object {
        /**
         * [SortMode.SMART]: groups that are never broken, whatever the search score says:
         * 1. pinned (inside: current window, other window, rest)
         * 2. current window
         * 3. other window
         * 4. everything else
         *
         * Inside a group: score (search quality + recently opened), then name, then path.
         */
        val ORDER: Comparator<RepoListItem> =
            compareByDescending<RepoListItem> { it.pinned }
                .thenBy { it.windowState.ordinal }
                .thenByDescending { it.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.repo.name }
                .thenBy { it.repo.displayPath }

        private val BY_NAME: Comparator<RepoListItem> =
            compareBy<RepoListItem, String>(String.CASE_INSENSITIVE_ORDER) { it.repo.name }.thenBy { it.repo.displayPath }

        private val BY_PATH: Comparator<RepoListItem> =
            compareBy<RepoListItem, String>(String.CASE_INSENSITIVE_ORDER) { it.repo.displayPath }

        private val BY_RECENT: Comparator<RepoListItem> =
            compareBy<RepoListItem> { it.recentRank ?: Int.MAX_VALUE }.then(BY_NAME)

        /**
         * The other modes only keep pinned repositories on top. While searching, the search quality comes first -
         * otherwise the best match could end up anywhere in a long list.
         */
        fun order(mode: SortMode, searching: Boolean): Comparator<RepoListItem> {
            val byMode = when (mode) {
                SortMode.SMART -> return ORDER
                SortMode.NAME -> BY_NAME
                SortMode.RECENT -> BY_RECENT
                SortMode.PATH -> BY_PATH
            }
            val pinnedFirst = compareByDescending<RepoListItem> { it.pinned }
            return if (searching) pinnedFirst.thenByDescending { it.match.score }.then(byMode) else pinnedFirst.then(byMode)
        }
    }
}
