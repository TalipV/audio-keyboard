package dev.yazin.keyboard.engine.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicksTest {
    private var time = 0L
    private val store = Picks { time++ }

    private fun send(text: String, chat: String = "Sam") = store.sent(text, "a", chat, text, emptyList())
    private fun all() = store.pick("a", "Sam", 1000)

    @Test fun classificationIsExclusive() {
        store.sent("picked", "a", "Sam", "picked", listOf("picked", "other"))
        send("sent")
        store.sent("changed", "a", "Sam", "draft", listOf("draft"))
        store.sent("mine", "a", "Sam", null, listOf("ignored", "also ignored"))
        store.sent("ordinary", "a", "Sam", null, emptyList())
        assertEquals(5, store.size)
        assertEquals(listOf(Verdict.IGNORED, Verdict.IGNORED, Verdict.SENT, Verdict.PICKED), all().map { it.verdict })
        assertEquals(listOf("mine", "mine"), all().take(2).map { it.instead })
        val out = StringBuilder()
        store.write(out)
        assertTrue(out.contains("\tEDITED\tchanged\tdraft\n"))
    }

    @Test fun privacyGuardsApplyToBothSides() {
        for (bad in listOf(" ", "x".repeat(301), "123456", "٠٥٥١٢٣٤٥", "050-123-4567")) {
            send(bad)
            store.sent(bad, "a", "Sam", "safe", emptyList())
            store.sent(bad, "a", "Sam", null, listOf("safe"))
            store.sent("safe", "a", "Sam", null, listOf(bad))
        }
        assertEquals(0, store.size)
        assertFalse(store.dirty)
        send("  trimmed  ")
        send("x".repeat(300))
        assertEquals("trimmed", all().last().text)
        assertEquals(2, store.size)
    }

    @Test fun newestWinsPerTextAppChatAndVerdict() {
        send("same")
        send("other")
        send("same")
        store.sent("same", "a", "Sam", "same", listOf("same"))
        send("same", "Mum")
        assertEquals(4, store.size)
        assertEquals(listOf("same", "same", "other", "same"), all().map { it.text })
        assertEquals(Verdict.PICKED, all().first().verdict)
    }

    @Test fun capsByDroppingOldest() {
        repeat(Picks.MAX_PICKS + 2) { send("message $it letters") }
        assertEquals(Picks.MAX_PICKS, store.size)
        assertEquals("message 2 letters", all().last().text)
        assertEquals(Picks.PROMPT, store.pick("a", "Sam").size)
    }

    @Test fun localFirstWithOnlyPositiveFallback() {
        send("local")
        store.sent("mine", "a", "Sam", null, listOf("local ignored"))
        send("elsewhere", "Mum")
        store.sent("mine", "a", "Mum", null, listOf("foreign ignored"))
        store.sent("edited", "a", "Sam", "draft", emptyList())
        store.sent("other app", "b", "Sam", "other app", emptyList())
        assertEquals(listOf("local ignored", "local", "other app", "elsewhere"), all().map { it.text })
        assertEquals(listOf("local ignored", "local"), store.pick("a", "Sam", 2).map { it.text })
    }

    @Test fun titlesCompareCleanedAndBlankTitleHasNoLocals() {
        store.sent("mine", "a", "Sam ", null, listOf("raw ignored"))
        assertEquals(listOf("raw ignored"), store.pick("a", "Sam", 5).filter { it.verdict == Verdict.IGNORED }.map { it.text })
        // A real merge rewrites to the destination's cleaned title; a raw-title lookup still matches.
        store.sent("yep", "a", "Sam\u202F", "yep", listOf("yep")) // PICKED under a title with an invisible mark
        store.move("a", "Sam", "Sara")
        assertEquals(listOf("yep", "raw ignored"), store.pick("a", "Sara ", 5).map { it.text })
        // A blank title can come from any chat: positives only, titleless ones included.
        store.sent("own", "a", "", null, listOf("ignored titleless"))
        store.sent("kept", "a", "", "kept", emptyList()) // SENT, no chat
        assertEquals(listOf("kept", "yep"), store.pick("a", "", 5).map { it.text })
    }

    @Test fun roundTripAndChatOperations() {
        send("tabs\tand\nslashes\\", " Sam\u00a0 ")
        send("same", "Sam")
        send("same", "Mum")
        store.sent("mine\ntext", "a", "Mum", null, listOf("no thanks"))
        val out = StringBuilder()
        store.write(out)
        assertFalse(store.dirty)
        val copy = Picks()
        copy.read((out.toString() + "broken\np\tbad\na\n").lineSequence())
        assertEquals(store.size, copy.size)
        assertEquals(store.pick("a", "Mum"), copy.pick("a", "Mum"))
        assertFalse(copy.dirty)
        copy.move("a", " Sam ", "Mum")
        assertTrue(copy.dirty)
        assertEquals(3, copy.size)
        assertEquals(2L, copy.pick("a", "Mum").first { it.text == "same" }.time)
        copy.forget("a", " Mum\u00a0")
        assertEquals(0, copy.size)
        send("remaining")
        store.clear()
        assertEquals(0, store.size)
        assertTrue(store.dirty)
    }
}
