package de.pdenis.repoleap.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.GitRepoInfo
import de.pdenis.repoleap.scan.RepoEntry
import org.jetbrains.annotations.TestOnly
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/** Git and working tree information about a repository. OK = open it. */
internal class RepoInfoDialog(
    project: Project?,
    private val repo: RepoEntry,
    private val info: GitRepoInfo,
    private val openState: String,
) : DialogWrapper(project) {

    init {
        title = RepoLeapBundle.message("info.title", repo.name)
        setOKButtonText(RepoLeapBundle.message("info.open"))
        setCancelButtonText(RepoLeapBundle.message("info.close"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group(RepoLeapBundle.message("info.group.general")) {
            row(RepoLeapBundle.message("info.name")) { label(repo.name).bold() }
            row(RepoLeapBundle.message("info.path")) { cell(JBLabel(repo.pathString).apply { setCopyable(true) }) }
            row(RepoLeapBundle.message("info.state")) { label(openState) }
        }

        group(RepoLeapBundle.message("info.group.git")) {
            when {
                !info.gitAvailable -> row { comment(RepoLeapBundle.message("info.noGit")) }
                info.error != null -> row { comment(RepoLeapBundle.message("info.gitError", info.error)) }
            }
            row(RepoLeapBundle.message("info.branch")) { label(branchText()) }
            if (info.gitAvailable && info.error == null) {
                row(RepoLeapBundle.message("info.upstream")) { label(upstreamText()) }
            }
            info.lastCommit?.let { commit ->
                row(RepoLeapBundle.message("info.lastCommit")) {
                    label("${commit.shortHash}  ${commit.subject.take(MAX_SUBJECT_LENGTH)}")
                }
                row("") {
                    val date = commit.date?.let { DateFormatUtil.formatPrettyDateTime(it.toInstant().toEpochMilli()) }
                    comment(listOfNotNull(commit.author, date).joinToString(" \u00b7 "))
                }
            }
            if (info.remotes.isEmpty()) {
                row(RepoLeapBundle.message("info.remotes")) { label(RepoLeapBundle.message("info.remotes.none")) }
            }
            info.remotes.forEachIndexed { index, remote ->
                row(if (index == 0) RepoLeapBundle.message("info.remotes") else "") {
                    val url = remote.browserUrl
                    if (url != null) browserLink("${remote.name}: ${remote.url}", url)
                    else label("${remote.name}: ${remote.url}")
                }
            }
        }

        if (info.gitAvailable && info.error == null) {
            group(RepoLeapBundle.message("info.group.workingTree")) {
                row(RepoLeapBundle.message("info.changes")) { label(changesText()) }
                row(RepoLeapBundle.message("info.unpushed")) {
                    label(
                        when (val unpushed = info.unpushedCommits) {
                            null -> "\u2013"
                            0 -> RepoLeapBundle.message("info.unpushed.none")
                            else -> RepoLeapBundle.message("risk.unpushed", unpushed)
                        },
                    )
                }
                row(RepoLeapBundle.message("info.branches")) { label(info.localBranches?.toString() ?: "\u2013") }
                row(RepoLeapBundle.message("info.stashes")) { label(info.stashes?.toString() ?: "\u2013") }
            }
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        val actions = ArrayList<Action>()
        if (RevealFileAction.isSupported()) {
            actions += object : AbstractAction(RevealFileAction.getActionName()) {
                override fun actionPerformed(e: ActionEvent) = RevealFileAction.openDirectory(repo.path)
            }
        }
        val url = info.remotes.firstOrNull { it.name == "origin" }?.browserUrl ?: info.remotes.firstNotNullOfOrNull { it.browserUrl }
        if (url != null) {
            actions += object : AbstractAction(RepoLeapBundle.message("action.browser")) {
                override fun actionPerformed(e: ActionEvent) = BrowserUtil.browse(url)
            }
        }
        return actions.toTypedArray()
    }

    private fun branchText(): String = when {
        info.branch != null && info.headCommit == null && info.gitAvailable ->
            RepoLeapBundle.message("info.branch.noCommits", info.branch)
        info.branch != null -> info.branch
        info.headCommit != null -> RepoLeapBundle.message("info.branch.detached", info.headCommit.take(8))
        else -> "\u2013"
    }

    private fun upstreamText(): String {
        val upstream = info.upstream ?: return RepoLeapBundle.message("info.upstream.none")
        val ahead = info.ahead ?: 0
        val behind = info.behind ?: 0
        if (ahead == 0 && behind == 0) return RepoLeapBundle.message("info.upstream.upToDate", upstream)
        return RepoLeapBundle.message("info.upstream.aheadBehind", upstream, ahead, behind)
    }

    private fun changesText(): String {
        if (info.isClean) return RepoLeapBundle.message("info.changes.clean")
        return listOfNotNull(
            info.changedFiles?.takeIf { it > 0 }?.let { RepoLeapBundle.message("risk.changes", it) },
            info.conflictedFiles?.takeIf { it > 0 }?.let { RepoLeapBundle.message("risk.conflicts", it) },
            info.untrackedFiles?.takeIf { it > 0 }?.let { RepoLeapBundle.message("risk.untracked", it) },
        ).joinToString(" \u00b7 ")
    }

    @TestOnly
    internal fun createCenterPanelForTest(): JComponent = createCenterPanel()

    private companion object {
        const val MAX_SUBJECT_LENGTH = 90
    }
}
