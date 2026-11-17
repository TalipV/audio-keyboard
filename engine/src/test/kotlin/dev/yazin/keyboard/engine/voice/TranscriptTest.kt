package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    private fun reply(vararg segs: Triple<String, Double, Double>) =
        """{"text":"${segs.joinToString("") { it.first }}","segments":[${segs.joinToString(",") { (t, nsp, lp) ->
            """{"text":"$t","no_speech_prob":$nsp,"avg_logprob":$lp}"""
        }}]}"""

    @Test fun realSpeechIsKept() =
        assertEquals("Hello Yazin, can you send me the report?", Transcript.clean(reply(Triple(" Hello Yazin, can you send me the report?", 0.0, -0.138))))

    @Test fun silenceHallucinationIsDropped() = assertEquals("", Transcript.clean(reply(Triple(" you", 0.0, -0.889))))

    @Test fun arabicNoiseHallucinationIsDropped() = assertEquals("", Transcript.clean(reply(Triple(" شكراً للمشاهدة", 0.0, -0.557))))

    @Test fun indonesianNoiseHallucinationIsDropped() = assertEquals("", Transcript.clean(reply(Triple(" Salamat menikmati.", 0.0, -0.6))))

    @Test fun listedHallucinationsAreDroppedWhateverTheirPunctuation() {
        for (t in listOf(
            "I'm not sure what I'm doing here.", "Subtitles by the Amara.org community", "Untertitel im Auftrag des ZDF für funk, 2017",
            "شُكْرًا لِلْمُشَاهَدَة", "ご視聴ありがとうございました。", "İzlediğiniz için teşekkürler!", "❤️ par SousTitreur.com", "[Music]",
        )) assertEquals(t, "", Transcript.clean(reply(Triple(" $t", 0.0, -0.6))))
    }

    @Test fun unsureEverydayPhrasesAreKept() {
        for (t in listOf("I don't know.", "Thanks!", "Thank you very much.", "Bye.", "شكرا", "Gracias.")) assertEquals(t, t, Transcript.clean(reply(Triple(" $t", 0.0, -0.6))))
    }

    @Test fun aConfidentThankYouIsKept() = assertEquals("Thank you.", Transcript.clean(reply(Triple(" Thank you.", 0.0, -0.12))))

    @Test fun noSpeechSegmentsAreDropped() =
        assertEquals("Hello", Transcript.clean(reply(Triple(" Hello", 0.01, -0.2), Triple(" um the", 0.9, -1.3))))

    @Test fun plainJsonFormatWorksToo() = assertEquals("hi", Transcript.clean("""{"text":" hi "}"""))

    @Test fun aReplyWithoutTextIsNotSilence() {
        assertEquals(null, Transcript.clean("{}"))
        assertEquals(null, Transcript.clean("[]"))
    }
}
