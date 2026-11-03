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
import de.pdenis.repoleap.settings.HighlightStyle
import de.pdenis.repoleap.settings.OpenMode
import de.pdenis.repoleap.settings.RepoLeapConfigurable
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.settings.RowPart
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.ui.MatchHighlight
import de.pdenis.repoleap.ui.RepoLeapPopup
import de.pdenis.repoleap.ui.RepoListCellRenderer
import de.pdenis.repoleap.ui.RepoListItem
import de.pdenis.repoleap.ui.WindowState
import com.intellij.ui.JBColor
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

    fun testBranchPathAndWindowStateCanBeHidden() {
        val entry = RepoEntry("repo", Path.of("/repos/repo"), "/repos/repo", branch = "develop")
        val item = RepoListItem(entry, RepoMatcher.Result(0, emptyList(), emptyList()), 0, windowState = WindowState.OTHER)
        fun texts(renderer: RepoListCellRenderer): List<String> {
            val iterator = (renderer.getListCellRendererComponent(JBList(), item, 0, false, false) as SimpleColoredComponent).iterator()
            return buildList { while (iterator.hasNext()) add(iterator.next().trim()) }.filter { it.isNotEmpty() }
        }
        val tag = "\u25CF " + RepoLeapBundle.message("popup.tag.open")
        assertEquals(listOf("repo", "develop", "/repos/repo", tag), texts(RepoListCellRenderer()))
        assertEquals(listOf("repo", "/repos/repo"), texts(RepoListCellRenderer(showBranch = false, showWindowState = false)))
        assertEquals(listOf("repo", "develop", tag), texts(RepoListCellRenderer(showPath = false)))
    }

    fun testTagsAreShownWithTheMatchedPrefixMarked() {
        val entry = RepoEntry("repo", Path.of("/repos/repo"), "/repos/repo")
        val item = RepoListItem(entry, RepoMatcher.Result(0, emptyList(), emptyList()), 0, tags = listOf("work", "java"), tagMatches = listOf(2, 0))
        fun fragments(renderer: RepoListCellRenderer): List<Pair<String, SimpleTextAttributes>> {
            val iterator = (renderer.getListCellRendererComponent(JBList(), item, 0, false, false) as SimpleColoredComponent).iterator()
            return buildList { while (iterator.hasNext()) add(iterator.next().trim() to iterator.textAttributes) }.filter { it.first.isNotEmpty() }
        }
        val shown = fragments(RepoListCellRenderer(showPath = false))
        assertEquals(listOf("repo", "#wo", "rk", "#java"), shown.map { it.first })
        assertEquals("#wo is marked in the highlight color", MatchHighlight.DEFAULT_COLOR, shown[1].second.fgColor)
        assertEquals(RepoListCellRenderer.TAG.fgColor, shown[2].second.fgColor)
        assertEquals(listOf("repo"), fragments(RepoListCellRenderer(showPath = false, showTags = false)).map { it.first })
    }

    fun testPartsOfARowAreJoinedWithTheSeparator() {
        val entry = RepoEntry("repo", Path.of("/repos/repo"), "/repos/repo", branch = "develop")
        val item = RepoListItem(
            entry, RepoMatcher.Result(0, emptyList(), emptyList()), 0,
            windowState = WindowState.OTHER, tags = listOf("work", "java"),
        )
        val tag = "\u25CF " + RepoLeapBundle.message("popup.tag.open")
        fun fragments(renderer: RepoListCellRenderer): List<Pair<String, SimpleTextAttributes>> {
            val iterator = (renderer.getListCellRendererComponent(JBList(), item, 0, false, false) as SimpleColoredComponent).iterator()
            return buildList { while (iterator.hasNext()) add(iterator.next() to iterator.textAttributes) }
        }
        fun line(renderer: RepoListCellRenderer) = fragments(renderer).joinToString("") { it.first }

        assertEquals("spaces only, as before", "repo  develop  #work #java  /repos/repo   $tag", line(RepoListCellRenderer()))
        assertEquals("repo / develop / #work #java / /repos/repo / $tag", line(RepoListCellRenderer(separator = "/")))
        assertEquals("repo \u00b7 develop \u00b7 /repos/repo", line(RepoListCellRenderer(showTags = false, showWindowState = false, separator = " \u00b7 ")))
        assertEquals("hidden parts leave no separator behind", "repo / /repos/repo", line(RepoListCellRenderer(showBranch = false, showTags = false, showWindowState = false, separator = "/")))
        assertEquals("repo", line(RepoListCellRenderer(showBranch = false, showTags = false, showPath = false, showWindowState = false, separator = "/")))

        val separators = fragments(RepoListCellRenderer(separator = "/")).filter { it.first == "/" && it.second == SimpleTextAttributes.GRAYED_ATTRIBUTES }
        assertEquals("one separator between each of the five parts, in grey", 4, separators.size)

        val renderer = RepoListCellRenderer(separator = "  //  ")
        assertEquals("white space around it is dropped, the length is limited", "//", renderer.separator)
        renderer.separator = "abcdef"
        assertEquals("abc", renderer.separator)
    }

    fun testPartsFollowTheChosenOrderAndTheNameStaysFirst() {
        val entry = RepoEntry("repo", Path.of("/repos/repo"), "/repos/repo", branch = "develop")
        val item = RepoListItem(
            entry, RepoMatcher.Result(0, emptyList(), emptyList()), 0,
            windowState = WindowState.OTHER, tags = listOf("work"),
        )
        val tag = "\u25CF " + RepoLeapBundle.message("popup.tag.open")
        fun line(renderer: RepoListCellRenderer): String {
            val iterator = (renderer.getListCellRendererComponent(JBList(), item, 0, false, false) as SimpleColoredComponent).iterator()
            return buildList { while (iterator.hasNext()) add(iterator.next()) }.joinToString("")
        }

        assertEquals("default order", "repo / develop / #work / /repos/repo / $tag", line(RepoListCellRenderer(separator = "/")))
        assertEquals(
            "repo / $tag / /repos/repo / #work / develop",
            line(RepoListCellRenderer(separator = "/", order = listOf(RowPart.WINDOW_STATE, RowPart.PATH, RowPart.TAGS, RowPart.BRANCH))),
        )
        assertEquals(
            "without a separator every part keeps its gap",
            "repo   $tag  /repos/repo  develop  #work",
            line(RepoListCellRenderer(order = listOf(RowPart.WINDOW_STATE, RowPart.PATH, RowPart.BRANCH, RowPart.TAGS))),
        )
        assertEquals(
            "switched off parts are skipped wherever they are",
            "repo / develop",
            line(RepoListCellRenderer(showPath = false, showTags = false, showWindowState = false, separator = "/", order = listOf(RowPart.PATH, RowPart.BRANCH))),
        )
    }

    fun testPreviewFollowsTheShowNextToTheNameCheckboxes() {
        val state = RepoLeapSettings.getInstance().state
        val before = listOf(state.showBranch, state.showTags, state.showPath, state.showWindowState, state.rowSeparator)
        val configurable = RepoLeapConfigurable()
        try {
            state.showBranch = true
            state.showTags = true
            state.showPath = true
            state.showWindowState = true
            state.rowSeparator = "/"
            configurable.createComponent()
            configurable.reset()
            val open = "\u25CF " + RepoLeapBundle.message("popup.tag.open")
            val all = "shop-customer-api / main / #work #java / ~/dev/shop-customer-api / $open"
            assertEquals(all, configurable.previewTextForTest())

            configurable.setShownForTest(RowPart.BRANCH, false)
            assertEquals("shop-customer-api / #work #java / ~/dev/shop-customer-api / $open", configurable.previewTextForTest())
            assertTrue("not applied yet, but modified", configurable.isModified)
            assertTrue("the saved setting is untouched until Apply", state.showBranch)

            configurable.setShownForTest(RowPart.PATH, false)
            configurable.setShownForTest(RowPart.TAGS, false)
            assertEquals("shop-customer-api / $open", configurable.previewTextForTest())

            configurable.setShownForTest(RowPart.WINDOW_STATE, false)
            assertEquals("only the name is left, no separator", "shop-customer-api", configurable.previewTextForTest())

            configurable.setShownForTest(RowPart.PATH, true)
            assertEquals("shop-customer-api / ~/dev/shop-customer-api", configurable.previewTextForTest())

            configurable.apply()
            assertEquals(listOf(false, false, true, false), listOf(state.showBranch, state.showTags, state.showPath, state.showWindowState))
            assertFalse(configurable.isModified)

            configurable.setShownForTest(RowPart.BRANCH, true)
            assertEquals("shop-customer-api / main / ~/dev/shop-customer-api", configurable.previewTextForTest())
            configurable.reset()
            assertEquals("Reset: the preview goes back to the saved choice", "shop-customer-api / ~/dev/shop-customer-api", configurable.previewTextForTest())
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            state.showBranch = before[0] as Boolean
            state.showTags = before[1] as Boolean
            state.showPath = before[2] as Boolean
            state.showWindowState = before[3] as Boolean
            state.rowSeparator = before[4] as String
        }
    }

    fun testPopupUsesTheOrderFromTheSettings() {
        val settings = RepoLeapSettings.getInstance()
        val before = settings.state.rowOrder
        try {
            settings.state.rowOrder = RowPart.format(listOf(RowPart.PATH, RowPart.WINDOW_STATE, RowPart.BRANCH, RowPart.TAGS))
            val popup = RepoLeapPopup(project)
            try {
                popup.show()
                assertEquals(listOf(RowPart.PATH, RowPart.WINDOW_STATE, RowPart.BRANCH, RowPart.TAGS), popup.rendererForTest().order)
            } finally {
                popup.closeForTest()
            }
        } finally {
            settings.state.rowOrder = before
        }
    }

    fun testOrderIsChosenOnTheSettingsPage() {
        val settings = RepoLeapSettings.getInstance()
        val before = settings.state.rowOrder
        val configurable = RepoLeapConfigurable()
        try {
            settings.state.rowOrder = RowPart.format(RowPart.DEFAULT_ORDER)
            configurable.createComponent()
            configurable.reset()
            assertEquals(listOf(RowPart.BRANCH, RowPart.TAGS, RowPart.PATH, RowPart.WINDOW_STATE), configurable.orderForTest())
            assertFalse(configurable.isModified)

            configurable.moveOrderForTest(2, 0) // what the up arrow does for "Path" at row 3
            assertEquals(listOf(RowPart.PATH, RowPart.TAGS, RowPart.BRANCH, RowPart.WINDOW_STATE).let { it[0] to it[2] }, configurable.orderForTest().let { it[0] to it[2] })
            assertEquals("the preview follows", configurable.orderForTest(), configurable.previewOrderForTest())
            assertTrue(configurable.isModified)

            configurable.apply()
            assertEquals(configurable.orderForTest(), RowPart.parse(settings.state.rowOrder))
            assertFalse(configurable.isModified)

            configurable.moveOrderForTest(0, 3)
            assertTrue(configurable.isModified)
            configurable.reset()
            assertEquals("Reset brings back the saved order", RowPart.parse(settings.state.rowOrder), configurable.orderForTest())
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.state.rowOrder = before
        }
    }

    fun testSeparatorFieldAcceptsAtMostThreeCharacters() {
        val configurable = RepoLeapConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            val field = configurable.separatorFieldForTest()

            configurable.setSeparatorForTest("abcdef")
            assertEquals("typed or pasted text is cut off", "abc", field.text)

            configurable.setSeparatorForTest("ab")
            field.document.insertString(2, "cdef", null)
            assertEquals("pasted at the end: only what fits", "abc", field.text)

            field.document.insertString(0, "x", null)
            assertEquals("full: nothing more is accepted", "abc", field.text)

            field.document.remove(1, 1)
            field.document.insertString(1, "yzzz", null)
            assertEquals("room is made by deleting", "ayc", field.text)

            configurable.setSeparatorForTest(" / ")
            assertEquals("three characters incl. spaces are fine", " / ", field.text)
            assertEquals("/", configurable.previewSeparatorForTest())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testSeparatorIsKeptAsEnteredOnTheSettingsPage() {
        val settings = RepoLeapSettings.getInstance()
        val before = settings.state.rowSeparator
        val configurable = RepoLeapConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            assertEquals("", settings.state.rowSeparator)
            assertFalse(configurable.isModified)

            configurable.setSeparatorForTest(" / ")
            assertEquals("the preview follows what is typed", "/", configurable.previewSeparatorForTest())
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals("/", settings.state.rowSeparator)
            assertFalse("not modified after Apply although the field had spaces", configurable.isModified)

            configurable.setSeparatorForTest("")
            assertTrue(configurable.isModified)
            configurable.reset()
            assertEquals("Reset brings back the saved one", "/", settings.state.rowSeparator)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.state.rowSeparator = before
        }
    }

    fun testPopupUsesTheSeparatorFromTheSettings() {
        val settings = RepoLeapSettings.getInstance()
        val before = settings.state.rowSeparator
        try {
            settings.state.rowSeparator = "|"
            val popup = RepoLeapPopup(project)
            try {
                popup.show()
                assertEquals("|", popup.rendererForTest().separator)
            } finally {
                popup.closeForTest()
            }
        } finally {
            settings.state.rowSeparator = before
        }
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

    fun testMatchesAreMarkedAsConfigured() {
        val entry = RepoEntry("customer-api", Path.of("/dev/customer-api"), "~/dev/customer-api")
        val match = RepoMatcher.match(RepoMatcher.tokenize("api dev"), entry.name, entry.displayPath)!!
        val item = RepoListItem(entry, match, 0)

        /** the attributes of the fragments "api" (name) and "dev" (path) */
        fun marked(highlight: MatchHighlight, selected: Boolean = false, list: JBList<RepoListItem> = JBList()): Pair<SimpleTextAttributes, SimpleTextAttributes> {
            val renderer = RepoListCellRenderer(showBranch = false, showWindowState = false, highlight = highlight)
            val iterator = (renderer.getListCellRendererComponent(list, item, 0, selected, false) as SimpleColoredComponent).iterator()
            val fragments = buildMap { while (iterator.hasNext()) put(iterator.next(), iterator.textAttributes) }
            return fragments.getValue("api") to fragments.getValue("dev")
        }

        assertEquals("colored bold text is the default", HighlightStyle.TEXT_COLOR, RepoLeapSettings.State().highlightStyle)
        assertEquals(HighlightStyle.TEXT_COLOR, MatchHighlight.DEFAULT.style)
        val (name, path) = marked(MatchHighlight.DEFAULT)
        assertEquals(MatchHighlight.DEFAULT_COLOR, name.fgColor)
        assertEquals(MatchHighlight.DEFAULT_COLOR, path.fgColor)
        assertTrue(name.style and SimpleTextAttributes.STYLE_BOLD != 0)
        assertFalse(name.isSearchMatch)

        val (themeName, themePath) = marked(MatchHighlight(HighlightStyle.BACKGROUND))
        assertTrue("the theme's search match", themeName.isSearchMatch && themePath.isSearchMatch)
        assertEquals(SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor, themePath.fgColor)

        val own = Color(0x112233)
        val (bgName, bgPath) = marked(MatchHighlight(HighlightStyle.BACKGROUND, own))
        assertEquals(own, bgName.bgColor)
        assertEquals(own, bgPath.bgColor)
        assertFalse(bgName.isSearchMatch)
        assertEquals("own background is kept on the selected row", own, marked(MatchHighlight(HighlightStyle.BACKGROUND, own), selected = true).first.bgColor)

        val (textName, textPath) = marked(MatchHighlight(HighlightStyle.TEXT_COLOR, own))
        assertEquals(own, textName.fgColor)
        assertEquals(own, textPath.fgColor)
        assertTrue(textPath.style and SimpleTextAttributes.STYLE_BOLD != 0)

        assertTrue(marked(MatchHighlight(HighlightStyle.UNDERLINE, own)).first.isUnderline)
        val (wavedName, wavedPath) = marked(MatchHighlight(HighlightStyle.WAVED, own))
        assertTrue(wavedName.isWaved)
        assertEquals(own, wavedName.waveColor)
        assertEquals("the text keeps its grey", SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor, wavedPath.fgColor)
        assertEquals(own, marked(MatchHighlight(HighlightStyle.WAVED, own), selected = true).first.waveColor)
    }

    fun testColoredMatchesStayVisibleOnTheSelectedRow() {
        val entry = RepoEntry("customer-api", Path.of("/dev/customer-api"), "~/dev/customer-api")
        val match = RepoMatcher.match(RepoMatcher.tokenize("api"), entry.name, entry.displayPath)!!
        val item = RepoListItem(entry, match, 0)
        val orange = Color(0xC25A0A)

        fun apiOnSelectedRow(selectionBackground: Color): SimpleTextAttributes {
            val list = JBList<RepoListItem>().apply {
                this.selectionBackground = selectionBackground
                selectionForeground = Color.WHITE
            }
            val renderer = RepoListCellRenderer(showBranch = false, showPath = false, showWindowState = false, highlight = MatchHighlight(HighlightStyle.TEXT_COLOR, orange))
            val iterator = (renderer.getListCellRendererComponent(list, item, 0, true, false) as SimpleColoredComponent).iterator()
            val fragments = buildMap { while (iterator.hasNext()) put(iterator.next(), iterator.textAttributes) }
            return fragments.getValue("api")
        }

        val onLight = apiOnSelectedRow(Color(0xD5E1FF))
        assertEquals("readable on a light selection: keeps its color", orange, onLight.fgColor)
        assertFalse(onLight.isUnderline)

        val onStrongBlue = apiOnSelectedRow(Color(0x2675BF))
        assertEquals("not readable: the selection color", Color.WHITE, onStrongBlue.fgColor)
        assertTrue("so the match is underlined to stay visible", onStrongBlue.isUnderline)
    }

    fun testHighlightIsReadFromTheSettings() {
        val state = RepoLeapSettings.State()
        assertEquals(MatchHighlight.DEFAULT, MatchHighlight.fromSettings(state))
        state.highlightStyle = HighlightStyle.UNDERLINE
        state.customHighlightColor = true
        state.highlightColorLight = 0x102030
        state.highlightColorDark = 0x405060
        val highlight = MatchHighlight.fromSettings(state)
        assertEquals(HighlightStyle.UNDERLINE, highlight.style)
        val color = highlight.customColor as JBColor
        assertEquals(0x102030, color.rgb and 0xFFFFFF) // light theme in tests
    }

    fun testSettingsPageCanBeCreatedAndIsNotModifiedAfterReset() {
        withRoots(listOf("/some/folder")) {
            val configurable = RepoLeapConfigurable()
            try {
                assertNotNull(configurable.createComponent())
                configurable.reset()
                assertFalse(configurable.isModified)
                assertEquals(MatchHighlight.fromSettings(), configurable.previewHighlightForTest())
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
