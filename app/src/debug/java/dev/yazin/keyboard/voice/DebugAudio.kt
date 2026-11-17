package dev.yazin.keyboard.voice

import android.content.Context
import android.os.SystemClock
import dev.yazin.keyboard.engine.voice.Wav
import java.io.File

/** Debug builds: dictate from files/voice-input.wav when present (`tools/dev.sh voice`); the emulator has no voice. */
object DebugAudio {
    fun source(context: Context): AudioSource? =
        File(context.filesDir, "voice-input.wav").takeIf { it.isFile }?.let { FileSource(Wav.decode(it.readBytes())) }
}

/** Plays [pcm] in real time (so the endpointer's timers behave as with a live mic), then silence until stopped. */
private class FileSource(private val pcm: ShortArray) : AudioSource {
    @Volatile private var running = false

    override fun start(onFrame: (ShortArray) -> Unit, onEnd: (Boolean) -> Unit): Boolean {
        running = true
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            var i = 0
            var frames = 0L
            while (running) {
                val f = ShortArray(MicSource.FRAME)
                if (i < pcm.size) {
                    val n = minOf(f.size, pcm.size - i)
                    pcm.copyInto(f, 0, i, i + n)
                    i += n
                }
                onFrame(f)
                frames++
                val wait = t0 + frames * 20 - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            onEnd(false)
        }, "voice-file").start()
        return true
    }

    override fun stop() {
        running = false
    }
}
