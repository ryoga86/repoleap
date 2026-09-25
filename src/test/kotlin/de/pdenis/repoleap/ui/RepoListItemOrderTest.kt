package de.pdenis.repoleap.ui

import de.pdenis.repoleap.scan.RepoEntry
import de.pdenis.repoleap.search.RepoMatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class RepoListItemOrderTest {

    private fun item(name: String, score: Int, pinned: Boolean = false, window: WindowState = WindowState.CLOSED) =
        RepoListItem(RepoEntry(name, Path.of("/r/$name"), "/r/$name"), RepoMatcher.Result(score, emptyList(), emptyList()), score, pinned, window)

    private fun sorted(vararg items: RepoListItem) = items.sortedWith(RepoListItem.ORDER).map { it.repo.name }

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
