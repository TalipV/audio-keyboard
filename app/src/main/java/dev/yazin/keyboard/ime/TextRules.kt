package dev.yazin.keyboard.ime

import java.text.BreakIterator

object TextRules {
    private const val NO_PERIOD_AFTER = ".,;:!?،؛؟"

    /**
     * Gboard's double-space period: the user typed a word, a space, and now another space.
     * [before] is the text before the cursor, before the second space is inserted.
     */
    fun isDoubleSpacePeriod(before: CharSequence, previousKeyWasSpace: Boolean): Boolean {
        if (!previousKeyWasSpace || before.length < 2 || before[before.length - 1] != ' ') return false
        val c = Character.codePointBefore(before, before.length - 1)
        return !Character.isWhitespace(c) && NO_PERIOD_AFTER.codePoints().noneMatch { it == c }
    }

    /** Length in chars of the last user-perceived character (emoji ZWJ sequences, flags, letter + marks). */
    fun lastGraphemeLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val s = before.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val start = it.preceding(s.length)
        return s.length - if (start == BreakIterator.DONE) 0 else start
    }

    /**
     * Whether the next letter should be capitalised, from the text before the cursor and the field's cap flags.
     * This replaces `getCursorCapsMode`, so there is no call to the app per key.
     */
    fun capsMode(before: CharSequence, inputType: Int): Boolean {
        val caps = inputType and (android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        if (caps == 0) return false
        if (caps and android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        if (before.isEmpty()) return true
        if (caps and android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) return before.last().isWhitespace()
        if (before.last() == '\n') return true
        var i = before.length
        while (i > 0 && before[i - 1] == ' ') i--
        if (i == before.length) return false // no space yet after the last character
        if (i == 0) return true
        return before[i - 1] in ".?!؟\n"
    }

    /** The word before the cursor for next-word context, or null at a sentence boundary. */
    fun previousWord(before: CharSequence): String? {
        var end = before.length
        while (end > 0 && before[end - 1] == ' ') end--
        var start = end
        while (start > 0 && Editor.isWordChar(before[start - 1].toString())) start--
        return if (start < end) before.substring(start, end) else null
    }

    private const val NO_SPACE_AFTER = "([{\"'«“‘/@#-"
    private const val NO_SPACE_BEFORE = ".,;:!?،؛؟)]}"

    /**
     * What to commit for dictated [text] (spec §7.2): a leading space only when the character before needs one, and a
     * mid-sentence capital lowercased when the lowercase form is an ordinary word ("and Then" → "and then"; "Yazin",
     * "I" and "NASA" stay). Whisper's punctuation is kept.
     */
    fun dictation(before: CharSequence, text: String, isWord: (String) -> Boolean): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        val first = t.takeWhile { it.isLetter() }
        val sentenceCapital = first.isNotEmpty() && first != "I" && first[0].isUpperCase() && first.drop(1).none { it.isUpperCase() }
        if (sentenceCapital && !atSentenceStart(before) && isWord(first.lowercase())) t = first.lowercase() + t.substring(first.length)
        val last = before.lastOrNull()
        val space = last != null && !last.isWhitespace() && last !in NO_SPACE_AFTER && t[0] !in NO_SPACE_BEFORE
        return if (space) " $t" else t
    }

    /**
     * Whether a glided word needs a space before it (glide spec §3.3): not at the start, after whitespace, or after an
     * opening bracket or quote. A straight quote opens when it starts the text or follows whitespace.
     */
    fun spaceBeforeGlide(before: CharSequence): Boolean {
        val last = before.lastOrNull() ?: return false
        if (last.isWhitespace() || last in "([{«“‘") return false
        if (last == '"' || last == '\'') return before.length >= 2 && !before[before.length - 2].isWhitespace()
        return true
    }

    /** A glided word in the shift state: caps lock gives all capitals, shift or auto-caps a capital first letter. */
    fun glideCase(word: String, caps: Caps): String = when (caps) {
        Caps.OFF -> word
        Caps.LOCKED -> word.uppercase()
        Caps.AUTO, Caps.ONE_SHOT -> word.replaceFirstChar { it.uppercaseChar() }
    }

    private fun atSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && before[i - 1] == ' ') i--
        return i == 0 || before[i - 1] in ".?!؟\n"
    }
}
