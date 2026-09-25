package de.pdenis.repoleap.scan

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RepoScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: Path get() = tmp.root.toPath()

    private fun repo(relative: String, gitAsFile: Boolean = false): Path {
        val dir = Files.createDirectories(root.resolve(relative))
        if (gitAsFile) Files.writeString(dir.resolve(".git"), "gitdir: /somewhere/else")
        else Files.createDirectories(dir.resolve(".git"))
        return dir
    }

    private fun dir(relative: String): Path = Files.createDirectories(root.resolve(relative))

    private fun scan(
        roots: List<String> = listOf(root.toString()),
        maxDepth: Int = 3,
        excluded: Set<String> = setOf("node_modules"),
        skipHidden: Boolean = true,
        home: String = "/nonexistent-home",
    ): List<RepoEntry> = RepoScanner(ScanOptions(roots, maxDepth, excluded, skipHidden), homeDir = home).scan()

    private fun names(entries: List<RepoEntry>) = entries.map { it.name }

    @Test
    fun `finds repositories on several levels, sorted by name`() {
        repo("zeta")
        repo("org/beta")
        repo("org/team/Alpha")
        dir("empty/folder")
        assertEquals(listOf("Alpha", "beta", "zeta"), names(scan()))
    }

    @Test
    fun `respects the maximum depth`() {
        repo("a")
        repo("l1/b")
        repo("l1/l2/c")
        repo("l1/l2/l3/d")
        assertEquals(listOf("a", "b"), names(scan(maxDepth = 2)))
        assertEquals(listOf("a", "b", "c", "d"), names(scan(maxDepth = 4)))
    }

    @Test
    fun `does not descend into repositories`() {
        repo("outer")
        repo("outer/submodule")
        assertEquals(listOf("outer"), names(scan()))
    }

    @Test
    fun `a git file (worktree or submodule) marks a repository`() {
        repo("worktree", gitAsFile = true)
        assertEquals(listOf("worktree"), names(scan()))
    }

    @Test
    fun `excluded folders are not searched but are found when they are a repository themselves`() {
        repo("node_modules/some-dep")
        repo("build")
        assertEquals(listOf("build"), names(scan(excluded = setOf("node_modules", "build"))))
    }

    @Test
    fun `hidden folders are skipped unless configured otherwise`() {
        repo(".hidden/repo")
        repo("visible")
        assertEquals(listOf("visible"), names(scan()))
        assertEquals(listOf("repo", "visible"), names(scan(skipHidden = false)))
    }

    @Test
    fun `the root itself can be a repository`() {
        val single = repo("single")
        assertEquals(listOf("single"), names(scan(roots = listOf(single.toString()))))
    }

    @Test
    fun `overlapping roots and missing roots produce no duplicates or errors`() {
        repo("org/one")
        repo("org/two")
        val entries = scan(roots = listOf(root.toString(), root.resolve("org").toString(), "/does/not/exist", ""))
        assertEquals(listOf("one", "two"), names(entries))
    }

    @Test
    fun `symlinked repositories are listed once and symlink cycles terminate`() {
        val target = repo("real/project")
        Files.createSymbolicLink(root.resolve("link-to-project"), target)
        Files.createSymbolicLink(root.resolve("real/loop"), root.resolve("real"))
        assertEquals(1, scan(maxDepth = 6).size)
    }

    @Test
    fun `home directory is expanded and abbreviated`() {
        repo("code/my-repo")
        val entries = scan(roots = listOf("~/code"), home = root.toString())
        assertEquals(1, entries.size)
        assertEquals("~/code/my-repo".replace('/', java.io.File.separatorChar), entries[0].displayPath)
    }
}
