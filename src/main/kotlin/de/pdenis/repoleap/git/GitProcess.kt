package de.pdenis.repoleap.git

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Runs the git executable in a repository: never prompts, English messages, killed after [timeoutMillis]. Blocking. */
internal class GitProcess(private val gitExecutable: String, private val timeoutMillis: Long) {

    /** [exitCode] is -1 if git could not be started or timed out; [stderr] holds the reason then. */
    class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        fun errorText(): String = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "git exited with code $exitCode"
    }

    fun run(repo: Path, vararg args: String): Result {
        val command = listOf(gitExecutable, "-C", repo.toString()) + args
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
}
