package dev.yazin.keyboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.text.TextPaint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.yazin.keyboard.engine.GlidePoint
import dev.yazin.keyboard.engine.KeyModel
import dev.yazin.keyboard.engine.voice.VoiceUi
import dev.yazin.keyboard.BuildConfig
import dev.yazin.keyboard.R
import dev.yazin.keyboard.ime.Caps
import dev.yazin.keyboard.ime.EnterAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The whole keyboard as one Canvas-drawn view: toolbar, keys, previews and long-press panels.
 * The top [Dimens.POPUP_HEADROOM] is transparent so popups can draw over the app; KeyboardService
 * excludes it from the insets and the touchable region.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onEvent: (TouchEvent) -> Unit,
    private val onSettings: () -> Unit,
    private val onStrip: (StripItem, Boolean) -> Unit,
    private val onUndo: () -> Unit,
    private val onVoice: (VoiceInput) -> Unit,
    private val onToolbar: (ToolbarInput) -> Unit,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val headroom = Dimens.POPUP_HEADROOM * dp
    private val toolbarHeight = Dimens.TOOLBAR_HEIGHT * dp
    private val keyGap get() = if (landscape) Dimens.LANDSCAPE_KEY_GAP else Dimens.KEY_GAP
    private val bottomPadding get() = (if (landscape) Dimens.LANDSCAPE_BOTTOM_PADDING else Dimens.BOTTOM_PADDING) * dp
    private fun rowGapOf(l: Layout) = if (landscape) Dimens.LANDSCAPE_ROW_GAP else l.rowGap

    var colors = Themes.LIGHT
        set(v) { field = v; invalidate() }
    var caps = Caps.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterAction = EnterAction.NEWLINE
        set(v) { if (field != v) { field = v; invalidate() } }
    var spaceLabel = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The previous and next language for the space-bar swipe, as the space bar names them; null with one language. */
    var spaceNeighbours: Pair<String, String>? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Cursor control from a held space bar: the keys dim under a caption (as Samsung). */
    var cursorCaption: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** How far the language label is dragged along the space bar, px (0 = at rest). */
    private var spaceDx = 0f
    private var spaceAnim: ValueAnimator? = null
    /** The keys are right to left (Arabic): only the keys themselves follow it, e.g. the mirrored newline arrow. */
    var rtl = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * Whether the toolbar, suggestion strip, chips, orb and panels mirror for [rtl] (Gboard does). Off: they stay where
     * they are in every language, so the owner's thumbs never relearn the buttons when switching to Arabic.
     */
    private val barRtl = false
    var showPreview = true
    /** Three slots (leading → trailing) while typing; empty when there is nothing to suggest. */
    var strip: List<StripItem?> = emptyList()
        set(v) { if (field != v) { field = v; toolbarRevealed = false; invalidate() } }
    /** The leading grid was tapped: the toolbar icons show instead of the strip until the strip changes (as Gboard). */
    private var toolbarRevealed = false
        set(v) { field = v; syncAutofill() }
    /** Autofill chips over the strip area; KeyboardService puts it beside this view in the input view. */
    val autofill = AutofillStrip(context)
    /** Gboard's undo chip, shown right after an autocorrect. */
    var showUndo = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Voice state (AI orb spec §2): while [VoiceUi.orb] the band replaces toolbar and keys. */
    var voice = VoiceUi()
        set(v) {
            if (field == v) return
            field = v
            showBand()
        }
    /** Recording a chat (chat memory spec §4.2): while set, the view is the orb band with this caption instead of [voice]. */
    var recordCaption: String? = null
        set(v) {
            if (field == v) return
            field = v
            showBand()
        }
    /** What the band draws: [voice], or the recording. */
    private var band = VoiceUi()
    /** The orb band replaces toolbar and keys. */
    val orbShown: Boolean get() = band.orb

    private fun showBand() {
        val next = recordCaption?.let { VoiceUi(orb = true, caption = it) } ?: voice
        val resized = band.orb != next.orb
        if (resized && next.orb) dropToolbarTouch()
        band = next
        syncAutofill()
        // A new size, or new controls whose touch region the service only recomputes on layout.
        if (resized || next.orb) requestLayout()
        invalidate()
    }
    /** ✦ in the toolbar; hidden in password and incognito fields. */
    var showSpark = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** ↶ beside the orb: a ✦ edit or a dictation can be undone. */
    var orbUndo = false
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } } // ↶ changes the touch region
    /** The accessibility service is on; otherwise the thinking caption says screen context is off. */
    var screenContext = true
    /** The password manager's key in the idle toolbar (slot 2), in sign-in fields. */
    var showPasswords = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Gboard hides the mic in password fields. */
    var showMic = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The emoji or clipboard panel (spec §8), drawn in place of the toolbar and keys; null shows the keyboard. */
    var sheet: Sheet? = null
        set(v) {
            if (field === v) return
            field?.cancel()
            if (v != null) {
                // Keys still held (a delete repeating, a shift) would never see their release under the sheet.
                touch.cancelAll()
                removeCallbacks(timer)
                dropToolbarTouch()
            }
            field = v
            toolbarRevealed = false
            invalidate()
        }
    /** Emoji search: drawn in place of the toolbar plus a row of results, which the keyboard grows by; the keys stay. */
    var searchBar: Sheet? = null
        set(v) {
            if (field === v) return
            field?.cancel()
            // The keys move down a row: a held key would be released somewhere else.
            touch.cancelAll()
            removeCallbacks(timer)
            dropToolbarTouch()
            barTouch = false
            field = v
            toolbarRevealed = false
            requestLayout()
            rebuildGrid()
        }
    /** The touch stream since the last down belongs to [searchBar]. */
    private var barTouch = false

    /** The toolbar, or the search bar with its results. */
    private fun barHeight() = toolbarHeight + if (searchBar != null) Dimens.EMOJI_RESULTS_HEIGHT * dp else 0f

    /** Android 11+ autofill chips; while any show they replace suggestions and chips in the strip, as Gboard. */
    fun setAutofill(pinned: List<View>, chips: List<View>) {
        autofill.show(pinned, chips)
        toolbarRevealed = false
        invalidate()
    }

    private fun syncAutofill() {
        autofill.showing = !toolbarRevealed && !band.orb && sheet == null && searchBar == null
    }

    /** Icon drawing for sheets, through this view's tinted-drawable cache. */
    val iconPainter = Icons { c, res, x, y, s, color -> drawIcon(c, res, x, y, s, color) }
    /** Oldest pending dictation (spec §7.3), shown as a chip while the strip is free. */
    var pendingChip: String? = null
        set(v) { if (field != v) { field = v; toolbarRevealed = false; invalidate() } }
    /** Gboard's clipboard chip (spec §8.1): the newest clip for a minute, unless a dictation chip is waiting. */
    var clipChip: String? = null
        set(v) { if (field != v) { field = v; toolbarRevealed = false; invalidate() } }
    /** The Chat's title (chat memory spec §7) while the keyboard is in one: a chip in the idle toolbar, and ✦ held opens its panel. */
    var chatChip: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private var level = 0f
    /** Mic loudness 0..1, about 50 times a second while recording; smoothed so the ring breathes instead of jittering. */
    var voiceLevel: Float
        get() = level
        set(v) {
            level += (v - level) * if (v > level) 0.6f else 0.15f
            if (voice.orb && voice.recording) invalidate()
        }
    private val orb = Orb(context)
    private var orbDownX = 0f
    private var orbDownY = 0f
    private var orbPointer = -1
    private var stripLongPressed = false
    private var stripDownX = 0f
    /** The toolbar touch went down on ✦ in a Chat: held, it opens the panel instead of starting ✦. */
    private var sparkDown = false
    private val stripLongPress = Runnable {
        stripLongPressed = true
        when {
            sparkDown -> onToolbar(ToolbarInput.Chat)
            chipVisible() && chipRect().contains(stripDownX, chipRect().centerY()) -> chipTapped(longPress = true)
            else -> stripItemAt(stripDownX)?.let { onStrip(it, true) }
        }
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(FONT, Typeface.BOLD) }
    /** Compact vertical metrics; set before [setLayout]. */
    var landscape = false
    var touchConfig: TouchConfig
        get() = touch.config
        set(v) { touch.config = v }

    /** Top of the visible keyboard in this view's coordinates; everything above is transparent headroom. */
    val keyboardTop: Int get() = headroom.roundToInt()

    /** While [voice] shows the orb: where the app ends, through the orb's centre (see [Orb]). */
    val orbEdge: Int get() = (headroom + Dimens.ORB_BAND * dp / 2).roundToInt()

    private var layout = Layout(emptyList())
    private var rowsHeight = 0f
    private var navInset = 0
    private var grid: KeyGrid? = null
    private var preview: PlacedKey? = null
    private var panel: AltPanel? = null
    private var altIndex = 0
    private var toolbarPointer = -1

    private val touch = TouchHandler { ev ->
        when (ev) {
            is TouchEvent.Press ->
                preview = ev.key.takeIf { it.key.action == KeyAction.TEXT && it.key.style == KeyStyle.LETTER }
            is TouchEvent.Unpress -> if (preview === ev.key) preview = null
            is TouchEvent.ShowAlts -> { panel = ev.panel; preview = null }
            is TouchEvent.SelectAlt -> altIndex = ev.index
            TouchEvent.HideAlts -> panel = null
            is TouchEvent.Glide -> {
                trail = ev.points
                trailLiftAt = if (ev.ended) SystemClock.uptimeMillis() else STILL_DOWN
                preview = null
            }
            TouchEvent.GlideCancelled -> trailLiftAt = SystemClock.uptimeMillis()
            is TouchEvent.SpaceDrag -> { spaceAnim?.cancel(); spaceDx = ev.dx }
            is TouchEvent.SpaceSwipe -> { settleSpace(ev); return@TouchHandler } // passed on once the label has slid
            else -> Unit
        }
        onEvent(ev)
    }

    /**
     * Lift after a space-bar drag: the label slides the rest of the way and the language changes (the service hears
     * [ev] then), or it springs back. The new name is shown at once, so the system's subtype switch never flickers.
     */
    private fun settleSpace(ev: TouchEvent.SpaceSwipe) {
        val neighbours = spaceNeighbours
        val direction = if (neighbours == null) 0 else ev.direction
        spaceAnim?.cancel()
        spaceAnim = ValueAnimator.ofFloat(spaceDx, direction * ev.key.width).apply {
            duration = Dimens.SPACE_SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { spaceDx = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    spaceDx = 0f
                    if (direction != 0 && neighbours != null) {
                        spaceLabel = if (direction > 0) neighbours.second else neighbours.first
                        onEvent(ev)
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    /** The glide trail: the stroke's points, and when the finger lifted ([STILL_DOWN] while gliding). */
    private var trail: List<GlidePoint> = emptyList()
    private var trailLiftAt = STILL_DOWN
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    private val timer = Runnable {
        touch.onTimer(SystemClock.uptimeMillis())
        scheduleTimer()
        invalidate()
    }

    private val rect = RectF()
    private val bounds = Rect()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = FONT
    }
    private val icons = HashMap<Int, Drawable>()
    private val latency = LatencyLog()

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            // The bar's height even while it is hidden: apps in full screen report 0, then the bar slides in over the
            // keys (seen on a Samsung phone with button navigation). Like the Samsung keyboard, always leave room.
            val nav = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom,
            )
            if (nav != navInset) {
                navInset = nav
                requestLayout()
            }
            insets
        }
    }

    /**
     * Shows [layout]. [reference] (the letters page) fixes the keyboard height so changing pages never moves the app;
     * [keyScale] scales key heights only, gaps stay fixed.
     */
    fun setLayout(layout: Layout, reference: Layout, keyScale: Float) {
        this.layout = layout
        val rows = LayoutGeometry.naturalRowsHeight(reference, dp, rowGap = 0f) * keyScale + reference.rows.size * rowGapOf(reference) * dp
        if (rows != rowsHeight) {
            rowsHeight = rows
            requestLayout()
        }
        rebuildGrid()
    }

    fun cancelTouches() {
        touch.cancelAll()
        removeCallbacks(timer)
        dropToolbarTouch()
        orbPointer = -1
        sheet?.cancel()
        searchBar?.cancel()
        barTouch = false
        invalidate()
    }

    /** Forgets a toolbar touch whose release won't come here: a sheet or the orb took over mid-gesture. */
    private fun dropToolbarTouch() {
        toolbarPointer = -1
        stripLongPressed = false
        sparkDown = false
        removeCallbacks(stripLongPress)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The orb band keeps the keyboard's full height (as Samsung's voice panel does): the app doesn't jump, and nothing
        // floats over its text field (Telegram's reply preview sat right where half the orb used to be).
        val body = barHeight() + rowsHeight + bottomPadding
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (headroom + body + navInset).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildGrid()

    private fun rebuildGrid() {
        if (width == 0 || layout.rows.isEmpty()) return
        val g = LayoutGeometry.place(layout, width.toFloat(), headroom + barHeight(), dp, rowsHeight, rowGapOf(layout), keyGap)
        grid = g
        touch.grid = g
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        sheet?.let { s ->
            // A sheet owns the whole keyboard.
            s.onTouch(e)
            invalidate()
            return true
        }
        if (band.orb) {
            onOrbTouch(e)
            return true
        }
        searchBar?.let { bar ->
            // A gesture that starts on the bar is the bar's alone; further fingers on it are ignored.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) barTouch = e.y < headroom + barHeight()
            if (barTouch) {
                bar.onTouch(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) barTouch = false
                invalidate()
                return true
            }
        }
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i)
                val y = e.getY(i)
                when {
                    y < headroom + barHeight() -> if (searchBar == null && toolbarPointer < 0) {
                        toolbarPointer = id
                        stripDownX = x
                        stripLongPressed = false
                        sparkDown = chatChip != null && showSpark && !(showMic && isOnMic(x)) && isOnSpark(x)
                        val onChip = chipVisible() && chipRect().contains(x, chipRect().centerY())
                        if (sparkDown || (!toolbarRevealed && (onChip || stripItemAt(x) != null))) postDelayed(stripLongPress, STRIP_LONG_PRESS_MS)
                    }
                    else -> {
                        toolbarRevealed = false
                        touch.down(id, x, y, e.eventTime)
                    }
                }
            }
            MotionEvent.ACTION_MOVE ->
                for (p in 0 until e.pointerCount) touch.move(e.getPointerId(p), e.getX(p), e.getY(p), e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> when (id) {
                toolbarPointer -> {
                    toolbarPointer = -1
                    removeCallbacks(stripLongPress)
                    val x = e.getX(i)
                    val lx = mirror(x)
                    when {
                        stripLongPressed -> Unit
                        showMic && isOnMic(x) -> onVoice(VoiceInput.Start(prompt = false))
                        showSpark && isOnSpark(x) -> onVoice(VoiceInput.Start(prompt = true))
                        showUndo && !toolbarRevealed && abs(lx - (width - Dimens.UNDO_CENTER_FROM_END * dp)) < (Dimens.UNDO_WIDTH / 2 + 4) * dp -> onUndo()
                        lx < Dimens.STRIP_START * dp -> if (stripBusy() && !toolbarRevealed) toolbarRevealed = true else onSettings()
                        clipboardIconVisible() && onSlot(lx, 1) -> onToolbar(ToolbarInput.Clipboard)
                        showPasswords && clipboardIconVisible() && onSlot(lx, 2) -> onToolbar(ToolbarInput.Passwords)
                        chatChipVisible() && chatChipRect().contains(x, chatChipRect().centerY()) -> onToolbar(ToolbarInput.Chat)
                        toolbarRevealed -> Unit
                        chipVisible() && chipRect().contains(x, chipRect().centerY()) -> chipTapped(longPress = false)
                        else -> stripItemAt(x)?.let { onStrip(it, false) }
                    }
                }
                else -> {
                    touch.up(id, e.getX(i), e.getY(i), e.eventTime)
                    latency.record(e.eventTime)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        scheduleTimer()
        invalidate()
        return true
    }

    private fun scheduleTimer() {
        removeCallbacks(timer)
        touch.nextDeadline()?.let { postDelayed(timer, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this) // a reused view must not keep the bar height of the last app
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTouches()
    }

    override fun onDraw(c: Canvas) {
        val g = grid ?: return
        // The orb band is drawn on the keyboard's own background, at full keyboard height.
        fill.color = colors.background
        c.drawRect(0f, headroom, width.toFloat(), height.toFloat(), fill)
        sheet?.let {
            it.draw(c, RectF(0f, headroom, width.toFloat(), height - navInset - bottomPadding), colors, barRtl)
            return
        }
        if (band.orb) {
            // Only the keys give way to the orb: the toolbar row stays where it was, so nothing moves.
            drawToolbar(c)
            orb.draw(c, orbArea(), band, level, bandUndo(), screenContext, colors, barRtl, iconPainter)
            if (orb.animating(band)) postInvalidateOnAnimation()
            return
        }
        val bar = searchBar
        if (bar != null) bar.draw(c, RectF(0f, headroom, width.toFloat(), headroom + barHeight()), colors, barRtl) else drawToolbar(c)
        val pressed = touch.pressedKeys()
        for (k in g.keys) drawKey(c, k, k in pressed)
        drawTrail(c)
        cursorCaption?.let { caption ->
            fill.color = colors.background
            fill.alpha = (Dimens.CURSOR_SCRIM_ALPHA * 255).toInt()
            c.drawRect(0f, headroom, width.toFloat(), height.toFloat(), fill)
            fill.alpha = 255
            val top = g.keys.minOf { it.top }
            val bottom = g.keys.maxOf { it.bottom }
            drawText(c, caption, width / 2f, (top + bottom) / 2f, Dimens.CURSOR_CAPTION_SP, colors.label)
        }
        val p = panel
        if (p != null) {
            // Gboard fades the keyboard toward its background behind an open panel.
            fill.color = colors.background
            fill.alpha = (Dimens.SCRIM_ALPHA * 255).toInt()
            c.drawRect(0f, headroom, width.toFloat(), height.toFloat(), fill)
            fill.alpha = 255
            drawPanel(c, p)
        } else {
            preview?.let { if (showPreview) drawPreview(c, it) }
        }
    }

    private fun drawToolbar(c: Canvas) {
        if (stripBusy() && !toolbarRevealed) drawGrid(c)
        else drawIcon(c, R.drawable.ic_settings, toolbarSlotX(0), headroom + toolbarHeight / 2, Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        if (clipboardIconVisible()) drawIcon(c, R.drawable.ic_clipboard, toolbarSlotX(1), headroom + toolbarHeight / 2, Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        if (showPasswords && clipboardIconVisible()) drawIcon(c, R.drawable.ic_key, toolbarSlotX(2), headroom + toolbarHeight / 2, Dimens.TOOLBAR_ICON, colors.toolbarIcon)
        when {
            toolbarRevealed || !autofill.isEmpty -> Unit
            strip.isNotEmpty() -> drawStrip(c)
            chipVisible() -> drawChip(c)
        }
        if (chatChipVisible()) drawChatChip(c)
        if (showUndo && !toolbarRevealed && autofill.isEmpty) drawUndo(c)
        if (showMic) drawMic(c)
        if (showSpark) drawIcon(c, R.drawable.ic_spark, sparkCenterX(), headroom + toolbarHeight / 2, Dimens.SPARK_ICON, Orb.PROMPT_TINT)
    }

    /**
     * Gboard's glide trail: round segments in the accent colour, each thinning from [Dimens.TRAIL_HEAD] to
     * [Dimens.TRAIL_TAIL] and fading as it ages; after lift the whole trail fades out over [Dimens.TRAIL_LIFT_FADE_MS].
     */
    private fun drawTrail(c: Canvas) {
        val pts = trail
        if (pts.size < 2) return
        val now = SystemClock.uptimeMillis()
        val lift = if (trailLiftAt == STILL_DOWN) 1f else 1f - (now - trailLiftAt).toFloat() / Dimens.TRAIL_LIFT_FADE_MS
        var visible = false
        if (lift > 0f) {
            trailPaint.color = colors.accent
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val age = (now - b.t).coerceAtLeast(0).toFloat() / Dimens.TRAIL_FADE_MS
                if (age >= 1f) continue
                visible = true
                trailPaint.strokeWidth = (Dimens.TRAIL_HEAD + (Dimens.TRAIL_TAIL - Dimens.TRAIL_HEAD) * age) * dp
                trailPaint.alpha = ((1f - age) * lift * 255).toInt()
                c.drawLine(a.x, a.y, b.x, b.y, trailPaint)
            }
        }
        if (visible) postInvalidateOnAnimation() else if (trailLiftAt != STILL_DOWN) trail = emptyList()
    }

    private fun drawKey(c: Canvas, k: PlacedKey, pressed: Boolean) {
        val key = k.key
        fill.color = when (key.style) {
            KeyStyle.LETTER -> if (pressed) colors.letterKeyPressed else colors.letterKey
            KeyStyle.FUNCTION -> if (pressed) colors.functionKeyPressed else colors.functionKey
            KeyStyle.ENTER -> if (pressed) colors.accentPressed else colors.accent
        }
        rect.set(k.left, k.top, k.right, k.bottom)
        val radius = if (key.pill) k.height / 2 else Dimens.CORNER_RADIUS * dp
        c.drawRoundRect(rect, radius, radius, fill)
        val fg = if (key.style == KeyStyle.ENTER) colors.onAccent else colors.label
        val icon = iconFor(key)
        when {
            icon != 0 -> {
                val size = if (key.action == KeyAction.SHIFT || key.action == KeyAction.DELETE) Dimens.FUNCTION_ICON else Dimens.ICON
                // Gboard mirrors the newline arrow for right-to-left languages.
                val mirror = rtl && icon == R.drawable.ic_action_newline
                if (mirror) { c.save(); c.scale(-1f, 1f, k.centerX, k.centerY) }
                drawIcon(c, icon, k.centerX, k.centerY, size, fg)
                if (mirror) c.restore()
            }
            key.action == KeyAction.SPACE && key.label.isEmpty() -> drawSpaceLabel(c, k)
            key.sublabel != null -> {
                // Phone pad: digit left of centre, letters to its right (measured on Gboard).
                drawText(c, key.label, k.centerX - 0.085f * k.width, k.centerY, Dimens.PAD_LABEL_SP, fg)
                drawText(c, key.sublabel, k.centerX + 0.25f * k.width, k.centerY, Dimens.PAD_SUBLABEL_SP, fg)
            }
            else -> {
                val label = labelFor(key)
                val size = when {
                    k.row == RowKind.PAD && label.codePointCount(0, label.length) > 1 -> Dimens.PAD_SYMBOL_SP
                    k.row == RowKind.PAD -> Dimens.PAD_LABEL_SP
                    label.codePointCount(0, label.length) > 1 -> Dimens.FUNCTION_LABEL_SP
                    k.row == RowKind.NUMBER -> Dimens.NUMBER_LABEL_SP
                    else -> Dimens.LABEL_SP
                }
                drawText(c, label, k.centerX, k.centerY, size, fg, maxWidth = k.width - 8 * dp)
            }
        }
        key.hint?.let { drawHint(c, it, k) }
    }

    private fun drawPreview(c: Canvas, k: PlacedKey) {
        val bottom = k.top - Dimens.POPUP_GAP * dp
        rect.set(k.left, bottom - k.height, k.right, bottom)
        drawPopup(c, rect)
        drawText(c, labelFor(k.key), rect.centerX(), rect.centerY(), Dimens.PREVIEW_LABEL_SP, colors.label)
    }

    /** Gboard's panel: a pill (rounded rect when it wraps) in the key colour, the selection a circle in the accent. */
    private fun drawPanel(c: Canvas, p: AltPanel) {
        rect.set(p.left, p.top, p.right, p.bottom)
        val panelRadius = minOf(rect.height() / 2, if (p.rows == 1) Float.MAX_VALUE else Dimens.ALT_MULTI_RADIUS * dp)
        popupPaint.color = colors.popup
        popupPaint.setShadowLayer(4 * dp, 0f, 1 * dp, colors.popupShadow)
        c.drawRoundRect(rect, panelRadius, panelRadius, popupPaint)
        val upper = caps.isUpper && p.anchor.key.style == KeyStyle.LETTER
        for (i in p.alts.indices) {
            val l = p.cellLeft(i)
            val t = p.cellTop(i)
            rect.set(l, t, l + p.cellWidth, t + p.cellHeight)
            val selected = i == altIndex
            if (selected) {
                fill.color = colors.accent
                c.drawCircle(rect.centerX(), rect.centerY(), p.cellWidth / 2, fill)
            }
            val s = display(if (upper) p.alts[i].uppercase() else p.alts[i])
            val size = if (s.length > 2) Dimens.FUNCTION_LABEL_SP else Dimens.ALT_LABEL_SP
            drawText(c, s, rect.centerX(), rect.centerY(), size, if (selected) colors.onAccent else colors.label)
        }
    }

    private fun drawPopup(c: Canvas, r: RectF) {
        popupPaint.color = colors.popup
        popupPaint.setShadowLayer(4 * dp, 0f, 1 * dp, colors.popupShadow)
        val radius = Dimens.CORNER_RADIUS * dp
        c.drawRoundRect(r, radius, radius, popupPaint)
    }

    private fun labelFor(key: Key): String =
        if (caps.isUpper && key.action == KeyAction.TEXT && key.style == KeyStyle.LETTER) key.label.uppercase() else key.label

    /**
     * Draws [text] centred the way Gboard does: lowercase Latin on its x-height, Arabic on the alef,
     * everything else (capitals, digits, symbols) on cap height.
     */
    /**
     * The language on the space bar. With more than one language it shows ‹ › to say it can be swiped; while dragged
     * it slides with the finger and the next language (finger moving right) or the previous one slides in behind it.
     */
    private fun drawSpaceLabel(c: Canvas, k: PlacedKey) {
        val neighbours = spaceNeighbours
        val maxWidth = k.width * 0.9f
        if (neighbours == null) {
            drawText(c, spaceLabel, k.centerX, k.centerY, Dimens.SPACE_LABEL_SP, colors.label, maxWidth)
            return
        }
        if (spaceDx == 0f) {
            // Isolated, so an Arabic name doesn't reorder the arrows.
            drawText(c, "\u200E‹  \u2068$spaceLabel\u2069  ›", k.centerX, k.centerY, Dimens.SPACE_LABEL_SP, colors.label, maxWidth)
            return
        }
        val shown = (abs(spaceDx) / k.width).coerceIn(0f, 1f)
        c.save()
        c.clipRect(k.left, k.top, k.right, k.bottom)
        drawText(c, spaceLabel, k.centerX + spaceDx, k.centerY, Dimens.SPACE_LABEL_SP, fade(colors.label, 1f - shown), maxWidth)
        val incoming = if (spaceDx > 0) neighbours.second else neighbours.first
        val x = k.centerX + spaceDx - (if (spaceDx > 0) k.width else -k.width)
        drawText(c, incoming, x, k.centerY, Dimens.SPACE_LABEL_SP, fade(colors.label, shown), maxWidth)
        c.restore()
    }

    private fun fade(color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (android.graphics.Color.alpha(color) * alpha).roundToInt())

    private fun drawText(c: Canvas, text: String, cx: Float, cy: Float, sizeSp: Float, color: Int, maxWidth: Float = Float.MAX_VALUE) {
        textPaint.textSize = sp(sizeSp)
        textPaint.color = color
        // Shrink labels that would overflow a narrow key (e.g. "1234" on a one-unit key).
        val w = textPaint.measureText(text)
        if (w > maxWidth) textPaint.textSize *= maxWidth / w
        val ref = when {
            text.any { it in '؀'..'ۿ' } -> "ا"
            text.all { it.isLowerCase() } -> "x"
            else -> "H"
        }
        textPaint.getTextBounds(ref, 0, ref.length, bounds)
        c.drawText(text, cx, cy - bounds.exactCenterY(), textPaint)
    }

    /** Letter keys show hints top-right (digits); function keys top-centre (Arabic tashkeel on the period). */
    private fun drawHint(c: Canvas, hint: String, k: PlacedKey) {
        val s = display(hint)
        textPaint.textSize = sp(Dimens.HINT_SP)
        textPaint.color = colors.hint
        textPaint.getTextBounds("H", 0, 1, bounds)
        val inset = 5 * dp
        val x = if (k.key.style == KeyStyle.LETTER) k.right - inset - textPaint.measureText(s) / 2 else k.centerX
        c.drawText(s, x, k.top + inset + bounds.height(), textPaint)
    }

    /** Combining marks (tashkeel) are drawn on a dotted circle so they are visible on their own. */
    private fun display(s: String): String =
        if (s.isNotEmpty() && Character.getType(s.codePointAt(0)) == Character.NON_SPACING_MARK.toInt()) "◌$s" else s

    private fun iconFor(key: Key): Int = when (key.action) {
        KeyAction.SHIFT -> when (caps) {
            Caps.OFF -> R.drawable.ic_shift
            Caps.LOCKED -> R.drawable.ic_shift_lock
            else -> R.drawable.ic_shift_on
        }
        KeyAction.DELETE -> R.drawable.ic_delete
        KeyAction.EMOJI -> R.drawable.ic_emoji
        KeyAction.ENTER -> when (enterAction) {
            EnterAction.NEWLINE -> R.drawable.ic_action_newline
            EnterAction.SEARCH -> R.drawable.ic_action_search
            EnterAction.SEND -> R.drawable.ic_action_send
            EnterAction.DONE -> R.drawable.ic_action_done
            EnterAction.GO, EnterAction.NEXT, EnterAction.PREVIOUS -> R.drawable.ic_action_go
        }
        else -> 0
    }

    private fun drawIcon(c: Canvas, res: Int, cx: Float, cy: Float, sizeDp: Float, color: Int) {
        val d = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }
        val half = sizeDp * dp / 2
        d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        d.setTint(color)
        d.draw(c)
    }

    /** Gboard's toolbar slots, counted from the leading edge (mirrored for Arabic). */
    private fun toolbarSlotX(slot: Int): Float {
        val x = (Dimens.TOOLBAR_FIRST_CENTER + slot * Dimens.TOOLBAR_SLOT) * dp
        return if (barRtl) width - x else x
    }

    /** [lx] is mirrored, as [toolbarSlotX] without RTL. */
    private fun onSlot(lx: Float, slot: Int) = abs(lx - (Dimens.TOOLBAR_FIRST_CENTER + slot * Dimens.TOOLBAR_SLOT) * dp) < Dimens.TOOLBAR_SLOT / 2 * dp

    /** LTR-equivalent x: the strip is laid out leading → trailing and mirrored for Arabic. */
    private fun mirror(x: Float) = if (barRtl) width - x else x

    private fun stripStart() = Dimens.STRIP_START * dp
    private fun stripEnd() = width - (if (showUndo) Dimens.STRIP_END_WITH_UNDO else Dimens.STRIP_END) * dp

    private fun drawStrip(c: Canvas) {
        val cy = headroom + toolbarHeight / 2
        val start = stripStart()
        val slot = (stripEnd() - start) / 3
        for (i in 0 until 3) {
            val item = strip.getOrNull(i) ?: continue
            drawStripText(c, if (item.bold) boldPaint else textPaint, item.text, mirror(start + slot * i + slot / 2), cy, slot - 12 * dp)
        }
        fill.color = colors.divider
        val half = Dimens.STRIP_DIVIDER_HEIGHT * dp / 2
        val w = Dimens.STRIP_DIVIDER_WIDTH * dp / 2
        for (k in 1..2) {
            val x = mirror(start + slot * k)
            c.drawRect(x - w, cy - half, x + w, cy + half, fill)
        }
    }

    private fun drawUndo(c: Canvas) {
        val cy = headroom + toolbarHeight / 2
        val cx = mirror(width - Dimens.UNDO_CENTER_FROM_END * dp)
        rect.set(cx - Dimens.UNDO_WIDTH * dp / 2, cy - Dimens.UNDO_HEIGHT * dp / 2, cx + Dimens.UNDO_WIDTH * dp / 2, cy + Dimens.UNDO_HEIGHT * dp / 2)
        fill.color = colors.letterKey
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        if (barRtl) { c.save(); c.scale(-1f, 1f, cx, cy) } // Gboard mirrors the undo arrow in Arabic
        drawIcon(c, R.drawable.ic_undo, cx, cy, Dimens.ICON, colors.label)
        if (barRtl) c.restore()
    }

    private fun micCenterX() = mirror(width - Dimens.MIC_CENTER_FROM_END * dp)

    private fun isOnMic(x: Float) = abs(x - micCenterX()) <= Dimens.MIC_HIT * dp / 2

    /** The orb and its caption, centred where the keys are (below the toolbar row). */
    private fun orbArea(): RectF {
        val content = (Dimens.ORB_BAND / 2 + Dimens.ORB_DIAMETER / 2 + Dimens.ORB_CAPTION_GAP + Dimens.ORB_CAPTION_ZONE) * dp
        val keysTop = headroom + barHeight()
        val room = height - navInset - keysTop
        val top = keysTop + ((room - content) / 2).coerceAtLeast(0f)
        return RectF(0f, top, width.toFloat(), top + content)
    }

    /** While [orbShown]: where its controls take touches, in this view's coordinates. */
    fun orbTouchable(out: Region) = orb.touchable(orbArea(), band, bandUndo(), screenContext, barRtl, out)

    /** ↶ beside the orb, never while recording a chat: the recording isn't an edit. */
    private fun bandUndo() = orbUndo && recordCaption == null

    /** The band: a tap hits the orb, ↶, the keyboard icon or a caption action; a drag down dismisses. */
    private fun onOrbTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { orbPointer = e.getPointerId(0); orbDownX = e.x; orbDownY = e.y }
            MotionEvent.ACTION_UP -> if (orbPointer >= 0) {
                orbPointer = -1
                if (e.y - orbDownY > Dimens.ORB_SWIPE * dp) onVoice(VoiceInput.Dismiss) else orb.hit(orbDownX, orbDownY)?.let(onVoice)
            }
            MotionEvent.ACTION_CANCEL -> orbPointer = -1
        }
    }

    private fun sparkCenterX() = mirror(width - Dimens.SPARK_CENTER_FROM_END * dp)

    /** 20 dp each side: ✦ sits 40 dp from the mic, whose 48 dp hit area is tested first (step 7). */
    private fun isOnSpark(x: Float) = abs(x - sparkCenterX()) <= 20 * dp


    private fun drawMic(c: Canvas) {
        val cy = headroom + toolbarHeight / 2
        val cx = micCenterX()
        val d = Dimens.MIC_DIAMETER * dp
        fill.color = colors.letterKey
        c.drawCircle(cx, cy, d / 2, fill)
        drawIcon(c, R.drawable.ic_mic, cx, cy, Dimens.MIC_ICON, colors.label)
        if (voice.pending > 0) {
            // Audio waiting for the server (spec §7.3): it is safe on disk and will be sent.
            val bx = cx + (if (barRtl) -1 else 1) * d * 0.38f
            val by = cy - d * 0.38f
            fill.color = colors.accent
            c.drawCircle(bx, by, Dimens.MIC_BADGE * dp / 2, fill)
            drawText(c, voice.pending.coerceAtMost(9).toString(), bx, by, Dimens.MIC_BADGE_SP, colors.onAccent)
        }
    }

    private fun chipVisible() = (pendingChip ?: clipChip) != null && strip.isEmpty() && autofill.isEmpty

    private fun chipLabel() = (pendingChip ?: clipChip).orEmpty().let { if (it.length > 32) it.take(31) + "…" else it }

    private fun chipTapped(longPress: Boolean) =
        if (pendingChip != null) onVoice(VoiceInput.Chip(longPress)) else onToolbar(ToolbarInput.ClipChip(longPress))

    /** Slot 1 of the toolbar while it's idle (spec §8.1). */
    private fun clipboardIconVisible() = toolbarRevealed || (strip.isEmpty() && !chipVisible() && autofill.isEmpty)

    /** Suggestions, predictions or a chip fill the strip, so the toolbar icons give way to Gboard's grid. */
    private fun stripBusy() = strip.isNotEmpty() || (pendingChip ?: clipChip) != null || !autofill.isEmpty

    /** Gboard's leading grid (measured on gboard-strip-midword-en.png: four 7.8 dp squares, 2.7 dp apart). */
    private fun drawGrid(c: Canvas) {
        val cx = toolbarSlotX(0)
        val cy = headroom + toolbarHeight / 2
        val sq = Dimens.GRID_SQUARE * dp
        val gap = Dimens.GRID_GAP * dp
        val r = Dimens.GRID_RADIUS * dp
        fill.color = colors.toolbarIcon
        for (i in 0..1) for (j in 0..1) {
            val l = cx - sq - gap / 2 + i * (sq + gap)
            val t = cy - sq - gap / 2 + j * (sq + gap)
            c.drawRoundRect(l, t, l + sq, t + sq, r, r, fill)
        }
    }

    private fun chipRect(): RectF {
        val cy = headroom + toolbarHeight / 2
        textPaint.textSize = sp(Dimens.CHIP_SP)
        val maxW = stripEnd() - stripStart()
        val w = minOf(maxW, textPaint.measureText(chipLabel()) + (Dimens.CHIP_ICON + 34) * dp)
        val centre = mirror(stripStart() + maxW / 2)
        val h = Dimens.UNDO_HEIGHT * dp / 2
        return RectF(centre - w / 2, cy - h, centre + w / 2, cy + h)
    }

    /** Spec §7.3 "Pending dictation": text dictated for another field, or recovered after a restart. */
    private fun drawChip(c: Canvas) {
        val r = chipRect()
        fill.color = colors.letterKey
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        val inset = (14 + Dimens.CHIP_ICON / 2) * dp
        drawIcon(c, if (pendingChip != null) R.drawable.ic_mic else R.drawable.ic_clipboard, if (barRtl) r.right - inset else r.left + inset, r.centerY(), Dimens.CHIP_ICON, colors.label)
        val textCx = r.centerX() + (if (barRtl) -1 else 1) * (Dimens.CHIP_ICON + 6) * dp / 2
        drawText(c, chipLabel(), textCx, r.centerY(), Dimens.CHIP_SP, colors.label, maxWidth = r.width() - (Dimens.CHIP_ICON + 34) * dp)
    }

    /** The Chat chip shows only in the idle toolbar, after the last slot icon. */
    private fun chatChipVisible() = chatChip != null && clipboardIconVisible()

    /** "Nora ▸": the title cut to [CHAT_CHIP_CHARS] characters, never through an emoji; the arrow points inward in Arabic. */
    private fun chatChipLabel(): String {
        val t = chatChip.orEmpty()
        val cut = t.substring(0, t.offsetByCodePoints(0, minOf(CHAT_CHIP_CHARS, t.codePointCount(0, t.length)))).trimEnd()
        return cut + if (barRtl) " ◂" else " ▸"
    }

    private fun chatChipRect(): RectF {
        val cy = headroom + toolbarHeight / 2
        val start = (Dimens.TOOLBAR_FIRST_CENTER + (if (showPasswords) 2.5f else 1.5f) * Dimens.TOOLBAR_SLOT) * dp
        textPaint.textSize = sp(Dimens.CHIP_SP)
        val end = minOf(stripEnd(), start + textPaint.measureText(chatChipLabel()) + 2 * Dimens.CHAT_CHIP_PAD * dp)
        val h = Dimens.UNDO_HEIGHT * dp / 2
        val a = mirror(start)
        val b = mirror(end)
        return RectF(minOf(a, b), cy - h, maxOf(a, b), cy + h)
    }

    private fun drawChatChip(c: Canvas) {
        val r = chatChipRect()
        fill.color = colors.letterKey
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        drawText(c, chatChipLabel(), r.centerX(), r.centerY(), Dimens.CHIP_SP, colors.label, maxWidth = r.width() - 2 * Dimens.CHAT_CHIP_PAD * dp)
    }

    private fun drawStripText(c: Canvas, paint: Paint, text: String, cx: Float, cy: Float, maxWidth: Float) {
        paint.textSize = sp(Dimens.STRIP_SP)
        paint.color = colors.label
        val w = paint.measureText(text)
        var shown = text
        // Long text (a drafted reply) shrinks only so far, then ends in "…": a tiny full line can't be read anyway.
        if (w > maxWidth) {
            paint.textSize *= maxOf(MIN_STRIP_SCALE, maxWidth / w)
            if (paint.measureText(text) > maxWidth) {
                shown = TextUtils.ellipsize(text, TextPaint(paint), maxWidth, TextUtils.TruncateAt.END).toString()
            }
        }
        paint.getTextBounds("x", 0, 1, bounds)
        c.drawText(shown, cx, cy - bounds.exactCenterY(), paint)
    }

    private fun stripItemAt(x: Float): StripItem? {
        if (strip.isEmpty()) return null
        val lx = mirror(x)
        val start = stripStart()
        val end = stripEnd()
        if (lx < start || lx > end) return null
        return strip.getOrNull(((lx - start) / ((end - start) / 3)).toInt().coerceIn(0, 2))
    }

    fun keyModel(): KeyModel? = grid?.keyModel()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        /** The smallest strip text, as a share of [Dimens.STRIP_SP], before it is cut short instead. */
        const val MIN_STRIP_SCALE = 0.72f
        const val STRIP_LONG_PRESS_MS = 500L
        /** The Chat chip's title, at most (chat memory spec §7). */
        const val CHAT_CHIP_CHARS = 14
        const val STILL_DOWN = Long.MAX_VALUE
        /** Pixel phones ship Google Sans (Gboard's face); elsewhere this falls back to the system sans. */
        val FONT: Typeface = Typeface.create("google-sans", Typeface.NORMAL)
    }
}

/** One suggestion-strip slot. [literal] is the user's own typed text (kept as typed when tapped). */
/** [shortcut]: the text of one of the owner's text shortcuts; tapping it puts the text in, learning nothing. */
data class StripItem(val text: String, val bold: Boolean = false, val literal: Boolean = false, val shortcut: Boolean = false)

/** Debug builds only: logs p50/p95 of touch-up → commit-done every 50 keys (spec §6.7 target < 16 ms). */
private class LatencyLog {
    private val samples = LongArray(50)
    private var n = 0

    fun record(eventTime: Long) {
        if (!BuildConfig.DEBUG) return
        samples[n++] = SystemClock.uptimeMillis() - eventTime
        if (n == samples.size) {
            samples.sort()
            Log.d("KbdLatency", "p50=${samples[25]}ms p95=${samples[47]}ms")
            n = 0
        }
    }
}

/** Toolbar actions that aren't suggestions or voice. */
sealed interface ToolbarInput {
    data object Clipboard : ToolbarInput
    data class ClipChip(val longPress: Boolean) : ToolbarInput
    data object Passwords : ToolbarInput
    /** The Chat chip, or ✦ held in a Chat: open the Chat's panel. */
    data object Chat : ToolbarInput
}
