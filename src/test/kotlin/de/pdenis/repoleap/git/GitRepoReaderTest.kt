package de.pdenis.repoleap.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

class GitRepoReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val git: String? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "git") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf(git!!, "-C", dir.toString()) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_AUTHOR_NAME"] = "Test"
                environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
                environment()["GIT_COMMITTER_NAME"] = "Test"
                environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["HOME"] = tmp.root.absolutePath // no global config of the machine
            }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue("git ${args.joinToString(" ")} timed out", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.exitValue())
        return output
    }

    private fun commit(dir: Path, file: String, content: String, message: String) {
        Files.writeString(dir.resolve(file), content)
        git(dir, "add", file)
        git(dir, "commit", "-q", "-m", message)
    }

    /** origin (bare) <- work (clone) with: 1 unpushed commit on main, 1 on a local-only branch, 1 stash, 1 change, 1 untracked file */
    private fun createWorkingCopy(): Path {
        val origin = tmp.newFolder("origin.git").toPath()
        git(origin, "init", "-q", "--bare", "--initial-branch=main")
        val work = tmp.root.toPath().resolve("work")
        git(tmp.root.toPath(), "clone", "-q", origin.toString(), work.toString())
        git(work, "checkout", "-q", "-B", "main")
        commit(work, "a.txt", "a", "first")
        git(work, "push", "-q", "-u", "origin", "main")
        commit(work, "b.txt", "b", "second, not pushed")
        git(work, "checkout", "-q", "-b", "feature")
        commit(work, "c.txt", "c", "feature work")
        git(work, "checkout", "-q", "main")
        Files.writeString(work.resolve("a.txt"), "stashed change")
        git(work, "stash", "-q")
        Files.writeString(work.resolve("a.txt"), "uncommitted change")
        Files.writeString(work.resolve("new.txt"), "untracked")
        return work
    }

    @Test
    fun `reads branch, upstream, last commit, remotes and working tree state`() {
        assumeTrue("git is not installed", git != null)
        val work = createWorkingCopy()

        val info = GitRepoReader(git).read(work)

        assertTrue(info.gitAvailable)
        assertNull(info.error)
        assertEquals("main", info.branch)
        assertEquals("origin/main", info.upstream)
        assertEquals(1, info.ahead)
        assertEquals(0, info.behind)
        assertEquals("second, not pushed", info.lastCommit?.subject)
        assertEquals("Test", info.lastCommit?.author)
        assertNotNull(info.lastCommit?.date)
        assertEquals(listOf("origin"), info.remotes.map { it.name })
        assertEquals(1, info.changedFiles)
        assertEquals(1, info.untrackedFiles)
        assertEquals(0, info.conflictedFiles)
        assertEquals(2, info.localBranches)
        assertEquals(2, info.unpushedCommits)
        assertEquals(1, info.stashes)
        assertFalse(info.isClean)
        assertEquals(
            listOf(
                GitRepoInfo.Risk.UncommittedChanges(1),
                GitRepoInfo.Risk.UntrackedFiles(1),
                GitRepoInfo.Risk.UnpushedCommits(2),
                GitRepoInfo.Risk.Stashes(1),
            ),
            info.risks,
        )
    }

    @Test
    fun `clean and fully pushed repository has no risks`() {
        assumeTrue("git is not installed", git != null)
        val origin = tmp.newFolder("clean-origin.git").toPath()
        git(origin, "init", "-q", "--bare", "--initial-branch=main")
        val work = tmp.root.toPath().resolve("clean")
        git(tmp.root.toPath(), "clone", "-q", origin.toString(), work.toString())
        git(work, "checkout", "-q", "-B", "main")
        commit(work, "a.txt", "a", "first")
        git(work, "push", "-q", "-u", "origin", "main")

        val info = GitRepoReader(git).read(work)
        assertTrue(info.isClean)
        assertEquals(0, info.unpushedCommits)
        assertEquals(emptyList<GitRepoInfo.Risk>(), info.risks)
    }

    @Test
    fun `repository without commits and remote`() {
        assumeTrue("git is not installed", git != null)
        val repo = tmp.newFolder("empty").toPath()
        git(repo, "init", "-q", "--initial-branch=trunk")

        val info = GitRepoReader(git).read(repo)
        assertEquals("trunk", info.branch)
        assertNull(info.headCommit)
        assertNull(info.lastCommit)
        assertTrue(GitRepoInfo.Risk.NoRemote in info.risks)
    }

    @Test
    fun `without git executable the git files are read directly`() {
        val repo = tmp.newFolder("plain").toPath()
        val gitDir = Files.createDirectories(repo.resolve(".git"))
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/develop\n")
        Files.writeString(
            gitDir.resolve("config"),
            """
            [core]
                bare = false
            [remote "origin"]
                url = git@github.com:ryoga86/repoleap.git
                fetch = +refs/heads/*:refs/remotes/origin/*
            [branch "develop"]
                remote = origin
            [remote "fork"]
                url = https://github.com/someone/repoleap.git
            """.trimIndent(),
        )

        val info = GitRepoReader(null).read(repo)
        assertFalse(info.gitAvailable)
        assertEquals("develop", info.branch)
        assertEquals(listOf("origin", "fork"), info.remotes.map { it.name })
        assertEquals("git@github.com:ryoga86/repoleap.git", info.remotes.first().url)
    }

    @Test
    fun `worktree with a git file is followed`() {
        val repo = tmp.newFolder("worktree").toPath()
        val realGitDir = Files.createDirectories(tmp.root.toPath().resolve("main/.git/worktrees/wt"))
        Files.writeString(repo.resolve(".git"), "gitdir: ${realGitDir}\n")
        Files.writeString(realGitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n")
        Files.writeString(realGitDir.resolve("commondir"), "../..\n")
        Files.writeString(tmp.root.toPath().resolve("main/.git/config"), "[remote \"origin\"]\n\turl = https://example.com/x.git\n")

        val info = GitRepoReader(null).readFromFiles(repo)
        assertNull(info.branch)
        assertEquals("0123456789abcdef0123456789abcdef01234567", info.headCommit)
        assertEquals("https://example.com/x.git", info.remotes.single().url)
    }

    @Test
    fun `status output with detached head and conflicts is parsed`() {
        val info = GitRepoReader(null).parseStatus(
            """
            # branch.oid 1234567890abcdef1234567890abcdef12345678
            # branch.head (detached)
            u UU N... 100644 100644 100644 100644 abc abc abc file.txt
            2 R. N... 100644 100644 100644 abc abc R100 new.txt	old.txt
            1 .M N... 100644 100644 100644 abc abc other.txt
            ? untracked.txt
            """.trimIndent(),
        )
        assertNull(info.branch)
        assertEquals("1234567890abcdef1234567890abcdef12345678", info.headCommit)
        assertEquals(2, info.changedFiles)
        assertEquals(1, info.conflictedFiles)
        assertEquals(1, info.untrackedFiles)
    }
}
