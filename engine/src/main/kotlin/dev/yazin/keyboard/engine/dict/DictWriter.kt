package dev.yazin.keyboard.engine.dict

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.TreeMap

/** Builds the binary dictionary described in [DictFormat]. */
object DictWriter {
    private class Node(val char: Char) {
        val children = TreeMap<Char, Node>()
        var freq = -1
        var offensive = false
        var shortcut: String? = null
        var bigrams: List<Pair<String, Int>> = emptyList()
        var index = 0
    }

    /** Keeps the [maxWords] most frequent real words; entries carrying a shortcut are always kept. */
    fun build(entries: List<Entry>, maxWords: Int = Int.MAX_VALUE): ByteArray {
        val words = entries.filter { it.isWord }.sortedByDescending { it.freq }.take(maxWords).toHashSet()
        val root = Node('\u0000')
        for (e in entries) {
            val asWord = e in words
            if (!asWord && e.shortcuts.isEmpty()) continue
            var n = root
            for (c in e.word) n = n.children.getOrPut(c) { Node(c) }
            if (asWord) {
                n.freq = maxOf(n.freq, e.freq.coerceIn(0, 254))
                n.offensive = n.offensive || e.offensive
                if (e.bigrams.isNotEmpty()) n.bigrams = e.bigrams.sortedBy { it.second }
            }
            if (e.shortcuts.isNotEmpty()) n.shortcut = e.shortcuts.first()
        }
        // Breadth-first numbering keeps each node's children contiguous (and sorted, thanks to TreeMap).
        val order = ArrayList<Node>().apply { add(root) }
        var i = 0
        while (i < order.size) {
            order[i].index = i
            order.addAll(order[i].children.values)
            i++
        }
        val pool = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun intern(s: String): Int = offsets.getOrPut(s) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            require(bytes.size < 256) { "string too long: $s" }
            pool.size().also { pool.write(bytes.size); pool.write(bytes) }
        }
        val bigrams = ArrayList<IntArray>()
        val shortcuts = ArrayList<IntArray>()
        for (n in order) {
            for ((w, rank) in n.bigrams) bigrams += intArrayOf(n.index, intern(w), rank.coerceIn(0, 255))
            n.shortcut?.let { shortcuts += intArrayOf(n.index, intern(it)) }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { d ->
            d.writeInt(DictFormat.MAGIC)
            d.writeInt(DictFormat.VERSION)
            d.writeInt(order.size)
            d.writeInt(bigrams.size)
            d.writeInt(shortcuts.size)
            d.writeInt(pool.size())
            d.writeInt(0)
            d.writeInt(0)
            for (n in order) {
                require(n.children.size < 128) { "node '${n.char}' has ${n.children.size} children" }
                d.writeChar(n.char.code)
                d.writeInt(if (n.children.isEmpty()) 0 else n.children.firstEntry().value.index)
                d.writeByte(n.children.size or if (n.offensive) DictFormat.OFFENSIVE else 0)
                d.writeByte(n.freq + 1)
            }
            for (b in bigrams) { d.writeInt(b[0]); d.writeInt(b[1]); d.writeByte(b[2]) }
            for (s in shortcuts) { d.writeInt(s[0]); d.writeInt(s[1]) }
            pool.writeTo(d)
        }
        return bytes.toByteArray()
    }
}
