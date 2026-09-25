package de.pdenis.repoleap

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.repoleap.git.GitRepoInfo
import de.pdenis.repoleap.git.Remote
import de.pdenis.repoleap.open.WindowMerger
import de.pdenis.repoleap.open.WindowTabs
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.ui.RepoActionHost
import de.pdenis.repoleap.ui.RepoActionsProvider
import de.pdenis.repoleap.ui.RepoDialogs
import de.pdenis.repoleap.ui.RepoInfoDialog
import de.pdenis.repoleap.ui.RepoLeapPopup
import de.pdenis.repoleap.ui.RepoListItem
import de.pdenis.repoleap.ui.WindowState
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/** Action view of the popup (Tab), pin/hide, disabled states, trash confirmation text and info dialog. */
class RepoActionsPlatformTest : BasePlatformTestCase() {

    private val noMatch = RepoMatcher.Result(0, emptyList(), emptyList())

    private val noopHost = object : RepoActionHost {
        override fun open(item: RepoListItem, alternative: Boolean) = Unit
        override fun closeThen(block: () -> Unit) = Unit
        override fun reopen(selection: Path?) = Unit
        override fun backToRepositories() = Unit
    }

    private fun repos(vararg names: String): Path {
        val root = FileUtil.createTempDirectory("repoleap-actions", null).toPath()
        names.forEach { Files.createDirectories(root.resolve("$it/.git")) }
        return root
    }

