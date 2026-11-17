package dev.yazin.keyboard.engine.voice

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueOverHttpTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun droppedConnectionsAnd503sLoseNothingAndKeepOrder() {
        val n = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/v1/audio/transcriptions") { ex ->
            val body = String(ex.requestBody.readBytes(), Charsets.ISO_8859_1)
            when (n.incrementAndGet() % 3) {
                1 -> { ex.sendResponseHeaders(200, 100); ex.responseBody.write("{\"te".toByteArray()); ex.close() } // cut off
                2 -> { ex.sendResponseHeaders(503, -1); ex.close() }
                else -> {
                    val said = Regex("name=\"prompt\"\r\n\r\n(c\\d+)\r\n").find(body)!!.groupValues[1]
                    Thread.sleep((0..80).random().toLong()) // finish out of order
                    val json = """{"text":" $said","segments":[{"text":" $said","no_speech_prob":0.0,"avg_logprob":-0.1}]}""".toByteArray()
                    ex.sendResponseHeaders(200, json.size.toLong())
                    ex.responseBody.use { it.write(json) }
                }
            }
        }
        server.start()
        try {
            val texts = LinkedBlockingQueue<String>()
            val store = ChunkStore(tmp.root)
            val client = WhisperClient("http://127.0.0.1:${server.address.port}")
            val q = ChunkQueue(
                store,
                { wav, c -> client.transcribe(wav, TranscribeRequest("m", c.language, c.prompt, null)) },
                object : ChunkQueue.Listener {
                    override fun onText(chunk: ChunkInfo, text: String, recovered: Boolean) { store.delete(chunk.id); texts += text } // delete first: the test checks the folder as soon as the last text arrives
                    override fun onStatus(status: QueueStatus) {}
                },
                Backoff(baseMs = 5, capMs = 50),
            )
            val said = (1..10).map { "c%02d".format(it) }
            said.forEachIndexed { i, s ->
                q.add(ChunkInfo("100-%04d".format(i + 1), "en", s, "app/1", System.currentTimeMillis()), Wav.encode(ShortArray(1_600)))
            }
            assertEquals(said, List(said.size) { texts.poll(20, TimeUnit.SECONDS) })
            assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
            q.shutdown()
        } finally {
            server.stop(0)
        }
    }
}
