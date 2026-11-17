package dev.yazin.keyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.voice.VoiceStatus
import dev.yazin.keyboard.engine.voice.VoiceUi
import kotlin.math.hypot

/** What the user did in the voice UI (AI orb spec §2). */
sealed interface VoiceInput {
    /** 🎤 ([prompt] false) or ✦ in the toolbar. */
    data class Start(val prompt: Boolean) : VoiceInput
    data object OrbTap : VoiceInput
    data object Dismiss : VoiceInput
    data object Undo : VoiceInput
    /** A refused chunk: Retry / Discard (spec §7.3). */
    data object Retry : VoiceInput
    data object Discard : VoiceInput
    /** A failed LLM call: Retry / Insert what I said. */
    data object PromptRetry : VoiceInput
    data object InsertTranscript : VoiceInput
    /** ✦ can't see the screen: open the steps to turn screen context on. */
    data object ScreenSetup : VoiceInput
    data class Chip(val longPress: Boolean) : VoiceInput
}

/**
 * The controls that replace the keys while voice runs (AI orb spec §2): the keyboard chip at the leading edge, the orb
 * centred with ↶ beside it, and the caption card centred below. The app ends at the row's centre line, so the top half
 * of the row floats over it. Mirrored for Arabic. The orb is one sky vortex everywhere, recoloured per state:
 * black for dictation; blue listening, violet thinking, light grey otherwise in prompt mode.
 */
