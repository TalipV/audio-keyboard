package dev.yazin.keyboard.engine.dict

/** One word-list entry. Not-a-word entries are kept only for the shortcut they carry ("im" → "I'm"). */
class Entry(val word: String, val freq: Int, val isWord: Boolean, val offensive: Boolean) {
    /** Next words with their rank (1 = most likely). */
    val bigrams = ArrayList<Pair<String, Int>>()
    val shortcuts = ArrayList<String>()
}

/** Parser for AOSP "combined" word lists (`  word=…,f=…` lines with indented bigram/shortcut lines). */
object WordList {
    fun parse(lines: Sequence<String>): List<Entry> {
        val out = ArrayList<Entry>()
        var current: Entry? = null
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("word=") -> {
                    val f = fields(trimmed)
                    current = Entry(
                        word = f.getValue("word"),
                        freq = f["f"]?.toIntOrNull() ?: 0,
                        isWord = f["not_a_word"] != "true",
                        offensive = f["possibly_offensive"] == "true",
                    ).also { out += it }
                }
                trimmed.startsWith("bigram=") -> current?.let { e ->
                    val f = fields(trimmed)
                    e.bigrams += f.getValue("bigram") to (f["f"]?.toIntOrNull() ?: 0)
                }
                trimmed.startsWith("shortcut=") -> current?.let { e ->
                    val f = fields(trimmed)
                    if (f["f"] == "whitelist") e.shortcuts += f.getValue("shortcut")
                }
            }
        }
        return out
    }

    private fun fields(s: String): Map<String, String> =
        s.split(',').associate { it.substringBefore('=') to it.substringAfter('=') }
}
