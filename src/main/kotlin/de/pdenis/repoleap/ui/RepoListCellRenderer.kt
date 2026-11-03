package de.pdenis.repoleap.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.open.CloneTarget
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.settings.RowPart
import java.awt.Color
import javax.swing.JList

/** A list renderer with colored `● tag` fragments (green "current window", ...). */
internal abstract class TaggedListCellRenderer<T> : ColoredListCellRenderer<T>() {

    protected fun appendTag(text: String, attributes: SimpleTextAttributes) {
        append("   ")
        appendTagText(text, attributes)
    }

    /** `● text` without the space in front of it. */
    protected fun appendTagText(text: String, attributes: SimpleTextAttributes) {
        appendKeepingColor("$TAG_BULLET $text", attributes)
    }

    protected fun appendKeepingColor(text: String, attributes: SimpleTextAttributes) {
        // ColoredListCellRenderer paints every fragment of the selected row in the selection color. Keep the color
        // on the selected row as well - unless it would be hard to read on the theme's selection background.
        if (!canKeepColor(attributes)) {
            append(text, attributes)
            return
        }
        appendUnselected(text, attributes)
    }

    /** `true` on a selected row if the color of [attributes] is readable on the selection background. */
    private fun canKeepColor(attributes: SimpleTextAttributes): Boolean {
        val selectionBackground = background
        val color = attributes.fgColor
        return mySelected && selectionBackground != null && color != null &&
            RepoListCellRenderer.contrastRatio(color, selectionBackground) >= MIN_TAG_CONTRAST
    }

    /** Appends as if the row was not selected, so that background and wave colors are painted as given. */
    private fun appendUnselected(text: String, attributes: SimpleTextAttributes) {
        val selected = mySelected
        mySelected = false
        try {
            append(text, attributes)
        } finally {
            mySelected = selected
        }
    }

    /** [text] in [plain] with the [ranges] (inclusive) in [highlight], see [MatchHighlight]. */
    protected fun appendHighlighted(
        list: JList<*>,
        text: String,
        ranges: List<IntRange>,
        plain: SimpleTextAttributes,
        highlight: SimpleTextAttributes,
        /** Keep the color of [plain] on the selected row (like the branch and the tags). */
        keepPlainColor: Boolean = false,
    ) {
        fun appendPlain(fragment: String) = if (keepPlainColor) appendKeepingColor(fragment, plain) else append(fragment, plain)
        var position = 0
        for (range in ranges.sortedBy { it.first }) {
            val start = range.first.coerceIn(position, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start > position) appendPlain(text.substring(position, start))
            if (end > start) appendMatch(list, text.substring(start, end), highlight)
            position = end
        }
        if (position < text.length) appendPlain(text.substring(position))
    }

    private fun appendMatch(list: JList<*>, text: String, attributes: SimpleTextAttributes) {
        when {
            // the theme's search match is painted on selected rows as well
            !mySelected || attributes.isSearchMatch -> append(text, attributes)
            // own background: the text in the normal list color on it, as on unselected rows
            attributes.bgColor != null -> appendUnselected(text, attributes)
            attributes.waveColor != null -> appendUnselected(
                text, SimpleTextAttributes(null, list.selectionForeground, attributes.waveColor, attributes.style),
            )
            canKeepColor(attributes) -> appendUnselected(text, attributes)
            // The color is not readable on this selection (e.g. orange on strong blue) and the text falls back to the
            // selection color: names are bold anyway, so underline the match to keep it visible
            else -> append(text, SimpleTextAttributes(attributes.style or SimpleTextAttributes.STYLE_UNDERLINE, attributes.fgColor))
        }
    }

    private companion object {
        const val TAG_BULLET = "\u25CF"
        const val MIN_TAG_CONTRAST = 3.0
    }
}

/**
 * Renders `name  branch  #tags  ~/path/to/name  ● other window` with highlighted matches and colored tags.
 * The name is always first, the parts after it follow [order]. With a [separator] the parts are joined like
 * `name / branch / #tags / ~/path / ● other window`.
 */
