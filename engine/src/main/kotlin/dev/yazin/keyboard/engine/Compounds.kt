package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.Dictionary

/**
 * German writes compounds as one word ("Fahrradschlossschlüssel"), so most of them are missing from any word list.
 * A typed word that splits into known words, with or without a linking element (Fugen-s, -n, -en …), is treated as
 * a real word rather than a typo. Only frequent parts count, so typos rarely split by accident.
 */
object Compounds {
    /** Linking elements between parts, longest first so "-en" is tried before "-e". */
    private val LINKS = listOf("ens", "es", "en", "er", "ns", "s", "n", "e", "")
    private const val MIN_HEAD = 3
    private const val MIN_TAIL = 4
    private const val MIN_LENGTH = 7
    private const val MAX_PARTS = 3

    /** Dictionary frequency (0..254) a part needs; ≈ the 60k most frequent German forms. */
    const val MIN_FREQ = 60

    fun isCompound(dict: Dictionary, word: String): Boolean {
        if (word.length < MIN_LENGTH || !word.all { it.isLetter() }) return false
        return splits(dict, word.lowercase(), 1)
    }

    private fun splits(dict: Dictionary, w: String, parts: Int): Boolean {
        for (i in MIN_HEAD..w.length - MIN_TAIL) {
            if (!known(dict, w.substring(0, i))) continue
            val rest = w.substring(i)
            for (link in LINKS) {
                if (!rest.startsWith(link)) continue
                val tail = rest.substring(link.length)
                if (tail.length < MIN_TAIL) continue
                if (known(dict, tail) || (parts + 1 < MAX_PARTS && splits(dict, tail, parts + 1))) return true
            }
        }
        return false
    }

    /** Whether [part] (lowercase) is a frequent word, as written or capitalised ("schloss" → "Schloss"). */
    private fun known(dict: Dictionary, part: String): Boolean =
        freq(dict, part) >= MIN_FREQ || freq(dict, part.replaceFirstChar { it.uppercaseChar() }) >= MIN_FREQ

    private fun freq(dict: Dictionary, s: String): Int {
        val node = dict.find(s)
        return if (node >= 0) dict.freq(node) else -1
    }
}
