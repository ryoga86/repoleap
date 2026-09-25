package de.pdenis.repoleap.git

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.nio.file.Path

/** Entry point for git data inside the IDE. */
object GitSupport {

    /** Uses the `git` found on the PATH of the IDE (the IDE loads the shell environment on macOS). */
    fun reader(): GitRepoReader = GitRepoReader(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("git")?.absolutePath)

    /**
     * The remote to open in the browser: `origin` if it has a usable URL, otherwise the first one that has.
     * Read from `.git/config` without running git (fast enough for the EDT).
     */
    fun preferredRemote(repo: Path): Remote? {
        val remotes = GitRepoReader(null).readFromFiles(repo).remotes.filter { RemoteUrls.parse(it.url) != null }
        return remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull()
    }
}
