package dev.yazin.keyboard.style

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.style.StyleStore
import java.io.File
import java.io.IOException

/** The user's writing samples (writing-style spec §2), one store per process, in no-backup storage. */
object StyleFile {
    private var store: StyleStore? = null

    @Synchronized
    fun get(context: Context): StyleStore = store ?: StyleStore().also { s ->
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

    private fun file(context: Context) = File(context.noBackupFilesDir, "style.tsv")
}
