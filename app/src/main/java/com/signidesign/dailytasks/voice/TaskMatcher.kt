package com.signidesign.dailytasks.voice

import com.signidesign.dailytasks.data.TaskEntity

/**
 * Resolves a spoken fragment ("the Delta deck", "invoice Nestle") to a task.
 *
 * Recognition rarely returns a title verbatim — words get dropped, brand
 * names get mangled — so matching is fuzzy, but it refuses rather than
 * guesses below [THRESHOLD], and reports ambiguity when the top two
 * candidates are too close to separate. Acting on the wrong task is the one
 * failure mode worth paying latency and extra prompts to avoid.
 */
object TaskMatcher {

    private const val THRESHOLD = 0.42
    private const val AMBIGUITY_MARGIN = 0.08

    private val stopWords = setOf(
        "the", "a", "an", "my", "to", "for", "of", "task", "item", "about",
        "please", "off", "as", "that", "this", "and", "with", "on", "at", "it"
    )

    sealed interface Match {
        data class One(val task: TaskEntity) : Match
        data class Several(val tasks: List<TaskEntity>) : Match
        data object None : Match
    }

    fun match(query: String, candidates: List<TaskEntity>): Match {
        if (candidates.isEmpty()) return Match.None
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return Match.None

        val scored = candidates
            .map { it to score(queryTokens, it.title) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }

        if (scored.isEmpty()) return Match.None
        val best = scored.first()
        val tied = scored.filter { best.second - it.second <= AMBIGUITY_MARGIN }
        return if (tied.size > 1) Match.Several(tied.take(3).map { it.first })
        else Match.One(best.first)
    }

    private fun score(queryTokens: List<String>, title: String): Double {
        val titleTokens = tokenize(title)
        if (titleTokens.isEmpty()) return 0.0

        val normalizedQuery = queryTokens.joinToString(" ")
        val normalizedTitle = titleTokens.joinToString(" ")
        if (normalizedQuery == normalizedTitle) return 1.0
        if (normalizedTitle.contains(normalizedQuery)) {
            // Partial name of a longer task: strong, but shorter fragments of
            // very long titles are weaker evidence.
            return 0.80 + 0.15 * (normalizedQuery.length.toDouble() / normalizedTitle.length)
        }
        if (normalizedQuery.contains(normalizedTitle)) return 0.85

        // Coverage: how much of what was said is accounted for by the title.
        val matched = queryTokens.count { q -> titleTokens.any { tokensAlike(q, it) } }
        val coverage = matched.toDouble() / queryTokens.size
        // Precision: how much of the title was named. Keeps a one-word query
        // from matching every task that happens to share that word.
        val named = titleTokens.count { t -> queryTokens.any { tokensAlike(t, it) } }
        val precision = named.toDouble() / titleTokens.size

        return 0.72 * coverage + 0.28 * precision
    }

    private fun tokensAlike(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4) {
            if (a.startsWith(b) || b.startsWith(a)) return true
            val longer = maxOf(a.length, b.length)
            if (editDistance(a, b) <= if (longer >= 7) 2 else 1) return true
        }
        return false
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in stopWords }

    /** Iterative Levenshtein over two short strings; rows only, no matrix. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
