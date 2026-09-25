package de.pdenis.repoleap.open

import de.pdenis.repoleap.open.RepoFileOperations.NameProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RepoFileOperationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(name: String): Path = Files.createDirectories(tmp.root.toPath().resolve(name).resolve(".git")).parent

    @Test
    fun `names are validated`() {
        val repo = repo("my-repo")
        repo("other")
        assertEquals(NameProblem.EMPTY, RepoFileOperations.validateNewName(repo, "   "))
        assertEquals(NameProblem.UNCHANGED, RepoFileOperations.validateNewName(repo, "my-repo"))
        assertEquals(NameProblem.INVALID, RepoFileOperations.validateNewName(repo, "a/b"))
        assertEquals(NameProblem.INVALID, RepoFileOperations.validateNewName(repo, ".."))
        assertEquals(NameProblem.INVALID, RepoFileOperations.validateNewName(repo, "a:b"))
        assertEquals(NameProblem.ALREADY_EXISTS, RepoFileOperations.validateNewName(repo, "other"))
        assertNull(RepoFileOperations.validateNewName(repo, "renamed"))
    }

    @Test
    fun `rename moves the folder with its content`() {
        val repo = repo("my-repo")
        Files.writeString(repo.resolve("README.md"), "hello")

        val renamed = RepoFileOperations.rename(repo, " renamed ")

        assertEquals(tmp.root.toPath().resolve("renamed"), renamed)
        assertFalse(Files.exists(repo))
        assertEquals("hello", Files.readString(renamed.resolve("README.md")))
        assertTrue(Files.isDirectory(renamed.resolve(".git")))
    }

    @Test(expected = IOException::class)
    fun `rename refuses to overwrite`() {
        val repo = repo("my-repo")
        repo("other")
        RepoFileOperations.rename(repo, "other")
    }

    @Test
    fun `changing only upper and lower case works on case-insensitive file systems`() {
        val repo = repo("myrepo")
        assumeTrue("file system is case-sensitive", Files.exists(tmp.root.toPath().resolve("MYREPO")))

        val renamed = RepoFileOperations.rename(repo, "MyRepo")

        val names = Files.list(tmp.root.toPath()).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("MyRepo"), names)
        assertTrue(Files.isDirectory(renamed.resolve(".git")))
    }
}
