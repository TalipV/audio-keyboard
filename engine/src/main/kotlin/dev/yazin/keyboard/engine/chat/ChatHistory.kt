package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.Tsv
import dev.yazin.keyboard.engine.style.StyleStore
import dev.yazin.keyboard.engine.style.WhatsAppExport
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * One Chat's History (chat-memory spec §4): messages from both sides, oldest first, at most [MAX]. Not thread-safe:
 * `ChatMemory` locks it.
 */
class ChatHistory {
    private val list = ArrayList<Message>()
    /** Every stored message by side and collapsed text, to find the one a new message merges into. */
    private val byKey = HashMap<Key, MutableList<Message>>()
    /** Messages inserted during [addAll], so it can count the ones trimming left. */
    private var fresh: MutableSet<Message>? = null

    val messages: List<Message> get() = list.toList()
    val size: Int get() = list.size

    /**
     * Stores [m] unless it must never be stored (§4.3) or is already here (§4.5), then drops the oldest past [MAX].
     * Returns whether anything changed. A guessed time meeting an exact one takes the exact time.
     */
    fun add(m: Message): Boolean {
        val text = m.text.trim()
        val key = Key(m.side, collapse(text))
        if (key.text.isEmpty() || text.length > StyleStore.MAX_CHARS || StyleStore.DIGITS.containsMatchIn(key.text)) return false
        val same = byKey[key]
        val match = same?.firstOrNull { !it.guessed && matches(it, m, key) } ?: same?.firstOrNull { matches(it, m, key) }
        if (match != null) return merge(match, m, key)
        val msg = if (text == m.text) m else m.copy(text = text)
        insert(msg, key)
        fresh?.add(msg)
        return trim() !== msg
    }

    /** Adds [ms] in order. Returns how many were new and are still here after trimming. */
    fun addAll(ms: List<Message>): Int {
        val added = Collections.newSetFromMap(IdentityHashMap<Message, Boolean>())
        fresh = added
        try {
            ms.forEach(::add)
        } finally {
            fresh = null
        }
        return added.size
    }

