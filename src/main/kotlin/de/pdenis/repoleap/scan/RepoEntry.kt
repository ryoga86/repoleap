package de.pdenis.repoleap.scan

import java.nio.file.Path

/** A discovered repository. */
data class RepoEntry(
    /** Folder name, e.g. `my-service`. */
    val name: String,
    /** Absolute path as found below the configured root. */
    val path: Path,
    /** Path shown to the user (home directory abbreviated with `~`). */
    val displayPath: String,
) {
    val pathString: String get() = path.toString()
}

/** Immutable snapshot of the settings that influence scanning. */
data class ScanOptions(
    val roots: List<String>,
    val maxDepth: Int,
    val excludedFolderNames: Set<String>,
    val skipHiddenFolders: Boolean,
)
