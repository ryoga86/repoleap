package de.pdenis.repoleap.git

import java.io.IOException
import java.io.InputStreamReader
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `git clone` with progress and cancel. Never prompts (like [GitProcess]): authentication has to work without
 * questions - ssh agent / keys without passphrase, or a credential helper for https. Blocking.
 */
class GitClone(private val gitExecutable: String) {

    sealed interface Result {
        data object Success : Result
        data object Canceled : Result
        data class Failed(val message: String) : Result {
            /** git's message looks like the server wanted a login that could not be given. */
            val isAuthenticationProblem: Boolean get() = AUTH_HINTS.any { message.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Clones [url] into [target], which must not exist yet. [onProgress] gets git's progress lines
     * (`Receiving objects:  45% (450/1000)`); [isCanceled] is asked regularly. A canceled or failed clone leaves
     * nothing behind.
     */
    fun clone(url: String, target: Path, onProgress: (String) -> Unit = {}, isCanceled: () -> Boolean = { false }): Result {
        if (Files.exists(target)) return Result.Failed("destination path '$target' already exists")
        val process = try {
            ProcessBuilder(gitExecutable, "clone", "--progress", "--", url, target.toString())
                .apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["LC_ALL"] = "C"
                    target.parent?.let { directory(it.toFile()) }
                }
                .start()
        } catch (e: IOException) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        process.outputStream.close()
        Thread({ process.inputStream.use { it.readAllBytes() } }, "RepoLeap git clone output").apply { isDaemon = true }.start()

        val messages = ArrayDeque<String>()
        val reader = Thread({
            InputStreamReader(process.errorStream, StandardCharsets.UTF_8).use { input ->
                val line = StringBuilder()
                fun flush() {
                    val text = line.toString().trim()
                    line.setLength(0)
                    if (text.isEmpty()) return
                    onProgress(text)
                    if (!isProgress(text)) synchronized(messages) {
                        messages.addLast(text)
                        while (messages.size > MAX_MESSAGE_LINES) messages.removeFirst()
                    }
                }
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\r'.code || c == '\n'.code) flush() else line.append(c.toChar())
                }
                flush()
            }
        }, "RepoLeap git clone progress").apply { isDaemon = true }
        reader.start()

        try {
            while (true) {
                if (isCanceled()) {
                    process.destroy()
                    if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(STOP_SECONDS, TimeUnit.SECONDS)
                    deleteRecursively(target)
                    return Result.Canceled
                }
                if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            deleteRecursively(target)
            return Result.Canceled
        }
        reader.join(STOP_SECONDS * 1000)
        if (process.exitValue() == 0) return Result.Success
        deleteRecursively(target)
        val message = synchronized(messages) { messages.joinToString("\n") }
        return Result.Failed(message.ifBlank { "git exited with code ${process.exitValue()}" })
    }

    companion object {
        private const val POLL_MILLIS = 100L
        private const val STOP_SECONDS = 5L
        private const val MAX_MESSAGE_LINES = 8

        private val AUTH_HINTS = listOf(
            "Permission denied", "Authentication failed", "could not read Username", "could not read Password",
            "terminal prompts disabled", "Host key verification failed", "access denied", "returned error: 401",
            "returned error: 403",
        )

        private val PROGRESS = Regex("""^(?:remote: )?[A-Za-z ]+:\s+(\d{1,3})%""")
        private val PHASES = listOf("Receiving objects" to (0.0 to 0.8), "Resolving deltas" to (0.8 to 0.9), "Updating files" to (0.9 to 1.0))

        private fun isProgress(line: String) = PROGRESS.containsMatchIn(line)

        /** Overall fraction (0..1) for a progress line of git, `null` for other lines and phases that are not counted. */
        fun fraction(line: String): Double? {
            val percent = PROGRESS.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val (from, to) = PHASES.firstOrNull { line.contains(it.first) }?.second ?: return null
            return from + (to - from) * percent.coerceIn(0, 100) / 100.0
        }

        internal fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            try {
                Files.walk(path).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            } catch (_: IOException) {
                // a leftover folder is reported by the next clone ("already exists")
            } catch (_: UncheckedIOException) {
            }
        }
    }
}
