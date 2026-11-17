package dev.yazin.keyboard.engine

import dev.yazin.keyboard.engine.voice.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** Where dictation and ✦ go, with the addresses and models used unless settings name others. */
enum class Backend(val voiceUrl: String, val llmUrl: String, val voiceModel: String, val llmModel: String) {
    /**
     * Your own OpenAI-compatible servers (e.g. Speaches for dictation, llama-server for ✦). They have no default address:
     * Settings → AI service names them. A blank model is the server's own.
     */
    SERVER("", "", "", ""),

    /**
     * Venice AI, one key for both. It rejects JSON keys it doesn't know (llama-server's `chat_template_kwargs` is a 400),
     * transcribes only to plain `json` (no language, no segments) and ignores `hotwords`.
     */
    VENICE("https://api.venice.ai/api", "https://api.venice.ai/api", "openai/whisper-large-v3", "deepseek-v4-1-flash");

    companion object {
        /** Shown instead of sending a request Venice would refuse. */
        const val NO_KEY = "Add your Venice API key in Settings → AI service"
        /** Shown instead of a request to a server with no address yet. */
        const val NO_SERVER = "Add your server's address in Settings → AI service"
    }
}

/** What Venice says about a key: one GET, nothing billed. */
sealed interface Account {
    /** The key works; the balances left, in USD and in DIEM (Venice's daily credit). */
    data class Ok(val usd: Double, val diem: Double) : Account
    data class Failed(val message: String) : Account
}

object Venice {
    fun account(baseUrl: String, token: String, connectTimeoutMs: Int = 10_000): Account {
        if (token.isBlank()) return Account.Failed(Backend.NO_KEY)
        return try {
            val (code, body) = http(baseUrl, "GET", "/v1/api_keys/rate_limits", token, null, null, connectTimeoutMs, 10_000)
            if (code != 200) return Account.Failed(failure(code, body))
            val balances = ((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("balances") as? Map<*, *>
            Account.Ok(balances?.get("USD") as? Double ?: 0.0, balances?.get("DIEM") as? Double ?: 0.0)
        } catch (e: IOException) {
            Account.Failed("Can't reach Venice (${e.javaClass.simpleName})")
        } catch (_: IllegalArgumentException) {
            Account.Failed("Unreadable reply from Venice")
        }
    }
}

/**
 * One request to [baseUrl] + [path]: the status and the body, the error body for 4xx/5xx. A non-blank [token] goes as a
 * Bearer header. [onConnection] gets the connection when it is opened and again once it is connected, before the body
 * is sent, so another thread can disconnect it; an IOException it throws ends the call. It is called twice because on
 * the JVM a disconnect() before the TCP connect has finished does nothing.
 */
internal fun http(
    baseUrl: String, method: String, path: String, token: String, body: ByteArray?, contentType: String?,
    connectTimeoutMs: Int, readTimeoutMs: Int, onConnection: ((HttpURLConnection) -> Unit)? = null,
): Pair<Int, String> {
    val c = URL(baseUrl.trim().trimEnd('/') + path).openConnection() as? HttpURLConnection ?: throw MalformedURLException("not http(s)")
    try {
        onConnection?.invoke(c)
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
        }
        c.connect()
        onConnection?.invoke(c)
        if (body != null) c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        return code to text
    } finally {
        c.disconnect()
    }
}

/**
 * Why a request failed, for the user. A refused key or an empty account is said plainly; otherwise the code and the
 * server's message: llama-server's {"error":{"message":…}}, Speaches' {"detail":…}, Venice's {"error":…} and its
 * {"error":"Invalid request parameters","issues":[{"message":…}]}, where the issues say what was wrong.
 */
internal fun failure(code: Int, body: String): String {
    val json = try { Json.parse(body) as? Map<*, *> } catch (_: IllegalArgumentException) { null }
    val error = json?.get("error")
    val issues = (json?.get("issues") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("message") as? String }.orEmpty()
    val message = (error as? Map<*, *>)?.get("message") as? String
        ?: json?.get("detail") as? String
        ?: issues.takeIf { it.isNotEmpty() }?.joinToString("; ")
        ?: error as? String
    return when {
        code == 401 -> "API key not accepted"
        code == 402 && message == "Authentication required" -> "No API key" // Venice's answer to a request without one
        code == 402 -> "Venice account has no credit"
        else -> "HTTP $code" + (message?.let { ": $it" } ?: "")
    }
}
