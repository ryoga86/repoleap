package de.pdenis.repoleap.search

/**
 * Free-text matcher for repositories.
 *
 * The query is split at whitespace into tokens; **every** token has to match either the repository name
 * or its path (in any order). Per token the best of these strategies is used:
 * 1. substring match (preferring exact, prefix and word-boundary matches) - name and path
 * 2. fuzzy subsequence match (characters in order, e.g. `scapi` -> `shop-customer-api`) - name only
 *
 * Name matches are weighted higher than path matches. The result contains the matched character ranges
 * for highlighting. Pure Kotlin, no IntelliJ dependencies.
 */
object RepoMatcher {

    data class Result(
        val score: Int,
        /** Matched ranges in the name (inclusive). */
        val nameRanges: List<IntRange>,
        /** Matched ranges in the path (inclusive). */
        val pathRanges: List<IntRange>,
    )

    private data class TokenMatch(val score: Int, val ranges: List<IntRange>)

    private val EMPTY = Result(0, emptyList(), emptyList())
    private val WHITESPACE = Regex("\\s+")

    fun tokenize(query: String): List<String> =
        query.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { lower(it) }

    /** Returns `null` if at least one token does not match. */
    fun match(tokens: List<String>, name: String, path: String): Result? {
        if (tokens.isEmpty()) return EMPTY
        val lowerName = lower(name)
        val lowerPath = lower(path)

        var total = 0
        val nameRanges = ArrayList<IntRange>()
        val pathRanges = ArrayList<IntRange>()
        for (token in tokens) {
            val inName = substringMatch(token, name, lowerName) ?: fuzzyMatch(token, name, lowerName)
            // Paths are long; fuzzy matching them would match almost anything -> substring only
            val inPath = substringMatch(token, path, lowerPath)
            val nameScore = inName?.score ?: -1
            val pathScore = inPath?.let { it.score * PATH_WEIGHT_PERCENT / 100 } ?: -1
            when {
                inName == null && inPath == null -> return null
                nameScore >= pathScore -> {
                    total += nameScore
                    nameRanges += inName!!.ranges
                }
                else -> {
                    total += pathScore
                    pathRanges += inPath!!.ranges
                }
            }
        }
        // Slight preference for shorter names (more specific hits)
        total -= name.length.coerceAtMost(MAX_LENGTH_PENALTY)
        return Result(total, merge(nameRanges), merge(pathRanges))
    }

    private fun substringMatch(token: String, original: String, lower: String): TokenMatch? {
        var best: TokenMatch? = null
        var index = lower.indexOf(token)
        while (index >= 0) {
            var score = SUBSTRING
            if (index == 0) score += PREFIX
            if (isWordStart(original, index)) score += WORD_START
            if (index == 0 && token.length == lower.length) score += EXACT
            // An occurrence that ends at a word end is a "whole word" match
            if (isWordEnd(original, index + token.length - 1)) score += WORD_END
            score += token.length * CHAR
            score -= index.coerceAtMost(MAX_POSITION_PENALTY)
            if (best == null || score > best.score) best = TokenMatch(score, listOf(index until index + token.length))
            index = lower.indexOf(token, index + 1)
        }
        return best
    }

    /**
     * fzf-v1-like: find the earliest end position of an in-order match, then walk backwards to find the
     * shortest window containing the token, and score the characters matched inside that window.
     */
    private fun fuzzyMatch(token: String, original: String, lower: String): TokenMatch? {
        if (token.length < 2) return null

        // forward pass
        var t = 0
        var end = -1
        for (i in lower.indices) {
            if (lower[i] == token[t]) {
                t++
                if (t == token.length) {
                    end = i
                    break
                }
            }
        }
        if (end < 0) return null

        // backward pass -> shortest window
        t = token.length - 1
        var start = end
        for (i in end downTo 0) {
            if (lower[i] == token[t]) {
                t--
                if (t < 0) {
                    start = i
                    break
                }
            }
        }

        // collect the matched positions inside the window
        val positions = IntArray(token.length)
        t = 0
        var i = start
        while (i <= end && t < token.length) {
            if (lower[i] == token[t]) {
                positions[t] = i
                t++
            }
            i++
        }
        if (t < token.length) return null

        var score = FUZZY
        var previous = -2
        for (p in positions) {
            score += CHAR
            if (p == previous + 1) score += CONSECUTIVE
            if (isWordStart(original, p)) score += FUZZY_WORD_START
            previous = p
        }
        val gaps = (end - start + 1) - token.length
        score -= (gaps * GAP_PENALTY).coerceAtMost(MAX_GAP_PENALTY)
        score -= start.coerceAtMost(MAX_POSITION_PENALTY)

        return TokenMatch(score, merge(positions.map { it..it }))
    }

    private fun isWordStart(text: String, index: Int): Boolean {
        if (index <= 0) return true
        val previous = text[index - 1]
        val current = text[index]
        return isSeparator(previous) ||
            (previous.isLowerCase() && current.isUpperCase()) ||
            (previous.isLetter() && current.isDigit()) ||
            (previous.isDigit() && current.isLetter())
    }

    private fun isWordEnd(text: String, index: Int): Boolean =
        index >= text.length - 1 || isSeparator(text[index + 1]) ||
            (text[index].isLowerCase() && text[index + 1].isUpperCase())

    private fun isSeparator(c: Char) = !c.isLetterOrDigit()

    /** Lower-cases char by char so that indices stay identical to the original string. */
    private fun lower(text: String): String {
        val chars = CharArray(text.length) { text[it].lowercaseChar() }
        return String(chars)
    }

    internal fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val result = ArrayList<IntRange>(sorted.size)
        var current = sorted[0]
        for (r in sorted.drop(1)) {
            current = if (r.first <= current.last + 1) {
                current.first..maxOf(current.last, r.last)
            } else {
                result += current
                r
            }
        }
        result += current
        return result
    }

    // --- scoring constants -------------------------------------------------------------------------------
    private const val SUBSTRING = 1000
    private const val PREFIX = 400
    private const val WORD_START = 300
    private const val WORD_END = 100
    private const val EXACT = 1000
    private const val FUZZY = 100
    private const val FUZZY_WORD_START = 40
    private const val CONSECUTIVE = 25
    private const val CHAR = 10
    private const val GAP_PENALTY = 3
    private const val MAX_GAP_PENALTY = 90
    private const val MAX_POSITION_PENALTY = 60
    private const val MAX_LENGTH_PENALTY = 40
    private const val PATH_WEIGHT_PERCENT = 60
}
