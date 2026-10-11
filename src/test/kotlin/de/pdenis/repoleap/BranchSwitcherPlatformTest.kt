package de.pdenis.repoleap

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.repoleap.ui.BranchPopup
import de.pdenis.repoleap.ui.BranchSwitcher
import de.pdenis.repoleap.ui.RepoListCellRenderer
import java.awt.event.KeyEvent

/** The branch list: choice and Esc are handed over, and it searches like the repository list. */
class BranchSwitcherPlatformTest : BasePlatformTestCase() {

    private val main = BranchSwitcher.Item("main", remoteOnly = false, current = true)
    private val feature = BranchSwitcher.Item("feature/login-form", remoteOnly = false, current = false)
    private val remote = BranchSwitcher.Item("origin/release-2", remoteOnly = true, current = false)
    private val items = listOf(main, feature, remote)


    private fun withPopup(block: (BranchPopup, List<BranchSwitcher.Item?>) -> Unit) {
        val done = mutableListOf<BranchSwitcher.Item?>()
        val popup = BranchPopup("t", items) { done += it }
        popup.show(project)
        try {
            block(popup, done)
        } finally {
            popup.closeForTest()
        }
    }

    private fun names(popup: BranchPopup) = popup.rowsForTest().map { it.first }

    fun testChosenBranchIsPassedOn() = withPopup { popup, done ->
        popup.pressKeyForTest(KeyEvent.VK_DOWN) // main -> feature
        popup.pressKeyForTest(KeyEvent.VK_ENTER)
        assertEquals(listOf<BranchSwitcher.Item?>(feature), done)
    }

    fun testEscGivesNull() = withPopup { popup, done ->
        popup.pressKeyForTest(KeyEvent.VK_ESCAPE)
        assertEquals(listOf<BranchSwitcher.Item?>(null), done)
    }

    fun testClosingFromOutsideCallsNothing() = withPopup { popup, done ->
        popup.closeForTest()
        assertEquals(emptyList<BranchSwitcher.Item?>(), done)
    }

    fun testEnterWithoutMatchStays() = withPopup { popup, done ->
        popup.setSearchTextForTest("zzzz")
        assertEquals(emptyList<String>(), names(popup))
        popup.pressKeyForTest(KeyEvent.VK_ENTER)
        assertEquals(emptyList<BranchSwitcher.Item?>(), done)
        popup.setSearchTextForTest("")
        popup.pressKeyForTest(KeyEvent.VK_ENTER) // the popup was still open
        assertEquals(listOf<BranchSwitcher.Item?>(main), done)
    }

    fun testCurrentBranchHasTheGreenTag() = withPopup { popup, _ ->
        val rows = popup.renderedForTest().map { fragments -> fragments.toMap() }
        val tag = "\u25CF " + RepoLeapBundle.message("branch.current")
        assertEquals("\u25CF current branch", tag)
        assertEquals(RepoListCellRenderer.CURRENT_WINDOW_TAG.fgColor, rows[0].getValue(tag).fgColor)
        assertTrue("only the current branch has it", rows.drop(1).none { row -> row.keys.any { it.startsWith("\u25CF") } })
    }

    fun testSearchIsFuzzyAndHighlights() = withPopup { popup, _ ->
        assertEquals("without a query the order is kept", listOf("main", "feature/login-form", "origin/release-2"), names(popup))
        assertTrue(popup.rowsForTest().all { it.second.isEmpty() })

        popup.setSearchTextForTest("login")
        assertEquals(listOf("feature/login-form" to listOf(8..12)), popup.rowsForTest())

        popup.setSearchTextForTest("flf") // fuzzy: f(eature/)l(ogin-)f(orm)
        assertEquals(listOf("feature/login-form"), names(popup))
        assertEquals(listOf(0..0, 8..8, 14..14), popup.rowsForTest().single().second)

        popup.setSearchTextForTest("rel 2") // several words, any order, remote ones too
        assertEquals(listOf("origin/release-2"), names(popup))
    }
}
