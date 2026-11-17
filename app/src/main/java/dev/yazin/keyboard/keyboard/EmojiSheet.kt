package dev.yazin.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.emoji.Emoji
import dev.yazin.keyboard.engine.emoji.EmojiGroup
import dev.yazin.keyboard.engine.emoji.EmojiPrefs
import kotlin.math.hypot

/**
 * Gboard's emoji panel (spec §8): the "Search emoji" pill and category tabs in the toolbar row, which scrolls
 * sideways, a scrolling grid with a header per category (recently used first), and a bar with ABC and delete.
 * Long-press picks a skin tone. Mirrored for Arabic.
 */
class EmojiSheet(
    private val view: View,
    private val icons: Icons,
    private val catalog: () -> List<EmojiGroup>,
    private val prefs: EmojiPrefs,
    /** False in password and incognito fields: no recents, no remembered tones (spec §4). */
    private val remember: () -> Boolean,
    private val onEmoji: (String) -> Unit,
    private val onAbc: () -> Unit,
    private val onDelete: () -> Unit,
    private val onSearch: () -> Unit,
) : Sheet {
    /** A grid row in content coordinates (0 = the first header): a header when [items] is null. */
    private class Row(val section: Int, val top: Float, val items: List<Emoji>?, val recents: List<String>?)

    private class Section(val title: String, val top: Float)

    private sealed interface Target
    private data class Tab(val section: Int) : Target
    private data object Search : Target
    private data object Abc : Target
    private data object Delete : Target
    private data class Cell(val row: Int, val index: Int) : Target

    private inner class TonePopup(val emoji: Emoji, val rect: RectF, val cols: Int, val openX: Float, val openY: Float) {
        /** A choice needs a slide away from where the long-press opened it, or a tap: the panel may cover that spot. */
        var armed = false
        val options = listOf(emoji.base) + emoji.variants
        var selected = -1

        fun cell(i: Int): RectF {
            val size = Dimens.EMOJI_POPUP_CELL * dp
            val pad = Dimens.EMOJI_POPUP_PAD * dp
            val l = rect.left + pad + (i % cols) * size
            val t = rect.top + pad + (i / cols) * size
            return RectF(l, t, l + size, t + size)
        }

        fun select(x: Float, y: Float) {
            selected = options.indices.firstOrNull { cell(it).contains(x, y) } ?: -1
            view.invalidate()
        }
    }

    private val ctx = view.context
    private val dp = ctx.resources.displayMetrics.density
    private val scroll = SheetScroll(view)
    private val tabScroll = SheetScroll(view, horizontal = true)
    /** The tab last scrolled into view, so the row follows the grid without fighting the finger. */
    private var revealed = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(Dimens.EMOJI_SP) }
    private val glyphBaseline = glyph.fontMetrics.let { -(it.ascent + it.descent) / 2 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private val mark = Path()
    private var rows: List<Row> = emptyList()
    private var sections: List<Section> = emptyList()
    private var content = 0f
    private var cell = 0f
    private var columns = Dimens.EMOJI_COLUMNS
    private var builtFor = -1f
    private val area = RectF()
    private var rtl = false
    private var target: Target? = null
    private var downInGrid = false
    private var downInTabs = false
    private var dragged = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var popup: TonePopup? = null
    private val longPress = Runnable { (target as? Cell)?.let(::openPopup) }
    private val repeat = object : Runnable {
        override fun run() {
            onDelete()
            view.postDelayed(this, REPEAT_MS)
        }
    }

    /** Shows the recents as they are now, from the top, as Gboard does each time the panel opens. */
    fun onOpen() {
        builtFor = -1f
        scroll.scrollTo(0f)
        tabScroll.scrollTo(0f)
        revealed = -1
        cancel()
    }

    override fun draw(c: Canvas, area: RectF, colors: KeyboardColors, rtl: Boolean) {
        this.area.set(area)
        this.rtl = rtl
        if (builtFor != area.width()) build(area.width())
        val grid = grid()
        scroll.max = content - grid.height()
        scroll.step()

        c.save()
        c.clipRect(grid)
        val top = grid.top - scroll.y
        for (r in firstRow(scroll.y) until rows.size) {
            val row = rows[r]
            val y = top + row.top
            if (y > grid.bottom) break
            val items = row.items
            if (items == null) drawHeader(c, sections[row.section].title, y, colors)
            else for (i in items.indices) drawCell(c, row, r, i, y, colors)
        }
        c.restore()
        drawTabs(c, colors)
        drawBar(c, colors)
        popup?.let { drawPopup(c, it, colors) }
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = popup
                if (p != null) { // a tone panel left open: tap a tone, or anywhere else to close it
                    p.select(e.x, e.y)
                    if (p.selected < 0) popup = null
                    downInGrid = false // this touch neither scrolls nor taps the grid
                    downInTabs = false
                    dragged = true
                    return
                }
                dragged = false
                downX = e.x
                downY = e.y
                target = targetAt(e.x, e.y)
                downInGrid = grid().contains(e.x, e.y)
                downInTabs = tabRow().contains(e.x, e.y)
                if (downInGrid) scroll.down(e)
                if (downInTabs) tabScroll.down(e)
                when (val t = target) {
                    Delete -> {
                        onDelete()
                        view.postDelayed(repeat, REPEAT_DELAY_MS)
                    }
                    is Cell -> if (emojiAt(t).variants.isNotEmpty()) view.postDelayed(longPress, LONG_PRESS_MS)
                    else -> Unit
                }
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val p = popup
                if (p != null) {
                    if (!p.armed && hypot(e.x - p.openX, e.y - p.openY) < slop) return
                    p.armed = true
                    return p.select(e.x, e.y)
                }
                val s = when {
                    downInGrid -> scroll
                    downInTabs -> tabScroll
                    else -> return
                }
                s.move(e)
                if (s.dragging && !dragged) {
                    dragged = true
                    view.removeCallbacks(longPress)
                    target = null
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPress)
                view.removeCallbacks(repeat)
                val p = popup
                if (p != null) {
                    // Slid onto a tone and let go, or tapped one: type it. Released elsewhere: the panel stays open.
                    p.options.getOrNull(p.selected)?.let { typed ->
                        type(p.emoji, typed)
                        popup = null
                    }
                    view.invalidate()
                    return
                }
                if (downInGrid) scroll.up(e)
                if (downInTabs) tabScroll.up(e)
                if (!dragged) tap(target)
                target = null
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    override fun cancel() {
        view.removeCallbacks(longPress)
        view.removeCallbacks(repeat)
        popup = null
        target = null
        scroll.cancel()
        tabScroll.cancel()
    }

    private fun build(width: Float) {
        // 9 columns on a phone held upright; wider screens get more columns of about the same size, not bigger cells.
        val inner = width - 2 * Dimens.EMOJI_SIDE_MARGIN * dp
        columns = maxOf(Dimens.EMOJI_COLUMNS, (inner / (Dimens.EMOJI_MAX_CELL * dp)).toInt())
        cell = inner / columns
        val header = Dimens.EMOJI_HEADER_HEIGHT * dp
        val groups = catalog()
        val byBase = groups.flatMap { it.emoji }.associateBy { it.base }
        val out = ArrayList<Row>()
        val secs = ArrayList<Section>()
        var y = 0f
        fun section(title: String, items: List<Emoji>, recents: List<String>?) {
            val s = secs.size
            secs += Section(title, y)
            out += Row(s, y, null, null)
            y += header
            for (start in items.indices step columns) {
                val end = minOf(items.size, start + columns)
                out += Row(s, y, items.subList(start, end), recents?.subList(start, end))
                y += cell
            }
        }
        val recent = prefs.recent.mapNotNull { (base, typed) -> byBase[base]?.let { it to typed } }
        if (recent.isNotEmpty()) section(RECENT, recent.map { it.first }, recent.map { it.second })
        for (g in groups) section(g.name, g.emoji, null)
        rows = out
        sections = secs
        content = y
        builtFor = if (groups.isEmpty()) -1f else width // the catalog may still be loading
    }

    private fun tabRow() = RectF(area.left, area.top, area.right, area.top + Dimens.TOOLBAR_HEIGHT * dp)

    private fun bar() = RectF(area.left, area.bottom - Dimens.BOTTOM_ROW_HEIGHT * dp, area.right, area.bottom)

    private fun grid() = RectF(area.left, tabRow().bottom, area.right, bar().top)

    /** Layout is computed left to right and mirrored for Arabic. */
    private fun mirror(x: Float) = if (rtl) area.left + area.right - x else x

    private fun cellX(i: Int) = mirror(area.left + Dimens.EMOJI_SIDE_MARGIN * dp + cell * (i + 0.5f))

    private fun firstRow(y: Float): Int {
        var lo = 0
        var hi = rows.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (rows[mid].top <= y) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun emojiAt(t: Cell) = rows[t.row].items!![t.index]

    /** A recent shows exactly what was typed; elsewhere an emoji shows its remembered tone. */
    private fun shown(row: Row, i: Int) = row.recents?.get(i) ?: prefs.forTap(row.items!![i])

    private fun selectedSection(): Int {
        if (sections.isEmpty()) return -1
        if (scroll.max > 0 && scroll.y >= scroll.max - 1) return sections.lastIndex
        return sections.indexOfLast { it.top <= scroll.y + 1 }.coerceAtLeast(0)
    }

    private fun targetAt(x: Float, y: Float): Target? {
        val lx = mirror(x) - area.left
        val tabs = tabRow()
        val b = bar()
        val g = grid()
        return when {
            tabs.contains(x, y) -> {
                val tx = lx + tabScroll.y
                val pill = Dimens.EMOJI_SEARCH_PILL_INSET * dp
                when {
                    tx in pill..pill + Dimens.EMOJI_SEARCH_PILL_WIDTH * dp -> Search
                    sections.isNotEmpty() && tx >= tabsStart() ->
                        Tab(((tx - tabsStart()) / tabSlot()).toInt().coerceIn(0, sections.lastIndex))
                    else -> null
                }
            }
            b.contains(x, y) -> when {
                lx < 2 * Dimens.EMOJI_BAR_SIDE * dp -> Abc
                lx > b.width() - 2 * Dimens.EMOJI_BAR_SIDE * dp -> Delete
                else -> null
            }
            g.contains(x, y) -> {
                val r = firstRow(y - g.top + scroll.y)
                val items = rows.getOrNull(r)?.items ?: return null
                val gx = lx - Dimens.EMOJI_SIDE_MARGIN * dp
                val i = (gx / cell).toInt()
                if (gx >= 0 && i in items.indices) Cell(r, i) else null
            }
            else -> null
        }
    }

    private fun tap(t: Target?) {
        when (t) {
            is Tab -> scroll.scrollTo(sections[t.section].top)
            Search -> onSearch()
            Abc -> onAbc()
            is Cell -> type(emojiAt(t), shown(rows[t.row], t.index))
            else -> Unit // delete acts on touch-down
        }
    }

    private fun type(e: Emoji, typed: String) {
        prefs.used(e.base, typed, remember())
        onEmoji(typed)
    }

    private fun openPopup(t: Cell) {
        val e = emojiAt(t)
        val cellTop = grid().top - scroll.y + rows[t.row].top
        val size = Dimens.EMOJI_POPUP_CELL * dp
        val pad = Dimens.EMOJI_POPUP_PAD * dp
        val (cols, lines) = tonePanelGrid(e.variants.size + 1, size, pad, area.height())
        val w = cols * size + 2 * pad
        val h = lines * size + 2 * pad
        val left = (cellX(t.index) - w / 2).coerceIn(area.left + pad, maxOf(area.left + pad, area.right - pad - w))
        val top = tonePanelTop(cellTop, cellTop + cell, h, pad / 2, area.top, area.bottom)
        popup = TonePopup(e, RectF(left, top, left + w, top + h), cols, downX, downY)
        target = null
        view.invalidate()
    }

    private fun drawCell(c: Canvas, row: Row, r: Int, i: Int, y: Float, colors: KeyboardColors) {
        val cx = cellX(i)
        val cy = y + cell / 2
        if (target == Cell(r, i) && popup == null) {
            fill.color = colors.letterKeyPressed
            c.drawCircle(cx, cy, cell * 0.46f, fill)
        }
        c.drawText(shown(row, i), cx, cy + glyphBaseline, glyph)
        if (row.items!![i].variants.isNotEmpty()) {
            // Gboard's corner mark: long-press for skin tones.
            val s = Dimens.EMOJI_MARK * dp
            val side = if (rtl) -1 else 1
            val mx = cx + side * cell * 0.42f
            val my = cy + cell * 0.42f
            mark.reset()
            mark.moveTo(mx, my)
            mark.lineTo(mx - side * s, my)
            mark.lineTo(mx, my - s)
            mark.close()
            fill.color = colors.hint
            c.drawPath(mark, fill)
        }
    }

    private fun drawHeader(c: Canvas, title: String, y: Float, colors: KeyboardColors) {
        label.textSize = sp(Dimens.EMOJI_HEADER_SP)
        label.color = colors.hint
        label.letterSpacing = 0.1f
        label.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val s = if (title == RECENT) ctx.getString(R.string.emoji_recent) else title.uppercase().replace("&", "AND")
        val fm = label.fontMetrics
        c.drawText(s, mirror(area.left + Dimens.EMOJI_HEADER_X * dp), y + Dimens.EMOJI_HEADER_TEXT_Y * dp - (fm.ascent + fm.descent) / 2, label)
        label.letterSpacing = 0f
    }

    /** Content x where the tabs start, after the pill. */
    private fun tabsStart() = (Dimens.EMOJI_SEARCH_PILL_INSET + Dimens.EMOJI_SEARCH_PILL_WIDTH + Dimens.EMOJI_TAB_GAP) * dp

    /** [Dimens.EMOJI_TAB_SLOT] per tab, spread out where they all fit (landscape). */
    private fun tabSlot(): Float {
        if (sections.isEmpty()) return 0f
        return maxOf(Dimens.EMOJI_TAB_SLOT * dp, (area.width() - tabsStart() - Dimens.EMOJI_SEARCH_PILL_INSET * dp) / sections.size)
    }

    /** Scrolls the tab row so [section]'s tab shows; the first section brings back the pill. */
    private fun reveal(section: Int) {
        if (section <= 0) return tabScroll.scrollTo(0f)
        val left = tabsStart() + tabSlot() * section
        val right = left + tabSlot()
        when {
            right > tabScroll.y + area.width() -> tabScroll.scrollTo(right - area.width() + Dimens.EMOJI_SEARCH_PILL_INSET * dp)
            left < tabScroll.y -> tabScroll.scrollTo(left - tabSlot())
        }
    }

    private fun drawTabs(c: Canvas, colors: KeyboardColors) {
        val row = tabRow()
        tabScroll.reverse = rtl
        tabScroll.max = tabsStart() + sections.size * tabSlot() + Dimens.EMOJI_SEARCH_PILL_INSET * dp - row.width()
        tabScroll.step()
        val selected = selectedSection()
        if (selected != revealed) {
            revealed = selected
            reveal(selected)
        }
        c.save()
        c.clipRect(row)
        drawPill(c, row, colors)
        val w = tabSlot()
        for (i in sections.indices) {
            val cx = mirror(row.left + tabsStart() + w * (i + 0.5f) - tabScroll.y)
            val on = i == selected
            icons.draw(c, iconFor(sections[i].title), cx, row.centerY(), Dimens.EMOJI_TAB_ICON, if (on) colors.accent else colors.hint)
            if (on) {
                val half = Dimens.EMOJI_TAB_UNDERLINE * dp / 2
                val h = Dimens.EMOJI_TAB_UNDERLINE_HEIGHT * dp
                fill.color = colors.accent
                c.drawRoundRect(cx - half, row.bottom - h, cx + half, row.bottom, h / 2, h / 2, fill)
            }
        }
        c.restore()
    }

    private fun drawPill(c: Canvas, row: RectF, colors: KeyboardColors) {
        val h = Dimens.EMOJI_SEARCH_PILL_HEIGHT * dp
        val start = row.left + Dimens.EMOJI_SEARCH_PILL_INSET * dp - tabScroll.y
        val a = mirror(start)
        val b = mirror(start + Dimens.EMOJI_SEARCH_PILL_WIDTH * dp)
        fill.color = colors.letterKey
        c.drawRoundRect(minOf(a, b), row.centerY() - h / 2, maxOf(a, b), row.centerY() + h / 2, h / 2, h / 2, fill)
        icons.draw(c, R.drawable.ic_action_search, mirror(start + Dimens.EMOJI_SEARCH_ICON_X * dp), row.centerY(), Dimens.ICON, colors.label)
        label.textSize = sp(Dimens.EMOJI_SEARCH_SP)
        label.color = colors.hint
        label.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val fm = label.fontMetrics
        c.drawText(ctx.getString(R.string.emoji_search), mirror(start + Dimens.EMOJI_SEARCH_TEXT_X * dp), row.centerY() - (fm.ascent + fm.descent) / 2, label)
    }

    private fun drawBar(c: Canvas, colors: KeyboardColors) {
        val b = bar()
        label.textSize = sp(Dimens.FUNCTION_LABEL_SP)
        label.color = colors.label
        label.textAlign = Paint.Align.CENTER
        val fm = label.fontMetrics
        c.drawText(ctx.getString(R.string.emoji_abc), mirror(b.left + Dimens.EMOJI_BAR_SIDE * dp), b.centerY() - (fm.ascent + fm.descent) / 2, label)
        icons.draw(c, R.drawable.ic_delete, mirror(b.right - Dimens.EMOJI_BAR_SIDE * dp), b.centerY(), Dimens.ICON, colors.label)
    }

    private fun drawPopup(c: Canvas, p: TonePopup, colors: KeyboardColors) {
        val radius = Dimens.EMOJI_POPUP_RADIUS * dp
        fill.color = colors.popup
        fill.setShadowLayer(4 * dp, 0f, 1 * dp, colors.popupShadow)
        c.drawRoundRect(p.rect, radius, radius, fill)
        fill.clearShadowLayer()
        for (i in p.options.indices) {
            val cr = p.cell(i)
            if (i == p.selected) {
                fill.color = colors.letterKeyPressed
                c.drawRoundRect(cr, radius / 2, radius / 2, fill)
            }
            c.drawText(p.options[i], cr.centerX(), cr.centerY() + glyphBaseline, glyph)
        }
    }

    private fun iconFor(title: String) = when (title) {
        RECENT -> R.drawable.ic_emoji_recent
        "Smileys & Emotion" -> R.drawable.ic_emoji
        "People & Body" -> R.drawable.ic_emoji_people
        "Animals & Nature" -> R.drawable.ic_emoji_nature
        "Food & Drink" -> R.drawable.ic_emoji_food
        "Travel & Places" -> R.drawable.ic_emoji_travel
        "Activities" -> R.drawable.ic_emoji_activities
        "Objects" -> R.drawable.ic_emoji_objects
        "Flags" -> R.drawable.ic_emoji_flags
        else -> R.drawable.ic_emoji_symbols
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    private companion object {
        /** Not a Unicode group name: marks the recents section. */
        const val RECENT = "\u0000recent"
        const val LONG_PRESS_MS = 300L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 50L
    }
}

private const val TONE_COLUMNS = 6

/**
 * The tone panel's columns and rows for [n] options: six columns, or more when six would be taller than [maxHeight]
 * (the 26 options of couples and handshakes in landscape).
 */
internal fun tonePanelGrid(n: Int, cell: Float, pad: Float, maxHeight: Float): Pair<Int, Int> {
    val rowsFit = maxOf(1, ((maxHeight - 2 * pad) / cell).toInt())
    val cols = maxOf(minOf(n, TONE_COLUMNS), (n + rowsFit - 1) / rowsFit)
    return cols to (n + cols - 1) / cols
}

/** Above the pressed cell when there's room, else below it; always inside [top]..[bottom], covering the cell if it must. */
internal fun tonePanelTop(cellTop: Float, cellBottom: Float, h: Float, gap: Float, top: Float, bottom: Float): Float {
    val above = cellTop - h - gap
    val y = if (above >= top) above else cellBottom + gap
    return y.coerceIn(top, maxOf(top, bottom - h))
}
