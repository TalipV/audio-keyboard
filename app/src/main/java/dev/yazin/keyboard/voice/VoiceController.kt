package dev.yazin.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.yazin.keyboard.BuildConfig
import dev.yazin.keyboard.engine.llm.LlmResult
import dev.yazin.keyboard.engine.llm.Prompt
import dev.yazin.keyboard.engine.llm.PromptContext
import dev.yazin.keyboard.engine.llm.PromptRequest
import dev.yazin.keyboard.engine.voice.ChunkInfo
import dev.yazin.keyboard.engine.voice.ChunkQueue
import dev.yazin.keyboard.engine.voice.ChunkStore
import dev.yazin.keyboard.engine.voice.DictationRoute
import dev.yazin.keyboard.engine.voice.EndpointConfig
import dev.yazin.keyboard.engine.voice.Endpointer
import dev.yazin.keyboard.engine.voice.Outcome
import dev.yazin.keyboard.engine.voice.QueueStatus
import dev.yazin.keyboard.engine.voice.TranscribeRequest
import dev.yazin.keyboard.engine.voice.VoiceSession
import dev.yazin.keyboard.engine.voice.VoiceStatus
import dev.yazin.keyboard.engine.voice.VoiceUi
import dev.yazin.keyboard.engine.voice.Wav
import dev.yazin.keyboard.engine.voice.WhisperClient
import dev.yazin.keyboard.keyboard.VoiceInput
import dev.yazin.keyboard.llm.LlmLogFile
import dev.yazin.keyboard.settings.Prefs
import java.io.File
import java.io.IOException

/**
 * Dictation glue (spec §7): mic → endpointer → durable queue → Whisper → the field, steered by [VoiceSession].
 * Main thread unless noted.
 */
class VoiceController(private val context: Context, private val host: Host) : VoiceSession.Effects, ChunkQueue.Listener {
    interface Host {
        /** "package/fieldId" of the field the keyboard is showing for, or null when hidden (spec §7.3). */
        fun target(): String?

        /** The enabled keyboard languages, the active layout's first: "en", "ar" or e.g. "ar,en" (any may be spoken). */
        fun language(): String

        /** About 200 characters before the cursor, sent as the request's `prompt`. */
        fun textBeforeCursor(): String

        /** Personal words to hint, or null when the setting is off. Called on a queue thread. */
        fun hotwords(language: String): String?

        /** Whether [word] is in the English or German dictionary. Called on a queue thread. */
        fun latinWord(word: String): Boolean

        /** Inserts [text] as one ↶ step, shared with earlier inserts of the same [group] (a dictation's chunks). */
        fun insert(text: String, group: String? = null)

        /** The field and its scope for ✦ (AI orb spec §4), read now; null when the field can't be read. */
        fun promptRequest(): PromptRequest?

        /**
         * [context] with the screen's text ("" when screen context is off), and the user's writing samples and the Chat's
         * memory when the setting is on. Called on a worker thread.
         */
        fun withMemory(context: PromptContext): PromptContext

        /** Puts an LLM answer in place of [request]'s scope; false when the field changed meanwhile (then it is a chip). */
        fun replace(request: PromptRequest, text: String): Boolean

        fun haptic()

        /** [ui] or [chip] changed. */
        fun voiceChanged()

        fun voiceLevel(level: Float)
    }

    private class Dictation(val id: String, val target: String, val prompt: String, val language: String, val ask: Boolean) {
        var seq = 0
        @Volatile var send = true
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val queue = sharedQueue(context)
    private val session = VoiceSession(this)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    /** Process-wide, like the queue that released them: a recreated keyboard service still shows them. */
    private val chips get() = pendingTexts
    private var source: AudioSource? = null
    private var dictation: Dictation? = null
    @Volatile private var autoModel: Pair<String, String>? = null // url → model
    /** Words of the prompt being spoken or retried, in order and not yet acked: after a crash they come back as chips. */
    private val promptChunks = ArrayList<Pair<ChunkInfo, String>>()
    /** The ✦ dictation whose words feed the open orb. */
    private var promptId: String? = null
    /** Every ✦ dictation of this process: their words are an instruction, never typed as narration. */
    private val promptIds = HashSet<String>()
    /** Bumped per LLM call, so a late answer can tell it is stale. */
    private var promptGen = 0
    /** Takes the open ✦ dictation's words verbatim instead of the LLM (chat memory spec §7: panel input). */
    private var memo: ((String) -> Unit)? = null

    val ui: VoiceUi get() = session.ui

    /** The open orb takes a [memo], not a ✦ instruction. */
    val memoing: Boolean get() = memo != null

