package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.voice.Json

/** Pure request and response handling for replies drafted before the owner asks ✦. */
object ReplyDrafts {
    const val MAX_TOKENS = 300
    const val READ_TIMEOUT_MS = 30_000

    /** OpenAI-style structured output, passed as LlmClient.complete's format override. */
    const val SCHEMA = """{"type":"json_schema","json_schema":{"name":"field_replies","strict":true,"schema":""" +
        """{"type":"object","properties":{"replies":{"type":"array","items":{"type":"string"},"minItems":3,"maxItems":3}},"required":["replies"],"additionalProperties":false}}}"""

    private const val INSTRUCTION = "Write three replies I might send to the newest message: one short reaction, one fuller reply, one that asks a follow-up. Each in my voice, as me."

    data class Request(val system: String, val user: String)

    fun shouldDraft(historyCount: Int, fieldEmpty: Boolean, newestIsTheirs: Boolean, drafted: Boolean): Boolean =
        historyCount > 0 && fieldEmpty && newestIsTheirs && !drafted

    fun request(ctx: PromptContext) = Request(Prompt.system(ctx), Prompt.user(ctx, INSTRUCTION))

    /** Fewer than three usable replies is fine; none — or all identical (spec §4) — means silence. */
    fun parse(rawJson: String): List<String> {
        val obj = try { Json.parse(rawJson) as? Map<*, *> } catch (_: IllegalArgumentException) { null }
        val replies = obj?.get("replies") as? List<*> ?: return emptyList()
        val usable = replies.filterIsInstance<String>().map(Prompt::clean)
            .filter { it.isNotBlank() && it.length <= 300 }
        if (usable.size > 1 && usable.distinct().size == 1) return emptyList()
        return usable.distinct()
    }
}
