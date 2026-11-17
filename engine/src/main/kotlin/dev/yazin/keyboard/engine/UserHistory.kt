package dev.yazin.keyboard.engine

import java.io.Reader
import java.io.Writer
import kotlin.math.ln
import kotlin.math.pow

/**
 * What the keyboard has learned on this device for one language: word and word-pair counts with a 60-day
 * half-life, words the user added explicitly, and words they removed (never suggested again).
 * Stored as a small tab-separated file by the app. Thread-safe.
 */
class UserHistory(private val now: () -> Long = System::currentTimeMillis) {
    private class Stat(var count: Int, var day: Long)

    private val words = HashMap<String, Stat>()
    private val pairs = HashMap<String, Stat>()
    private val added = HashSet<String>()
    private val blocked = HashSet<String>()

    @Volatile var dirty = false
        private set
    private var knownCache: List<String>? = null

    private fun changed() {
        dirty = true
        knownCache = null
    }

    @Synchronized fun learn(word: String, previous: String?) {
        if (word.isBlank() || word in blocked) return
        val day = today()
        words.getOrPut(word) { Stat(0, day) }.apply { count++; this.day = day }
        if (previous != null && previous !in blocked) pairs.getOrPut(previous + SEP + word) { Stat(0, day) }.apply { count++; this.day = day }
        changed()
        trim()
    }

    @Synchronized fun add(word: String) {
        added += word
        blocked -= word
        changed()
    }

    @Synchronized fun remove(word: String) {
        added -= word
        words -= word
        pairs.keys.removeAll { it.startsWith(word + SEP) || it.endsWith(SEP + word) }
        blocked += word
        changed()
    }

    @Synchronized fun clear() {
        words.clear(); pairs.clear(); added.clear(); blocked.clear()
        changed()
    }

    @Synchronized fun isBlocked(word: String): Boolean = word in blocked

    /** The user's own word: added explicitly, or typed at least twice. */
    @Synchronized fun isKnown(word: String): Boolean = word in added || (words[word]?.count ?: 0) >= 2

    /** 0 for words never typed; grows with recent use. */
    @Synchronized fun score(word: String): Float = words[word]?.let { ln(1f + decayed(it).toFloat()) } ?: 0f

    @Synchronized fun pairScore(previous: String, word: String): Float =
        pairs[previous + SEP + word]?.let { ln(1f + decayed(it).toFloat()) } ?: 0f

    /** Cached until something changes: the suggester asks on every keystroke. */
    @Synchronized fun knownWords(): List<String> =
        knownCache ?: (added + words.filterValues { it.count >= 2 }.keys).toList().also { knownCache = it }

    /** Words the user followed [previous] with at least twice, most used first. */
    @Synchronized fun nextWords(previous: String): List<String> {
        val prefix = previous + SEP
        return pairs.entries.filter { it.key.startsWith(prefix) && it.value.count >= 2 }
            .sortedByDescending { decayed(it.value) }.map { it.key.substring(prefix.length) }
    }

    /**
     * Whisper's `hotwords` (spec §7.5): the added words first, then the learned ones by recent use, the order the
     * suggester ranks them, comma-separated (the server follows that better than spaces) and cut at [maxChars].
     * Learned words for which [known] is true (dictionary words Whisper already gets right) are left out.
     */
    @Synchronized fun hotwords(maxChars: Int = 200, known: (String) -> Boolean = { false }): String? {
        val learned = words.filter { it.key !in added && it.value.count >= 2 && !known(it.key) }.entries.sortedByDescending { decayed(it.value) }.map { it.key }
        var len = 0
        return (added.sorted() + learned).takeWhile { len += it.length + 2; len - 2 <= maxChars }.joinToString(", ").ifEmpty { null }
    }

    /** For the personal-dictionary screen. */
    @Synchronized fun userWords(): List<String> = knownWords().sorted()

    @Synchronized fun write(out: Writer) {
        for ((w, s) in words) out.write("w\t$w\t${s.count}\t${s.day}\n")
        for ((p, s) in pairs) out.write("p\t${p.substringBefore(SEP)}\t${p.substringAfter(SEP)}\t${s.count}\t${s.day}\n")
        for (w in added) out.write("a\t$w\n")
        for (w in blocked) out.write("b\t$w\n")
        out.flush()
        dirty = false
    }

    @Synchronized fun read(input: Reader) {
        input.buffered().forEachLine { line ->
            val f = line.split('\t')
            // A damaged line (e.g. a torn write) is skipped rather than failing the whole file.
            when (f[0]) {
                "w" -> if (f.size == 4) stat(f[2], f[3])?.let { words[f[1]] = it }
                "p" -> if (f.size == 5) stat(f[3], f[4])?.let { pairs[f[1] + SEP + f[2]] = it }
                "a" -> if (f.size == 2) added += f[1]
                "b" -> if (f.size == 2) blocked += f[1]
            }
        }
        knownCache = null
    }

    private fun stat(count: String, day: String): Stat? {
        val c = count.toIntOrNull() ?: return null
        val d = day.toLongOrNull() ?: return null
        return Stat(c, d)
    }

    private fun decayed(s: Stat): Double = s.count * 0.5.pow((today() - s.day) / HALF_LIFE_DAYS)

    private fun today(): Long = now() / 86_400_000

    // ponytail: drops the least-used 10% when over the cap; an LRU structure if this ever shows in profiles.
    private fun trim() {
        if (words.size > MAX_WORDS) words.entries.sortedBy { decayed(it.value) }.take(MAX_WORDS / 10).map { it.key }.forEach { words.remove(it) }
        if (pairs.size > MAX_PAIRS) pairs.entries.sortedBy { decayed(it.value) }.take(MAX_PAIRS / 10).map { it.key }.forEach { pairs.remove(it) }
    }

    private companion object {
        const val SEP = '\u0001'
        const val HALF_LIFE_DAYS = 60.0
        const val MAX_WORDS = 20_000
        const val MAX_PAIRS = 50_000
    }
}
