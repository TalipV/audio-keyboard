package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test fun readsSpeachesVerboseJson() {
        val v = Json.parse(
            """{"text":" Hello","segments":[{"no_speech_prob":0.0,"avg_logprob":-0.138,"text":" Hello"}],"duration":4.2,"ok":true,"x":null}""",
        ) as Map<*, *>
        assertEquals(" Hello", v["text"])
        val seg = (v["segments"] as List<*>)[0] as Map<*, *>
        assertEquals(-0.138, seg["avg_logprob"] as Double, 1e-9)
        assertEquals(true, v["ok"])
        assertTrue(v.containsKey("x"))
        assertNull(v["x"])
    }

    @Test fun decodesEscapesArabicAndSurrogatePairs() {
        assertEquals("مرحبا\n\"😀\"", Json.parse("\"\\u0645\\u0631\\u062d\\u0628\\u0627\\n\\\"\\ud83d\\ude00\\\"\""))
        assertEquals("مرحبا", Json.parse("\"مرحبا\""))
        assertEquals(listOf(1.0, -2.5e3), Json.parse("[1, -2.5e3]"))
    }

    @Test fun malformedInputIsAnIllegalArgument() {
        for (bad in listOf("{} x", "{\"te", "[1,", "\"\\u12", "{\"a\" 1}", "")) {
            try {
                Json.parse(bad)
                throw AssertionError("parsed: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun quoteRoundTripsAnyText() {
        for (s in listOf("", "plain", "مرحبا كيف حالك؟", "say \"hi\"\\ now", "line1\nline2\ttab\r", "\u0001ctl", "😀 emoji"))
            assertEquals(s, Json.parse(Json.quote(s)))
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"))
    }
}
