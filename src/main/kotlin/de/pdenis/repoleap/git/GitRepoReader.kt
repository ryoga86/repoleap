package de.pdenis.repoleap.git

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Reads [GitRepoInfo] by running the git executable (read-only commands, no network access).
 * Without an executable, branch/HEAD/remotes are read directly from the `.git` files.
 *
 * Blocking - call it from a background thread. Pure Kotlin/JDK, unit testable.
 */
class GitRepoReader(
    /** Absolute path of the git executable, `null` if none was found. */
    private val gitExecutable: String?,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    fun read(repo: Path): GitRepoInfo {
        val basic = readFromFiles(repo)
        if (gitExecutable == null) return basic.copy(gitAvailable = false)

        val status = run(repo, "--no-optional-locks", "status", "--porcelain=v2", "--branch")
        if (status.exitCode != 0) return basic.copy(gitAvailable = status.exitCode > 0, error = status.errorText())

        var info = parseStatus(status.stdout).copy(remotes = basic.remotes)

        if (info.headCommit != null) {
            run(repo, "log", "-1", "--format=%H%x1f%s%x1f%an%x1f%aI")
                .takeIf { it.exitCode == 0 }
                ?.let { info = info.copy(lastCommit = parseCommit(it.stdout)) }
        }
        run(repo, "remote", "-v").takeIf { it.exitCode == 0 }?.let { result ->
            info = info.copy(remotes = parseRemotes(result.stdout))
        }
        run(repo, "for-each-ref", "--format=%(refname:short)", "refs/heads").takeIf { it.exitCode == 0 }?.let { result ->
            info = info.copy(localBranches = result.stdout.lineSequence().count { it.isNotBlank() })
        }
        run(repo, "rev-list", "--count", "--branches", "--not", "--remotes").takeIf { it.exitCode == 0 }?.let { result ->
            info = info.copy(unpushedCommits = result.stdout.trim().toIntOrNull())
        }
        run(repo, "stash", "list", "--format=%h").takeIf { it.exitCode == 0 }?.let { result ->
            info = info.copy(stashes = result.stdout.lineSequence().count { it.isNotBlank() })
        }
        return info
    }

    // --- git status --porcelain=v2 --branch -------------------------------------------------------------------

    internal fun parseStatus(output: String): GitRepoInfo {
        var branch: String? = null
        var head: String? = null
        var upstream: String? = null
        var ahead: Int? = null
        var behind: Int? = null
        var changed = 0
        var untracked = 0
        var conflicts = 0
        for (line in output.lineSequence()) {
            when {
                line.startsWith("# branch.oid ") -> head = line.removePrefix("# branch.oid ").takeUnless { it == "(initial)" }
                line.startsWith("# branch.head ") -> branch = line.removePrefix("# branch.head ").takeUnless { it == "(detached)" }
                line.startsWith("# branch.upstream ") -> upstream = line.removePrefix("# branch.upstream ")
                line.startsWith("# branch.ab ") -> {
                    val parts = line.removePrefix("# branch.ab ").split(' ')
                    ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull()
                    behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull()
                }
                line.startsWith("1 ") || line.startsWith("2 ") -> changed++
                line.startsWith("u ") -> conflicts++
                line.startsWith("? ") -> untracked++
            }
        }
        return GitRepoInfo(
            gitAvailable = true,
            branch = branch,
            headCommit = head,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            changedFiles = changed,
            untrackedFiles = untracked,
            conflictedFiles = conflicts,
        )
    }

    internal fun parseCommit(output: String): CommitInfo? {
        val parts = output.trim().split('\u001f')
        if (parts.size < 4 || parts[0].isBlank()) return null
        val date = try {
            OffsetDateTime.parse(parts[3].trim())
        } catch (_: DateTimeParseException) {
            null
        }
        return CommitInfo(hash = parts[0], subject = parts[1], author = parts[2], date = date)
    }

    internal fun parseRemotes(output: String): List<Remote> =
        output.lineSequence()
            .mapNotNull { line ->
                val columns = line.split('\t', limit = 2)
                if (columns.size < 2) return@mapNotNull null
                val url = columns[1].substringBeforeLast(" (").trim()
                Remote(columns[0].trim(), url)
            }
            .distinctBy { it.name }
            .toList()

    // --- fallback without git executable ----------------------------------------------------------------------

    /** Branch, HEAD and remotes read from the `.git` files (also used for the remote URL in the popup menu). */
    fun readFromFiles(repo: Path): GitRepoInfo {
        val gitDir = resolveGitDir(repo) ?: return GitRepoInfo(gitAvailable = false, branch = null, headCommit = null)
        val headContent = readText(gitDir.resolve("HEAD"))?.trim()
        val branch = headContent?.takeIf { it.startsWith("ref: ") }?.removePrefix("ref: ")?.removePrefix("refs/heads/")
        val detachedHead = headContent?.takeUnless { it.startsWith("ref: ") }?.takeIf { it.isNotEmpty() }

        val commonDir = readText(gitDir.resolve("commondir"))?.trim()?.let { gitDir.resolve(it).normalize() } ?: gitDir
        val remotes = parseConfigRemotes(readText(commonDir.resolve("config")).orEmpty())
        return GitRepoInfo(gitAvailable = false, branch = branch, headCommit = detachedHead, remotes = remotes)
    }

    private fun resolveGitDir(repo: Path): Path? {
        val dotGit = repo.resolve(".git")
        return when {
            Files.isDirectory(dotGit) -> dotGit
            Files.isRegularFile(dotGit) -> readText(dotGit)
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("gitdir:") }
                ?.removePrefix("gitdir:")
                ?.trim()
                ?.let { repo.resolve(it).normalize() }
            else -> null
        }
    }

    internal fun parseConfigRemotes(config: String): List<Remote> {
        val remotes = ArrayList<Remote>()
        var currentRemote: String? = null
        val section = Regex("""^\s*\[\s*remote\s+"([^"]+)"\s*]\s*$""")
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                currentRemote = section.matchEntire(line)?.groupValues?.get(1)
                continue
            }
            val remote = currentRemote ?: continue
            val key = line.substringBefore('=').trim()
            if (key.equals("url", ignoreCase = true) && remotes.none { it.name == remote }) {
                remotes += Remote(remote, line.substringAfter('=').trim())
            }
        }
        return remotes
    }

    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (_: IOException) {
        null
    }

    // --- process handling -------------------------------------------------------------------------------------

    /** [exitCode] is -1 if git could not be started or timed out; [stderr] holds the reason then. */
    private class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        fun errorText(): String = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "git exited with code $exitCode"
    }

    private fun run(repo: Path, vararg args: String): Result {
        val command = listOf(gitExecutable!!, "-C", repo.toString()) + args
        return try {
            val process = ProcessBuilder(command)
                .apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    environment()["LC_ALL"] = "C"
                }
                .start()
            process.outputStream.close()
            val stdout = readAsync(process.inputStream)
            val stderr = readAsync(process.errorStream)
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Result(-1, "", "git did not respond within ${timeoutMillis / 1000} s")
            }
            Result(process.exitValue(), stdout.get(timeoutMillis, TimeUnit.MILLISECONDS), stderr.get(timeoutMillis, TimeUnit.MILLISECONDS))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(-1, "", "interrupted")
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readAsync(stream: InputStream): FutureTask<String> {
        val task = FutureTask { stream.use { it.readBytes().toString(StandardCharsets.UTF_8) } }
        Thread(task, "RepoLeap git output reader").apply { isDaemon = true }.start()
        return task
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
