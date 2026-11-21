package dev.yazin.keyboard.clip

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.settings.Prefs

/**
 * Spec §8: records copies (text, and images unless turned off) while this is the active keyboard. Clips Android marks
 * sensitive (passwords) are skipped. Android stops the keyboard's process while it is hidden, so copies made then are
 * missed by the listener; [catchUp] takes the current clip when the keyboard opens, once, by its copy time.
 */
class ClipboardWatcher(private val context: Context, private val store: ClipStore, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { read() }
    private val main = Handler(Looper.getMainLooper())
    /** When the newest clip already taken was copied (ClipDescription.timestamp), kept across process restarts. */
    private val seen = context.getSharedPreferences("clipboard_watch", Context.MODE_PRIVATE)

    /** Takes the clip on the system clipboard if it was copied after the last one taken (e.g. while the keyboard was away). */
    fun catchUp() {
        if (enabled) read(onlyIfNew = true)
    }

    var enabled = false
        set(v) {
            if (v == field) return
            field = v
            if (v) cm.addPrimaryClipChangedListener(listener) else cm.removePrimaryClipChangedListener(listener)
        }

    private fun read(onlyIfNew: Boolean = false) {
        // Turning history off in Settings takes effect at once, not when the keyboard next shows.
        if (!Prefs(context).clipboard) return
        val clip = try { cm.primaryClip } catch (_: SecurityException) { null } ?: return
        if (clip.itemCount == 0) return
        val stamp = clip.description.timestamp
        if (onlyIfNew && stamp <= seen.getLong(LAST, 0L)) return
        seen.edit().putLong(LAST, stamp).apply()
        // Android 13 named this extra, but password managers set it on older versions too.
        val sensitive = clip.description.extras?.getBoolean(SENSITIVE) == true
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (item.text == null && uri != null) {
            val mime = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") } ?: return
            if (sensitive || !Prefs(context).clipboardImages) return
            // Reading the image is IO; the history is main-thread only.
            Thread {
                val name = runCatching { ClipImages.save(context, uri, mime) }.getOrNull() ?: return@Thread
                main.post {
                    if (store.offerImage(name, false)) {
                        ClipFile.save(context)
                        onChange()
                    }
                }
            }.start()
            return
        }
        // A copied link can arrive as a URI rather than text ("Copy link" in some apps).
        if (store.offer(item.text ?: uri?.toString(), sensitive)) {
            ClipFile.save(context)
            onChange()
        }
    }

    private companion object {
        const val LAST = "last_clip_time"

        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), by value so it's read on Android 10–12 too. */
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
