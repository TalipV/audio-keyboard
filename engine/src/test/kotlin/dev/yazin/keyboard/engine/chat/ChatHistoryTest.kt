package dev.yazin.keyboard.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    private val now = 1_800_000_000_000L
    private val h = ChatHistory()

    private fun msg(
        side: Side,
        text: String,
        time: Long = now,
        guessed: Boolean = false,
        source: Source = Source.SCREEN,
        sender: String = "",
        draft: String? = null,
    ) = Message(side, sender, text, time, guessed, source, draft)

    /** [count] messages a minute apart, sides alternating, texts long enough to match on guessed times. */
    private fun fill(count: Int) = (0 until count).map { i ->
        msg(if (i % 2 == 0) Side.THEM else Side.ME, "message number $i", time = now + i * MINUTE)
    }.also { h.addAll(it) }

    @Test fun theSameMessageWithinADayIsOneMessage() {
        assertTrue(h.add(msg(Side.THEM, "see you at the station")))
        assertFalse(h.add(msg(Side.THEM, " see  you at\nthe\u200E station ", time = now + 20 * HOUR)))
        assertFalse(h.add(msg(Side.THEM, "see you at the station", time = now - 23 * HOUR, source = Source.EXPORT)))
        assertEquals(1, h.size)
        assertEquals("see you at the station", h.messages.single().text)
    }

    @Test fun theSameTextTwoDaysApartIsTwoMessages() {
        assertTrue(h.add(msg(Side.ME, "ok")))
        assertTrue(h.add(msg(Side.ME, "ok", time = now + 2 * DAY)))
        assertEquals(listOf(now, now + 2 * DAY), h.messages.map { it.time })
        assertTrue(h.add(msg(Side.ME, "good morning habibi", time = now, source = Source.EXPORT)))
        assertTrue(h.add(msg(Side.ME, "good morning habibi", time = now + 2 * DAY, source = Source.EXPORT)))
        assertEquals(4, h.size)
    }

    @Test fun theSameTextFromTheOtherSideIsAnotherMessage() {
        assertTrue(h.add(msg(Side.THEM, "happy birthday!")))
        assertTrue(h.add(msg(Side.ME, "happy birthday!")))
        assertEquals(listOf(Side.THEM, Side.ME), h.messages.map { it.side })
    }

    @Test fun aGuessedTimeMergesWithTheExportsExactOne() {
        h.add(msg(Side.THEM, "are you coming to the wedding?", time = now, guessed = true, source = Source.SCREEN))
        h.add(msg(Side.THEM, "are you coming to the wedding?", time = now - 90 * DAY, guessed = false, source = Source.EXPORT))
        assertEquals(1, h.size)
        assertEquals(now - 90 * DAY, h.messages.single().time)
        assertFalse(h.messages.single().guessed)
        // the other way round: an exact time already stored stays
        assertFalse(h.add(msg(Side.THEM, "are you coming to the wedding?", time = now + 5 * DAY, guessed = true)))
        assertEquals(now - 90 * DAY, h.messages.single().time)
    }

    @Test fun shortGuessedTextsDontMergeAcrossDays() {
        assertTrue(h.add(msg(Side.THEM, "ok", time = now, guessed = true)))
        assertTrue(h.add(msg(Side.THEM, "ok", time = now - 90 * DAY, source = Source.EXPORT)))
        assertEquals(2, h.size)
        assertEquals(listOf(false, true), h.messages.map { it.guessed })
    }

    @Test fun digitsLongTextAndBlanksAreNeverStoredFromEitherSide() {
        for (side in Side.entries) {
            assertFalse(h.add(msg(side, "code 482913")))
            assertFalse(h.add(msg(side, "call me 050-123-4567")))
            assertFalse(h.add(msg(side, "x".repeat(2_001))))
            assertFalse(h.add(msg(side, "  ")))
            assertFalse(h.add(msg(side, "\u200B\u200E ")))
        }
        assertEquals(0, h.size)
        assertTrue(h.add(msg(Side.THEM, "x".repeat(2_000))))
        assertTrue(h.add(msg(Side.ME, "meet at 12:30")))
    }

    @Test fun trimsToTheNewest5000() {
        val ms = (0 until ChatHistory.MAX + 3).map { msg(Side.THEM, "message $it", time = now + it * MINUTE) }
        h.addAll(ms)
        assertEquals(ChatHistory.MAX, h.size)
        assertEquals("message 3", h.messages.first().text)
        assertEquals("message 5002", h.messages.last().text)
        assertFalse(h.add(msg(Side.ME, "older than everything", time = now - DAY)))
        assertEquals(ChatHistory.MAX, h.size)
        assertEquals("message 3", h.messages.first().text)
    }

    @Test fun pairsJoinRunsAndRankBySharedWords() {
        h.add(msg(Side.THEM, "hey", time = now))
        h.add(msg(Side.THEM, "you\naround?", time = now + MINUTE))
        h.add(msg(Side.ME, "yeah what's up", time = now + 2 * MINUTE))
        h.add(msg(Side.THEM, "dinner saturday?", time = now + 3 * MINUTE))
        h.add(msg(Side.ME, "yes!", time = now + 4 * MINUTE))
        h.add(msg(Side.THEM, "unanswered", time = now + 5 * MINUTE))
        assertEquals(
            listOf("dinner saturday?" to "yes!", "hey / you around?" to "yeah what's up"),
            h.pairs("dinner tonight"),
        )
        // no shared words: newest first
        assertEquals("dinner saturday?" to "yes!", h.pairs("").first())
        assertEquals(1, h.pairs("", n = 1).size)
    }

    @Test fun pairRunsAreCutTo300Characters() {
        h.add(msg(Side.THEM, "a".repeat(250), time = now))
        h.add(msg(Side.THEM, "b".repeat(250), time = now + MINUTE))
        h.add(msg(Side.ME, "sure", time = now + 2 * MINUTE))
        val (theirs, mine) = h.pairs("").single()
        assertEquals(300, theirs.length)
        assertTrue(theirs.startsWith("a".repeat(250) + " / b"))
        assertEquals("sure", mine)
    }

    @Test fun earlierEndsWhereTheScreenBegins() {
        val all = fill(100)
        assertEquals(all.subList(50, 90), h.earlier(all.subList(90, 100)))
        // a later screen read with guessed times still finds its place
        val read = all.subList(90, 100).map { it.copy(time = now + 3 * DAY, guessed = true) }
        assertEquals(all.subList(50, 90), h.earlier(read))
        assertEquals(all.subList(0, 5), h.earlier(all.subList(5, 10)))
    }

    @Test fun earlierStartsAtTheScreensOwnCopyOfARepeatedFirstLine() {
        val greeting = "good morning habibi"
        (0 until 50).forEach { h.add(msg(Side.ME, "old filler number $it", time = now - 95 * DAY + it * MINUTE)) }
        for ((day, tag) in listOf(-90 to "ninety", -30 to "thirty", -1 to "yesterday")) {
            h.add(msg(Side.THEM, greeting, time = now + day * DAY, source = Source.EXPORT))
            (0 until 3).forEach { k ->
                h.add(msg(if (k % 2 == 0) Side.ME else Side.THEM, "after $tag $k", time = now + day * DAY + (k + 1) * MINUTE))
            }
        }
        val all = h.messages
        fun copyAt(day: Int) = all.indexOfFirst { it.text == greeting && it.time == now + day * DAY }
        fun readOf(tag: String) = listOf(msg(Side.THEM, greeting, time = now, guessed = true)) +
            all.filter { it.text.startsWith("after $tag ") }.map { it.copy(time = now, guessed = true) }

        // the screen's lines follow the newest copy
        assertEquals(all.subList(copyAt(-1) - 40, copyAt(-1)), h.earlier(readOf("yesterday")))
        // they follow an older copy: alignment decides, not recency
        assertEquals(all.subList(copyAt(-30) - 40, copyAt(-30)), h.earlier(readOf("thirty")))
        assertEquals(all.subList(0, copyAt(-90)).takeLast(40), h.earlier(readOf("ninety")))
        // nothing to align on: the newest copy
        assertEquals(all.subList(copyAt(-1) - 40, copyAt(-1)), h.earlier(readOf("yesterday").take(1)))
    }

    @Test fun earlierWithoutAMatchIsTheLast40OffScreen() {
        val all = fill(100)
        val screen = listOf(msg(Side.THEM, "never seen before"), all[98], all[99])
        assertEquals(all.subList(58, 98), h.earlier(screen))
        assertEquals(all.subList(60, 100), h.earlier(listOf(msg(Side.ME, "nothing like it"))))
    }

    @Test fun latestTheirsPrefersTheScreen() {
        assertEquals("", h.latestTheirs(emptyList()))
        h.add(msg(Side.THEM, "stored one", time = now))
        h.add(msg(Side.ME, "mine", time = now + MINUTE))
        assertEquals("stored one", h.latestTheirs(emptyList()))
        assertEquals("stored one", h.latestTheirs(listOf(msg(Side.ME, "on screen, mine"))))
        val screen = listOf(msg(Side.THEM, "first"), msg(Side.THEM, "second"), msg(Side.ME, "reply"))
        assertEquals("second", h.latestTheirs(screen))
    }

    @Test fun writeThenReadRoundTrips() {
        h.add(msg(Side.THEM, "tab\there\nnew line \\ slash", time = now, sender = "Sam\tB", source = Source.EXPORT))
        h.add(msg(Side.ME, "ya see u at 8", time = now + MINUTE, source = Source.SENT, draft = "Sure,\tsee you\nat 8!"))
        h.add(msg(Side.THEM, "guessed one here", time = now + 2 * MINUTE, guessed = true))
        val out = StringBuilder()
        h.write(out)
        val back = ChatHistory()
        back.read(out.lineSequence())
        assertEquals(h.messages, back.messages)
        assertEquals(3, out.lines().count { it.startsWith("m\t") })
    }

    @Test fun aDamagedLineIsSkipped() {
        h.add(msg(Side.ME, "kept line"))
        val out = StringBuilder()
        h.write(out)
        val good = out.lines().first()
        val f = good.split('\t')
        val lines = listOf(
            "m\tbroken",
            "x" + good.drop(1),
            f.toMutableList().apply { this[1] = "YOU" }.joinToString("\t"),
            f.toMutableList().apply { this[2] = "soon" }.joinToString("\t"),
            f.toMutableList().apply { this[3] = "maybe" }.joinToString("\t"),
            f.toMutableList().apply { this[4] = "PIGEON" }.joinToString("\t"),
            good + "\textra",
            "",
            good,
        )
        val back = ChatHistory()
        back.read(lines.asSequence())
        assertEquals(h.messages, back.messages)
    }

    @Test fun importing20000LinesIsFast() {
        val ms = (0 until 20_000).map { i ->
            msg(if (i % 3 == 0) Side.ME else Side.THEM, if (i % 10 == 0) "ok" else "line $i about the plan", time = now + i * MINUTE, source = Source.EXPORT)
        }
        val start = System.nanoTime()
        val added = h.addAll(ms)
        val again = h.addAll(ms.takeLast(1_000))
        val ms1 = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms1 ms", ms1 < 1_000)
        assertEquals(ChatHistory.MAX, h.size)
        assertEquals(ChatHistory.MAX, added)
        assertEquals(0, again)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
