package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.Dictionary
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot

/** One sample of a glide stroke: view px and uptime ms. */
data class GlidePoint(val x: Float, val y: Float, val t: Long)

/**
 * Glide tuning knobs, calibrated by the accuracy harness (docs/engine/accuracy.md).
 * Distances are in key pitches, costs in nats.
 */
data class GlideParams(
    /** Spread of the stroke around each key centre. */
    val sigma: Float = 0.40f,
    /** How far along the stroke the first letter may be aligned. */
    val startSlack: Float = 0.15f,
    /** A turn sharper than this is a corner, which some letter must explain. */
    val cornerDegrees: Float = 25f,
    /** Cost of gliding past a corner without a letter there. */
    val corner: Float = 5f,
    /** Weight of the relative difference between the stroke's length and the word's path. */
    val length: Float = 1.0f,
    /** Resampling step. */
    val step: Float = 0.25f,
    val maxPoints: Int = 128,
    val maxCost: Float = 10f,
    /** Cap on one letter's cost, so one far letter is costly but not infinite. */
    val far: Float = 9f,
    val poolSize: Int = 8,
)

/**
 * Glide typing: which words a stroke across the keys spells. Each word is aligned to the stroke letter by letter, in
 * order (first letter at the start, last at the end), by walking the dictionary trie with one cost row per prefix.
 * Words are scored like taps ([wordScore]). Not reentrant; calls are serialised.
 */
