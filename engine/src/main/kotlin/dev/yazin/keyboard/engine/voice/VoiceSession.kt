package dev.yazin.keyboard.engine.voice

/** The orb's caption (AI orb spec §2). Strings live in the app. */
enum class VoiceStatus { SPEAK_NOW, LISTENING, TRANSCRIBING, WAITING, NO_SPEECH, FAILED, MIC_UNAVAILABLE, THINKING, PROMPT_FAILED, DONE }

/** Everything the keyboard draws for voice. */
data class VoiceUi(
    /** The keys are collapsed to the orb band. */
    val orb: Boolean = false,
    /** ✦: what is said is an instruction for the LLM, not text to type. */
    val prompt: Boolean = false,
    val recording: Boolean = false,
    val status: VoiceStatus = VoiceStatus.SPEAK_NOW,
    val pending: Int = 0,
    val failed: ChunkInfo? = null,
    /** Prompt mode: the instruction heard so far. */
    val transcript: String = "",
    /** PROMPT_FAILED: why the LLM call failed. */
    val error: String? = null,
    /** Shown in place of the status message: what the band is for when it isn't plain voice (chat recording, memos). */
    val caption: String? = null,
)

/**
 * The orb's lifecycle (AI orb spec §2), free of Android. The app feeds in taps, endpointer events, queue status and LLM
 * results, and carries out the [Effects]. Main thread only.
 *
 * - 🎤 / ✦ open the orb and listen. Tapping the orb stops; tapping again listens again in the same mode.
 * - Once everything said is transcribed, narration is done; a prompt goes to the LLM ([Effects.runPrompt]).
 * - The orb stays open until dismissed (swipe down, the keyboard icon, the user moving the cursor, the keyboard hiding).
 */
class VoiceSession(private val fx: Effects) {
    interface Effects {
        /** Starts the mic for a new dictation ([prompt]: an instruction); false when it can't (mic busy, no field). */
        fun startRecording(prompt: Boolean): Boolean

        /** Stops the mic; [send] flushes the speech still in the endpointer as a last chunk. */
        fun stopRecording(send: Boolean)

        /** Sends the transcript, field and screen to the LLM; the answer comes back through [onPromptDone]. */
        fun runPrompt()

        /** The words of a prompt that won't run: typed into the field ([insert]) or kept as pending chips. */
        fun useTranscript(insert: Boolean)

        fun haptic()

        fun render(ui: VoiceUi)

        /** Calls [onCloseTimeout] after [ms]. */
        fun closeLater(ms: Long)
    }

    private enum class Mode { IDLE, RECORDING, FINISHING, THINKING, OPEN }

    private var mode = Mode.IDLE
    private var queue = QueueStatus(0, false, null)
    /** The mic thread hasn't delivered its last chunk yet. */
    private var audioOpen = false
    /** A last chunk was just queued and the queue's status for it hasn't arrived: [queue] still counts without it. */
    private var awaitingQueue = false
    /** The orb opened by itself to show a refused chunk; it closes again once that is resolved. */
    private var refusalOnly = false

    var ui = VoiceUi()
        private set

    /** An LLM call is in flight for the open orb. */
    val thinking: Boolean get() = mode == Mode.THINKING

    /** 🎤 ([prompt] false) or ✦ in the toolbar. */
    fun onStart(prompt: Boolean) {
        if (mode == Mode.IDLE) record(prompt)
    }

    fun onOrbTap() {
        when (mode) {
            Mode.RECORDING -> finish()
            Mode.OPEN -> if (ui.status != VoiceStatus.FAILED) record(ui.prompt) // a refusal needs Retry / Discard first
            else -> Unit
        }
    }

    /**
     * Back to the keys. A prompt that won't run keeps its words: those heard so far become chips here, those still on
     * their way are routed as chips (VoiceController). An answer in flight still lands in its field.
     */
    fun onDismiss() {
        when (mode) {
            Mode.IDLE -> return
            Mode.RECORDING -> {
                fx.stopRecording(send = true)
                fx.haptic()
                if (ui.prompt) fx.useTranscript(insert = false)
            }
            Mode.FINISHING -> if (ui.prompt) fx.useTranscript(insert = false)
            Mode.OPEN -> if (ui.status == VoiceStatus.PROMPT_FAILED) fx.useTranscript(insert = false)
            Mode.THINKING -> Unit
        }
        close()
    }

    /** Prompt mode: the words heard so far, joined in spoken order. */
    fun onTranscript(text: String) {
        if (ui.orb && ui.prompt) show(ui.copy(transcript = text))
    }

    /** The LLM call [Effects.runPrompt] started is over: [error] is null when its answer is in the field (or a chip). */
    fun onPromptDone(error: String?) {
        if (mode != Mode.THINKING) return
        mode = Mode.OPEN
        show(if (error == null) ui.copy(status = VoiceStatus.DONE, transcript = "", error = null) else ui.copy(status = VoiceStatus.PROMPT_FAILED, error = error))
    }

    fun onPromptRetry() {
        if (mode == Mode.OPEN && ui.status == VoiceStatus.PROMPT_FAILED) think()
    }

