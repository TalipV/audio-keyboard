package dev.yazin.keyboard.engine.voice

import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.failure
import dev.yazin.keyboard.engine.http
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.MalformedURLException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** What one transcription attempt produced; decides what is retried (spec §7.3). */
sealed interface Outcome {
    data class Text(val text: String) : Outcome
    /** Connection failures, timeouts, cut-off replies, 408/429/5xx: try again later. */
    data class Retry(val reason: String) : Outcome
    /** Any other 4xx, or a bad URL: needs the user (Retry / Discard). The audio is kept. */
    data class Failed(val message: String) : Outcome
}

/** [language]: the codes the speaker may use, likeliest first, comma-separated ("ar" or "ar,en"). */
data class TranscribeRequest(val model: String, val language: String, val prompt: String, val hotwords: String?)

/**
 * Dictation's network call site (spec §4): an OpenAI-compatible transcription server, the user's Speaches or Venice.
 * [english] tells whether a word is in the keyboard's English dictionary.
 */
class WhisperClient(
    baseUrl: String,
    private val token: String = "",
    private val backend: Backend = Backend.SERVER,
    private val english: (String) -> Boolean = { false },
    private val connectTimeoutMs: Int = if (backend == Backend.VENICE) 10_000 else 3_000,
    private val hedgeMs: Long = 5_000,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val venice = backend == Backend.VENICE
    private val modelsPath = if (venice) "/v1/models?type=asr" else "/v1/models"

    fun transcribe(wav: ByteArray, given: TranscribeRequest): Outcome = attempt {
        if (venice && token.isBlank()) return@attempt Outcome.Failed(Backend.NO_KEY)
        // One language is named. Several: Whisper detects, and a wrong guess is sent again naming the likeliest.
        val languages = given.language.split(',')
        // Context pulls speech into its own language: after English text Arabic came back translated, echoed or romanized,
        // even with `ar` named; after Arabic text mostly English speech became a stock Arabic phrase (docs/voice.md). So
        // `prompt` carries context only when one language can be spoken, and never English context for Arabic.
        val req = if (languages.size > 1 || "ar" in languages && latin(given.prompt)) given.copy(prompt = "") else given
        var (code, body) = send(wav, req, languages.singleOrNull())
        val again = if (code in 200..299) readable { askAgain(body, languages) } else null
        if (again != null) {
            val reply = send(wav, req.copy(prompt = ""), again)
            // Without scores, Venice's second reading is kept only when it is Arabic and not a stock phrase.
            if (!venice || reply.first in 200..299 && readable { arabicReply(reply.second) }) {
                code = reply.first
                body = reply.second
            }
        } else if (code in 200..299 && languages.size > 1 && !venice) {
            // Gulf Arabic heard as English comes back translated, so Whisper's guess is checked against a reading in the
            // other language and the surer one is kept: the right language scored higher on every measured clip.
            // ponytail: two requests per chunk (~0.3 s each); send both at once if the latency shows.
            val detected = readable { detected(body) }
            val (c, b) = send(wav, req, languages.first { it != detected })
            if (c in 200..299 && readable { Transcript.confidence(b) > Transcript.confidence(body) }) body = b
        }
        val text = if (code in 200..299) readable { Transcript.clean(body) } else null
        when {
            code in 200..299 -> text?.let { Outcome.Text(it) } ?: Outcome.Failed("Unexpected reply from the server")
            code == 408 || code == 429 || code >= 500 -> Outcome.Retry("HTTP $code")
            else -> Outcome.Failed(failure(code, body))
        }
    }

    private fun send(wav: ByteArray, req: TranscribeRequest, language: String?): Pair<Int, String> {
        val boundary = "kbd" + System.nanoTime()
        val fields = buildMap {
            put("model", req.model)
            if (language != null) put("language", language)
            put("response_format", if (venice) "json" else "verbose_json")
            put("temperature", "0")
            if (req.prompt.isNotBlank()) put("prompt", req.prompt)
            // A named language gets only the hotwords written in its script: Latin ones romanize short Arabic ("Abshar").
            val hotwords = if (venice) null else if (language == null) req.hotwords
                else req.hotwords?.split(',')?.map { it.trim() }?.filter { arabic(it) == (language == "ar") }?.joinToString(", ")
            if (!hotwords.isNullOrBlank()) put("hotwords", hotwords)
        }
        // Spec §7.3: read timeout 10 s plus twice the clip length; Venice's slowest replies took 16 s.
        val timeout = (if (venice) 30_000 else 10_000) + 2 * Wav.durationMs(wav).toInt()
        val body = multipart(boundary, wav, fields)
        val call = { request("POST", "/v1/audio/transcriptions", body, "multipart/form-data; boundary=$boundary", timeout) }
        return if (venice) hedged(call) else call()
    }

    /**
     * Venice answers most clips in about 2 s, but about one request in ten takes 10–16 s (docs/voice.md), holding up every
     * text after it. A request still open after [hedgeMs] is sent once more and the first good reply is used.
     */
    private fun hedged(call: () -> Pair<Int, String>): Pair<Int, String> {
        val replies = LinkedBlockingQueue<Result<Pair<Int, String>>>()
        fun start() = Thread({ replies.put(runCatching(call)) }, "voice-hedge").apply { isDaemon = true }.start()
        start()
        var open = 1
        var reply = replies.poll(hedgeMs, TimeUnit.MILLISECONDS) ?: run { start(); open++; replies.take() }
        open--
        while (open > 0 && reply.getOrNull()?.first !in 200..299) {
            reply = replies.take()
            open--
        }
        return reply.getOrThrow()
    }

    /**
     * The language to name in a second try, or null when Whisper's guess holds. Short English is placed right, but short
     * Gulf Arabic can come back as Indonesian, Finnish or Persian, so a language outside [languages] is asked as Arabic.
     * Arabic written mostly in Latin letters (romanized by Latin hotwords, or English from an English prompt) is asked
     * again too: one Arabic word left in ("fhaai aخر muhawola") doesn't make it Arabic.
     * Venice names no language, so there the words decide: Latin text that may be Arabic is asked again unless most of
     * it is English ("Viinu" and "Abshar" are asked, "Bye-bye" isn't).
     */
    private fun askAgain(body: String, languages: List<String>): String? {
        val text = text(body)
        val wrong = if (venice) "ar" in languages && latin(text) && (languages.size == 1 || !englishWords(text))
            else detected(body).let { languages.size > 1 && it !in languages || it == "ar" && latin(text) }
        return if (wrong) languages.firstOrNull { it == "ar" } ?: languages.first() else null
    }

    /** The language Whisper says it heard (or was told). */
    private fun detected(body: String) = (Json.parse(body) as? Map<*, *>)?.get("language") as? String

    private fun text(body: String) = (Json.parse(body) as? Map<*, *>)?.get("text") as? String ?: ""

    /** At least half of [text]'s words are in the English dictionary, as written or lowercased. */
    private fun englishWords(text: String): Boolean {
        val words = WORD.findAll(text).map { it.value }.toList()
        return words.count { english(it) || english(it.lowercase()) } * 2 >= words.size
    }

    /** A reply in Arabic script that isn't one of Whisper's stock phrases (English read as Arabic comes back as those). */
    private fun arabicReply(body: String) = text(body).let { arabic(it) && !latin(it) && !Transcript.stock(it) }

    private fun arabic(s: String) = s.any { arabic(it) }

    /** More Latin letters than Arabic ones. */
    private fun latin(s: String) = s.count { it.isLetter() && !arabic(it) } > s.count { arabic(it) }

    private fun arabic(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.ARABIC

    /**
     * The model to use, as [Outcome.Text] (the id): [configured], or the server's first speech model (spec §7.2).
     * Otherwise why not: an outage is [Outcome.Retry]; a settings mistake (bad URL, wrong path, no model) is [Outcome.Failed].
     */
    fun model(configured: String): Outcome {
        val named = configured.trim().ifBlank { backend.voiceModel }
        if (named.isNotEmpty()) return Outcome.Text(named)
        return attempt {
            val (code, body) = request("GET", modelsPath, null, null, 5_000)
            when {
                code == 200 -> readable { speechModels(body) }.firstOrNull()?.let { Outcome.Text(it) } ?: Outcome.Failed("No speech model on the server")
                code == 408 || code == 429 || code >= 500 -> Outcome.Retry("HTTP $code")
                else -> Outcome.Failed(failure(code, body))
            }
        }
    }

    /** Speaches' /health; on Venice, whether the key is accepted (the model list is public). */
    fun health(): Boolean = request("GET", if (venice) "/v1/api_keys/rate_limits" else "/health", null, null, 5_000).first == 200

    /** Speech-recognition model ids, in the server's order (spec §7.2: the default is the first). On Venice only private ones. */
    fun speechModels(): List<String> {
        val (code, body) = request("GET", modelsPath, null, null, 5_000)
        if (code != 200) throw IOException("HTTP $code")
        return speechModels(body)
    }

    private fun speechModels(body: String): List<String> {
        val data = (Json.parse(body) as? Map<*, *>)?.get("data") as? List<*> ?: return emptyList()
        return data.mapNotNull { m ->
            (m as? Map<*, *>)?.takeIf {
                if (venice) it["type"] == "asr" && (it["model_spec"] as? Map<*, *>)?.get("privacy") == "private"
                else it["task"] == "automatic-speech-recognition"
            }?.get("id") as? String
        }
    }

    /** Spec §7.2 cold model: loads [model] unless it is already loaded (201 loaded, 409 already). Best effort; Venice's are always warm. */
    fun preload(model: String) {
        if (venice) return
        try {
            val (code, body) = request("GET", "/api/ps", null, null, 3_000)
            val loaded = if (code == 200) ((Json.parse(body) as? Map<*, *>)?.get("models") as? List<*>).orEmpty() else emptyList()
            if (model !in loaded) request("POST", "/api/ps/$model", ByteArray(0), "application/json", 30_000)
        } catch (_: Exception) {
        }
    }

    private inline fun attempt(block: () -> Outcome): Outcome = try {
        if (base.isEmpty()) Outcome.Failed(Backend.NO_SERVER) else block()
    } catch (_: MalformedURLException) {
        Outcome.Failed("Invalid server URL")
    } catch (e: SSLException) {
        Outcome.Failed("Secure connection failed (${e.javaClass.simpleName}): check http vs https") // settings, not the network
    } catch (e: IOException) {
        Outcome.Retry(e.javaClass.simpleName) // refused, unknown host, timeout, reset, cut-off reply
    } catch (_: UnreadableReply) {
        Outcome.Retry("Unreadable reply")
    } catch (e: RuntimeException) {
        Outcome.Failed("Invalid server URL (${e.javaClass.simpleName})") // e.g. a port out of range
    }

    /** A reply we couldn't parse: likely cut off, so worth another try. */
    private class UnreadableReply : RuntimeException()

    private inline fun <T> readable(parse: () -> T): T = try {
        parse()
    } catch (_: IllegalArgumentException) {
        throw UnreadableReply()
    }

    private fun request(method: String, path: String, body: ByteArray?, contentType: String?, readTimeoutMs: Int) =
        http(base, method, path, token, body, contentType, connectTimeoutMs, readTimeoutMs)

    private fun multipart(boundary: String, wav: ByteArray, fields: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream(wav.size + 2_048)
        fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$k\"")
            line("")
            line(v)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"")
        line("Content-Type: audio/wav")
        line("")
        out.write(wav)
        line("")
        line("--$boundary--")
        return out.toByteArray()
    }

    private companion object {
        /** A word, apostrophes inside it included ("don't"). */
        val WORD = Regex("\\p{L}+(?:'\\p{L}+)*")
    }
}
