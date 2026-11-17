package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun doublesFromHalfASecondAndCapsAtThirtyForever() {
        val b = Backoff()
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..8).map(b::delayMs))
        assertEquals(30_000L, b.delayMs(10_000))
    }
}
