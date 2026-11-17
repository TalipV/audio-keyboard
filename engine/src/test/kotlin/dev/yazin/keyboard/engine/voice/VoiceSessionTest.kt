package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {
    private class Fx : VoiceSession.Effects {
        val log = ArrayList<String>()
        var micWorks = true
        override fun startRecording(prompt: Boolean): Boolean { log += if (prompt) "start prompt" else "start"; return micWorks }
        override fun stopRecording(send: Boolean) { log += if (send) "send" else "drop" }
        override fun runPrompt() { log += "llm" }
        override fun useTranscript(insert: Boolean) { log += if (insert) "insert said" else "chips" }
        override fun haptic() { log += "tick" }
        override fun render(ui: VoiceUi) {}
        override fun closeLater(ms: Long) { log += "closeLater $ms" }
    }

    private val fx = Fx()
    private val s = VoiceSession(fx)
    private val ui get() = s.ui

    private fun queue(pending: Int, offline: Boolean = false, failed: ChunkInfo? = null) = s.onQueue(QueueStatus(pending, offline, failed))

    /** Speech in, chunks transcribed, queue drained. */
    private fun speak(vararg words: String) {
        s.onSpeech()
        s.onAutoStop()
        queue(1)
        if (words.isNotEmpty()) s.onTranscript(words.joinToString(" "))
        s.onRecordingEnded()
        queue(0)
    }

    @Test fun theMicOpensTheOrbAndListens() {
        s.onStart(prompt = false)
        assertEquals(listOf("start", "tick"), fx.log)
        assertTrue(ui.orb && ui.recording && !ui.prompt)
        assertEquals(VoiceStatus.SPEAK_NOW, ui.status)
        s.onSpeech()
        assertEquals(VoiceStatus.LISTENING, ui.status)
    }

    @Test fun narrationStaysOpenWhenDone() {
        s.onStart(prompt = false)
        speak()
        assertTrue(ui.orb && !ui.recording)
        assertEquals(VoiceStatus.DONE, ui.status)
        assertFalse("llm" in fx.log)
    }

    @Test fun theOrbWaitsForTheLastChunkToBeQueued() {
        s.onStart(prompt = false)
        s.onAutoStop()
        queue(0) // the final chunk is still on its way from the mic thread
        assertEquals(VoiceStatus.TRANSCRIBING, ui.status)
        s.onRecordingEnded()
        assertEquals(VoiceStatus.DONE, ui.status)
    }

    @Test fun tappingTheOrbStopsThenListensAgainInTheSameMode() {
        s.onStart(prompt = true)
        s.onOrbTap()
        assertTrue("send" in fx.log)
        assertFalse(ui.recording)
        s.onTranscript("make it shorter")
        s.onRecordingEnded()
        queue(0)
        s.onPromptDone(null)
        s.onOrbTap()
        assertEquals(2, fx.log.count { it == "start prompt" })
        assertTrue(ui.recording && ui.prompt)
        assertEquals("", ui.transcript)
    }

    @Test fun aPromptRunsTheLlmOnceEverythingIsTranscribed() {
        s.onStart(prompt = true)
        speak("reply saying", "yes")
        assertEquals("reply saying yes", ui.transcript)
        assertEquals(VoiceStatus.THINKING, ui.status)
        assertTrue(s.thinking)
        assertEquals(1, fx.log.count { it == "llm" })
        s.onPromptDone(null)
        assertEquals(VoiceStatus.DONE, ui.status)
        assertTrue(ui.orb && ui.prompt)
        assertEquals("", ui.transcript)
    }

    @Test fun aPromptWithNoWordsSaysSoAndRunsNothing() {
        s.onStart(prompt = true)
        speak()
        assertEquals(VoiceStatus.NO_SPEECH, ui.status)
        assertFalse("llm" in fx.log)
        s.onCloseTimeout()
        assertEquals(VoiceStatus.DONE, ui.status)
        assertTrue(ui.orb)
    }

    @Test fun aFailedPromptOffersRetryAndInsertWhatISaid() {
        s.onStart(prompt = true)
        speak("tell him", "I'm late")
        s.onPromptDone("HTTP 503")
        assertEquals(VoiceStatus.PROMPT_FAILED, ui.status)
        assertEquals("HTTP 503", ui.error)
        assertFalse(s.thinking)
        s.onPromptRetry()
        assertEquals(VoiceStatus.THINKING, ui.status)
        assertEquals(2, fx.log.count { it == "llm" })
        s.onPromptDone("HTTP 503")
        s.onInsertTranscript()
        assertTrue("insert said" in fx.log)
        assertEquals(VoiceStatus.DONE, ui.status)
    }

    @Test fun speakingAgainAfterAFailureKeepsTheOldWords() {
        s.onStart(prompt = true)
        speak("tell him I'm late")
        s.onPromptDone("timeout")
        s.onOrbTap()
        assertEquals(listOf("chips", "start prompt"), fx.log.takeLast(3).dropLast(1))
        assertTrue(ui.recording)
    }

    @Test fun dismissWhileRecordingAPromptSendsTheAudio() {
        s.onStart(prompt = true)
        s.onDismiss()
        assertTrue("send" in fx.log && "drop" !in fx.log)
        assertTrue("chips" in fx.log) // words already heard become chips; later ones are routed as chips
        assertFalse(ui.orb || ui.recording)
        s.onRecordingEnded()
        queue(0)
        assertFalse("llm" in fx.log)
    }

    @Test fun dismissWhileAPromptIsTranscribingKeepsHeardWordsAsChips() {
        s.onStart(prompt = true)
        s.onAutoStop()
        queue(2)
        s.onTranscript("make it more formal")
        s.onDismiss()
        assertTrue("chips" in fx.log)
        assertFalse(ui.orb)
    }

    @Test fun dismissAfterAPromptFailedKeepsTheWordsAsChips() {
        s.onStart(prompt = true)
        speak("tell him I'm late")
        s.onPromptDone("HTTP 500")
        s.onDismiss()
        assertTrue("chips" in fx.log)
        assertFalse(ui.orb)
    }

    @Test fun aLateAnswerAfterDismissIsIgnoredHere() {
        s.onStart(prompt = true)
        speak("hi")
        s.onDismiss()
        assertFalse(s.thinking)
        s.onPromptDone("HTTP 500") // VoiceController turns it into chips itself
        assertFalse(ui.orb)
        assertFalse("chips" in fx.log)
    }

    @Test fun keyboardHiddenSendsWhateverWasSaid() {
        s.onStart(prompt = false)
        s.onKeyboardHidden()
        assertTrue("send" in fx.log)
        assertFalse("chips" in fx.log) // narration keeps going into its field
        assertFalse(ui.orb || ui.recording)
    }

    @Test fun noSpeechStopsWithAMessageAndKeepsTheOrb() {
        s.onStart(prompt = false)
        s.onNoSpeech()
        assertTrue("drop" in fx.log && "send" !in fx.log)
        assertEquals(VoiceStatus.NO_SPEECH, ui.status)
        assertTrue("closeLater 1500" in fx.log)
        s.onCloseTimeout()
        assertEquals(VoiceStatus.DONE, ui.status)
        assertTrue(ui.orb)
    }

    @Test fun aCallOrMaxDurationStopsListening() {
        s.onStart(prompt = false)
        s.onFocusLost()
        assertTrue("send" in fx.log)
        assertEquals(VoiceStatus.TRANSCRIBING, ui.status)
        val f = Fx()
        val other = VoiceSession(f)
        other.onStart(prompt = false)
        other.onMaxDuration()
        assertTrue("send" in f.log)
    }

    @Test fun offlineShowsWaitingWithTheCount() {
        s.onStart(prompt = false)
        s.onAutoStop()
        queue(2, offline = true)
        assertEquals(VoiceStatus.WAITING, ui.status)
        assertEquals(2, ui.pending)
    }

    @Test fun aRefusedChunkOpensTheOrbAndClosesOnceResolved() {
        val refused = ChunkInfo("1-0001", "en", "", "a/1", 0, error = "HTTP 404")
        queue(1, failed = refused)
        assertTrue(ui.orb)
        assertEquals(VoiceStatus.FAILED, ui.status)
        assertEquals(refused, ui.failed)
        queue(0)
        assertFalse(ui.orb)
    }

    @Test fun aRefusedChunkComesBackWhenTheKeyboardShowsAgain() {
        queue(1, failed = ChunkInfo("1-0001", "en", "", "a/1", 0, error = "HTTP 404"))
        s.onKeyboardHidden()
        assertFalse(ui.orb)
        s.onKeyboardShown()
        assertTrue(ui.orb)
        assertEquals(VoiceStatus.FAILED, ui.status)
    }

    @Test fun aBusyMicDoesNothing() {
        fx.micWorks = false
        s.onStart(prompt = true)
        assertEquals(listOf("start prompt"), fx.log)
        assertFalse(ui.orb || ui.recording)
    }

    @Test fun aMicErrorStopsAndSays() {
        s.onStart(prompt = false)
        s.onMicError()
        assertTrue("send" in fx.log)
        assertEquals(VoiceStatus.MIC_UNAVAILABLE, ui.status)
        assertTrue("closeLater 1500" in fx.log)
        s.onRecordingEnded()
        queue(0)
        assertEquals(VoiceStatus.MIC_UNAVAILABLE, ui.status) // the message stays until its timer
        s.onCloseTimeout()
        assertEquals(VoiceStatus.DONE, ui.status)
    }

    @Test fun aLastChunkQueuedAsTheMicStopsIsWaitedFor() {
        // A short instruction is one chunk, flushed when the mic stops; its queue status arrives just after.
        s.onStart(prompt = true)
        s.onAutoStop()
        s.onRecordingEnded(lastChunkQueued = true)
        assertEquals(VoiceStatus.TRANSCRIBING, ui.status)
        queue(1)
        s.onTranscript("make it shorter")
        queue(0)
        assertEquals(VoiceStatus.THINKING, ui.status)
        assertTrue("llm" in fx.log)
    }
}
