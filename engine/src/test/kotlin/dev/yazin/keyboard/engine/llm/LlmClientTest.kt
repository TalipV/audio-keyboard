package dev.yazin.keyboard.engine.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yazin.keyboard.engine.Account
import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.Venice
import dev.yazin.keyboard.engine.http
import dev.yazin.keyboard.engine.voice.Json
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientTest {
    private lateinit var server: HttpServer
    private val requests = ArrayList<Pair<String, String>>() // "METHOD path" to body
    private val auth = ArrayList<String?>() // each request's Authorization header
    @Volatile private var handler: (HttpExchange) -> Unit = { reply(it, 200, "{}") }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(requests) {
                requests += "${ex.requestMethod} ${ex.requestURI.path}" to String(ex.requestBody.readBytes(), Charsets.UTF_8)
                auth += ex.requestHeaders.getFirst("Authorization")
            }
            handler(ex)
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/"

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    /** A chat completion whose message content is [content] verbatim. */
    private fun raw(content: String) = """{"choices":[{"index":0,"message":{"role":"assistant","content":${Json.quote(content)}}}]}"""

    /** What llama-server returns under the JSON schema: the content is {"text": …}. */
    private fun answer(text: String) = raw("{\"text\": ${Json.quote(text)}}")

    @Test fun sendsAJsonSchemaAndReturnsOnlyTheTextField() {
        handler = { reply(it, 200, answer("  Yes, I'll be there around 8.\n")) }
        assertEquals(LlmResult.Text("Yes, I'll be there around 8."), LlmClient(url).complete("qwen", "SYS", "USER مرحبا"))
        val (call, body) = requests.single()
        assertEquals("POST /v1/chat/completions", call)
        val json = Json.parse(body) as Map<*, *>
        assertEquals("qwen", json["model"])
        assertEquals(false, json["stream"])
        assertEquals(1024.0, json["max_tokens"])
        assertEquals(false, (json["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
        val messages = json["messages"] as List<*>
        assertEquals(mapOf("role" to "system", "content" to "SYS"), messages[0])
        assertEquals(mapOf("role" to "user", "content" to "USER مرحبا"), messages[1])
        val format = json["response_format"] as Map<*, *>
        assertEquals("json_schema", format["type"])
        val schema = (format["json_schema"] as Map<*, *>)["schema"] as Map<*, *>
        assertEquals(listOf("text"), schema["required"])
        assertEquals(mapOf("type" to "string"), (schema["properties"] as Map<*, *>)["text"])
    }

    @Test fun aFormatOverrideReturnsRawContentAndLeavesTheDefaultUnchanged() {
        val content = """ {"replies":["ok","sure","when?"]} """
        handler = { reply(it, 200, raw(content)) }
        for (client in listOf(LlmClient(url), venice())) {
            client.complete("", "s", "u")
            val before = Json.parse(requests.last().second) as Map<*, *>
            assertEquals(LlmResult.Text(content), client.complete("", "s", "u", format = ReplyDrafts.SCHEMA))
            val overridden = Json.parse(requests.last().second) as Map<*, *>
            assertEquals(Json.parse(ReplyDrafts.SCHEMA), overridden["response_format"])
            assertEquals(before - "response_format", overridden - "response_format")
            client.complete("", "s", "u")
            assertEquals(before, Json.parse(requests.last().second))
        }
    }

    @Test fun paddingAroundTheJsonIsIgnored() {
        // A server that ignores response_format may still wrap the object; only its "text" lands.
        handler = { reply(it, 200, raw("<think>ok</think>\nHere's your message: {\"text\": \"See you at 8!\"} Hope that helps!")) }
        assertEquals(LlmResult.Text("See you at 8!"), LlmClient(url).complete("", "s", "u"))
    }

    @Test fun aReplyThatIsNotTheJsonFormatFails() {
        handler = { reply(it, 200, raw("Here's your message: See you at 8!")) }
        val out = LlmClient(url).complete("", "s", "u")
        assertTrue("$out", out is LlmResult.Failed && out.message.contains("JSON"))
        handler = { reply(it, 200, raw("{\"reply\": \"See you\"}")) }
        assertTrue(LlmClient(url).complete("", "s", "u") is LlmResult.Failed)
    }

    @Test fun aBlankModelIsLeftOutSoTheServerUsesItsOwn() {
        handler = { reply(it, 200, answer("ok")) }
        LlmClient(url).complete("  ", "s", "u")
        assertFalse((Json.parse(requests.single().second) as Map<*, *>).containsKey("model"))
    }

    @Test fun serverErrorsCarryTheirMessage() {
        handler = { reply(it, 400, """{"error":{"code":400,"message":"the request exceeds the available context size","type":"exceed_context_size_error"}}""") }
        assertEquals(LlmResult.Failed("HTTP 400: the request exceeds the available context size"), LlmClient(url).complete("", "s", "u"))
        handler = { reply(it, 503, "") }
        assertEquals(LlmResult.Failed("HTTP 503"), LlmClient(url).complete("", "s", "u"))
    }

    @Test fun anEmptyOrUnreadableReplyFails() {
        handler = { reply(it, 200, answer("   ")) }
        assertTrue(LlmClient(url).complete("", "s", "u") is LlmResult.Failed)
        handler = { reply(it, 200, raw("")) }
        assertTrue(LlmClient(url).complete("", "s", "u") is LlmResult.Failed)
        handler = { reply(it, 200, "{\"choi") }
        assertTrue(LlmClient(url).complete("", "s", "u") is LlmResult.Failed)
    }

    @Test fun anUnreachableServerFails() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val out = LlmClient("http://127.0.0.1:$closedPort").complete("", "s", "u")
        assertTrue("$out", out is LlmResult.Failed && out.message.startsWith("Can't reach the AI server"))
    }

    @Test fun everyCallIsLoggedWithTheBodyItSentAndTheReplyItGot() {
        val calls = ArrayList<LlmCall>()
        handler = { reply(it, 200, answer("Hi")) }
        LlmClient(url, log = { calls += it }).complete("qwen", "SYS", "USER\n## Instruction\nsay hi", "com.app")
        handler = { reply(it, 503, "down") }
        LlmClient(url, log = { calls += it }).complete("qwen", "SYS", "USER")
        val (ok, bad) = calls
        assertEquals("""{"model":"qwen","messages":[{"role":"system","content":"SYS"},{"role":"user","content":"USER\n## Instruction\nsay hi"}],${ok.params}}""", requests[0].second)
        assertEquals(listOf(6, 6), calls.map { it.id.length })
        assertEquals(listOf("com.app", "say hi", 200, true, "Hi"), listOf(ok.app, ok.instruction(), ok.status, ok.ok, ok.result))
        assertEquals(answer("Hi"), ok.response)
        assertEquals(listOf(503, false, "down", "HTTP 503"), listOf(bad.status, bad.ok, bad.response, bad.result))
        assertTrue(ok.text().startsWith("My Board LLM call ${ok.id} · "))
    }

    @Test fun maxTokensAndTimeoutArePerCall() {
        val calls = ArrayList<LlmCall>()
        handler = { reply(it, 200, answer("ok")) }
        LlmClient(url, log = { calls += it }).complete("", "s", "u", maxTokens = 400)
        assertTrue(requests.single().second.contains("\"max_tokens\":400,"))
        assertTrue(calls.single().params.startsWith("\"max_tokens\":400,"))
        venice().complete("", "s", "u", maxTokens = 400)
        assertEquals(400.0, (Json.parse(requests.last().second) as Map<*, *>)["max_tokens"])
        handler = { Thread.sleep(600); reply(it, 200, answer("late")) }
        val out = LlmClient(url).complete("", "s", "u", readTimeoutMs = 200)
        assertTrue("$out", out is LlmResult.Failed && out.message.contains("Timeout"))
    }

    @Test fun cancelClosesTheConnection() {
        val release = CountDownLatch(1)
        val cancel = LlmCancel()
        // The request has arrived, so the client is connected and waiting for the reply.
        handler = { cancel.cancel(); release.await(5, TimeUnit.SECONDS); reply(it, 200, answer("too late")) }
        try {
            val t0 = System.nanoTime()
            val out = LlmClient(url).complete("", "s", "u", cancel = cancel)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(LlmResult.Failed("Cancelled"), out)
            assertTrue("$ms ms", ms < 1_000)
            // Cancelled before the call: nothing is sent.
            val sent = requests.size
            assertEquals(LlmResult.Failed("Cancelled"), LlmClient(url).complete("", "s", "u", cancel = LlmCancel().apply { cancel() }))
            assertEquals(sent, requests.size)
        } finally {
            release.countDown()
        }
    }

    @Test fun aCancelAfterTheReplyStillFails() {
        handler = { reply(it, 200, answer("a Profile")) }
        val cancel = LlmCancel()
        val calls = ArrayList<LlmCall>()
        val out = LlmClient(url, log = { calls += it; cancel.cancel() }).complete("", "s", "u", cancel = cancel)
        assertEquals(LlmResult.Failed("Cancelled"), out)
        assertEquals(1, requests.size)
    }

    @Test fun aCancelDuringConnectStopsTheRequestBeforeTheBody() {
        // On the JVM, disconnect() while the TCP connect is under way does nothing. A cancel with no connection to
        // disconnect stands in for it; the hand-over once connected must still stop the call.
        val cancel = LlmCancel()
        var handed = 0
        val out = runCatching {
            http(url, "POST", "/v1/chat/completions", "", "{}".toByteArray(), "application/json", 1_000, 1_000) { c ->
                if (++handed == 1) cancel.cancel() else cancel.attach(c)
            }
        }
        assertTrue("$out", out.exceptionOrNull() is java.io.IOException)
        assertEquals(2, handed)
        Thread.sleep(100)
        assertTrue(requests.isEmpty())
    }

    @Test fun aBadUrlFails() = assertEquals(LlmResult.Failed("Invalid AI server URL"), LlmClient("mybox:11434").complete("", "s", "u"))

    @Test fun noAddressAsksForOne() = assertEquals(LlmResult.Failed(Backend.NO_SERVER), LlmClient("  ").complete("", "s", "u"))

    @Test fun listsTheServersModels() {
        handler = { reply(it, 200, """{"object":"list","data":[{"id":"Qwen3.8-27B-UD-Q4_K_XL.gguf","object":"model"},{"id":"other"}]}""") }
        assertEquals(listOf("Qwen3.8-27B-UD-Q4_K_XL.gguf", "other"), LlmClient(url).models())
        assertEquals("GET /v1/models", requests.single().first)
    }

    private fun venice(log: ((LlmCall) -> Unit)? = null) = LlmClient(url, "sk-1", Backend.VENICE, log = log)

    @Test fun aTokenIsSentAsABearerHeaderOnlyWhenSet() {
        handler = { reply(it, 200, answer("ok")) }
        LlmClient(url, "sk-1").complete("", "s", "u")
        LlmClient(url, "").complete("", "s", "u")
        LlmClient(url).models()
        LlmClient(url, "sk-1").models()
        assertEquals(listOf("Bearer sk-1", null, null, "Bearer sk-1"), auth)
    }

    @Test fun veniceTurnsThinkingOffItsOwnWayAndAlwaysNamesAModel() {
        // Venice answers an unknown key such as chat_template_kwargs with a 400.
        val calls = ArrayList<LlmCall>()
        handler = { reply(it, 200, answer("Hi")) }
        assertEquals(LlmResult.Text("Hi"), venice { calls += it }.complete("  ", "SYS", "USER"))
        val json = Json.parse(requests.single().second) as Map<*, *>
        assertEquals(Backend.VENICE.llmModel, json["model"])
        assertFalse(json.containsKey("chat_template_kwargs"))
        assertEquals(mapOf("disable_thinking" to true, "include_venice_system_prompt" to false), json["venice_parameters"])
        assertEquals("json_schema", (json["response_format"] as Map<*, *>)["type"])
        assertEquals(setOf("model", "messages", "max_tokens", "stream", "venice_parameters", "response_format"), json.keys)
        assertEquals(Backend.VENICE.llmModel, calls.single().model)
        assertTrue(calls.single().params.contains("venice_parameters"))
        venice().complete("kimi-k3", "s", "u")
        assertEquals("kimi-k3", (Json.parse(requests.last().second) as Map<*, *>)["model"])
    }

    @Test fun veniceWithoutAKeyFailsBeforeAnythingIsSent() {
        assertEquals(LlmResult.Failed(Backend.NO_KEY), LlmClient(url, "", Backend.VENICE).complete("", "s", "u"))
        assertTrue(requests.isEmpty())
    }

    @Test fun veniceErrorsAreReadable() {
        for ((code, body, want) in listOf(
            // Measured on Venice: a thinking-only model refuses disable_thinking.
            Triple(400, """{"error":"GLM-5.3 is a thinking-only model; disabling thinking (reasoning_effort='none') is not supported.","request_id":"W"}""",
                "HTTP 400: GLM-5.3 is a thinking-only model; disabling thinking (reasoning_effort='none') is not supported."),
            Triple(400, """{"error":"Invalid request parameters","issues":[{"code":"unrecognized_keys","keys":["chat_template_kwargs"],"path":[],"message":"Unrecognized key(s) in object: 'chat_template_kwargs'"}]}""",
                "HTTP 400: Unrecognized key(s) in object: 'chat_template_kwargs'"),
            Triple(401, """{"error":"Authentication failed"}""", "API key not accepted"),
            Triple(402, """{"error":"Insufficient USD or Diem balance"}""", "Venice account has no credit"),
            Triple(429, """{"error":"Rate limit exceeded"}""", "HTTP 429: Rate limit exceeded"),
        )) {
            handler = { reply(it, code, body) }
            assertEquals(LlmResult.Failed(want), venice().complete("", "s", "u"))
        }
    }

    @Test fun veniceListsOnlyPrivateModelsThatCanAnswerWithoutThinking() {
        fun model(id: String, privacy: String = "private", schema: Boolean = true, reasoning: Boolean = true, efforts: String? = "[\"none\",\"high\"]") =
            """{"id":"$id","type":"text","model_spec":{"privacy":"$privacy","offline":false,"capabilities":{"supportsResponseSchema":$schema,""" +
                """"supportsReasoning":$reasoning${efforts?.let { ",\"reasoningEffortOptions\":$it" } ?: ""}}}}"""
        handler = {
            reply(it, 200, """{"data":[${listOf(
                model("deepseek-v4-1-flash"), model("z-ai-glm-5-3", efforts = null), model("zai-org-glm-4.6", efforts = "[\"low\",\"high\"]"),
                model("claude", privacy = "anonymized"), model("no-schema", schema = false), model("llama-3.3-70b", reasoning = false, efforts = null),
            ).joinToString(",")}],"object":"list"}""")
        }
        assertEquals(listOf("deepseek-v4-1-flash", "llama-3.3-70b"), venice().models())
    }

    @Test fun theVeniceKeyCheckGivesTheCreditOrWhyNot() {
        handler = { reply(it, 200, """{"data":{"accessPermitted":true,"balances":{"USD":4.98492454,"DIEM":0,"BUNDLED_CREDITS":0}}}""") }
        assertEquals(Account.Ok(4.98492454, 0.0), Venice.account(url, "sk-1"))
        assertEquals("GET /v1/api_keys/rate_limits" to "Bearer sk-1", requests.single().first to auth.single())
        handler = { reply(it, 401, """{"error":"Authentication failed"}""") }
        assertEquals(Account.Failed("API key not accepted"), Venice.account(url, "sk-2"))
        assertEquals(Account.Failed(Backend.NO_KEY), Venice.account(url, ""))
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val out = Venice.account("http://127.0.0.1:$closedPort", "sk-1")
        assertTrue("$out", out is Account.Failed && out.message.startsWith("Can't reach Venice"))
    }
}
