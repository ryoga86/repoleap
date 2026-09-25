package de.pdenis.repoleap.scan

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds Git repositories below a set of root folders.
 *
 * Rules:
 * - A folder containing `.git` (directory, or file for worktrees/submodules) is a repository.
 *   Repositories are not searched any further (nested repositories/submodules are ignored).
 * - Root folders are searched up to [ScanOptions.maxDepth] levels deep (root = level 0).
 * - Hidden folders (name starts with `.`) are skipped completely if [ScanOptions.skipHiddenFolders] is set.
 * - Folders from [ScanOptions.excludedFolderNames] are not searched, but are still listed if they are a repository
 *   themselves (so a repository called `build` is not lost).
 * - Symlinks are followed; every physical folder is visited only once (no cycles, no duplicates).
 *
 * This class is free of IntelliJ APIs and can be unit tested directly.
 */
class RepoScanner(
    private val options: ScanOptions,
    private val homeDir: String = System.getProperty("user.home"),
    /** Called regularly; throw to abort (e.g. a cancelled coroutine). */
    private val checkCanceled: () -> Unit = {},
) {
    private val excludedLowercase = options.excludedFolderNames.map { it.lowercase() }.toSet()

    fun scan(): List<RepoEntry> {
        val result = LinkedHashMap<Path, RepoEntry>()
        val visited = HashSet<Path>()
        for (root in options.roots.mapNotNull { resolveRoot(it) }) {
            if (!Files.isDirectory(root)) continue
            visit(root, 0, visited, result)
        }
        return result.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RepoEntry::name).thenBy { it.pathString })
    }

    private fun visit(dir: Path, depth: Int, visited: MutableSet<Path>, result: MutableMap<Path, RepoEntry>) {
        checkCanceled()
        val realPath = realPathOf(dir)
        if (!visited.add(realPath)) return

        if (isRepository(dir)) {
            result.putIfAbsent(realPath, entryFor(dir))
            return
        }
        if (depth >= options.maxDepth) return
        if (depth > 0 && isExcluded(dir)) return

        for (child in listSubdirectories(dir)) {
            visit(child, depth + 1, visited, result)
        }
    }

    private fun listSubdirectories(dir: Path): List<Path> {
        val children = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val name = child.fileName?.toString() ?: continue
                    if (options.skipHiddenFolders && name.startsWith(".")) continue
                    if (Files.isDirectory(child)) children.add(child)
                }
            }
        } catch (_: IOException) {
            // unreadable folder (permissions, vanished while scanning, ...) -> ignore
        } catch (_: DirectoryIteratorException) {
        } catch (_: SecurityException) {
        }
        return children
    }

    private fun isRepository(dir: Path): Boolean = Files.exists(dir.resolve(GIT_MARKER), LinkOption.NOFOLLOW_LINKS)

    private fun isExcluded(dir: Path): Boolean {
        val name = dir.fileName?.toString() ?: return false
        return name.lowercase() in excludedLowercase
    }

    private fun entryFor(dir: Path): RepoEntry {
        val absolute = dir.toAbsolutePath().normalize()
        val name = absolute.fileName?.toString() ?: absolute.toString()
        return RepoEntry(name = name, path = absolute, displayPath = abbreviateHome(absolute.toString()))
    }

    private fun resolveRoot(raw: String): Path? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val expanded = when {
            trimmed == "~" -> homeDir
            trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> homeDir + trimmed.substring(1)
            else -> trimmed
        }
        return try {
            Paths.get(expanded).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun abbreviateHome(path: String): String {
        if (homeDir.isEmpty()) return path
        return when {
            path == homeDir -> "~"
            path.startsWith(homeDir) && path.length > homeDir.length && isSeparator(path[homeDir.length]) ->
                "~" + path.substring(homeDir.length)
            else -> path
        }
    }

    private fun isSeparator(c: Char) = c == '/' || c == '\\'

    private fun realPathOf(dir: Path): Path =
        try {
            dir.toRealPath()
        } catch (_: IOException) {
            dir.toAbsolutePath().normalize()
        }

    companion object {
        const val GIT_MARKER = ".git"
    }
}
