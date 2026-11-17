package dev.yazin.keyboard.ime

/** What the keyboard learns from a finished word. */
object Learning {
    /**
     * The form of [word] to learn, or null when it must not be learned: anything with digits (codes, card or phone
     * numbers). A capital that only the sentence start explains is learned lowercase, so "Thanks" isn't offered
     * capitalised mid-sentence later.
     */
    fun form(word: String, atSentenceStart: Boolean): String? {
        if (word.isBlank() || word.any { it.isDigit() }) return null
        val sentenceCapital = atSentenceStart && word.length > 1 && word[0].isUpperCase() && word.drop(1).none { it.isUpperCase() }
        return if (sentenceCapital) word.replaceFirstChar { it.lowercaseChar() } else word
    }
}
