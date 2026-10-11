package de.pdenis.repoleap

import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.TitlePanel
import com.intellij.util.ui.UIUtil
import de.pdenis.repoleap.ui.RepoLeapPopup
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.math.abs

/** The toolbar sits left in the title bar; without a counterweight on the right the title is pushed off the center. */
class PopupTitlePlatformTest : BasePlatformTestCase() {

    fun testTitleIsCentered() {
        val popup = RepoLeapPopup(project)
        popup.show()
        try {
            var top: Container = popup.contentForTest()
            while (top.parent != null) top = top.parent
            val title = UIUtil.findComponentOfType(top as JComponent, TitlePanel::class.java)!!
            UIUtil.findComponentOfType(title, ActionToolbarImpl::class.java)!!.updateActionsImmediately()
            title.setSize(700, 38)
            UIUtil.layoutRecursively(title)

            val label = UIUtil.findComponentOfType(title, JLabel::class.java)!!
            val textWidth = label.getFontMetrics(label.font).stringWidth(label.text)
            val textCenter = label.x + (label.width - textWidth) / 2.0 + textWidth / 2.0
            assertTrue("title center $textCenter, popup center ${title.width / 2.0}", abs(textCenter - title.width / 2.0) <= 4)
        } finally {
            popup.closeForTest()
        }
    }
}
