package dev.yazin.keyboard.keyboard

/**
 * A keyboard language. [code] is its ISO 639-1 code (subtype tag, dictionary name `<code>.dict`, Whisper language);
 * [cased] languages get shift and auto-caps; [compounds] languages write compounds as one word (German).
 */
enum class Lang(val code: String, val rtl: Boolean = false, val cased: Boolean = true, val compounds: Boolean = false) {
    EN("en"),
    AR("ar", rtl = true, cased = false),
    DE("de", compounds = true);

    val dictFile get() = "$code.dict"

    companion object {
        /** The language of a subtype tag or locale ("de-DE", "ar", "en_US"); English when unknown. */
        fun of(tag: String?): Lang = values().firstOrNull { tag?.startsWith(it.code) == true } ?: EN
    }
}
enum class Page { LETTERS, SYMBOLS, SYMBOLS_MORE, NUMBER, PHONE }
enum class Field { TEXT, EMAIL, URL }

data class LayoutSpec(
    val lang: Lang = Lang.EN,
    val page: Page = Page.LETTERS,
    val field: Field = Field.TEXT,
    val numberRow: Boolean = true,
    val easternDigits: Boolean = false,
    /** The emoji key beside space; without it space is wider and a long-press on the comma opens emoji (as Gboard). */
    val emojiKey: Boolean = true,
)

/**
 * Every keyboard page, matching the user's Gboard. The letter pages were measured
 * from screenshots; the symbol, number and phone pages follow Gboard's standard pages and are checked
 * in Task 10's calibration pass.
 */
object Layouts {
    private const val DIGITS = "1234567890"
    private const val EASTERN_DIGITS = "١٢٣٤٥٦٧٨٩٠"

