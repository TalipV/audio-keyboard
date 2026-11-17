package dev.yazin.keyboard.engine.llm

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmLogTest {
    private fun call(id: String, user: String = "u") = LlmCall(id, 1_760_000_000_000, "pkg", "http://x", "m", "sys \"q\"", user, "p", 200, "{\"a\":\n1}", true, "ok ✦ مرحبا", 12)

    @Test fun keepsTheNewestAndListsThemNewestFirst() {
        val log = LlmLog(max = 3)
        listOf("a", "b", "c", "d").forEach { log.add(call(it)) }
        assertEquals(listOf("d", "c", "b"), log.all().map { it.id })
        assertTrue(log.dirty)
    }

    @Test fun roundTripsThroughOneLinePerCallAndSkipsGarbage() {
        val log = LlmLog()
        log.add(call("a", "line1\nline2\t\"x\""))
        log.add(call("b"))
        val out = StringWriter().also(log::write).toString()
        assertEquals(2, out.lines().count { it.isNotEmpty() })
        assertFalse(log.dirty)
        val back = LlmLog().apply { read((out + "not json\n").lineSequence().filter { it.isNotEmpty() }) }
        assertEquals(log.all(), back.all())
    }

    @Test fun textStartsWithTheIdHeaderAndShowsTheInstructionLine() {
        val c = call("k3x9a2", "## App\nx\n\n## Instruction\nsay hi\nmore")
        assertTrue(c.text().startsWith("My Board LLM call k3x9a2 · "))
        assertTrue(c.text().lines().first().endsWith(" · m"))
        assertEquals("say hi", c.instruction())
    }
}
