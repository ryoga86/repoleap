package de.pdenis.repoleap.settings

import de.pdenis.repoleap.RepoLeapBundle
import org.jetbrains.annotations.Nls

/** How the characters matched by the search are marked in the popup lists. */
enum class HighlightStyle {
    /** Bold text in the highlight color. This is the default. */
    TEXT_COLOR,

    /** The theme's search-match background (as in the IDE's speed search), or a background color of its own. */
    BACKGROUND,

    /** Underlined, text and line in the highlight color. */
    UNDERLINE,

    /** Wavy underline in the highlight color, the text keeps its color. */
    WAVED;

    @get:Nls
    val displayName: String
        get() = RepoLeapBundle.message("settings.highlight.style.$name")
}
