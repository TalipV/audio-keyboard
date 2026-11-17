package dev.yazin.keyboard.llm

import android.content.Context
import android.util.AtomicFile
import dev.yazin.keyboard.engine.llm.LlmCall
import dev.yazin.keyboard.engine.llm.LlmLog
import dev.yazin.keyboard.settings.Prefs
import java.io.File
import java.io.IOException

/**
 * The last 50 LLM calls (Settings → AI writing → Debug log), one store per process, in no-backup storage. Nothing is kept
 * unless the Debug log switch is on. It never leaves the phone except by the user's own Copy or Share. On a debug build
 * `tools/dev.sh llmlog` pulls it.
 */
object LlmLogFile {
    private var log: LlmLog? = null

    @Synchronized
    fun get(context: Context): LlmLog = log ?: LlmLog().also { l ->
        val f = AtomicFile(file(context))
        if (f.baseFile.exists()) f.openRead().bufferedReader().useLines { l.read(it) }
        log = l
    }

    @Synchronized
    fun save(context: Context) {
        val l = log?.takeIf { it.dirty } ?: return
        val f = AtomicFile(file(context))
        val out = f.startWrite()
        try {
            val w = out.writer()
            l.write(w)
            w.flush()
            f.finishWrite(out)
        } catch (_: IOException) {
            f.failWrite(out)
        }
    }

    /** What [dev.yazin.keyboard.engine.llm.LlmClient] reports each call to. Runs on the caller's worker thread. */
    fun record(context: Context): (LlmCall) -> Unit = { call ->
        if (Prefs(context).debugLog) {
            get(context).add(call)
            save(context)
        }
    }

    /** Forgets every kept call, when the Debug log is turned off. */
    @Synchronized
    fun clear(context: Context) {
        log?.clear()
        log = null
        file(context).delete()
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, "llm-log.jsonl")
}
