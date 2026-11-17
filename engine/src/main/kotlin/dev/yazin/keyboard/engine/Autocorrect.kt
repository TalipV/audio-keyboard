package dev.yazin.keyboard.engine

/** Whether a typed word is replaced when a separator follows it (spec §6.4). Defaults tuned by the accuracy harness. */
object Autocorrect {
    data class Policy(
        /** Never correct further than this. */
        val maxCost: Float = 4.2f,
        /** Required score lead over the best competing correction, unless the fix is "sure". */
        val minMargin: Float = 1.2f,
        /** At or below this cost (case, accent or hamza only) a same-length fix needs no margin. */
        val sureCost: Float = 0.6f,
        /** Candidates scoring below this (rare words reached by big edits) are not worth replacing what was typed. */
        val minScore: Float = -1f,
    )

    fun decide(s: Suggestions, rejected: Set<String>, policy: Policy = Policy()): String? {
        val typed = s.typed
        if (typed.isEmpty() || typed.any { it.isDigit() } || rejected.any { it.equals(typed, ignoreCase = true) }) return null
        val first = s.candidates.firstOrNull() ?: return null
        if (first.shortcut) return first.word.takeIf { it != typed }
        if (s.typedIsWord || typed.length < 2) return null
        val folded = Fold.word(typed)
        val eligible = s.candidates.filter { isCorrection(it, typed, folded) }
        val top = eligible.firstOrNull() ?: return null
        if (top.word == typed || top.cost > policy.maxCost || top.score < policy.minScore) return null
        // The top word's own longer forms ("strange" → "stranger") are not rival corrections.
        val topFolded = Fold.word(top.word)
        val rival = eligible.firstOrNull { it !== top && !Fold.word(it.word).startsWith(topFolded) }
        val sure = top.length == folded.length && top.cost <= policy.sureCost
        val clear = rival == null || top.score - rival.score >= policy.minMargin
        return if (sure || clear) top.word else null
    }

    /**
     * A correction, as opposed to a completion or inflection of what was typed ("snippet" → "snippets",
     * "harms" → "harm's"). Gboard offers those in the strip but never swaps them in.
     */
    private fun isCorrection(c: Candidate, typed: String, typedFolded: String): Boolean {
        val f = Fold.word(c.word)
        // Apostrophes the user typed (players', 'quoted') are kept: only add missing ones (dont → don't).
        if (f == typedFolded && apostrophes(c.word) < apostrophes(typed)) return false
        if (c.length > typedFolded.length + 1) return false
        if (f != typedFolded && f.startsWith(typedFolded)) return false
        if ((c.word.endsWith("'s") || c.word.endsWith("’s")) && f == typedFolded) return false
        // Arabic attaches و ب ل ك ف س to words; a leading one is a real form the dictionary lacks, not a typo.
        if (typedFolded.length > 3 && typedFolded[0] in CLITICS) {
            val rest = typedFolded.substring(1)
            if (f == rest || (typedFolded[0] == 'ل' && f == "ا$rest")) return false
        }
        return true
    }

    private fun apostrophes(s: String) = s.count { it == '\'' || it == '’' }

    private const val CLITICS = "وبلكفس"
}
