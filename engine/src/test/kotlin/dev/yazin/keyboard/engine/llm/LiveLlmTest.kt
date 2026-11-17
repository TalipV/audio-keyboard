package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.chat.ChatContext
import dev.yazin.keyboard.engine.chat.ChatKey
import dev.yazin.keyboard.engine.chat.ChatMemory
import dev.yazin.keyboard.engine.chat.ChatStorage
import dev.yazin.keyboard.engine.chat.Message
import dev.yazin.keyboard.engine.chat.Side
import dev.yazin.keyboard.engine.chat.Source
import dev.yazin.keyboard.engine.style.ChatLine
import dev.yazin.keyboard.engine.style.WhatsAppExport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The user's llama-server: one compose and one revise. Run with -Pllm (or -Pllm=http://host:port/), or -Pllm=venice with VENICE_API_KEY. */
class LiveLlmTest {
    private val url = System.getProperty("llmUrl").orEmpty()
    private val client = if (url == "venice") LlmClient(Backend.VENICE.llmUrl, System.getenv("VENICE_API_KEY").orEmpty(), Backend.VENICE) else LlmClient(url)

    private fun ask(ctx: PromptContext, instruction: String): String {
        val t0 = System.nanoTime()
        val out = client.complete("", Prompt.system(ctx), Prompt.user(ctx, instruction))
        println("$instruction → $out in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("$out", out is LlmResult.Text)
        return Prompt.clean((out as LlmResult.Text).text)
    }

    @Test fun composesAReplyFromTheScreen() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val reply = ask(PromptContext("com.whatsapp", "Message", "Sam\nare you coming tonight?", "", false), "reply saying yes, around 8")
        assertTrue(reply, reply.contains("8"))
        assertTrue(reply, !reply.startsWith("\""))
    }

