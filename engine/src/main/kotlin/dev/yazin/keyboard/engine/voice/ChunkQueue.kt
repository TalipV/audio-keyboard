package dev.yazin.keyboard.engine.voice

import java.util.TreeMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Retry delays (spec §7.3): 0.5, 1, 2, … s, capped at 30 s, with no limit on attempts. */
class Backoff(private val baseMs: Long = 500, private val capMs: Long = 30_000) {
    fun delayMs(failures: Int): Long = if (failures <= 0) 0 else minOf(capMs, baseMs shl minOf(failures - 1, 20))
}

/** What the voice UI shows about the queue. */
data class QueueStatus(val pending: Int, val offline: Boolean, val failed: ChunkInfo?)

/**
 * The durable transcription queue (spec §7.3). Every chunk is on disk before its first request; up to [workers]
 * requests run at once; network failures are retried forever with [backoff]; texts are released strictly in spoken
 * order, across dictations. Thread-safe.
 */
class ChunkQueue(
    private val store: ChunkStore,
    private val transcriber: Transcriber,
    private var listener: Listener? = null,
    private val backoff: Backoff = Backoff(),
    workers: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun interface Transcriber {
        fun transcribe(wav: ByteArray, chunk: ChunkInfo): Outcome
    }

    /** Called on a queue thread with the queue locked: post the work elsewhere, never call back into the queue. */
    interface Listener {
        /** The next text in spoken order ([text] is empty when nothing was said). Call [ack] once it has been used. */
        fun onText(chunk: ChunkInfo, text: String, recovered: Boolean)

        fun onStatus(status: QueueStatus)
    }

    private class Entry(var info: ChunkInfo, val recovered: Boolean) {
        var failures = 0
        var inFlight = false
        var future: ScheduledFuture<*>? = null
    }

    private val pool = ScheduledThreadPoolExecutor(workers) { r -> Thread(r, "voice-queue").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val entries = TreeMap<String, Entry>() // not yet released, in spoken order
    private var lastStatus: QueueStatus? = null

    @Synchronized fun add(info: ChunkInfo, wav: ByteArray) {
        store.save(info, wav)
        val e = Entry(info, recovered = false)
        entries[info.id] = e
        schedule(e, 0)
        status()
    }

    /** Spec §7.3 restart recovery: picks up what an earlier process left; those texts come back as recovered. */
    @Synchronized fun resume() {
        for (info in store.load(clock())) {
            if (info.id in entries) continue
            val e = Entry(info, recovered = true)
            entries[info.id] = e
            if (info.text == null && info.error == null) schedule(e, 0)
        }
        release()
        status()
    }

    /** The network is back: retry everything waiting on it now. */
    @Synchronized fun wake() {
        for (e in entries.values) {
            if (e.info.text == null && e.info.error == null && !e.inFlight && e.failures > 0) {
                e.future?.cancel(false)
                schedule(e, 0)
            }
        }
    }

    /** Retry (spec §7.3): every refused chunk goes back in line; refusals usually share one cause, like a wrong model. */
    @Synchronized fun retryFailed() {
        for (e in entries.values) {
            if (e.info.error == null) continue
            e.info = e.info.copy(error = null)
            store.saveInfo(e.info)
            e.failures = 0
            schedule(e, 0)
        }
        status()
    }

    /** Discard (spec §7.3): drops every refused chunk and its audio. */
    @Synchronized fun discardFailed() {
        entries.values.filter { it.info.error != null }.map { it.info.id }.forEach(::discard)
    }

    @Synchronized fun discard(id: String) {
        entries.remove(id)?.future?.cancel(false)
        store.delete(id)
        release()
        status()
    }

    /** The text of [id] was inserted or dismissed: forget it for good. */
    fun ack(id: String) = store.delete(id)

    /**
     * Who receives texts and status (spec §7.3). Without a listener texts stay queued, so a keyboard service that is
     * recreated takes over the same process-wide queue instead of racing a second one over the same files.
     */
    @Synchronized fun attach(l: Listener?) {
        listener = l
        lastStatus = null
        release()
        status()
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    private fun schedule(e: Entry, delayMs: Long) {
        if (pool.isShutdown) return // stays on disk; the next process resumes it
        e.future = pool.schedule({ attempt(e) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun attempt(e: Entry) {
        synchronized(this) {
            if (entries[e.info.id] !== e || e.inFlight) return
            e.inFlight = true
        }
        val outcome = try {
            store.wav(e.info.id)?.let { transcriber.transcribe(it, e.info) } ?: Outcome.Failed("Audio missing")
        } catch (t: Throwable) {
            Outcome.Failed("Unexpected error: ${t.javaClass.simpleName}") // never leave the entry in flight: it would block every later text
        }
        synchronized(this) {
            e.inFlight = false
            if (entries[e.info.id] !== e) return // discarded meanwhile
            when (outcome) {
                is Outcome.Text -> {
                    e.info = e.info.copy(text = outcome.text)
                    store.saveInfo(e.info) // the text is durable before the audio goes
                    store.deleteAudio(e.info.id)
                    e.failures = 0
                }
                is Outcome.Retry -> {
                    e.failures++
                    schedule(e, backoff.delayMs(e.failures))
                }
                is Outcome.Failed -> {
                    e.info = e.info.copy(error = outcome.message)
                    store.saveInfo(e.info)
                }
            }
            release()
            status()
        }
    }

    /** Hands texts over strictly in spoken order, stopping at the first chunk without one. */
    private fun release() {
        val l = listener ?: return
        while (true) {
            val first = entries.firstEntry()?.value ?: return
            val text = first.info.text ?: return
            entries.remove(first.info.id)
            l.onText(first.info, text, first.recovered)
        }
    }

    private fun status() {
        val l = listener ?: return
        val s = QueueStatus(
            pending = entries.size,
            offline = entries.values.any { it.failures > 0 && it.info.text == null },
            failed = entries.values.firstOrNull { it.info.error != null }?.info,
        )
        if (s != lastStatus) {
            lastStatus = s
            l.onStatus(s)
        }
    }
}
