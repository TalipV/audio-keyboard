package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.voice.Json
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * One chat completion as it went over the wire, for debugging replies. The request body is
 * `{"model":…,"messages":[system,user],` + [params] + `}`. [status] is the HTTP code, 0 when there was none (timeout,
 * unreachable); [response] is the raw reply body; [result] is the parsed text or the error shown to the user.
 */
data class LlmCall(
    val id: String, val time: Long, val app: String, val url: String, val model: String,
    val system: String, val user: String, val params: String,
    val status: Int, val response: String, val ok: Boolean, val result: String, val ms: Long,
) {
    companion object {
        /** Six base36 characters (36^6 values), short enough to say out loud: "look at call k3x9a2". */
        fun newId(): String = Random.nextLong(2_176_782_336L).toString(36).padStart(6, '0')
    }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

    /** The text the viewer shows and Copy / Share send; its first line names the call. */
    fun text(): String = buildString {
        append("My Board LLM call $id · ${stamp()} · ${model.ifBlank { "auto" }}\n")
        append("app: $app\nurl: $url\n")
        append("${if (status == 0) "no HTTP reply" else "HTTP $status"} · $ms ms · ${if (ok) "ok" else "failed"}\n")
        append("params: $params\n\n--- system ---\n$system\n\n--- user ---\n$user\n\n--- response (raw) ---\n$response\n\n")
        append("--- ${if (ok) "result" else "error"} ---\n$result\n")
    }

    /** The first line of the spoken instruction, for the list. */
    fun instruction(): String = user.substringAfterLast("## Instruction\n", "").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    fun json(): String = "{\"id\":${Json.quote(id)},\"time\":$time,\"app\":${Json.quote(app)},\"url\":${Json.quote(url)}," +
        "\"model\":${Json.quote(model)},\"system\":${Json.quote(system)},\"user\":${Json.quote(user)},\"params\":${Json.quote(params)}," +
        "\"status\":$status,\"response\":${Json.quote(response)},\"ok\":$ok,\"result\":${Json.quote(result)},\"ms\":$ms}"
}

/** The last [max] calls (older ones drop off), kept as JSONL: one call per line, pasteable into an agent session. */
class LlmLog(private val max: Int = 50) {
    private val calls = ArrayDeque<LlmCall>() // oldest first

    @Volatile var dirty = false
        private set

    @Synchronized
    fun add(call: LlmCall) {
        calls.addLast(call)
        while (calls.size > max) calls.removeFirst()
        dirty = true
    }

    /** Newest first. */
    @Synchronized fun all(): List<LlmCall> = calls.reversed()

    @Synchronized
    fun clear() {
        calls.clear()
        dirty = true
    }

    @Synchronized
    fun write(w: Writer) {
        calls.forEach { w.write(it.json()); w.write("\n") }
        dirty = false
    }

    /** Lines it can't read are skipped. */
    @Synchronized
    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val m = try { Json.parse(line) as? Map<*, *> } catch (_: IllegalArgumentException) { null } ?: continue
            fun s(k: String) = m[k] as? String ?: ""
            fun n(k: String) = (m[k] as? Double)?.toLong() ?: 0L
            calls.addLast(LlmCall(s("id"), n("time"), s("app"), s("url"), s("model"), s("system"), s("user"), s("params"),
                n("status").toInt(), s("response"), m["ok"] == true, s("result"), n("ms")))
        }
        while (calls.size > max) calls.removeFirst()
    }
}
