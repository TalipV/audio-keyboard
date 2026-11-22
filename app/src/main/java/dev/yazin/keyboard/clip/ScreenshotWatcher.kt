package dev.yazin.keyboard.clip

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.settings.Prefs

/**
 * Screenshots in the clipboard history, as Samsung's keyboard has them: Android doesn't put a screenshot on the
 * clipboard, so when the keyboard opens this looks for screenshots taken since the last look and keeps them as image
 * clips (newest last, so the newest is the strip chip). Reads only images whose folder or name says "Screenshot", and
 * only with the photo permission the owner granted for this ([Prefs.clipboardScreenshots]).
 */
object ScreenshotWatcher {
    /** On the first look, screenshots older than this are history, not something just taken. */
    private const val FIRST_LOOK_MS = 10 * 60_000L
    private const val LAST = "last_screenshot_ms"
    private val main = Handler(Looper.getMainLooper())

    /** The photo permission this Android version needs to see screenshots. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Main thread; the query and the copies run on a worker, the history is changed back on the main thread. */
    fun catchUp(context: Context, store: ClipStore, onChange: () -> Unit) {
        val prefs = Prefs(context)
        if (!prefs.clipboard || !prefs.clipboardScreenshots || !prefs.clipboardImages || !allowed(context)) return
        val app = context.applicationContext
        val seen = app.getSharedPreferences("clipboard_watch", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = seen.getLong(LAST, 0L).takeIf { it > 0 } ?: (now - FIRST_LOOK_MS)
        Thread({
            val found = runCatching { query(app, since) }.getOrDefault(emptyList())
            // One still being written (just taken) is left for the next look: only what was read counts as seen.
            val saved = found.mapNotNull { (shot, added) -> runCatching { ClipImages.save(app, shot.first, shot.second) }.getOrNull()?.let { it to added } }
            if (saved.isEmpty()) return@Thread
            seen.edit().putLong(LAST, saved.maxOf { it.second }).apply()
            val names = saved.map { it.first }
            main.post {
                var changed = false
                for (n in names) changed = store.offerImage(n, false) || changed
                if (changed) {
                    ClipFile.save(app)
                    onChange()
                }
            }
        }, "screenshots").start()
    }

    /** The newest few screenshots added after [since] (ms), oldest first: ((uri, mime), added ms). */
    private fun query(context: Context, since: Long): List<Pair<Pair<android.net.Uri, String>, Long>> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE)
        val where = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?) " +
            "AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
        val args = arrayOf("%Screenshot%", "Screenshot%", (since / 1000).toString())
        val out = ArrayList<Pair<Pair<android.net.Uri, String>, Long>>()
        context.contentResolver.query(uri, cols, where, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < 5) {
                val id = c.getLong(0)
                val added = c.getLong(1) * 1000
                val mime = c.getString(2) ?: "image/png"
                out += (ContentUris.withAppendedId(uri, id) to mime) to added
            }
        }
        return out.reversed()
    }
}
