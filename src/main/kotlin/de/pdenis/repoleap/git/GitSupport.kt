package de.pdenis.repoleap.git

import java.nio.file.Path

/** Entry point for git data inside the IDE. */
object GitSupport {

    /** Uses the `git` found on the PATH of the IDE (the IDE loads the shell environment on macOS). */
    fun reader(): GitRepoReader = GitRepoReader(ExecutableFinder.find("git")?.toString())

    /** `null` if git was not found. */
    fun branches(): GitBranches? = ExecutableFinder.find("git")?.let { GitBranches(it.toString()) }

    /**
     * The remote to open in the browser: `origin` if it has a usable URL, otherwise the first one that has.
     * Read from `.git/config` without running git (fast enough for the EDT).
     */
    fun preferredRemote(repo: Path): Remote? {
        val remotes = GitRepoReader(null).readFromFiles(repo).remotes.filter { RemoteUrls.parse(it.url) != null }
        return remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull()
    }
}
