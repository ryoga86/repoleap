package de.pdenis.repoleap

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.repoleap.ui.BranchSwitcher

/** The branch list must hand the choice over - the platform runs the item callback only after `onClosed`. */
class BranchSwitcherPlatformTest : BasePlatformTestCase() {

    private val items = listOf(
        BranchSwitcher.Item("feature", remoteOnly = false, current = false),
        BranchSwitcher.Item("main", remoteOnly = false, current = true),
    )

    private fun run(close: (com.intellij.openapi.ui.popup.JBPopup) -> Unit): List<BranchSwitcher.Item?> {
        val done = mutableListOf<BranchSwitcher.Item?>()
        val popup = BranchSwitcher.createPopup("t", items) { done += it }
        popup.showCenteredInCurrentWindow(project)
        close(popup)
        PlatformTestUtil.waitWithEventsDispatching("onDone was not called", { done.isNotEmpty() }, 10)
        return done
    }

    fun testChosenBranchIsPassedOn() {
        assertEquals(listOf(items[0]), run { it.closeOk(null) })
    }

    fun testLeavingTheListWithoutChoiceGivesNull() {
        assertEquals(listOf<BranchSwitcher.Item?>(null), run { it.cancel() })
    }
}
