package dev.yazin.keyboard.engine.clip

import dev.yazin.keyboard.engine.Tsv

/** One copied text (spec §8). [time] is when it was copied, in ms. */
data class Clip(val text: String, val time: Long, val pinned: Boolean = false)

/**
 * Clipboard history (spec §8, §8.1): text clips, newest first; unpinned ones last an hour, pinned ones until deleted.
 * Clips Android marks sensitive are never kept. Main thread.
 */
class ClipStore(private val now: () -> Long = System::currentTimeMillis) {
    private val clips = ArrayList<Clip>()
    private var chipDismissed: Clip? = null

    var dirty = false
        private set

    /** Pinned first, then the rest, each newest first. Expired clips go first. */
    fun clips(): List<Clip> {
        expire()
        return clips.filter { it.pinned } + clips.filterNot { it.pinned }
    }

    /** Something was copied. Returns whether it was kept. */
    fun offer(text: CharSequence?, sensitive: Boolean): Boolean {
        val t = text?.toString() ?: return false
        if (sensitive || t.isBlank() || t.length > MAX_CHARS) return false
        val pinned = clips.any { it.text == t && it.pinned }
        clips.removeAll { it.text == t }
        clips.add(0, Clip(t, now(), pinned))
        var unpinned = 0
        clips.removeAll { !it.pinned && ++unpinned > MAX_UNPINNED }
        dirty = true
        return true
    }

    fun pin(text: String, pinned: Boolean) {
        val i = clips.indexOfFirst { it.text == text }
        if (i < 0) return
        clips[i] = clips[i].copy(pinned = pinned)
        dirty = true
    }

    fun delete(text: String) {
        if (clips.removeAll { it.text == text }) dirty = true
    }

    fun clear() {
        clips.clear()
        dirty = true
    }

    /** Gboard's strip chip: the newest clip for a minute after it was copied, until it's pasted or dismissed. */
    fun chip(): Clip? = clips.firstOrNull()?.takeIf { now() - it.time < CHIP_MS && it != chipDismissed }

    fun dismissChip() {
        chipDismissed = clips.firstOrNull()
    }

    /** How long [chip] keeps showing, so the strip can refresh; null when there is none. */
    fun chipRemainingMs(): Long? = chip()?.let { CHIP_MS - (now() - it.time) }

    fun write(out: Appendable) {
        expire() // an hour-old clip leaves the disk too, not just the panel
        for (c in clips) {
            out.append("c\t").append(c.time.toString()).append('\t').append(if (c.pinned) "1" else "0").append('\t').append(Tsv.escape(c.text)).append('\n')
        }
        dirty = false
    }

    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 4 || f[0] != "c") continue // a damaged line
            val time = f[1].toLongOrNull() ?: continue
            clips += Clip(Tsv.unescape(f[3]), time, f[2] == "1")
        }
        expire()
    }

    private fun expire() {
        if (clips.removeAll { !it.pinned && now() - it.time >= LIFETIME_MS }) dirty = true
    }

    companion object {
        const val LIFETIME_MS = 3_600_000L
        const val CHIP_MS = 60_000L
        const val MAX_UNPINNED = 30
        /** Longer clips aren't kept; they still paste from the system clipboard. */
        const val MAX_CHARS = 100_000
    }
}
