package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.style.WhatsAppExport
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Reads the dates and times chats show: export stamps, screen date dividers and bubble times (spec §4.4). */
object ChatDates {
    /** The field order of an export's numeric dates. */
    enum class Order { DMY, MDY, YMD }

    private val SPACES = Regex("[\\s\u00A0\u202F]+")
    private val DATE = Regex("""^\[?(\d{1,4})[./-](\d{1,2})[./-](\d{1,4})""")
    private const val MERIDIEM = """(?:\s?([AaPp])\.?\s?[Mm]\.?|\s?([صم]))"""
    private val STAMP = Regex("""^\[?(\d{1,4})[./-](\d{1,2})[./-](\d{1,4})[,،]?\s+(\d{1,2})[:.](\d{2})(?:[:.](\d{2}))?$MERIDIEM?""")
    private val TIME = Regex("""^(\d{1,2})[:.](\d{2})$MERIDIEM?$""")
    private val WORDS = Regex("""[\s,،.]+""")
    private val ALIF = Regex("[أإآ]")

    /** Keys are lowercase, with Arabic alif forms folded as [fold] does. */
    private val WEEKDAYS: Map<String, Int> = buildMap {
        listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").forEachIndexed { i, d ->
            put(d, i + 1)
            put(d.take(3), i + 1)
        }
        listOf("الاثنين", "الثلاثاء", "الاربعاء", "الخميس", "الجمعة", "السبت", "الاحد").forEachIndexed { i, d -> put(d, i + 1) }
    }
    private val MONTHS: Map<String, Int> = buildMap {
        listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        ).forEachIndexed { i, m ->
            put(m, i + 1)
            put(m.take(3), i + 1)
        }
        put("sept", 9)
        listOf(
            "يناير", "فبراير", "مارس", "ابريل", "مايو", "يونيو",
            "يوليو", "اغسطس", "سبتمبر", "اكتوبر", "نوفمبر", "ديسمبر",
        ).forEachIndexed { i, m -> put(m, i + 1) }
        listOf(
            "كانون الثاني", "شباط", "اذار", "نيسان", "ايار", "حزيران",
            "تموز", "اب", "ايلول", "تشرين الاول", "تشرين الثاني", "كانون الاول",
        ).forEachIndexed { i, m -> put(m, i + 1) }
    }
    private val TODAY = setOf("today", "اليوم")
    private val YESTERDAY = setOf("yesterday", "امس", "الامس")

    /** The date order of an export from the date part of each of its [stamps]: any field above 12, then dates running forward, then [fallback] (spec §4.4). */
    fun order(stamps: List<String>, fallback: Order): Order {
        val dates = stamps.mapNotNull { DATE.find(clean(it))?.groupValues }
        if (dates.any { it[1].length == 4 }) return Order.YMD
        if (dates.any { it[1].toInt() > 12 }) return Order.DMY
        if (dates.any { it[2].toInt() > 12 }) return Order.MDY
        // Fewer steps back wins. When both run forward, only a misreading of consecutive days moves the month while
        // keeping the day (01/02 → 02/02 as Jan 2 → Feb 2), so fewer such steps wins; anything else is the locale's call.
        fun cost(dayFirst: Boolean): Pair<Int, Int> {
            val days = dates.mapNotNull { g ->
                val (a, b) = g[1].toInt() to g[2].toInt()
                if (dayFirst) date(g[3], b, a) else date(g[3], a, b)
            }
            val steps = days.zipWithNext()
            return steps.count { (x, y) -> y.isBefore(x) } to
                steps.count { (x, y) -> x.monthValue != y.monthValue && x.dayOfMonth == y.dayOfMonth }
        }
        val better = compareValuesBy(cost(dayFirst = true), cost(dayFirst = false), { it.first }, { it.second })
        return if (better < 0) Order.DMY else if (better > 0) Order.MDY else fallback
    }

    /** An export line's stamp ("15/10/2026, 15:37 - …", "[15/10/26, 3:37:00 PM] …") as epoch ms in [zone]; null when it has none. */
    fun exportTime(stamp: String, order: Order, zone: ZoneId): Long? {
        val g = STAMP.find(clean(stamp))?.groupValues ?: return null
        val (a, b, c) = Triple(g[1], g[2], g[3])
        val day = when (order) {
            Order.DMY -> date(c, b.toInt(), a.toInt())
            Order.MDY -> date(c, a.toInt(), b.toInt())
            Order.YMD -> date(a, b.toInt(), c.toInt())
        } ?: return null
        val time = time(g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(), meridiem(g[7], g[8])) ?: return null
        return LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * The day a date divider ("Today", "أمس", "Sunday", "12 Oct", "October 18, 2026", "Sunday • 8:23 PM") starts,
     * as of [today]; null when [text] isn't one. Weekdays are the last one before today; dates without a year are
     * this year, or last year if that would be after [today].
     */
    fun divider(text: String, today: LocalDate): LocalDate? {
        val words = fold(clean(text).substringBefore('•')).split(WORDS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val name = words.joinToString(" ")
        if (name in TODAY) return today
        if (name in YESTERDAY) return today.minusDays(1)
        WEEKDAYS[name]?.let { d -> return today.minusDays(((today.dayOfWeek.value - d + 6) % 7 + 1).toLong()) }
        val numbers = words.filter { w -> w.all { it.isDigit() } }
        val month = MONTHS[words.filter { it !in numbers && it !in WEEKDAYS }.joinToString(" ")] ?: return null
        val day = numbers.singleOrNull { it.length <= 2 }?.toInt() ?: return null
        val years = numbers.filter { it.length == 4 }
        if (years.size > 1 || numbers.size != 1 + years.size) return null
        years.singleOrNull()?.let { return date(it, month, day) }
        val d = date(today.year.toString(), month, day) ?: return null
        return if (d.isAfter(today)) date((today.year - 1).toString(), month, day) else d
    }

    /** A bubble's time ("13:42", "1:42 PM", "١:٤٢ م") when [text] is only that; null for anything else. */
    fun bubbleTime(text: String): LocalTime? {
        val g = TIME.find(clean(text))?.groupValues ?: return null
        return time(g[1].toInt(), g[2].toInt(), 0, meridiem(g[3], g[4]))
    }

    /** Without invisible marks, with odd spaces collapsed to one and Arabic-Indic digits made Western. */
    private fun clean(s: String): String {
        val out = StringBuilder(s.length)
        for (c in WhatsAppExport.INVISIBLE.replace(s, "")) {
            out.append(
                when (c) {
                    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                    else -> c
                },
            )
        }
        return SPACES.replace(out, " ").trim()
    }

    /** Lowercase, with أ, إ and آ as ا so either spelling of an Arabic name matches. */
    private fun fold(s: String) = s.lowercase().replace(ALIF, "ا")

    /** true for PM, false for AM, null for a 24-hour time. */
    private fun meridiem(latin: String, arabic: String): Boolean? = when {
        latin.isNotEmpty() -> latin[0].lowercaseChar() == 'p'
        arabic.isNotEmpty() -> arabic == "م"
        else -> null
    }

    private fun time(hour: Int, minute: Int, second: Int, pm: Boolean?): LocalTime? {
        if (pm != null && hour !in 1..12) return null
        val h = if (pm == null) hour else hour % 12 + if (pm) 12 else 0
        return try {
            LocalTime.of(h, minute, second)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Two-digit years are 20xx. */
    private fun date(year: String, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(if (year.length <= 2) 2000 + year.toInt() else year.toInt(), month, day)
    } catch (_: DateTimeException) {
        null
    }
}
