package dev.yazin.keyboard.chat

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.yazin.keyboard.engine.chat.ChatKey
import dev.yazin.keyboard.engine.chat.ChatMemory
import dev.yazin.keyboard.engine.llm.LlmCancel
import dev.yazin.keyboard.engine.llm.LlmResult
import dev.yazin.keyboard.engine.llm.ProfilePrompt
import dev.yazin.keyboard.engine.llm.Prompt
import dev.yazin.keyboard.llm.LlmLogFile
import dev.yazin.keyboard.settings.Prefs

/**
 * Writes due Profiles (chat memory spec §5.2) one Chat at a time on one thread, only while the keyboard is hidden;
 * Regenerate's one Chat is the exception. A failure or cancel leaves the Chat due for the next hide; after a failure
 * that isn't a cancel, hides start none for [BACKOFF_MS].
 */
object ProfileWorker {
    private var thread: Thread? = null
    /** The running loop's; null once [stop] cancelled it. */
    private var cancel: LlmCancel? = null
    /** The running loop writes one Chat ([writeNow]), not every due one. */
    private var single = false
    /** When the last call failed other than by a cancel ([SystemClock.elapsedRealtime]), or 0. */
    @Volatile private var failedAt = 0L

    /** On keyboard hide. Main thread. */
    @Synchronized
    fun start(context: Context) = launch(context, null)

    /** Regenerate (spec §7): writes [key]'s Profile now, keyboard up; the next [stop] cancels it like the hidden loop. Main thread. */
    @Synchronized
    fun writeNow(context: Context, key: ChatKey) = launch(context, key)

    private fun launch(context: Context, only: ChatKey?) {
        val running = cancel != null && thread?.isAlive == true
        if (running && !single) return // the loop writes every due Chat
        if (only == null && failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < BACKOFF_MS) return
        val app = context.applicationContext
        if (!Prefs(app).style) return // ✦ doesn't use Chat memory then: don't send History anywhere
        val c = cancel.takeIf { running } ?: LlmCancel() // one [stop] still ends both
        val previous = thread
        cancel = c
        single = only != null
        thread = Thread({
            try {
                previous?.join() // a cancelled loop finishing its call must not write the same Chat
                run(app, c, only)
            } catch (e: Exception) {
                if (!c.cancelled) failedAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "profile loop failed", e)
            }
        }, "profile").apply { start() }
    }

    /** On keyboard show: ends the call now, so a one-slot server is free for ✦. Main thread. */
    @Synchronized
    fun stop() {
        val c = cancel ?: return
        cancel = null
        Thread(c::cancel, "profile-cancel").start() // disconnecting can do socket IO
    }

    private fun run(context: Context, cancel: LlmCancel, only: ChatKey?) {
        val prefs = Prefs(context)
        val record = LlmLogFile.record(context)
        val memory = ChatFiles.get(context)
        val due = memory.due()
        for (key in if (only != null) due.filter { it == only } else due) {
            if (cancel.cancelled) return
            val info = memory.info(key) ?: continue
            val history = memory.historyText(key, ChatMemory.HISTORY_CHARS)
            val user = ProfilePrompt.user(key.title, info.corrections, info.written, history, info.topics)
            // The debug log skips cancelled calls (one per field switch) and keeps only the start of the History.
            val client = prefs.llm { call ->
                if (!cancel.cancelled) record(call.copy(user = ProfilePrompt.user(key.title, info.corrections, info.written, ProfilePrompt.logged(history), info.topics)))
            }
            val out = client.complete(
                prefs.llmModel, ProfilePrompt.SYSTEM, user, key.app,
                maxTokens = ProfilePrompt.MAX_TOKENS, readTimeoutMs = ProfilePrompt.TIMEOUT_MS, cancel = cancel,
            )
            val text = (out as? LlmResult.Text)?.let { Prompt.clean(it.text) }.orEmpty()
            if (text.isEmpty()) { // offline or refused: the rest would fail the same way
                if (!cancel.cancelled) failedAt = SystemClock.elapsedRealtime()
                return
            }
            failedAt = 0L
            val written = ProfilePrompt.parse(text)
            if (written.profile.isBlank()) return
            memory.setWritten(key, written.profile, written.topics)
            ChatFiles.save(context)
        }
    }

    /** No background Profile call starts for this long after one failed. */
    private const val BACKOFF_MS = 10 * 60_000L
    private const val TAG = "KbdProfile"
}
