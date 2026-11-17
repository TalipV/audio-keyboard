package dev.yazin.keyboard.keyboard

enum class KeyAction { TEXT, SHIFT, DELETE, SYMBOLS, SYMBOLS_MORE, ALPHA, NUMPAD, EMOJI, SPACE, ENTER }

/** Colour role: LETTER = white key, FUNCTION = grey key, ENTER = accent. */
enum class KeyStyle { LETTER, FUNCTION, ENTER }

data class Key(
    val action: KeyAction = KeyAction.TEXT,
    /** Drawn label; empty for icon keys. Letters are lowercase and drawn uppercase while shifted. */
    val label: String = "",
    /** Text committed by a TEXT key. */
    val output: String = label,
    /** Width in key units; a row's total units set its key pitch. */
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.LETTER,
    /** Fully rounded ends (Gboard's ?123 and enter keys). */
    val pill: Boolean = false,
    /** Small character drawn on the key, e.g. the digit or tashkeel available on long-press. */
    val hint: String? = null,
    /** Long-press alternates in Gboard's display order (rows top to bottom). */
    val alts: List<String> = emptyList(),
    /** The alternate drawn directly above the key and preselected. */
    val altDefault: Int = 0,
    /** Phone-pad letters beside the digit ("ABC"); non-null shifts the label left like Gboard ("" for the 1 key). */
    val sublabel: String? = null,
)

enum class RowKind { NUMBER, LETTER, BOTTOM, PAD }

data class Row(val keys: List<Key>, val kind: RowKind = RowKind.LETTER, val inset: Float = 0f) {
    /** Key units across the row including the leading and trailing inset. */
    val units: Float get() = keys.sumOf { it.width.toDouble() }.toFloat() + 2 * inset
}

/** Rows top to bottom; keys left to right as they appear on screen (Arabic too). [rtl] mirrors the toolbar. */
data class Layout(val rows: List<Row>, val rtl: Boolean = false, val rowGap: Float = Dimens.ROW_GAP)
