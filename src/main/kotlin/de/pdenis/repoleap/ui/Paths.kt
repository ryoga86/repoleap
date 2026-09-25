package de.pdenis.repoleap.ui

import java.nio.file.InvalidPathException
import java.nio.file.Paths

/** Normalizes a path string so that paths from different sources (project base path, scanner) are comparable. */
internal fun normalizePath(path: String): String =
    try {
        Paths.get(path).toAbsolutePath().normalize().toString()
    } catch (_: InvalidPathException) {
        path
    }
