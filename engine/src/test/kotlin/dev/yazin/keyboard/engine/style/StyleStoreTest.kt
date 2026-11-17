package dev.yazin.keyboard.engine.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleStoreTest {
    private var t = 1_000_000L
    private val store = StyleStore { t++ }
    private val wa = "com.whatsapp"

    @Test fun keepsTrimmedTextAndSkipsWhatMustNotBeLearned() {
        assertTrue(store.add("  haha thanks man \n", wa, "Sam"))
        assertFalse(store.add("   ", wa, "Sam"))
        assertFalse(store.add("your code is 123456", wa, "Sam"))
        assertFalse(store.add("رقمي ٠٥٥١٢٣٤٥", wa, "Sam")) // Arabic-Indic digits count too
        assertFalse(store.add("x".repeat(StyleStore.MAX_CHARS + 1), wa, "Sam"))
        assertEquals(listOf("haha thanks man"), store.pick(wa, "Sam", "").map { it.text })
    }

    @Test fun anUnchangedDraftIsNotLearnedAnEditedOneIs() {
        assertFalse(store.add("Sure, see you at 8!", wa, "Sam", draft = "Sure, see you at 8!"))
        assertTrue(store.add("ya see u at 8", wa, "Sam", draft = "Sure, see you at 8!"))
        val e = store.edits(wa, "Sam").single()
        assertEquals("Sure, see you at 8!" to "ya see u at 8", e.draft to e.text)
    }

    @Test fun aResendReplacesTheOldSample() {
        store.add("on my way", wa, "Sam")
        store.add("on my way", wa, "Sam")
        store.add("on my way", wa, "Mum") // another chat is another sample
        assertEquals(2, store.size)
    }

    @Test fun pickTakesThisChatThenThisAppThenAnyRankedBySharedWords() {
        store.add("slack thing", "com.slack", "")
        store.add("other chat", wa, "Mum")
        store.add("random", wa, "Sam")
        store.add("dinner tonight?", wa, "Sam")
        val picked = store.pick(wa, "Sam", "Sam\nare we still on for dinner?", n = 3).map { it.text }
        assertEquals(listOf("dinner tonight?", "random", "other chat"), picked)
    }

    @Test fun pickCapsThisChatAtTwoThirds() {
        repeat(20) { store.add("sam $it", wa, "Sam") }
        repeat(20) { store.add("mum $it", wa, "Mum") }
        val picked = store.pick(wa, "Sam", "")
        assertEquals(StyleStore.PICK, picked.size)
        assertEquals(10, picked.count { it.chat == "Sam" })
    }

    @Test fun withoutAChatTitlePickUsesTheApp() {
        store.add("in whatsapp", wa, "Sam")
        store.add("in slack", "com.slack", "general")
        assertEquals("in whatsapp", store.pick(wa, "", "", n = 1).single().text)
        assertTrue(store.mine(wa, "").isEmpty())
    }

    @Test fun mineHasOnlyLongEnoughLinesOfThisChat() {
        store.add("haha\nwho doesn't love free stuff", wa, "Reunion")
        store.add("congrats on the launch!!", wa, "Other")
        assertEquals(setOf("haha who doesn't love free stuff", "who doesn't love free stuff"), store.mine(wa, "Reunion"))
    }

    @Test fun theOldestGoFirstAtTheCap() {
        repeat(StyleStore.MAX_SAMPLES + 1) { store.add("message $it", wa, "Sam") }
        assertEquals(StyleStore.MAX_SAMPLES, store.size)
        assertFalse(store.pick(wa, "Sam", "", n = StyleStore.MAX_SAMPLES).any { it.text == "message 0" })
    }

    @Test fun importsSkipDuplicatesAndRankOlderThanCaptures() {
        store.add("captured one", wa, "Nora")
        assertEquals(2, store.import(listOf("imported a", "captured one", "imported b", "imported a", "123456789"), wa, "Nora"))
        assertEquals(3, store.size)
        assertEquals("captured one", store.pick(wa, "Nora", "", n = 1).single().text)
    }

    @Test fun importKeepsOnlyTheNewestLines() {
        val n = store.import((1..StyleStore.MAX_IMPORT + 5).map { "line $it" }, wa, "Big group")
        assertEquals(StyleStore.MAX_IMPORT, n)
        assertFalse(store.pick(wa, "Big group", "", n = StyleStore.MAX_SAMPLES).any { it.text == "line 1" })
    }

    @Test fun roundTripsThroughTsvWithTabsAndNewlines() {
        store.add("line one\nline\ttwo", wa, "Group\twith tab", draft = "draft\\here")
        store.add("plain", "com.slack", "")
        val out = StringBuilder().also { store.write(it) }.toString()
        assertFalse(store.dirty)
        val back = StyleStore().apply { read(out.lineSequence()) }
        assertEquals(store.pick(wa, "Group\twith tab", "", n = 10), back.pick(wa, "Group\twith tab", "", n = 10))
        assertEquals(2, back.size)
    }

    @Test fun damagedLinesAreSkipped() {
        val back = StyleStore().apply { read(sequenceOf("s\tnope\ta\tb\t\tc", "junk", "s\t5\tcom.whatsapp\tSam\t\tok then")) }
        assertEquals(1, back.size)
    }

    @Test fun clearForgetsEverything() {
        store.add("hello there", wa, "Sam")
        store.clear()
        assertEquals(0, store.size)
        assertTrue(store.dirty)
    }

    @Test fun forgetDeletesOneChatsSamples() {
        store.add("hello there", wa, "Sam")
        store.add("see you soon", wa, "Sam\u200e ")
        store.add("hello there", wa, "Lina")
        store.add("hello there", "org.telegram.messenger", "Sam")
        store.write(StringBuilder())
        store.forget(wa, "Sam")
        assertEquals(listOf("Lina", "Sam"), store.samples().map { it.chat })
        assertTrue(store.dirty)
        store.write(StringBuilder())
        store.forget(wa, "Omar")
        assertFalse(store.dirty)
    }

    @Test fun moveFilesOneChatsSamplesUnderAnother() {
        store.add("hello there", wa, "Sam")
        store.add("see you soon", wa, "Sam")
        store.add("hello there", wa, "Sam B")
        store.move(wa, "Sam", "Sam B")
        assertEquals(setOf("hello there", "see you soon"), store.samples().map { it.text }.toSet())
        assertEquals(listOf("Sam B", "Sam B"), store.samples().map { it.chat })
        assertEquals(setOf("hello there", "see you soon"), store.pick(wa, "Sam B", "").map { it.text }.toSet())
    }

    @Test fun numbersWithSpacesOrDashesAreNotLearned() {
        assertFalse(store.add("card 4111 1111 1111 1111", wa, "Sam"))
        assertFalse(store.add("call me on 050-123-4567", wa, "Sam"))
        assertTrue(store.add("see you at 15:30, it's 2 km away", wa, "Sam"))
    }

    @Test fun mineMatchesABubbleAsTheScreenFlattensIt() {
        store.add("haha\nwho  doesn't love free stuff", wa, "Reunion")
        assertTrue("haha who doesn't love free stuff" in store.mine(wa, "Reunion")) // ScreenText collapses whitespace
    }

    @Test fun aRecentAnswerSentUnchangedIsNotLearnedEvenWithoutItsDraft() {
        store.answered("Sure, see you at 8!")
        assertFalse(store.add("Sure, see you at 8!", wa, "Sam")) // e.g. the field's input restarted in between
        assertTrue(store.add("sure see u at 8", wa, "Sam"))
    }
}
