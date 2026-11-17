package dev.yazin.keyboard.engine.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Endpointer tunables (spec §7.2, §7.5), in ms of audio. All are calibration knobs. */
data class EndpointConfig(
    /** A frame this far above the noise floor is loud... */
    val thresholdDb: Float = 12f,
    /** ...and never quieter than this (dBFS). */
    val minSpeechDb: Float = -55f,
    /** Consecutive loud frames that start speech (60 ms); shorter is a click. */
    val onsetFrames: Int = 3,
    val chunkPauseMs: Int = 700,
    val minChunkSpeechMs: Int = 1_000,
    val autoStopMs: Int = 2_000,
    val noSpeechMs: Int = 5_000,
    val maxChunkMs: Int = 28_000,
    val maxDictationMs: Int = 300_000,
    /** Audio kept around speech in every chunk but the first. */
    val padMs: Int = 200,
    /** The first chunk keeps up to this much before its speech, so a word spoken at touch-down isn't clipped. */
    val firstLeadMs: Int = 1_500,
    val minFinalSpeechMs: Int = 200,
)

/**
 * Speech detector and chunker for 16 kHz mono PCM16, fed 20 ms frames on the mic thread (spec §7.2).
 * ponytail: energy against an adaptive noise floor; switch to WebRTC/Silero VAD if it misfires in real rooms.
 */
class Endpointer(private val config: EndpointConfig = EndpointConfig()) {
    sealed interface Event {
        data object SpeechStarted : Event
        class Chunk(val pcm: ShortArray) : Event
        data object AutoStop : Event
        data object NoSpeech : Event
        data object MaxDuration : Event
    }

    /** Loudness above the noise floor, 0..1, for the level meter. */
    var level = 0f
        private set

    private val buf = Pcm()
    private val floorWindow = FloatArray(FLOOR_FRAMES) { Float.MAX_VALUE }
    private val recentDb = FloatArray(FLOOR_FRAMES) // every frame's loudness, same ring as floorWindow
    private var floorIndex = 0
    private var elapsedMs = 0
    private var loudRun = 0
    private var heardSpeech = false
    private var silenceMs = 0
    private var chunks = 0
    private var chunkSpeechMs = 0
    private var speechStart = -1 // index in buf of this chunk's first speech sample
    private var speechEnd = -1 // index in buf just past its latest speech sample
    private var autoStopped = false
    private var noSpeechReported = false
    private var maxReported = false

    fun accept(frame: ShortArray): List<Event> {
        val events = ArrayList<Event>(1)
        val ms = frame.size * 1000 / Wav.RATE
        val db = dbfs(frame)
        val floor = floorWindow.min()
        val loud = db > maxOf(floor + config.thresholdDb, config.minSpeechDb)
        loudRun = if (loud) loudRun + 1 else 0
        recentDb[floorIndex] = db
        // Learn the floor from quiet frames, and from a sound too steady to be speech (a fan), never from a voice:
        // fluent narration can go 3 s without a dip, but its loudness keeps swinging.
        val steady = loudRun > STEADY_FRAMES && recentDb.max() - recentDb.min() < STEADY_SPREAD_DB
        floorWindow[floorIndex] = if (!loud || steady) db else floor
        floorIndex = (floorIndex + 1) % FLOOR_FRAMES
        level = ((db - floor) / LEVEL_RANGE_DB).coerceIn(0f, 1f)
        buf.add(frame)
        elapsedMs += ms

        if (loudRun >= config.onsetFrames) {
            // At onset, the frames that led up to it were speech too.
            val frames = if (loudRun == config.onsetFrames) config.onsetFrames else 1
            if (speechStart < 0) speechStart = maxOf(0, buf.size - frames * frame.size)
            speechEnd = buf.size
            chunkSpeechMs += frames * ms
            silenceMs = 0
            if (!heardSpeech) {
                heardSpeech = true
                events += Event.SpeechStarted
            }
        } else if (heardSpeech) {
            silenceMs += ms
        }

        when {
            speechStart >= 0 && silenceMs >= config.chunkPauseMs && chunkSpeechMs >= config.minChunkSpeechMs ->
                events += cut(minOf(buf.size, speechEnd + samples(config.padMs)))
            speechStart >= 0 && buf.size >= samples(config.maxChunkMs) -> events += cut(buf.size)
        }
        if (heardSpeech && !autoStopped && silenceMs >= config.autoStopMs) {
            autoStopped = true
            events += Event.AutoStop
        }
        if (!heardSpeech && !noSpeechReported && elapsedMs >= config.noSpeechMs) {
            noSpeechReported = true
            events += Event.NoSpeech
        }
        if (!maxReported && elapsedMs >= config.maxDictationMs) {
            maxReported = true
            events += Event.MaxDuration
        }
        if (speechStart < 0) {
            // No speech in this chunk yet: keep only the lead-in it may need.
            val keep = samples(lead()) + config.onsetFrames * frame.size
            if (buf.size > keep) buf.dropFront(buf.size - keep)
        }
        return events
    }

    /** Speech not yet sent as a chunk, trimmed; null when there's none worth sending. Call once, when recording stops. */
    fun finish(): ShortArray? {
        if (speechStart < 0 || chunkSpeechMs < config.minFinalSpeechMs) return null
        return buf.slice(maxOf(0, speechStart - samples(lead())), minOf(buf.size, speechEnd + samples(config.padMs)))
    }

    private fun cut(end: Int): Event.Chunk {
        val pcm = buf.slice(maxOf(0, speechStart - samples(lead())), end)
        buf.dropFront(end)
        chunks++
        speechStart = -1
        speechEnd = -1
        chunkSpeechMs = 0
        return Event.Chunk(pcm)
    }

    private fun lead() = if (chunks == 0) config.firstLeadMs else config.padMs

    private fun samples(ms: Int) = ms * (Wav.RATE / 1000)

    private fun dbfs(f: ShortArray): Float {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        val rms = sqrt(sum / f.size)
        return if (rms < 1) -96f else (20 * log10(rms / 32_768.0)).toFloat()
    }

    /** A growable sample buffer. */
    private class Pcm {
        var data = ShortArray(Wav.RATE)
        var size = 0

        fun add(f: ShortArray) {
            if (size + f.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + f.size))
            f.copyInto(data, size)
            size += f.size
        }

        fun slice(from: Int, to: Int): ShortArray = data.copyOfRange(from, to)

        fun dropFront(n: Int) {
            data.copyInto(data, 0, n, size)
            size -= n
        }
    }

    private companion object {
        const val FLOOR_FRAMES = 75 // 1.5 s
        const val STEADY_FRAMES = 150 // 3 s of unbroken loudness...
        const val STEADY_SPREAD_DB = 6f // ...that hardly varies over 1.5 s is not a voice
        const val LEVEL_RANGE_DB = 30f
    }
}