    private val enAccents = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "êéèëē", 'i' to "ìíîïīį", 'n' to "ñń",
        'o' to "òóôöõœøō", 's' to "ßśš", 'u' to "ùúûüū", 'y' to "ÿ", 'z' to "žźż",
    )
    private val enDigitAlts = mapOf(
        '1' to "¹½⅓¼⅛", '2' to "²⅔", '3' to "³¾⅜", '4' to "⁴", '5' to "⅝", '7' to "⅞", '0' to "ⁿ∅",
    )
    /** Index of the alternate Gboard places above the key (measured: é for e, أ for ا); others default to 0. */
    private val altDefaults = mapOf('e' to 1, 'ا' to 2)
    private val arAlts = mapOf('ا' to "آءأإٱ", 'ي' to "ئى", 'و' to "ؤ", 'ه' to "ة", 'ء' to "ئؤأإ")
    private val periodAlts = listOf("&", "%", "+", "\"", "-", ":", "'", "@", ";", "/", "(", ")", "#", "!", ",", "?")
    private val urlAlts = listOf(".com", ".org", ".net", ".edu", ".gov", "/", ":", "-", "_")

    /** Fatha, damma, kasra, fathatan, dammatan, kasratan, shadda, sukun, tatweel. */
    private val tashkeel = "ًٌٍَُِّْـ".splitCodePoints()

    private val SHIFT = Key(KeyAction.SHIFT, width = 1.5f, style = KeyStyle.FUNCTION)
    private val DELETE = Key(KeyAction.DELETE, style = KeyStyle.FUNCTION)
    private val DELETE_WIDE = DELETE.copy(width = 1.5f)
    private val EMOJI = Key(KeyAction.EMOJI)
    private val SPACE = Key(KeyAction.SPACE, width = 4f)
    private val ENTER = Key(KeyAction.ENTER, width = 1.5f, style = KeyStyle.ENTER, pill = true)
    private val NUMPAD = Key(KeyAction.NUMPAD, "1234") // white like Gboard

    fun build(spec: LayoutSpec): Layout = when (spec.page) {
        Page.LETTERS -> when (spec.lang) {
            Lang.EN -> english(spec)
            Lang.AR -> arabic(spec)
            Lang.DE -> german(spec)
        }
        Page.SYMBOLS -> symbols(spec)
        Page.SYMBOLS_MORE -> symbolsMore(spec)
        Page.NUMBER -> numberPad(spec)
        Page.PHONE -> phonePad()
    }

    private fun english(spec: LayoutSpec) = Layout(
        listOfNotNull(
            numberRow(spec),
            Row(digitHints(spec, "qwertyuiop".map { letter(it, enAccents[it]) })),
            Row("asdfghjkl".map { letter(it, enAccents[it]) }, inset = 0.5f),
            Row(listOf(SHIFT) + "zxcvbnm".map { letter(it, enAccents[it]) } + DELETE_WIDE),
            lettersBottom(spec),
        ),
    )

    /** German QWERTZ like Gboard: ü, ö and ä as their own keys (11 columns), ß on long-press of s. */
    private fun german(spec: LayoutSpec) = Layout(
        listOfNotNull(
            numberRow(spec),
            Row(digitHints(spec, "qwertzuiopü".map { letter(it, enAccents[it]) })),
            Row("asdfghjklöä".map { letter(it, enAccents[it]) }),
            Row(listOf(SHIFT.copy(width = 2f)) + "yxcvbnm".map { letter(it, enAccents[it]) } + DELETE.copy(width = 2f)),
            lettersBottom(spec),
        ),
    )

    private fun arabic(spec: LayoutSpec) = Layout(
        listOfNotNull(
            numberRow(spec),
            Row(digitHints(spec, "ضصثقفغعهخحج".map { letter(it, arAlts[it]) })),
            Row("شسيبلاتنمكط".map { letter(it, arAlts[it]) }),
            Row("ذءؤرىةوزظد".map { letter(it, arAlts[it]) } + DELETE),
            lettersBottom(spec),
        ),
        rtl = true,
    )

    private fun numberRow(spec: LayoutSpec): Row? =
        if (spec.numberRow) Row(DIGITS.indices.map { digit(it, spec) }, kind = RowKind.NUMBER) else null

    /** Without a number row, Gboard shows digits as hints on the top letter row and offers them first on long-press. */
    private fun digitHints(spec: LayoutSpec, keys: List<Key>): List<Key> {
        if (spec.numberRow) return keys
        return keys.mapIndexed { i, k ->
            if (i >= DIGITS.length) k else digit(i, spec).label.let { d -> k.copy(hint = d, alts = listOf(d) + k.alts, altDefault = 0) }
        }
    }

    private fun digit(i: Int, spec: LayoutSpec): Key {
        val western = DIGITS[i].toString()
        val eastern = EASTERN_DIGITS[i].toString()
        return when {
            spec.lang == Lang.AR && spec.easternDigits -> Key(label = eastern, alts = listOf(western))
            spec.lang == Lang.AR -> Key(label = western, alts = listOf(eastern))
            else -> Key(label = western, alts = enDigitAlts[DIGITS[i]]?.splitCodePoints().orEmpty())
        }
    }

    private fun lettersBottom(spec: LayoutSpec): Row {
        val ar = spec.lang == Lang.AR
        val second = when (spec.field) {
            Field.EMAIL -> fn("@")
            Field.URL -> fn("/")
            Field.TEXT -> fn(if (ar) "،" else ",")
        }
        val period = when {
            ar -> fn(".", tashkeel, hint = "ً")
            spec.field == Field.URL -> fn(".", urlAlts)
            else -> fn(".", periodAlts).copy(altDefault = periodAlts.indexOf(","))
        }
        if (!spec.emojiKey) {
            val emojiOnHold = second.copy(longPress = KeyAction.EMOJI, hint = "☺")
            return Row(listOf(symbolsKey(spec, pill = true), emojiOnHold, SPACE.copy(width = 5f), period, ENTER), kind = RowKind.BOTTOM)
        }
        return Row(listOf(symbolsKey(spec, pill = true), second, EMOJI, SPACE, period, ENTER), kind = RowKind.BOTTOM)
    }

    private fun symbols(spec: LayoutSpec): Layout {
        val ar = spec.lang == Lang.AR
        return Layout(
            listOf(
                Row(DIGITS.indices.map { digit(it, spec) }),
                Row(
                    listOf(
                        sym("@"), sym("#", "№"), sym("$", "¢£€¥₹₩"), sym("_"), sym("&"), sym("-", "–—·"),
                        sym("+", "±"), sym("(", "[{<"), sym(")", "]}>"), sym("/", "\\"),
                    ),
                ),
                Row(
                    listOf(
                        Key(KeyAction.SYMBOLS_MORE, "=\\<", width = 1.5f, style = KeyStyle.FUNCTION),
                        sym("*", "†‡★"), sym("\"", "“”„«»"), sym("'", "‘’‚‹›"), sym(":"),
                        if (ar) sym("؛", ";") else sym(";"), sym("!", "¡"), if (ar) sym("؟", "?") else sym("?", "¿"),
                        DELETE_WIDE,
                    ),
                ),
                Row(listOf(alphaKey(spec), fn(if (ar) "،" else ","), NUMPAD, SPACE, fn("."), ENTER), kind = RowKind.BOTTOM),
            ),
            rtl = ar,
        )
    }

    private fun symbolsMore(spec: LayoutSpec) = Layout(
        listOf(
            Row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { sym(it) }),
            Row(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { sym(it) }),
            Row(
                listOf(symbolsKey(spec, pill = false)) +
                    listOf("%", "©", "®", "™", "✓", "[", "]").map { sym(it) } + DELETE_WIDE,
            ),
            Row(listOf(alphaKey(spec), fn("<"), NUMPAD, SPACE, fn(">"), ENTER), kind = RowKind.BOTTOM),
        ),
        rtl = spec.lang == Lang.AR,
    )

    private fun numberPad(spec: LayoutSpec): Layout {
        fun d(i: Int) = digit(i, spec).copy(width = 2f, alts = emptyList())
        fun f(label: String, alts: String = "") =
            Key(label = label, width = 2f, style = KeyStyle.FUNCTION, alts = alts.splitCodePoints())
        return Layout(
            listOf(
                Row(listOf(f("+"), d(0), d(1), d(2), f("%"))),
                Row(listOf(f("-", "/"), d(3), d(4), d(5), SPACE.copy(label = "␣", width = 2f, style = KeyStyle.FUNCTION))),
                Row(listOf(f("*", "×÷"), d(6), d(7), d(8), DELETE.copy(width = 2f))),
                Row(
                    listOf(alphaKey(spec).copy(width = 2f), f(if (spec.lang == Lang.AR) "،" else ","), d(9), f("."), ENTER.copy(width = 2f)),
                    kind = RowKind.BOTTOM,
                ),
            ),
        )
    }

    /** Gboard's phone pad (measured): pill keys, letters beside digits. Dialers need ASCII digits, never Eastern Arabic. */
    private fun phonePad(): Layout {
        fun d(i: Int, letters: String, alts: String = "") =
            Key(label = DIGITS[i].toString(), sublabel = letters, width = 2.5f, pill = true, alts = alts.splitCodePoints())
        fun f(key: Key) = key.copy(width = 2.5f, style = KeyStyle.FUNCTION, pill = true)
        fun row(vararg keys: Key) = Row(keys.toList(), kind = RowKind.PAD)
        return Layout(
            listOf(
                row(d(0, ""), d(1, "ABC"), d(2, "DEF"), f(Key(label = "–", output = "-"))),
                row(d(3, "GHI"), d(4, "JKL"), d(5, "MNO"), f(SPACE.copy(label = "␣"))),
                row(d(6, "PQRS"), d(7, "TUV"), d(8, "WXYZ"), f(DELETE)),
                // ponytail: "* #" types * and offers # first on long-press; Gboard may open a symbol pad instead (unverified).
                row(
                    Key(label = "* #", output = "*", width = 2.5f, pill = true, alts = "#(),;/N".splitCodePoints()),
                    d(9, "+", "+"),
                    Key(label = ".", width = 2.5f, pill = true),
                    ENTER.copy(width = 2.5f),
                ),
            ),
            rowGap = Dimens.PAD_ROW_GAP,
        )
    }

    /** Left-to-right marks (before ؟ and before the digits, which bidi would otherwise turn RTL) keep Gboard's "؟123" order instead of "123؟". */
    private fun symbolsKey(spec: LayoutSpec, pill: Boolean) =
        Key(KeyAction.SYMBOLS, if (spec.lang == Lang.AR) "\u200E؟\u200E123" else "?123", width = 1.5f, style = KeyStyle.FUNCTION, pill = pill)

    private fun alphaKey(spec: LayoutSpec) =
        Key(KeyAction.ALPHA, if (spec.lang == Lang.AR) "أبج" else "ABC", width = 1.5f, style = KeyStyle.FUNCTION, pill = true)

    private fun letter(c: Char, alts: String?) =
        Key(label = c.toString(), alts = alts?.splitCodePoints().orEmpty(), altDefault = altDefaults[c] ?: 0)

    private fun sym(label: String, alts: String = "") = Key(label = label, alts = alts.splitCodePoints())

    private fun fn(label: String, alts: List<String> = emptyList(), hint: String? = null) =
        Key(label = label, style = KeyStyle.FUNCTION, alts = alts, hint = hint)
}

/** Splits into code points as strings, keeping supplementary characters (emoji) whole. */
fun String.splitCodePoints(): List<String> = codePoints().toArray().map { String(Character.toChars(it)) }
