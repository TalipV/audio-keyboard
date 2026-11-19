package dev.yazin.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningTest {
    @Test fun sentenceStartCapitalsAreLearnedLowercase() {
        assertEquals("thanks", Learning.form("Thanks", atSentenceStart = true))
        assertEquals("Thanks", Learning.form("Thanks", atSentenceStart = false))
        assertEquals("iPhone", Learning.form("iPhone", atSentenceStart = true))
        // German nouns are capitalised everywhere: the caller says so, and the capital is kept.
        assertEquals("Haus", Learning.form("Haus", atSentenceStart = true) { it == "Haus" })
        assertEquals("die", Learning.form("Die", atSentenceStart = true) { it == "Haus" })
        assertEquals("NASA", Learning.form("NASA", atSentenceStart = true))
        assertEquals("مرحبا", Learning.form("مرحبا", atSentenceStart = true))
    }

    @Test fun numbersAreNeverLearned() {
        assertNull(Learning.form("482913", atSentenceStart = false))
        assertNull(Learning.form("2nd", atSentenceStart = false))
    }
}
