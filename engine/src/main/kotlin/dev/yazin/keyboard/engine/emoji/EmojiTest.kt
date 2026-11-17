package dev.yazin.keyboard.engine.emoji

/** One emoji in the panel: [base] and its skin-tone [variants] (empty when it has none). */
data class Emoji(val base: String, val variants: List<String> = emptyList())

/** A panel section: one of Unicode's emoji groups (spec §8). */
data class EmojiGroup(val name: String, val emoji: List<Emoji>)

/**
 * Unicode's emoji-test.txt → the panel catalog (spec §8.1). Keeps fully-qualified emoji in Unicode order, drops the
 * "Component" group, and folds skin-tone sequences into their base emoji as variants.
 */
object EmojiTest {
    private val TONES = 0x1F3FB..0x1F3FF
    private const val GROUP = "group\t"

    fun parse(lines: Sequence<String>): List<EmojiGroup> {
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        var group = ""
        var last: String? = null
        for (line in lines) {
            if (line.startsWith("# group:")) {
                group = line.substringAfter(":").trim()
                last = null
                continue
            }
            if (line.isBlank() || line.startsWith("#") || group == "Component") continue
            val fields = line.split(';', '#')
            if (fields.size < 2 || fields[1].trim() != "fully-qualified") continue
            val emoji = fields[0].trim().split(' ').filter { it.isNotEmpty() }.joinToString("") { String(Character.toChars(it.toInt(16))) }
            val inGroup = groups.getOrPut(group) { LinkedHashMap() }
            val base = stripTones(emoji)
            if (base == emoji) {
                inGroup.getOrPut(emoji) { ArrayList() }
                last = emoji
            } else {
                // Tones follow their base in the file; mixed-tone couples (👩🏻‍🤝‍👨🏼) have no untoned form, so they join it.
                (inGroup[base] ?: inGroup[withVs16(base)] ?: last?.let { inGroup[it] })?.add(emoji)
            }
        }
        return groups.map { (name, byBase) -> EmojiGroup(name, byBase.map { (b, v) -> Emoji(b, v) }) }
    }

    /** Each fully-qualified emoji's English name, without U+FE0F as in [Annotations]: the words for those CLDR lacks. */
    fun names(lines: Sequence<String>): Map<String, EmojiWords> {
        val out = HashMap<String, EmojiWords>()
        for (line in lines) {
            if (line.startsWith("#")) continue
            val fields = line.split(';', '#')
            if (fields.size < 3 || fields[1].trim() != "fully-qualified") continue
            val emoji = fields[0].trim().split(' ').filter { it.isNotEmpty() && it != "FE0F" }.joinToString("") { String(Character.toChars(it.toInt(16))) }
            // "# 😀 E1.0 grinning face"; the comment itself may hold a "#" (keycap: #).
            val name = line.substringAfter('#').trim().split(' ', limit = 3).getOrNull(2) ?: continue
            out[emoji] = EmojiWords(name, emptyList())
        }
        return out
    }

    /** [s] without skin-tone modifiers. */
    fun stripTones(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp !in TONES) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** "☝" → "☝️": some bases carry the emoji presentation selector that their toned forms drop. */
    private fun withVs16(s: String): String {
        val first = Character.charCount(s.codePointAt(0))
        return s.substring(0, first) + "️" + s.substring(first)
    }

    /** The asset: "group<TAB><name>" lines (no emoji contains a tab; "#️⃣" starts with "#"), then one emoji per line: its base, then its variants, space-separated. */
    fun write(groups: List<EmojiGroup>, out: Appendable) {
        for (g in groups) {
            out.append(GROUP).append(g.name).append('\n')
            for (e in g.emoji) {
                out.append(e.base)
                for (v in e.variants) out.append(' ').append(v)
                out.append('\n')
            }
        }
    }

    fun read(lines: Sequence<String>): List<EmojiGroup> {
        val out = ArrayList<EmojiGroup>()
        var name: String? = null
        var list = ArrayList<Emoji>()
        for (line in lines) {
            when {
                line.startsWith(GROUP) -> {
                    name?.let { out += EmojiGroup(it, list) }
                    name = line.substring(GROUP.length)
                    list = ArrayList()
                }
                line.isNotBlank() -> line.split(' ').let { list += Emoji(it[0], it.drop(1)) }
            }
        }
        name?.let { out += EmojiGroup(it, list) }
        return out
    }

    /** Only what the device can draw (spec §8: `Paint.hasGlyph`): never an empty box. */
    fun supported(groups: List<EmojiGroup>, canDraw: (String) -> Boolean): List<EmojiGroup> = groups.mapNotNull { g ->
        val kept = g.emoji.filter { canDraw(it.base) }.map { e -> e.copy(variants = e.variants.filter(canDraw)) }
        if (kept.isEmpty()) null else EmojiGroup(g.name, kept)
    }
}
