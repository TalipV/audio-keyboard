package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.DictWriter
import dev.yazin.keyboard.engine.dict.Dictionary
import dev.yazin.keyboard.engine.dict.WordList
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {
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
         word=im,f=0,not_a_word=true
          shortcut=I'm,f=whitelist
         word=i,f=0,not_a_word=true
          shortcut=I,f=whitelist
         word=hel,f=39
          shortcut=he'll,f=whitelist
         word=Hel,f=1
         word=أنا,f=160
         word=مدرسة,f=150
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
    private val history = UserHistory()
    private val s = Suggester(dict, qwerty, history)

    /** Touches exactly on each typed letter's key (none for characters without a key). */
    private fun suggest(typed: String, previous: String? = null) =
        s.suggest(typed, typed.map { c -> qwertyCentre(c) }, previous)

    private fun qwertyCentre(c: Char): Touch? {
        val lc = c.lowercaseChar()
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (keys, offset) ->
            val i = keys.indexOf(lc)
            if (i >= 0) return Touch(i + offset, row.toFloat())
        }
        return null
    }

    private fun words(typed: String, previous: String? = null) = suggest(typed, previous).candidates.map { it.word }

    @Test fun anExactWordComesFirstAndIsValid() {
        val r = suggest("the")
        assertEquals("the", r.candidates.first().word)
        assertTrue(r.typedIsWord)
    }

    @Test fun neighbouringKeyTypoIsCorrected() {
        assertEquals("the", words("thr").first())
        assertFalse(suggest("thr").typedIsWord)
    }

    @Test fun swappedLettersAreCorrected() = assertEquals("the", words("teh").first())

    @Test fun partialWordsOfferCompletions() {
        val w = words("hel")
        assertTrue(w.containsAll(listOf("help", "hello", "hell")))
    }

    @Test fun caseFollowsTheTypist() {
        assertEquals("The", words("Teh").first())
        assertEquals("Monday", words("monday").first())
        assertEquals("THE", words("TEH").first())
    }

    @Test fun apostrophesAreRestored() = assertEquals("don't", words("dont").first())

    @Test fun arabicHamzaAndTaMarbutaAreRestored() {
        val r = s.suggest("انا", listOf(null, null, null), null)
        assertEquals("أنا", r.candidates.first().word)
        assertEquals(0f, r.candidates.first().cost, 1e-6f)
        assertEquals("مدرسة", s.suggest("مدرسه", List(5) { null }, null).candidates.first().word)
    }

    @Test fun offensiveWordsAreNeverOffered() {
        assertFalse("sex" in words("sez"))
        assertTrue(suggest("sex").typedIsWord)
        assertFalse("sex" in words("sex"))
    }

    @Test fun shortcutsComeFirst() {
        val r = suggest("im")
        assertEquals("I'm", r.candidates.first().word)
        assertTrue(r.candidates.first().shortcut)
        assertEquals("I", words("i").first())
    }

    @Test fun aSentenceCapitalPrefersTheLowercaseWord() {
        val r = suggest("Hel")
        assertEquals("He'll", r.candidates.first().word)
        assertTrue(r.candidates.first().shortcut)
    }

    @Test fun learnedWordsAreSuggestedAndBlockedOnesAreNot() {
        history.add("yalla")
        assertTrue("yalla" in words("yala"))
        history.remove("hello")
        assertFalse("hello" in words("hel"))
    }

    @Test fun thePreviousWordBoostsLikelyFollowers() {
        val alone = words("fir")
        val afterThe = words("fir", previous = "the")
        assertEquals("fire", alone.first())
        assertEquals("first", afterThe.first())
    }

    @Test fun predictionsComeFromTheDictionaryAndTheUser() {
        assertEquals(listOf("first", "same"), s.predict("the").map { it.word })
        repeat(2) { history.learn("then", "the") }
        assertEquals("then", s.predict("the").first().word)
        assertEquals(emptyList<String>(), s.predict(null))
    }

    @Test fun oversizedAndSymbolInputIsSafe() {
        val long = "a".repeat(80)
        assertTrue(s.suggest(long, List(long.length) { null }, null).candidates.size <= Params().poolSize)
        assertFalse(s.suggest("12:30", List(5) { null }, null).typedIsWord)
        s.suggest("👍", listOf(null, null), null)
    }
}
