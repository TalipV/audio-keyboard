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

    @Test fun onlyFiftyUnpinnedAreKept() {
        store.offer("pinned", false)
        store.pin("pinned", true)
        for (i in 1..60) store.offer("clip $i", false)
        assertEquals(ClipStore.MAX_UNPINNED + 1, texts().size)
        assertEquals("pinned", texts().first())
        assertEquals("clip 60", texts()[1])
    }

    @Test fun aLinkIsOneWebAddressAndNothingElse() {
        assertTrue(ClipStore.isLink("https://claude.ai/code/cshk_01QQ4X2TNydN"))
        assertTrue(ClipStore.isLink(" http://example.org/a?b=c "))
        assertFalse(ClipStore.isLink("Tr0ub4dor&3"))
        assertFalse(ClipStore.isLink("see https://example.org"))
        assertFalse(ClipStore.isLink("https://a.org https://b.org"))
        assertFalse(ClipStore.isLink(null))
    }

    @Test fun theLifetimeIsTheOwnersChoice() {
        store.lifetimeMs = 7 * 86_400_000L
        store.offer("a", false)
        t += 6 * 86_400_000L
        assertEquals(listOf("a"), texts())
        t += 86_400_000L
        assertEquals(emptyList<String>(), texts())
    }

    @Test fun foreverKeepsClipsUntilTheLimitPushesThemOut() {
        store.lifetimeMs = ClipStore.FOREVER
        store.offer("a", false)
        t += 365 * 86_400_000L
        assertEquals(listOf("a"), texts())
    }

    @Test fun imagesAreClipsTooAndTheSameImageIsOneClip() {
        store.offer("text", false)
        assertTrue(store.offerImage("abc.png", false))
        store.offer("more", false)
        assertTrue(store.offerImage("abc.png", false))
        assertEquals(listOf("image:abc.png", "more", "text"), store.clips().map { it.key })
        store.pin("image:abc.png", true)
        assertTrue(store.clips().first().pinned)
        assertEquals("image:abc.png", store.chip()?.key) // a fresh image (a screenshot) is the strip chip too
    }

    @Test fun droppedImagesLetGoOfTheirFiles() {
        val dropped = ArrayList<String>()
        store.onDropImage = { dropped += it }
        store.offerImage("a.png", false)
        store.offerImage("b.png", false)
        store.offerImage("b.png", false) // a copy again keeps the file
        store.delete("image:a.png")
        assertEquals(listOf("a.png"), dropped)
        t += ClipStore.LIFETIME_MS
        store.clips()
        assertEquals(listOf("a.png", "b.png"), dropped)
        assertFalse(store.offerImage("secret.png", true))
        assertEquals("secret.png", dropped.last())
    }

    @Test fun onlyTwentyUnpinnedImagesAreKept() {
        val dropped = ArrayList<String>()
        store.onDropImage = { dropped += it }
        for (i in 1..25) store.offerImage("$i.png", false)
        assertEquals(ClipStore.MAX_UNPINNED_IMAGES, store.clips().size)
        assertEquals(listOf("1.png", "2.png", "3.png", "4.png", "5.png"), dropped) // oldest first
    }

    @Test fun imageClipsSurviveARestart() {
        store.offerImage("abc.png", false)
        store.pin("image:abc.png", true)
        store.offer("text", false)
        val out = StringBuilder()
        store.write(out)
        val again = ClipStore { t }
        again.read(out.lineSequence())
        assertEquals(store.clips(), again.clips())
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
