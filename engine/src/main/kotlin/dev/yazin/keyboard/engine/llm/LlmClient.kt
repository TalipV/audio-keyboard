package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.failure
import dev.yazin.keyboard.engine.http
import dev.yazin.keyboard.engine.voice.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException

sealed interface LlmResult {
    data class Text(val text: String) : LlmResult
    /** Shown to the user with Retry / Insert what I said (AI orb spec §6); there is no background retry. */
    data class Failed(val message: String) : LlmResult
}

/** The keyboard's second network call site (spec §4): an OpenAI-compatible LLM server, the user's llama-server or Venice. */
class LlmClient(
    baseUrl: String,
    private val token: String = "",
    private val backend: Backend = Backend.SERVER,
    private val connectTimeoutMs: Int = if (backend == Backend.VENICE) 10_000 else 3_000,
    private val readTimeoutMs: Int = 30_000,
    /** Told about every [complete] call whatever its outcome: the one place ✦'s requests and replies are recorded. */
    private val log: ((LlmCall) -> Unit)? = null,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val venice = backend == Backend.VENICE

    /**
     * One non-streaming chat completion with thinking off (≈0.25 s on llama-server vs 0.65 s with it). A blank [model] is the
     * backend's default, or left out for the server's own. The reply is forced into {"text": "…"} by a JSON schema
     * (llama-server turns it into a grammar), and only that text is returned: the model can't pad it with "Here's your
     * message:". [app] only labels the [log] entry. [cancel] can end the call from another thread: it then fails with
     * [CANCELLED]. An explicit [format] overrides response_format and returns the message content as raw JSON.
     */
    fun complete(
        model: String, system: String, user: String, app: String = "",
        maxTokens: Int = 1024, readTimeoutMs: Int = this.readTimeoutMs, cancel: LlmCancel? = null,
        format: String? = null,
    ): LlmResult {
        val t0 = System.nanoTime()
        val wire = Wire()
        val named = model.trim().ifBlank { backend.llmModel }
        val params = params(maxTokens, format)
        val result = if (venice && token.isBlank()) LlmResult.Failed(Backend.NO_KEY)
            else if (base.isEmpty()) LlmResult.Failed(Backend.NO_SERVER)
            else exchange(named, system, user, params, readTimeoutMs, cancel, wire, format != null).let { if (cancel?.cancelled == true) LlmResult.Failed(CANCELLED) else it }
        log?.invoke(
            LlmCall(
                LlmCall.newId(), System.currentTimeMillis(), app, base, named, system, user, params, wire.status, wire.response,
                result is LlmResult.Text, (result as? LlmResult.Text)?.text ?: (result as LlmResult.Failed).message, (System.nanoTime() - t0) / 1_000_000,
            ),
        )
        // A cancel that lands while the call is logged still wins: a cancelled call never returns text.
        return if (cancel?.cancelled == true) LlmResult.Failed(CANCELLED) else result
    }

    /** What came back over the wire, for the [log]. */
    private class Wire(var status: Int = 0, var response: String = "")

    private fun exchange(model: String, system: String, user: String, params: String, readTimeoutMs: Int, cancel: LlmCancel?, wire: Wire, rawContent: Boolean): LlmResult = try {
        val body = buildString {
            append('{')
            if (model.isNotBlank()) append("\"model\":").append(Json.quote(model)).append(',')
            append("\"messages\":[{\"role\":\"system\",\"content\":").append(Json.quote(system))
            append("},{\"role\":\"user\",\"content\":").append(Json.quote(user)).append("}],")
            append(params).append('}')
        }
        val (code, reply) = request("POST", "/v1/chat/completions", body.toByteArray(Charsets.UTF_8), readTimeoutMs, cancel?.let { it::attach })
        wire.status = code
        wire.response = reply
        if (code !in 200..299) {
            LlmResult.Failed(failure(code, reply))
        } else {
            val content = (((Json.parse(reply) as? Map<*, *>)?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>)
                ?.let { it["message"] as? Map<*, *> }?.get("content") as? String
            when {
                content.isNullOrBlank() -> LlmResult.Failed("Empty reply from the AI server")
                rawContent -> LlmResult.Text(content)
                else -> textOf(content)?.trim()?.takeIf { it.isNotEmpty() }?.let { LlmResult.Text(it) }
                    ?: LlmResult.Failed("The AI server didn't answer in the JSON format (does it support response_format?)")
            }
        }
    } catch (_: MalformedURLException) {
        LlmResult.Failed("Invalid AI server URL")
    } catch (e: IOException) {
        LlmResult.Failed("Can't reach the AI server (${e.javaClass.simpleName})")
    } catch (_: IllegalArgumentException) {
        LlmResult.Failed("Unreadable reply from the AI server")
    } catch (e: RuntimeException) {
        LlmResult.Failed("Invalid AI server URL (${e.javaClass.simpleName})")
    }

    /** The "text" of the first {…} in [content]; anything a server put around the object is ignored. Null if there is none. */
    private fun textOf(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val obj = try { Json.parse(content.substring(start, end + 1)) as? Map<*, *> } catch (_: IllegalArgumentException) { null }
        return obj?.get("text") as? String
    }

    /** The server's model ids, in its order. On Venice only those ✦ can use: private, held to a schema, able not to think. */
    fun models(): List<String> {
        val (code, body) = request("GET", if (venice) "/v1/models?type=text" else "/v1/models", null, 5_000)
        if (code != 200) throw IOException("HTTP $code")
        val data = try { (Json.parse(body) as? Map<*, *>)?.get("data") as? List<*> } catch (e: IllegalArgumentException) { throw IOException(e) }
        return data.orEmpty().mapNotNull { m -> (m as? Map<*, *>)?.takeIf { !venice || usable(it) }?.get("id") as? String }
    }

    /** Thinking-only models refuse `disable_thinking` with a 400; Venice lists effort "none" for those that can stop. */
    private fun usable(model: Map<*, *>): Boolean {
        val spec = model["model_spec"] as? Map<*, *> ?: return false
        val caps = spec["capabilities"] as? Map<*, *> ?: return false
        val thinksOnly = caps["supportsReasoning"] == true && "none" !in (caps["reasoningEffortOptions"] as? List<*>).orEmpty()
        return spec["privacy"] == "private" && spec["offline"] != true && caps["supportsResponseSchema"] == true && !thinksOnly
    }

    private fun request(method: String, path: String, body: ByteArray?, readTimeout: Int, onConnection: ((HttpURLConnection) -> Unit)? = null) =
        http(base, method, path, token, body, "application/json", connectTimeoutMs, readTimeout, onConnection)

    private fun params(maxTokens: Int, format: String?) =
        "\"max_tokens\":$maxTokens,\"stream\":false," + (if (venice) VENICE_PARAMS else PARAMS).let {
            if (format == null) it else it.replace(RESPONSE_FORMAT, format)
        }

    companion object {
        /** What a call ended by [LlmCancel.cancel] fails with. */
        const val CANCELLED = "Cancelled"

        /** OpenAI-style structured output; llama-server compiles the schema to a grammar, so nothing else can be generated. */
        private const val RESPONSE_FORMAT = """{"type":"json_schema","json_schema":{"name":"field_text","strict":true,"schema":""" +
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}}"""

        /** The request body after "model", "messages", "max_tokens" and "stream". */
        private const val PARAMS = "\"chat_template_kwargs\":{\"enable_thinking\":false},\"response_format\":$RESPONSE_FORMAT"

        /** Venice turns thinking off its own way and refuses `chat_template_kwargs`; its own system prompt would add to ours. */
        private const val VENICE_PARAMS =
            "\"venice_parameters\":{\"disable_thinking\":true,\"include_venice_system_prompt\":false},\"response_format\":$RESPONSE_FORMAT"
    }
}

/**
 * Ends one [LlmClient.complete] call from another thread by disconnecting its connection, now or as soon as it is open.
 * Single use: once cancelled, every call given it fails.
 */
class LlmCancel {
    @Volatile var cancelled = false
        private set
    @Volatile private var connection: HttpURLConnection? = null

    /** Sets [cancelled] at once, so log and result guards see the cancel before the connection closes. */
    fun softCancel() { cancelled = true }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * Called with the same connection when it is opened and again once it is connected. Both fields are volatile and
     * each side writes its own before reading the other's, so one of them disconnects.
     */
    internal fun attach(c: HttpURLConnection) {
        connection = c
        if (cancelled) throw IOException(LlmClient.CANCELLED)
    }
}
