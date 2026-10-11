package de.pdenis.repoleap.git

import java.nio.file.Path

/** Branches that can be switched to: local ones, and remote ones that don't have a local branch of the same name yet. */
data class BranchList(
    val current: String?,
    val local: List<String>,
    /** `remote/name`, e.g. `origin/feature-x` - switching creates a local tracking branch `feature-x`. */
    val remoteOnly: List<String>,
)

/** Lists and switches branches with the git executable. Blocking - call it from a background thread. */
class GitBranches(gitExecutable: String, timeoutMillis: Long = SWITCH_TIMEOUT_MILLIS) {

    private val git = GitProcess(gitExecutable, timeoutMillis)

    /** @return the branches, or the error message of git */
    fun list(repo: Path): Result<BranchList> {
        val refs = git.run(repo, "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes")
        if (refs.exitCode != 0) return Result.failure(GitException(refs.errorText()))
        val head = git.run(repo, "symbolic-ref", "--quiet", "--short", "HEAD")
        return Result.success(parse(refs.stdout, head.stdout.trim().takeIf { head.exitCode == 0 && it.isNotEmpty() }))
    }

    /**
     * `git switch` - refuses (and changes nothing) if local changes would be overwritten; nothing is forced or stashed.
     * @return `null` on success, otherwise the error message of git
     */
    fun switch(repo: Path, branch: String, remoteOnly: Boolean): String? {
        val result = if (remoteOnly) git.run(repo, "switch", "--track", branch) else git.run(repo, "switch", branch)
        return if (result.exitCode == 0) null else gitMessage(result)
    }

    private fun gitMessage(result: GitProcess.Result): String {
        // git explains a refused switch over several lines ("error: ... would be overwritten:", the files, "Aborting")
        val lines = result.stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return if (lines.isEmpty()) result.errorText() else lines.take(MAX_MESSAGE_LINES).joinToString("\n")
    }

    class GitException(message: String) : Exception(message)

    companion object {
        const val SWITCH_TIMEOUT_MILLIS = 60_000L
        private const val MAX_MESSAGE_LINES = 8

        internal fun parse(refs: String, current: String?): BranchList {
            val all = refs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val local = all.filter { it.startsWith("refs/heads/") }.map { it.removePrefix("refs/heads/") }
            val localSet = local.toSet()
            val remoteOnly = all.asSequence()
                .filter { it.startsWith("refs/remotes/") && !it.endsWith("/HEAD") }
                .map { it.removePrefix("refs/remotes/") }
                .filter { it.contains('/') && it.substringAfter('/') !in localSet }
                .toList()
            return BranchList(current, local.sortedWith(String.CASE_INSENSITIVE_ORDER), remoteOnly.sortedWith(String.CASE_INSENSITIVE_ORDER))
        }
    }
}
