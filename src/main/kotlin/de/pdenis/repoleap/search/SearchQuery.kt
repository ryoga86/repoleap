package de.pdenis.repoleap.search

/**
 * The search text split into free text (for [RepoMatcher]) and tag filters: words starting with `#`.
 *
 * Every tag filter is a prefix (`#wo` finds `work`) that has to match at least one tag of a repository;
 * several filters must all match. A lone `#` is ignored. Pure Kotlin.
 */
data class SearchQuery(
    /** Lower case, see [RepoMatcher.tokenize]. */
    val textTokens: List<String>,
    /** Lower case, without the `#`. */
    val tagPrefixes: List<String>,
) {
    val isEmpty: Boolean get() = textTokens.isEmpty() && tagPrefixes.isEmpty()

    /**
     * Per tag of [tags] (same order) the length of the matched prefix (0 = not matched),
     * or `null` if a filter matches none of the tags.
     */
    fun matchTags(tags: List<String>): List<Int>? {
        if (tagPrefixes.isEmpty()) return List(tags.size) { 0 }
        val lengths = IntArray(tags.size)
        for (prefix in tagPrefixes) {
            var found = false
            tags.forEachIndexed { index, tag ->
                if (tag.startsWith(prefix, ignoreCase = true)) {
                    found = true
                    lengths[index] = maxOf(lengths[index], prefix.length)
                }
            }
            if (!found) return null
        }
        return lengths.toList()
    }

    companion object {
        private const val TAG_MARKER = '#'

        fun parse(text: String): SearchQuery {
            val (tagWords, words) = RepoMatcher.tokenize(text).partition { it.startsWith(TAG_MARKER) }
            return SearchQuery(words, tagWords.map { it.trimStart(TAG_MARKER) }.filter { it.isNotEmpty() })
        }
    }
}

/** Tag names: as typed, without `#`, no white space or commas, unique ignoring case. Pure Kotlin. */
object Tags {

    private val SEPARATORS = Regex("[\\s,]+")

    /** `"#work, java  #Work"` -> `[work, java]` */
    fun parse(input: String): List<String> =
        input.split(SEPARATORS)
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() && '#' !in it }
            .distinctBy { it.lowercase() }

    /** `[work, java]` -> `"#work #java"` */
    fun format(tags: List<String>): String = tags.joinToString(" ") { "#$it" }
}
