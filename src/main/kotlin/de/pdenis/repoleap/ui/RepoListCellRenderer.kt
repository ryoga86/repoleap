package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import de.pdenis.repoleap.RepoLeapBundle
import java.awt.Color
import javax.swing.JList

/** Renders `name  ~/path/to/name  ● other window` with highlighted matches and colored tags. */
internal class RepoListCellRenderer : ColoredListCellRenderer<RepoListItem>() {

    override fun customizeCellRenderer(
        list: JList<out RepoListItem>,
        value: RepoListItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        val repo = value.repo
        icon = if (value.pinned) AllIcons.General.Pin_tab else AllIcons.Nodes.Folder

        SpeedSearchUtil.appendColoredFragments(
            this, repo.name, value.match.nameRanges.toTextRanges(),
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, NAME_HIGHLIGHT,
        )
        append("  ")
        SpeedSearchUtil.appendColoredFragments(
            this, repo.displayPath, value.match.pathRanges.toTextRanges(),
            SimpleTextAttributes.GRAYED_ATTRIBUTES, PATH_HIGHLIGHT,
        )

        when (value.windowState) {
            WindowState.CURRENT -> appendTag(RepoLeapBundle.message("popup.tag.current"), CURRENT_WINDOW_TAG)
            WindowState.OTHER -> appendTag(RepoLeapBundle.message("popup.tag.open"), OPEN_TAG)
            WindowState.CLOSED -> Unit
        }
    }

    private fun appendTag(text: String, attributes: SimpleTextAttributes) {
        append("   ")
        val tag = "$TAG_BULLET $text"
        // ColoredListCellRenderer paints every fragment of the selected row in the selection color. Keep the tag color
        // on the selected row as well - unless it would be hard to read on the theme's selection background.
        val selectionBackground = background
        val keepColor = mySelected && selectionBackground != null &&
            contrastRatio(attributes.fgColor, selectionBackground) >= MIN_TAG_CONTRAST
        if (!keepColor) {
            append(tag, attributes)
            return
        }
        mySelected = false
        try {
            append(tag, attributes)
        } finally {
            mySelected = true
        }
    }

    private fun List<IntRange>.toTextRanges(): List<TextRange> = map { TextRange(it.first, it.last + 1) }

    internal companion object {
        private const val TAG_BULLET = "\u25CF"
        private const val MIN_TAG_CONTRAST = 3.0

        /** WCAG contrast ratio (1..21) between two colors. */
        internal fun contrastRatio(a: Color, b: Color): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun relativeLuminance(c: Color): Double {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }

        private val NAME_HIGHLIGHT = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SEARCH_MATCH, null,
        )
        private val PATH_HIGHLIGHT = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SEARCH_MATCH, SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor,
        )

        // Green / blue from the IntelliJ palette (light, dark). Themes can override the named keys.
        val CURRENT_WINDOW_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.currentWindowForeground", JBColor(0x208A3C, 0x5FB865)),
        )
        val OPEN_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.openForeground", JBColor(0x3574F0, 0x6B9BFA)),
        )
    }
}
