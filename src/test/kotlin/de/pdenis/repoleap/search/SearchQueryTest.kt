package de.pdenis.repoleap.search

import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.settings.RepoTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `words with a hash are tag filters, the rest is free text`() {
        assertEquals(SearchQuery(listOf("shop", "api"), listOf("work")), SearchQuery.parse("  shop #Work api "))
        assertEquals("a lone # is ignored", SearchQuery(emptyList(), emptyList()), SearchQuery.parse("#"))
        assertTrue(SearchQuery.parse(" # ").isEmpty)
        assertEquals(listOf("java"), SearchQuery.parse("##java").tagPrefixes)
    }

    @Test
    fun `tag filters are prefixes and must all match`() {
        val tags = listOf("work", "Java", "workshop")
        assertEquals("no filter: everything, nothing marked", listOf(0, 0, 0), SearchQuery.parse("api").matchTags(tags))
        assertEquals(listOf(2, 0, 2), SearchQuery.parse("#wo").matchTags(tags))
        assertEquals("case does not matter", listOf(0, 4, 0), SearchQuery.parse("#JAVA").matchTags(tags))
        assertEquals(listOf(4, 1, 4), SearchQuery.parse("#work #j").matchTags(tags))
        assertNull(SearchQuery.parse("#work #python").matchTags(tags))
        assertNull(SearchQuery.parse("#x").matchTags(emptyList()))
        assertEquals(emptyList<Int>(), SearchQuery.parse("").matchTags(emptyList()))
    }

    @Test
    fun `tags are cleaned up when entered`() {
        assertEquals(listOf("work", "java"), Tags.parse("#work, java  #Work ,,"))
        assertEquals("a hash inside a word is no tag", listOf("ok"), Tags.parse("a#b ok"))
        assertEquals("#work #java", Tags.format(listOf("work", "java")))
        assertEquals("", Tags.format(emptyList()))
    }

    @Test
    fun `tags are counted, renamed and removed across repositories`() {
        val entries = listOf(RepoTags("/a", "work java"), RepoTags("/b", "Work"), RepoTags("/c", "java"))
        assertEquals(mapOf("java" to 2, "work" to 2), RepoLeapSettings.countTags(entries))

        val renamed = RepoLeapSettings.renameTag(entries, "WORK", "job")
        assertEquals(listOf(RepoTags("/a", "job java"), RepoTags("/b", "job"), RepoTags("/c", "java")), renamed)

        assertEquals("renaming onto an existing tag merges", listOf(RepoTags("/a", "java"), RepoTags("/b", "java"), RepoTags("/c", "java")),
            RepoLeapSettings.renameTag(entries, "work", "java"))
        assertEquals("removing drops repositories without tags", listOf(RepoTags("/a", "java"), RepoTags("/c", "java")),
            RepoLeapSettings.renameTag(entries, "work", ""))
    }
}
