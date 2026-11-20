package dev.yazin.keyboard.engine.text

import dev.yazin.keyboard.engine.Tsv

/** A text shortcut, like Samsung's: typing [trigger] offers [text] in the suggestion strip ("mfg" → "Mit freundlichen Grüßen"). */
data class Shortcut(val trigger: String, val text: String)

/**
 * The owner's text shortcuts. Offered, never typed for them: the strip shows [matches] while the word being typed is a
 * trigger (any case), and a tap puts the text in. One trigger may have several texts. Not thread-safe; main thread.
 */
class Shortcuts {
    private val items = ArrayList<Shortcut>()

    var dirty = false
        private set

    /** Every shortcut, by trigger. */
    fun all(): List<Shortcut> = items.sortedWith(compareBy({ it.trigger.lowercase() }, { it.text }))

    /** The texts for the word being typed, oldest first (at most [MAX_OFFERED], the strip's room). */
    fun matches(typed: String): List<String> {
        if (typed.isEmpty()) return emptyList()
        val t = same(typed)
        return items.filter { same(it.trigger) == t }.map { it.text }.distinct().take(MAX_OFFERED)
    }

    /**
     * Whether [typed] is a shortcut, or typing [next] after it heads into one ("talip" + "." for "talip."): autocorrect
     * must leave such a word alone, or the shortcut can't be reached.
     */
    fun protects(typed: String, next: String): Boolean {
        if (typed.isEmpty()) return false
        val t = same(typed)
        val ahead = same(typed + next)
        return items.any { same(it.trigger).let { k -> k == t || (next.isNotEmpty() && k.startsWith(ahead)) } }
    }

    /** Adds a shortcut, or changes [old] into it. False when the trigger isn't one typed word or the text is empty. */
    fun put(trigger: String, text: String, old: Shortcut? = null): Boolean {
        val t = trigger.trim()
        if (!isTrigger(t) || text.isBlank() || text.length > MAX_TEXT) return false
        if (old != null) items.remove(old)
        val i = items.indexOfFirst { same(it.trigger) == same(t) && it.text == text }
        if (i >= 0) items[i] = Shortcut(t, text) else items += Shortcut(t, text) // the same again keeps its place
        dirty = true
        return true
    }

    fun remove(s: Shortcut) {
        if (items.remove(s)) dirty = true
    }

    fun clear() {
        items.clear()
        dirty = true
    }

    fun write(out: Appendable) {
        for (s in items) out.append("s\t").append(Tsv.escape(s.trigger)).append('\t').append(Tsv.escape(s.text)).append('\n')
        dirty = false
    }

    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 3 || f[0] != "s") continue // a damaged line
            val trigger = Tsv.unescape(f[1])
            val text = Tsv.unescape(f[2])
            if (isTrigger(trigger) && text.isNotBlank()) items += Shortcut(trigger, text)
        }
    }

    /**
     * A file the owner wrote or exported: one shortcut per line, the trigger then a tab (or " = ") then the text;
     * "\n" in the text is a line break. Returns how many were added.
     */
    fun import(lines: Sequence<String>): Int {
        var n = 0
        for (line in lines) {
            val (trigger, text) = when {
                '\t' in line -> line.substringBefore('\t') to line.substringAfter('\t')
                " = " in line -> line.substringBefore(" = ") to line.substringAfter(" = ")
                else -> continue
            }
            if (put(trigger, Tsv.unescape(text.trim()))) n++
        }
        return n
    }

    /** The lines [import] reads back. */
    fun export(out: Appendable) {
        for (s in all()) out.append(s.trigger).append('\t').append(Tsv.escape(s.text)).append('\n')
    }

    companion object {
        /** How triggers compare: any case, and ’ ‘ ` ´ as ' (keyboards and autocorrect swap them: "'alaykum"). */
        private fun same(s: String): String = s.lowercase().map { if (it in "’‘`´ʼ") '\'' else it }.joinToString("")

        const val MAX_OFFERED = 2
        const val MAX_TEXT = 5_000

        /**
         * A trigger is what is typed since the last space: no spaces, but punctuation is fine ("mfg", "017", "+49",
         * "e-mail").
         */
        fun isTrigger(s: String): Boolean = s.isNotEmpty() && s.length <= 30 && s.none { it.isWhitespace() }
    }
}