    private fun withPopup(root: Path, block: (RepoLeapPopup) -> Unit) {
        val state = RepoLeapSettings.getInstance().state
        val before = Triple(state.roots.toMutableList(), state.pinnedRepos.toMutableList(), state.hiddenRepos.toMutableList())
        state.roots = mutableListOf(root.toString())
        state.pinnedRepos = mutableListOf()
        state.hiddenRepos = mutableListOf()
        val index = RepoIndexService.getInstance()
        index.invalidate()
        PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)
        val popup = RepoLeapPopup(project)
        try {
            popup.show()
            PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)
            block(popup)
        } finally {
            popup.closeForTest()
            state.roots = before.first
            state.pinnedRepos = before.second
            state.hiddenRepos = before.third
        }
    }

    fun testTabShowsTheActionsOfTheSelectedRepositoryAndEscGoesBack() {
        withPopup(repos("alpha", "beta")) { popup ->
            popup.selectRepoForTest("beta")
            popup.pressKeyForTest(KeyEvent.VK_TAB)
            assertTrue(popup.isShowingActionsForTest())
            assertEquals(
                listOf(
                    RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_OPEN_ALTERNATIVE, RepoActionsProvider.ID_CLOSE,
                    RepoActionsProvider.ID_INFO,
                    RepoActionsProvider.ID_REVEAL, RepoActionsProvider.ID_COPY_PATH, RepoActionsProvider.ID_BROWSER,
                    RepoActionsProvider.ID_PIN, RepoActionsProvider.ID_HIDE, RepoActionsProvider.ID_RENAME,
                    RepoActionsProvider.ID_TRASH,
                ),
                popup.actionsForTest().map { it.id },
            )

            popup.pressKeyForTest(KeyEvent.VK_ESCAPE)
            assertFalse("Esc leaves the action view instead of closing", popup.isShowingActionsForTest())

            popup.pressKeyForTest(KeyEvent.VK_TAB)
            popup.pressKeyForTest(KeyEvent.VK_TAB)
            assertFalse("Tab toggles back", popup.isShowingActionsForTest())

            popup.pressKeyForTest(KeyEvent.VK_TAB)
            popup.setSearchTextForTest("al")
            assertFalse("typing searches again", popup.isShowingActionsForTest())
            assertEquals(listOf("alpha"), popup.visibleRepoNamesForTest())
        }
    }

    fun testPinnedRepositoriesComeFirstAndHiddenOnesAreNotListed() {
        withPopup(repos("alpha", "beta", "gamma")) { popup ->
            assertEquals(listOf("alpha", "beta", "gamma"), popup.visibleRepoNamesForTest())

            popup.selectRepoForTest("gamma")
            popup.pressKeyForTest(KeyEvent.VK_TAB)
            popup.runActionForTest(RepoActionsProvider.ID_PIN)
            assertFalse(popup.isShowingActionsForTest())
            assertEquals(listOf("gamma", "alpha", "beta"), popup.visibleRepoNamesForTest())

            popup.setSearchTextForTest("a") // pinned stays on top for searches, too
            assertEquals("gamma", popup.visibleRepoNamesForTest().first())
            popup.setSearchTextForTest("")

            popup.selectRepoForTest("beta")
            popup.pressKeyForTest(KeyEvent.VK_TAB)
            popup.runActionForTest(RepoActionsProvider.ID_HIDE)
            assertEquals(listOf("gamma", "alpha"), popup.visibleRepoNamesForTest())

            val state = RepoLeapSettings.getInstance().state
            assertEquals(1, state.pinnedRepos.size)
            assertTrue(state.hiddenRepos.single().endsWith("beta"))

            popup.selectRepoForTest("gamma")
            popup.pressKeyForTest(KeyEvent.VK_TAB)
            assertEquals(
                RepoLeapBundle.message("action.unpin"),
                popup.actionsForTest().single { it.id == RepoActionsProvider.ID_PIN }.text,
            )
        }
    }

    fun testRenameAndTrashAreDisabledWhileTheRepositoryIsOpen() {
        val basePath = Path.of(project.basePath!!)
        Files.createDirectories(basePath)
        val provider = RepoActionsProvider(project, noopHost)

        val openRepo = RepoEntry(basePath.fileName.toString(), basePath, basePath.toString())
        val openActions = provider.actionsFor(RepoListItem(openRepo, noMatch, 0)).associateBy { it.id }
        val reason = RepoLeapBundle.message("action.disabled.open")
        assertEquals(reason, openActions.getValue(RepoActionsProvider.ID_RENAME).disabledReason)
        assertEquals(reason, openActions.getValue(RepoActionsProvider.ID_TRASH).disabledReason)
        assertTrue(openActions.getValue(RepoActionsProvider.ID_CLOSE).enabled)
        assertEquals(RepoLeapBundle.message("popup.tag.current"), openActions.getValue(RepoActionsProvider.ID_CLOSE).hint)

        val closedPath = repos("closed").resolve("closed")
        Files.writeString(closedPath.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@github.com:ryoga86/repoleap.git\n")
        val closedActions = provider.actionsFor(RepoListItem(RepoEntry("closed", closedPath, closedPath.toString()), noMatch, 0))
            .associateBy { it.id }
        assertTrue(closedActions.getValue(RepoActionsProvider.ID_RENAME).enabled)
        assertEquals(RepoLeapBundle.message("action.disabled.notOpen"), closedActions.getValue(RepoActionsProvider.ID_CLOSE).disabledReason)
        assertTrue(closedActions.getValue(RepoActionsProvider.ID_BROWSER).enabled)
        assertEquals("https://github.com/ryoga86/repoleap", closedActions.getValue(RepoActionsProvider.ID_BROWSER).hint)

        val noRemote = repos("local").resolve("local")
        val noRemoteActions = provider.actionsFor(RepoListItem(RepoEntry("local", noRemote, noRemote.toString()), noMatch, 0))
            .associateBy { it.id }
        assertEquals(RepoLeapBundle.message("action.disabled.noRemote"), noRemoteActions.getValue(RepoActionsProvider.ID_BROWSER).disabledReason)
    }

    private fun proxyProject(name: String): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "isDisposed" -> false
                "getName" -> name
                "toString" -> "Project($name)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as Project

    private class FakeTabs : WindowTabs {
        override var isSupported = true
        /** projects whose windows are tabs of one window */
        val groups = mutableListOf<MutableSet<Project>>()
        var newWindowsBecomeTabs = false
        val calls = mutableListOf<String>()
        override fun areInSameGroup(a: Project, b: Project) = a == b || groups.any { a in it && b in it }
        override fun newWindowsBecomeTabsOf(host: Project) = newWindowsBecomeTabs
        override fun mergeAllWindowsInto(host: Project): Boolean {
            calls += "merge all into ${host.name}"
            return true
        }
    }

    fun testCloseProjectShowsTheListFromTheTopAgain() {
        val path = Path.of("/repos/other")
        val openByPath = mutableMapOf(path to proxyProject("other"))
        val reopenedWith = mutableListOf<Path?>()
        val recordingHost = object : RepoActionHost {
            override fun open(item: RepoListItem, alternative: Boolean) = Unit
            override fun closeThen(block: () -> Unit) = block()
            override fun reopen(selection: Path?) {
                reopenedWith += selection
            }
            override fun backToRepositories() = Unit
        }
        val provider = RepoActionsProvider(proxyProject("current"), recordingHost, WindowMerger(FakeTabs()) { emptyList() }) { openByPath[it] }
        val close = provider.actionsFor(RepoListItem(RepoEntry("other", path, path.toString()), noMatch, 0, windowState = WindowState.OTHER))
            .single { it.id == RepoActionsProvider.ID_CLOSE }
        assertTrue(close.enabled)

        openByPath.clear() // closed in the meantime -> nothing to close, only the popup is shown again
        close.perform()
        assertEquals(listOf<Path?>(null), reopenedWith)
    }

    fun testWindowActionsDependOnWhereTheRepositoryIsOpen() {
        val current = proxyProject("current")
        val other = proxyProject("other")
        val third = proxyProject("third")
        val currentPath = Path.of("/repos/current")
        val otherPath = Path.of("/repos/other")
        val closedPath = Path.of("/repos/closed")
        val openByPath = mutableMapOf(currentPath to current, otherPath to other)
        val openProjects = mutableListOf(current, other)
        val immediateHost = object : RepoActionHost {
            override fun open(item: RepoListItem, alternative: Boolean) = Unit
            override fun closeThen(block: () -> Unit) = block()
            override fun reopen(selection: Path?) = Unit
            override fun backToRepositories() = Unit
        }
        val tabs = FakeTabs()
        val merger = WindowMerger(tabs) { openProjects }
        val provider = RepoActionsProvider(current, immediateHost, merger) { openByPath[it] }

        fun item(path: Path, state: WindowState) =
            RepoListItem(RepoEntry(path.fileName.toString(), path, path.toString()), noMatch, 0, windowState = state)
        fun actions(path: Path, state: WindowState) = provider.actionsFor(item(path, state))
        fun ids(path: Path, state: WindowState, count: Int) = actions(path, state).take(count).map { it.id }

        // "Open" is always the first entry; the alternative only where it differs
        assertEquals(
            listOf(RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_OPEN_ALTERNATIVE, RepoActionsProvider.ID_CLOSE),
            ids(closedPath, WindowState.CLOSED, 3),
        )
        assertEquals(listOf(RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_CLOSE), ids(currentPath, WindowState.CURRENT, 2))
        assertEquals(RepoLeapBundle.message("action.open"), actions(otherPath, WindowState.OTHER).first().text)

        // the only other window -> IntelliJ's "Merge All Windows", nothing is reloaded
        assertEquals(WindowMerger.Way.MERGE_ALL_WINDOWS, merger.wayToMerge(current, other))
        assertEquals(
            listOf(RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_MERGE, RepoActionsProvider.ID_CLOSE),
            ids(otherPath, WindowState.OTHER, 3),
        )
        assertTrue(provider.mergeIntoThisWindow(item(otherPath, WindowState.OTHER)))
        assertEquals(listOf("merge all into current"), tabs.calls)

        // a third window would be merged as well -> only by reopening (if macOS opens new windows as tabs)
        openProjects += third
        assertEquals(WindowMerger.Way.NOT_POSSIBLE, merger.wayToMerge(current, other))
        val disabled = actions(otherPath, WindowState.OTHER).single { it.id == RepoActionsProvider.ID_MERGE }
        assertEquals(RepoLeapBundle.message("action.disabled.tabsPreference"), disabled.disabledReason)
        assertFalse(provider.mergeIntoThisWindow(item(otherPath, WindowState.OTHER)))
        tabs.newWindowsBecomeTabs = true
        assertEquals(WindowMerger.Way.REOPEN_AS_TAB, merger.wayToMerge(current, other))
        assertTrue(actions(otherPath, WindowState.OTHER).single { it.id == RepoActionsProvider.ID_MERGE }.hint!!
            .contains(RepoLeapBundle.message("action.merge.reopens")))

        // the third window is a tab of this window already -> merging all is exact again
        tabs.groups += mutableSetOf(current, third)
        assertEquals(WindowMerger.Way.MERGE_ALL_WINDOWS, merger.wayToMerge(current, other))

        // already a tab of this window -> nothing to merge
        tabs.groups.single() += other
        assertEquals(WindowMerger.Way.ALREADY_TAB, merger.wayToMerge(current, other))
        assertEquals(listOf(RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_CLOSE), ids(otherPath, WindowState.OTHER, 2))
        assertFalse(provider.mergeIntoThisWindow(item(otherPath, WindowState.OTHER)))

        // without native window tabs (Windows, Linux) there is no merge at all
        tabs.isSupported = false
        tabs.groups.clear()
        assertEquals(WindowMerger.Way.UNSUPPORTED, merger.wayToMerge(current, other))
        assertEquals(listOf(RepoActionsProvider.ID_OPEN, RepoActionsProvider.ID_CLOSE), ids(otherPath, WindowState.OTHER, 2))
        assertFalse(provider.mergeIntoThisWindow(item(closedPath, WindowState.CLOSED)))
    }

    fun testRemoteHintShowsTheCompleteWebPage() {
        val repo = repos("with-remote").resolve("with-remote")
        Files.writeString(repo.resolve(".git/config"), "[remote \"origin\"]\n\turl = ssh://git@bitbucket.example.com:7999/team/app.git\n")
        val action = RepoActionsProvider(project, noopHost)
            .actionsFor(RepoListItem(RepoEntry("with-remote", repo, repo.toString()), noMatch, 0))
            .single { it.id == RepoActionsProvider.ID_BROWSER }
        assertEquals("https://bitbucket.example.com/projects/TEAM/repos/app/browse", action.hint)
    }

    fun testTrashConfirmationListsWhatWouldBeLost() {
        val repo = RepoEntry("my-repo", Path.of("/repos/my-repo"), "~/repos/my-repo")
        val origin = listOf(Remote("origin", "git@github.com:a/b.git"))

        val risky = GitRepoInfo(
            gitAvailable = true, branch = "main", headCommit = "abc", remotes = origin,
            changedFiles = 2, untrackedFiles = 1, conflictedFiles = 0, unpushedCommits = 3, stashes = 1,
        )
        val riskyText = RepoDialogs.trashMessage(repo, risky)
        assertTrue(riskyText, riskyText.contains("~/repos/my-repo"))
        for (expected in listOf("2 uncommitted changes", "1 untracked file", "3 commits not pushed to any remote", "1 stash")) {
            assertTrue("'$expected' in: $riskyText", riskyText.contains(expected))
        }

        val safe = risky.copy(changedFiles = 0, untrackedFiles = 0, unpushedCommits = 0, stashes = 0)
        assertTrue(RepoDialogs.trashMessage(repo, safe).contains(RepoLeapBundle.message("trash.safe")))

        val local = safe.copy(remotes = emptyList())
        assertTrue(RepoDialogs.trashMessage(repo, local).contains(RepoLeapBundle.message("risk.noRemote")))

        val withoutGit = GitRepoInfo(gitAvailable = false, branch = "main", headCommit = null)
        assertTrue(RepoDialogs.trashMessage(repo, withoutGit).contains(RepoLeapBundle.message("trash.noGit")))
    }

    fun testInfoDialogCanBeBuiltForAllStates() {
        val repo = RepoEntry("my-repo", Path.of("/repos/my-repo"), "~/repos/my-repo")
        val infos = listOf(
            GitRepoInfo(
                gitAvailable = true, branch = "main", headCommit = "0123456789", upstream = "origin/main", ahead = 1, behind = 2,
                lastCommit = de.pdenis.repoleap.git.CommitInfo("0123456789", "Fix things", "pdenis", java.time.OffsetDateTime.now()),
                remotes = listOf(Remote("origin", "git@github.com:a/b.git"), Remote("local", "/tmp/x.git")),
                changedFiles = 1, untrackedFiles = 0, conflictedFiles = 0, localBranches = 3, unpushedCommits = 1, stashes = 0,
            ),
            GitRepoInfo(gitAvailable = false, branch = null, headCommit = "0123456789"),
            GitRepoInfo(gitAvailable = true, branch = "main", headCommit = null, error = "fatal: something"),
        )
        for (info in infos) {
            val dialog = RepoInfoDialog(project, repo, info, RepoLeapBundle.message("info.state.closed"))
            try {
                val content = dialog.createCenterPanelForTest()
                assertTrue(content.componentCount > 0)
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }
}
