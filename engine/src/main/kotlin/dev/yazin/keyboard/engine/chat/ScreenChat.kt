package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.llm.ScreenNode
import dev.yazin.keyboard.engine.llm.ScreenText
import dev.yazin.keyboard.engine.style.WhatsAppExport
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reads a chat screen's accessibility tree into messages with their side, sender and time (chat-memory spec §2). Only
 * WhatsApp and Google Messages: elsewhere geometry alone misattributes (Slack puts everyone on the left, quoted replies
 * repeat the owner's text in others' bubbles, wide incoming bubbles cross the centre).
 */
object ScreenChat {
    private const val MAX_NODES = 2_000
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private const val MESSAGES = "com.google.android.apps.messaging"
    private val SPACE = Regex("\\s+")
    private val SAID = Regex("""^(.+?) said\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val TAIL_TIME = Regex("""(\d{1,2}:\d{2}(?:[\s\u00A0\u202F]?[AaPp]\.?\s?[Mm]\.?)?)$""")

    /**
     * The messages on [root], oldest first, with `source = SCREEN`; null for other apps, when there are none, or when
     * the sides contradict. The layout direction is the screen's: WhatsApp's is RTL when its contact name sits right of
     * the window's centre, and Google Messages is only read in English. [rtl], the keyboard's language direction, is the
     * fallback when WhatsApp shows no contact name. [width] is the window's. A time is the nearest divider above plus
     * the bubble's time, otherwise [now], guessed.
     */
    fun read(root: ScreenNode, width: Int, app: String, rtl: Boolean, now: Long, zone: ZoneId): List<Message>? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val messages = when (app) {
            in WHATSAPP -> {
                val name = visible(root).firstOrNull { it.id == "conversation_contact_name" }
                Screen(width, name?.let { (it.left + it.right) / 2.0 > width / 2.0 } ?: rtl, now, zone, today).whatsApp(root)
            }
            MESSAGES -> Screen(width, false, now, zone, today).googleMessages(root)
            else -> null
        }
        return messages?.takeIf { it.isNotEmpty() }
    }

    private class Screen(width: Int, val rtl: Boolean, val now: Long, val zone: ZoneId, val today: LocalDate) {
        private val mid = width / 2.0

        private fun centre(n: ScreenNode) = (n.left + n.right) / 2.0

        /** Whether [n] sits past the window's centre toward the leading edge: left in LTR, right with [rtl]. */
        fun leading(n: ScreenNode) = if (rtl) centre(n) > mid else centre(n) < mid

        fun message(side: Side, sender: String, text: String, day: LocalDate?, time: LocalTime?): Message {
            val at = if (day != null && time != null) LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli() else null
            return Message(side, if (side == Side.ME) "" else sender, text, at ?: now, at == null, Source.SCREEN)
        }

        /**
         * A bubble is a `main_layout` with a visible `date` ending above its bottom (a bubble cut above that row hides
         * whose it is); it is the owner's when it holds a `status`. Its text is `message_text`, or the link preview's
         * title or url in a link or document bubble, which has none. A group's sender is `name_in_group_tv`, shown on
         * the first bubble of a run. Uncaptioned media and reactions aren't read.
         */
        fun whatsApp(root: ScreenNode): List<Message>? {
            val out = ArrayList<Message>()
            var day: LocalDate? = null
            var sender = ""
            var contradicts = false
            fun bubble(b: ScreenNode) {
                val inside = visible(b)
                val owner = inside.any { it.id == "status" }
                if (owner && leading(b)) contradicts = true
                inside.firstOrNull { it.id == "name_in_group_tv" }?.text?.let { sender = clean(it) }
                if (owner) sender = ""
                // The time and the ticks share the last row, and bounds are clipped: a bubble cut above that row hides
                // whose it is.
                val date = inside.firstOrNull { it.id == "date" }?.takeIf { it.bottom < b.bottom } ?: return
                // A link or document bubble has no message_text: the preview's title or url stands in, so its side
                // and words aren't lost (a history of one side only misreads who wrote last). An empty shell is a
                // bubble whose text the list's edge clipped away: still skipped.
                val text = inside.firstOrNull { it.id == "message_text" }?.text?.let(::clean)?.takeIf { it.isNotEmpty() }
                    ?: inside.firstOrNull { it.id == "title" }?.text?.let(::clean)?.takeIf { it.isNotEmpty() }
                    ?: inside.firstOrNull { it.id == "url" }?.text?.let(::clean)?.takeIf { it.isNotEmpty() }
                    ?: return
                // Only `date` holds the time: a voice note's "0:43" reads as one too.
                val time = date.text?.let(ChatDates::bubbleTime)
                out += message(if (owner) Side.ME else Side.THEM, sender, text, day, time)
            }
            var seen = 0
            fun walk(n: ScreenNode) {
                if (!n.visible || ++seen > MAX_NODES) return
                when (n.id) {
                    "main_layout" -> bubble(n)
                    "conversation_row_date_divider" -> day = n.text?.let { ChatDates.divider(it, today) }
                    else -> n.children.forEach(::walk)
                }
            }
            walk(root)
            return if (contradicts) null else out
        }

        /**
         * Each `message_text` described "<who> said <text> <day> <time> ." is a message, the owner's when <who> is "You";
         * one without a description that reads as "<day> • <time>" is a divider. The tree lists the newest first.
         */
        fun googleMessages(root: ScreenNode): List<Message>? {
            val title = ScreenText.title(root)?.let(::collapse)
            val out = ArrayList<Message>()
            var divider: LocalDate? = null
            for (n in visible(root).filter { it.id == "message_text" }.sortedBy { it.top }) {
                val text = n.text?.let(::clean).orEmpty()
                val description = n.description?.let(::clean)
                if (description.isNullOrEmpty()) {
                    ChatDates.divider(text, today)?.let { divider = it }
                    continue
                }
                val said = SAID.find(description) ?: continue
                if (text.isEmpty()) continue
                val who = said.groupValues[1].trim()
                val rest = said.groupValues[2]
                val tail = (if (rest.startsWith(text)) rest.substring(text.length) else "").trim().removeSuffix(".").trim()
                val clock = TAIL_TIME.find(tail)
                val time = clock?.let { ChatDates.bubbleTime(it.value) }
                val stated = clock?.let { tail.substring(0, it.range.first).trim() }?.takeIf { it.isNotEmpty() }?.let(::onOrBeforeToday)
                val owner = who == "You"
                if (owner && leading(n)) return null
                out += message(if (owner) Side.ME else Side.THEM, if (collapse(who) == title) "" else who, text, day(divider, stated), time)
            }
            return out
        }

        /**
         * The description's day is the message's own, so it wins when it falls in the week after the divider above
         * (a run that crosses midnight under one divider); otherwise the divider's.
         */
        private fun day(divider: LocalDate?, stated: LocalDate?): LocalDate? = when {
            divider == null -> stated
            stated != null && stated.isAfter(divider) && !stated.isAfter(divider.plusDays(6)) -> stated
            else -> divider
        }

        /** A description's day; unlike a divider, its weekday can be today's. */
        private fun onOrBeforeToday(text: String): LocalDate? {
            val d = ChatDates.divider(text, today) ?: return null
            // ChatDates takes a weekday as the last one before today, so today's weekday reads as a week ago; a week
            // ago that tomorrow reads as today is that weekday.
            return if (d == today.minusDays(7) && ChatDates.divider(text, today.plusDays(1)) == today) today else d
        }
    }

    /** "message_text" from "com.whatsapp:id/message_text"; Google Messages' ids have no package. */
    private val ScreenNode.id: String? get() = viewId?.substringAfter(":id/")

    /** [root] and its visible descendants in tree order. */
    private fun visible(root: ScreenNode): List<ScreenNode> {
        val out = ArrayList<ScreenNode>()
        fun walk(n: ScreenNode) {
            if (!n.visible || out.size >= MAX_NODES) return
            out += n
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun clean(s: String) = WhatsAppExport.INVISIBLE.replace(s, "").trim()

    /** For comparing texts: [clean], with whitespace collapsed. */
    private fun collapse(s: String) = clean(s).replace(SPACE, " ")
}
