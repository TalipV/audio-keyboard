package dev.yazin.keyboard.engine.style

import dev.yazin.keyboard.engine.chat.ChatDates
import java.time.ZoneId

/** One message of a chat export. */
data class ChatLine(val sender: String, val text: String, val time: Long? = null)

/** Reads WhatsApp's "Export chat" text, Android and iOS formats (writing-style spec §2). */
object WhatsAppExport {
    /** Zero-width spaces, direction marks and BOMs WhatsApp sprinkles into exports, titles and screen text. */
    internal val INVISIBLE = Regex("[\u200B\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    /** The no-break spaces before "PM". */
    private val SPACES = Regex("[  ]")
    /** "15/10/2026, 15:37 - " (Android) or "[15/10/2026, 3:37:12 PM] " (iOS); any date order, am/pm optional. */
    private val STAMP = Regex("""^\[?\d{1,4}[./-]\d{1,2}[./-]\d{1,4},?\s+\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[AaPp]\.?\s?[Mm]\.?)?\]?\s*(?:-\s*)?""")
    private val SENDER = Regex("""^([^:]{1,60}):\s?(.*)$""")
    /** Bodies that aren't words the user wrote. */
    private val SKIP = Regex("""^(<.+>|.+ omitted|null|This message was deleted\.?|You deleted this message\.?|Missed (voice|video) call.*|Waiting for this message.*|location: .*)$""")
    private const val EDITED = "<This message was edited>"
    private val COPY_SUFFIX = Regex("""\s*\(\d+\)$""")

    fun parse(
        lines: Sequence<String>,
        fallback: ChatDates.Order = ChatDates.Order.DMY,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ChatLine> {
        // Each message with its stamp; every stamp, system lines' too, goes into deciding the date order.
        val out = ArrayList<Pair<ChatLine, String>>()
        val stamps = ArrayList<String>()
        var sender: String? = null
        var at = ""
        val text = StringBuilder()
        fun flush() {
            val s = sender
            val t = text.toString().trim().removeSuffix(EDITED).trim()
            if (s != null && t.isNotEmpty() && !SKIP.matches(t)) out += ChatLine(s, t) to at
            sender = null
            text.setLength(0)
        }
        for (raw in lines) {
            val line = SPACES.replace(INVISIBLE.replace(raw, ""), " ")
            val stamp = STAMP.find(line)
            if (stamp == null) {
                if (sender != null) text.append('\n').append(line)
                continue
            }
            flush()
            stamps += stamp.value
            // No "Name:" after the stamp: a system line ("Messages and calls are end-to-end encrypted…").
            val m = SENDER.find(line.substring(stamp.range.last + 1)) ?: continue
            sender = m.groupValues[1].trim()
            at = stamp.value
            text.append(m.groupValues[2])
        }
        flush()
        val order = ChatDates.order(stamps, fallback)
        return out.map { (line, stamp) -> line.copy(time = ChatDates.exportTime(stamp, order, zone)) }
    }

    /** The chat's title from the export's file name: "WhatsApp Chat with Nora.txt" → "Nora"; "" when it has none. */
    fun title(fileName: String): String {
        val base = COPY_SUFFIX.replace(fileName.trim().removeSuffix(".txt").removeSuffix(".zip"), "")
        for (prefix in listOf("WhatsApp Chat with ", "WhatsApp Chat - ")) {
            if (base.startsWith(prefix, ignoreCase = true)) return base.substring(prefix.length).trim()
        }
        return ""
    }

    /** Who wrote in [chat], most messages first, with [first] (the sender remembered as the user) at the top. */
    fun senders(chat: List<ChatLine>, first: String = ""): List<String> =
        chat.groupingBy { it.sender }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.sortedByDescending { it == first }
}
