package de.pdenis.repoleap.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ExecutableFinderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sep = File.pathSeparator

    private fun dir(name: String): Path = Files.createDirectories(tmp.root.toPath().resolve(name))

    private fun file(dir: Path, name: String, executable: Boolean = true): Path {
        val file = Files.writeString(dir.resolve(name), "#!/bin/sh\n")
        file.toFile().setExecutable(executable)
        return file
    }

    private fun onWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun find(name: String, path: String?, windows: Boolean = false, pathExt: String? = null) =
        ExecutableFinder.find(name, pathValue = path, windows = windows, pathExt = pathExt)

    @Test
    fun findsExecutableInPath() {
        val bin = dir("bin")
        val git = file(bin, "git")
        assertEquals(git, find("git", "${dir("other")}$sep$bin"))
    }

    @Test
    fun resultIsAbsolute() {
        val git = file(dir("bin"), "git")
        assertTrue(find("git", git.parent.toString())!!.isAbsolute)
    }

    @Test
    fun firstMatchWins() {
        val first = file(dir("first"), "git")
        file(dir("second"), "git")
        assertEquals(first, find("git", "${first.parent}$sep${tmp.root.toPath().resolve("second")}"))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        file(dir("bin"), "svn")
        assertNull(find("git", dir("bin").toString()))
    }

    @Test
    fun skipsFilesThatAreNotExecutable() {
        assumeFalse("no executable bit on Windows", onWindows())
        val plain = dir("plain")
        file(plain, "git", executable = false)
        val bin = dir("bin")
        val git = file(bin, "git")
        assertEquals(git, find("git", "$plain$sep$bin"))
        assertNull(find("git", plain.toString()))
    }

    @Test
    fun skipsDirectoriesWithTheSameName() {
        val a = dir("a")
        Files.createDirectories(a.resolve("git"))
        val b = dir("b")
        val git = file(b, "git")
        assertEquals(git, find("git", "$a$sep$b"))
    }

    @Test
    fun ignoresRelativeMissingAndEmptyEntries() {
        val bin = dir("bin")
        val git = file(bin, "git")
        val missing = tmp.root.toPath().resolve("does-not-exist")
        assertEquals(git, find("git", "relative/dir$sep$missing$sep$sep $sep$bin"))
    }

    @Test
    fun acceptsQuotedEntries() {
        val bin = dir("bin")
        val git = file(bin, "git")
        assertEquals(git, find("git", "\"$bin\""))
    }

    @Test
    fun noPathMeansNoResult() {
        assertNull(find("git", null))
        assertNull(find("git", ""))
        assertNull(find("git", "   "))
    }

    @Test
    fun rejectsNamesThatAreNotPlainFileNames() {
        val bin = dir("bin")
        file(bin, "git")
        assertNull(find("", bin.toString()))
        assertNull(find(" ", bin.toString()))
        assertNull(find("sub/git", bin.toString()))
        assertNull(find("sub\\git", bin.toString()))
        assertNull(find("../bin/git", bin.toString()))
    }

    @Test
    fun noExtensionsOutsideWindows() {
        val bin = dir("bin")
        file(bin, "git.exe")
        assertNull(find("git", bin.toString(), windows = false, pathExt = ".EXE"))
    }

    @Test
    fun windowsTriesTheExtensionsOfPathExt() {
        val bin = dir("bin")
        val git = file(bin, "git.exe")
        assertEquals(git, find("git", bin.toString(), windows = true, pathExt = ".COM;.EXE;.BAT"))
    }

    @Test
    fun windowsUsesDefaultExtensionsWithoutPathExt() {
        val bin = dir("bin")
        val git = file(bin, "git.exe")
        assertEquals(git, find("git", bin.toString(), windows = true, pathExt = null))
    }

    @Test
    fun windowsKeepsNamesThatAlreadyHaveAnExtension() {
        val bin = dir("bin")
        val git = file(bin, "git.exe")
        assertEquals(git, find("git.exe", bin.toString(), windows = true, pathExt = ".exe;.cmd"))
    }

    @Test
    fun windowsPrefersEarlierExtension() {
        val bin = dir("bin")
        file(bin, "tool.bat")
        val exe = file(bin, "tool.exe")
        assertEquals(exe, find("tool", bin.toString(), windows = true, pathExt = ".exe;.bat"))
    }
}
