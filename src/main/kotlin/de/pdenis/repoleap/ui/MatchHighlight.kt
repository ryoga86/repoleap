package de.pdenis.repoleap.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import de.pdenis.repoleap.settings.HighlightStyle
import de.pdenis.repoleap.settings.RepoLeapSettings
import java.awt.Color

/**
 * How matched characters are painted - the same in the repository list, the branch list and the settings preview.
 *
 * [customColor] `null` means the theme default: the search-match background for [HighlightStyle.BACKGROUND],
 * [DEFAULT_COLOR] for the other styles.
 */
internal data class MatchHighlight(val style: HighlightStyle, val customColor: Color? = null) {

    /** Matches in names (repository, branch), which are bold anyway. */
    val nameAttributes: SimpleTextAttributes = attributes(SimpleTextAttributes.STYLE_BOLD, null)

    /** Matches in the grey path. */
    val pathAttributes: SimpleTextAttributes = attributes(SimpleTextAttributes.STYLE_PLAIN, SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor)

    /** Matched `#tag` prefixes. */
    val tagAttributes: SimpleTextAttributes = attributes(SimpleTextAttributes.STYLE_PLAIN, RepoListCellRenderer.TAG.fgColor)

    private fun attributes(baseStyle: Int, baseForeground: Color?): SimpleTextAttributes {
        val color = customColor ?: DEFAULT_COLOR
        return when (style) {
            HighlightStyle.BACKGROUND ->
                if (customColor == null) SimpleTextAttributes(baseStyle or SimpleTextAttributes.STYLE_SEARCH_MATCH, baseForeground)
                else SimpleTextAttributes(customColor, baseForeground, null, baseStyle or SimpleTextAttributes.STYLE_OPAQUE)
            HighlightStyle.TEXT_COLOR -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
            HighlightStyle.UNDERLINE -> SimpleTextAttributes(baseStyle or SimpleTextAttributes.STYLE_UNDERLINE, color)
            HighlightStyle.WAVED -> SimpleTextAttributes(null, baseForeground, color, baseStyle or SimpleTextAttributes.STYLE_WAVED)
        }
    }

    companion object {
        // Declared before DEFAULT: DEFAULT is built from it when the companion is initialized
        /** Orange (light: dark enough to read on white and light selections; dark). Themes can override the named key. */
        val DEFAULT_COLOR: Color = JBColor.namedColor(
            "RepoLeap.Popup.matchForeground",
            JBColor(RepoLeapSettings.DEFAULT_HIGHLIGHT_LIGHT, RepoLeapSettings.DEFAULT_HIGHLIGHT_DARK),
        )

        val DEFAULT = MatchHighlight(HighlightStyle.TEXT_COLOR)

        fun fromSettings(state: RepoLeapSettings.State = RepoLeapSettings.getInstance().state): MatchHighlight = MatchHighlight(
            state.highlightStyle,
            if (state.customHighlightColor) JBColor(state.highlightColorLight, state.highlightColorDark) else null,
        )
    }
}
