package dev.yazin.keyboard.engine

import java.text.Normalizer

/**
 * How typed text is compared with dictionary words:
 * - case-insensitive
 * - Latin accents ignored
 * - Arabic letters that people type without hamza unified: أ إ آ ٱ → ا, ة → ه, ى → ي, ؤ → و, ئ → ي
 * - tashkeel, tatweel and apostrophes skipped, since typists leave them out
 */
object Fold {
    private val latin = CharArray(0x250) { i ->
        Normalizer.normalize(i.toChar().toString(), Normalizer.Form.NFD)[0].lowercaseChar()
    }

    fun key(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ة' -> 'ه'
        'ى', 'ئ' -> 'ي'
        'ؤ' -> 'و'
        else -> if (c.code < latin.size) latin[c.code] else c.lowercaseChar()
    }

    fun isSkippable(c: Char): Boolean = c in 'ً'..'ْ' || c == 'ـ' || c == '\'' || c == '’'

    /** The folded typing form of [s]: skippables removed, everything else folded. */
    fun word(s: CharSequence): String = buildString { for (c in s) if (!isSkippable(c)) append(key(c)) }
}
