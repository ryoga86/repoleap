package de.pdenis.repoleap.ui

import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.RepoMatcher
import de.pdenis.repoleap.settings.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class RepoListItemOrderTest {

    private fun item(
        name: String,
        score: Int,
        pinned: Boolean = false,
        window: WindowState = WindowState.CLOSED,
        path: String = "/r/$name",
        recent: Int? = null,
    ) = RepoListItem(RepoEntry(name, Path.of(path), path), RepoMatcher.Result(score, emptyList(), emptyList()), score, pinned, window, recent)

    private fun sorted(vararg items: RepoListItem) = items.sortedWith(RepoListItem.ORDER).map { it.repo.name }

    private fun sorted(mode: SortMode, searching: Boolean, vararg items: RepoListItem) =
        items.sortedWith(RepoListItem.order(mode, searching)).map { it.repo.name }

    private val mixed = arrayOf(
        item("zeta", 10, window = WindowState.CURRENT, path = "/a/zeta", recent = 1),
        item("Beta", 30, path = "/c/beta"),
        item("alpha", 20, window = WindowState.OTHER, path = "/b/alpha", recent = 0),
        item("pinned", 0, pinned = true, path = "/z/pinned"),
    )

    @Test
    fun `other modes keep only pinned on top and ignore the windows`() {
        assertEquals(listOf("pinned", "alpha", "Beta", "zeta"), sorted(SortMode.NAME, false, *mixed))
        assertEquals(listOf("pinned", "zeta", "alpha", "Beta"), sorted(SortMode.PATH, false, *mixed))
        assertEquals(listOf("pinned", "alpha", "zeta", "Beta"), sorted(SortMode.RECENT, false, *mixed))
        assertEquals(sorted(*mixed), sorted(SortMode.SMART, false, *mixed))
    }

    @Test
    fun `while searching the match decides first, then the mode`() {
        assertEquals(listOf("pinned", "Beta", "alpha", "zeta"), sorted(SortMode.NAME, true, *mixed))
        assertEquals(
            listOf("a", "b"),
            sorted(SortMode.NAME, true, item("b", 5), item("a", 5)),
        )
    }

    @Test
    fun `groups are never broken by the search score`() {
        val result = sorted(
            item("rest-best-match", 9999),
            item("other-window", 10, window = WindowState.OTHER),
            item("current-window", 1, window = WindowState.CURRENT),
            item("pinned-closed", 0, pinned = true),
            item("pinned-other", 0, pinned = true, window = WindowState.OTHER),
            item("pinned-current", -50, pinned = true, window = WindowState.CURRENT),
        )
        assertEquals(
            listOf("pinned-current", "pinned-other", "pinned-closed", "current-window", "other-window", "rest-best-match"),
            result,
        )
    }

    @Test
    fun `inside a group the score decides, then the name`() {
        val result = sorted(
            item("b-weak", 10, window = WindowState.OTHER),
            item("a-weak", 10, window = WindowState.OTHER),
            item("strong", 500, window = WindowState.OTHER),
            item("rest-z", 5),
            item("rest-a", 5),
        )
        assertEquals(listOf("strong", "a-weak", "b-weak", "rest-a", "rest-z"), result)
    }
}
