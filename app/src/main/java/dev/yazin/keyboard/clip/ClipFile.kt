package dev.yazin.keyboard.clip

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.settings.Prefs
import java.io.File
import java.io.IOException

/** The clipboard history (spec §8), one per process, in no-backup storage. */
object ClipFile {
    private var store: ClipStore? = null

    @Synchronized
    fun get(context: Context): ClipStore = store ?: ClipStore().also { s ->
        val app = context.applicationContext
        s.lifetimeMs = Prefs(app).clipLifetimeMs
        s.onDropImage = { ClipImages.delete(app, it) }
        val f = AtomicFile(file(context))
        if (f.baseFile.exists()) f.openRead().bufferedReader().useLines { s.read(it) }
        ClipImages.sweep(app, s.clips().mapNotNull { it.image }.toSet())
        store = s
    }

    @Synchronized
    fun save(context: Context) {
        val s = store?.takeIf { it.dirty } ?: return
        val f = AtomicFile(file(context))
        val out = f.startWrite()
        try {
            val w = out.writer()
            s.write(w)
            w.flush()
            f.finishWrite(out)
        } catch (_: IOException) {
            f.failWrite(out)
        }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, "clipboard.tsv")
}
