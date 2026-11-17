package dev.yazin.keyboard.engine

/** Escaping for files with one tab-separated record per line: tabs, newlines and backslashes inside a field. */
object Tsv {
    fun escape(s: String) = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i++]
            if (ch != '\\' || i == s.length) {
                append(ch)
                continue
            }
            when (val e = s[i++]) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> append(e)
            }
        }
    }
}
