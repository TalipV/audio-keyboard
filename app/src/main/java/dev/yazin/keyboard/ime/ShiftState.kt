package dev.yazin.keyboard.ime

enum class Caps {
    OFF, AUTO, ONE_SHOT, LOCKED;

    val isUpper: Boolean get() = this != OFF
}

/**
 * Gboard shift semantics:
 * - tap = one capital
 * - double-tap = caps lock
 * - tap again = off
 * - holding shift while typing capitalises until release
 * - auto-caps only moves between OFF and AUTO
 */
class ShiftState(private val doubleTapMs: Long = 300) {
    var caps = Caps.OFF
        private set
    val isUpper: Boolean get() = caps.isUpper

    private var lastTapAt = NEVER
    private var held = false
    private var chorded = false

    fun onShiftDown(now: Long) {
        held = true
        chorded = false
        caps = when {
            caps == Caps.LOCKED -> Caps.OFF.also { lastTapAt = NEVER }
            now - lastTapAt <= doubleTapMs -> Caps.LOCKED.also { lastTapAt = NEVER }
            caps == Caps.OFF -> Caps.ONE_SHOT.also { lastTapAt = now }
            else -> Caps.OFF.also { lastTapAt = now }
        }
    }

    fun onShiftUp() {
        held = false
        if (chorded) {
            chorded = false
            if (caps != Caps.LOCKED) caps = Caps.OFF
        }
    }

    fun onCharCommitted() {
        if (held) chorded = true
        else if (caps == Caps.ONE_SHOT || caps == Caps.AUTO) caps = Caps.OFF
    }

    fun onAutoCaps(wanted: Boolean) {
        if (caps == Caps.OFF || caps == Caps.AUTO) caps = if (wanted) Caps.AUTO else Caps.OFF
    }

    fun reset() {
        caps = Caps.OFF
        lastTapAt = NEVER
        held = false
        chorded = false
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}
