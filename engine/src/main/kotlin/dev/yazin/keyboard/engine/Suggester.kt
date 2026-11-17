package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.Dictionary

/**
 * Tuning knobs, calibrated by the accuracy harness (docs/engine/accuracy.md).
 * Costs are in nats: 0 is a perfect match, about 2–3 is one plausible slip.
 */
data class Params(
    /** Touch spread, in key pitches. */
    val sigma: Float = 0.50f,
    val omit: Float = 2.6f,
    val extra: Float = 2.6f,
    val transpose: Float = 2.2f,
    /** Per letter past the end of what was typed (completions). */
    val complete: Float = 0.25f,
    val far: Float = 4.5f,
    val apostrophe: Float = 0.2f,
    val maxCostBase: Float = 2.2f,
    val maxCostPerChar: Float = 0.45f,
    val maxCostCap: Float = 4.6f,
    val maxCompletion: Int = 6,
    val freqWeight: Float = 6f,
    val bigramWeight: Float = 1.5f,
    val historyWeight: Float = 1.2f,
    val shortcutBonus: Float = 3f,
    /** Dictionary-equivalent frequency of a learned out-of-dictionary word. */
    val learnedFreq: Int = 150,
    val poolSize: Int = 24,
)

data class Candidate(
    val word: String,
    val score: Float,
    /** Cost of turning the typed input into this word (0 = exact). */
    val cost: Float,
    /** Folded length (no marks or apostrophes), for telling completions from corrections. */
    val length: Int,
    val shortcut: Boolean = false,
    val learned: Boolean = false,
)

data class Suggestions(val typed: String, val candidates: List<Candidate>, val typedIsWord: Boolean)

/**
 * How likely [word] is, given what it cost to reach it from the input: frequency, the dictionary's bigrams after [previous]
 * ([followers], best first) and the user's own history. Shared by taps ([Suggester]) and glides ([GlideDecoder]).
 */
internal fun wordScore(params: Params, history: UserHistory, previous: String?, followers: List<String>, word: String, freq: Int, cost: Float): Float {
    val rank = followers.indexOf(word)
    val bigram = if (rank >= 0) params.bigramWeight * (3 - minOf(rank, 2)) / 3f else 0f
    val pair = previous?.let { history.pairScore(it, word) } ?: 0f
    return params.freqWeight * freq / 255f - cost + bigram + params.historyWeight * (history.score(word) + pair)
}

