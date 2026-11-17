package dev.yazin.keyboard.engine.dict

import java.nio.ByteBuffer

/**
 * Read-only view of a binary dictionary ([DictFormat]), usually memory-mapped.
 * Only absolute reads are used, so one instance can be shared across threads.
 */
class Dictionary(private val buf: ByteBuffer) {
    val nodeCount: Int
    private val bigramCount: Int
    private val shortcutCount: Int
    private val bigrams: Int
    private val shortcuts: Int
    private val pool: Int

    init {
        require(buf.capacity() >= DictFormat.HEADER && buf.getInt(0) == DictFormat.MAGIC && buf.getInt(4) == DictFormat.VERSION) {
            "not a v${DictFormat.VERSION} keyboard dictionary"
        }
        nodeCount = buf.getInt(8)
        bigramCount = buf.getInt(12)
        shortcutCount = buf.getInt(16)
        bigrams = DictFormat.HEADER + nodeCount * DictFormat.NODE
        shortcuts = bigrams + bigramCount * DictFormat.BIGRAM
        pool = shortcuts + shortcutCount * DictFormat.SHORTCUT
    }

    private fun at(node: Int) = DictFormat.HEADER + node * DictFormat.NODE

    fun char(node: Int): Char = buf.getChar(at(node))
    fun firstChild(node: Int): Int = buf.getInt(at(node) + 2)
    fun childCount(node: Int): Int = buf.get(at(node) + 6).toInt() and 0x7F
    fun isOffensive(node: Int): Boolean = buf.get(at(node) + 6).toInt() and DictFormat.OFFENSIVE != 0

    /** Log-scale frequency 0..254 when [node] ends a word, else −1. */
    fun freq(node: Int): Int = (buf.get(at(node) + 7).toInt() and 0xFF) - 1

    fun child(node: Int, c: Char): Int {
        val count = childCount(node)
        if (count == 0) return -1
        var lo = firstChild(node)
        var hi = lo + count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = char(mid)
            when {
                m < c -> lo = mid + 1
                m > c -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The node spelling exactly [word] (it may not be a word itself), or −1. */
    fun find(word: CharSequence): Int {
        var n = 0
        for (c in word) {
            n = child(n, c)
            if (n < 0) return -1
        }
        return n
    }

    /** Most likely next words after the word at [node], best first. */
    fun nextWords(node: Int): List<String> {
        var i = lowerBound(bigrams, DictFormat.BIGRAM, bigramCount, node)
        val out = ArrayList<String>()
        while (i < bigramCount && buf.getInt(bigrams + i * DictFormat.BIGRAM) == node) {
            out += string(buf.getInt(bigrams + i * DictFormat.BIGRAM + 4))
            i++
        }
        return out
    }

    /** The word-list correction for [node] ("im" → "I'm"), if any. */
    fun shortcut(node: Int): String? {
        val i = lowerBound(shortcuts, DictFormat.SHORTCUT, shortcutCount, node)
        if (i >= shortcutCount || buf.getInt(shortcuts + i * DictFormat.SHORTCUT) != node) return null
        return string(buf.getInt(shortcuts + i * DictFormat.SHORTCUT + 4))
    }

    private fun lowerBound(base: Int, size: Int, count: Int, key: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (buf.getInt(base + mid * size) < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun string(offset: Int): String {
        val p = pool + offset
        val len = buf.get(p).toInt() and 0xFF
        val bytes = ByteArray(len) { buf.get(p + 1 + it) }
        return String(bytes, Charsets.UTF_8)
    }
}