class GlideDecoder(
    private val dict: Dictionary,
    private val keys: KeyModel,
    private val history: UserHistory,
    private val params: Params = Params(),
    private val glide: GlideParams = GlideParams(),
) {
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var corner = BooleanArray(0)
    private var m = 0
    /** Points within [GlideParams.startSlack] of the start: where the first letter may align. */
    private var startPoints = 0
    private var strokeLen = 0f
    private var rows = Array(MAX_LEN + 1) { FloatArray(0) }
    private val buf = CharArray(MAX_LEN)
    /** Per depth: the folded letter, the key centre chosen for it, and the word path's length so far. */
    private val chars = CharArray(MAX_LEN + 1)
    private val cx = FloatArray(MAX_LEN + 1)
    private val cy = FloatArray(MAX_LEN + 1)
    private val lens = FloatArray(MAX_LEN + 1)
    private val found = HashMap<String, Candidate>()
    private var followers: List<String> = emptyList()
    private var previous: String? = null
    private var cancelled: () -> Boolean = { false }

    /** [cancelled] lets a stale preview stop early, so the final decode on lift never waits long for the lock. */
    @Synchronized
    fun decode(points: List<GlidePoint>, previous: String?, cancelled: () -> Boolean = { false }): List<Candidate> {
        this.cancelled = cancelled
        if (points.size < 2 || !prepare(points)) return emptyList()
        found.clear()
        costs.clear()
        this.previous = previous
        val pn = previous?.let { wordNode(it) } ?: -1
        followers = if (pn >= 0) dict.nextWords(pn) else emptyList()
        search(0, 0)
        if (cancelled()) return emptyList()
        matchLearned()
        return found.values.sortedByDescending { it.score }.take(glide.poolSize)
    }

    /** Resamples the stroke into pitch units at an even spacing and finds its corners. False for a stroke that never moved. */
    private fun prepare(points: List<GlidePoint>): Boolean {
        val n = points.size
        val xs = FloatArray(n) { points[it].x / keys.pitchX }
        val ys = FloatArray(n) { points[it].y / keys.pitchY }
        var total = 0f
        for (i in 1 until n) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (total <= 0f) return false
        val spacing = maxOf(glide.step, total / (glide.maxPoints - 1))
        val count = minOf(glide.maxPoints, (total / spacing).toInt() + 2)
        val ox = FloatArray(count)
        val oy = FloatArray(count)
        ox[0] = xs[0]; oy[0] = ys[0]
        var k = 1
        var next = spacing
        var walked = 0f
        for (i in 1 until n) {
            val seg = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            while (seg > 0f && walked + seg >= next && k < count - 1) {
                val f = (next - walked) / seg
                ox[k] = xs[i - 1] + (xs[i] - xs[i - 1]) * f
                oy[k] = ys[i - 1] + (ys[i] - ys[i - 1]) * f
                k++
                next += spacing
            }
            walked += seg
        }
        ox[k] = xs[n - 1]; oy[k] = ys[n - 1]
        m = k + 1
        px = ox; py = oy
        strokeLen = total
        startPoints = minOf(m, (glide.startSlack / spacing).toInt() + 1)
        findCorners(ceil(0.5f / spacing).toInt().coerceAtLeast(1))
        if (rows[0].size < m) rows = Array(MAX_LEN + 1) { FloatArray(m) }
        return true
    }

    /** A corner is a local maximum of the turn angle, measured over about half a pitch each side. */
    private fun findCorners(r: Int) {
        val angle = FloatArray(m)
        for (k in r until m - r) {
            val ax = px[k] - px[k - r]; val ay = py[k] - py[k - r]
            val bx = px[k + r] - px[k]; val by = py[k + r] - py[k]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la > 0f && lb > 0f) angle[k] = Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f).toDouble())).toFloat()
        }
        // Non-maximum suppression: a rounded turn is one corner. Among equal angles the first wins.
        corner = BooleanArray(m) { k ->
            k in 1 until m - 1 && angle[k] > glide.cornerDegrees &&
                (maxOf(0, k - r)..minOf(m - 1, k + r)).all { j -> if (j < k) angle[j] < angle[k] else angle[j] <= angle[k] }
        }
    }

    /** Per folded letter, its cost at every stroke point; filled on first use in each decode. */
    private val costs = HashMap<Char, FloatArray>()

    private fun costsOf(c: Char): FloatArray = costs.getOrPut(c) {
        FloatArray(m) { i -> keys.cost(Touch(px[i] * keys.pitchX, py[i] * keys.pitchY), c, glide.sigma, glide.far) }
    }

    /**
     * The row for letter [c] after [prev] (null for the first letter). A letter may align at the same point as the one
     * before it or later; gliding past a corner without aligning a letter there costs [GlideParams.corner].
     * Returns the row's minimum.
     */
    private fun step(prev: FloatArray?, prevChar: Char, c: Char, out: FloatArray): Float {
        var min = INF
        val cost = costsOf(c)
        if (prev == null) {
            for (i in 0 until m) {
                out[i] = if (i < startPoints) cost[i] else INF
                if (out[i] < min) min = out[i]
            }
            return min
        }
        if (c == prevChar) { // a doubled letter: no extra movement
            prev.copyInto(out, 0, 0, m)
            for (i in 0 until m) if (out[i] < min) min = out[i]
            return min
        }
        var carry = INF
        for (i in 0 until m) {
            out[i] = minOf(carry, prev[i]) + cost[i]
            if (out[i] < min) min = out[i]
            carry = minOf(carry + if (corner[i]) glide.corner else 0f, prev[i])
        }
        return min
    }

    /** Aligns folded letter [c] as letter number [letters] (0-based) into rows[letters + 1]. False when it can't fit. */
    private fun push(letters: Int, c: Char): Boolean {
        if (!keys.has(c)) return false
        val prevChar = if (letters == 0) '\u0000' else chars[letters - 1]
        if (step(if (letters == 0) null else rows[letters], prevChar, c, rows[letters + 1]) > glide.maxCost) return false
        chars[letters] = c
        if (letters > 0 && c == prevChar) {
            cx[letters] = cx[letters - 1]; cy[letters] = cy[letters - 1]; lens[letters] = lens[letters - 1]
        } else {
            place(letters, c)
        }
        return true
    }

    /** Sets the key centre and path length for depth [d] from depth [d] − 1 (or the stroke's start when [d] is 0). */
    private fun place(d: Int, c: Char) {
        val fromX = if (d == 0) px[0] * keys.pitchX else cx[d - 1]
        val fromY = if (d == 0) py[0] * keys.pitchY else cy[d - 1]
        var best: Touch? = null
        var bestD = Float.MAX_VALUE
        for (t in keys.centers(c)) {
            val dist = hypot(t.x - fromX, t.y - fromY)
            if (dist < bestD) { bestD = dist; best = t }
        }
        val b = best ?: Touch(fromX, fromY)
        cx[d] = b.x; cy[d] = b.y
        lens[d] = if (d == 0) 0f else lens[d - 1] + hypot((b.x - cx[d - 1]) / keys.pitchX, (b.y - cy[d - 1]) / keys.pitchY)
    }

    /**
     * Walks the trie under [node]. [depth] counts trie characters (the word so far is buf[0, depth)); [letters] counts the
     * folded letters aligned so far, whose last row is rows[letters].
     */
    private fun search(node: Int, depth: Int, letters: Int = 0) {
        if (depth >= MAX_LEN - 1 || cancelled()) return
        val first = dict.firstChild(node)
        for (k in 0 until dict.childCount(node)) {
            val child = first + k
            val raw = dict.char(child)
            buf[depth] = raw
            if (Fold.isSkippable(raw)) {
                // Apostrophes and marks are typed without touching a key.
                if (letters == 0) continue
                val skip = if (raw == '\'' || raw == '’') params.apostrophe else 0f
                val row = rows[letters]
                for (i in 0 until m) row[i] += skip
                visit(child, depth, letters)
                for (i in 0 until m) row[i] -= skip
                continue
            }
            if (push(letters, Fold.key(raw))) visit(child, depth, letters + 1)
        }
    }

    private fun visit(child: Int, depth: Int, letters: Int) {
        val freq = dict.freq(child)
        if (freq >= 0 && !dict.isOffensive(child)) {
            val cost = total(rows[letters], lens[letters - 1])
            if (cost <= glide.maxCost) consider(String(buf, 0, depth + 1), cost, freq)
        }
        search(child, depth + 1, letters)
    }

    /** The full word's cost: its last letter aligned at the stroke's end, plus how far the lengths disagree. */
    private fun total(row: FloatArray, idealLen: Float): Float =
        row[m - 1] + glide.length * abs(strokeLen - idealLen) / maxOf(1f, idealLen)

    /** Learned words the dictionary doesn't have, aligned one by one with the same recurrence. */
    private fun matchLearned() {
        for (w in history.knownWords()) {
            if (w.length >= MAX_LEN) continue
            val node = dict.find(w)
            if (node >= 0 && dict.freq(node) >= 0) continue // dictionary words are found by the trie walk
            var letters = 0
            var ok = true
            for (raw in w) {
                if (Fold.isSkippable(raw)) continue
                if (!push(letters, Fold.key(raw))) { ok = false; break }
                letters++
            }
            if (!ok || letters == 0) continue
            val cost = total(rows[letters], lens[letters - 1])
            if (cost <= glide.maxCost) consider(w, cost, params.learnedFreq)
        }
    }

    private fun consider(word: String, cost: Float, freq: Int) {
        if (history.isBlocked(word)) return
        val score = wordScore(params, history, previous, followers, word, freq, cost)
        // Case twins ("fox", "Fox") are one candidate with the better score, spelled lowercase when that form exists:
        // a glide carries no case, and sentence capitals are added later.
        val key = word.lowercase()
        val old = found[key]
        if (old != null && old.score >= score) {
            if (word == key) found[key] = old.copy(word = word)
            return
        }
        found[key] = Candidate(if (old?.word == key) key else word, score, cost, Fold.word(word).length)
    }

    private fun wordNode(word: String): Int {
        val n0 = dict.find(word)
        if (n0 >= 0 && dict.freq(n0) >= 0) return n0
        return dict.find(word.lowercase())
    }

    private companion object {
        const val MAX_LEN = 48
        const val INF = 1e9f
    }
}
