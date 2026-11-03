package de.pdenis.repoleap.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.Tags
import org.jetbrains.annotations.TestOnly
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** "Edit Tags…": one text field (`#work #java`), the tags already used elsewhere can be added with a click. */
internal class TagsDialog(
    project: Project?,
    repo: RepoEntry,
    current: List<String>,
    /** All tags in use, by name. */
    private val known: List<String>,
) : DialogWrapper(project) {

    private val textField = JBTextField(Tags.format(current).let { if (it.isEmpty()) it else "$it " })

    init {
        title = RepoLeapBundle.message("tags.title", repo.name)
        init()
    }

    /** The tags as entered (cleaned up). */
    val tags: List<String> get() = Tags.parse(textField.text)

    override fun createCenterPanel(): JComponent = panel {
        row(RepoLeapBundle.message("tags.label")) {
            cell(textField)
                .align(AlignX.FILL)
                .comment(RepoLeapBundle.message("tags.comment"))
        }
        if (known.isNotEmpty()) {
            row(RepoLeapBundle.message("tags.known")) {
                val links = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    known.forEach { tag -> add(ActionLink("#$tag") { toggle(tag) }) }
                }
                cell(links).align(AlignX.FILL)
            }
        }
    }.apply { preferredSize = JBUI.size(460, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = textField

    /** Adds [tag], or removes it if it is there already. */
    internal fun toggle(tag: String) {
        val tags = Tags.parse(textField.text)
        val updated = if (tags.any { it.equals(tag, ignoreCase = true) }) tags.filterNot { it.equals(tag, ignoreCase = true) } else tags + tag
        textField.text = Tags.format(updated).let { if (it.isEmpty()) it else "$it " }
        textField.requestFocusInWindow()
    }

    @TestOnly
    internal fun setTextForTest(text: String) {
        textField.text = text
    }

    @TestOnly
    internal fun textForTest(): String = textField.text
}
