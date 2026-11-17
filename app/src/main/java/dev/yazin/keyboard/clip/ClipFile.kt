package dev.yazin.keyboard.clip

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.clip.ClipStore
import java.io.File
import java.io.IOException

/** The clipboard history (spec §8), one per process, in no-backup storage. */
object ClipFile {
    private var store: ClipStore? = null

    @Synchronized
    fun get(context: Context): ClipStore = store ?: ClipStore().also { s ->
        val f = AtomicFile(file(context))
        if (f.baseFile.exists()) f.openRead().bufferedReader().useLines { s.read(it) }
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
