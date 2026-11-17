package dev.yazin.keyboard.engine.voice

/** Turns a transcription reply into the text to insert, dropping what Whisper invents from silence or noise (§7.5). */
object Transcript {
    /** Whisper's own no-speech rule. */
    const val NO_SPEECH_PROB = 0.6
    const val NO_SPEECH_LOGPROB = -1.0
    /** A chunk that is only a stock phrase is dropped below this confidence (measured: speech ≈ −0.1, noise −0.56, silence −0.89). */
    const val PHRASE_LOGPROB = -0.4

    /** What Whisper says for silence and noise, normalized (hallucinations.txt has the sources). */
    private val PHRASES: Set<String> by lazy {
        val file = Transcript::class.java.getResourceAsStream("/dev/yazin/keyboard/engine/voice/hallucinations.txt")!!
        file.bufferedReader().useLines { lines -> lines.filterNot { it.startsWith('#') }.map(::normalize).filter { it.isNotEmpty() }.toSet() }
    }

    /** The text to insert (empty when nothing real was said), or null when the reply carries no transcription at all. */
    fun clean(json: String): String? {
        val root = Json.parse(json) as? Map<*, *> ?: return null
        val segments = (root["segments"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?: return (root["text"] as? String)?.trim()
        val kept = segments.filterNot { it.num("no_speech_prob") > NO_SPEECH_PROB && it.num("avg_logprob") < NO_SPEECH_LOGPROB }
        val text = kept.joinToString("") { it["text"] as? String ?: "" }.trim()
        val worst = kept.minOfOrNull { it.num("avg_logprob") } ?: 0.0
        return if (normalize(text) in PHRASES && worst < PHRASE_LOGPROB) "" else text
    }

    /** [text] is, whole, one of the phrases Whisper says for silence and noise. */
    fun stock(text: String): Boolean = normalize(text) in PHRASES

    /** How sure Whisper was of the reply: its segments' mean `avg_logprob`, or −∞ without segments. */
    fun confidence(json: String): Double {
        val segments = ((Json.parse(json) as? Map<*, *>)?.get("segments") as? List<*>)?.mapNotNull { it as? Map<*, *> }
        return if (segments.isNullOrEmpty()) Double.NEGATIVE_INFINITY else segments.map { it.num("avg_logprob") }.average()
    }

    private fun Map<*, *>.num(key: String) = this[key] as? Double ?: 0.0

    /** Lowercase words: marks go (Arabic harakat, a lowercased İ's dot), any other symbol splits words, so "I'm" is "i m". */
    private fun normalize(s: String) = buildString {
        for (ch in s.lowercase()) {
            when {
                ch.category == CharCategory.NON_SPACING_MARK -> {}
                ch.isLetterOrDigit() -> append(ch)
                else -> append(' ')
            }
        }
    }.trim().replace(Regex("\\s+"), " ")
}
