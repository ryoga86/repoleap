package de.pdenis.repoleap.settings

import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

/** Lets a text field accept at most [max] characters: typing stops there, pasted text is cut off. */
internal class MaxLengthFilter(private val max: Int) : DocumentFilter() {

    override fun insertString(fb: FilterBypass, offset: Int, text: String?, attrs: AttributeSet?) =
        replace(fb, offset, 0, text, attrs)

    override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
        if (text == null) return super.replace(fb, offset, length, null, attrs)
        val room = (max - (fb.document.length - length)).coerceAtLeast(0)
        super.replace(fb, offset, length, text.take(room), attrs)
    }
}
