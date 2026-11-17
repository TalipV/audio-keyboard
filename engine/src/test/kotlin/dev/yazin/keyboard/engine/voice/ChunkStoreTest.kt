package dev.yazin.keyboard.engine.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_000_000_000L

    @Test fun roundTripsArabicPromptsAndTexts() {
        val store = ChunkStore(tmp.root)
        val info = ChunkInfo("5-0001", "ar", "قبل السطر\nالثاني", "pkg/7", now, text = "مرحبا")
        store.save(info, Wav.encode(ShortArray(4)))
        assertEquals(listOf(info), ChunkStore(tmp.root).load(now))
    }

    @Test fun damagedOrphanedAndStaleFilesAreCleanedUp() {
        val store = ChunkStore(tmp.root)
        store.save(ChunkInfo("1-0001", "en", "", "a/1", now - 25 * 3_600_000L), Wav.encode(ShortArray(4))) // stale
        File(tmp.root, "2-0001.meta").writeText("garbage") // damaged
        File(tmp.root, "3-0001.wav").writeBytes(Wav.encode(ShortArray(4))) // orphan audio from a torn save
        store.saveInfo(ChunkInfo("4-0001", "en", "", "a/1", now)) // never transcribed, audio lost
        File(tmp.root, "5-0001.wav.tmp").writeText("x") // interrupted write
        assertEquals(emptyList<ChunkInfo>(), store.load(now))
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }
}
