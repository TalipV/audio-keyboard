package dev.yazin.keyboard.text

import dev.yazin.keyboard.engine.Autocorrect
import dev.yazin.keyboard.engine.GlideDecoder
import dev.yazin.keyboard.engine.GlidePoint
import dev.yazin.keyboard.engine.Suggester
import dev.yazin.keyboard.engine.Touch
import dev.yazin.keyboard.engine.UserHistory
import dev.yazin.keyboard.engine.dict.DictWriter
import dev.yazin.keyboard.engine.dict.Dictionary
import dev.yazin.keyboard.engine.dict.WordList
import dev.yazin.keyboard.keyboard.KeyAction
import dev.yazin.keyboard.keyboard.Lang
import dev.yazin.keyboard.keyboard.LayoutGeometry
import dev.yazin.keyboard.keyboard.LayoutSpec
import dev.yazin.keyboard.keyboard.Layouts
import dev.yazin.keyboard.keyboard.RowKind
import dev.yazin.keyboard.keyboard.keyModel
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.random.asKotlinRandom
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates thumb typing on the real key geometry and measures what autocorrect does (spec §10.2).
 * Run: ./gradlew :app:testDebugUnitTest --tests '*AccuracyHarnessTest' -Pharness
 * Report: docs/engine/accuracy.md.
 */
