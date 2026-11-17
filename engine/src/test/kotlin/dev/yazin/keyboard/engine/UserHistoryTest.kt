package dev.yazin.keyboard.engine

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHistoryTest {
    private var time = 1_000L * 86_400_000
    private val h = UserHistory { time }

    @Test fun aNewWordIsKnownAfterTwoUses() {
        h.learn("yalla", null)
        assertFalse(h.isKnown("yalla"))
        h.learn("yalla", null)
        assertTrue(h.isKnown("yalla"))
        assertEquals(listOf("yalla"), h.knownWords())
    }

    @Test fun addingAWordMakesItKnownAtOnce() {
        h.add("Gulraiz")
        assertTrue(h.isKnown("Gulraiz"))
        assertEquals(listOf("Gulraiz"), h.userWords())
    }

    @Test fun removingBlocksTheWordFromComingBack() {
        repeat(3) { h.learn("teh", null) }
        h.remove("teh")
        assertTrue(h.isBlocked("teh"))
        h.learn("teh", null)
        h.learn("teh", null)
        assertFalse(h.isKnown("teh"))
        h.add("teh")
        assertFalse(h.isBlocked("teh"))
    }

    @Test fun scoresDecayWithAHalfLifeOfSixtyDays() {
        repeat(4) { h.learn("hello", null) }
        val fresh = h.score("hello")
        time += 60L * 86_400_000
        assertEquals(Math.log(1.0 + 2.0).toFloat(), h.score("hello"), 1e-4f)
        assertTrue(fresh > h.score("hello"))
        assertEquals(0f, h.score("unknown"), 0f)
    }

    @Test fun pairsDriveNextWordsAfterTwoUses() {
        h.learn("good", null)
        h.learn("morning", "good")
        assertEquals(emptyList<String>(), h.nextWords("good"))
        h.learn("morning", "good")
        assertEquals(listOf("morning"), h.nextWords("good"))
        assertTrue(h.pairScore("good", "morning") > 0f)
    }

    @Test fun roundTripsThroughText() {
        h.add("Gulraiz")
        repeat(2) { h.learn("yalla", "ok") }
        h.remove("teh")
        val out = StringWriter().also { h.write(it) }.toString()
        assertFalse(h.dirty)
        val copy = UserHistory { time }.apply { read(StringReader(out)) }
        assertTrue(copy.isKnown("Gulraiz"))
        assertTrue(copy.isKnown("yalla"))
        assertTrue(copy.isBlocked("teh"))
        assertEquals(listOf("yalla"), copy.nextWords("ok"))
    }

    @Test fun aDamagedFileIsSkippedLineByLine() {
        val copy = UserHistory { time }.apply { read(StringReader("p\ta\tb\t3\t\nw\tok\t2\t1000\nw\tbad\tx\t1\n")) }
        assertTrue(copy.isKnown("ok"))
        assertFalse(copy.isKnown("bad"))
    }

    @Test fun knownWordsAreCachedUntilSomethingChanges() {
        h.add("a")
        val first = h.knownWords()
        assertTrue(first === h.knownWords())
        h.add("b")
        assertFalse(first === h.knownWords())
    }

    @Test fun clearForgetsEverything() {
        h.add("x")
        h.learn("y", null)
        h.clear()
        assertEquals(emptyList<String>(), h.userWords())
        assertTrue(h.dirty)
    }

    @Test fun hotwordsListAddedWordsThenLearnedByRecentUseAndStopAtTheLimit() {
        assertEquals(null, h.hotwords())
        repeat(2) { h.learn("old", null) }
        time += 120L * 86_400_000
        repeat(2) { h.learn("busy", null) }
        repeat(3) { h.learn("busy", null) }
        h.learn("once", null)
        h.add("Yazin")
        h.add("busy") // added words keep their place at the front, once
        assertEquals("Yazin, busy, old", h.hotwords())
        assertEquals("Yazin, busy", h.hotwords(maxChars = 11))
        assertEquals("Yazin", h.hotwords(maxChars = 10))
        assertEquals("Yazin, busy", h.hotwords { it == "old" || it == "Yazin" }) // only learned words are filtered
    }
}
