package dev.yazin.keyboard.engine.clip

import dev.yazin.keyboard.engine.Tsv

/**
 * One copied text or image (spec §8). [time] is when it was copied, in ms. An image clip has an empty [text] and the
 * name of its file in [image] (the app keeps the files; see [ClipStore.onDropImage]).
 */
data class Clip(val text: String, val time: Long, val pinned: Boolean = false, val image: String? = null) {
    /** What identifies the clip to pin, delete or paste it: its text, or its image file. */
    val key: String get() = image?.let { IMAGE_KEY + it } ?: text

    companion object {
        const val IMAGE_KEY = "image:"
    }
}

/**
 * Clipboard history (spec §8, §8.1): text and image clips, newest first; unpinned ones last [lifetimeMs] (an hour by
 * default), pinned ones until deleted. Clips Android marks sensitive are never kept. Main thread.
 */
class ClipStore(private val now: () -> Long = System::currentTimeMillis) {
    private val clips = ArrayList<Clip>()
    private var chipDismissed: Clip? = null

    /** How long unpinned clips are kept; [FOREVER] keeps them until the count limits push them out. */
    var lifetimeMs: Long = LIFETIME_MS

    /** Called with an image clip's file name once the clip is gone (expired, pushed out, deleted, cleared). */
    var onDropImage: (String) -> Unit = {}

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
        add(Clip(t, now()))
        return true
    }

    /** An image was copied and saved as [name] (named by its content, so the same image copied again is one clip). */
    fun offerImage(name: String, sensitive: Boolean): Boolean {
        if (sensitive || name.isBlank()) {
            if (name.isNotBlank() && clips.none { it.image == name }) onDropImage(name)
            return false
        }
        add(Clip("", now(), image = name))
        return true
    }

    private fun add(clip: Clip) {
        val pinned = clips.any { it.key == clip.key && it.pinned }
        clips.removeAll { it.key == clip.key } // the same file stays: it is the new clip's too
        clips.add(0, clip.copy(pinned = pinned))
        var texts = 0
        var images = 0
        drop { !it.pinned && if (it.image == null) ++texts > MAX_UNPINNED else ++images > MAX_UNPINNED_IMAGES }
        dirty = true
    }

    fun pin(key: String, pinned: Boolean) {
        val i = clips.indexOfFirst { it.key == key }
        if (i < 0) return
        clips[i] = clips[i].copy(pinned = pinned)
        dirty = true
    }

    fun delete(key: String) {
        if (drop { it.key == key }) dirty = true
    }

    fun clear() {
        drop { true }
        dirty = true
    }

    /** Gboard's strip chip: the newest clip, if text, for a minute after it was copied, until pasted or dismissed. */
    fun chip(): Clip? = clips.firstOrNull()?.takeIf { it.image == null && now() - it.time < CHIP_MS && it != chipDismissed }

    fun dismissChip() {
        chipDismissed = clips.firstOrNull()
    }

    /** How long [chip] keeps showing, so the strip can refresh; null when there is none. */
    fun chipRemainingMs(): Long? = chip()?.let { CHIP_MS - (now() - it.time) }

    fun write(out: Appendable) {
        expire() // an expired clip leaves the disk too, not just the panel
        for (c in clips) {
            val kind = if (c.image != null) "i" else "c"
            out.append(kind).append('\t').append(c.time.toString()).append('\t').append(if (c.pinned) "1" else "0").append('\t')
                .append(Tsv.escape(c.image ?: c.text)).append('\n')
        }
        dirty = false
    }

    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 4 || (f[0] != "c" && f[0] != "i")) continue // a damaged line
            val time = f[1].toLongOrNull() ?: continue
            val value = Tsv.unescape(f[3])
            clips += if (f[0] == "i") Clip("", time, f[2] == "1", image = value) else Clip(value, time, f[2] == "1")
        }
        expire()
    }

    private fun expire() {
        if (lifetimeMs == FOREVER) return
        if (drop { !it.pinned && now() - it.time >= lifetimeMs }) dirty = true
    }

    /** Removes the clips matching [what], telling [onDropImage] about their files. */
    private fun drop(what: (Clip) -> Boolean): Boolean {
        val gone = clips.filter(what)
        if (gone.isEmpty()) return false
        clips.removeAll(gone.toSet())
        gone.mapNotNull { it.image }.forEach(onDropImage)
        return true
    }

    companion object {
        const val LIFETIME_MS = 3_600_000L
        const val FOREVER = Long.MAX_VALUE
        const val CHIP_MS = 60_000L
        const val MAX_UNPINNED = 50
        /** Images take disk space (a screenshot is a few MB), so fewer are kept. */
        const val MAX_UNPINNED_IMAGES = 20
        /** Longer clips aren't kept; they still paste from the system clipboard. */
        const val MAX_CHARS = 100_000
    }
}
