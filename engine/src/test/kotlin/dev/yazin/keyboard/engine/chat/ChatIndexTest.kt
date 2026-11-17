package dev.yazin.keyboard.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIndexTest {
    private val wa = "com.whatsapp"

    private fun roundTrip(index: ChatIndex) = ChatIndex().also { copy ->
        val out = StringBuilder()
        index.write(out)
        copy.read(out.lineSequence())
    }

    @Test fun directionMarksDontSplitAChat() =
        assertEquals(ChatKey.of(wa, "\u200ENora", "WhatsApp"), ChatKey.of(wa, "Nora", "WhatsApp"))

    @Test fun zeroWidthSpacesAndRunsOfWhitespaceDontSplitAChat() {
        assertEquals(ChatKey(wa, "Nora K"), ChatKey.of(wa, "\u200B Nora\u00A0\u202A K\u202C\n", "WhatsApp"))
        assertEquals(ChatKey(wa, "حديل"), ChatKey.of(wa, "\u200Fحديل", "WhatsApp"))
    }

    @Test fun theAppsOwnNameAndItsTabsAreNotChats() {
        assertNull(ChatKey.of(wa, "WhatsApp", "WhatsApp"))
        assertNull(ChatKey.of(wa, "Status", "WhatsApp"))
        assertNull(ChatKey.of(wa, "whatsapp", "WhatsApp"))
        assertNull(ChatKey.of(wa, "\u200BAsk Meta AI or Search", "WhatsApp"))
        assertNull(ChatKey.of(wa, " \u200E ", "WhatsApp"))
        assertNull(ChatKey.of("com.google.android.apps.messaging", "Messages", "Messages"))
    }

    @Test fun idsAreNeverReused() {
        val index = ChatIndex()
        val a = index.ensure(ChatKey(wa, "Nora"))
        val b = index.ensure(ChatKey(wa, "Sam"))
        assertEquals(a, index.ensure(ChatKey(wa, "Nora")))
        index.remove(b.id)
        assertNull(index.byId(b.id))
        assertNull(index.get(ChatKey(wa, "Sam")))
        val c = index.ensure(ChatKey(wa, "Sam"))
        assertTrue(c.id > b.id)

        val copy = roundTrip(index)
        copy.remove(c.id)
        val d = copy.ensure(ChatKey(wa, "Lina"))
        assertTrue(d.id > c.id)
        assertEquals(listOf(a.id, d.id), copy.all().map { it.id }.sorted())
    }

    @Test fun allIsNewestLastSeenFirst() {
        val index = ChatIndex()
        val a = index.ensure(ChatKey(wa, "Nora"))
        val b = index.ensure(ChatKey(wa, "Sam"))
        val c = index.ensure(ChatKey(wa, "Lina"))
        index.update(a.id) { it.copy(lastSeen = 30) }
        index.update(b.id) { it.copy(lastSeen = 10) }
        index.update(c.id) { it.copy(lastSeen = 20) }
        assertEquals(listOf("Nora", "Lina", "Sam"), index.all().map { it.key.title })
    }

    @Test fun writeThenReadRoundTripsNotesCorrectionsAndGoal() {
        val index = ChatIndex()
        val plain = index.ensure(ChatKey(wa, "Sam"))
        val made = index.ensure(ChatKey(wa, "Nora\tK"))
        val full = index.update(made.id) {
            it.copy(
                count = 12,
                first = 1_700_000_000_000L,
                lastSeen = 1_800_000_000_000L,
                goal = "Get closer:\nask about her life",
                written = "Cousin. Writes in Khaleeji \\ Arabizi.",
                writtenAt = 1_790_000_000_000L,
                corrections = listOf("He's my cousin, not a coworker", "We always\tspeak Khaleeji"),
                notes = listOf(Note("Mum's surgery on 3 Nov", 1_795_000_000_000L), Note("Started at Aramco", 1_796_000_000_000L)),
                sinceProfile = 42,
                profileDue = true,
                remember = false,
            )
        }!!
        index.seeded = true

        val copy = roundTrip(index)
        assertEquals(full, copy.byId(made.id))
        assertEquals(full, copy.get(ChatKey(wa, "Nora\tK")))
        assertEquals(plain, copy.byId(plain.id))
        assertTrue(copy.seeded)
        assertFalse(roundTrip(ChatIndex()).seeded)
    }

    @Test fun damagedLinesAreSkipped() {
        val index = ChatIndex()
        index.read(sequenceOf("c\tnot-a-number", "r\t99\tan orphan correction", "garbage", ""))
        assertTrue(index.all().isEmpty())
    }
}
