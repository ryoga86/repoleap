package de.pdenis.repoleap.settings

import de.pdenis.repoleap.RepoLeapBundle
import org.jetbrains.annotations.Nls

/** What happens when a repository is chosen in the popup. */
enum class OpenMode {
    /** Always open the repository in a separate project window. */
    NEW_WINDOW,

    /** Close the current project and open the repository in the same window. */
    THIS_WINDOW,

    /** Delegate to the IDE setting "Open project in" (which asks by default). */
    IDE_DEFAULT;

    @get:Nls
    val displayName: String
        get() = RepoLeapBundle.message("settings.openMode.$name")

    /** Mode used for Shift+Enter in the popup. */
    val alternative: OpenMode
        get() = if (this == NEW_WINDOW) THIS_WINDOW else NEW_WINDOW
}
