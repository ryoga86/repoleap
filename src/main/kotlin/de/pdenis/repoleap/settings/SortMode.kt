package de.pdenis.repoleap.settings

import de.pdenis.repoleap.RepoLeapBundle
import org.jetbrains.annotations.Nls

/** Order of the repositories in the popup. Pinned repositories are always listed first. */
enum class SortMode {
    /** Current window, other windows, the rest - each by search quality and recent use. */
    SMART,
    NAME,

    /** Most recently opened via RepoLeap first, never opened ones by name afterwards. */
    RECENT,
    PATH;

    @get:Nls
    val displayName: String
        get() = RepoLeapBundle.message("settings.sortMode.$name")
}
