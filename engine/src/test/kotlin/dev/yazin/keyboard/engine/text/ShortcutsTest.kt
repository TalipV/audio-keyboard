package dev.yazin.keyboard.engine.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val s = Shortcuts()

    @Test fun aTriggerOffersItsTextInAnyCase() {
        s.put("mfg", "Mit freundlichen Grüßen")
        assertEquals(listOf("Mit freundlichen Grüßen"), s.matches("mfg"))
        assertEquals(listOf("Mit freundlichen Grüßen"), s.matches("Mfg"))
        assertEquals(emptyList<String>(), s.matches("mf"))
        assertEquals(emptyList<String>(), s.matches("mfgx"))
        assertEquals(emptyList<String>(), s.matches(""))
    }

    @Test fun anyApostropheMatches() {
        s.put("'alaykum", "wa ʿalaykum as-salām")
        assertEquals(listOf("wa ʿalaykum as-salām"), s.matches("'alaykum"))
        assertEquals(listOf("wa ʿalaykum as-salām"), s.matches("’alaykum"))
        assertEquals(listOf("wa ʿalaykum as-salām"), s.matches("'Alaykum"))
    }

    @Test fun autocorrectLeavesShortcutsAndTheirBeginningsAlone() {
        s.put("talip.", "talip@example.org")
        s.put("mfg", "Mit freundlichen Grüßen")
        assertTrue(s.protects("talip", ".")) // the dot completes it
        assertTrue(s.protects("Mfg", " ")) // a shortcut itself
        assertFalse(s.protects("talip", " "))
        assertFalse(s.protects("tal", " "))
        assertFalse(s.protects("", "."))
    }

    @Test fun digitsWorkForAPhoneNumber() {
        s.put("017", "+49 170 1234567")
        assertEquals(listOf("+49 170 1234567"), s.matches("017"))
    }

    @Test fun oneTriggerCanHoldTwoTexts() {
        s.put("adr", "Musterstraße 1")
        s.put("adr", "Hauptstraße 2")
        s.put("adr", "Musterstraße 1") // the same again is one
        assertEquals(listOf("Musterstraße 1", "Hauptstraße 2"), s.matches("adr"))
    }

    @Test fun triggersHaveNoSpacesButMayHoldPunctuation() {
        assertFalse(s.put("m f g", "x"))
        assertTrue(s.put("e-mail", "talip@example.org"))
        assertTrue(s.put("+49", "+49 170 1234567"))
        assertEquals(listOf("+49 170 1234567"), s.matches("+49"))
        s.clear()
        assertFalse(s.put("ok", "  "))
        assertTrue(s.put(" lg ", "Liebe Grüße"))
        assertEquals("lg", s.all().single().trigger)
    }

    @Test fun editingReplacesTheOldShortcut() {
        s.put("lg", "Liebe Grüße")
        val old = s.all().single()
        s.put("lg", "Liebe Grüße, Talip", old)
        assertEquals(listOf("Liebe Grüße, Talip"), s.matches("lg"))
        s.remove(s.all().single())
        assertEquals(emptyList<Shortcut>(), s.all())
    }

    @Test fun shortcutsSurviveARestartWithLineBreaksAndTabs() {
        s.put("sig", "Talip\nTel. 0170\t123")
        s.put("ok", "Okay 👍")
        val out = StringBuilder()
        s.write(out)
        val again = Shortcuts()
        again.read(out.lineSequence())
        assertEquals(s.all(), again.all())
    }

    @Test fun importReadsTabsOrEqualsAndExportRoundTrips() {
        val n = s.import(sequenceOf("mfg\tMit freundlichen Grüßen", "lg = Liebe Grüße", "kein trenner", "sig\tZeile 1\\nZeile 2", "bad trigger\tx"))
        assertEquals(3, n)
        assertEquals(listOf("Zeile 1\nZeile 2"), s.matches("sig"))
        val out = StringBuilder()
        s.export(out)
        val again = Shortcuts()
        assertEquals(3, again.import(out.lineSequence()))
        assertEquals(s.all(), again.all())
    }
}
