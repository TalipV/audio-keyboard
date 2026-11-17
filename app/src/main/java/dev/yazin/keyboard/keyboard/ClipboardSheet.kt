package dev.yazin.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import dev.yazin.keyboard.R
import kotlin.math.abs

/** One entry in the clipboard panel. [key] identifies it to the keyboard: the clip's text, or a dictation's chunk id. */
data class ClipItem(val text: String, val kind: Kind, val key: String) {
    enum class Kind { DICTATION, PINNED, RECENT }
}

/**
 * Gboard's clipboard panel (spec §8): back and the title in the toolbar row, then cards in two columns — pending
 * dictations (spec §7.3), pinned clips, recent clips. Tap pastes; long-press offers Pin / Delete. Mirrored for Arabic.
 */
class ClipboardSheet(
    private val view: View,
    private val icons: Icons,
    private val items: () -> List<ClipItem>,
    private val enabled: () -> Boolean,
    private val onPaste: (ClipItem) -> Unit,
    private val onPin: (ClipItem, Boolean) -> Unit,
    private val onDelete: (ClipItem) -> Unit,
    private val onBack: () -> Unit,
) : Sheet {
    /** [rect] is in content coordinates, left to right from the area's leading edge. */
    private class Card(val item: ClipItem, val rect: RectF) {
        var layout: StaticLayout? = null
    }

    private class Header(val kind: ClipItem.Kind, val top: Float)

    private val ctx = view.context
    private val dp = ctx.resources.displayMetrics.density
    private val scroll = SheetScroll(view)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private var cards: List<Card> = emptyList()
    private var headers: List<Header> = emptyList()
    private var content = 0f
    private var builtFor = -1f
    private val area = RectF()
    private var rtl = false
    private var pressed: Card? = null
    private var actionsFor: Card? = null
    private var dragged = false
    private var onBackArrow = false
    private var consumed = false
    private val longPress = Runnable {
        actionsFor = pressed
        pressed = null
        view.invalidate()
    }

    fun onOpen() {
        refresh()
        scroll.scrollTo(0f)
    }

    /** The items changed (a copy, pin, delete, or a dictation arriving): lay out again. */
    fun refresh() {
        builtFor = -1f
        actionsFor = null
        view.invalidate()
    }

    override fun draw(c: Canvas, area: RectF, colors: KeyboardColors, rtl: Boolean) {
        this.area.set(area)
        this.rtl = rtl
        if (builtFor != area.width()) build(area.width())
        val body = body()
        scroll.max = content - body.height()
        scroll.step()
        drawHeaderRow(c, colors)
        c.save()
        c.clipRect(body)
        val top = body.top - scroll.y
        if (cards.isEmpty()) drawEmpty(c, body, colors)
        for (h in headers) drawSectionTitle(c, h, top, colors)
        for (card in cards) {
            val r = screenRect(card, top)
            if (r.bottom >= body.top && r.top <= body.bottom) drawCard(c, card, r, colors)
        }
        c.restore()
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragged = false
                onBackArrow = false
                consumed = false
                val a = actionsFor
                if (a != null) { // the Pin / Delete pills are up: use one, or tap anywhere to close them
                    val hit = actionRects(a, screenRect(a, body().top - scroll.y)).indexOfFirst { it.contains(e.x, e.y) }
                    actionsFor = null
                    consumed = true
                    if (hit >= 0) actions(a)[hit].second()
                    view.invalidate()
                    return
                }
                if (headerRow().contains(e.x, e.y)) {
                    onBackArrow = abs(e.x - backX()) < Dimens.TOOLBAR_SLOT / 2 * dp
                    return
                }
                scroll.down(e)
                pressed = cardAt(e.x, e.y)
                if (pressed != null) view.postDelayed(longPress, Dimens.CLIP_LONG_PRESS_MS)
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (consumed || onBackArrow) return
                scroll.move(e)
                if (scroll.dragging && !dragged) {
                    dragged = true
                    view.removeCallbacks(longPress)
                    pressed = null
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPress)
                if (consumed) return
                if (onBackArrow) return onBack()
                scroll.up(e)
                if (!dragged) pressed?.let { onPaste(it.item) }
                pressed = null
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    override fun cancel() {
        view.removeCallbacks(longPress)
        pressed = null
        actionsFor = null
        scroll.cancel()
    }

    private fun build(width: Float) {
        val m = Dimens.CLIP_MARGIN * dp
        val gap = Dimens.CLIP_GAP * dp
        val w = (width - 2 * m - gap) / 2
        val h = Dimens.CLIP_CARD_HEIGHT * dp
        val header = Dimens.EMOJI_HEADER_HEIGHT * dp
        val list = items()
        val outCards = ArrayList<Card>()
        val outHeaders = ArrayList<Header>()
        var y = 0f
        for (kind in ClipItem.Kind.entries) {
            val group = list.filter { it.kind == kind }
            if (group.isEmpty()) continue
            outHeaders += Header(kind, y)
            y += header
            group.forEachIndexed { i, item ->
                if (i > 0 && i % 2 == 0) y += h + gap
                val left = m + (i % 2) * (w + gap)
                outCards += Card(item, RectF(left, y, left + w, y + h))
            }
            y += h + gap
        }
        cards = outCards
        headers = outHeaders
        content = y
        builtFor = width
    }

    private fun headerRow() = RectF(area.left, area.top, area.right, area.top + Dimens.TOOLBAR_HEIGHT * dp)

    private fun body() = RectF(area.left, headerRow().bottom, area.right, area.bottom)

    /** A content x (from the leading edge) on screen. */
    private fun sx(x: Float) = if (rtl) area.right - x else area.left + x

    private fun backX() = sx(Dimens.TOOLBAR_FIRST_CENTER * dp)

    private fun screenRect(card: Card, top: Float): RectF {
        val a = sx(card.rect.left)
        val b = sx(card.rect.right)
        return RectF(minOf(a, b), top + card.rect.top, maxOf(a, b), top + card.rect.bottom)
    }

    private fun cardAt(x: Float, y: Float): Card? {
        val top = body().top - scroll.y
        return cards.firstOrNull { screenRect(it, top).contains(x, y) }
    }

    private fun actions(card: Card): List<Pair<String, () -> Unit>> = buildList {
        val kind = card.item.kind
        if (kind != ClipItem.Kind.DICTATION) {
            add(ctx.getString(if (kind == ClipItem.Kind.PINNED) R.string.clip_unpin else R.string.clip_pin) to { onPin(card.item, kind != ClipItem.Kind.PINNED) })
        }
        add(ctx.getString(R.string.clip_delete) to { onDelete(card.item) })
    }

    private fun actionRects(card: Card, r: RectF): List<RectF> {
        text.textSize = sp(Dimens.PANEL_PILL_SP)
        val labels = actions(card).map { it.first }
        val pad = Dimens.PANEL_PILL_PAD * dp
        val h = Dimens.PANEL_PILL_HEIGHT * dp
        val gap = Dimens.CLIP_GAP * dp
        val widths = labels.map { text.measureText(it) + 2 * pad }
        var x = r.centerX() - (widths.sum() + gap * (widths.size - 1)) / 2
        return widths.map { w -> RectF(x, r.centerY() - h / 2, x + w, r.centerY() + h / 2).also { x += w + gap } }
    }

    private fun drawHeaderRow(c: Canvas, colors: KeyboardColors) {
        val row = headerRow()
        val bx = backX()
        if (rtl) { c.save(); c.scale(-1f, 1f, bx, row.centerY()) } // the arrow points back, which is right in Arabic
        icons.draw(c, R.drawable.ic_arrow_back, bx, row.centerY(), Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        if (rtl) c.restore()
        text.textSize = sp(Dimens.CLIP_TITLE_SP)
        text.color = colors.label
        text.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val fm = text.fontMetrics
        c.drawText(ctx.getString(R.string.clip_title), sx(Dimens.CLIP_TITLE_X * dp), row.centerY() - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawSectionTitle(c: Canvas, h: Header, top: Float, colors: KeyboardColors) {
        text.textSize = sp(Dimens.EMOJI_HEADER_SP)
        text.color = colors.hint
        text.letterSpacing = 0.1f
        text.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val title = ctx.getString(when (h.kind) {
            ClipItem.Kind.DICTATION -> R.string.clip_section_dictation
            ClipItem.Kind.PINNED -> R.string.clip_section_pinned
            ClipItem.Kind.RECENT -> R.string.clip_section_recent
        })
        val fm = text.fontMetrics
        c.drawText(title, sx(Dimens.EMOJI_HEADER_X * dp), top + h.top + Dimens.EMOJI_HEADER_TEXT_Y * dp - (fm.ascent + fm.descent) / 2, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawCard(c: Canvas, card: Card, r: RectF, colors: KeyboardColors) {
        val radius = Dimens.CLIP_CARD_RADIUS * dp
        fill.color = if (card === pressed && !dragged) colors.letterKeyPressed else colors.letterKey
        c.drawRoundRect(r, radius, radius, fill)
        val pad = Dimens.CLIP_CARD_PAD * dp
        // Room for the corner badge (mic or pin) only on cards that have one.
        val badge = if (card.item.kind == ClipItem.Kind.RECENT) 0f else Dimens.CLIP_BADGE * dp
        val layout = card.layout ?: run {
            val tp = TextPaint(text).apply { textSize = sp(Dimens.CLIP_SP); color = colors.label; letterSpacing = 0f }
            val s = card.item.text.take(400) // the card shows three lines at most
            StaticLayout.Builder.obtain(s, 0, s.length, tp, (r.width() - 2 * pad - badge).toInt().coerceAtLeast(1))
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                .also { card.layout = it }
        }
        c.save()
        c.translate(if (rtl) r.left + pad + badge else r.left + pad, r.top + pad)
        layout.draw(c)
        c.restore()
        val icon = when (card.item.kind) {
            ClipItem.Kind.DICTATION -> R.drawable.ic_mic
            ClipItem.Kind.PINNED -> R.drawable.ic_pin
            ClipItem.Kind.RECENT -> 0
        }
        if (icon != 0) icons.draw(c, icon, if (rtl) r.left + pad + badge / 2 else r.right - pad - badge / 2, r.top + pad + badge / 2, Dimens.CLIP_BADGE, colors.hint)
        if (card === actionsFor) drawActions(c, card, r, colors)
    }

    private fun drawActions(c: Canvas, card: Card, r: RectF, colors: KeyboardColors) {
        val radius = Dimens.CLIP_CARD_RADIUS * dp
        fill.color = colors.letterKeyPressed
        c.drawRoundRect(r, radius, radius, fill)
        val labels = actions(card).map { it.first }
        actionRects(card, r).forEachIndexed { i, pr ->
            fill.color = if (i == 0) colors.accent else colors.letterKey
            c.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, fill)
            text.textSize = sp(Dimens.PANEL_PILL_SP)
            text.color = if (i == 0) colors.onAccent else colors.label
            text.textAlign = Paint.Align.CENTER
            val fm = text.fontMetrics
            c.drawText(labels[i], pr.centerX(), pr.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawEmpty(c: Canvas, body: RectF, colors: KeyboardColors) {
        val s = ctx.getString(if (enabled()) R.string.clip_empty else R.string.clip_off)
        val tp = TextPaint(text).apply { textSize = sp(Dimens.CLIP_SP); color = colors.hint; textAlign = Paint.Align.LEFT }
        val w = (body.width() - 48 * dp).toInt()
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, tp, w).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate(body.centerX() - w / 2f, body.centerY() - layout.height / 2f)
        layout.draw(c)
        c.restore()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)
}