internal class RepoListCellRenderer(
    var showBranch: Boolean = true,
    var showPath: Boolean = true,
    var showWindowState: Boolean = true,
    var showTags: Boolean = true,
    var highlight: MatchHighlight = MatchHighlight.DEFAULT,
    /** Text between the parts of a row, see [RepoLeapSettings.normalizeSeparator]; empty = only spaces. */
    separator: String = "",
    /** Order of the parts after the name. */
    var order: List<RowPart> = RowPart.DEFAULT_ORDER,
) : TaggedListCellRenderer<RepoListItem>() {

    var separator: String = RepoLeapSettings.normalizeSeparator(separator)
        set(value) {
            field = RepoLeapSettings.normalizeSeparator(value)
        }

    /** Whether the row has a part already, so that the next one needs a gap or the separator in front of it. */
    private var hasPart = false

    /** Starts a part: [gap] (spaces) in front of it, or the separator with a space on each side. */
    private fun startPart(gap: String) {
        if (hasPart) {
            if (separator.isEmpty()) {
                append(gap)
            } else {
                append(" ")
                append(separator, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(" ")
            }
        }
        hasPart = true
    }

    override fun customizeCellRenderer(
        list: JList<out RepoListItem>,
        value: RepoListItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        hasPart = false
        if (value == null) return
        value.clone?.let {
            renderClone(it)
            return
        }
        val repo = value.repo
        icon = if (value.pinned) AllIcons.General.Pin_tab else AllIcons.Nodes.Folder

        startPart("")
        appendHighlighted(list, repo.name, value.match.nameRanges, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, highlight.nameAttributes)
        for (part in order) when (part) {
            RowPart.BRANCH -> repo.branch?.takeIf { showBranch }?.let {
                startPart("  ")
                appendKeepingColor(it, BRANCH)
            }
            RowPart.TAGS -> if (showTags && value.tags.isNotEmpty()) {
                startPart("  ")
                value.tags.forEachIndexed { i, tag ->
                    if (i > 0) append(" ")
                    val matched = value.tagMatches.getOrElse(i) { 0 }
                    // the # belongs to the match: "#wo" was typed
                    appendHighlighted(list, "#$tag", if (matched > 0) listOf(0..matched) else emptyList(), TAG, highlight.tagAttributes, keepPlainColor = true)
                }
            }
            RowPart.PATH -> if (showPath) {
                startPart("  ")
                appendHighlighted(list, repo.displayPath, value.match.pathRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, highlight.pathAttributes)
            }
            RowPart.WINDOW_STATE -> if (showWindowState) when (value.windowState) {
                WindowState.CURRENT -> windowTag(RepoLeapBundle.message("popup.tag.current"), CURRENT_WINDOW_TAG)
                WindowState.OTHER -> windowTag(RepoLeapBundle.message("popup.tag.open"), OPEN_TAG)
                WindowState.CLOSED -> Unit
            }
        }
        if (value.sameRemote) windowTag(RepoLeapBundle.message("popup.tag.sameRemote"), CURRENT_WINDOW_TAG)
    }

    private fun windowTag(text: String, attributes: SimpleTextAttributes) {
        startPart("   ")
        appendTagText(text, attributes)
    }

    /** `Clone into ~/dev/shop-api`, or `Clone into another folder…  shop-api`. */
    private fun renderClone(target: CloneTarget) {
        icon = AllIcons.Vcs.Clone
        val enabled = target.enabled
        val plain = if (enabled) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
        val bold = if (enabled) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
        if (target.target == null) {
            append(RepoLeapBundle.message("clone.row.other"), bold)
            append("  " + target.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        append(RepoLeapBundle.message("clone.row.into") + " ", plain)
        append(target.displayTarget, bold)
        target.problem?.let { append("  \u2013 $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }

    internal companion object {
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

        // Green / blue from the IntelliJ palette (light, dark). Themes can override the named keys.
        val CURRENT_WINDOW_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.currentWindowForeground", JBColor(0x208A3C, 0x5FB865)),
        )
        val OPEN_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.openForeground", JBColor(0x3574F0, 0x6B9BFA)),
        )
        // Purple from the IntelliJ palette, as git branches are shown in the IDE's VCS widgets
        val BRANCH = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.branchForeground", JBColor(0x834DF0, 0xA571E6)),
        )
        // Teal from the IntelliJ palette, so that tags stand out from the grey path
        val TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("RepoLeap.Popup.tagForeground", JBColor(0x0C7C75, 0x3FB5A8)),
        )
    }
}