class AccuracyHarnessTest {
    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("harness") == "true")

    @Test fun english() = run(Lang.EN, "main_en_US.combined", Int.MAX_VALUE)

    @Test fun arabic() = run(Lang.AR, "main_ar.combined", 300_000)

    @Test fun englishGlide() = runGlide(Lang.EN, "main_en_US.combined", Int.MAX_VALUE, firstBar = 85.0, top3Bar = 95.0)

    @Test fun arabicGlide() = runGlide(Lang.AR, "main_ar.combined", 300_000, firstBar = 75.0, top3Bar = 90.0)

    private data class Result(var clean: Int = 0, var falseFix: Int = 0, var typo: Int = 0, var realWord: Int = 0, var fixed: Int = 0, var top3: Int = 0,
                              var oov: Int = 0, var oovChanged: Int = 0, val times: ArrayList<Long> = ArrayList())

    private fun run(lang: Lang, list: String, maxWords: Int) {
        val gauss = Random(42)
        val rnd = Random(7).asKotlinRandom()
        val entries = File("../tools/wordlists/$list").useLines { WordList.parse(it) }
        // Hold out 500 real but rarer words: typing them exactly measures how often we "correct" words we don't know.
        val heldOut = entries.filter { it.isWord && !it.offensive && it.freq in 40..90 && it.word.length >= 3 && typeable(lang, it.word) }
            .shuffled(rnd).take(500).map { it.word }.toHashSet()
        val dict = Dictionary(ByteBuffer.wrap(DictWriter.build(entries.filter { it.word !in heldOut }, maxWords)))
        val grid = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = lang, numberRow = false)), 1080f, 0f, 2.625f)
        val suggester = Suggester(dict, grid.keyModel(), UserHistory())
        val keys = grid.keys.filter { it.key.action == KeyAction.TEXT && it.key.label.length == 1 && it.row == RowKind.LETTER }
        val centre = keys.associateBy { it.key.label[0] }
        val pitch = keys[1].left - keys[0].left

        fun type(word: String, sigma: Float): Pair<String, List<Touch?>> {
            val typed = StringBuilder()
            val touches = ArrayList<Touch?>()
            for (c0 in word) {
                if (c0 == '\'') continue // nobody types the apostrophe in "dont"
                val c = if (lang == Lang.AR && c0 in "أإآٱ" && rnd.nextFloat() < 0.6f) 'ا' else c0
                val k = centre[c]
                if (k == null) { typed.append(c); touches += null; continue } // long-press alternate
                val tx = k.centerX + (gauss.nextGaussian() * sigma * pitch).toFloat()
                val ty = k.centerY + (gauss.nextGaussian() * sigma * pitch).toFloat()
                val hit = grid.nearest(tx, ty)
                // A slip onto shift/delete types nothing; only letters carry a touch, so touches stay aligned with letters.
                if (hit.key.action == KeyAction.TEXT && hit.key.label.length == 1) {
                    typed.append(hit.key.label)
                    touches += Touch(tx, ty)
                }
            }
            return typed.toString() to touches
        }

        val r = Result()
        val sample = entries.filter { it.isWord && !it.offensive && it.word !in heldOut && typeable(lang, it.word) && it.word.length in 2..12 }
            .sortedByDescending { it.freq }.take(30_000)
        val weights = sample.map { (it.freq + 1).toDouble() }
        val total = weights.sum()
        repeat(4000) {
            var x = rnd.nextDouble() * total
            val word = sample[weights.indexOfFirst { x -= it; x <= 0 }.coerceAtLeast(0)].word
            var (typed, touches) = type(word, sigma = 0.22f)
            when (rnd.nextInt(100)) { // occasional whole-letter slips
                in 0..3 -> if (typed.length > 2) { val i = rnd.nextInt(typed.length); typed = typed.removeRange(i, i + 1); touches = touches.filterIndexed { j, _ -> j != i } }
                in 4..6 -> if (typed.length > 2) { val i = rnd.nextInt(typed.length - 1); typed = typed.substring(0, i) + typed[i + 1] + typed[i] + typed.substring(i + 2); touches = touches.toMutableList().also { val a = it[i]; it[i] = it[i + 1]; it[i + 1] = a } }
            }
            val t0 = System.nanoTime()
            val s = suggester.suggest(typed, touches, null)
            r.times += System.nanoTime() - t0
            val final = Autocorrect.decide(s, emptySet()) ?: typed
            if (typed == word) { r.clean++; if (final != word) r.falseFix++ }
            else { r.typo++; if (s.typedIsWord) r.realWord++; if (final == word) r.fixed++; if (s.candidates.take(3).any { it.word == word }) r.top3++ }
        }
        for (word in heldOut) {
            val (typed, touches) = type(word, sigma = 0.15f)
            if (typed != word.replace("'", "")) continue
            r.oov++
            if ((Autocorrect.decide(suggester.suggest(typed, touches, null), emptySet()) ?: typed) != typed) r.oovChanged++
        }
        val ms = r.times.sorted().let { it[it.size / 2] / 1e6 to it[it.size * 95 / 100] / 1e6 }
        val report = """
            ${lang.name}: ${dict.nodeCount} nodes
            clean words: ${r.clean}, wrongly changed: ${r.falseFix} (${pct(r.falseFix, r.clean)})
            typos: ${r.typo} (${r.realWord} became another real word), fixed by autocorrect: ${r.fixed} (${pct(r.fixed, r.typo)}; ${pct(r.fixed, r.typo - r.realWord)} of fixable), intended word in top 3: ${pct(r.top3, r.typo)}
            unknown words typed exactly: ${r.oov}, changed: ${r.oovChanged} (${pct(r.oovChanged, r.oov)})
            suggest time (JVM): p50 ${"%.2f".format(ms.first)} ms, p95 ${"%.2f".format(ms.second)} ms
        """.trimIndent()
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/accuracy-${lang.name.lowercase()}.txt").writeText(report)
        assertTrue("false-correction rate must stay under 0.5%", r.falseFix * 200 < r.clean)
    }

    /**
     * Glide accuracy (glide spec §6): one synthetic stroke per word. The stroke aims at each letter's key with Gaussian
     * noise of 0.25 pitch, rounds its corners, and is sampled at a thumb speed of 18 pitches/s every 8 ms with 0.03-pitch
     * jitter. Doubled letters add no movement.
     */
    private fun runGlide(lang: Lang, list: String, maxWords: Int, firstBar: Double, top3Bar: Double) {
        val gauss = Random(42)
        val rnd = Random(7).asKotlinRandom()
        val entries = File("../tools/wordlists/$list").useLines { WordList.parse(it) }
        val dict = Dictionary(ByteBuffer.wrap(DictWriter.build(entries, maxWords)))
        val grid = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = lang, numberRow = false)), 1080f, 0f, 2.625f)
        val keyModel = grid.keyModel()
        val decoder = GlideDecoder(dict, keyModel, UserHistory())
        val letterKeys = grid.keys.filter { it.key.action == KeyAction.TEXT && it.key.label.length == 1 && it.row == RowKind.LETTER }
        val pitch = letterKeys[1].left - letterKeys[0].left

        fun stroke(word: String): List<GlidePoint>? {
            val aims = ArrayList<Touch>()
            var last: Touch? = null
            for (c0 in word) {
                if (c0 == '\'') continue
                val c = if (lang == Lang.AR && c0 in "أإآٱ" && rnd.nextFloat() < 0.6f) 'ا' else c0
                val k = keyModel.centers(c).firstOrNull() ?: return null
                if (k == last) continue // a doubled letter: no movement
                last = k
                aims += Touch(k.x + (gauss.nextGaussian() * 0.25 * pitch).toFloat(), k.y + (gauss.nextGaussian() * 0.25 * pitch).toFloat())
            }
            if (aims.size < 2) return null
            // A dense polyline with rounded corners: a quadratic Bézier around each interior aim, starting and ending
            // 0.4 pitch (at most half the segment) from it, so the thumb passes within about 0.2 pitch of the key.
            val line = ArrayList<Touch>()
            fun toward(from: Touch, to: Touch): Touch {
                val d = kotlin.math.hypot(to.x - from.x, to.y - from.y)
                val r = minOf(0.4f * pitch, d / 2) / maxOf(d, 1e-3f)
                return Touch(from.x + (to.x - from.x) * r, from.y + (to.y - from.y) * r)
            }
            line += aims[0]
            if (aims.size == 2) line += aims[1] else {
                for (i in 1 until aims.size - 1) {
                    val a = toward(aims[i], aims[i - 1]); val c = aims[i]; val b = toward(aims[i], aims[i + 1])
                    for (s in 0..16) {
                        val t = s / 16f
                        line += Touch((1 - t) * (1 - t) * a.x + 2 * (1 - t) * t * c.x + t * t * b.x, (1 - t) * (1 - t) * a.y + 2 * (1 - t) * t * c.y + t * t * b.y)
                    }
                }
                line += aims.last()
            }
            val stepPx = 18f * pitch * 0.008f
            val out = ArrayList<GlidePoint>()
            var t = 0L
            fun jittered(p: Touch) = GlidePoint(p.x + (gauss.nextGaussian() * 0.03 * pitch).toFloat(), p.y + (gauss.nextGaussian() * 0.03 * pitch).toFloat(), t)
            out += jittered(line[0])
            var carry = 0f
            for (i in 1 until line.size) {
                val a = line[i - 1]; val b = line[i]
                val seg = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                var d = stepPx - carry
                while (d <= seg) {
                    t += 8
                    out += jittered(Touch(a.x + (b.x - a.x) * d / seg, a.y + (b.y - a.y) * d / seg))
                    d += stepPx
                }
                carry = seg - (d - stepPx)
            }
            t += 8
            out += jittered(line.last())
            return out
        }

        val sample = entries.filter { it.isWord && !it.offensive && typeable(lang, it.word) && it.word.length in 2..12 }
            .sortedByDescending { it.freq }.take(30_000)
        val weights = sample.map { (it.freq + 1).toDouble() }
        val total = weights.sum()
        var tried = 0
        var first = 0
        var top3 = 0
        val times = ArrayList<Long>()
        val misses = ArrayList<String>()
        while (tried < 2000) {
            var x = rnd.nextDouble() * total
            val word = sample[weights.indexOfFirst { x -= it; x <= 0 }.coerceAtLeast(0)].word
            val points = stroke(word) ?: continue
            tried++
            val t0 = System.nanoTime()
            val got = decoder.decode(points, null).map { it.word }
            times += System.nanoTime() - t0
            if (got.firstOrNull() == word) first++ else if (misses.size < 40) misses += "$word→${got.take(3)}"
            if (word in got.take(3)) top3++
        }
        val ms = times.sorted().let { it[it.size / 2] / 1e6 to it[it.size * 95 / 100] / 1e6 }
        val report = """
            ${lang.name} glide: ${tried} strokes
            intended word first: ${pct(first, tried)}, in top 3: ${pct(top3, tried)}
            decode time (JVM): p50 ${"%.2f".format(ms.first)} ms, p95 ${"%.2f".format(ms.second)} ms
            misses: ${misses.joinToString(", ")}
        """.trimIndent()
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/glide-${lang.name.lowercase()}.txt").writeText(report)
        assertTrue("first ≥ $firstBar%", first * 100.0 / tried >= firstBar)
        assertTrue("top 3 ≥ $top3Bar%", top3 * 100.0 / tried >= top3Bar)
        assertTrue("p95 ≤ 30 ms", ms.second <= 30.0)
    }

    private fun typeable(lang: Lang, w: String) =
        if (lang == Lang.EN) w.all { it in 'a'..'z' || it == '\'' } else w.all { it in 'ء'..'ي' }

    private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%%".format(100.0 * a / b)
}
