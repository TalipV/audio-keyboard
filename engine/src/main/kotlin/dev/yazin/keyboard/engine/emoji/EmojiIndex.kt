package dev.yazin.keyboard.engine.emoji

import dev.yazin.keyboard.engine.Fold

/**
 * Gboard's "Search emoji" over the catalog and its CLDR words in every language, compared through [Fold] so case,
 * accents and hamza don't matter. Ranked: the name equal to the query, then a keyword equal to it, then a keyword or
 * name starting with it, then a word of the name starting with it, then a substring anywhere; ties keep catalog
 * order. A query of several words also matches, last, when each of them starts one of the emoji's words.
 */
class EmojiIndex(groups: List<EmojiGroup>, words: List<Map<String, EmojiWords>>) {
    private class Entry(val emoji: Emoji, val names: List<String>, val phrases: List<String>, val nameWords: List<String>, val allWords: List<String>)

    private val entries: List<Entry> = groups.flatMap { it.emoji }.mapNotNull { e ->
        val ws = words.mapNotNull { it[e.base] }
        if (ws.isEmpty()) return@mapNotNull null
        val names = ws.map { Fold.word(it.name) }.filter { it.isNotEmpty() }
        val phrases = (names + ws.flatMap { w -> w.keywords.map(Fold::word) }).distinct()
        Entry(e, names, phrases, names.flatMap(::split).distinct(), phrases.flatMap(::split).distinct())
    }

    /** At most [limit] emoji matching [query], best first; none for a blank query. */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<Emoji> {
        val q = Fold.word(query).trim().replace(SPACES, " ")
        if (q.isEmpty()) return emptyList()
        val tokens = split(q)
        val ranked = ArrayList<Pair<Int, Int>>()
        for ((i, e) in entries.withIndex()) rank(e, q, tokens).let { if (it >= 0) ranked += it to i }
        ranked.sortWith(compareBy({ it.first }, { it.second }))
        return ranked.take(limit).map { entries[it.second].emoji }
    }

    private fun rank(e: Entry, q: String, tokens: List<String>): Int = when {
        e.names.any { it == q } -> 0
        e.phrases.any { it == q } -> 1
        e.phrases.any { it.startsWith(q) } -> 2
        e.nameWords.any { it.startsWith(q) } -> 3
        e.phrases.any { it.contains(q) } -> 4
        tokens.size > 1 && tokens.all { t -> e.allWords.any { it.startsWith(t) } } -> 5
        else -> -1
    }

    private companion object {
        val SPACES = Regex("\\s+")
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun split(s: String): List<String> = s.split(NON_WORD).filter { it.isNotEmpty() }
    }
}
