package de.pdenis.repoleap.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GitCloneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val git: String? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "git") }.firstOrNull { it.canExecute() }?.absolutePath
    }

    private fun run(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf(git!!, "-C", dir.toString()) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_AUTHOR_NAME"] = "Test"
                environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
                environment()["GIT_COMMITTER_NAME"] = "Test"
                environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    /** A repository with one commit to clone from. */
    private fun origin(): Path {
        val origin = tmp.newFolder("origin").toPath()
        run(origin, "init", "-q", "--initial-branch=main")
        Files.writeString(origin.resolve("README.md"), "hello")
        run(origin, "add", "README.md")
        run(origin, "commit", "-q", "-m", "first")
        return origin
    }

    @Test
    fun `clones into a new folder`() {
        assumeTrue(git != null)
        val target = tmp.root.toPath().resolve("clones/my-copy")
        Files.createDirectories(target.parent)
        val lines = mutableListOf<String>()
        assertEquals(GitClone.Result.Success, GitClone(git!!).clone(origin().toString(), target, onProgress = { lines += it }))
        assertEquals("hello", Files.readString(target.resolve("README.md")))
        assertTrue(lines.isNotEmpty()) // "Cloning into ..."
    }

    @Test
    fun `an existing folder is never touched`() {
        assumeTrue(git != null)
        val target = tmp.newFolder("exists").toPath()
        Files.writeString(target.resolve("keep.txt"), "mine")
        val result = GitClone(git!!).clone(origin().toString(), target)
        assertTrue(result is GitClone.Result.Failed)
        assertEquals("mine", Files.readString(target.resolve("keep.txt")))
    }

    @Test
    fun `a failed clone leaves nothing behind and explains why`() {
        assumeTrue(git != null)
        val target = tmp.root.toPath().resolve("broken")
        val result = GitClone(git!!).clone(tmp.root.toPath().resolve("does-not-exist").toString(), target)
        assertTrue(result.toString(), result is GitClone.Result.Failed && result.message.isNotBlank())
        assertFalse(Files.exists(target))
    }

    @Test
    fun `canceling stops git and removes the folder`() {
        assumeTrue(git != null)
        val target = tmp.root.toPath().resolve("canceled")
        assertEquals(GitClone.Result.Canceled, GitClone(git!!).clone(origin().toString(), target, isCanceled = { true }))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `progress lines give the overall fraction`() {
        assertEquals(0.4, GitClone.fraction("Receiving objects:  50% (5/10), 1.00 KiB | 1 MiB/s")!!, 0.001)
        assertEquals(0.85, GitClone.fraction("Resolving deltas:  50% (1/2)")!!, 0.001)
        assertEquals(1.0, GitClone.fraction("Updating files: 100% (3/3), done.")!!, 0.001)
        assertNull("not counted", GitClone.fraction("remote: Counting objects: 100% (3/3), done."))
        assertNull(GitClone.fraction("Cloning into 'x'..."))
    }

    @Test
    fun `login problems are recognized`() {
        assertTrue(GitClone.Result.Failed("fatal: could not read Username for 'https://github.com': terminal prompts disabled").isAuthenticationProblem)
        assertTrue(GitClone.Result.Failed("git@github.com: Permission denied (publickey).").isAuthenticationProblem)
        assertFalse(GitClone.Result.Failed("fatal: repository 'x' does not exist").isAuthenticationProblem)
    }
}
