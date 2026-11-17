package dev.yazin.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutocorrectTest {
    private fun c(word: String, score: Float, cost: Float, shortcut: Boolean = false) =
        Candidate(word, score, cost, Fold.word(word).length, shortcut)

    private fun s(typed: String, valid: Boolean, vararg cands: Candidate) = Suggestions(typed, cands.toList(), valid)

    @Test fun correctsAConfidentTypo() =
        assertEquals("the", Autocorrect.decide(s("teh", false, c("the", 3f, 2.2f), c("ten", 1.2f, 2.5f)), emptySet()))

    @Test fun keepsValidWords() =
        assertNull(Autocorrect.decide(s("then", true, c("them", 4f, 2f), c("then", 3.9f, 0f)), emptySet()))

    @Test fun keepsWordsWhenTheChoiceIsClose() =
        assertNull(Autocorrect.decide(s("thw", false, c("the", 3f, 2.4f), c("thy", 2.7f, 2.3f)), emptySet()))

    @Test fun neverAutocompletesAPartialWord() =
        assertNull(Autocorrect.decide(s("hel", false, c("hello", 6f, 0.5f), c("help", 3f, 0.25f)), emptySet()))

    @Test fun foldOnlyFixesAreSure() {
        assertEquals("أنا", Autocorrect.decide(s("انا", false, c("أنا", 4f, 0f), c("انت", 3.8f, 2.5f)), emptySet()))
        assertEquals("Monday", Autocorrect.decide(s("monday", false, c("Monday", 3f, 0f)), emptySet()))
    }

    @Test fun shortcutsApplyEvenToValidWords() =
        assertEquals("I'm", Autocorrect.decide(s("im", false, c("I'm", 9f, 0f, shortcut = true)), emptySet()))

    @Test fun shortcutsYieldToWordsTheUserUses() {
        // The suggester drops the shortcut once "dud" is the user's own word; here the list has no shortcut and "dud" is valid.
        assertNull(Autocorrect.decide(s("dud", true, c("did", 5f, 2f), c("dud", 4f, 0f)), emptySet()))
    }

    @Test fun respectsUndoAndSkipsDigitsAndSingleLetters() {
        assertNull(Autocorrect.decide(s("teh", false, c("the", 3f, 2.2f)), setOf("teh")))
        assertNull(Autocorrect.decide(s("12th", false, c("12th", 3f, 0f)), emptySet()))
        assertNull(Autocorrect.decide(s("x", false, c("a", 5f, 2f)), emptySet()))
    }

    @Test fun theTopWordsOwnExtensionsDoNotCompeteWithIt() =
        assertEquals("strange", Autocorrect.decide(s("strangw", false, c("strange", 1.08f, 1.7f), c("stranger", 0.57f, 1.95f)), emptySet()))

    @Test fun inflectionsOfTheTypedWordAreCompletionsNotCorrections() {
        assertNull(Autocorrect.decide(s("snippet", false, c("snippets", 0.75f, 0.96f)), emptySet()))
        assertNull(Autocorrect.decide(s("harms", false, c("harm's", 1.5f, 0.33f)), emptySet()))
        assertEquals("don't", Autocorrect.decide(s("dont", false, c("don't", 4f, 0.2f)), emptySet()))
    }

    @Test fun arabicCliticPrefixesAreNotTypos() {
        assertNull(Autocorrect.decide(s("بالكفاح", false, c("الكفاح", 2f, 2.8f)), emptySet()))
        assertNull(Autocorrect.decide(s("ودخولها", false, c("دخولها", 2f, 2.8f)), emptySet()))
        assertNull(Autocorrect.decide(s("للتحذيرات", false, c("التحذيرات", 2f, 2.9f)), emptySet()))
        assertEquals("مدرسة", Autocorrect.decide(s("مدرسه", false, c("مدرسة", 3f, 0f)), emptySet()))
    }

    @Test fun weakCandidatesAreLeftAlone() =
        assertNull(Autocorrect.decide(s("بالكفاح", false, c("الكفاح", -2f, 2.88f)), emptySet()))

    @Test fun apostrophesTheUserTypedAreKept() {
        assertNull(Autocorrect.decide(s("players'", false, c("players", 4f, 0f)), emptySet()))
        assertNull(Autocorrect.decide(s("'hello'", false, c("hello", 4f, 0f)), emptySet()))
    }

    @Test fun undoIgnoresCase() =
        assertNull(Autocorrect.decide(s("teh", false, c("the", 3f, 2.2f)), setOf("Teh")))

    @Test fun tooFarIsLeftAlone() =
        assertNull(Autocorrect.decide(s("qzxw", false, c("quiz", 3f, 6f)), emptySet()))
}
