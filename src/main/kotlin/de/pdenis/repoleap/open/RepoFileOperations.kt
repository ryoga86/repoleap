package de.pdenis.repoleap.open

import java.awt.Desktop
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Renaming and trashing of repository folders. Pure JDK, unit testable. */
object RepoFileOperations {

    enum class NameProblem { EMPTY, INVALID, UNCHANGED, ALREADY_EXISTS }

    private val FORBIDDEN_CHARACTERS = charArrayOf('/', '\\', ':', '\u0000')

    /** `null` if [newName] can be used as the new folder name of [folder]. */
    fun validateNewName(folder: Path, newName: String): NameProblem? {
        val name = newName.trim()
        if (name.isEmpty()) return NameProblem.EMPTY
        if (name == "." || name == ".." || name.any { it in FORBIDDEN_CHARACTERS }) return NameProblem.INVALID
        if (name == folder.fileName?.toString()) return NameProblem.UNCHANGED
        val target = try {
            folder.resolveSibling(name)
        } catch (_: InvalidPathException) {
            return NameProblem.INVALID
        }
        if (Files.exists(target) && !isSameFile(folder, target)) return NameProblem.ALREADY_EXISTS
        return null
    }

    /**
     * Renames [folder] to [newName] (same parent) and returns the new path.
     * A change of upper/lower case only works on case-insensitive file systems (macOS, Windows) as well.
     */
    @Throws(IOException::class)
    fun rename(folder: Path, newName: String): Path {
        val name = newName.trim()
        validateNewName(folder, name)?.let { throw IOException("Invalid name '$name': $it") }
        val target = folder.resolveSibling(name)
        if (Files.exists(target) && isSameFile(folder, target)) {
            // Case-only rename on a case-insensitive file system: Files.move would treat it as a no-op
            val temp = folder.resolveSibling(".$name.repoleap-rename-${System.nanoTime()}")
            Files.move(folder, temp)
            try {
                Files.move(temp, target)
            } catch (e: IOException) {
                Files.move(temp, folder)
                throw e
            }
            return target
        }
        try {
            return Files.move(folder, target)
        } catch (e: FileAlreadyExistsException) {
            throw IOException("'$name' already exists", e)
        }
    }

    fun isTrashSupported(): Boolean =
        try {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
        } catch (_: Exception) {
            false
        }

    /** Moves [folder] to the system trash. Returns `false` if that is not supported or failed. */
    fun moveToTrash(folder: Path): Boolean =
        isTrashSupported() && try {
            Desktop.getDesktop().moveToTrash(folder.toFile())
        } catch (_: Exception) {
            false
        }

    private fun isSameFile(a: Path, b: Path): Boolean =
        try {
            Files.isSameFile(a, b)
        } catch (_: IOException) {
            false
        }
}
