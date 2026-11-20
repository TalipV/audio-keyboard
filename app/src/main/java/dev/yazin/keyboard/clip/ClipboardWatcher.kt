package dev.yazin.keyboard.clip

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.settings.Prefs

/**
 * Spec §8: records copies (text, and images unless turned off) while this is the active keyboard. Clips Android marks
 * sensitive (passwords) are skipped.
 */
class ClipboardWatcher(private val context: Context, private val store: ClipStore, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { read() }
    private val main = Handler(Looper.getMainLooper())

    var enabled = false
        set(v) {
            if (v == field) return
            field = v
            if (v) cm.addPrimaryClipChangedListener(listener) else cm.removePrimaryClipChangedListener(listener)
        }

    private fun read() {
        // Turning history off in Settings takes effect at once, not when the keyboard next shows.
        if (!Prefs(context).clipboard) return
        val clip = try { cm.primaryClip } catch (_: SecurityException) { null } ?: return
        if (clip.itemCount == 0) return
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
        if (store.offer(item.text, sensitive)) {
            ClipFile.save(context)
            onChange()
        }
    }

    private companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), by value so it's read on Android 10–12 too. */
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
