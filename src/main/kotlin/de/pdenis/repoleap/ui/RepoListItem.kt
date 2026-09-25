package de.pdenis.repoleap.ui

import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.RepoMatcher

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
) {
    companion object {
        /**
         * Groups that are never broken, whatever the search score says:
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
    }
}
