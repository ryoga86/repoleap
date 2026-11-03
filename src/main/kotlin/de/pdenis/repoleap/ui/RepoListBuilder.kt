package de.pdenis.repoleap.ui

import de.pdenis.repoleap.git.RemoteUrls
import de.pdenis.repoleap.open.RepoCloner
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.search.SearchQuery
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.nio.file.Path

/**
 * Builds the rows of the popup for a search text:
 * - a remote URL: the repositories cloned from it, then where it can be cloned to ([RepoCloner.targets])
 * - otherwise: the repositories matching the text and the `#tag` filters, in the configured order
 */
internal class RepoListBuilder(
    private val settings: RepoLeapSettings,
    private val windowStateOf: (path: String) -> WindowState,
) {

    fun build(text: String, repositories: List<RepoEntry>): List<RepoListItem> {
        val listed = repositories.filterNot { settings.isHidden(it.pathString) }
        return if (RemoteUrls.looksLikeRemoteUrl(text)) forRemoteUrl(text.trim(), listed) else forQuery(SearchQuery.parse(text), listed)
    }

    private fun forQuery(query: SearchQuery, repositories: List<RepoEntry>): List<RepoListItem> {
        val recentRank = recentRanks()
        return repositories.mapNotNull { repo ->
            val tags = settings.tagsOf(repo.pathString)
            val tagMatches = query.matchTags(tags) ?: return@mapNotNull null
            val match = RepoMatcher.match(query.textTokens, repo.name, repo.displayPath) ?: return@mapNotNull null
            item(repo, match, recentRank, tags, tagMatches)
        }.sortedWith(RepoListItem.order(settings.state.sortMode, searching = query.textTokens.isNotEmpty()))
    }

    private fun forRemoteUrl(url: String, repositories: List<RepoEntry>): List<RepoListItem> {
        val key = RemoteUrls.identity(url)
        val recentRank = recentRanks()
        val cloned = repositories
            .filter { key != null && key in it.remoteKeys }
            .map { repo -> item(repo, NO_MATCH, recentRank, settings.tagsOf(repo.pathString), emptyList()).copy(sameRemote = true) }
            .sortedWith(RepoListItem.ORDER)
        val clones = RepoCloner.targets(url, settings.state.roots).map { target ->
            RepoListItem(RepoEntry(target.name, target.target ?: Path.of(""), target.displayTarget), NO_MATCH, 0, clone = target)
        }
        return cloned + clones
    }

    private fun item(repo: RepoEntry, match: RepoMatcher.Result, recentRank: Map<String, Int>, tags: List<String>, tagMatches: List<Int>): RepoListItem {
        val rank = recentRank[repo.pathString]
        val recentBonus = rank?.let { (RepoLeapSettings.MAX_RECENT - it) * RECENT_BONUS } ?: 0
        return RepoListItem(
            repo, match, match.score + recentBonus,
            pinned = settings.isPinned(repo.pathString),
            windowState = windowStateOf(repo.pathString),
            recentRank = rank,
            tags = tags,
            tagMatches = tagMatches,
        )
    }

    private fun recentRanks(): Map<String, Int> = settings.recentRepos.withIndex().associate { (rank, path) -> path to rank }

    private companion object {
        const val RECENT_BONUS = 5
        val NO_MATCH = RepoMatcher.Result(0, emptyList(), emptyList())
    }
}