    /**
     * Up to [n] (their run, the owner's next run) pairs, each run joined with " / " and cut to [RUN_CHARS]. Ranked by
     * words their run shares with [latestTheirs], newest first on ties.
     */
    fun pairs(latestTheirs: String, n: Int = PAIRS): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < list.size) {
            val start = i
            while (i < list.size && list[i].side == Side.THEM) i++
            val reply = i
            while (i < list.size && list[i].side == Side.ME) i++
            if (start < reply && reply < i) found += join(start, reply) to join(reply, i)
        }
        val wanted = StyleStore.words(latestTheirs)
        return found.mapIndexed { idx, p -> Triple(p, StyleStore.words(p.first).count { it in wanted }, idx) }
            .sortedWith(compareByDescending<Triple<Pair<String, String>, Int, Int>> { it.second }.thenByDescending { it.third })
            .take(n)
            .map { it.first }
    }

    /**
     * The [n] messages before [screen]'s first one, oldest first. Its place is the stored message it merges with (§4.5)
     * that the most following screen lines line up after, the newest on ties: a guessed time matches every copy of a
     * repeated line. If none merges, the last [n] that merge with nothing on [screen].
     */
    fun earlier(screen: List<Message>, n: Int = EARLIER): List<Message> {
        val first = screen.firstOrNull()
        if (first != null) {
            val rest = screen.drop(1).map { it to keyOf(it) }
            fun linedUp(c: Int) = rest.indices.count { k ->
                val j = c + 1 + k
                val (s, key) = rest[k]
                j < list.size && keyOf(list[j]) == key && matches(list[j], s, key)
            }
            val start = candidates(first).map(::indexOf).maxWithOrNull(compareBy<Int>(::linedUp).thenBy { it })
            if (start != null) return list.subList(max(0, start - n), start).toList()
        }
        val shown = Collections.newSetFromMap(IdentityHashMap<Message, Boolean>())
        for (s in screen) shown += candidates(s)
        return list.filter { it !in shown }.takeLast(n)
    }

    /** The newest message from someone else on [screen], else in History, else "". */
    fun latestTheirs(screen: List<Message>): String =
        (screen.lastOrNull { it.side == Side.THEM } ?: list.lastOrNull { it.side == Side.THEM })?.text.orEmpty()

    fun write(out: Appendable) {
        for (m in list) {
            out.append("m\t").append(m.side.name).append('\t').append(m.time.toString()).append('\t')
                .append(if (m.guessed) "1" else "0").append('\t').append(m.source.name).append('\t')
                .append(Tsv.escape(m.sender)).append('\t').append(Tsv.escape(m.draft.orEmpty())).append('\t')
                .append(Tsv.escape(m.text)).append('\n')
        }
    }

    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 8 || f[0] != "m") continue // a damaged line
            val side = Side.entries.firstOrNull { it.name == f[1] } ?: continue
            val time = f[2].toLongOrNull() ?: continue
            val guessed = when (f[3]) {
                "1" -> true
                "0" -> false
                else -> continue
            }
            val source = Source.entries.firstOrNull { it.name == f[4] } ?: continue
            add(Message(side, Tsv.unescape(f[5]), Tsv.unescape(f[7]), time, guessed, source, Tsv.unescape(f[6]).ifEmpty { null }))
        }
    }

    private fun join(from: Int, to: Int) = (from until to).joinToString(" / ") { collapse(list[it].text) }.take(RUN_CHARS)

    private fun matches(a: Message, b: Message, key: Key) =
        abs(a.time - b.time) <= DAY || (a.guessed || b.guessed) && key.text.length >= MIN_GUESS_MATCH

    /** The stored messages [m] would merge with. */
    private fun candidates(m: Message): List<Message> {
        val key = keyOf(m)
        return byKey[key]?.filter { matches(it, m, key) }.orEmpty()
    }

    private fun merge(old: Message, m: Message, key: Key): Boolean {
        val merged = old.copy(
            time = if (old.guessed && !m.guessed) m.time else old.time,
            guessed = old.guessed && m.guessed,
            sender = old.sender.ifEmpty { m.sender },
            draft = old.draft ?: m.draft,
        )
        if (merged == old) return false
        list.removeAt(indexOf(old))
        unlink(old, key)
        insert(merged, key)
        fresh?.let { if (it.remove(old)) it += merged }
        return true
    }

    /** After every message with the same or an earlier time, so ties keep their order of arrival. */
    private fun insert(m: Message, key: Key) {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].time <= m.time) lo = mid + 1 else hi = mid
        }
        list.add(lo, m)
        byKey.getOrPut(key) { ArrayList(1) } += m
    }

    private fun indexOf(m: Message): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].time < m.time) lo = mid + 1 else hi = mid
        }
        while (list[lo] !== m) lo++
        return lo
    }

    private fun unlink(m: Message, key: Key) {
        val same = byKey.getValue(key)
        same.removeAt(same.indexOfFirst { it === m })
        if (same.isEmpty()) byKey.remove(key)
    }

    /** Drops the oldest message past [MAX] and returns it. */
    private fun trim(): Message? {
        if (list.size <= MAX) return null
        val dropped = list.removeAt(0)
        unlink(dropped, keyOf(dropped))
        fresh?.remove(dropped)
        return dropped
    }

    private data class Key(val side: Side, val text: String)

    private fun keyOf(m: Message) = Key(m.side, collapse(m.text))

    /** Whitespace collapsed as ScreenText does, without the direction and zero-width marks WhatsApp adds. */
    private fun collapse(s: String) = s.replace(WhatsAppExport.INVISIBLE, "").replace(SPACE, " ").trim()

    companion object {
        const val MAX = 5_000
        const val MIN_GUESS_MATCH = 8
        const val PAIRS = 10
        const val EARLIER = 40
        const val RUN_CHARS = 300
        private const val DAY = 86_400_000L
        private val SPACE = Regex("\\s+")
    }
}
