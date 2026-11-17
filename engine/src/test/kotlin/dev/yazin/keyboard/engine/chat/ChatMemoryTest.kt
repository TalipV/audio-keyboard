package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.style.ChatLine
import dev.yazin.keyboard.engine.style.StyleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatMemoryTest {
    private class MemStorage : ChatStorage {
        val files = HashMap<String, List<String>>()
        val reads = ArrayList<String>()

        override fun read(name: String): List<String>? {
            reads += name
            return files[name]
        }

        override fun write(name: String, lines: (Appendable) -> Unit) {
            val out = StringBuilder()
            lines(out)
            files[name] = out.lines().filter { it.isNotEmpty() }
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private var t = 1_800_000_000_000L
    private val storage = MemStorage()
    private val mem = ChatMemory(storage) { t }
    private val nora = ChatKey("com.whatsapp", "Nora")
    private val family = ChatKey("com.whatsapp", "Family")

    private fun msg(side: Side, text: String, time: Long = t, sender: String = "") =
        Message(side, sender, text, time, false, Source.SCREEN)

    @Test fun sentAndCapturedMessagesLandInTheirChat() {
        mem.sent(nora, "see you at the station", null)
        t += MINUTE
        val screen = listOf(msg(Side.ME, "see you at the station"), msg(Side.THEM, "on my way"))
        assertEquals(1, mem.capture(nora, screen))
        assertEquals(1, mem.capture(family, listOf(msg(Side.THEM, "dinner at eight?", sender = "Lina"))))

        assertEquals(2, mem.info(nora)!!.count)
        assertEquals(t, mem.info(nora)!!.lastSeen)
        assertEquals("Me: see you at the station\nNora: on my way", mem.historyText(nora))
        assertEquals("Lina: dinner at eight?", mem.historyText(family))
        assertNull(mem.info(ChatKey("com.whatsapp", "Sam")))
        assertEquals(0, mem.capture(ChatKey("com.whatsapp", "Sam"), listOf(msg(Side.THEM, "123456789"))))
        assertNull(mem.info(ChatKey("com.whatsapp", "Sam")))

        mem.save()
        val again = ChatMemory(storage) { t }
        assertEquals("Me: see you at the station\nNora: on my way", again.historyText(nora))
        assertEquals(2, again.info(nora)!!.count)
    }

    @Test fun aChatSetNotToRememberCapturesNothing() {
        mem.setRemember(nora, false)
        assertEquals(0, mem.capture(nora, listOf(msg(Side.THEM, "on my way"))))
        mem.sent(nora, "see you there", null)
        assertEquals(0, mem.info(nora)!!.count)
        assertFalse(mem.info(nora)!!.remember)
        assertEquals("", mem.historyText(nora))

        mem.setRemember(nora, true)
        assertEquals(1, mem.capture(nora, listOf(msg(Side.THEM, "on my way"))))
    }

    @Test fun importKeepsEverySenderAndMarksTheProfileDue() {
        val lines = listOf(
            ChatLine("Omar", "who's coming tonight", t),
            ChatLine("Lina", "me and Sam", t + MINUTE),
            ChatLine("Sam", "bringing dessert", t + 2 * MINUTE),
            ChatLine("Omar", "perfect", t + 3 * MINUTE),
        )
        assertEquals(4, mem.import(family, lines, owner = "Omar"))
        assertEquals("Me: who's coming tonight\nLina: me and Sam\nSam: bringing dessert\nMe: perfect", mem.historyText(family))
        assertTrue(mem.info(family)!!.profileDue)
        assertEquals(listOf(family), mem.due())
        assertEquals(0, mem.import(family, lines, owner = "Omar"))
    }

    @Test fun importIntoAChatNotToRememberAddsNothing() {
        mem.setRemember(family, false)
        val lines = listOf(ChatLine("Omar", "who's coming tonight", t), ChatLine("Lina", "me and Sam", t + MINUTE))
        assertEquals(0, mem.import(family, lines, owner = "Omar"))
        val info = mem.info(family)!!
        assertEquals(0, info.count)
        assertFalse(info.remember)
        assertFalse(info.profileDue)
        assertEquals("", mem.historyText(family))
    }

    @Test fun importedLinesWithoutTimesAreOlderThanEverything() {
        mem.sent(nora, "already in history", null)
        val lines = listOf(
            ChatLine("Nora", "an undated first line"),
            ChatLine("Omar", "an undated reply"),
            ChatLine("Nora", "a dated line", t - HOUR),
        )
        assertEquals(3, mem.import(nora, lines, owner = "Omar"))
        assertEquals(
            "Nora: an undated first line\nMe: an undated reply\nNora: a dated line\nMe: already in history",
            mem.historyText(nora),
        )
        assertTrue(mem.info(nora)!!.first < t - HOUR)
    }

    @Test fun aProfileRefreshesAfter30NewMessages() {
        mem.open(nora)
        mem.setWritten(nora, "Cousin.")
        assertEquals(29, mem.capture(nora, (0 until 29).map { msg(Side.THEM, "message number $it", t + it) }))
        assertEquals(emptyList<ChatKey>(), mem.due())
        assertEquals(1, mem.capture(nora, listOf(msg(Side.THEM, "one more message"))))
        assertEquals(listOf(nora), mem.due())
        assertEquals(30, mem.info(nora)!!.sinceProfile)

        t += HOUR
        mem.setWritten(nora, "Cousin; writes short Khaleeji messages.")
        assertEquals(emptyList<ChatKey>(), mem.due())
        assertEquals(0, mem.info(nora)!!.sinceProfile)
        assertEquals(t, mem.info(nora)!!.writtenAt)
        assertEquals("Cousin; writes short Khaleeji messages.", mem.info(nora)!!.written)
    }

    @Test fun firstIncomingMessageMakesTopicsDue() {
        mem.sent(nora, "hello", null)
        assertTrue(mem.due().isEmpty())
        mem.capture(nora, listOf(msg(Side.THEM, "I want to learn pottery")))
        assertEquals(listOf(nora), mem.due())
    }

    @Test fun topicsSurviveReloadLegacyUpdatesAndMergeButNotForget() {
        mem.open(nora)
        mem.setWritten(nora, "Cousin.", listOf("Pottery: tried a class?", "Pottery: tried a class?"))
        mem.save()
        val again = ChatMemory(storage) { t }
        assertEquals(listOf("Pottery: tried a class?"), again.info(nora)!!.topics)
        again.setWritten(nora, "Updated profile without topics.")
        assertEquals(1, again.info(nora)!!.topics.size)
        again.merge(nora, family)
        assertEquals(listOf("Pottery: tried a class?"), again.info(family)!!.topics)
        again.setWritten(family, "Family.", emptyList())
        assertTrue(again.info(family)!!.topics.isEmpty())
        again.setWritten(family, "Family.", listOf("Pottery"))
        again.setRemember(family, false)
        again.forget(family)
        assertTrue(again.info(family)!!.topics.isEmpty())
        again.setWritten(nora, "Forgotten", listOf("Should stay gone"))
        assertNull(again.info(nora))
    }

    @Test fun regenerateMakesAProfileDueOnlyWithHistory() {
        mem.setGoal(nora, "Closer")
        mem.markDue(nora)
        assertEquals(emptyList<ChatKey>(), mem.due())
        mem.markDue(family)
        assertNull(mem.info(family))

        mem.capture(nora, listOf(msg(Side.THEM, "on my way")))
        mem.setWritten(nora, "Cousin.")
        assertEquals(emptyList<ChatKey>(), mem.due())
        mem.markDue(nora)
        assertEquals(listOf(nora), mem.due())
    }

    @Test fun openingThePanelMakesAnEmptyChatOnceAndSavesIt() {
        val first = mem.open(nora)
        assertEquals(0, first.count)
        assertTrue(first.remember)
        mem.capture(nora, listOf(msg(Side.THEM, "on my way")))
        assertEquals(first.id, mem.open(nora).id)
        assertEquals(1, mem.open(nora).count)

        mem.open(family)
        mem.save()
        assertNotNull(ChatMemory(storage) { t }.info(family))
    }

    @Test fun contextIsNullWithoutHistory() {
        assertNull(mem.context(nora, emptyList(), ""))
        mem.setGoal(nora, "Closer")
        assertNotNull(mem.info(nora))
        assertNull(mem.context(nora, emptyList(), ""))
    }

    @Test fun contextPutsCorrectionsBeforeTheWrittenPartAndPicksNotesByScreenWords() {
        mem.sent(nora, "morning habibti", null)
        t += MINUTE
        val screen = listOf(msg(Side.THEM, "how did the surgery go?"), msg(Side.ME, "it went fine alhamdulillah", t + MINUTE))
        mem.capture(nora, screen)
        mem.setWritten(nora, "Cousin. Writes in Khaleeji.")
        mem.addCorrection(nora, "He's my cousin, not a coworker")
        mem.addCorrection(nora, "We always speak Khaleeji")
        mem.setGoal(nora, "Get to know her better")
        val notes = listOf(
            "Started at Aramco in October",
            "Mum's surgery on 3 Nov",
            "Loves padel",
            "Sister is Lina",
            "Moving to Riyadh",
            "Birthday in March",
            "Has a cat called Simba",
        )
        for (n in notes) {
            t += DAY
            mem.addNote(nora, n)
        }

        val c = mem.context(nora, screen, "Nora\nhow did the surgery go?\nit went fine alhamdulillah")!!
        assertEquals("Nora", c.title)
        assertEquals("He's my cousin, not a coworker\nWe always speak Khaleeji\nCousin. Writes in Khaleeji.", c.profile)
        assertEquals("Get to know her better", c.goal)
        assertEquals(
            listOf("Mum's surgery on 3 Nov", "Has a cat called Simba", "Birthday in March", "Moving to Riyadh", "Sister is Lina"),
            c.notes,
        )
        assertEquals(listOf("how did the surgery go?" to "it went fine alhamdulillah"), c.pairs)
        assertEquals(listOf("Me: morning habibti"), c.earlier)

        mem.removeNote(nora, mem.info(nora)!!.notes.first { it.text == "Mum's surgery on 3 Nov" })
        assertEquals(6, mem.info(nora)!!.notes.size)
    }

    @Test fun mergeMovesHistoryAndCombinesGoalCorrectionsAndNotes() {
        val old = ChatKey("com.whatsapp", "Nora K")
        mem.sent(old, "an old message here", null)
        mem.setGoal(old, "Old goal")
        mem.addCorrection(old, "He's my cousin")
        mem.addCorrection(old, "We speak Khaleeji")
        mem.addNote(old, "Mum's surgery on 3 Nov")
        mem.save()
        val oldId = mem.info(old)!!.id
        t += HOUR
        mem.sent(nora, "a new message here", null)
        mem.setGoal(nora, "New goal")
        mem.addCorrection(nora, "We speak Khaleeji")
        mem.addNote(nora, "Started at Aramco")

        mem.merge(old, nora)
        assertNull(mem.info(old))
        assertFalse("$oldId.tsv" in storage.files)
        val info = mem.info(nora)!!
        assertEquals(2, info.count)
        assertEquals("New goal", info.goal)
        assertEquals(listOf("We speak Khaleeji", "He's my cousin"), info.corrections)
        assertEquals(setOf("Mum's surgery on 3 Nov", "Started at Aramco"), info.notes.map { it.text }.toSet())
        assertTrue(info.profileDue)
        assertEquals("Me: an old message here\nMe: a new message here", mem.historyText(nora))

        val sam = ChatKey("com.whatsapp", "Sam")
        val samNew = ChatKey("com.whatsapp", "Sam B")
        mem.setGoal(sam, "Keep it professional")
        mem.sent(samNew, "hello there Sam", null)
        mem.merge(sam, samNew)
        assertEquals("Keep it professional", mem.info(samNew)!!.goal)

        val again = ChatMemory(storage) { t }
        assertNull(again.info(old))
        assertEquals("Me: an old message here\nMe: a new message here", again.historyText(nora))
    }

    @Test fun forgetDeletesTheFile() {
        mem.sent(nora, "see you at the station", null)
        mem.addNote(nora, "Mum's surgery on 3 Nov")
        mem.save()
        val id = mem.info(nora)!!.id
        assertTrue("$id.tsv" in storage.files)

        mem.forget(nora)
        assertNull(mem.info(nora))
        assertFalse("$id.tsv" in storage.files)
        assertFalse(storage.files.getValue("index.tsv").any { "Nora" in it || "surgery" in it })
        assertNull(ChatMemory(storage) { t }.info(nora))

        mem.sent(nora, "a fresh start", null)
        assertTrue(mem.info(nora)!!.id > id)
    }

    @Test fun forgetMergeAndClearReachTheWritingSamples() {
        val style = StyleStore { t }
        val picks = dev.yazin.keyboard.engine.style.Picks { t }
        val m = ChatMemory(storage, style, picks) { t }
        val lina = ChatKey("com.whatsapp", "Lina")
        val linaNew = ChatKey("com.whatsapp", "Lina B")
        val omar = ChatKey("com.whatsapp", "Omar")
        style.add("see you at the station", "com.whatsapp", "Sam")
        style.add("on my way now", "com.whatsapp", "Lina")
        style.add("dinner at eight then", "com.whatsapp", "Omar")
        style.add("no title on this one", "com.whatsapp", "")
        m.seed(style)
        for (sample in style.samples()) {
            picks.sent(sample.text, sample.app, sample.chat, sample.text, emptyList())
        }

        m.forget(ChatKey("com.whatsapp", "Sam"))
        assertEquals(3, picks.size)
        assertEquals(listOf("Lina", "Omar", ""), style.samples().map { it.chat })
        m.sent(linaNew, "a newer message", null)
        m.merge(lina, linaNew)
        assertEquals(listOf("Lina B", "Omar", ""), style.samples().map { it.chat })
        assertEquals("Lina B", picks.pick("com.whatsapp", "Lina B").first().chat)
        m.clear()
        assertEquals(listOf(""), style.samples().map { it.chat })
        assertEquals(1, picks.size)
        assertNull(m.info(omar))
    }

    @Test fun forgettingAChatNotToRememberKeepsItsSwitch() {
        mem.sent(nora, "see you at the station", null)
        mem.setRemember(nora, false)
        mem.forget(nora)
        val info = mem.info(nora)!!
        assertFalse(info.remember)
        assertEquals(0, info.count)
        assertEquals("", mem.historyText(nora))
    }

    @Test fun clearForgetsEveryChat() {
        mem.sent(nora, "see you at the station", null)
        mem.sent(family, "who's coming tonight", null)
        mem.save()
        mem.clear()
        assertTrue(mem.all().isEmpty())
        assertEquals(setOf("index.tsv"), storage.files.keys)
    }

    @Test fun seedCopiesTitledStyleSamplesOnce() {
        val style = StyleStore { t }
        style.add("hi from the style store", "com.whatsapp", "Nora")
        t += MINUTE
        style.add("edited reply", "com.whatsapp", "Nora", draft = "the original draft")
        style.add("no title on this one", "com.whatsapp", "")
        style.add("chat list text", "com.whatsapp", "Ask Meta AI or Search")
        val firstTime = t - MINUTE

        mem.seed(style)
        assertEquals(listOf(nora), mem.all().map { it.key })
        assertEquals(2, mem.info(nora)!!.count)
        assertEquals(firstTime, mem.info(nora)!!.first)
        assertEquals("Me: hi from the style store\nMe: edited reply", mem.historyText(nora))
        mem.save()
        assertTrue(storage.files.getValue("${mem.info(nora)!!.id}.tsv").any { "the original draft" in it })

        style.add("added after seeding", "com.whatsapp", "Nora")
        mem.seed(style)
        assertEquals(2, mem.info(nora)!!.count)
        val again = ChatMemory(storage) { t }
        again.seed(style)
        assertEquals(2, again.info(nora)!!.count)
    }

    @Test fun seededImportsHaveGuessedTimesSoAReExportMergesThem() {
        val style = StyleStore { t }
        style.add("a real send before the import", "com.whatsapp", "Nora")
        t += DAY
        val mine = listOf("who's coming tonight", "bringing the dessert", "see you all there")
        assertEquals(3, style.import(mine, "com.whatsapp", "Nora"))
        mem.seed(style)
        assertEquals(4, mem.info(nora)!!.count)

        val export = listOf(
            ChatLine("Owner", mine[0], t - 30 * DAY),
            ChatLine("Nora", "me and my sister", t - 30 * DAY + MINUTE),
            ChatLine("Owner", mine[1], t - 30 * DAY + 2 * MINUTE),
            ChatLine("Nora", "perfect, see you", t - 30 * DAY + 3 * MINUTE),
            ChatLine("Owner", mine[2], t - 30 * DAY + 4 * MINUTE),
        )
        assertEquals(2, mem.import(nora, export, owner = "Owner"))
        assertEquals(6, mem.info(nora)!!.count)
        assertEquals(
            "Me: who's coming tonight\nNora: me and my sister\nMe: bringing the dessert\nNora: perfect, see you\n" +
                "Me: see you all there\nMe: a real send before the import",
            mem.historyText(nora),
        )
    }

    @Test fun seededSamplesDontMakeAProfileDue() {
        val style = StyleStore { t }
        repeat(350) {
            t += MINUTE
            style.add("seeded sample number $it", "com.whatsapp", "Nora")
        }
        mem.seed(style)
        assertEquals(350, mem.info(nora)!!.count)
        assertEquals(0, mem.info(nora)!!.sinceProfile)
        assertEquals(emptyList<ChatKey>(), mem.due())
    }

    @Test fun anUnreadableFileIsNeverReplacedByAnEmptyOne() {
        mem.sent(nora, "see you at the station", null)
        mem.save()
        val id = mem.info(nora)!!.id
        val history = storage.files.getValue("$id.tsv")
        val unreadable = object : ChatStorage by storage {
            override fun read(name: String): List<String>? = throw IOException("disk")
        }
        assertThrows(IOException::class.java) { ChatMemory(unreadable) { t } }

        val flaky = object : ChatStorage by storage {
            override fun read(name: String): List<String>? = if (name == "$id.tsv") throw IOException("disk") else storage.read(name)
        }
        val m = ChatMemory(flaky) { t }
        assertThrows(IOException::class.java) { m.capture(nora, listOf(msg(Side.THEM, "on my way"))) }
        m.save()
        assertEquals(history, storage.files["$id.tsv"])
        assertEquals("Me: see you at the station", ChatMemory(storage) { t }.historyText(nora))
    }

    @Test fun aNewChatNeverLoadsAStaleFileWithItsId() {
        // Opening the panel and setting a Goal both make the Chat.
        for (make in listOf<(ChatMemory, ChatKey) -> Unit>({ m, k -> m.open(k) }, { m, k -> m.setGoal(k, "Closer") })) {
            // The process died after writing 7.tsv and before the index that counts it.
            storage.files["index.tsv"] = listOf("f\t7\t1")
            storage.files["7.tsv"] = listOf("m\tTHEM\t$t\t0\tSCREEN\t\t\tsomeone else's message")
            val m = ChatMemory(storage) { t }
            make(m, nora)
            assertEquals(7, m.info(nora)!!.id)
            m.sent(nora, "a brand new message", null)
            assertEquals("Me: a brand new message", m.historyText(nora))
            m.save()
            assertEquals("Me: a brand new message", ChatMemory(storage) { t }.historyText(nora))
        }
    }

    @Test fun mergeWritesTheTargetBeforeDeletingTheSource() {
        val old = ChatKey("com.whatsapp", "Nora K")
        mem.sent(old, "an old message here", null)
        mem.save()
        val failing = object : ChatStorage by storage {
            override fun write(name: String, lines: (Appendable) -> Unit) = throw IllegalStateException("disk full")
        }
        val m = ChatMemory(failing) { t }
        assertThrows(IllegalStateException::class.java) { m.merge(old, nora) }
        assertEquals("Me: an old message here", ChatMemory(storage) { t }.historyText(old))
    }

    @Test fun saveDropsColdHistoriesAndReloadsThemOnDemand() {
        val keys = (1..5).map { ChatKey("com.whatsapp", "Chat $it") }
        for (k in keys) {
            t += MINUTE
            mem.sent(k, "hello ${k.title}", null)
        }
        assertTrue(storage.reads.none { it != "index.tsv" })
        mem.save()
        for (k in keys.drop(2)) assertEquals("Me: hello ${k.title}", mem.historyText(k))
        assertTrue(storage.reads.none { it != "index.tsv" })

        assertEquals("Me: hello Chat 1", mem.historyText(keys[0]))
        assertEquals(listOf("${mem.info(keys[0])!!.id}.tsv"), storage.reads.filter { it != "index.tsv" })
        assertEquals("Me: hello Chat 1", mem.historyText(keys[0]))
        assertEquals(1, storage.reads.count { it != "index.tsv" })
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
