package de.pdenis.repoleap

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.repoleap.actions.OpenRepositoryAction
import de.pdenis.repoleap.open.RepoOpener
import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.settings.OpenMode
import de.pdenis.repoleap.settings.RepoLeapConfigurable
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.ui.RepoLeapPopup
import de.pdenis.repoleap.ui.RepoListCellRenderer
import de.pdenis.repoleap.ui.RepoListItem
import de.pdenis.repoleap.ui.WindowState
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.KeyStroke

/** Runs inside a headless IDE (plugin.xml, services, keymaps, settings UI). */
class RepoLeapPlatformTest : BasePlatformTestCase() {

    private val actionId = "de.pdenis.repoleap.OpenRepository"

    private val macKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
    private val defaultKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)

    /** Accepted overlaps: Mercurial "Push" (Mercurial plugin, bundled until 2025.x) uses Alt+Shift+P as well. */
    private val acceptedConflicts = setOf("hg4idea.QPushAction")

    fun testActionIsRegisteredWithDefaultShortcuts() {
        assertInstanceOf(ActionManager.getInstance().getAction(actionId), OpenRepositoryAction::class.java)

        val keymaps = KeymapManager.getInstance()
        val mac = keymaps.getKeymap("Mac OS X 10.5+")!!
        assertEquals(listOf(KeyboardShortcut(macKeyStroke, null)), mac.getShortcuts(actionId).toList())
        val default = keymaps.getKeymap(KeymapManager.DEFAULT_IDEA_KEYMAP)!!
        assertEquals(listOf(KeyboardShortcut(defaultKeyStroke, null)), default.getShortcuts(actionId).toList())
    }

    fun testDefaultShortcutsDoNotConflictWithOtherActions() {
        val keymaps = KeymapManager.getInstance()
        val checks = mapOf(
            "Mac OS X 10.5+" to macKeyStroke,
            KeymapManager.DEFAULT_IDEA_KEYMAP to defaultKeyStroke,
        )
        for ((keymapName, keyStroke) in checks) {
            val actionIds = keymaps.getKeymap(keymapName)!!.getActionIds(keyStroke).toList() - acceptedConflicts
            assertEquals("Actions bound to $keyStroke in $keymapName", listOf(actionId), actionIds)
        }
    }

    fun testMacKeymapsDoNotGetTheWindowsShortcut() {
        val macKeymaps = KeymapManagerEx.getInstanceEx().allKeymaps.filter { keymap ->
            generateSequence(keymap) { it.parent }.any { it.name == "Mac OS X" || it.name == "Mac OS X 10.5+" }
        }
        assertNotEmpty(macKeymaps)
        for (keymap in macKeymaps) {
            val shortcuts = keymap.getShortcuts(actionId).toList()
            assertFalse("${keymap.name}: $shortcuts", KeyboardShortcut(defaultKeyStroke, null) in shortcuts)
        }
    }

    fun testAlreadyOpenRepositoryIsFocusedInsteadOfOpenedAgain() {
        val basePath = Path.of(project.basePath!!)
        Files.createDirectories(basePath)
        assertSame(project, RepoOpener.findOpenProject(basePath))
        assertNull(RepoOpener.findOpenProject(FileUtil.createTempDirectory("not-open", null).toPath()))

        val settings = RepoLeapSettings.getInstance()
        val recentBefore = settings.state.recentRepos.toMutableList()
        val openBefore = ProjectManager.getInstance().openProjects.toList()
        try {
            val entry = RepoEntry(basePath.fileName.toString(), basePath, basePath.toString())
            for (mode in OpenMode.entries) {
                RepoOpener.open(entry, mode, project)
                assertEquals("mode $mode", openBefore, ProjectManager.getInstance().openProjects.toList())
            }
        } finally {
            settings.state.recentRepos = recentBefore
        }
    }

    fun testCurrentAndOpenRepositoriesAreTaggedInDifferentColors() {
        val current = RepoEntry("current", Path.of("/repos/current"), "/repos/current")
        val open = RepoEntry("open", Path.of("/repos/open"), "/repos/open")
        val closed = RepoEntry("closed", Path.of("/repos/closed"), "/repos/closed")
        val renderer = RepoListCellRenderer()
        val windowStates = mapOf(current to WindowState.CURRENT, open to WindowState.OTHER, closed to WindowState.CLOSED)

        /** text fragment -> its attributes, as painted for [entry] */
        fun fragments(entry: RepoEntry, list: JBList<RepoListItem> = JBList(), selected: Boolean = false): Map<String, SimpleTextAttributes> {
            val item = RepoListItem(entry, RepoMatcher.Result(0, emptyList(), emptyList()), 0, windowState = windowStates.getValue(entry))
            val component = renderer.getListCellRendererComponent(list, item, 0, selected, false)
            val iterator = (component as SimpleColoredComponent).iterator()
            val result = LinkedHashMap<String, SimpleTextAttributes>()
            while (iterator.hasNext()) result[iterator.next().trim()] = iterator.textAttributes
            return result
        }

        fun tag(entry: RepoEntry) = fragments(entry).entries.singleOrNull { it.key.startsWith("\u25CF") }

        val currentTag = tag(current)!!
        val openTag = tag(open)!!
        assertEquals("\u25CF " + RepoLeapBundle.message("popup.tag.current"), currentTag.key)
        assertEquals("\u25CF " + RepoLeapBundle.message("popup.tag.open"), openTag.key)
        assertEquals(RepoListCellRenderer.CURRENT_WINDOW_TAG.fgColor, currentTag.value.fgColor)
        assertEquals(RepoListCellRenderer.OPEN_TAG.fgColor, openTag.value.fgColor)
        assertFalse(currentTag.value.fgColor == openTag.value.fgColor)
        assertNull(tag(closed))

        // Selected row: the tag keeps its color on a light selection, but falls back to the selection color
        // where it would be hard to read (strong blue selection of classic themes)
        fun selectedTagColor(selectionBackground: Color): Color? {
            val list = JBList<RepoListItem>().apply {
                this.selectionBackground = selectionBackground
                selectionForeground = Color.WHITE
            }
            return fragments(open, list, selected = true).entries.single { it.key.startsWith("\u25CF") }.value.fgColor
        }
        assertEquals(RepoListCellRenderer.OPEN_TAG.fgColor, selectedTagColor(Color(0xD5E1FF)))
        assertEquals(Color.WHITE, selectedTagColor(Color(0x2675BF)))
    }

    fun testIndexServiceScansConfiguredRootsInBackground() {
        val root = FileUtil.createTempDirectory("repoleap", null).toPath()
        Files.createDirectories(root.resolve("group/my-repo/.git"))
        Files.createDirectories(root.resolve("other-repo/.git"))

        withRoots(listOf(root.toString())) {
            val index = RepoIndexService.getInstance()
            var notified = 0
            val listenerScope = Disposer.newDisposable()
            try {
                index.addListener(listenerScope) { notified++ }
                index.invalidate()
                assertTrue(index.isScanning)
                PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)

                assertEquals(listOf("my-repo", "other-repo"), index.repositories!!.map { it.name })
                assertEquals(1, notified)
            } finally {
                Disposer.dispose(listenerScope)
            }
        }
    }

    fun testRecentRepositoriesAreLimitedAndNewestFirst() {
        val settings = RepoLeapSettings.getInstance()
        val before = settings.state.recentRepos.toMutableList()
        try {
            repeat(RepoLeapSettings.MAX_RECENT + 5) { settings.markRecentlyOpened("/repo/$it") }
            settings.markRecentlyOpened("/repo/3")
            val recent = settings.recentRepos
            assertEquals(RepoLeapSettings.MAX_RECENT, recent.size)
            assertEquals("/repo/3", recent.first())
            assertEquals(1, recent.count { it == "/repo/3" })
        } finally {
            settings.state.recentRepos = before
        }
    }

    fun testSettingsPageCanBeCreatedAndIsNotModifiedAfterReset() {
        withRoots(listOf("/some/folder")) {
            val configurable = RepoLeapConfigurable()
            try {
                assertNotNull(configurable.createComponent())
                configurable.reset()
                assertFalse(configurable.isModified)
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    fun testPopupFiltersAndRanksRepositories() {
        val root = FileUtil.createTempDirectory("repoleap-popup", null).toPath()
        listOf("platform/customer-api", "platform/billing-service", "tools/api-gateway", "tools/dotfiles").forEach {
            Files.createDirectories(root.resolve("$it/.git"))
        }

        withRoots(listOf(root.toString())) {
            val index = RepoIndexService.getInstance()
            index.invalidate()
            PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)

            val popup = RepoLeapPopup(project)
            try {
                popup.show()
                PlatformTestUtil.waitWithEventsDispatching("Popup scan did not finish", { !index.isScanning }, 20)
                assertEquals(listOf("api-gateway", "billing-service", "customer-api", "dotfiles"), popup.visibleRepoNamesForTest())

                popup.setSearchTextForTest("api")
                assertEquals(listOf("api-gateway", "customer-api"), popup.visibleRepoNamesForTest())

                popup.setSearchTextForTest("platform api")
                assertEquals(listOf("customer-api"), popup.visibleRepoNamesForTest())

                popup.setSearchTextForTest("bilserv")
                assertEquals(listOf("billing-service"), popup.visibleRepoNamesForTest())

                popup.setSearchTextForTest("does-not-exist")
                assertEmpty(popup.visibleRepoNamesForTest())
                assertEquals(RepoLeapBundle.message("popup.empty.noMatch"), popup.emptyTextForTest())
            } finally {
                popup.closeForTest()
            }
        }
    }

    fun testPopupExplainsMissingConfiguration() {
        withRoots(emptyList()) {
            RepoIndexService.getInstance().invalidate()
            val popup = RepoLeapPopup(project)
            try {
                popup.show()
                assertEquals(RepoLeapBundle.message("popup.empty.noRoots"), popup.emptyTextForTest())
            } finally {
                popup.closeForTest()
            }
        }
    }

    private fun withRoots(roots: List<String>, block: () -> Unit) {
        val state = RepoLeapSettings.getInstance().state
        val before = state.roots.toMutableList()
        state.roots = roots.toMutableList()
        try {
            block()
        } finally {
            state.roots = before
        }
    }
}
