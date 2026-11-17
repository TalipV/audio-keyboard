package dev.yazin.keyboard.engine.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipStoreTest {
    private var t = 1_000_000L
    private val store = ClipStore { t }
    private fun texts() = store.clips().map { it.text }

    @Test fun newestFirstAndACopyAgainMovesToTheTopKeepingItsPin() {
        store.offer("a", false)
        store.offer("b", false)
        store.pin("a", true)
        t += 10
        store.offer("c", false)
        store.offer("a", false)
        assertEquals(listOf("a", "c", "b"), texts())
        assertTrue(store.clips().first().pinned)
    }

    @Test fun pinnedClipsComeFirst() {
        store.offer("a", false)
        store.offer("b", false)
        store.pin("a", true)
        assertEquals(listOf("a", "b"), texts())
    }

    @Test fun passwordsAndHugeOrEmptyClipsAreNotKept() {
        assertFalse(store.offer("hunter2", sensitive = true))
        assertFalse(store.offer("   ", false))
        assertFalse(store.offer(null, false))
        assertFalse(store.offer("x".repeat(ClipStore.MAX_CHARS + 1), false))
        assertEquals(emptyList<String>(), texts())
    }

    @Test fun unpinnedClipsExpireAfterAnHourPinnedOnesStay() {
        store.offer("keep", false)
        store.pin("keep", true)
        store.offer("go", false)
        t += ClipStore.LIFETIME_MS
        assertEquals(listOf("keep"), texts())
    }

    @Test fun onlyThirtyUnpinnedAreKept() {
        store.offer("pinned", false)
        store.pin("pinned", true)
        for (i in 1..40) store.offer("clip $i", false)
        assertEquals(31, texts().size)
        assertEquals("pinned", texts().first())
        assertEquals("clip 40", texts()[1])
    }

    @Test fun theChipShowsForAMinuteUntilUsed() {
        assertNull(store.chip())
        store.offer("copied", false)
        assertEquals("copied", store.chip()?.text)
        assertEquals(ClipStore.CHIP_MS, store.chipRemainingMs())
        store.dismissChip()
        assertNull(store.chip())
        store.offer("again", false)
        t += ClipStore.CHIP_MS
        assertNull(store.chip())
    }

    @Test fun deleteAndClear() {
        store.offer("a", false)
        store.offer("b", false)
        store.delete("a")
        assertEquals(listOf("b"), texts())
        store.clear()
        assertEquals(emptyList<String>(), texts())
    }

    @Test fun newlinesTabsAndArabicSurviveARestart() {
        val odd = listOf("line 1\nline 2", "a\tb", "back\\slash \\n literal", "مرحبا\r\nبك")
        odd.forEach { store.offer(it, false) }
        store.pin(odd[0], true)
        val out = StringBuilder()
        store.write(out)
        assertFalse(store.dirty)
        val again = ClipStore { t }
        again.read((out.toString() + "garbage line\nc\tnot-a-time\t0\tx\n").lineSequence())
        assertEquals(store.clips(), again.clips())
    }

    @Test fun expiryAppliesAfterARestartToo() {
        store.offer("keep", false)
        store.pin("keep", true)
        store.offer("go", false)
        val out = StringBuilder()
        store.write(out)
        val later = ClipStore { t + 2 * ClipStore.LIFETIME_MS }
        later.read(out.lineSequence())
        assertEquals(listOf("keep"), later.clips().map { it.text })
    }

    @Test fun anExpiredClipIsNotWrittenBackToDisk() {
        store.offer("old", false)
        t += ClipStore.LIFETIME_MS
        val out = StringBuilder()
        store.write(out)
        assertEquals("", out.toString())
    }
}
