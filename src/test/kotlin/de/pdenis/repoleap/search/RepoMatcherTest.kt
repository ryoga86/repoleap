package de.pdenis.repoleap.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoMatcherTest {

    private fun match(query: String, name: String, path: String = "~/git/$name") =
        RepoMatcher.match(RepoMatcher.tokenize(query), name, path)

    /** Names ordered by score for [query]; non-matching names are dropped. */
    private fun rank(query: String, vararg names: String): List<String> =
        names.mapNotNull { name -> match(query, name)?.let { name to it.score } }
            .sortedByDescending { it.second }
            .map { it.first }

    @Test
    fun `empty query matches everything`() {
        val result = match("   ", "anything")
        assertNotNull(result)
        assertEquals(0, result!!.score)
        assertTrue(result.nameRanges.isEmpty())
    }

    @Test
    fun `substring match is case insensitive and highlighted`() {
        val result = match("CUSTOMER", "shop-customer-api")!!
        assertEquals(listOf(5..12), result.nameRanges)
    }

    @Test
    fun `all tokens must match in any order`() {
        assertNotNull(match("api customer", "shop-customer-api"))
        assertNull(match("api billing", "shop-customer-api"))
    }

    @Test
    fun `tokens can match the path`() {
        val result = match("platform api", "customer-api", "~/git/platform/customer-api")!!
        assertEquals(listOf(9..11), result.nameRanges)
        assertEquals(listOf(6..13), result.pathRanges)
    }

    @Test
    fun `fuzzy subsequence matches the name`() {
        val result = match("scapi", "shop-customer-api")
        assertNotNull(result)
        assertEquals(listOf(0..0, 5..5, 14..16), result!!.nameRanges)
    }

    @Test
    fun `path is not matched fuzzily`() {
        assertNull(match("xyz", "repo", "~/x/y/z/repo"))
    }

    @Test
    fun `exact beats prefix beats word start beats infix beats fuzzy`() {
        val ranked = rank("api", "api", "api-gateway", "customer-api", "rapid", "a-p-i")
        assertEquals(listOf("api", "api-gateway", "customer-api", "rapid", "a-p-i"), ranked)
    }

    @Test
    fun `camel case boundaries count as word starts`() {
        val camel = match("service", "MyServiceTool")!!.score
        val infix = match("service", "myservicetool")!!.score
        assertTrue(camel > infix)
    }

    @Test
    fun `name matches beat path matches`() {
        val nameHit = match("infra", "infra-tools", "~/git/other/infra-tools")!!.score
        val pathHit = match("infra", "tools", "~/git/infra/tools")!!.score
        assertTrue(nameHit > pathHit)
    }

    @Test
    fun `merge joins adjacent and overlapping ranges`() {
        assertEquals(listOf(0..4, 7..8), RepoMatcher.merge(listOf(3..4, 0..2, 7..7, 8..8, 1..3)))
    }
}
