package dev.yazin.keyboard.ime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.yazin.keyboard.BuildConfig
import dev.yazin.keyboard.engine.Candidate
import dev.yazin.keyboard.engine.GlideDecoder
import dev.yazin.keyboard.engine.GlidePoint
import dev.yazin.keyboard.engine.KeyModel
import dev.yazin.keyboard.engine.Suggester
import dev.yazin.keyboard.engine.Suggestions
import dev.yazin.keyboard.engine.Touch
import dev.yazin.keyboard.engine.UserHistory
import dev.yazin.keyboard.engine.dict.Dictionary
import dev.yazin.keyboard.keyboard.Lang
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Runs suggestions on a dedicated thread and posts results back to the main thread.
 * A newer request makes older results stale, so they are dropped.
 */
class TextEngine(private val context: Context) {
    private val thread = HandlerThread("suggestions", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val dicts = HashMap<Lang, Dictionary>()
    @Volatile private var suggester: Suggester? = null
    @Volatile private var glider: GlideDecoder? = null
    @Volatile private var generation = 0

    /** Swaps in the suggester for [lang] with the visible key positions (maps the dictionary on first use). */
    fun setKeyboard(lang: Lang, keys: KeyModel) {
        worker.post {
            val dict = dictionary(lang)
            suggester = Suggester(dict, keys, history(lang), compounds = lang.compounds)
            glider = GlideDecoder(dict, keys, history(lang))
        }
    }

    fun history(lang: Lang): UserHistory = HistoryStore.get(context, lang)

    /** Whether [word] is spelled exactly as a dictionary word in [lang]. Any thread. */
    fun isWord(lang: Lang, word: String): Boolean {
        val d = dictionary(lang)
        val node = d.find(word)
        return node >= 0 && d.freq(node) >= 0
    }

    fun request(typed: String, touches: List<Touch?>, previous: String?, onResult: (Suggestions) -> Unit) {
        val g = ++generation
        worker.post {
            if (g != generation) return@post
            val t0 = SystemClock.elapsedRealtimeNanos()
            val s = suggester?.suggest(typed, touches, previous) ?: return@post
            if (BuildConfig.DEBUG) Log.d("KbdSuggest", "${typed.length} letters ${(SystemClock.elapsedRealtimeNanos() - t0) / 1000} µs")
            main.post { if (g == generation) onResult(s) }
        }
    }

    fun requestPredictions(previous: String?, onResult: (List<Candidate>) -> Unit) {
        val g = ++generation
        worker.post {
            if (g != generation) return@post
            val p = suggester?.predict(previous) ?: return@post
            main.post { if (g == generation) onResult(p) }
        }
    }

    /** The live glide preview (glide spec §3.2): like [request], newer requests make older results stale. */
    fun requestGlide(points: List<GlidePoint>, previous: String?, onResult: (List<Candidate>) -> Unit) {
        val g = ++generation
        worker.post {
            if (g != generation) return@post
            val r = decodeGlide(points, previous) { g != generation } ?: return@post
            main.post { if (g == generation) onResult(r) }
        }
    }

    /**
     * The glide's final word, decoded on the caller's thread so it lands before any later key; null while the dictionary
     * loads. ponytail: runs on the main thread (p95 ≈ 3–8 ms in the harness; a preview holding the lock stops early once
     * the generation moves on); post it and queue keys if a phone shows jank.
     */
    fun glideNow(points: List<GlidePoint>, previous: String?): List<Candidate>? {
        ++generation // drops previews still queued
        return decodeGlide(points, previous)
    }

    private fun decodeGlide(points: List<GlidePoint>, previous: String?, cancelled: () -> Boolean = { false }): List<Candidate>? {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = glider?.decode(points, previous, cancelled) ?: return null
        if (BuildConfig.DEBUG) Log.d("KbdGlide", "${points.size} points ${(SystemClock.elapsedRealtimeNanos() - t0) / 1000} µs")
        return r
    }

    /** Synchronous fallback when a separator arrives before the background result; null while still loading. */
    fun suggestNow(typed: String, touches: List<Touch?>, previous: String?): Suggestions? =
        suggester?.suggest(typed, touches, previous)

    fun save() {
        worker.post { HistoryStore.save(context) }
    }

    fun close() {
        worker.post { HistoryStore.save(context) }
        thread.quitSafely()
    }

    @Synchronized
    private fun dictionary(lang: Lang): Dictionary = dicts.getOrPut(lang) {
        context.assets.openFd(lang.dictFile).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                Dictionary(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
            }
        }
    }
}
