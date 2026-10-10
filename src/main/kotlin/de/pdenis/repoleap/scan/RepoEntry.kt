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
    /** Checked out branch (short commit hash for a detached HEAD) at scan time, `null` if unknown. */
    val branch: String? = null,
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
