package dev.yazin.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/** Draws a tinted icon (KeyboardView's cache). */
fun interface Icons {
    fun draw(c: Canvas, res: Int, cx: Float, cy: Float, sizeDp: Float, color: Int)
}

/**
 * A full-keyboard sheet (spec §8: the emoji and clipboard panels) that KeyboardView draws over the toolbar and keys
 * (§8.1). It gets every touch below the transparent headroom; it follows the first finger.
 */
interface Sheet {
    fun draw(c: Canvas, area: RectF, colors: KeyboardColors, rtl: Boolean)

    fun onTouch(e: MotionEvent)

    /** The sheet closed or the touch stream was cancelled: drop the gesture in progress. */
    fun cancel()
}

/** Drag and fling for a sheet's scrolling content: vertical, or [horizontal] for a row. */
class SheetScroll(private val view: View, private val horizontal: Boolean = false) {
    private val scroller = OverScroller(view.context)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var startY = 0f

    /** A row laid out right to left (Arabic): dragging right scrolls on. */
    var reverse = false

    private fun pos(e: MotionEvent) = (if (horizontal) e.x else e.y) * if (reverse) -1 else 1

    /** Scroll offset in px along the axis, 0..[max]. */
    var y = 0f
        private set
    var max = 0f
        set(v) {
            field = maxOf(0f, v)
            y = y.coerceIn(0f, field)
        }

    /** The finger moved far enough to scroll: taps and long-presses are off. */
    var dragging = false
        private set

    fun down(e: MotionEvent) {
        scroller.forceFinished(true)
        tracker?.recycle()
        tracker = VelocityTracker.obtain().apply { addMovement(e) }
        downY = pos(e)
        startY = y
        dragging = false
    }

    fun move(e: MotionEvent) {
        tracker?.addMovement(e)
        if (!dragging && abs(pos(e) - downY) > slop) dragging = true
        if (dragging) {
            y = (startY - (pos(e) - downY)).coerceIn(0f, max)
            view.invalidate()
        }
    }

    fun up(e: MotionEvent) {
        val t = tracker ?: return
        t.addMovement(e)
        if (dragging) {
            t.computeCurrentVelocity(1000)
            val v = (if (horizontal) t.xVelocity else t.yVelocity) * if (reverse) -1 else 1
            scroller.fling(0, y.toInt(), 0, -v.toInt(), 0, 0, 0, max.toInt())
            view.postInvalidateOnAnimation()
        }
        t.recycle()
        tracker = null
    }

    fun scrollTo(target: Float) {
        scroller.forceFinished(true)
        y = target.coerceIn(0f, max)
        view.invalidate()
    }

    /** Advances a fling: call at the start of every draw. */
    fun step() {
        if (scroller.computeScrollOffset()) {
            y = scroller.currY.toFloat().coerceIn(0f, max)
            view.postInvalidateOnAnimation()
        }
    }

    fun cancel() {
        scroller.forceFinished(true)
        tracker?.recycle()
        tracker = null
        dragging = false
    }
}
