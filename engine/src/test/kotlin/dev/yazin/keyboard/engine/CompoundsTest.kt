package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.dict.DictWriter
import dev.yazin.keyboard.engine.dict.Dictionary
import dev.yazin.keyboard.engine.dict.WordList
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundsTest {
    private val dict = Dictionary(ByteBuffer.wrap(DictWriter.build(WordList.parse("""
         word=Fahrrad,f=129
         word=Schloss,f=133
         word=Schlüssel,f=125
         word=Arbeit,f=173
         word=Zimmer,f=138
         word=Haus,f=166
         word=hoch,f=170
         word=laden,f=120
         word=Wien,f=110
         word=achten,f=100
         word=Weihnachten,f=150
         word=Rarität,f=20
         word=Straße,f=168
         word=Trasse,f=60
    """.trimIndent().lineSequence()))))

    private val qwertz = KeyModel(
        buildMap {
            "qwertzuiopü".forEachIndexed { i, c -> put(c, listOf(Touch(i.toFloat(), 0f))) }
            "asdfghjklöä".forEachIndexed { i, c -> put(c, listOf(Touch(i.toFloat(), 1f))) }
            "yxcvbnm".forEachIndexed { i, c -> put(c, listOf(Touch(i + 1.5f, 2f))) }
        },
        pitchX = 1f, pitchY = 1f,
    )

    @Test fun twoAndThreePartCompoundsSplit() {
        assertTrue(Compounds.isCompound(dict, "Fahrradschloss"))
        assertTrue(Compounds.isCompound(dict, "Fahrradschlossschlüssel"))
        assertTrue(Compounds.isCompound(dict, "hochladen"))
    }

    @Test fun linkingElementsAreAllowed() {
        assertTrue(Compounds.isCompound(dict, "Arbeitszimmer")) // Fugen-s
        assertTrue(Compounds.isCompound(dict, "Hauszimmer"))
    }

    @Test fun typosRareAndShortPartsDoNotSplit() {
        assertFalse(Compounds.isCompound(dict, "Arbietszimmer"))
        assertFalse(Compounds.isCompound(dict, "Raritätzimmer")) // "Rarität" is too rare to vouch for anything
        assertFalse(Compounds.isCompound(dict, "Hausz"))
        assertFalse(Compounds.isCompound(dict, "Haus-Zimmer"))
    }

    @Test fun aTypedCompoundIsNotCorrectedAway() {
        val s = Suggester(dict, qwertz, UserHistory(), compounds = true)
        val typed = "Fahrradschlossschlüssel"
        val out = s.suggest(typed, typed.map { null }, null)
        assertFalse(out.typedIsWord)
        assertTrue(out.typedIsCompound)
        assertNull(Autocorrect.decide(out, emptySet()))
    }

    @Test fun compoundsAreOnlyCheckedWhenTheLanguageHasThem() {
        val s = Suggester(dict, qwertz, UserHistory())
        assertFalse(s.suggest("Arbeitszimmer", "Arbeitszimmer".map { null }, null).typedIsCompound)
    }

    @Test fun aCommonWordStillBeatsAnAccidentalCompound() {
        fun c(word: String, score: Float, cost: Float) = Candidate(word, score, cost, Fold.word(word).length)
        val split = Suggestions("Wienachten", listOf(c("Weihnachten", 1.0f, 2.6f)), typedIsWord = false, typedIsCompound = true)
        assertEquals("Weihnachten", Autocorrect.decide(split, emptySet()))
        val rare = Suggestions("Wienachten", listOf(c("Weihnachten", 0.2f, 2.6f)), typedIsWord = false, typedIsCompound = true)
        assertNull(Autocorrect.decide(rare, emptySet()))
    }

    @Test fun doubleSStandsForSharpS() {
        val s = Suggester(dict, qwertz, UserHistory(), compounds = true)
        val out = s.suggest("strasse", "strasse".map { null }, null)
        assertEquals("Straße", out.candidates.first().word)
        assertEquals("Straße", Autocorrect.decide(out, emptySet()))
    }
}
