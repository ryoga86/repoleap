package de.pdenis.repoleap.git

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Finds an executable in the directories of the `PATH` of the IDE.
 *
 * Replaces `PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS`, which is scheduled for removal; its successor
 * `findFirst` only exists since IDE 2026.3 and is experimental, so it cannot be used with since-build 252.
 * `EnvironmentUtil.getValue` is stable API and, like the former call, returns the environment of the user's shell on
 * macOS and Linux (IDEs started from the Dock or a launcher do not inherit it).
 */
internal object ExecutableFinder {

    private const val DEFAULT_PATHEXT = ".com;.exe;.bat;.cmd"

    /**
     * Absolute path of the first executable file called [baseName] in [pathValue], or `null` if there is none.
     * Relative `PATH` entries are ignored. On Windows the extensions of [pathExt] are tried (`git` finds `git.exe`).
     *
     * The parameters only exist for the tests; the defaults describe the running IDE.
     */
    fun find(
        baseName: String,
        pathValue: String? = EnvironmentUtil.getValue("PATH"),
        windows: Boolean = isWindows(),
        pathExt: String? = System.getenv("PATHEXT"),
    ): Path? {
        if (baseName.isBlank() || '/' in baseName || '\\' in baseName) return null
        if (pathValue.isNullOrBlank()) return null
        val candidates = candidateNames(baseName, windows, pathExt)
        for (entry in pathValue.split(File.pathSeparatorChar)) {
            val text = entry.trim().trim('"')
            if (text.isEmpty()) continue
            val dir = try {
                Path.of(text)
            } catch (_: InvalidPathException) {
                continue
            }
            if (!dir.isAbsolute || !Files.isDirectory(dir)) continue
            for (name in candidates) {
                val file = try {
                    dir.resolve(name)
                } catch (_: InvalidPathException) {
                    continue
                }
                if (Files.isRegularFile(file) && Files.isExecutable(file)) return file
            }
        }
        return null
    }

    private fun candidateNames(baseName: String, windows: Boolean, pathExt: String?): List<String> {
        if (!windows) return listOf(baseName)
        val extensions = (pathExt?.takeIf { it.isNotBlank() } ?: DEFAULT_PATHEXT)
            .split(';')
            .map { it.trim().lowercase() }
            .filter { it.startsWith(".") }
        if (extensions.any { baseName.lowercase().endsWith(it) }) return listOf(baseName)
        return extensions.map { baseName + it } + baseName
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
}
