package dev.yazin.keyboard.text

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.text.Shortcuts
import java.io.File
import java.io.IOException

/** The owner's text shortcuts, one store per process (the keyboard and the settings screen share it), in no-backup storage. */
object ShortcutFile {
    private var store: Shortcuts? = null

    @Synchronized
    fun get(context: Context): Shortcuts = store ?: Shortcuts().also { s ->
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

    private fun file(context: Context) = File(context.noBackupFilesDir, "shortcuts.tsv")
}