    @Test fun carriesOutTheInstructionInsteadOfWritingItDown() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val answer = ask(PromptContext("com.google.android.keep", "Note", "", "", false), "what's 15 percent of 80")
        assertTrue(answer, answer.contains("12") && !answer.contains("percent", ignoreCase = true))
        val message = ask(PromptContext("com.whatsapp", "Message", "Sam\nyou around this weekend?", "", false), "ask him if he wants to grab dinner on Saturday")
        assertTrue(message, !message.startsWith("ask", ignoreCase = true) && message.contains("dinner", ignoreCase = true))
    }

    /** Speech that already sounds like a message is still guidance: ✦ composes, it doesn't transcribe. */
    @Test fun composesFromSpeechThatSoundsLikeTheMessage() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val said = "I'm going to be out tomorrow, I'm sick, Hassan will cover the standup"
        val message = ask(PromptContext("com.slack", "Message #team", "", "", false), said)
        assertTrue(message, !message.equals(said, ignoreCase = true) && !message.trimEnd('.').equals(said, ignoreCase = true))
        assertTrue(message, message.contains("Hassan"))
        val exact = ask(PromptContext("com.whatsapp", "Message", "Sam\nyou around?", "", false), "say exactly: on my way")
        assertTrue(exact, exact.trimEnd('.', '!').equals("on my way", ignoreCase = true))
    }

    @Test fun revisesTheFieldInArabic() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val reply = ask(PromptContext("com.whatsapp", "", "", "Yes, I'll be there around 8.", false), "translate to Arabic")
        assertTrue(reply, reply.any { it in '؀'..'ۿ' })
    }

    @Test fun repliesInTheUsersVoiceFromTheirSamples() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val screen = "Family Reunion 2025\nNora\nمبروك سام!!\nCongrats on the new job, so proud of you\nلازم نجتمع ان شاء الله"
        val samples = listOf("haha thanks man, means a lot 🙏", "lol yeah that tracks", "sounds good, see u then", "omg congrats!! so happy for you",
            "ya3ni honestly not sure yet, will let u know", "الله يبارك فيك", "hahaha no way", "appreciate it 🙏 alhamdulillah")
        val reply = ask(PromptContext("com.whatsapp", "Message", screen, "", false, samples = samples), "warm reply")
        assertTrue(reply, !reply.contains("how are you", ignoreCase = true))
        assertTrue(reply, reply.length < 200)
    }

    /** The fixture export as ChatMemory.historyText gives it. */
    private fun samHistory(): String {
        val stream = javaClass.getResourceAsStream("/exports/WhatsApp Chat with Sam.txt") ?: error("no fixture")
        val lines = stream.bufferedReader().useLines { WhatsAppExport.parse(it.toList().asSequence()) }
        return lines.joinToString("\n") { if (it.sender == "Me") "Me: ${it.text}" else "${it.sender}: ${it.text}" }
    }

    @Test fun writesAProfileFromAnExport() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val t0 = System.nanoTime()
        val out = client.complete("", ProfilePrompt.SYSTEM, ProfilePrompt.user("Sam", emptyList(), "", samHistory()),
            maxTokens = ProfilePrompt.MAX_TOKENS, readTimeoutMs = ProfilePrompt.TIMEOUT_MS)
        println("profile → $out in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("$out", out is LlmResult.Text)
        val profile = (out as LlmResult.Text).text
        assertTrue(profile, profile.split(Regex("\\s+")).count { it.isNotBlank() } <= 120)
        assertTrue(profile, Regex("khaleeji|gulf|emirati|kuwaiti|qatari|bahraini|خليج", RegexOption.IGNORE_CASE).containsMatchIn(profile))
    }

    private val sam = ChatContext(
        "Sam",
        "He's my cousin\nSam is the owner's cousin. They mix Khaleeji Arabic in Arabic script with English. The owner writes short, " +
            "lowercase lines with \"haha\" and emoji; Sam writes more Arabic. Topics: family dinners, football, Sam's new job.",
        "Get to know them on a deeper level: ask about their life, remember what they share.",
        listOf("Started a new job in October"),
        listOf("Got the interview!!" to "yooo congrats 🎉 / which company?", "work is getting busier" to "already?? that's fast / they trust u",
            "thanks for coming today" to "anytime 🙏 / the machboos was unreal"),
        listOf("Sam: tomorrow is the first day", "Me: u'll be great", "Sam: الحمدلله زين", "Me: give it a month"),
    )
    private val samScreen = PromptContext("com.whatsapp", "Message", "Sam\njust got back from Istanbul", "", false, chat = sam)

    @Test fun aCloserGoalAddsAFollowUp() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val reply = ask(samScreen, "reply")
        // In this owner's lowercase style a question often goes without its "?".
        val asks = Regex("\\b(how|what|when|where|why|which|did)\\b|شلون|كيف|وين|متى|ليش", RegexOption.IGNORE_CASE)
        assertTrue(reply, reply.contains('?') || reply.contains('؟') || asks.containsMatchIn(reply))
    }

    private fun drafts(ctx: PromptContext): List<String> {
        val req = ReplyDrafts.request(ctx)
        val out = client.complete("", req.system, req.user, "replies",
            maxTokens = ReplyDrafts.MAX_TOKENS, format = ReplyDrafts.SCHEMA)
        assertTrue("$out", out is LlmResult.Text)
        return ReplyDrafts.parse((out as LlmResult.Text).text).also { println("waiting replies → $it") }
    }

    @Test fun draftsThreeRepliesInTheChatsVoice() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val replies = drafts(samScreen.copy(chat = sam.copy(goal = "")))
        assertTrue("$replies", replies.size == 3)
        replies.forEach { reply ->
            assertTrue(reply, reply.isNotBlank() && reply.length <= 300)
            // This Chat mixes English with Arabic script; neither language is mandatory.
            assertTrue(reply, reply.any { it in 'a'..'z' || it in '؀'..'ۿ' })
            assertTrue(reply, !reply.startsWith("\""))
            assertTrue(reply, !reply.contains("how are you", ignoreCase = true))
        }
    }

    @Test fun waitingRepliesWithACloserGoalIncludeAFollowUp() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val replies = drafts(samScreen)
        val asks = Regex("\\b(how|what|when|where|why|which|did)\\b|شلون|كيف|وين|متى|ليش", RegexOption.IGNORE_CASE)
        assertTrue("$replies", replies.any { it.contains('?') || it.contains('؟') || asks.containsMatchIn(it) })
    }

    @Test fun justSayOkSaysOnlyOk() {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val reply = ask(samScreen, "just say ok")
        assertTrue(reply, reply.length <= 6)
    }

    private class MemStorage : ChatStorage {
        val files = HashMap<String, List<String>>()
        override fun read(name: String) = files[name]
        override fun write(name: String, lines: (Appendable) -> Unit) {
            files[name] = StringBuilder().also(lines).lines().filter { it.isNotEmpty() }
        }
        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private fun samLines(): List<ChatLine> {
        val stream = javaClass.getResourceAsStream("/exports/WhatsApp Chat with Sam.txt") ?: error("no fixture")
        return stream.bufferedReader().useLines { WhatsAppExport.parse(it.toList().asSequence()) }
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Nearest-rank percentile. */
    private fun pct(times: List<Long>, p: Double) = times.sorted()[maxOf(0, Math.ceil(p * times.size).toInt() - 1)]

    /**
     * Spec §6.4: 10 short replies with Plan 6's prompt against 10 with the full Chat memory from the fixture export
     * (pairs and Earlier at their defaults, a Profile, a Goal and 5 Notes), interleaved so neither reuses the other's
     * cached prefix beyond the system prompt. Each run puts a different stretch of the export on screen.
     */
    @Test fun chatContextLatency() = measureChatLatency(false)

    @Test fun waitingRepliesLatency() = measureChatLatency(true)

    private fun measureChatLatency(prefetch: Boolean) {
        assumeTrue("run with -Pllm", url.isNotEmpty())
        val lines = samLines()
        val key = ChatKey("com.whatsapp", "Sam")
        val memory = ChatMemory(MemStorage())
        memory.import(key, lines, "Me")
        memory.addCorrection(key, "He's my cousin")
        memory.setWritten(key, sam.profile.lines().last())
        memory.setGoal(key, sam.goal)
        listOf("Started a new job in October", "Mum's surgery on 3 Nov", "Supports Al Qadsiah", "Moving to a new flat in Salmiya", "Getting married next summer")
            .forEach { memory.addNote(key, it) }
        val today = ArrayList<Long>()
        val full = ArrayList<Long>()
        var sizes = ""
        for (run in -1 until RUNS) {
            // A screen of 8 messages ending on one of Sam's, further back each run.
            val end = lines.indices.reversed().filter { lines[it].sender != "Me" }[(run + 1) * 3]
            val shown = lines.subList(end - 7, end + 1)
            val text = "Sam\n" + shown.joinToString("\n") { it.text }
            val screen = shown.map { Message(if (it.sender == "Me") Side.ME else Side.THEM, "", it.text, it.time!!, false, Source.SCREEN) }
            val mine = shown.filter { it.sender == "Me" }.mapTo(HashSet()) { it.text }
            val before = lines.subList(0, end - 7).filter { it.sender == "Me" }.map { it.text }
            val plain = PromptContext("com.whatsapp", "Message", text, "", false, samples = before.takeLast(PICK_ALONE), mine = mine)
            val chat = memory.context(key, screen, text)!!
            val rich = plain.copy(samples = before.takeLast(PICK_WITH_PAIRS), chat = chat)
            val times = listOf(plain, rich).map { ctx ->
                val t0 = System.nanoTime()
                val out = if (prefetch && ctx.chat != null) {
                    val req = ReplyDrafts.request(ctx)
                    client.complete("", req.system, req.user, "replies",
                        maxTokens = ReplyDrafts.MAX_TOKENS, format = ReplyDrafts.SCHEMA)
                } else client.complete("", Prompt.system(ctx), Prompt.user(ctx, "reply"))
                assertTrue("$out", out is LlmResult.Text)
                ms(t0).also { println("${if (ctx.chat == null) "today" else "full "} run $run: ${(out as LlmResult.Text).text} in $it ms") }
            }
            if (run < 0) {
                sizes = "prompt chars: today ${Prompt.system(plain).length + Prompt.user(plain, "reply").length}, " +
                    "full ${Prompt.system(rich).length + Prompt.user(rich, "reply").length} (${chat.pairs.size} pairs, ${chat.earlier.size} earlier)"
                continue // warm-up
            }
            today += times[0]
            full += times[1]
        }
        println(sizes)
        println("service $url; ${if (prefetch) "waiting replies" else "chat context"}")
        println("today: p50 ${pct(today, 0.5)} ms, p90 ${pct(today, 0.9)} ms ${today.sorted()}")
        println("${if (prefetch) "prefetch" else "full"}: p50 ${pct(full, 0.5)} ms, p90 ${pct(full, 0.9)} ms ${full.sorted()}")
    }

    /**
     * Spec §5.2: a Profile call on a full History, cancelled after 2 s, must not hold a one-slot llama-server: a ✦ reply
     * right after it comes back in normal time.
     */
    @Test fun cancellingAProfileFreesTheServer() {
        assumeTrue("run with -Pllm (My server)", url.isNotEmpty() && url != "venice")
        val reply = PromptContext("com.whatsapp", "Message", "Sam\nare you coming tonight?", "", false)
        fun short(): Long {
            val t0 = System.nanoTime()
            val out = client.complete("", Prompt.system(reply), Prompt.user(reply, "reply saying yes, around 8"))
            assertTrue("$out", out is LlmResult.Text)
            return ms(t0)
        }
        val baseline = (1..3).map { short() }
        // A nonce first so no cached prefix shortens the call.
        val one = samHistory()
        val history = generateSequence { one }.take(ChatMemory.HISTORY_CHARS / one.length + 1).joinToString("\n").take(ChatMemory.HISTORY_CHARS)
        val user = ProfilePrompt.user("Sam ${System.nanoTime()}", emptyList(), "", history)
        val cancel = LlmCancel()
        var profile: LlmResult? = null
        var profileMs = 0L
        val t0 = System.nanoTime()
        val worker = Thread {
            profile = client.complete("", ProfilePrompt.SYSTEM, user, maxTokens = ProfilePrompt.MAX_TOKENS, readTimeoutMs = ProfilePrompt.TIMEOUT_MS, cancel = cancel)
            profileMs = ms(t0)
        }
        worker.start()
        Thread.sleep(2_000)
        cancel.cancel()
        worker.join()
        val after = short()
        println("history ${history.length} chars; baseline ✦ ${baseline} ms; Profile returned $profile at $profileMs ms; ✦ right after cancel $after ms")
        assertTrue("$profile", profile == LlmResult.Failed(LlmClient.CANCELLED))
        assertTrue("✦ after cancel took $after ms", after < 3_000)
    }

    @Test fun cancellingWaitingRepliesFreesTheServer() {
        assumeTrue("run with -Pllm (My server)", url.isNotEmpty() && url != "venice")
        fun short(): Long {
            val t0 = System.nanoTime()
            val out = client.complete("", Prompt.system(samScreen), Prompt.user(samScreen, "reply"))
            assertTrue("$out", out is LlmResult.Text)
            return ms(t0)
        }
        val baseline = (1..3).map { short() }
        // As in the Profile cancel test, avoid a cached prefix and keep a large call in flight.
        val one = samHistory()
        val history = generateSequence { one }.take(ChatMemory.HISTORY_CHARS / one.length + 1).joinToString("\n").take(ChatMemory.HISTORY_CHARS)
        val ctx = samScreen.copy(chat = sam.copy(title = "Sam ${System.nanoTime()}", earlier = history.lines()))
        val req = ReplyDrafts.request(ctx)
        val cancel = LlmCancel()
        var result: LlmResult? = null
        val worker = Thread {
            result = client.complete("", req.system, req.user, "replies",
                maxTokens = ReplyDrafts.MAX_TOKENS, format = ReplyDrafts.SCHEMA, cancel = cancel)
        }
        worker.start()
        Thread.sleep(1_000)
        cancel.cancel()
        worker.join()
        val after = short()
        println("history ${history.length} chars; baseline ✦ $baseline ms; prefetch returned $result; ✦ right after cancel $after ms")
        assertTrue("$result", result == LlmResult.Failed(LlmClient.CANCELLED))
        assertTrue("✦ after cancel took $after ms", after < 3_000)
    }

    private companion object {
        const val RUNS = 10
        /** KeyboardService's "My messages" counts: with pairs, and without. */
        const val PICK_WITH_PAIRS = 5
        const val PICK_ALONE = 15
    }
}