/** Gboard-style word suggestions over one language's [dict]. Not reentrant; calls are serialised. */
class Suggester(
    private val dict: Dictionary,
    private val keys: KeyModel,
    private val history: UserHistory,
    val params: Params = Params(),
) {
    private var t = CharArray(0)
    private var pts = arrayOfNulls<Touch>(0)
    private var n = 0
    private var maxCost = 0f
    private val buf = CharArray(MAX_LEN)
    private var rows = Array(MAX_LEN + 1) { FloatArray(1) }
    private val found = HashMap<String, Candidate>()
    private var followers: List<String> = emptyList()
    private var previous: String? = null

    @Synchronized
    fun suggest(typed: String, touches: List<Touch?>, previous: String?): Suggestions {
        prepare(typed, touches, previous)
        if (n == 0 || n >= MAX_LEN) return Suggestions(typed, emptyList(), false)
        val root = FloatArray(n + 1) { it * params.extra }
        search(0, 0, root, null, '\u0000')
        matchLearned()
        val typedNode = typedNode(typed)
        val typedIsWord = (typedNode >= 0 && dict.freq(typedNode) >= 0) || history.isKnown(typed)
        shortcut(typedNode, typed)
        val list = found.values.sortedByDescending { it.score }.take(params.poolSize).map { it.copy(word = matchCase(it.word, typed)) }
        return Suggestions(typed, list, typedIsWord)
    }

    @Synchronized
    fun predict(previous: String?, limit: Int = 3): List<Candidate> {
        if (previous.isNullOrEmpty()) return emptyList()
        val node = wordNode(previous)
        val fromUser = history.nextWords(previous)
        val fromDict = if (node >= 0) dict.nextWords(node) else emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fromUser.forEachIndexed { i, w -> if (!history.isBlocked(w)) out.putIfAbsent(w, Candidate(w, 10f - i, 0f, w.length, learned = true)) }
        fromDict.forEachIndexed { i, w ->
            val wn = dict.find(w)
            if (!history.isBlocked(w) && !(wn >= 0 && dict.isOffensive(wn))) out.putIfAbsent(w, Candidate(w, 5f - i, 0f, w.length))
        }
        return out.values.take(limit)
    }

    private fun prepare(typed: String, touches: List<Touch?>, prev: String?) {
        val tc = StringBuilder()
        val tp = ArrayList<Touch?>()
        typed.forEachIndexed { i, c -> if (!Fold.isSkippable(c)) { tc.append(Fold.key(c)); tp += touches.getOrNull(i) } }
        t = tc.toString().toCharArray()
        pts = tp.toTypedArray()
        n = t.size
        maxCost = minOf(params.maxCostCap, params.maxCostBase + params.maxCostPerChar * n)
        if (n < MAX_LEN && rows[0].size < n + 1) rows = Array(MAX_LEN + 1) { FloatArray(n + 1) }
        found.clear()
        previous = prev
        val pn = prev?.let { wordNode(it) } ?: -1
        followers = if (pn >= 0) dict.nextWords(pn) else emptyList()
    }

    /** One edit-distance row step for dictionary character [c] (already folded). */
    private fun step(prev: FloatArray, prevPrev: FloatArray?, prevChar: Char, c: Char, out: FloatArray): Float {
        out[0] = prev[0] + params.omit
        var min = out[0]
        for (j in 1..n) {
            var v = prev[j - 1] + sub(j - 1, c)
            v = minOf(v, prev[j] + if (j == n) params.complete else params.omit)
            v = minOf(v, out[j - 1] + params.extra)
            if (prevPrev != null && j >= 2 && c == t[j - 2] && prevChar == t[j - 1]) v = minOf(v, prevPrev[j - 2] + params.transpose)
            out[j] = v
            if (v < min) min = v
        }
        return min
    }

    private fun sub(i: Int, c: Char): Float {
        val p = pts[i]
        return if (p != null && keys.has(c)) keys.cost(p, c, params.sigma, params.far) else if (t[i] == c) 0f else params.far
    }

    private fun search(node: Int, depth: Int, prev: FloatArray, prevPrev: FloatArray?, prevChar: Char) {
        // Short inputs complete only a little way: one Arabic letter (often "ا" of "ال…") opens most of the dictionary.
        if (depth >= MAX_LEN - 1 || depth > n + minOf(params.maxCompletion, n + 2)) return
        val first = dict.firstChild(node)
        for (k in 0 until dict.childCount(node)) {
            val child = first + k
            val raw = dict.char(child)
            buf[depth] = raw
            val row = rows[depth + 1]
            if (Fold.isSkippable(raw)) {
                val skip = if (raw == '\'' || raw == '’') params.apostrophe else 0f
                for (j in 0..n) row[j] = prev[j] + skip
                visit(child, depth, row, prevPrev, prevChar)
                continue
            }
            val c = Fold.key(raw)
            if (step(prev, prevPrev, prevChar, c, row) > maxCost) continue
            visit(child, depth, row, prev, c)
        }
    }

    private fun visit(child: Int, depth: Int, row: FloatArray, parentRow: FloatArray?, char: Char) {
        val freq = dict.freq(child)
        if (freq >= 0 && row[n] <= maxCost && !dict.isOffensive(child)) consider(String(buf, 0, depth + 1), row[n], freq, learned = false)
        search(child, depth + 1, row, parentRow, char)
    }

    private fun matchLearned() {
        for (w in history.knownWords()) {
            if (kotlin.math.abs(w.length - n) > 3) continue // too far to be what was typed (and keeps big histories fast)
            val node = dict.find(w)
            if (w.length >= MAX_LEN || (node >= 0 && dict.freq(node) >= 0)) continue // dictionary words are found by the trie walk
            val cost = costOf(w)
            if (cost <= maxCost) consider(w, cost, params.learnedFreq, learned = true)
        }
    }

    /** The row recurrence run along a single word (for learned words outside the dictionary). */
    private fun costOf(word: String): Float {
        var prev = FloatArray(n + 1) { it * params.extra }
        var prevPrev: FloatArray? = null
        var prevChar = '\u0000'
        for (raw in word) {
            if (Fold.isSkippable(raw)) continue
            val c = Fold.key(raw)
            val row = FloatArray(n + 1)
            step(prev, prevPrev, prevChar, c, row)
            prevPrev = prev
            prev = row
            prevChar = c
        }
        return prev[n]
    }

    private fun consider(word: String, cost: Float, freq: Int, learned: Boolean) {
        if (history.isBlocked(word)) return
        val score = wordScore(params, history, previous, followers, word, freq, cost)
        val key = word.lowercase()
        val old = found[key]
        if (old == null || score > old.score) found[key] = Candidate(word, score, cost, Fold.word(word).length, learned = learned)
    }

    /** Word-list corrections ("im" → "I'm") rank first unless the user has made the typed word their own. */
    private fun shortcut(typedNode: Int, typed: String) {
        if (typedNode < 0 || history.isKnown(typed)) return
        val replacement = dict.shortcut(typedNode) ?: return
        val top = found.values.maxOfOrNull { it.score } ?: 0f
        found[replacement.lowercase()] = Candidate(replacement, top + params.shortcutBonus, 0f, Fold.word(replacement).length, shortcut = true)
    }

    /**
     * The node of what was typed. A capital first letter is usually just the sentence start, so the lowercase word
     * wins when it exists ("Hel" → "hel", whose shortcut is "he'll", rather than the rare name "Hel").
     */
    private fun typedNode(typed: String): Int {
        if (typed.firstOrNull()?.isUpperCase() == true) {
            val lower = dict.find(typed.replaceFirstChar { it.lowercaseChar() })
            if (lower >= 0 && (dict.freq(lower) >= 0 || dict.shortcut(lower) != null)) return lower
        }
        return dict.find(typed)
    }

    private fun wordNode(word: String): Int {
        val n0 = dict.find(word)
        if (n0 >= 0 && dict.freq(n0) >= 0) return n0
        return dict.find(word.lowercase())
    }

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.any { it.isLetter() } && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    private companion object {
        const val MAX_LEN = 48
    }
}
