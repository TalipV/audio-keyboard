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

/**
 * What is on the system clipboard right now and what the keyboard makes of it, for Settings → Clipboard → Check: the
 * owner copies something the history misses, opens this, and sees why (marked sensitive, no text, too long, …).
 * The text itself is shown only as its first characters.
 */
fun clipboardReport(context: Context): String {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = try { cm.primaryClip } catch (e: SecurityException) { return "Android refused to show the clipboard: ${e.message}" }
        ?: return "The system clipboard is empty, or Android doesn't let this app read it."
    val d = clip.description
    val mimes = (0 until d.mimeTypeCount).map { d.getMimeType(it) }
    val extras = d.extras?.keySet()?.joinToString { k -> "$k=${d.extras?.get(k)}" }.orEmpty()
    val sensitive = d.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
    val item = if (clip.itemCount > 0) clip.getItemAt(0) else null
    val text = item?.text?.toString()
    val last = context.getSharedPreferences("clipboard_watch", Context.MODE_PRIVATE).getLong("last_clip_time", 0L)
    val verdict = when {
        item == null -> "Not kept: the clip has no items."
        sensitive -> "Not kept: the copying app marked it sensitive (like a password)."
        text == null && item.uri == null -> "Not kept: no text and no link (only HTML: ${item.htmlText != null})."
        text != null && text.isBlank() -> "Not kept: the text is blank."
        text != null && text.length > ClipStore.MAX_CHARS -> "Not kept: longer than ${ClipStore.MAX_CHARS} characters."
        !Prefs(context).clipboard -> "Not kept: clipboard history is off."
        d.timestamp <= last -> "Already taken (or older than the last clip taken)."
        else -> "Would be kept the next time the keyboard opens."
    }
    return buildString {
        appendLine(verdict)
        appendLine()
        appendLine("Label: ${d.label}")
        appendLine("Types: ${mimes.joinToString()}")
        appendLine("Items: ${clip.itemCount}")
        appendLine("Text: ${text?.let { "\"" + it.take(40) + (if (it.length > 40) "…" else "") + "\" (${it.length} chars)" } ?: "none"}")
        appendLine("Link (URI): ${item?.uri ?: "none"}")
        appendLine("HTML: ${if (item?.htmlText != null) "yes" else "no"}")
        appendLine("Sensitive: $sensitive")
        if (extras.isNotEmpty()) appendLine("Extras: $extras")
        appendLine("Copied at: ${java.util.Date(d.timestamp)}")
        append("Last taken: ${if (last == 0L) "never" else java.util.Date(last).toString()}")
    }
}

