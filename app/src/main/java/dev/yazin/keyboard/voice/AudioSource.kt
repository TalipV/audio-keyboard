package dev.yazin.keyboard.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.yazin.keyboard.engine.voice.Wav

/** 20 ms frames of 16 kHz mono PCM16, delivered on the source's own thread. */
interface AudioSource {
    /** False when the mic can't start (busy, no permission). [onEnd] runs on that thread after the last frame. */
    fun start(onFrame: (ShortArray) -> Unit, onEnd: (error: Boolean) -> Unit): Boolean

    fun stop()
}

/** The real mic: `AudioRecord`, `VOICE_RECOGNITION` source (spec §7.2). */
class MicSource : AudioSource {
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // VoiceController checks RECORD_AUDIO before starting
    override fun start(onFrame: (ShortArray) -> Unit, onEnd: (Boolean) -> Unit): Boolean {
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 10))
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return false }
        running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var error = false
            try {
                while (running) {
                    val f = ShortArray(FRAME)
                    var n = 0
                    while (n < FRAME && running) {
                        val r = rec.read(f, n, FRAME - n)
                        if (r < 0) { error = true; running = false; break }
                        n += r
                    }
                    if (n == FRAME) onFrame(f)
                }
            } finally {
                rec.stop()
                rec.release()
                onEnd(error)
            }
        }, "mic").start()
        return true
    }

    override fun stop() {
        running = false
    }

    companion object {
        const val FRAME = 320 // 20 ms
    }
}
