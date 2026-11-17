package dev.yazin.keyboard.engine.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test fun headerDescribes16kMonoPcm() {
        val wav = Wav.encode(ShortArray(16_000) { (it % 100).toShort() })
        assertEquals(44 + 32_000, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(32_000, b.getInt(40))
        assertEquals(1_000L, Wav.durationMs(wav))
    }

    @Test fun decodeRoundTripsAndSkipsExtraChunks() {
        val pcm = ShortArray(50) { (it * 300 - 7_000).toShort() }
        assertArrayEquals(pcm, Wav.decode(Wav.encode(pcm)))
        // macOS tools write extra chunks (LIST, FLLR) before "data".
        val plain = Wav.encode(pcm)
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        assertArrayEquals(pcm, Wav.decode(plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)))
    }
}
