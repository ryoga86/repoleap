package de.pdenis.repoleap.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import de.pdenis.repoleap.ui.RepoLeapPopup

/**
 * Shows the repository search popup (default: Option+Command+I on macOS, Alt+Shift+P elsewhere).
 * Works with and without an open project.
 */
class OpenRepositoryAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        RepoLeapPopup(e.project).show()
    }
}
