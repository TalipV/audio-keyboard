package dev.yazin.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test fun doubleSpaceAfterAWordMakesAPeriod() {
        assertTrue(TextRules.isDoubleSpacePeriod("hello ", previousKeyWasSpace = true))
        assertTrue(TextRules.isDoubleSpacePeriod("مرحبا ", previousKeyWasSpace = true))
        assertTrue(TextRules.isDoubleSpacePeriod("ok😀 ", previousKeyWasSpace = true))
    }

    @Test fun doubleSpaceNeedsTwoConsecutiveSpacesAfterAWord() {
        assertFalse(TextRules.isDoubleSpacePeriod("hello ", previousKeyWasSpace = false))
        assertFalse(TextRules.isDoubleSpacePeriod("hi. ", previousKeyWasSpace = true))
        assertFalse(TextRules.isDoubleSpacePeriod("a  ", previousKeyWasSpace = true))
        assertFalse(TextRules.isDoubleSpacePeriod("لماذا؟ ", previousKeyWasSpace = true))
        assertFalse(TextRules.isDoubleSpacePeriod(" ", previousKeyWasSpace = true))
    }

    @Test fun graphemeLengthKeepsUserPerceivedCharactersWhole() {
        assertEquals(1, TextRules.lastGraphemeLength("abc"))
        assertEquals(2, TextRules.lastGraphemeLength("a👍"))
        assertEquals(8, TextRules.lastGraphemeLength("hi 👨‍👩‍👧"))
        assertEquals(4, TextRules.lastGraphemeLength("🇸🇦"))
        assertEquals(2, TextRules.lastGraphemeLength("é"))
        assertEquals(2, TextRules.lastGraphemeLength("مَ"))
        assertEquals(0, TextRules.lastGraphemeLength(""))
    }

    private val sentences = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test fun capsAtSentenceStarts() {
        assertTrue(TextRules.capsMode("", sentences))
        assertTrue(TextRules.capsMode("Hi. ", sentences))
        assertTrue(TextRules.capsMode("Really?  ", sentences))
        assertTrue(TextRules.capsMode("line\n", sentences))
        assertFalse(TextRules.capsMode("Hi ", sentences))
        assertFalse(TextRules.capsMode("Hi.", sentences))
        assertFalse(TextRules.capsMode("", android.text.InputType.TYPE_CLASS_TEXT))
        assertTrue(TextRules.capsMode("a b ", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS))
    }

    @Test fun previousWordStopsAtSentenceEnds() {
        assertEquals("good", TextRules.previousWord("very good "))
        assertEquals("good", TextRules.previousWord("very good"))
        assertEquals(null, TextRules.previousWord("Done. "))
        assertEquals("مرحبا", TextRules.previousWord("مرحبا "))
        assertEquals(null, TextRules.previousWord(""))
    }

    @Test fun spaceBeforeGlide() {
        assertFalse(TextRules.spaceBeforeGlide(""))
        assertTrue(TextRules.spaceBeforeGlide("Hello"))
        assertTrue(TextRules.spaceBeforeGlide("Hello."))
        assertTrue(TextRules.spaceBeforeGlide("Hello,"))
        assertTrue(TextRules.spaceBeforeGlide("مرحبا،"))
        assertFalse(TextRules.spaceBeforeGlide("Hello "))
        assertFalse(TextRules.spaceBeforeGlide("x\n"))
        assertFalse(TextRules.spaceBeforeGlide("("))
        assertFalse(TextRules.spaceBeforeGlide("say \""))
        assertTrue(TextRules.spaceBeforeGlide("\"quoted\""))
    }

    @Test fun glideCase() {
        assertEquals("HELLO", TextRules.glideCase("hello", Caps.LOCKED))
        assertEquals("Hello", TextRules.glideCase("hello", Caps.AUTO))
        assertEquals("Hello", TextRules.glideCase("hello", Caps.ONE_SHOT))
        assertEquals("London", TextRules.glideCase("London", Caps.OFF))
        assertEquals("مرحبا", TextRules.glideCase("مرحبا", Caps.ONE_SHOT))
    }
}
