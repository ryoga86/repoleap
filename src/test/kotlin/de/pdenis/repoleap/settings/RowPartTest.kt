package de.pdenis.repoleap.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.text.PlainDocument

class RowPartTest {

    @Test
    fun `the default order is branch, tags, path, window state`() {
        assertEquals(listOf(RowPart.BRANCH, RowPart.TAGS, RowPart.PATH, RowPart.WINDOW_STATE), RowPart.DEFAULT_ORDER)
        assertEquals("BRANCH,TAGS,PATH,WINDOW_STATE", RepoLeapSettings.State().rowOrder)
        assertEquals(RowPart.DEFAULT_ORDER, RowPart.parse(RepoLeapSettings.State().rowOrder))
    }

    @Test
    fun `an order survives saving and loading`() {
        val order = listOf(RowPart.WINDOW_STATE, RowPart.PATH, RowPart.BRANCH, RowPart.TAGS)
        assertEquals(order, RowPart.parse(RowPart.format(order)))
    }

    @Test
    fun `a damaged or old order is repaired`() {
        assertEquals("missing parts come last", listOf(RowPart.PATH, RowPart.BRANCH, RowPart.TAGS, RowPart.WINDOW_STATE), RowPart.parse("PATH,BRANCH"))
        assertEquals("unknown and repeated names are ignored", listOf(RowPart.TAGS, RowPart.BRANCH, RowPart.PATH, RowPart.WINDOW_STATE), RowPart.parse("tags, nonsense, TAGS ,,Branch"))
        assertEquals(RowPart.DEFAULT_ORDER, RowPart.parse(""))
    }

    @Test
    fun `a length limit cuts off typed and pasted text`() {
        val document = PlainDocument().apply { documentFilter = MaxLengthFilter(3) }
        document.insertString(0, "abcdef", null)
        assertEquals("abc", document.getText(0, document.length))
        document.insertString(0, "x", null)
        assertEquals("abc", document.getText(0, document.length))
        document.replace(1, 2, "XYZ", null) // replacing two characters leaves room for two
        assertEquals("aXY", document.getText(0, document.length))
        document.remove(0, 3)
        assertEquals(0, document.length)
        document.insertString(0, "  /", null)
        assertEquals("  /", document.getText(0, document.length))
    }
}
