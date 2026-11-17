package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.DictWriter
import dev.yazin.keyboard.engine.dict.Dictionary
import dev.yazin.keyboard.engine.dict.WordList
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideDecoderTest {
    private val dict = Dictionary(ByteBuffer.wrap(DictWriter.build(WordList.parse("""
         word=the,f=222
          bigram=first,f=1
          bigram=same,f=2
         word=them,f=190
         word=then,f=180
         word=thee,f=60
         word=help,f=175
         word=hello,f=170
         word=hell,f=120
         word=fire,f=160
         word=first,f=150
         word=same,f=170
         word=Monday,f=140
         word=don't,f=180
         word=sex,f=135,possibly_offensive=true
         word=sec,f=40
         word=hel,f=39
         word=مدرسة,f=150
         word=Fox,f=170
         word=fox,f=150
    """.trimIndent().lineSequence()))))

    // A QWERTY grid with a 1 px pitch: rows offset like a real keyboard.
    private val qwerty = KeyModel(
        buildMap {
            "qwertyuiop".forEachIndexed { i, c -> put(c, listOf(Touch(i.toFloat(), 0f))) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, listOf(Touch(i + 0.5f, 1f))) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, listOf(Touch(i + 1.5f, 2f))) }
        },
        pitchX = 1f, pitchY = 1f,
    )
    private val arabic = KeyModel(
        mapOf('م' to listOf(Touch(0f, 0f)), 'د' to listOf(Touch(1f, 1f)), 'ر' to listOf(Touch(2f, 0f)),
            'س' to listOf(Touch(3f, 1f)), 'ه' to listOf(Touch(4f, 0f))),
        pitchX = 1f, pitchY = 1f,
    )
    private val history = UserHistory()
    private val decoder = GlideDecoder(dict, qwerty, history)
    private val arDecoder = GlideDecoder(dict, arabic, UserHistory())

    /** Straight lines between the letters' key centres: 6 points per segment, 16 ms apart. */
    private fun path(keys: KeyModel, letters: CharArray): List<GlidePoint> {
        val centres = letters.map { keys.centers(it).first() }
        val out = ArrayList<GlidePoint>()
        var t = 0L
        out += GlidePoint(centres[0].x, centres[0].y, t)
        for ((a, b) in centres.zipWithNext()) {
            for (s in 1..6) {
                t += 16
                out += GlidePoint(a.x + (b.x - a.x) * s / 6, a.y + (b.y - a.y) * s / 6, t)
            }
        }
        return out
    }

    private fun stroke(vararg letters: Char) = path(qwerty, letters)
    private fun strokeAr(vararg letters: Char) = path(arabic, letters)
    private fun top(points: List<GlidePoint>, previous: String? = null) = decoder.decode(points, previous).first().word

    @Test fun aCleanStrokeDecodesTheWord() = assertEquals("the", top(stroke('t', 'h', 'e')))

    @Test fun doubledLettersNeedNoExtraMovement() = assertEquals("hello", top(stroke('h', 'e', 'l', 'o')))

    @Test fun helpAndHelloAreToldApart() {
        assertEquals("help", top(stroke('h', 'e', 'l', 'p')))
        assertEquals("hello", top(stroke('h', 'e', 'l', 'o')))
    }

    @Test fun aLowercaseWordBeatsItsCapitalisedTwin() = assertEquals("fox", top(stroke('f', 'o', 'x')))

    @Test fun theLastLetterMustMatchTheEnd() = assertEquals("them", top(stroke('t', 'h', 'e', 'm')))

    @Test fun apostrophesAreNotNeeded() = assertEquals("don't", top(stroke('d', 'o', 'n', 't')))

    @Test fun arabicFoldsTaMarbuta() =
        assertEquals("مدرسة", arDecoder.decode(strokeAr('م', 'د', 'ر', 'س', 'ه'), null).first().word)

    @Test fun learnedWordsGlide() {
        history.learn("helo", null)
        history.learn("helo", null)
        assertTrue(decoder.decode(stroke('h', 'e', 'l', 'o'), null).any { it.word == "helo" })
    }

    @Test fun blockedWordsNever() {
        history.remove("hell")
        assertFalse(decoder.decode(stroke('h', 'e', 'l'), null).any { it.word == "hell" })
    }

    @Test fun offensiveWordsNever() = assertFalse(decoder.decode(stroke('s', 'e', 'x'), null).any { it.word == "sex" })

    @Test fun bigramLiftsTheFollower() = assertEquals("same", top(stroke('s', 'a', 'm', 'e'), previous = "the"))

    @Test fun aStrokeFarFromAnyWordReturnsNothing() =
        assertTrue(decoder.decode(listOf(GlidePoint(40f, 40f, 0), GlidePoint(60f, 60f, 100)), null).isEmpty())

    @Test fun aSinglePointReturnsNothing() = assertTrue(decoder.decode(listOf(GlidePoint(0f, 0f, 0)), null).isEmpty())

    @Test fun aLongWanderingStrokeStaysFast() {
        val wander = (0 until 10_000).map { GlidePoint((it % 9).toFloat(), (it / 9 % 3).toFloat(), it.toLong()) }
        val t0 = System.nanoTime()
        decoder.decode(wander, null)
        assertTrue((System.nanoTime() - t0) / 1e6 < 200)
    }

    @Test fun aCancelledDecodeStopsWithNothing() =
        assertTrue(decoder.decode(stroke('h', 'e', 'l', 'o'), null) { true }.isEmpty())
}
