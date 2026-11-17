package dev.yazin.keyboard.engine.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPrefsTest {
    private fun cp(vararg c: Int) = c.joinToString("") { String(Character.toChars(it)) }

    private val wave = Emoji(cp(0x1F44B), listOf(cp(0x1F44B, 0x1F3FB), cp(0x1F44B, 0x1F3FF)))
    private val smile = cp(0x1F600)

    @Test fun recentsAreNewestFirstOncePerEmoji() {
        val p = EmojiPrefs()
        p.used(smile, smile)
        p.used(wave.base, wave.base)
        p.used(smile, smile)
        assertEquals(listOf(smile, wave.base), p.recent.map { it.first })
        assertTrue(p.dirty)
    }

    @Test fun recentsKeepThreeRows() {
        val p = EmojiPrefs()
        for (i in 0 until 30) p.used(cp(0x1F600 + i), cp(0x1F600 + i))
        assertEquals(27, p.recent.size)
        assertEquals(cp(0x1F600 + 29), p.recent.first().first)
    }

    @Test fun aChosenToneIsRememberedAndShownInRecents() {
        val p = EmojiPrefs()
        assertEquals(wave.base, p.forTap(wave))
        p.used(wave.base, wave.variants[1])
        assertEquals(wave.variants[1], p.forTap(wave))
        assertEquals(wave.base to wave.variants[1], p.recent.first())
        p.used(wave.base, wave.base) // picking the base again forgets the tone
        assertEquals(wave.base, p.forTap(wave))
    }

    @Test fun aToneTheDeviceNoLongerOffersFallsBackToTheBase() {
        val p = EmojiPrefs()
        p.used(wave.base, cp(0x1F44B, 0x1F3FD))
        assertEquals(wave.base, p.forTap(wave))
    }

    @Test fun survivesARestartAndSkipsDamagedLines() {
        val p = EmojiPrefs()
        p.used(smile, smile)
        p.used(wave.base, wave.variants[0])
        val out = StringBuilder()
        p.write(out)
        val q = EmojiPrefs()
        q.read((out.toString() + "garbage\nr\tonly-two\n").lineSequence())
        assertEquals(p.recent, q.recent)
        assertEquals(wave.variants[0], q.forTap(wave))
    }

    @Test fun nothingIsRememberedFromIncognitoFields() {
        // Spec §4: password and incognito fields get no history.
        val p = EmojiPrefs()
        p.used(wave.base, wave.variants[0], remember = false)
        assertEquals(emptyList<Pair<String, String>>(), p.recent)
        assertEquals(wave.base, p.forTap(wave))
        assertTrue(!p.dirty)
    }
}
