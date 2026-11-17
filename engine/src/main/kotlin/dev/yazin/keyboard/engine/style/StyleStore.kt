package dev.yazin.keyboard.engine.style

import dev.yazin.keyboard.engine.Tsv

/** One message the user sent (writing-style spec §2). [draft] is the ✦ answer they edited into [text], if any. */
data class Sample(val text: String, val app: String, val chat: String, val time: Long, val draft: String? = null)

/**
 * How the user writes, for ✦ (writing-style spec §2–3): the messages they sent. Thread-safe: the keyboard adds from a
 * worker, ✦ picks on its LLM thread, settings import and clear on the main thread.
 */
class StyleStore(private val now: () -> Long = System::currentTimeMillis) {
    private val samples = ArrayList<Sample>()
    private val answers = ArrayDeque<String>()

    @Volatile var dirty = false
        private set

    val size: Int @Synchronized get() = samples.size

    /** The user sent [text] in [chat] of [app]. Returns whether it was kept. */
    @Synchronized
    fun add(text: String, app: String, chat: String, draft: String? = null): Boolean {
        val t = text.trim()
        val d = draft?.trim()?.takeIf { it.isNotEmpty() }
        if (!keepable(t) || t == d || t in answers) return false
        samples.removeAll { it.text == t && it.app == app && it.chat == chat }
        samples += Sample(t, app, chat, now(), d)
        trim()
        dirty = true
        return true
    }

    /**
     * An exported chat's messages from the user, oldest first. Only the newest [MAX_IMPORT] are kept, and they count as
     * older than every sample already here. Returns how many were added.
     */
    @Synchronized
    fun import(texts: List<String>, app: String, chat: String): Int {
        val have = samples.mapTo(HashSet()) { Triple(it.text, it.app, it.chat) }
        val kept = texts.map { it.trim() }.filter { keepable(it) && have.add(Triple(it, app, chat)) }.takeLast(MAX_IMPORT)
        // ponytail: times just before the oldest sample, not the export's dates (d/m vs m/d is ambiguous there)
        val start = (samples.minOfOrNull { it.time } ?: now()) - kept.size
        kept.forEachIndexed { i, t -> samples += Sample(t, app, chat, start + i) }
        if (kept.isNotEmpty()) {
            trim()
            dirty = true
        }
        return kept.size
    }

    /**
     * Up to [n] samples for a prompt: this chat's first (at most two thirds of [n]), then this app's, then any. Each group
     * is ranked by words shared with [context] (the screen), newest first on ties.
     */
    @Synchronized
    fun pick(app: String, chat: String, context: String, n: Int = PICK): List<Sample> {
        val wanted = words(context)
        val chosen = LinkedHashMap<String, Sample>()
        fun fill(pool: List<Sample>, limit: Int) {
            val ranked = pool.map { s -> s to words(s.text).count { it in wanted } }
                .sortedWith(compareByDescending<Pair<Sample, Int>> { it.second }.thenByDescending { it.first.time })
            for ((s, _) in ranked) {
                if (chosen.size >= limit) return
                chosen.putIfAbsent(s.text, s)
            }
        }
        if (chat.isNotEmpty()) fill(samples.filter { it.app == app && it.chat == chat }, n * 2 / 3)
        fill(samples.filter { it.app == app }, n)
        fill(samples, n)
        return chosen.values.toList()
    }

    /** Up to [n] ✦ drafts the user changed before sending: this chat's first, then this app's, newest first. */
    @Synchronized
    fun edits(app: String, chat: String, n: Int = EDITS): List<Sample> = samples.filter { it.draft != null }
        .sortedWith(
            compareByDescending<Sample> { chat.isNotEmpty() && it.app == app && it.chat == chat }
                .thenByDescending { it.app == app }.thenByDescending { it.time },
        )
        .take(n)

    /**
     * What the user sent in this chat, as screen lines would show it (whole messages, whitespace collapsed as ScreenText
     * does, and each line of them), for marking them "Me:". Short lines are left out: someone else's "haha" is not the
     * user's. Empty without a chat title.
     */
    @Synchronized
    fun mine(app: String, chat: String): Set<String> {
        if (chat.isEmpty()) return emptySet()
        return samples.filter { it.app == app && it.chat == chat }
            .flatMapTo(HashSet()) { s -> (s.text.lines() + s.text).map { it.replace(SPACE, " ").trim() }.filter { it.length >= MIN_MARK } }
    }

