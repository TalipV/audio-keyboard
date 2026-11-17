package dev.yazin.keyboard.emoji

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.emoji.EmojiPrefs
import java.io.File
import java.io.IOException

/** Emoji recents and skin tones (spec §8), one per process, in no-backup storage. */
object EmojiStore {
    private var prefs: EmojiPrefs? = null

    @Synchronized
    fun get(context: Context): EmojiPrefs = prefs ?: EmojiPrefs().also { p ->
        val f = AtomicFile(file(context))
        if (f.baseFile.exists()) f.openRead().bufferedReader().useLines { p.read(it) }
        prefs = p
    }

    @Synchronized
    fun save(context: Context) {
        val p = prefs?.takeIf { it.dirty } ?: return
        val f = AtomicFile(file(context))
        val out = f.startWrite()
        try {
            val w = out.writer()
            p.write(w)
            w.flush()
            f.finishWrite(out)
        } catch (_: IOException) {
            f.failWrite(out)
        }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, "emoji.tsv")
}
