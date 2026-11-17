package dev.yazin.keyboard.engine.voice

import dev.yazin.keyboard.engine.Backend
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spec §10.3: the real server with clips made by macOS `say`. Run with -Pvoice (or -Pvoice=http://host:port/), or
 * -Pvoice=venice with the key in VENICE_API_KEY.
 */
class LiveServerTest {
    @Test fun transcribesBothLanguages() {
        val url = System.getProperty("voiceUrl").orEmpty()
        assumeTrue("run with -Pvoice", url.isNotEmpty())
        val clips = File(System.getProperty("voiceClips"))
        val client = if (url == "venice") WhisperClient(Backend.VENICE.voiceUrl, System.getenv("VENICE_API_KEY").orEmpty(), Backend.VENICE)
            else WhisperClient(url)
        assertTrue(client.health())
        val model = (client.model("") as Outcome.Text).text
        client.preload(model)
        for ((lang, expect) in listOf("en" to "testing", "ar" to "مرحبا")) {
            val t0 = System.nanoTime()
            val out = client.transcribe(File(clips, "test-$lang.wav").readBytes(), TranscribeRequest(model, lang, "", null))
            println("$lang: $out in ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertTrue("$out", out is Outcome.Text && out.text.lowercase().contains(expect))
        }
    }
}