    /** A copy of every sample, for seeding Chat memory. */
    @Synchronized
    fun samples(): List<Sample> = samples.toList()

    /** ✦ just put [text] in a field: sent unchanged it is the model's voice, not the user's, even if its draft was lost. */
    @Synchronized
    fun answered(text: String) {
        answers.addLast(text.trim())
        if (answers.size > RECENT_ANSWERS) answers.removeFirst()
    }

    @Synchronized
    fun clear() {
        samples.clear()
        dirty = true
    }

    /** Deletes [app]'s samples from [chat] (a Chat forgotten, chat memory spec §8). Titles compare as [title] cleans them. */
    @Synchronized
    fun forget(app: String, chat: String) {
        val c = title(chat)
        if (samples.removeAll { it.app == app && title(it.chat) == c }) dirty = true
    }

    /** Files [app]'s samples from [from] under [into] (a Chat merged); a text sent in both is kept once, the newest. */
    @Synchronized
    fun move(app: String, from: String, into: String) {
        val f = title(from)
        val i = title(into)
        if (f == i || samples.none { it.app == app && title(it.chat) == f }) return
        samples.replaceAll { if (it.app == app && title(it.chat) in setOf(f, i)) it.copy(chat = into) else it }
        val newest = HashMap<String, Sample>()
        for (s in samples) if (s.app == app && s.chat == into) newest.merge(s.text, s) { a, b -> if (b.time > a.time) b else a }
        samples.removeAll { it.app == app && it.chat == into && newest[it.text] !== it }
        dirty = true
    }

    @Synchronized
    fun write(out: Appendable) {
        for (s in samples) {
            out.append("s\t").append(s.time.toString()).append('\t').append(Tsv.escape(s.app)).append('\t')
                .append(Tsv.escape(s.chat)).append('\t').append(Tsv.escape(s.draft.orEmpty())).append('\t')
                .append(Tsv.escape(s.text)).append('\n')
        }
        dirty = false
    }

    @Synchronized
    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 6 || f[0] != "s") continue // a damaged line
            val time = f[1].toLongOrNull() ?: continue
            samples += Sample(Tsv.unescape(f[5]), Tsv.unescape(f[2]), Tsv.unescape(f[3]), time, Tsv.unescape(f[4]).ifEmpty { null })
        }
        trim()
    }

    private fun keepable(t: String) = t.isNotEmpty() && t.length <= MAX_CHARS && !DIGITS.containsMatchIn(t)

    private fun trim() {
        if (samples.size <= MAX_SAMPLES) return
        samples.sortBy { it.time }
        samples.subList(0, samples.size - MAX_SAMPLES).clear()
    }

    companion object {
        const val MAX_SAMPLES = 3_000
        const val MAX_IMPORT = 1_500
        const val MAX_CHARS = 2_000
        const val PICK = 15
        const val EDITS = 3
        private const val RECENT_ANSWERS = 20
        private const val MIN_MARK = 8
        /** Codes, card and phone numbers ("4111 1111…", "050-123-4567"), in any script's digits. */
        internal val DIGITS = Regex("\\p{Nd}(?:[ .\\-]?\\p{Nd}){5,}")
        private val SPACE = Regex("\\s+")
        private val TITLE_SPACE = Regex("[\\s\u00A0\u202F]+")
        private val WORD = Regex("[\\p{L}\\p{M}']+")

        /** A chat title as Chat memory names it: without WhatsApp's invisible marks, whitespace collapsed. */
        fun title(raw: String): String = WhatsAppExport.INVISIBLE.replace(raw, "").replace(TITLE_SPACE, " ").trim()

        /** Lowercase words of 3+ letters, any script. */
        fun words(s: String): Set<String> = WORD.findAll(s.lowercase()).map { it.value }.filterTo(HashSet()) { it.length >= 3 }
    }
}
