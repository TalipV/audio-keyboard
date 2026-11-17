package dev.yazin.keyboard.engine.voice

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yazin.keyboard.engine.Backend
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhisperClientTest {
    private lateinit var server: HttpServer
    private val requests = ArrayList<Pair<String, String>>() // "METHOD path" to body
    private val auth = ArrayList<String?>() // each request's Authorization header
    @Volatile private var handler: (HttpExchange) -> Unit = { reply(it, 200, "{}") }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(requests) {
                requests += "${ex.requestMethod} ${ex.requestURI.path}" to String(ex.requestBody.readBytes(), Charsets.ISO_8859_1)
                auth += ex.requestHeaders.getFirst("Authorization")
            }
            handler(ex)
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}"
    private val wav = Wav.encode(ShortArray(1_600))
    private val req = TranscribeRequest("m", "ar", "قبل", "Yazin, يزن")

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    @Test fun sendsAMultipartRequestAndReturnsTheCleanText() {
        handler = { reply(it, 200, """{"text":" مرحبا","segments":[{"text":" مرحبا","no_speech_prob":0.0,"avg_logprob":-0.1}]}""") }
        assertEquals(Outcome.Text("مرحبا"), WhisperClient("$url/").transcribe(wav, req))
        val (call, body) = requests.single()
        assertEquals("POST /v1/audio/transcriptions", call)
        for (f in listOf("model", "language", "prompt", "hotwords", "response_format", "temperature", "file"))
            assertTrue(f, body.contains("name=\"$f\""))
        assertTrue(body.contains("verbose_json"))
        assertTrue(body.contains("RIFF"))
    }

    @Test fun severalLanguagesAreDetectedAndAWrongGuessIsAskedAgainAsArabic() {
        val many = req.copy(language = "en,ar") // the English layout is active
        handler = { reply(it, 200, """{"text":"Hi","language":"en"}""") }
        assertEquals(Outcome.Text("Hi"), WhisperClient(url).transcribe(wav, many))
        assertTrue(!requests.first().second.contains("name=\"language\""))

        // Measured on the live server: "وينك" came back as Finnish "Viinu", and with Latin hotwords "ابشر" as Arabic "Abshar".
        // From the phone: Gulf Arabic romanized with one Arabic word left in.
        val guesses = listOf("""{"text":"Viinu.","language":"fi"}""", """{"text":"Abshar","language":"ar"}""",
            """{"text":"Magi wallah, wma sarat, fhaai aخر muhawola betecon","language":"ar"}""")
        for (guess in guesses) {
            requests.clear()
            handler = { ex ->
                val named = synchronized(requests) { requests.last().second }.contains("name=\"language\"")
                reply(ex, 200, if (named) """{"text":"أبشر","language":"ar"}""" else guess)
            }
            assertEquals(guess, Outcome.Text("أبشر"), WhisperClient(url).transcribe(wav, many))
            val again = requests.last().second
            assertEquals(guess, 2, requests.size)
            assertTrue(guess, again.contains("name=\"language\"\r\n\r\nar\r\n"))
            assertTrue(guess, again.contains("name=\"hotwords\"\r\n\r\nيزن\r\n".toByteArray().toString(Charsets.ISO_8859_1)))
            assertTrue(guess, !again.contains("name=\"prompt\""))
        }
    }

    @Test fun contextIsLeftOutWhereItCouldPullSpeechIntoTheWrongLanguage() {
        // Measured: after English text, Arabic came back translated ("Hello, how are you?"), echoed or half romanized.
        val english = "Hey Sam, sounds good. I'll be there around 8 and then"
        handler = { reply(it, 200, """{"text":"مرحبا","language":"ar"}""") }
        for (languages in listOf("ar", "en,ar", "ar,en")) {
            requests.clear()
            WhisperClient(url).transcribe(wav, req.copy(language = languages, prompt = english))
            assertTrue(languages, requests.none { it.second.contains("name=\"prompt\"") })
        }
        requests.clear()
        WhisperClient(url).transcribe(wav, req.copy(language = "en", prompt = english))
        assertTrue(requests.single().second.contains("name=\"prompt\""))
        requests.clear()
        WhisperClient(url).transcribe(wav, req.copy(language = "en,ar")) // Arabic context turned English into a stock Arabic phrase
        assertTrue(requests.none { it.second.contains("name=\"prompt\"") })
        requests.clear()
        WhisperClient(url).transcribe(wav, req) // Arabic alone keeps Arabic context
        assertTrue(requests.single().second.contains("name=\"prompt\""))
    }

    @Test fun aGuessIsCheckedAgainstTheOtherLanguageAndTheSurerOneWins() {
        // Measured: Gulf Arabic heard as English came back translated at −0.74; read as Arabic it scored −0.08.
        // English read as Arabic is a stock hallucination, scoring below the English.
        fun seg(text: String, lp: Double) = """{"text":"$text","language":"x","segments":[{"text":"$text","avg_logprob":$lp,"no_speech_prob":0.0}]}"""
        val many = req.copy(language = "en,ar")
        for ((english, arabic, want) in listOf(
            Triple(seg("What can I do?", -0.74), seg("ما أدري", -0.08), "ما أدري"),
            Triple(seg("Send the report", -0.02), seg("ترجمة نانسي قنقر", -0.32), "Send the report"),
        )) {
            requests.clear()
            handler = { ex ->
                val named = synchronized(requests) { requests.last().second }.contains("name=\"language\"")
                reply(ex, 200, if (named) arabic.replace("\"x\"", "\"ar\"") else english.replace("\"x\"", "\"en\""))
            }
            assertEquals(Outcome.Text(want), WhisperClient(url).transcribe(wav, many))
            assertEquals(2, requests.size)
            assertTrue(!requests.last().second.contains("name=\"prompt\""))
        }
    }

    @Test fun theUrlMayHaveASlashOrAPathPrefix() {
        handler = { reply(it, 200, """{"text":"ok"}""") }
        WhisperClient(url).transcribe(wav, req)
        WhisperClient("  $url//  ").transcribe(wav, req)
        WhisperClient("$url/speech/").transcribe(wav, req)
        assertEquals(
            listOf("POST /v1/audio/transcriptions", "POST /v1/audio/transcriptions", "POST /speech/v1/audio/transcriptions"),
            requests.map { it.first },
        )
    }

    @Test fun serverErrorsTimeoutsAndRateLimitsAreRetried() {
        for (code in listOf(503, 500, 429, 408)) {
            handler = { reply(it, code, "") }
            assertTrue("$code", WhisperClient(url).transcribe(wav, req) is Outcome.Retry)
        }
    }

    @Test fun otherClientErrorsNeedTheUser() {
        handler = { reply(it, 404, """{"detail":"Model 'nope' is not installed locally."}""") }
        assertEquals(Outcome.Failed("HTTP 404: Model 'nope' is not installed locally."), WhisperClient(url).transcribe(wav, req))
    }

    @Test fun aReplyCutOffMidWayIsRetried() {
        handler = { ex -> ex.sendResponseHeaders(200, 100); ex.responseBody.write("{\"te".toByteArray()); ex.close() }
        assertTrue(WhisperClient(url).transcribe(wav, req) is Outcome.Retry)
    }

    @Test fun anUnreachableServerIsRetried() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(WhisperClient("http://127.0.0.1:$closedPort").transcribe(wav, req) is Outcome.Retry)
    }

    @Test fun aMalformedUrlNeedsTheUser() {
        assertEquals(Outcome.Failed("Invalid server URL"), WhisperClient("mybox:9000").transcribe(wav, req))
        assertEquals(Outcome.Failed(Backend.NO_SERVER), WhisperClient("").transcribe(wav, req))
    }

    @Test fun preloadSkipsALoadedModelAndLoadsOthers() {
        handler = { ex -> if (ex.requestMethod == "GET") reply(ex, 200, """{"models":["a/x","silero_vad_v5"]}""") else reply(ex, 201, "{}") }
        WhisperClient(url).preload("a/x")
        WhisperClient(url).preload("b/y")
        assertEquals(listOf("GET /api/ps", "GET /api/ps", "POST /api/ps/b/y"), requests.map { it.first })
    }

    @Test fun speechModelsListsOnlyRecognitionModels() {
        handler = {
            reply(it, 200, """{"data":[{"id":"deepdml/faster-whisper-large-v3-turbo-ct2","task":"automatic-speech-recognition"},
                {"id":"silero_vad_v5","task":"voice-activity-detection"}],"object":"list"}""")
        }
        assertEquals(listOf("deepdml/faster-whisper-large-v3-turbo-ct2"), WhisperClient(url).speechModels())
        assertTrue(WhisperClient(url).health())
    }

    private fun closedPort() = java.net.ServerSocket(0).use { it.localPort }

    @Test fun theAutomaticModelIsTheServersFirstSpeechModel() {
        handler = { reply(it, 200, """{"data":[{"id":"vad","task":"voice-activity-detection"},{"id":"w","task":"automatic-speech-recognition"}]}""") }
        assertEquals(Outcome.Text("w"), WhisperClient(url).model(""))
        assertEquals(Outcome.Text("mine"), WhisperClient(url).model(" mine "))
        assertEquals(1, requests.size) // a configured model needs no lookup
    }

    @Test fun settingsMistakesFoundWhileLookingUpTheModelNeedTheUser() {
        handler = { reply(it, 200, """{"data":[]}""") }
        assertEquals(Outcome.Failed("No speech model on the server"), WhisperClient(url).model(""))
        handler = { reply(it, 404, """{"detail":"Not Found"}""") }
        assertEquals(Outcome.Failed("HTTP 404: Not Found"), WhisperClient(url).model(""))
        assertEquals(Outcome.Failed("Invalid server URL"), WhisperClient("mybox:9000").model(""))
        assertTrue(WhisperClient("http://127.0.0.1:${closedPort()}").model("") is Outcome.Retry) // a real outage still waits
    }

    @Test fun aPortOutOfRangeOrATlsMismatchNeedsTheUser() {
        assertTrue(WhisperClient("http://127.0.0.1:90000").transcribe(wav, req) is Outcome.Failed)
        // https to a plain-http server: it answers the TLS hello in plain text, as uvicorn does.
        java.net.ServerSocket(0).use { plain ->
            Thread { plain.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) } }.start()
            assertTrue(WhisperClient("https://127.0.0.1:${plain.localPort}").transcribe(wav, req) is Outcome.Failed)
        }
    }

    @Test fun aReplyWithoutTextKeepsTheAudio() {
        handler = { reply(it, 200, "{}") }
        assertEquals(Outcome.Failed("Unexpected reply from the server"), WhisperClient(url).transcribe(wav, req))
    }

    private fun venice(english: Set<String> = emptySet()) = WhisperClient(url, "sk-1", Backend.VENICE, { it in english })

    @Test fun aTokenIsSentAsABearerHeaderOnlyWhenSet() {
        handler = { reply(it, 200, """{"text":"ok"}""") }
        WhisperClient(url, " sk-1 ").transcribe(wav, req)
        WhisperClient(url, "  ").transcribe(wav, req)
        WhisperClient(url).transcribe(wav, req)
        assertEquals(listOf("Bearer sk-1", null, null), auth)
    }

    @Test fun veniceGetsPlainJsonAndNoHotwords() {
        // Venice refuses verbose_json with a 400 and ignores hotwords.
        handler = { reply(it, 200, """{"text":" مرحبا كيف حالك","duration":2.06}""") }
        assertEquals(Outcome.Text("مرحبا كيف حالك"), venice().transcribe(wav, req))
        val body = requests.single().second
        assertTrue(body.contains("name=\"response_format\"\r\n\r\njson\r\n"))
        assertTrue(!body.contains("name=\"hotwords\""))
        assertTrue(body.contains("name=\"language\"\r\n\r\nar\r\n"))
        assertTrue(body.contains("name=\"prompt\""))
    }

    @Test fun veniceAsksRomanizedArabicAgainButNotEnglish() {
        // Venice's reply names no language and has no scores, so the words decide.
        val many = req.copy(language = "en,ar")
        val english = setOf("testing", "bye", "all", "right", "send", "the", "report")
        for (latin in listOf("Viinu.", "Abshar", "Magi wallah, wma sarat, fhaai aخر muhawola betecon")) {
            requests.clear()
            handler = { ex ->
                val named = synchronized(requests) { requests.last().second }.contains("name=\"language\"")
                reply(ex, 200, if (named) """{"text":"أبشر"}""" else """{"text":"$latin"}""")
            }
            assertEquals(latin, Outcome.Text("أبشر"), venice(english).transcribe(wav, many))
            assertEquals(latin, 2, requests.size)
            assertTrue(latin, requests.last().second.contains("name=\"language\"\r\n\r\nar\r\n"))
        }
        for (said in listOf("Testing 1 2 3", "Bye-bye!", "All right", "Send the report to Yazin")) {
            requests.clear()
            handler = { reply(it, 200, """{"text":"$said"}""") }
            assertEquals(said, Outcome.Text(said), venice(english).transcribe(wav, many))
            assertEquals(said, 1, requests.size) // no second reading: there is nothing to compare it by
        }
        requests.clear()
        handler = { reply(it, 200, """{"text":"مرحبا"}""") }
        assertEquals(Outcome.Text("مرحبا"), venice().transcribe(wav, many))
        assertEquals(1, requests.size)
    }

    @Test fun veniceKeepsTheFirstReadingWhenTheArabicOneIsNoBetter() {
        // English read as Arabic comes back as a stock phrase; a second Latin reading or a failure is no better either.
        val many = req.copy(language = "en,ar")
        for (second in listOf(200 to """{"text":"شكرا للمشاهدة"}""", 200 to """{"text":"Oleksandr"}""", 503 to "")) {
            handler = { ex ->
                val named = synchronized(requests) { requests.last().second }.contains("name=\"language\"")
                if (named) reply(ex, second.first, second.second) else reply(ex, 200, """{"text":"Oleksandr"}""")
            }
            assertEquals("$second", Outcome.Text("Oleksandr"), venice().transcribe(wav, many))
        }
    }

    @Test fun aSlowVeniceRequestIsSentAgainAndTheFirstGoodReplyWins() {
        // Measured: about one Venice request in ten took 10–16 s; the same clip sent again took about 2 s.
        val n = AtomicInteger()
        handler = { ex ->
            if (n.incrementAndGet() == 1) { Thread.sleep(3_000); reply(ex, 200, """{"text":"بطيء"}""") } else reply(ex, 200, """{"text":"سريع"}""")
        }
        val t0 = System.nanoTime()
        assertEquals(Outcome.Text("سريع"), WhisperClient(url, "sk-1", Backend.VENICE, hedgeMs = 200).transcribe(wav, req))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(2, requests.size)

        // A failure that comes first waits for the other request; a quick answer is never sent twice.
        n.set(0)
        requests.clear()
        handler = { ex -> if (n.incrementAndGet() == 1) { Thread.sleep(400); reply(ex, 503, "") } else { Thread.sleep(600); reply(ex, 200, """{"text":"تمام"}""") } }
        assertEquals(Outcome.Text("تمام"), WhisperClient(url, "sk-1", Backend.VENICE, hedgeMs = 200).transcribe(wav, req))
        requests.clear()
        handler = { reply(it, 200, """{"text":"تمام"}""") }
        WhisperClient(url, "sk-1", Backend.VENICE, hedgeMs = 200).transcribe(wav, req)
        assertEquals(1, requests.size)
    }

    @Test fun veniceWithoutAKeyNeedsTheUserBeforeAnythingIsSent() {
        assertEquals(Outcome.Failed(Backend.NO_KEY), WhisperClient(url, " ", Backend.VENICE).transcribe(wav, req))
        assertTrue(requests.isEmpty())
    }

    @Test fun veniceRefusalsSayWhatTheKeyOrAccountNeeds() {
        for ((code, body, want) in listOf(
            Triple(401, """{"error":"Authentication failed"}""", "API key not accepted"),
            Triple(402, """{"x402Version":2,"error":"Authentication required"}""", "No API key"),
            Triple(402, """{"error":"Insufficient balance"}""", "Venice account has no credit"),
            Triple(400, """{"error":"Invalid request parameters","issues":[{"code":"invalid_enum_value","message":"Invalid response_format"}]}""",
                "HTTP 400: Invalid response_format"),
            Triple(404, """{"error":"Specified model not found: nope"}""", "HTTP 404: Specified model not found: nope"),
        )) {
            handler = { reply(it, code, body) }
            assertEquals(Outcome.Failed(want), venice().transcribe(wav, req))
        }
    }

    @Test fun veniceModelsAreItsPrivateSpeechModelsAndHealthChecksTheKey() {
        handler = { ex ->
            if (ex.requestURI.path.endsWith("rate_limits")) reply(ex, 200, """{"data":{"balances":{"USD":4.98}}}""")
            else reply(ex, 200, """{"data":[{"id":"nvidia/parakeet-tdt-0.6b-v3","type":"asr","model_spec":{"privacy":"private"}},
                {"id":"elevenlabs/scribe-v2","type":"asr","model_spec":{"privacy":"anonymized"}},
                {"id":"openai/whisper-large-v3","type":"asr","model_spec":{"privacy":"private"}}]}""")
        }
        assertEquals(listOf("nvidia/parakeet-tdt-0.6b-v3", "openai/whisper-large-v3"), venice().speechModels())
        assertTrue(venice().health())
        assertEquals(listOf("GET /v1/models", "GET /v1/api_keys/rate_limits"), requests.map { it.first })
        assertEquals(Outcome.Text("openai/whisper-large-v3"), venice().model("")) // the default, not the list's first
        venice().preload("openai/whisper-large-v3")
        assertEquals(2, requests.size)
    }
}
