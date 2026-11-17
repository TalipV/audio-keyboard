package dev.yazin.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.emoji.Emoji
import dev.yazin.keyboard.engine.emoji.EmojiGroup
import dev.yazin.keyboard.engine.emoji.EmojiIndex
import dev.yazin.keyboard.engine.emoji.EmojiPrefs

/**
 * Gboard's emoji search, over the letter keys: the query field with a back arrow in the toolbar row, and below it a
 * row of matching emoji that scrolls sideways. KeyboardService types the query here, never into the app's field. A
 * blank query shows the recents, or the first smileys before any were used. Mirrored for Arabic.
 */
class EmojiSearchBar(
    private val view: View,
    private val icons: Icons,
    private val catalog: () -> List<EmojiGroup>,
    /** Null while it loads; [refresh] once it has. */
    private val index: () -> EmojiIndex?,
    private val prefs: EmojiPrefs,
    /** False in password and incognito fields: no recents, no remembered tones (spec §4). */
    private val remember: () -> Boolean,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
) : Sheet {
    /** [emoji], shown and typed as [typed]: its remembered tone, or exactly what a recent was. */
    private class Result(val emoji: Emoji, val typed: String)

    private sealed interface Target
    private data object Back : Target
    private data class Cell(val index: Int) : Target

    private val ctx = view.context
    private val dp = ctx.resources.displayMetrics.density
    private val scroll = SheetScroll(view, horizontal = true)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(Dimens.EMOJI_SP) }
    private val glyphBaseline = glyph.fontMetrics.let { -(it.ascent + it.descent) / 2 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private val area = RectF()
    private var rtl = false
    private val query = StringBuilder()
    private var results: List<Result> = emptyList()
    private var target: Target? = null
    private var downInRow = false

    /** Starts a new search: an empty query, the recents. */
    fun onOpen() {
        query.clear()
        cancel()
        refresh()
    }

    fun type(s: String) {
        query.append(s)
        refresh()
    }

    /** Backspace: the query's last character; nothing once it is empty. */
    fun delete() {
        if (query.isEmpty()) return
        query.setLength(query.length - Character.charCount(query.codePointBefore(query.length)))
        refresh()
    }

    /** Searches again: the query changed, or the index finished loading. */
    fun refresh() {
        results = if (query.isBlank()) idle()
        else index()?.search(query.toString(), MAX_RESULTS).orEmpty().map { Result(it, prefs.forTap(it)) }
        target = null
        scroll.scrollTo(0f)
        view.invalidate()
    }

    private fun idle(): List<Result> {
        val groups = catalog()
        val byBase = groups.flatMap { it.emoji }.associateBy { it.base }
        val recent = prefs.recent.mapNotNull { (base, typed) -> byBase[base]?.let { Result(it, typed) } }
        return recent.ifEmpty { groups.firstOrNull()?.emoji.orEmpty().take(MAX_RESULTS).map { Result(it, prefs.forTap(it)) } }
    }

    override fun draw(c: Canvas, area: RectF, colors: KeyboardColors, rtl: Boolean) {
        this.area.set(area)
        this.rtl = rtl
        drawField(c, colors)
        drawResults(c, colors)
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = targetAt(e.x, e.y)
                downInRow = resultRow().contains(e.x, e.y)
                if (downInRow) scroll.down(e)
            }
            MotionEvent.ACTION_MOVE -> if (downInRow) {
                scroll.move(e)
                if (scroll.dragging) target = null
            }
            MotionEvent.ACTION_UP -> {
                if (downInRow) scroll.up(e)
                when (val t = target) {
                    Back -> onBack()
                    is Cell -> results.getOrNull(t.index)?.let(::pick)
                    null -> Unit
                }
                target = null
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        view.invalidate()
    }

    override fun cancel() {
        target = null
        downInRow = false
        scroll.cancel()
    }

    /** Enter: the best match, as if tapped. */
    fun pickFirst() {
        if (query.isNotBlank()) results.firstOrNull()?.let(::pick)
    }

    private fun pick(r: Result) {
        prefs.used(r.emoji.base, r.typed, remember())
        onEmoji(r.typed)
    }

    private fun fieldRow() = RectF(area.left, area.top, area.right, area.top + Dimens.TOOLBAR_HEIGHT * dp)

    private fun resultRow() = RectF(area.left, fieldRow().bottom, area.right, fieldRow().bottom + Dimens.EMOJI_RESULTS_HEIGHT * dp)

    /** Layout is computed left to right and mirrored for Arabic. */
    private fun mirror(x: Float) = if (rtl) area.left + area.right - x else x

    /** The emoji panel's cell width, so results line up with its grid. */
    private fun cell(): Float {
        val inner = area.width() - 2 * Dimens.EMOJI_SIDE_MARGIN * dp
        return inner / maxOf(Dimens.EMOJI_COLUMNS, (inner / (Dimens.EMOJI_MAX_CELL * dp)).toInt())
    }

    private fun cellX(i: Int) = mirror(area.left + Dimens.EMOJI_SIDE_MARGIN * dp + cell() * (i + 0.5f) - scroll.y)

    private fun targetAt(x: Float, y: Float): Target? {
        val lx = mirror(x) - area.left
        return when {
            fieldRow().contains(x, y) -> if (lx < Dimens.STRIP_START * dp) Back else null
            resultRow().contains(x, y) -> {
                val i = ((lx + scroll.y - Dimens.EMOJI_SIDE_MARGIN * dp) / cell()).toInt()
                if (i in results.indices) Cell(i) else null
            }
            else -> null
        }
    }

    private fun drawField(c: Canvas, colors: KeyboardColors) {
        val row = fieldRow()
        val bx = mirror(area.left + Dimens.TOOLBAR_FIRST_CENTER * dp)
        if (rtl) { c.save(); c.scale(-1f, 1f, bx, row.centerY()) } // the arrow points back, which is right in Arabic
        icons.draw(c, R.drawable.ic_arrow_back, bx, row.centerY(), Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        if (rtl) c.restore()

        val h = Dimens.EMOJI_SEARCH_PILL_HEIGHT * dp
        val a = mirror(area.left + Dimens.STRIP_START * dp)
        val b = mirror(area.right - Dimens.EMOJI_SEARCH_PILL_INSET * dp)
        val pill = RectF(minOf(a, b), row.centerY() - h / 2, maxOf(a, b), row.centerY() + h / 2)
        fill.color = colors.letterKey
        c.drawRoundRect(pill, h / 2, h / 2, fill)

        val pad = Dimens.EMOJI_QUERY_PAD * dp
        val inner = RectF(pill.left + pad, pill.top, pill.right - pad, pill.bottom)
        val s = query.toString()
        text.textSize = sp(Dimens.EMOJI_SEARCH_SP)
        text.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        val baseline = row.centerY() - (fm.ascent + fm.descent) / 2
        val shown = s.ifEmpty { ctx.getString(R.string.emoji_search) }
        val w = text.measureText(shown)
        // The query's end stays in view: an Arabic query ends on its left, a Latin one on its right.
        val endsLeft = s.firstOrNull { it.isLetter() }?.let { Character.getDirectionality(it).let { d -> d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } } ?: rtl
        val left = when {
            w > inner.width() -> if (endsLeft) inner.left else inner.right - w
            rtl -> inner.right - w
            else -> inner.left
        }
        c.save()
        c.clipRect(inner.left - Dimens.EMOJI_CARET_WIDTH * dp, pill.top, inner.right + Dimens.EMOJI_CARET_WIDTH * dp, pill.bottom)
        text.color = if (s.isEmpty()) colors.hint else colors.label
        c.drawText(shown, left, baseline, text)
        val caretX = when {
            s.isEmpty() -> if (rtl) inner.right else inner.left
            endsLeft -> left
            else -> left + w
        }
        val ch = Dimens.EMOJI_CARET_HEIGHT * dp / 2
        val cw = Dimens.EMOJI_CARET_WIDTH * dp / 2
        fill.color = colors.accent
        c.drawRect(caretX - cw, row.centerY() - ch, caretX + cw, row.centerY() + ch, fill)
        c.restore()
    }

    private fun drawResults(c: Canvas, colors: KeyboardColors) {
        val row = resultRow()
        val cell = cell()
        scroll.reverse = rtl
        scroll.max = 2 * Dimens.EMOJI_SIDE_MARGIN * dp + cell * results.size - row.width()
        scroll.step()
        c.save()
        c.clipRect(row)
        if (results.isEmpty() && query.isNotBlank()) {
            text.textSize = sp(Dimens.CHIP_SP)
            text.textAlign = Paint.Align.CENTER
            text.color = colors.hint
            val fm = text.fontMetrics
            c.drawText(ctx.getString(R.string.emoji_no_results), row.centerX(), row.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
        for (i in results.indices) {
            val cx = cellX(i)
            if (cx < row.left - cell || cx > row.right + cell) continue
            if (target == Cell(i)) {
                fill.color = colors.letterKeyPressed
                c.drawCircle(cx, row.centerY(), cell * 0.46f, fill)
            }
            c.drawText(results[i].typed, cx, row.centerY() + glyphBaseline, glyph)
        }
        c.restore()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    private companion object {
        const val MAX_RESULTS = 60
    }
}
