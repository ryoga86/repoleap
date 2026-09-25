package de.pdenis.repoleap.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import javax.swing.JList

/** `icon  Text  hint` - disabled actions are greyed out and show why. */
internal class RepoActionCellRenderer : ColoredListCellRenderer<RepoAction>() {

    override fun customizeCellRenderer(
        list: JList<out RepoAction>,
        value: RepoAction?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        ipad = JBUI.insets(3, 6)
        icon = value.icon
        if (value.enabled) {
            append(value.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.hint?.let { append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        } else {
            append(value.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("   " + value.disabledReason, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }
}
