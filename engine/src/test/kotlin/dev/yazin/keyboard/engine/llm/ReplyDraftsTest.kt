package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.chat.ChatContext
import dev.yazin.keyboard.engine.voice.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDraftsTest {
    private val chat = ChatContext(
        "Sam", "My cousin", "Closer", listOf("New job"),
        listOf("free?" to "yalla"), listOf("Sam: hi", "Me: hey"),
    )
    private val ctx = PromptContext(
        "com.whatsapp", "Message", "Sam\ncoming?\nyes", "around 8", false,
        samples = listOf("yalla\nsoon", "x".repeat(400)),
        edits = listOf("Certainly!" to "sure"), mine = setOf("yes"), chat = chat,
    )

    @Test fun triggerRequiresHistoryEmptyFieldIncomingMessageAndAnUnseenScreen() {
        assertTrue(ReplyDrafts.shouldDraft(1, true, true, false))
        assertFalse(ReplyDrafts.shouldDraft(0, true, true, false))
        assertFalse(ReplyDrafts.shouldDraft(1, false, true, false))
        assertFalse(ReplyDrafts.shouldDraft(1, true, false, false))
        assertFalse(ReplyDrafts.shouldDraft(1, true, true, true))
    }

    @Test fun allIdenticalRepliesAreSilence() {
        assertEquals(emptyList<String>(), ReplyDrafts.parse("""{"replies":["ok","ok","ok"]}"""))
        assertEquals(listOf("ok", "okay"), ReplyDrafts.parse("""{"replies":["ok","okay","ok"]}"""))
    }

    @Test fun requestReusesTheExactOrbPrefixAndSectionOrder() {
        for (context in listOf(ctx, ctx.copy(chat = null), ctx.copy(selection = true))) {
            val request = ReplyDrafts.request(context)
            val orb = Prompt.user(context, "reply warmly")
            assertEquals(Prompt.system(context), request.system)
            assertEquals(orb.substringBefore("## Instruction\n"), request.user.substringBefore("## Instruction\n"))
            assertEquals(
                "Write three replies I might send to the newest message: one short reaction, one fuller reply, one that asks a follow-up. Each in my voice, as me.",
                request.user.substringAfter("## Instruction\n"),
            )
        }
    }

    @Test fun blankSectionsAreStillOmitted() {
        val context = PromptContext("", "", "", "", false)
        assertTrue(ReplyDrafts.request(context).user.startsWith("## Instruction\n"))
        assertEquals(Prompt.SYSTEM, ReplyDrafts.request(context).system)
    }

    @Test fun goalRuleIsPresentOnlyWithANonBlankGoal() {
        assertTrue(ReplyDrafts.request(ctx).system.contains(Prompt.GOAL_RULE))
        for (context in listOf(ctx.copy(chat = null), ctx.copy(chat = chat.copy(goal = "")), ctx.copy(chat = chat.copy(goal = " \n")))) {
            assertFalse(ReplyDrafts.request(context).system.contains(Prompt.GOAL_RULE))
        }
    }

    @Test fun schemaRequiresExactlyThreeStringsAndExposesCallLimits() {
        val format = Json.parse(ReplyDrafts.SCHEMA) as Map<*, *>
        assertEquals("json_schema", format["type"])
        val definition = format["json_schema"] as Map<*, *>
        assertEquals("field_replies", definition["name"])
        assertEquals(true, definition["strict"])
        val schema = definition["schema"] as Map<*, *>
        assertEquals("object", schema["type"])
        assertEquals(listOf("replies"), schema["required"])
        assertEquals(false, schema["additionalProperties"])
        val replies = (schema["properties"] as Map<*, *>)["replies"] as Map<*, *>
        assertEquals("array", replies["type"])
        assertEquals(mapOf("type" to "string"), replies["items"])
        assertEquals(3.0, replies["minItems"])
        assertEquals(3.0, replies["maxItems"])
        assertEquals(300, ReplyDrafts.MAX_TOKENS)
        assertEquals(30_000, ReplyDrafts.READ_TIMEOUT_MS)
    }

    @Test fun threeRepliesAreCleanedInOrder() {
        assertEquals(listOf("haha", "sounds good", "when?"), ReplyDrafts.parse("""{"replies":[" haha ","\"sounds good\"","“when?”"]}"""))
    }

    @Test fun fewerThanThreeIsFine() {
        assertEquals(listOf("ok"), ReplyDrafts.parse("""{"replies":["ok"]}"""))
        assertEquals(emptyList<String>(), ReplyDrafts.parse("""{"replies":[]}"""))
    }

    @Test fun blanksDuplicatesAndOverlongRepliesAreDropped() {
        val boundary = "x".repeat(300)
        val raw = """{"replies":[" ","\"\""," ok ","ok","“ok”",${Json.quote(boundary + "x")},${Json.quote(boundary)},"last"]}"""
        assertEquals(listOf("ok", boundary, "last"), ReplyDrafts.parse(raw))
        assertEquals(emptyList<String>(), ReplyDrafts.parse("""{"replies":[" ","\n"]}"""))
    }

    @Test fun malformedJsonReturnsSilence() {
        for (raw in listOf("", "not json", "{\"replies\":[\"ok\"", "{", "{}", "null", "[]", """{"replies":"ok"}""")) {
            assertEquals(raw, emptyList<String>(), ReplyDrafts.parse(raw))
        }
    }
}
