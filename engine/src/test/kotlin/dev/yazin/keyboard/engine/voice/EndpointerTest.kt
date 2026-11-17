package dev.yazin.keyboard.engine.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    private val rnd = java.util.Random(7)
    private fun amp(db: Float) = 32_768 * 10.0.pow(db / 20.0)

    /** Gaussian noise at [db] dBFS RMS. */
    private fun noise(ms: Int, db: Float = -65f) = ShortArray(ms * 16) { (rnd.nextGaussian() * amp(db)).toInt().toShort() }

    /** A 220 Hz tone at [db] dBFS RMS: stands in for a voiced sound. */
    private fun tone(ms: Int, db: Float = -20f) =
        ShortArray(ms * 16) { i -> (sin(2 * PI * 220 * i / 16_000) * amp(db) * sqrt(2.0)).toInt().toShort() }

    /** Speech-like: 200 ms "syllables" with 60 ms [dip]s, never pausing long enough to cut a chunk. */
    private fun syllables(ms: Int, dip: () -> ShortArray = { noise(60, -60f) }): ShortArray {
        val out = ArrayList<Short>()
        while (out.size < ms * 16) { out += tone(200).toList(); out += dip().toList() }
        return out.take(ms * 16).toShortArray()
    }

    private fun Endpointer.feed(vararg parts: ShortArray): List<Endpointer.Event> {
        val pcm = parts.reduce { a, b -> a + b }
        val out = ArrayList<Endpointer.Event>()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(320, pcm.size - i)
            out += accept(pcm.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    private fun List<Endpointer.Event>.chunks() = filterIsInstance<Endpointer.Event.Chunk>().map { it.pcm.size / 16 } // ms

    @Test fun silenceNeverMakesAChunkAndReportsNoSpeechOnce() {
        val e = Endpointer()
        val events = e.feed(noise(6_000))
        assertEquals(listOf(Endpointer.Event.NoSpeech), events)
        assertNull(e.finish())
    }

    @Test fun aClickIsNotSpeech() {
        val events = Endpointer().feed(noise(500), tone(40), noise(6_000))
        assertTrue(Endpointer.Event.SpeechStarted !in events)
        assertTrue(Endpointer.Event.NoSpeech in events)
    }

    @Test fun aSteadyNoisyRoomIsNotSpeech() {
        val events = Endpointer().feed(noise(6_000, -35f))
        assertEquals(listOf(Endpointer.Event.NoSpeech), events)
    }

    @Test fun speechThenAPauseCutsTheFirstChunkWithItsLeadIn() {
        val e = Endpointer()
        val events = e.feed(noise(500), tone(1_500), noise(800))
        assertEquals(Endpointer.Event.SpeechStarted, events.first())
        // The first chunk keeps up to 1.5 s before speech (here all 500 ms) and 200 ms after it.
        assertEquals(listOf(500 + 1_500 + 200), events.chunks())
        assertTrue(e.level < 0.1f)
    }

    @Test fun shortBurstsWaitForASecondOfSpeechBeforeCutting() {
        val events = Endpointer().feed(noise(300), tone(500), noise(800), tone(700), noise(800))
        assertEquals(listOf(300 + 500 + 800 + 700 + 200), events.chunks())
    }

    @Test fun laterChunksKeep200msAroundTheirSpeech() {
        val events = Endpointer().feed(noise(300), tone(1_200), noise(1_000), tone(1_200), noise(800))
        assertEquals(listOf(300 + 1_200 + 200, 200 + 1_200 + 200), events.chunks())
    }

    @Test fun autoStopsOnceAfterTwoSecondsOfSilence() {
        val events = Endpointer().feed(noise(300), tone(1_200), noise(2_500))
        assertEquals(1, events.count { it == Endpointer.Event.AutoStop })
        assertTrue(events.indexOfFirst { it is Endpointer.Event.Chunk } < events.indexOf(Endpointer.Event.AutoStop))
    }

    @Test fun finishReturnsSpeechNotYetChunked() {
        val e = Endpointer()
        assertEquals(emptyList<Int>(), e.feed(noise(300), tone(600), noise(300)).chunks())
        assertEquals(300 + 600 + 200, e.finish()!!.size / 16)
    }

    @Test fun speechAtTouchDownIsKept() {
        // Talking from the first frame: the floor starts at the voice, so detection waits for the first dip;
        // the first chunk's 1.5 s lead-in still covers the opening word.
        val e = Endpointer()
        e.feed(tone(1_000), noise(200), tone(800), noise(300))
        assertEquals(1_000 + 200 + 800 + 200, e.finish()!!.size / 16)
    }

    @Test fun longSpeechWithoutPausesIsCutAndNothingIsLost() {
        val e = Endpointer()
        val events = e.feed(noise(200), syllables(30_000), noise(800))
        val chunks = events.chunks()
        assertEquals(2, chunks.size)
        assertEquals(28_000, chunks[0]) // forced cut
        assertTrue("all speech kept: $chunks", chunks.sum() >= 200 + 30_000 - 60)
    }

    @Test fun fluentSpeechWithoutRealDipsKeepsListening() {
        // Narration whose quiet sounds stay within 10 dB of its loud ones: over 3 s without a dip, yet a voice, not a fan.
        val e = Endpointer()
        val events = e.feed(noise(500), syllables(10_000) { tone(60, -30f) }, noise(800))
        assertTrue("$events", Endpointer.Event.AutoStop !in events)
        assertEquals(listOf(500 + 10_000 + 200), events.chunks())
    }

    @Test fun aFanStartingMidDictationStillAutoStops() {
        val events = Endpointer().feed(noise(300), tone(1_200), noise(8_000, -35f))
        assertTrue(Endpointer.Event.AutoStop in events)
    }

    @Test fun maxDurationIsReportedOnce() {
        val events = Endpointer(EndpointConfig(maxDictationMs = 3_000)).feed(noise(3_500))
        assertEquals(listOf(Endpointer.Event.MaxDuration), events)
    }

    @Test fun levelFollowsTheVoice() {
        val e = Endpointer()
        e.feed(noise(300), tone(200))
        assertTrue(e.level > 0.9f)
    }
}
