package dev.yazin.keyboard.engine.voice

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val texts = LinkedBlockingQueue<Pair<String, Boolean>>() // text, recovered
    private val statuses = CopyOnWriteArrayList<QueueStatus>()
    private val queues = ArrayList<ChunkQueue>()
    private val listener = object : ChunkQueue.Listener {
        override fun onText(chunk: ChunkInfo, text: String, recovered: Boolean) { texts += text to recovered }
        override fun onStatus(status: QueueStatus) { statuses += status }
    }

    @After fun stop() = queues.forEach { it.shutdown() }

    private fun queue(backoff: Backoff = Backoff(baseMs = 5, capMs = 20), t: ChunkQueue.Transcriber) =
        ChunkQueue(ChunkStore(tmp.root), t, listener, backoff).also { queues += it }

    private fun info(id: String, createdAt: Long = System.currentTimeMillis()) = ChunkInfo(id, "en", "", "app/1", createdAt)

    /** A WAV whose samples are [tag]; the fake transcribers answer "t<tag>". */
    private fun wav(tag: Int) = Wav.encode(ShortArray(16) { tag.toShort() })
    private fun tag(wav: ByteArray) = Wav.decode(wav)[0].toInt()
    private fun next(): Pair<String, Boolean>? = texts.poll(5, TimeUnit.SECONDS)
    private fun nothingFor(ms: Long) = assertNull(texts.poll(ms, TimeUnit.MILLISECONDS))
    private fun files() = tmp.root.list()!!.sorted()

    private fun awaitStatus(p: (QueueStatus) -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (statuses.none(p)) {
            assertTrue("status never seen; got $statuses", System.currentTimeMillis() < end)
            Thread.sleep(5)
        }
    }

    @Test fun textsComeOutInSpokenOrderEvenWhenLaterChunksFinishFirst() {
        val q = queue { w, _ -> Thread.sleep((4 - tag(w)) * 60L); Outcome.Text("t${tag(w)}") }
        for (i in 1..3) q.add(info("100-000$i"), wav(i))
        assertEquals(listOf("t1", "t2", "t3"), List(3) { next()!!.first })
    }

    @Test fun networkErrorsAreRetriedUntilTheyWork() {
        val attempts = AtomicInteger()
        val q = queue { w, _ -> if (attempts.incrementAndGet() <= 3) Outcome.Retry("down") else Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        assertEquals("t1" to false, next())
        assertEquals(4, attempts.get())
        assertTrue(statuses.any { it.offline })
        awaitStatus { it.pending == 0 && !it.offline }
    }

    @Test fun theNetworkComingBackRetriesAtOnce() {
        val online = AtomicBoolean(false)
        val q = queue(Backoff(baseMs = 60_000)) { w, _ -> if (online.get()) Outcome.Text("t${tag(w)}") else Outcome.Retry("down") }
        q.add(info("100-0001"), wav(1))
        awaitStatus { it.offline }
        online.set(true)
        q.wake()
        assertEquals("t1" to false, next())
    }

    @Test fun refusedChunksKeepTheirAudioAndHoldBackLaterText() {
        val refuse = AtomicBoolean(true)
        val q = queue { w, _ -> if (tag(w) == 1 && refuse.get()) Outcome.Failed("HTTP 404") else Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        q.add(info("100-0002"), wav(2))
        awaitStatus { it.failed?.id == "100-0001" && it.failed?.error == "HTTP 404" }
        nothingFor(200)
        assertTrue(File(tmp.root, "100-0001.wav").exists())
        refuse.set(false)
        q.retryFailed()
        assertEquals(listOf("t1", "t2"), List(2) { next()!!.first })
    }

    @Test fun discardingARefusedChunkReleasesTheRest() {
        val q = queue { w, _ -> if (tag(w) == 1) Outcome.Failed("HTTP 404") else Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        q.add(info("100-0002"), wav(2))
        awaitStatus { it.failed != null }
        q.discardFailed()
        assertEquals("t2", next()!!.first)
        assertFalse(File(tmp.root, "100-0001.wav").exists())
    }

    @Test fun audioIsOnDiskBeforeTheRequestAndTheTextUntilItIsUsed() {
        val sawAudio = AtomicBoolean(false)
        val q = queue { w, c -> sawAudio.set(File(tmp.root, "${c.id}.wav").exists()); Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        assertEquals("t1", next()!!.first)
        assertTrue(sawAudio.get())
        assertEquals(listOf("100-0001.meta"), files()) // audio gone once the text is safe
        q.ack("100-0001")
        assertEquals(emptyList<String>(), files())
    }

    @Test fun aRestartResumesStoredChunksAsRecovered() {
        val offline = queue { _, _ -> Outcome.Retry("down") }
        offline.add(info("100-0001"), wav(1))
        offline.add(info("100-0002"), wav(2))
        awaitStatus { it.offline }
        offline.shutdown()
        queue { w, _ -> Outcome.Text("t${tag(w)}") }.resume()
        assertEquals(listOf("t1" to true, "t2" to true), List(2) { next() })
    }

    @Test fun aTextNotYetUsedSurvivesARestart() {
        val first = queue { w, _ -> Outcome.Text("t${tag(w)}") }
        first.add(info("100-0001"), wav(1))
        assertEquals("t1" to false, next())
        first.shutdown()
        val calls = AtomicInteger()
        queue { _, _ -> calls.incrementAndGet(); Outcome.Retry("unexpected") }.resume()
        assertEquals("t1" to true, next())
        assertEquals(0, calls.get())
    }

    @Test fun chunksOlderThanADayArePurged() {
        ChunkStore(tmp.root).save(info("100-0001", createdAt = System.currentTimeMillis() - 25 * 3_600_000L), wav(1))
        queue { w, _ -> Outcome.Text("t${tag(w)}") }.resume()
        nothingFor(200)
        assertEquals(emptyList<String>(), files())
    }

    @Test fun aNewDictationWaitsBehindAnEarlierOneStillOffline() {
        val online = AtomicBoolean(false)
        val q = queue(Backoff(baseMs = 60_000)) { w, c ->
            if (c.id.startsWith("100-") && !online.get()) Outcome.Retry("down") else Outcome.Text("t${tag(w)}")
        }
        q.add(info("100-0001"), wav(1))
        awaitStatus { it.offline }
        q.add(info("200-0001"), wav(2))
        nothingFor(200)
        online.set(true)
        q.wake()
        assertEquals(listOf("t1", "t2"), List(2) { next()!!.first })
    }

    @Test fun retryAndDiscardCoverEveryRefusedChunk() {
        // Refusals share a cause (a wrong model): one Retry or Discard deals with all of them.
        val refuse = AtomicBoolean(true)
        val q = queue { w, _ -> if (refuse.get()) Outcome.Failed("HTTP 404") else Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        q.add(info("100-0002"), wav(2))
        awaitStatus { s -> s.pending == 2 && s.failed != null }
        Thread.sleep(100) // both attempts done
        refuse.set(false)
        q.retryFailed()
        assertEquals(listOf("t1", "t2"), List(2) { next()!!.first })
        refuse.set(true)
        q.add(info("200-0001"), wav(3))
        q.add(info("200-0002"), wav(4))
        awaitStatus { s -> s.failed?.id == "200-0001" }
        Thread.sleep(100)
        q.ack("100-0001"); q.ack("100-0002")
        q.discardFailed()
        nothingFor(200)
        assertEquals(emptyList<String>(), files())
    }

    @Test fun anUnexpectedErrorNeedsTheUserInsteadOfFreezingTheQueue() {
        val broken = AtomicBoolean(true)
        val q = queue { w, _ -> if (broken.get()) throw IllegalStateException("bug") else Outcome.Text("t${tag(w)}") }
        q.add(info("100-0001"), wav(1))
        awaitStatus { it.failed?.error == "Unexpected error: IllegalStateException" }
        broken.set(false)
        q.retryFailed()
        assertEquals("t1", next()!!.first)
    }

    @Test fun textsWaitUntilSomeoneListens() {
        val q = ChunkQueue(ChunkStore(tmp.root), { w, _ -> Outcome.Text("t${tag(w)}") }, null, Backoff(5, 20)).also { queues += it }
        q.add(info("100-0001"), wav(1))
        Thread.sleep(200)
        q.attach(listener)
        assertEquals("t1" to false, next())
        q.attach(null)
        q.add(info("100-0002"), wav(2))
        nothingFor(200)
        q.attach(listener)
        assertEquals("t2" to false, next())
    }
}
