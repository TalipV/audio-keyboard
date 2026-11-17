package dev.yazin.keyboard.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUnlockWindowTest {
    private var now = 100L
    private val window = ChatUnlockWindow { now }

    @Test fun startsLockedAndExpiresExactlyAfterFiveMinutes() {
        assertFalse(window.unlocked)
        window.unlock()
        now += ChatUnlockWindow.DURATION_MS - 1
        assertTrue(window.unlocked)
        now++
        assertFalse(window.unlocked)
    }

    @Test fun checkingAccessDoesNotExtendTheWindow() {
        window.unlock()
        repeat(5) {
            assertTrue(window.unlocked)
            now += 60_000L
        }
        assertFalse(window.unlocked)
    }

    @Test fun screenOffRelocksImmediatelyAndAnotherAuthenticationGrantsAFreshWindow() {
        window.unlock()
        now += 1_000L
        window.lock()
        assertFalse(window.unlocked)
        window.unlock()
        now += ChatUnlockWindow.DURATION_MS - 1
        assertTrue(window.unlocked)
    }

    @Test fun aNewProcessHasNoRememberedUnlock() {
        window.unlock()
        assertFalse(ChatUnlockWindow { now }.unlocked)
    }
}
