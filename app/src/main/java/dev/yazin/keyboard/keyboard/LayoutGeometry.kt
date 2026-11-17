package dev.yazin.keyboard.keyboard

class PlacedKey(
    val key: Key,
    val row: RowKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    /** Squared distance from (x, y) to this key's rectangle; 0 when inside. */
    fun distanceSq(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return dx * dx + dy * dy
    }
}

class KeyGrid(
    val keys: List<PlacedKey>,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    /** Long-press panel metrics in px (see [Dimens.ALT_CELL]). */
    val altCell: Float,
    val altCellMulti: Float,
    val altPadding: Float,
    val altPaddingMulti: Float,
    val altGap: Float,
) {
    /** The key nearest to (x, y). Gaps, margins and touches past the edges go to the closest key: no dead zones. */
    fun nearest(x: Float, y: Float): PlacedKey = keys.minBy { it.distanceSq(x, y) }
}

object LayoutGeometry {
    private fun rowHeight(kind: RowKind) = when (kind) {
        RowKind.NUMBER -> Dimens.NUMBER_ROW_HEIGHT
        RowKind.LETTER -> Dimens.KEY_HEIGHT
        RowKind.BOTTOM -> Dimens.BOTTOM_ROW_HEIGHT
        RowKind.PAD -> Dimens.PAD_ROW_HEIGHT
    }

    /** Pixel height of [layout]'s rows including the [rowGap] (dp) above each row. */
    fun naturalRowsHeight(layout: Layout, dp: Float, rowGap: Float = Dimens.ROW_GAP): Float =
        layout.rows.sumOf { (rowHeight(it.kind) + rowGap).toDouble() }.toFloat() * dp

    /**
     * Places [layout] in a [width]-px-wide keyboard whose rows start at [top]. Key heights stretch so the rows
     * fill exactly [rowsHeight]; pass the letters page's natural height to keep every page the same size.
     */
    fun place(
        layout: Layout,
        width: Float,
        top: Float,
        dp: Float,
        rowsHeight: Float = naturalRowsHeight(layout, dp),
        rowGap: Float = Dimens.ROW_GAP,
        keyGap: Float = Dimens.KEY_GAP,
    ): KeyGrid {
        val gap = keyGap * dp
        val margin = Dimens.SIDE_MARGIN * dp
        val rowGapPx = rowGap * dp
        val natural = layout.rows.sumOf { rowHeight(it.kind).toDouble() }.toFloat() * dp
        val stretch = if (natural > 0f) (rowsHeight - rowGapPx * layout.rows.size) / natural else 1f
        val keys = ArrayList<PlacedKey>()
        var y = top
        for (row in layout.rows) {
            y += rowGapPx
            val h = rowHeight(row.kind) * dp * stretch
            val unit = (width - 2 * margin + gap) / row.units
            var x = margin + row.inset * unit
            for (k in row.keys) {
                val w = k.width * unit
                keys += PlacedKey(k, row.kind, x, y, x + w - gap, y + h)
                x += w
            }
            y += h
        }
        return KeyGrid(
            keys = keys,
            top = top,
            bottom = y,
            left = margin,
            right = width - margin,
            altCell = Dimens.ALT_CELL * dp,
            altCellMulti = Dimens.ALT_CELL_MULTI * dp,
            altPadding = Dimens.ALT_PADDING * dp,
            altPaddingMulti = Dimens.ALT_PADDING_MULTI * dp,
            altGap = Dimens.ALT_GAP * dp,
        )
    }
}
