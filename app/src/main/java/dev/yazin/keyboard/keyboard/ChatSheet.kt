package dev.yazin.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.chat.ChatInfo
import dev.yazin.keyboard.engine.chat.Note
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * A Chat's panel (chat memory spec §7), in place of the keys: back and the Chat's name and app in the toolbar row, then
 * Goal with its presets, Profile, Notes, History with Record and Import, the "don't remember" switch and Forget. Input
 * is by dictation only, through [actions]. [memory] is null until the Chat is loaded. Scrolls; mirrored for Arabic.
 */
class ChatSheet(
    private val view: View,
    private val icons: Icons,
    private val memory: () -> ChatInfo?,
    private val actions: Actions,
) : Sheet {
    interface Actions {
        /** Dictates a new Goal ([preset] null), or sets a preset's text. */
        fun goal(preset: String?)
        fun correction()
        fun regenerate()
        fun note()
        fun removeNote(n: Note)
        fun record()
        fun importSteps()
        fun remember(on: Boolean)
        fun forget()
        fun back()

        /** Record is offered: Remember chats is on (spec §8). */
        fun canRecord(): Boolean = true
    }

    private enum class Kind { SECTION, TEXT, CARD, PILL, SWITCH, ROW }

    /** [rect] is in content coordinates, left to right from the area's leading edge. A pill without [tap] is disabled. */
    private class Item(
        val kind: Kind,
        val rect: RectF,
        val label: String = "",
        val layout: StaticLayout? = null,
        val on: Boolean = false,
        val tap: (() -> Unit)? = null,
        val hold: (() -> Unit)? = null,
    )

    private val ctx = view.context
    private val dp = ctx.resources.displayMetrics.density
    private val scroll = SheetScroll(view)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private val labels = HashMap<String, String>()
    private var items: List<Item> = emptyList()
    private var content = 0f
    private var builtFor = -1f
    private var builtColors: KeyboardColors? = null
    private var builtRtl = false
    private val area = RectF()
    private var rtl = false
    private var pressed: Item? = null
    /** A finger went down on the sheet: held ✦ opens it mid-gesture, and the rest of that gesture isn't the sheet's. */
    private var touching = false
    private var dragged = false
    private var inHeader = false
    private var onBackArrow = false
    /** The export steps show under History (Import was tapped). */
    private var steps = false
    /** "Don't remember" was just switched on: offer to forget what is kept. */
    private var offerForget = false
    private var regenerating = false
    /** Forget was tapped once; a second tap within [FORGET_CONFIRM_MS] forgets. */
    private var forgetArmed = false
    private val disarm = Runnable {
        forgetArmed = false
        refresh()
    }
    private val longPress = Runnable {
        val p = pressed ?: return@Runnable
        pressed = null
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        p.hold?.invoke()
        view.invalidate()
    }

    /** Opened from the toolbar: from the top, with nothing left over from last time. */
    fun onOpen() {
        steps = false
        offerForget = false
        regenerating = false
        forgetArmed = false
        view.removeCallbacks(disarm)
        cancel()
        refresh()
        scroll.scrollTo(0f)
    }

    /** The Chat's memory changed: lay out again. */
    fun refresh() {
        builtFor = -1f
        view.invalidate()
    }

    fun showSteps() {
        steps = true
        refresh()
    }

    override fun draw(c: Canvas, area: RectF, colors: KeyboardColors, rtl: Boolean) {
        this.area.set(area)
        this.rtl = rtl
        if (builtFor != area.width() || builtColors !== colors || builtRtl != rtl) build(area.width(), colors)
        val body = body()
        scroll.max = content - body.height()
        scroll.step()
        drawHeaderRow(c, colors)
        c.save()
        c.clipRect(body)
        val top = body.top - scroll.y
        for (item in items) {
            val r = screenRect(item, top)
            if (r.bottom >= body.top && r.top <= body.bottom) drawItem(c, item, r, colors)
        }
        c.restore()
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragged = false
                inHeader = headerRow().contains(e.x, e.y)
                onBackArrow = inHeader && abs(e.x - backX()) < Dimens.TOOLBAR_SLOT / 2 * dp
                if (inHeader) return
                scroll.down(e)
                pressed = itemAt(e.x, e.y)?.takeIf { it.tap != null || it.hold != null }
                if (pressed?.hold != null) view.postDelayed(longPress, Dimens.CLIP_LONG_PRESS_MS)
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching || inHeader) return
                scroll.move(e)
                if (scroll.dragging && !dragged) {
                    dragged = true
                    view.removeCallbacks(longPress)
                    pressed = null
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPress)
                if (!touching) return
                touching = false
                if (inHeader) {
                    if (onBackArrow) actions.back()
                    return
                }
                scroll.up(e)
                val p = pressed
                pressed = null
                if (!dragged) p?.tap?.invoke()
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    override fun cancel() {
        view.removeCallbacks(longPress)
        touching = false
        pressed = null
        scroll.cancel()
    }

    private fun build(width: Float, colors: KeyboardColors) {
        builtFor = width
        builtColors = colors
        builtRtl = rtl
        val info = memory()
        val m = Dimens.CHAT_MARGIN * dp
        val inner = width - 2 * m
        val gap = Dimens.CHAT_LINE_GAP * dp
        val out = ArrayList<Item>()
        var y = 0f

        fun section(res: Int) {
            y += Dimens.CHAT_SECTION_GAP * dp
            val h = Dimens.EMOJI_HEADER_HEIGHT * dp
            out += Item(Kind.SECTION, RectF(m, y, m + inner, y + h), ctx.getString(res))
            y += h
        }
        fun text(s: CharSequence, color: Int) {
            val l = layout(s, inner, color)
            out += Item(Kind.TEXT, RectF(m, y, m + inner, y + l.height), layout = l)
            y += l.height + gap
        }
        fun card(s: CharSequence, color: Int, tap: (() -> Unit)?, hold: (() -> Unit)?) {
            val pad = Dimens.CLIP_CARD_PAD * dp
            val l = layout(s, inner - 2 * pad, color)
            out += Item(Kind.CARD, RectF(m, y, m + inner, y + l.height + 2 * pad), layout = l, tap = tap, hold = hold)
            y += l.height + 2 * pad + gap
        }
        /** Pills flow onto as many lines as they need. */
        fun pills(vararg list: Item?) {
            val h = Dimens.PANEL_PILL_HEIGHT * dp
            val pad = Dimens.PANEL_PILL_PAD * dp
            text.textSize = sp(Dimens.PANEL_PILL_SP)
            var x = m
            val placed = list.filterNotNull()
            for (p in placed) {
                val w = minOf(inner, text.measureText(p.label) + 2 * pad)
                if (x > m && x + w > m + inner) {
                    x = m
                    y += h + gap
                }
                out += Item(Kind.PILL, RectF(x, y, x + w, y + h), p.label, on = p.on, tap = p.tap)
                x += w + gap
            }
            if (placed.isNotEmpty()) y += h + gap
        }
        fun pill(res: Int, on: Boolean = false, tap: (() -> Unit)?) = Item(Kind.PILL, RectF(), ctx.getString(res), on = on, tap = tap)
        fun row(kind: Kind, label: String, on: Boolean, tap: () -> Unit) {
            val h = Dimens.CHAT_ROW_HEIGHT * dp
            out += Item(kind, RectF(m, y, m + inner, y + h), label, on = on, tap = tap)
            y += h
        }

        if (info != null) {
            section(R.string.chat_section_topics)
            if (info.topics.isEmpty()) {
                text(ctx.getString(R.string.chat_no_topics), colors.hint)
            } else {
                for (topic in info.topics) card(topic, colors.label, tap = null, hold = null)
            }

            section(R.string.chat_section_goal)
            val goal = info.goal
            card(goal.ifEmpty { ctx.getString(R.string.chat_no_goal) }, if (goal.isEmpty()) colors.hint else colors.label, tap = { actions.goal(null) }, hold = null)
            pills(*PRESETS.map { (name, sentence) ->
                val s = ctx.getString(sentence)
                pill(name, on = goal == s) { actions.goal(s) }
            }.toTypedArray())

            section(R.string.chat_section_profile)
            for (line in info.corrections) text(line, colors.label)
            if (info.written.isNotBlank()) {
                text(info.written, colors.label)
                if (info.writtenAt > 0) text(ctx.getString(R.string.chat_profile_written, day(info.writtenAt)), colors.hint)
            } else {
                text(ctx.getString(R.string.chat_profile_unwritten), colors.hint)
            }
            pills(
                pill(R.string.chat_add_correction) { actions.correction() },
                if (info.count == 0) null
                else if (regenerating) pill(R.string.chat_regenerating, tap = null)
                else pill(R.string.chat_regenerate) {
                    regenerating = true
                    actions.regenerate()
                    refresh()
                },
            )

            section(R.string.chat_section_notes)
            if (info.notes.isEmpty()) {
                text(ctx.getString(R.string.chat_no_notes), colors.hint)
            } else {
                for (n in info.notes) {
                    val s = SpannableStringBuilder(n.text).append("  ")
                    val start = s.length
                    s.append(day(n.day)).setSpan(ForegroundColorSpan(colors.hint), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    card(s, colors.label, tap = null, hold = { actions.removeNote(n) })
                }
                text(ctx.getString(R.string.chat_notes_hint), colors.hint)
            }
            pills(pill(R.string.chat_add_note) { actions.note() })

            section(R.string.chat_section_history)
            if (info.count > 0) text(ctx.getString(R.string.chat_history, info.count, month(info.first)), colors.label)
            else text(ctx.getString(R.string.chat_no_history), colors.hint)
            pills(
                if (info.remember && actions.canRecord()) pill(R.string.chat_record) { actions.record() } else null,
                pill(R.string.chat_import) { actions.importSteps() },
            )
            if (steps) text(ctx.getString(R.string.style_import_steps), colors.hint)

            y += Dimens.CHAT_SECTION_GAP * dp
            row(Kind.SWITCH, ctx.getString(R.string.chat_dont_remember), on = !info.remember) {
                offerForget = info.remember
                actions.remember(!info.remember)
                refresh()
            }
            if (offerForget && !info.remember) {
                pills(pill(R.string.chat_forget_kept) {
                    offerForget = false
                    actions.forget()
                    refresh()
                })
            }
            row(Kind.ROW, ctx.getString(if (forgetArmed) R.string.chat_forget_again else R.string.chat_forget), on = forgetArmed) {
                view.removeCallbacks(disarm)
                if (forgetArmed) {
                    forgetArmed = false
                    actions.forget()
                } else {
                    forgetArmed = true
                    view.postDelayed(disarm, FORGET_CONFIRM_MS)
                }
                refresh()
            }
            y += m
        }
        items = out
        content = y
    }

    private fun layout(s: CharSequence, width: Float, color: Int): StaticLayout {
        val tp = TextPaint(text).apply { textSize = sp(Dimens.CHAT_SP); this.color = color; letterSpacing = 0f }
        return StaticLayout.Builder.obtain(s, 0, s.length, tp, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()
    }

    private fun headerRow() = RectF(area.left, area.top, area.right, area.top + Dimens.TOOLBAR_HEIGHT * dp)

    private fun body() = RectF(area.left, headerRow().bottom, area.right, area.bottom)

    /** A content x (from the leading edge) on screen. */
    private fun sx(x: Float) = if (rtl) area.right - x else area.left + x

    private fun backX() = sx(Dimens.TOOLBAR_FIRST_CENTER * dp)

    private fun screenRect(item: Item, top: Float): RectF {
        val a = sx(item.rect.left)
        val b = sx(item.rect.right)
        return RectF(minOf(a, b), top + item.rect.top, maxOf(a, b), top + item.rect.bottom)
    }

    private fun itemAt(x: Float, y: Float): Item? {
        val top = body().top - scroll.y
        return items.firstOrNull { screenRect(it, top).contains(x, y) }
    }

    private fun drawHeaderRow(c: Canvas, colors: KeyboardColors) {
        val row = headerRow()
        val bx = backX()
        if (rtl) { c.save(); c.scale(-1f, 1f, bx, row.centerY()) } // the arrow points back, which is right in Arabic
        icons.draw(c, R.drawable.ic_arrow_back, bx, row.centerY(), Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        if (rtl) c.restore()
        val key = memory()?.key ?: return
        text.textSize = sp(Dimens.CLIP_TITLE_SP)
        text.color = colors.label
        text.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val room = row.width() - (Dimens.CLIP_TITLE_X + Dimens.CHAT_MARGIN) * dp
        val title = TextUtils.ellipsize(ctx.getString(R.string.chat_title, key.title, appLabel(key.app)), text, room, TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        c.drawText(title, sx(Dimens.CLIP_TITLE_X * dp), row.centerY() - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawItem(c: Canvas, item: Item, r: RectF, colors: KeyboardColors) {
        val down = item === pressed && !dragged
        when (item.kind) {
            Kind.SECTION -> {
                text.textSize = sp(Dimens.EMOJI_HEADER_SP)
                text.color = colors.hint
                text.letterSpacing = 0.1f
                drawLabel(c, item.label, r, r.top + Dimens.EMOJI_HEADER_TEXT_Y * dp)
                text.letterSpacing = 0f
            }
            Kind.TEXT -> drawLayout(c, item.layout ?: return, r.left, r.top)
            Kind.CARD -> {
                val radius = Dimens.CLIP_CARD_RADIUS * dp
                fill.color = if (down) colors.letterKeyPressed else colors.letterKey
                c.drawRoundRect(r, radius, radius, fill)
                val pad = Dimens.CLIP_CARD_PAD * dp
                drawLayout(c, item.layout ?: return, r.left + pad, r.top + pad)
            }
            Kind.PILL -> {
                fill.color = when {
                    item.on -> colors.accent
                    down -> colors.letterKeyPressed
                    else -> colors.letterKey
                }
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                text.textSize = sp(Dimens.PANEL_PILL_SP)
                text.color = when {
                    item.on -> colors.onAccent
                    item.tap == null -> colors.hint
                    else -> colors.label
                }
                text.textAlign = Paint.Align.CENTER
                val fm = text.fontMetrics
                c.drawText(item.label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
                text.textAlign = Paint.Align.LEFT
            }
            Kind.SWITCH, Kind.ROW -> {
                if (down) {
                    fill.color = colors.letterKeyPressed
                    val radius = Dimens.CLIP_CARD_RADIUS * dp
                    c.drawRoundRect(r, radius, radius, fill)
                }
                val sw = if (item.kind == Kind.SWITCH) Dimens.CHAT_SWITCH_WIDTH * dp else 0f
                text.textSize = sp(Dimens.CHAT_SP)
                text.color = if (item.kind == Kind.ROW && item.on) colors.accent else colors.label
                val room = RectF(r).apply { if (rtl) left += sw + 8 * dp else right -= sw + 8 * dp }
                drawLabel(c, item.label, room, r.centerY())
                if (item.kind == Kind.SWITCH) drawSwitch(c, r, item.on, colors)
            }
        }
    }

    /** [label] at [r]'s leading edge, centred on [cy], cut to fit. Uses [text]'s size and colour. */
    private fun drawLabel(c: Canvas, label: String, r: RectF, cy: Float) {
        text.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val s = TextUtils.ellipsize(label, text, r.width(), TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        c.drawText(s, if (rtl) r.right else r.left, cy - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
    }

    /** A Material-style switch at [r]'s trailing edge; on, the thumb sits at the trailing end. */
    private fun drawSwitch(c: Canvas, r: RectF, on: Boolean, colors: KeyboardColors) {
        val w = Dimens.CHAT_SWITCH_WIDTH * dp
        val h = Dimens.CHAT_SWITCH_HEIGHT * dp
        val trailing = if (rtl) r.left else r.right
        val left = if (rtl) trailing else trailing - w
        val track = RectF(left, r.centerY() - h / 2, left + w, r.centerY() + h / 2)
        fill.color = if (on) colors.accent else colors.functionKey
        c.drawRoundRect(track, h / 2, h / 2, fill)
        val thumb = Dimens.CHAT_SWITCH_THUMB * dp / 2
        val inset = h / 2
        val atTrailing = if (rtl) track.left + inset else track.right - inset
        val atLeading = if (rtl) track.right - inset else track.left + inset
        fill.color = if (on) colors.onAccent else colors.letterKey
        c.drawCircle(if (on) atTrailing else atLeading, track.centerY(), thumb, fill)
    }

    private fun appLabel(app: String): String = labels.getOrPut(app) {
        val pm = ctx.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)
    }

    private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun month(ms: Long) = MONTH.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    private companion object {
        const val FORGET_CONFIRM_MS = 3_000L
        /** Goal presets (spec §5.1): the pill and the sentence it fills in. */
        val PRESETS = listOf(
            R.string.chat_preset_banter to R.string.chat_preset_banter_text,
            R.string.chat_preset_closer to R.string.chat_preset_closer_text,
            R.string.chat_preset_transactional to R.string.chat_preset_transactional_text,
            R.string.chat_preset_professional to R.string.chat_preset_professional_text,
        )
        /** No-break spaces: a date never wraps across lines. */
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d\u00A0MMM")
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM\u00A0yyyy")
    }
}
