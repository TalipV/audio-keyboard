package dev.yazin.keyboard.clip

import android.content.ClipboardManager
import android.content.Context
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.settings.Prefs

/** Spec §8: records copies while this is the active keyboard. Clips Android marks sensitive (passwords) are skipped. */
class ClipboardWatcher(private val context: Context, private val store: ClipStore, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { read() }

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
        if (store.offer(clip.getItemAt(0).text, sensitive)) {
            ClipFile.save(context)
            onChange()
        }
    }

    private companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), by value so it's read on Android 10–12 too. */
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