class Orb(private val context: Context) {
    private val dp = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SHADER) else null
    /** Seconds elapsed, 0→1 blend into thinking, and accumulated vortex rotation. */
    private var time = 0f
    private var think = 0f
    private var spin = 0f
    /** The current sky and haze, easing towards the state's palette. */
    private val sky = FloatArray(3)
    private val haze = FloatArray(3)
    private var settled = false
    private var fallbackTint = 0
    private val center = PointF()
    private val keyboard = PointF()
    private val undo = PointF(Float.NaN, Float.NaN)
    private val card = RectF()
    private var captionText: StaticLayout? = null
    /** The card's top and bottom padding. */
    private var captionPad = 0f
    private val pillA = RectF()
    private val pillB = RectF()
    private var actionA: VoiceInput? = null
    private var actionB: VoiceInput? = null
    private var labelA = ""
    private var labelB = ""
    /** Time advances only while animating, so a still orb keeps its last frame. */
    private var lastFrame = 0L

    /** The orb is moving or still changing colour: the view should draw again next frame. */
    fun animating(ui: VoiceUi) = ui.recording || ui.status in MOVING || !settled

    fun draw(c: Canvas, area: RectF, ui: VoiceUi, level: Float, canUndo: Boolean, screenContext: Boolean, colors: KeyboardColors, rtl: Boolean, icons: Icons) {
        place(area, ui, canUndo, screenContext, rtl)
        chip(c, keyboard.x, keyboard.y, colors)
        icons.draw(c, R.drawable.ic_keyboard, keyboard.x, keyboard.y, Dimens.TOOLBAR_ICON, colors.label)
        drawOrb(c, ui, level)
        if (!undo.x.isNaN()) {
            chip(c, undo.x, undo.y, colors)
            if (rtl) { c.save(); c.scale(-1f, 1f, undo.x, undo.y) }
            icons.draw(c, R.drawable.ic_undo, undo.x, undo.y, Dimens.ICON, colors.label)
            if (rtl) c.restore()
        }
        drawCaption(c, colors)
    }

    /**
     * Where the controls take touches, in [area]'s coordinates; the rest of the band lets them through to the app. Laid
     * out from state rather than read back from the last frame, so it is right in the same pass that changes it.
     */
    fun touchable(area: RectF, ui: VoiceUi, canUndo: Boolean, screenContext: Boolean, rtl: Boolean, out: Region) {
        place(area, ui, canUndo, screenContext, rtl)
        out.setEmpty()
        fun circle(p: PointF, r: Float) {
            if (!p.x.isNaN()) out.union(Rect((p.x - r).toInt(), (p.y - r).toInt(), (p.x + r).toInt() + 1, (p.y + r).toInt() + 1))
        }
        val slop = 8 * dp
        circle(center, Dimens.ORB_DIAMETER * dp / 2 + slop)
        circle(undo, Dimens.ORB_UNDO_DIAMETER * dp / 2 + slop)
        circle(keyboard, KEYBOARD_HIT * dp)
        // The whole card, so a tap on it never lands on the app underneath.
        if (!card.isEmpty) out.union(Rect().also { card.roundOut(it) })
    }

    /** The orb row sits at the top of [area], the caption card below it. */
    private fun place(area: RectF, ui: VoiceUi, canUndo: Boolean, screenContext: Boolean, rtl: Boolean) {
        val cy = area.top + Dimens.ORB_BAND * dp / 2
        keyboard.set(if (rtl) area.right - Dimens.ORB_CHIP_CENTER * dp else area.left + Dimens.ORB_CHIP_CENTER * dp, cy)
        center.set(area.centerX(), cy)
        if (canUndo && !ui.recording && ui.status != VoiceStatus.THINKING) {
            undo.set(center.x + (if (rtl) -1 else 1) * Dimens.ORB_UNDO_OFFSET * dp, cy)
        } else {
            undo.set(Float.NaN, Float.NaN)
        }
        placeCaption(area, ui, screenContext, rtl)
    }

    fun hit(x: Float, y: Float): VoiceInput? {
        val slop = 8 * dp
        fun grown(r: RectF) = !r.isEmpty && x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        return when {
            hypot(x - center.x, y - center.y) <= Dimens.ORB_DIAMETER * dp / 2 + slop -> VoiceInput.OrbTap
            !undo.x.isNaN() && hypot(x - undo.x, y - undo.y) <= Dimens.ORB_UNDO_DIAMETER * dp / 2 + slop -> VoiceInput.Undo
            hypot(x - keyboard.x, y - keyboard.y) <= KEYBOARD_HIT * dp -> VoiceInput.Dismiss
            grown(pillA) -> actionA
            grown(pillB) -> actionB
            else -> null
        }
    }

    /** A round control floating over the app: the keyboard icon and ↶. */
    private fun chip(c: Canvas, x: Float, y: Float, colors: KeyboardColors) {
        fill.color = colors.letterKey
        fill.setShadowLayer(4 * dp, 0f, 1 * dp, colors.popupShadow)
        c.drawCircle(x, y, Dimens.ORB_UNDO_DIAMETER * dp / 2, fill)
        fill.clearShadowLayer()
    }

    private fun drawOrb(c: Canvas, ui: VoiceUi, level: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (animating(ui) && lastFrame != 0L) (now - lastFrame).coerceIn(0, 50) / 1000f else 0f
        val first = lastFrame == 0L
        lastFrame = now
        val r = Dimens.ORB_DIAMETER * dp / 2 * (1f + Dimens.ORB_SWELL * (if (ui.recording) level.coerceIn(0f, 1f) else 0f))
        val thinking = ui.prompt && ui.status == VoiceStatus.THINKING
        val palette = when {
            !ui.prompt -> TRANSCRIBE
            thinking -> THINKING
            ui.recording -> LISTENING
            else -> NEUTRAL
        }
        val tint = palette.tint
        val ease = if (first) 1f else 1f - kotlin.math.exp(-dt * 4f)
        think += ((if (thinking) 1f else 0f) - think) * ease
        spin += dt * 0.45f * (1f - think)
        time += dt
        var delta = 0f
        for (i in 0..2) {
            sky[i] += (palette.sky[i] - sky[i]) * ease
            haze[i] += (palette.haze[i] - haze[i]) * ease
            delta = maxOf(delta, kotlin.math.abs(palette.sky[i] - sky[i]), kotlin.math.abs(palette.haze[i] - haze[i]))
        }
        settled = delta < 0.004f
        val s = shader
        if (s != null && Build.VERSION.SDK_INT >= 33) {
            s.setFloatUniform("center", center.x, center.y)
            s.setFloatUniform("radius", r)
            s.setFloatUniform("uTime", time)
            s.setFloatUniform("uThink", think)
            s.setFloatUniform("uSpin", spin)
            s.setFloatUniform("uSky", sky)
            s.setFloatUniform("uHaze", haze)
            orbPaint.shader = s
        } else if (fallbackTint != tint || orbPaint.shader == null) {
            // ponytail: the gradient is fixed at its first position; the band never moves the orb
            orbPaint.shader = RadialGradient(center.x, center.y - r * 0.25f, r * 1.1f, intArrayOf(Color.WHITE, tint), floatArrayOf(0.1f, 1f), Shader.TileMode.CLAMP)
            fallbackTint = tint
        }
        // A soft shadow under a crisp sphere, so it reads on any app's background.
        fill.color = tint
        fill.setShadowLayer(10 * dp, 0f, 3 * dp, SHADOW)
        c.drawCircle(center.x, center.y, r - 1f, fill)
        fill.clearShadowLayer()
        c.drawCircle(center.x, center.y, r, orbPaint)
    }

    /**
     * The caption card: status or transcript, and Retry / Discard or Retry / Insert what I said after a failure, or
     * Turn on screen context when ✦ is listening blind.
     */
    private fun placeCaption(area: RectF, ui: VoiceUi, screenContext: Boolean, rtl: Boolean) {
        card.setEmpty(); pillA.setEmpty(); pillB.setEmpty(); actionA = null; actionB = null; captionText = null
        val message = message(ui, screenContext) ?: return
        val actions = when (ui.status) {
            VoiceStatus.FAILED -> listOf(R.string.voice_retry to VoiceInput.Retry, R.string.voice_discard to VoiceInput.Discard)
            VoiceStatus.PROMPT_FAILED -> listOf(R.string.voice_retry to VoiceInput.PromptRetry, R.string.orb_insert_said to VoiceInput.InsertTranscript)
            VoiceStatus.SPEAK_NOW, VoiceStatus.LISTENING ->
                if (ui.prompt && !screenContext) listOf(R.string.orb_screen_turn_on to VoiceInput.ScreenSetup) else emptyList()
            else -> emptyList()
        }
        val pad = Dimens.ORB_CAPTION_PAD * dp
        val gap = 8 * dp
        text.textSize = sp(Dimens.ORB_PILL_SP)
        val labels = actions.map { context.getString(it.first) }
        val widths = labels.map { text.measureText(it) + 2 * pad }
        val pillsWidth = widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
        // The card hugs its text, up to the screen width less a margin.
        text.textSize = sp(Dimens.ORB_CAPTION_SP)
        val maxInner = area.width() - 2 * (Dimens.ORB_CAPTION_MARGIN * dp + pad)
        val inner = maxOf(kotlin.math.ceil(StaticLayout.getDesiredWidth(message, text)), pillsWidth).coerceIn(1f, maxInner.coerceAtLeast(1f))
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, text, inner.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(if (actions.isEmpty()) 2 else 1)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        captionText = layout
        val pillH = Dimens.ORB_PILL_HEIGHT * dp
        captionPad = if (actions.isEmpty()) pad * 0.75f else Dimens.ORB_PILL_CARD_PAD * dp
        val h = layout.height + 2 * captionPad + if (actions.isEmpty()) 0f else pillH + Dimens.ORB_PILL_GAP * dp
        val top = area.top + (Dimens.ORB_BAND / 2 + Dimens.ORB_DIAMETER / 2 + Dimens.ORB_CAPTION_GAP) * dp
        val half = inner.toInt() / 2f + pad
        card.set(area.centerX() - half, top, area.centerX() + half, top + h)
        var x = if (rtl) card.right - pad else card.left + pad
        val py = card.bottom - captionPad - pillH / 2
        actions.forEachIndexed { i, (_, input) ->
            val w = widths[i]
            val r = if (i == 0) pillA else pillB
            if (rtl) r.set(x - w, py - pillH / 2, x, py + pillH / 2) else r.set(x, py - pillH / 2, x + w, py + pillH / 2)
            if (i == 0) { actionA = input; labelA = labels[i] } else { actionB = input; labelB = labels[i] }
            x += if (rtl) -(w + gap) else w + gap
        }
    }

    private fun drawCaption(c: Canvas, colors: KeyboardColors) {
        val layout = captionText ?: return
        val pad = Dimens.ORB_CAPTION_PAD * dp
        fill.color = colors.letterKey
        fill.setShadowLayer(4 * dp, 0f, 1 * dp, colors.popupShadow) // floats over the app (the band is see-through)
        c.drawRoundRect(card, Dimens.ORB_CAPTION_RADIUS * dp, Dimens.ORB_CAPTION_RADIUS * dp, fill)
        fill.clearShadowLayer()
        // The layout draws with this paint, so it needs the caption size back.
        text.textSize = sp(Dimens.ORB_CAPTION_SP)
        text.color = colors.label
        c.save()
        c.translate(card.left + pad, card.top + captionPad)
        layout.draw(c)
        c.restore()
        text.textSize = sp(Dimens.ORB_PILL_SP)
        for ((i, r) in listOf(pillA, pillB).withIndex()) {
            if (r.isEmpty) continue
            fill.color = if (i == 0) colors.accent else colors.functionKey
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            text.color = if (i == 0) colors.onAccent else colors.label
            c.drawText(if (i == 0) labelA else labelB, r.left + pad, r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun message(ui: VoiceUi, screenContext: Boolean): String? = ui.caption ?: when (ui.status) {
        VoiceStatus.SPEAK_NOW -> context.getString(
            when {
                !ui.prompt -> R.string.voice_speak_now
                screenContext -> R.string.orb_ask
                else -> R.string.orb_ask_no_screen
            },
        )
        VoiceStatus.LISTENING -> ui.transcript.ifEmpty { context.getString(R.string.voice_listening) }
        VoiceStatus.TRANSCRIBING -> ui.transcript.ifEmpty { context.getString(R.string.voice_transcribing) }
        VoiceStatus.WAITING -> context.getString(R.string.voice_waiting, ui.pending)
        VoiceStatus.THINKING -> context.getString(if (screenContext) R.string.orb_thinking else R.string.orb_thinking_no_screen)
        VoiceStatus.NO_SPEECH -> context.getString(R.string.voice_no_speech)
        VoiceStatus.MIC_UNAVAILABLE -> context.getString(R.string.voice_mic_unavailable)
        VoiceStatus.FAILED -> context.getString(R.string.voice_failed, ui.failed?.error.orEmpty())
        VoiceStatus.PROMPT_FAILED -> context.getString(R.string.orb_prompt_failed, ui.error.orEmpty())
        VoiceStatus.DONE -> null
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    companion object {
        /** The ✦ spark's violet, matching the thinking orb. */
        const val PROMPT_TINT = 0xFF7C4DFF.toInt()
        private const val SHADOW = 0x40000000
        /** The keyboard chip's hit radius, larger than the chip. */
        private const val KEYBOARD_HIT = 28f
        private val MOVING = setOf(VoiceStatus.TRANSCRIBING, VoiceStatus.WAITING, VoiceStatus.THINKING)

        /** The orb's top colour, its cloud colour, and a flat tint for the shadow and the pre-33 gradient. */
        private class Palette(val sky: FloatArray, val haze: FloatArray, val tint: Int)
        private val TRANSCRIBE = Palette(floatArrayOf(0.05f, 0.05f, 0.06f), floatArrayOf(0.40f, 0.40f, 0.43f), 0xFF111114.toInt())
        private val LISTENING = Palette(floatArrayOf(0.20f, 0.42f, 0.89f), floatArrayOf(0.96f, 0.97f, 1.0f), 0xFF346BE3.toInt())
        private val THINKING = Palette(floatArrayOf(0.50f, 0.25f, 0.92f), floatArrayOf(0.97f, 0.93f, 1.0f), PROMPT_TINT)
        private val NEUTRAL = Palette(floatArrayOf(0.72f, 0.73f, 0.76f), floatArrayOf(0.95f, 0.95f, 0.96f), 0xFFB8BAC2.toInt())

        /** A sky vortex ([uSky] over [uHaze] clouds) that spins, and sloshes in place as [uThink] rises. */
        private const val SHADER = """
uniform float2 center;
uniform float radius;
uniform float uTime;
uniform float uThink;
uniform float uSpin;
uniform float3 uSky;
uniform float3 uHaze;

float3 permute(float3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 a0 = x - floor(x + 0.5);
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

half4 main(float2 xy) {
    // Same space as the reference: radius 0.5, y up.
    float2 uv = float2(xy.x - center.x, center.y - xy.y) / (2.0 * radius);
    float dist = length(uv);
    float mask = 1.0 - smoothstep(0.5 - 0.75 / radius, 0.5, dist);
    if (mask <= 0.0) return half4(0.0);

    float3 skyBlue = uSky;
    float3 haze = uHaze;
    float t = uTime;

    float slosh = sin(t * 0.9) * 1.6 + sin(t * 0.37 + 1.3) * 0.9;
    float twist = (0.5 - dist) * (2.4 + mix(sin(t * 0.6) * 0.6, slosh, uThink));
    float ang = uSpin + twist;
    float ca = cos(ang);
    float sa = sin(ang);
    float2 sv = float2x2(ca, -sa, sa, ca) * uv;

    float gy = mix(uv.y, sv.y, 0.3) + 0.5;

    float2 p = float2(sv.x * 1.2 + t * 0.08, sv.y * 4.0);
    float n = snoise(p) * 0.5 + snoise(p * 2.0 + t * 0.12) * 0.25 + snoise(p * 4.0 - t * 0.06) * 0.125;

    float horizon = gy + n * 0.14 + sin(sv.x * 3.0 + t * 0.8) * 0.04;
    float3 col = mix(haze, skyBlue, smoothstep(0.35, 0.85, horizon));

    float2 q = float2(sv.x * 1.4 - t * 0.1, sv.y * 6.0);
    float churn = 1.0 + uThink * 0.8;
    q += float2(snoise(q * 0.35 + t * 0.15) * 0.6 * churn, snoise(q * 0.5 - t * 0.12) * 0.4 * churn);

    float wisp = 0.0;
    float amp = 0.5;
    float2 f = q;
    for (int k = 0; k < 4; k++) {
        wisp += snoise(f) * amp;
        f = f * float2(2.0, 1.8) + 3.1;
        amp *= 0.5;
    }
    wisp = smoothstep(-0.1, 0.7, wisp);

    float patches = smoothstep(-0.2, 0.6, snoise(float2(sv.x * 2.0 + t * 0.08, sv.y * 3.0)));
    float band = 1.0 - smoothstep(0.05, 0.4, abs(gy - 0.6));
    col = mix(col, haze, wisp * patches * band * 0.7);

    col *= mix(1.0, 0.82, smoothstep(0.46, 0.5, dist));

    return half4(half3(col) * half(mask), half(mask));
}
"""
    }
}