    fun onInsertTranscript() {
        if (mode != Mode.OPEN || ui.status != VoiceStatus.PROMPT_FAILED) return
        fx.useTranscript(insert = true)
        show(ui.copy(status = VoiceStatus.DONE, transcript = "", error = null))
    }

    fun onSpeech() {
        if (mode == Mode.RECORDING && ui.status == VoiceStatus.SPEAK_NOW) show(ui.copy(status = VoiceStatus.LISTENING))
    }

    fun onAutoStop() {
        if (mode == Mode.RECORDING) finish()
    }

    fun onNoSpeech() {
        if (mode != Mode.RECORDING) return
        fx.stopRecording(send = false)
        fx.haptic()
        mode = Mode.OPEN
        show(ui.copy(recording = false, status = VoiceStatus.NO_SPEECH))
        fx.closeLater(MESSAGE_MS)
    }

    fun onMaxDuration() = onAutoStop()

    fun onFocusLost() = onAutoStop() // e.g. a call

    fun onMicError() {
        if (mode != Mode.RECORDING) return
        finish()
        show(ui.copy(status = VoiceStatus.MIC_UNAVAILABLE))
        fx.closeLater(MESSAGE_MS)
    }

    fun onKeyboardHidden() = onDismiss()

    /** A refusal outlives a hidden keyboard: it blocks later text, so its Retry / Discard comes back. */
    fun onKeyboardShown() = update(queue)

    /** The mic thread is done; [lastChunkQueued] when it flushed a final chunk, whose queue status is still on its way. */
    fun onRecordingEnded(lastChunkQueued: Boolean = false) {
        audioOpen = false
        awaitingQueue = lastChunkQueued
        update(queue)
    }

    fun onCloseTimeout() {
        if (ui.status !in MESSAGES) return
        when (mode) {
            Mode.OPEN -> show(ui.copy(status = VoiceStatus.DONE))
            Mode.FINISHING -> {
                show(ui.copy(status = VoiceStatus.TRANSCRIBING))
                update(queue)
            }
            else -> Unit
        }
    }

    fun onQueue(status: QueueStatus) {
        awaitingQueue = false
        update(status)
    }

    private fun update(status: QueueStatus) {
        queue = status
        var next = ui.copy(pending = status.pending, failed = status.failed)
        if (mode == Mode.IDLE && status.failed != null) {
            mode = Mode.OPEN // a refusal needs the user: Retry / Discard (spec §7.3)
            refusalOnly = true
            next = next.copy(orb = true, prompt = false)
        }
        when {
            status.failed != null && (mode == Mode.OPEN || mode == Mode.FINISHING) -> next = next.copy(status = VoiceStatus.FAILED)
            mode == Mode.OPEN && next.status == VoiceStatus.FAILED -> {
                if (refusalOnly) {
                    show(next)
                    close()
                    return
                }
                next = next.copy(status = VoiceStatus.DONE)
            }
            mode == Mode.FINISHING && next.status in MESSAGES -> Unit // stays until its timer
            mode == Mode.FINISHING && status.pending == 0 && !audioOpen && !awaitingQueue -> {
                show(next)
                settle()
                return
            }
            mode == Mode.FINISHING -> next = next.copy(status = if (status.offline) VoiceStatus.WAITING else VoiceStatus.TRANSCRIBING)
        }
        show(next)
    }

    private fun record(prompt: Boolean) {
        if (ui.status == VoiceStatus.PROMPT_FAILED) fx.useTranscript(insert = false) // speaking again keeps the old words as chips
        if (!fx.startRecording(prompt)) return
        audioOpen = true
        refusalOnly = false
        fx.haptic()
        mode = Mode.RECORDING
        show(ui.copy(orb = true, prompt = prompt, recording = true, status = VoiceStatus.SPEAK_NOW, transcript = "", error = null))
    }

    private fun finish() {
        fx.stopRecording(send = true)
        fx.haptic()
        mode = Mode.FINISHING
        show(ui.copy(recording = false, status = if (queue.offline) VoiceStatus.WAITING else VoiceStatus.TRANSCRIBING))
    }

    /** Everything said is in: narration is done; a prompt goes to the LLM. */
    private fun settle() {
        mode = Mode.OPEN
        when {
            !ui.prompt -> show(ui.copy(status = VoiceStatus.DONE))
            ui.transcript.isBlank() -> {
                show(ui.copy(status = VoiceStatus.NO_SPEECH))
                fx.closeLater(MESSAGE_MS)
            }
            else -> think()
        }
    }

    private fun think() {
        mode = Mode.THINKING
        show(ui.copy(status = VoiceStatus.THINKING, error = null))
        fx.runPrompt()
    }

    private fun close() {
        mode = Mode.IDLE
        refusalOnly = false
        show(VoiceUi(pending = queue.pending, failed = queue.failed))
    }

    private fun show(next: VoiceUi) {
        if (next != ui) {
            ui = next
            fx.render(next)
        }
    }

    private companion object {
        const val MESSAGE_MS = 1_500L
        val MESSAGES = setOf(VoiceStatus.NO_SPEECH, VoiceStatus.MIC_UNAVAILABLE)
    }
}
