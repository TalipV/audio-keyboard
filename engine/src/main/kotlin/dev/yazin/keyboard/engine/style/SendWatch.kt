package dev.yazin.keyboard.engine.style

/**
 * Notices a message being sent (writing-style spec §2): the app empties a field that held text when it was last read,
 * and the keyboard hasn't changed it since. [field] names the field (app, id, name, hint, type); apps that restart input
 * on send keep it, while a WebView restarting into another of its fields doesn't. Main thread.
 */
class SendWatch {
    private var seen: String? = null
    private var seenIn = ""

    /** The keyboard changed the field: what was read is stale until the next [read]. */
    fun edited() {
        seen = null
    }

    /** The field's text, read once editing pauses; null when the app won't say. */
    fun read(text: String?, field: String = "") {
        seen = text?.takeIf { it.isNotBlank() }
        seenIn = field
    }

    /** The app moved the cursor or restarted [field], which now holds [text]. Returns what was sent, or null. */
    fun check(text: String?, field: String = ""): String? {
        val was = seen ?: return null
        if (field != seenIn) {
            seen = null // another field: whatever happened to the one read, it wasn't seen
            return null
        }
        if (text == null) return null
        seen = text.takeIf { it.isNotBlank() }
        return if (text.isBlank()) was else null
    }

    fun reset() {
        seen = null
    }
}
