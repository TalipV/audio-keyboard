package dev.yazin.keyboard.engine.voice

/**
 * Names the field each input session is in, for wrong-field protection (spec §7.3). View ids repeat (every chat's
 * message box, every WebView or Compose field), so a field shown anew is a new target and a late text becomes a chip.
 */
class FieldTargets {
    private var current = 0

    /** [sameField]: the app restarted the same field (e.g. after sending), so it keeps its target. */
    fun start(pkg: String, fieldId: Int, sameField: Boolean): String {
        if (!sameField || current == 0) current = sessions.incrementAndGet()
        return "$pkg/$fieldId#$current"
    }

    private companion object {
        /** Process-wide: a recreated keyboard service must not reuse a number a queued dictation still carries. */
        val sessions = java.util.concurrent.atomic.AtomicInteger()
    }
}
