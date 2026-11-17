package dev.yazin.keyboard.engine.emoji

/** Recently used emoji, newest first, and the skin tone chosen for each emoji (spec §8). Main thread. */
class EmojiPrefs(private val maxRecents: Int = MAX_RECENTS) {
    private val recents = ArrayList<Pair<String, String>>() // base to what was typed
    private val tones = HashMap<String, String>()

    var dirty = false
        private set

    /** The recents row, newest first: each emoji's base and what was typed (with its tone). */
    val recent: List<Pair<String, String>> get() = recents

    /** What tapping [e] types: the tone chosen for it before, if the device still offers it, else the base. */
    fun forTap(e: Emoji): String = tones[e.base]?.takeIf { it in e.variants } ?: e.base

    /**
     * [typed], the [base] or one of its tones, was entered. Typing the base itself forgets the tone. Nothing is kept
     * when [remember] is false: password and incognito fields (spec §4).
     */
    fun used(base: String, typed: String, remember: Boolean = true) {
        if (!remember) return
        if (typed != base) tones[base] = typed else tones.remove(base)
        recents.removeAll { it.first == base }
        recents.add(0, base to typed)
        while (recents.size > maxRecents) recents.removeAt(recents.lastIndex)
        dirty = true
    }

    fun write(out: Appendable) {
        for ((b, t) in recents) out.append("r\t").append(b).append('\t').append(t).append('\n')
        for ((b, t) in tones) out.append("t\t").append(b).append('\t').append(t).append('\n')
        dirty = false
    }

    fun read(lines: Sequence<String>) {
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 3) continue // a damaged line
            when (f[0]) {
                "r" -> if (recents.size < maxRecents) recents += f[1] to f[2]
                "t" -> tones[f[1]] = f[2]
            }
        }
    }

    companion object {
        /** Three rows of nine. */
        const val MAX_RECENTS = 27
    }
}
