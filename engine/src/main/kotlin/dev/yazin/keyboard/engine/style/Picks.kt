package dev.yazin.keyboard.engine.style

import dev.yazin.keyboard.engine.Tsv

enum class Verdict { PICKED, SENT, EDITED, IGNORED }

data class Pick(
    val text: String, val verdict: Verdict, val app: String, val chat: String,
    val time: Long, val instead: String? = null,
)

/** Outcomes of drafts shown by ✦. All access is synchronized, like [StyleStore]. */
class Picks(private val now: () -> Long = System::currentTimeMillis) {
    private val picks = ArrayList<Pick>()

    @Volatile var dirty = false
        private set

    val size: Int @Synchronized get() = picks.size

    @Synchronized
    fun sent(sent: String, app: String, chat: String, draft: String?, shown: List<String>) {
        when {
            draft != null && sent == draft -> add(Pick(draft, if (draft in shown) Verdict.PICKED else Verdict.SENT, app, chat, now()))
            draft != null -> add(Pick(draft, Verdict.EDITED, app, chat, now(), sent))
            else -> shown.forEach { add(Pick(it, Verdict.IGNORED, app, chat, now(), sent)) }
        }
    }

    /** This chat first, then positives from anywhere; never another chat's ignored drafts. Titles compare cleaned, as merge rewrites them. */
    @Synchronized
    fun pick(app: String, chat: String, n: Int = PROMPT): List<Pick> {
        val eligible = picks.filter { it.verdict != Verdict.EDITED }
        val c = StyleStore.title(chat)
        // Without a Chat name nothing is local: a titleless verdict can come from any chat. Positives stay global even then.
        val local = if (c.isEmpty()) emptyList() else eligible.filter { it.app == app && StyleStore.title(it.chat) == c }
            .sortedByDescending { it.time }
        val fallback = eligible.filter { it.verdict != Verdict.IGNORED && (c.isEmpty() || it.app != app || StyleStore.title(it.chat) != c) }
            .sortedByDescending { it.time }
        return (local + fallback).take(n)
    }

    @Synchronized
    fun clear() {
        picks.clear()
        dirty = true
    }

    @Synchronized
    fun forget(app: String, chat: String) {
        val c = StyleStore.title(chat)
        if (picks.removeAll { it.app == app && StyleStore.title(it.chat) == c }) dirty = true
    }

    @Synchronized
    fun move(app: String, from: String, into: String) {
        val f = StyleStore.title(from)
        val i = StyleStore.title(into)
        if (f == i || picks.none { it.app == app && StyleStore.title(it.chat) == f }) return
        val moved = picks.map { if (it.app == app && StyleStore.title(it.chat) in setOf(f, i)) it.copy(chat = into) else it }
        picks.clear()
        moved.sortedBy { it.time }.forEach { keep(it) }
        dirty = true
    }

    @Synchronized
    fun write(out: Appendable) {
        for (p in picks) {
            out.append("p\t").append(p.time.toString()).append('\t').append(Tsv.escape(p.app)).append('\t')
                .append(Tsv.escape(p.chat)).append('\t').append(p.verdict.name).append('\t')
                .append(Tsv.escape(p.instead.orEmpty())).append('\t').append(Tsv.escape(p.text)).append('\n')
        }
        dirty = false
    }

    @Synchronized
    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 7 || f[0] != "p") continue
            val time = f[1].toLongOrNull() ?: continue
            val verdict = Verdict.entries.find { it.name == f[4] } ?: continue
            val p = Pick(Tsv.unescape(f[6]).trim(), verdict, Tsv.unescape(f[2]), Tsv.unescape(f[3]), time,
                Tsv.unescape(f[5]).ifEmpty { null }?.trim())
            if (keepable(p.text) && (p.instead == null || keepable(p.instead))) keep(p)
        }
        trim()
    }

    private fun add(p: Pick) {
        val clean = p.copy(text = p.text.trim(), instead = p.instead?.trim())
        if (!keepable(clean.text) || (clean.instead != null && !keepable(clean.instead))) return
        keep(clean)
        trim()
        dirty = true
    }

    private fun keep(p: Pick) {
        val old = picks.find { it.text == p.text && it.app == p.app && it.chat == p.chat && it.verdict == p.verdict }
        if (old != null && old.time > p.time) return
        if (old != null) picks.remove(old)
        picks += p
    }

    private fun keepable(text: String) = text.isNotBlank() && text.length <= MAX_CHARS && !StyleStore.DIGITS.containsMatchIn(text)

    private fun trim() {
        if (picks.size <= MAX_PICKS) return
        picks.sortBy { it.time }
        picks.subList(0, picks.size - MAX_PICKS).clear()
    }

    companion object {
        const val MAX_PICKS = 600
        const val PROMPT = 6
        const val MAX_CHARS = 300
    }
}
