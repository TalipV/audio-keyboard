package dev.yazin.keyboard.engine.emoji

/** What an emoji is called in one language (Unicode CLDR annotations): its [name] and search [keywords]. */
data class EmojiWords(val name: String, val keywords: List<String>)

/**
 * CLDR's emoji annotations → the search asset, one file per language. Keeps only the panel's emoji, keyed by their
 * base as the catalog has it, and drops the keyword that repeats the name.
 */
object Annotations {
    private val LINE = Regex("""<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>""")
    /** CLDR's "same as the parent locale" placeholder. */
    private const val INHERITED = "↑↑↑"
    private const val VS16 = "\uFE0F"

    /** One annotations file (annotations/xx.xml or annotationsDerived/xx.xml): each emoji, without U+FE0F, to its words. */
    fun parse(xml: Sequence<String>): Map<String, EmojiWords> {
        val names = HashMap<String, String>()
        val keywords = HashMap<String, List<String>>()
        for (line in xml) {
            val m = LINE.find(line) ?: continue
            val text = unescape(m.groupValues[3]).trim()
            if (text.isEmpty() || text == INHERITED) continue
            val cp = unescape(m.groupValues[1]).replace(VS16, "")
            if (m.groupValues[2].isNotEmpty()) names[cp] = text
            else keywords[cp] = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return (names.keys + keywords.keys).associateWith { EmojiWords(names[it].orEmpty(), keywords[it].orEmpty()) }
    }

    /** The asset: "<base><TAB><name><TAB><keyword>|<keyword>…" in catalog order; [sources] are looked up first to last. */
    fun write(groups: List<EmojiGroup>, sources: List<Map<String, EmojiWords>>, out: Appendable) {
        for (g in groups) for (e in g.emoji) {
            val key = e.base.replace(VS16, "")
            val w = sources.firstNotNullOfOrNull { it[key] } ?: continue
            out.append(e.base).append('\t').append(w.name).append('\t')
            w.keywords.filter { !it.equals(w.name, ignoreCase = true) }.joinTo(out, "|")
            out.append('\n')
        }
    }

    fun read(lines: Sequence<String>): Map<String, EmojiWords> {
        val out = HashMap<String, EmojiWords>()
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 3) continue
            out[f[0]] = EmojiWords(f[1], if (f[2].isEmpty()) emptyList() else f[2].split('|'))
        }
        return out
    }

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
