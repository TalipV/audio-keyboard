package dev.yazin.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStateTest {
    private val s = ShiftState(doubleTapMs = 300)

    private fun tap(t: Long) {
        s.onShiftDown(t)
        s.onShiftUp()
    }

    @Test fun tapCapitalisesOneLetter() {
        tap(0)
        assertEquals(Caps.ONE_SHOT, s.caps)
        s.onCharCommitted()
        assertEquals(Caps.OFF, s.caps)
    }

    @Test fun doubleTapLocksUntilTappedAgain() {
        tap(0)
        tap(200)
        assertEquals(Caps.LOCKED, s.caps)
        s.onCharCommitted()
        s.onCharCommitted()
        assertEquals(Caps.LOCKED, s.caps)
        tap(5000)
        assertEquals(Caps.OFF, s.caps)
    }

    @Test fun slowSecondTapTurnsShiftOff() {
        tap(0)
        tap(500)
        assertEquals(Caps.OFF, s.caps)
    }

    @Test fun unlockingThenTappingQuicklyIsNotADoubleTap() {
        tap(0)
        tap(200)
        tap(1000)
        tap(1200)
        assertEquals(Caps.ONE_SHOT, s.caps)
    }

    @Test fun autoCapsOnlyChangesOffAndAuto() {
        s.onAutoCaps(true)
        assertEquals(Caps.AUTO, s.caps)
        s.onCharCommitted()
        assertEquals(Caps.OFF, s.caps)
        tap(0)
        tap(100)
        s.onAutoCaps(false)
        assertEquals(Caps.LOCKED, s.caps)
    }

    @Test fun doubleTapAtSentenceStartLocks() {
        s.onAutoCaps(true)
        tap(0)
        assertEquals(Caps.OFF, s.caps)
        tap(150)
        assertEquals(Caps.LOCKED, s.caps)
    }

    @Test fun holdingShiftWhileTypingCapitalisesUntilRelease() {
        s.onShiftDown(0)
        s.onCharCommitted()
        assertTrue(s.isUpper)
        s.onCharCommitted()
        assertTrue(s.isUpper)
        s.onShiftUp()
        assertEquals(Caps.OFF, s.caps)
    }

    @Test fun deleteDropsATappedShiftButKeepsCapsLock() {
        val s = ShiftState()
        s.onShiftDown(0); s.onShiftUp()
        assertEquals(Caps.ONE_SHOT, s.caps)
        s.onDelete()
        assertEquals(Caps.OFF, s.caps)
        s.onShiftDown(1_000); s.onShiftUp(); s.onShiftDown(1_100); s.onShiftUp()
        assertEquals(Caps.LOCKED, s.caps)
        s.onDelete()
        assertEquals(Caps.LOCKED, s.caps)
    }

    @Test fun deleteLeavesAutoCapsToBeDecidedAgain() {
        val s = ShiftState()
        s.onAutoCaps(true)
        s.onDelete()
        assertEquals(Caps.AUTO, s.caps)
    }

    @Test fun aShiftTappedAfterDeleteWorksAsUsual() {
        val s = ShiftState()
        s.onShiftDown(0); s.onShiftUp()
        s.onDelete()
        s.onShiftDown(100); s.onShiftUp() // not a double-tap: delete came between
        assertEquals(Caps.ONE_SHOT, s.caps)
    }
}
