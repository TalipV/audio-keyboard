package dev.yazin.keyboard.engine.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM WAV: what the recorder makes and Speaches decodes (it rejects headerless PCM with 415). */
object Wav {
    const val RATE = 16_000

    fun encode(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    /** The samples of a 16-bit mono WAV, skipping any chunks before "data". */
    fun decode(wav: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4, Charsets.US_ASCII)
            val len = b.getInt(p + 4)
            if (id == "data") {
                val n = minOf(len, wav.size - p - 8) / 2
                return ShortArray(n) { b.getShort(p + 8 + it * 2) }
            }
            p += 8 + len + (len and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }

    fun durationMs(wav: ByteArray): Long = (wav.size - 44).coerceAtLeast(0) / 2 * 1000L / RATE
}
