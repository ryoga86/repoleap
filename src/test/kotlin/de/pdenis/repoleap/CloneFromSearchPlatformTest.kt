package de.pdenis.repoleap

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import de.pdenis.repoleap.open.RepoCloner
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.scan.RepoScanner
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.ui.RepoLeapPopup
import de.pdenis.repoleap.ui.RepoListCellRenderer
import de.pdenis.repoleap.ui.RepoListItem
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path

/** A git URL pasted into the search: the repositories cloned from it, and where it can be cloned to. */
class CloneFromSearchPlatformTest : BasePlatformTestCase() {

    private fun withPopup(roots: List<Path>, block: (RepoLeapPopup) -> Unit) {
        val state = RepoLeapSettings.getInstance().state
        val before = state.roots.toMutableList()
        state.roots = roots.map { it.toString() }.toMutableList()
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
            state.roots = before
        }
    }

    private fun repo(root: Path, name: String, remote: String? = null): Path {
        val repo = root.resolve(name)
        Files.createDirectories(repo.resolve(".git"))
        if (remote != null) Files.writeString(repo.resolve(".git/config"), "[remote \"origin\"]\n\turl = $remote\n")
        return repo
    }

    private fun texts(item: RepoListItem): List<String> {
        val component = RepoListCellRenderer().getListCellRendererComponent(JBList(), item, 0, false, false) as SimpleColoredComponent
        val iterator = component.iterator()
        return buildList { while (iterator.hasNext()) add(iterator.next().trim()) }.filter { it.isNotEmpty() }
    }

    fun testPastedUrlFindsTheCloneAndOffersTheRootFolders() {
        val work = FileUtil.createTempDirectory("repoleap-work", null).toPath()
        val other = FileUtil.createTempDirectory("repoleap-other", null).toPath()
        repo(work, "shop", "git@github.com:acme/shop.git")
        repo(work, "unrelated", "git@github.com:acme/unrelated.git")

        withPopup(listOf(work, other)) { popup ->
            popup.setSearchTextForTest("https://github.com/Acme/shop")
            val items = popup.visibleItemsForTest()
            assertEquals(listOf("shop", "shop", "shop", "shop"), items.map { it.repo.name })
            assertTrue("the existing clone first", items[0].sameRemote && items[0].clone == null)
            assertTrue(texts(items[0]).contains("\u25CF " + RepoLeapBundle.message("popup.tag.sameRemote")))

            val (intoWork, intoOther, elsewhere) = items.drop(1).map { it.clone!! }
            assertEquals(work.resolve("shop"), intoWork.target)
            assertEquals("the folder exists already", RepoLeapBundle.message("clone.exists"), intoWork.problem)
            assertEquals(other.resolve("shop"), intoOther.target)
            assertTrue(intoOther.enabled)
            assertNull(elsewhere.target)
            assertEquals(listOf(RepoLeapBundle.message("clone.row.other"), "shop"), texts(items[3]))
            assertSame(items[0], popup.selectedItemForTest())

            popup.setSearchTextForTest("git@github.com:acme/new-thing.git")
            val clones = popup.visibleItemsForTest()
            assertTrue(clones.all { it.clone != null })
            assertEquals(listOf(work.resolve("new-thing"), other.resolve("new-thing"), null), clones.map { it.clone!!.target })
            assertEquals("the first usable target is selected", work.resolve("new-thing"), popup.selectedItemForTest()!!.clone!!.target)
            assertEquals(listOf(RepoLeapBundle.message("clone.row.into"), RepoScanner.abbreviateHome(work.resolve("new-thing").toString())), texts(clones[0]))

            popup.pressKeyForTest(KeyEvent.VK_TAB)
            assertFalse("no actions for a folder that does not exist", popup.isShowingActionsForTest())

            popup.setSearchTextForTest("shop")
            assertEquals("normal search again", listOf("shop"), popup.visibleRepoNamesForTest())
        }
    }

    fun testWithoutRootFoldersOnlyAnotherFolderIsOffered() {
        withPopup(emptyList()) { popup ->
            popup.setSearchTextForTest("git@github.com:acme/shop.git")
            assertEquals(listOf(null), popup.visibleItemsForTest().map { it.clone!!.target })
        }
    }

    fun testClonesOutsideTheRootFoldersAreDetected() {
        val roots = listOf("/dev", "/work/")
        assertTrue(RepoCloner.isCovered(Path.of("/dev/shop"), roots, 1))
        assertTrue(RepoCloner.isCovered(Path.of("/dev/a/b/shop"), roots, 3))
        assertFalse("too deep", RepoCloner.isCovered(Path.of("/dev/a/b/shop"), roots, 2))
        assertFalse(RepoCloner.isCovered(Path.of("/elsewhere/shop"), roots, 3))
        assertFalse("the root itself is no repository below it", RepoCloner.isCovered(Path.of("/dev"), roots, 3))
        assertFalse(RepoCloner.isCovered(Path.of("/developer/shop"), roots, 3))
    }
}