    /** The oldest dictated text waiting for the user (spec §7.3 "Pending dictation"). */
    val chip: String? get() = chips.firstOrNull()?.second

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = queue.wake()
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) session.onFocusLost() // e.g. a call
        }, main)
        .build()

    init {
        connectivity.registerDefaultNetworkCallback(network)
        current = this
        queue.attach(this) // texts recovered from an earlier process (spec §7.3) arrive now
        queue.wake() // chunks that waited for a keyboard to transcribe with
    }

    fun destroy() {
        session.onKeyboardHidden()
        connectivity.unregisterNetworkCallback(network)
        if (current === this) {
            current = null
            queue.attach(null) // texts wait, on disk and in the queue, for the next keyboard service
        }
    }

    /** Dictated texts waiting for the user, oldest first (spec §7.3): the strip chip and the clipboard panel. */
    val pending: List<Pair<String, String>> get() = chips.map { it.first.id to it.second }

    /** Inserts ([insert]) or discards the pending dictation [id]. */
    fun usePending(id: String, insert: Boolean) {
        val entry = chips.firstOrNull { it.first.id == id } ?: return
        chips.remove(entry)
        if (insert) host.insert(entry.second)
        queue.ack(id)
        host.voiceChanged()
    }

    fun onKeyboardHidden() = session.onKeyboardHidden()

    fun onKeyboardShown() = session.onKeyboardShown()

    fun onInput(input: VoiceInput) {
        when (input) {
            is VoiceInput.Start -> {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    context.startActivity(Intent(context, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                session.onStart(input.prompt)
            }
            VoiceInput.OrbTap -> session.onOrbTap()
            VoiceInput.Dismiss -> session.onDismiss()
            VoiceInput.Retry -> queue.retryFailed()
            VoiceInput.Discard -> queue.discardFailed()
            VoiceInput.PromptRetry -> session.onPromptRetry()
            VoiceInput.InsertTranscript -> session.onInsertTranscript()
            VoiceInput.Undo, VoiceInput.ScreenSetup -> Unit // KeyboardService owns the field and opens settings
            is VoiceInput.Chip -> chips.firstOrNull()?.let { usePending(it.first.id, insert = !input.longPress) }
        }
    }

    /** Listens as ✦ does, then hands what was said to [onText] as it was said and closes the orb: no LLM call. */
    fun memo(onText: (String) -> Unit) {
        memo = onText
        onInput(VoiceInput.Start(prompt = true))
        if (!session.ui.recording) memo = null // no mic, or the orb was busy
    }

    override fun startRecording(prompt: Boolean): Boolean {
        val target = host.target() ?: return false
        val whisperPrompt = if (prompt) "" else host.textBeforeCursor().takeLast(PROMPT_CHARS)
        val id = System.currentTimeMillis().toString() + if (prompt && memo != null) MEMO else ""
        val d = Dictation(id, target, whisperPrompt, host.language(), prompt)
        val endpointer = Endpointer(EndpointConfig(autoStopMs = prefs.voiceSilenceMs))
        val src = DebugAudio.source(context) ?: MicSource()
        val started = src.start(
            onFrame = { frame -> // mic thread
                val events = endpointer.accept(frame)
                val level = endpointer.level
                main.post { onAudio(d, events, level) }
            },
            onEnd = { error -> // mic thread, after the last frame
                val last = if (d.send) endpointer.finish() else null
                main.post { onAudioEnd(d, last, error) }
            },
        )
        if (!started) return false
        source = src
        dictation = d
        promptId = if (prompt) d.id.also { promptIds += it } else null
        audio.requestAudioFocus(focusRequest)
        preload()
        return true
    }

    override fun stopRecording(send: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "listening ended, send=$send", Throwable("why")) // the stack names the cause
        dictation?.send = send
        source?.stop()
        source = null
        audio.abandonAudioFocusRequest(focusRequest)
        host.voiceLevel(0f)
    }

    override fun haptic() = host.haptic()

    override fun render(ui: VoiceUi) {
        if (!ui.orb) {
            promptId = null // words still on their way become chips
            memo = null
        }
        // Like the Samsung keyboard: once the words (or ✦'s answer) are in, back to the keys after a short look at "Done".
        val done = ui.orb && !ui.recording && ui.status == VoiceStatus.DONE && ui.failed == null && ui.caption == null
        if (done && !wasDone && prefs.voiceReturnToKeys) {
            main.postDelayed({
                val now = session.ui
                if (now.orb && !now.recording && now.status == VoiceStatus.DONE && now.failed == null) session.onDismiss()
            }, RETURN_TO_KEYS_MS)
        }
        wasDone = done
        host.voiceChanged()
    }

    /** The last render showed a finished orb; [render] returns to the keys once per finish. */
    private var wasDone = false

    override fun closeLater(ms: Long) {
        main.postDelayed({ session.onCloseTimeout() }, ms)
    }

    override fun onText(chunk: ChunkInfo, text: String, recovered: Boolean) { // queue thread
        main.post { current?.deliver(chunk, text, recovered) }
    }

    private fun deliver(chunk: ChunkInfo, text: String, recovered: Boolean) {
        val dictationId = chunk.id.substringBeforeLast('-')
        when {
            text.isEmpty() -> queue.ack(chunk.id) // nothing was said
            !recovered && dictationId == promptId -> {
                promptChunks += chunk to text
                session.onTranscript(promptChunks.joinToString(" ") { it.second })
            }
            isMemo(chunk) -> queue.ack(chunk.id) // the memo is over: its words are never typed or offered
            DictationRoute.of(chunk, host.target(), recovered, prompt = dictationId in promptIds) == DictationRoute.Route.INSERT -> {
                host.insert(text, dictationId)
                queue.ack(chunk.id)
            }
            else -> {
                chips.addLast(chunk to text)
                host.voiceChanged()
            }
        }
    }

    override fun onStatus(status: QueueStatus) { // queue thread
        main.post { session.onQueue(status) }
    }

    private fun onAudio(d: Dictation, events: List<Endpointer.Event>, level: Float) {
        val live = d === dictation && source != null
        if (live) host.voiceLevel(level)
        for (e in events) {
            when (e) {
                is Endpointer.Event.Chunk -> enqueue(d, e.pcm)
                Endpointer.Event.SpeechStarted -> if (live) session.onSpeech()
                Endpointer.Event.AutoStop -> if (live) session.onAutoStop()
                Endpointer.Event.NoSpeech -> if (live) session.onNoSpeech()
                Endpointer.Event.MaxDuration -> if (live) session.onMaxDuration()
            }
        }
    }

    private fun onAudioEnd(d: Dictation, last: ShortArray?, error: Boolean) {
        val queued = last?.let { enqueue(d, it) } == true
        if (d !== dictation) return
        if (error) session.onMicError()
        session.onRecordingEnded(lastChunkQueued = queued)
    }

    /** Stores [pcm] as the dictation's next chunk; false when it couldn't be stored. */
    private fun enqueue(d: Dictation, pcm: ShortArray): Boolean {
        val info = ChunkInfo("${d.id}-%04d".format(++d.seq), d.language, d.prompt, d.target, System.currentTimeMillis())
        return try {
            queue.add(info, Wav.encode(pcm)) // ponytail: fsync on the main thread, one small file every few seconds
            true
        } catch (e: IOException) {
            Log.w(TAG, "couldn't store ${info.id}: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun runPrompt() {
        val chunks = promptChunks.toList()
        promptChunks.clear()
        val call = ++promptGen
        val target = host.target()
        val onMemo = memo
        if (onMemo != null && chunks.isNotEmpty()) {
            memo = null
            onMemo(chunks.joinToString(" ") { it.second })
            chunks.forEach { queue.ack(it.first.id) }
            session.onPromptDone(null)
            session.onDismiss()
            return
        }
        val request = host.promptRequest()
        if (request == null || chunks.isEmpty()) {
            main.post { finishPrompt(call, chunks, target, null, LlmResult.Failed("Can't read the text field")) }
            return
        }
        val instruction = chunks.joinToString(" ") { it.second }
        val client = prefs.llm(LlmLogFile.record(context))
        val model = prefs.llmModel
        Thread({
            // An uncaught throw here would take the keyboard down; without the screen ✦ still answers.
            val withScreen = request.copy(context = runCatching { host.withMemory(request.context) }
                .onFailure { Log.w(TAG, "couldn't read the screen for ✦", it) }.getOrDefault(request.context))
            val t0 = SystemClock.elapsedRealtime()
            val out = client.complete(model, Prompt.system(withScreen.context), Prompt.user(withScreen.context, instruction), withScreen.context.app)
            if (BuildConfig.DEBUG) Log.d(TAG, "prompt → ${out.javaClass.simpleName} in ${SystemClock.elapsedRealtime() - t0} ms, ${withScreen.context.samples.size} samples, chat ${withScreen.context.chat != null}")
            main.post { finishPrompt(call, chunks, target, request, out) }
        }, "llm").start()
    }

    /**
     * An answer goes in its own field; if that field moved on or was edited meanwhile, it is a chip. A failure the open
     * orb is still waiting for offers Retry / Insert what I said; otherwise its words are chips (nothing lost).
     */
    private fun finishPrompt(call: Int, chunks: List<Pair<ChunkInfo, String>>, target: String?, request: PromptRequest?, out: LlmResult) {
        val live = call == promptGen && session.thinking
        val answer = (out as? LlmResult.Text)?.let { Prompt.clean(it.text) }.orEmpty()
        if (answer.isNotEmpty() && request != null) {
            val placed = target != null && host.target() == target && host.replace(request, answer)
            if (placed) chunks.forEach { queue.ack(it.first.id) }
            else {
                chips.addLast(chunks.first().first to answer) // using or discarding the chip acks the first chunk
                chunks.drop(1).forEach { queue.ack(it.first.id) }
            }
            host.voiceChanged()
            if (live) session.onPromptDone(null)
            return
        }
        val message = (out as? LlmResult.Failed)?.message ?: "Empty reply from the AI server"
        if (live) {
            promptChunks.clear()
            promptChunks.addAll(chunks)
            session.onPromptDone(message)
        } else {
            chips.addAll(notMemo(chunks))
            host.voiceChanged()
        }
    }

    override fun useTranscript(insert: Boolean) {
        val chunks = notMemo(promptChunks.toList())
        promptChunks.clear()
        if (chunks.isEmpty()) return
        if (insert) {
            host.insert(chunks.joinToString(" ") { it.second })
            chunks.forEach { queue.ack(it.first.id) }
        } else {
            chips.addAll(chunks)
        }
        host.voiceChanged()
    }

    /** [chunks] without a memo's words, which are acked and dropped: what the memo didn't take is never typed or offered. */
    private fun notMemo(chunks: List<Pair<ChunkInfo, String>>): List<Pair<ChunkInfo, String>> {
        val (memo, rest) = chunks.partition { isMemo(it.first) }
        memo.forEach { queue.ack(it.first.id) }
        return rest
    }

    private fun transcribe(wav: ByteArray, chunk: ChunkInfo): Outcome { // queue thread
        val client = prefs.whisper(host::latinWord)
        val model = when (val m = model(client)) {
            is Outcome.Text -> m.text
            else -> return m // an outage waits; a settings mistake shows Retry / Discard
        }
        val t0 = SystemClock.elapsedRealtime()
        val out = client.transcribe(wav, TranscribeRequest(model, chunk.language, chunk.prompt, host.hotwords(chunk.language)))
        if (BuildConfig.DEBUG) Log.d(TAG, "${chunk.id} ${Wav.durationMs(wav)} ms audio → ${out.javaClass.simpleName} in ${SystemClock.elapsedRealtime() - t0} ms")
        return out
    }

    /** The model ([Outcome.Text]) from settings or the server (spec §7.2), the automatic one remembered per URL. */
    private fun model(client: WhisperClient): Outcome {
        val configured = prefs.voiceModel
        val url = prefs.voiceUrl
        if (configured.isBlank()) autoModel?.takeIf { it.first == url }?.let { return Outcome.Text(it.second) }
        return client.model(configured).also { if (it is Outcome.Text && configured.isBlank()) autoModel = url to it.text }
    }

    /** Spec §7.2 cold model: load it while the user is still talking. */
    private fun preload() {
        Thread({
            val c = prefs.whisper()
            (model(c) as? Outcome.Text)?.let { c.preload(it.text) }
        }, "voice-preload").start()
    }

    private companion object {
        /** How long "Done" shows before the keys come back. */
        const val RETURN_TO_KEYS_MS = 700L

        /** Released texts waiting for the user (chips and the clipboard panel). Main thread. */
        val pendingTexts = ArrayDeque<Pair<ChunkInfo, String>>()

        /** The live controller: one keyboard service at a time owns the process-wide queue. */
        @Volatile var current: VoiceController? = null
        private var shared: ChunkQueue? = null

        /** One queue per process (spec §7.3): a recreated keyboard service takes it over instead of racing a second one over the same files. */
        @Synchronized fun sharedQueue(context: Context): ChunkQueue = shared ?: ChunkQueue(
            ChunkStore(File(context.applicationContext.noBackupFilesDir, "voice")),
            { wav, chunk -> current?.transcribe(wav, chunk) ?: Outcome.Retry("keyboard closed") },
        ).also {
            shared = it
            it.resume() // spec §7.3 restart recovery
        }

        const val TAG = "KbdVoice"
        const val PROMPT_CHARS = 200
        /** Ends a memo dictation's id: after the start time, so ids still sort in spoken order, and on disk, so a restart still knows. */
        const val MEMO = "m"

        fun isMemo(chunk: ChunkInfo) = chunk.id.substringBeforeLast('-').endsWith(MEMO)
    }
}
