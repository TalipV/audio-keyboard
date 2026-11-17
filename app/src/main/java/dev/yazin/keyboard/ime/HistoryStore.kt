package dev.yazin.keyboard.ime

import android.content.Context
import dev.yazin.keyboard.engine.UserHistory
import dev.yazin.keyboard.keyboard.Lang
import java.io.File

/**
 * One [UserHistory] per language for the whole process. The keyboard and the personal-dictionary screen
 * share it. Stored in no-backup storage so it never reaches cloud backups.
 */
object HistoryStore {
    private val loaded = HashMap<Lang, UserHistory>()

    @Synchronized
    fun get(context: Context, lang: Lang): UserHistory = loaded.getOrPut(lang) {
        UserHistory().also { h -> file(context, lang).takeIf { it.exists() }?.reader()?.use { h.read(it) } }
    }

    @Synchronized
    fun save(context: Context) {
        for ((lang, h) in loaded) {
            if (!h.dirty) continue
            val target = file(context, lang)
            val tmp = File(target.path + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                val w = out.writer()
                h.write(w)
                out.fd.sync() // durable before it replaces the old file
            }
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    private fun file(context: Context, lang: Lang) = File(context.noBackupFilesDir, "history-${lang.name.lowercase()}.tsv")
}
