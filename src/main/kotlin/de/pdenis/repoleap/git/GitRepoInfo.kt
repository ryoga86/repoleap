package de.pdenis.repoleap.git

import java.time.OffsetDateTime

/** Git data shown in the info dialog and used for the safety check before moving a repository to the Trash. */
data class GitRepoInfo(
    /** `false` if no git executable was found - then only [branch], [headCommit] and [remotes] are filled (from .git files). */
    val gitAvailable: Boolean,
    /** Current branch, `null` if HEAD is detached. */
    val branch: String?,
    /** Full hash of HEAD, `null` if the repository has no commits yet. */
    val headCommit: String?,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val lastCommit: CommitInfo? = null,
    val remotes: List<Remote> = emptyList(),
    val changedFiles: Int? = null,
    val untrackedFiles: Int? = null,
    val conflictedFiles: Int? = null,
    val localBranches: Int? = null,
    /** Commits reachable from local branches but from no remote-tracking branch, i.e. not pushed anywhere. */
    val unpushedCommits: Int? = null,
    val stashes: Int? = null,
    /** Set if git could not be run or failed; the other values may be incomplete then. */
    val error: String? = null,
) {
    val isClean: Boolean
        get() = changedFiles == 0 && untrackedFiles == 0 && (conflictedFiles ?: 0) == 0

    /** Things that would be lost when the folder is deleted. Empty if nothing is at risk. */
    val risks: List<Risk>
        get() = buildList {
            changedFiles?.takeIf { it > 0 }?.let { add(Risk.UncommittedChanges(it)) }
            conflictedFiles?.takeIf { it > 0 }?.let { add(Risk.Conflicts(it)) }
            untrackedFiles?.takeIf { it > 0 }?.let { add(Risk.UntrackedFiles(it)) }
            unpushedCommits?.takeIf { it > 0 }?.let { add(Risk.UnpushedCommits(it)) }
            stashes?.takeIf { it > 0 }?.let { add(Risk.Stashes(it)) }
            if (remotes.isEmpty()) add(Risk.NoRemote)
        }

    sealed interface Risk {
        data class UncommittedChanges(val count: Int) : Risk
        data class Conflicts(val count: Int) : Risk
        data class UntrackedFiles(val count: Int) : Risk
        data class UnpushedCommits(val count: Int) : Risk
        data class Stashes(val count: Int) : Risk
        data object NoRemote : Risk
    }
}

data class CommitInfo(val hash: String, val subject: String, val author: String, val date: OffsetDateTime?) {
    val shortHash: String get() = hash.take(8)
}

/** [browserUrl] is resolved separately (it may need a request to the server), see `RemoteBrowserUrls`. */
data class Remote(val name: String, val url: String, val browserUrl: String? = null)
