package dev.yazin.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeometryTest {
    // The user's Gboard capture is 900 px wide for a 411.4 dp screen: 2.1875 px per dp.
    private val dp = 2.1875f
    private val keyboardTop = 1127f
    private val letters = Layouts.build(LayoutSpec())
    private val grid = LayoutGeometry.place(letters, 900f, keyboardTop + Dimens.TOOLBAR_HEIGHT * dp, dp)

    private fun key(label: String) = grid.keys.first { it.key.label == label }
    private fun key(action: KeyAction) = grid.keys.first { it.key.action == action }

    @Test fun topLetterRowMatchesGboardPixels() {
        val lefts = listOf(10f, 99f, 188f, 278f, 367f, 456f, 545f, 634f, 723f, 812f)
        "qwertyuiop".forEachIndexed { i, c -> assertEquals("$c left", lefts[i], key("$c").left, 1.5f) }
        assertEquals(78f, key("q").width, 1.5f)
        assertEquals(55f, key("a").left, 1.5f)
        assertEquals(845f, key("l").right, 1.5f)
    }

    @Test fun rowsMatchGboardPixelsVertically() {
        assertEquals(1241f, key("1").top, 2f)
        assertEquals(1340f, key("1").bottom, 2f)
        assertEquals(1360f, key("q").top, 2f)
        assertEquals(1486f, key("a").top, 2f)
        assertEquals(1612f, key("z").top, 2f)
        assertEquals(1738f, key(KeyAction.SPACE).top, 2f)
        assertEquals(1851f, key(KeyAction.SPACE).bottom, 2f)
    }

    @Test fun bottomRowMatchesGboardPixels() {
        assertEquals(10f, key(KeyAction.SYMBOLS).left, 1.5f)
        assertEquals(132f, key(KeyAction.SYMBOLS).right, 1.5f)
        assertEquals(143f, key(",").left, 1.5f)
        assertEquals(232f, key(KeyAction.EMOJI).left, 1.5f)
        assertEquals(322f, key(KeyAction.SPACE).left, 1.5f)
        assertEquals(667f, key(KeyAction.SPACE).right, 1.5f)
        assertEquals(678f, key(".").left, 1.5f)
        assertEquals(767f, key(KeyAction.ENTER).left, 1.5f)
        assertEquals(890f, key(KeyAction.ENTER).right, 1.5f)
    }

    @Test fun gapsAndEdgesBelongToTheNearestKey() {
        val q = key("q")
        val w = key("w")
        assertSame(q, grid.nearest(q.right + 2f, q.centerY))
        assertSame(w, grid.nearest(w.left - 2f, w.centerY))
        assertSame(key("a"), grid.nearest(1f, key("a").centerY))
        assertSame(key("1"), grid.nearest(key("1").centerX, key("1").top - 30f))
        assertSame(key(KeyAction.SPACE), grid.nearest(450f, 1990f))
    }

    @Test fun everyPageKeepsTheLettersHeight() {
        val rows = LayoutGeometry.naturalRowsHeight(letters, dp)
        for (page in Page.values()) {
            val g = LayoutGeometry.place(Layouts.build(LayoutSpec(page = page)), 900f, 0f, dp, rows)
            assertEquals(page.name, rows, g.keys.maxOf { it.bottom }, 0.01f)
        }
    }

    @Test fun germanShiftIsAsWideAsTheSymbolPagesOneHalfKey() {
        val de = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = Lang.DE)), 900f, 0f, dp)
        val sym = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = Lang.DE, page = Page.SYMBOLS)), 900f, 0f, dp)
        val shift = de.keys.first { it.key.action == KeyAction.SHIFT }
        val half = sym.keys.first { it.key.label == "1/2" }
        assertEquals(half.width, shift.width, 0.01f)
        assertEquals(half.left, shift.left, 0.01f)
        // The letters keep the size of the rows above; the delete key ends at the right edge like the 0 key.
        val q = de.keys.first { it.key.label == "q" }
        assertEquals(q.width, de.keys.first { it.key.label == "y" }.width, 0.01f)
        assertEquals(de.keys.first { it.key.label == "ü" }.right, de.keys.first { it.key.action == KeyAction.DELETE }.right, 0.01f)
    }

    @Test fun arabicLetterRowsUseElevenColumns() {
        val ar = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = Lang.AR)), 900f, 0f, dp)
        val dad = ar.keys.first { it.key.label == "ض" }
        val expected = (900f - 2 * Dimens.SIDE_MARGIN * dp + Dimens.KEY_GAP * dp) / 11f - Dimens.KEY_GAP * dp
        assertEquals(expected, dad.width, 0.01f)
        assertEquals(78f, ar.keys.first { it.key.label == "1" }.width, 1.5f)
    }

    @Test fun rowGapIsAdjustableForCompactLandscape() {
        val compact = LayoutGeometry.place(letters, 900f, 0f, dp, LayoutGeometry.naturalRowsHeight(letters, dp, rowGap = 5f), rowGap = 5f)
        val one = compact.keys.first { it.key.label == "1" }
        val q = compact.keys.first { it.key.label == "q" }
        assertEquals(5f * dp, one.top, 0.01f)
        assertEquals(one.bottom + 5f * dp, q.top, 0.01f)
        assertEquals(key("q").height, q.height, 0.01f)
    }

    @Test fun alternatesCellsMatchGboard() {
        assertEquals(96.6f, grid.altCell, 1f)
        assertEquals(88f, grid.altCellMulti, 1f)
        assertEquals(27.5f, grid.altPadding, 1f)
        assertEquals(22.1f, grid.altPaddingMulti, 1f)
    }

    @Test fun keyGapIsAdjustableForLandscape() {
        val wide = LayoutGeometry.place(letters, 900f, 0f, dp, keyGap = 9.1f)
        val gap = 9.1f * dp
        val q = wide.keys.first { it.key.label == "q" }
        val w = wide.keys.first { it.key.label == "w" }
        assertEquals((900f - 2 * Dimens.SIDE_MARGIN * dp + gap) / 10f - gap, q.width, 0.01f)
        assertEquals(gap, w.left - q.right, 0.01f)
    }

    @Test fun phonePadRowsMatchGboard() {
        val phone = Layouts.build(LayoutSpec(page = Page.PHONE))
        val g = LayoutGeometry.place(phone, 900f, 0f, dp, LayoutGeometry.naturalRowsHeight(phone, dp, phone.rowGap), phone.rowGap)
        val one = g.keys.first { it.key.label == "1" }
        val four = g.keys.first { it.key.label == "4" }
        assertEquals(127f, one.height, 1.5f)
        assertEquals(13f, four.top - one.bottom, 1f)
        assertEquals(211f, one.width, 1.5f)
    }
}
