package dev.yazin.keyboard.engine.voice

/**
 * A minimal JSON reader for the server's replies: objects → Map, arrays → List, numbers → Double.
 * Anything malformed (including a reply cut off mid-way) is an [IllegalArgumentException].
 * ponytail: enough for Speaches' and llama-server's small replies; swap for a real parser if more endpoints arrive.
 */
object Json {
    fun parse(s: String): Any? = Reader(s).run {
        val v = value()
        ws()
        require(i == s.length) { "trailing data at $i" }
        v
    }

    /** [s] as a JSON string literal, for request bodies. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private class Reader(val s: String) {
        var i = 0

        fun peek(): Char {
            require(i < s.length) { "unexpected end" }
            return s[i]
        }

        fun next(): Char = peek().also { i++ }

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble() // NumberFormatException is an IllegalArgumentException
        }

        fun str(): String {
            next() // opening quote
            val b = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = next()) {
                        'n' -> b.append('\n')
                        't' -> b.append('\t')
                        'r' -> b.append('\r')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'u' -> {
                            require(i + 4 <= s.length) { "short \\u escape" }
                            b.append(s.substring(i, i + 4).toInt(16).toChar()) // surrogate pairs arrive as two escapes
                            i += 4
                        }
                        else -> b.append(e) // \" \\ \/
                    }
                    else -> b.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            next()
            val out = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("expected , or ] at ${i - 1}")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            next()
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                require(peek() == '"') { "expected a key at $i" }
                val k = str()
                ws()
                require(next() == ':') { "expected : at ${i - 1}" }
                out[k] = value()
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("expected , or } at ${i - 1}")
                }
            }
        }
    }
}
