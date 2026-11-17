package dev.yazin.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.children
import kotlin.math.roundToInt

/**
 * Autofill chips (Android 11+ inline suggestions, e.g. 1Password) over the strip area, as Gboard shows them:
 * pinned chips (the provider's key icon) at the leading edge, the rest scrolling after them.
 * The chips are system-rendered views, so this sits over [KeyboardView] in the input view rather than being drawn by it.
 */
@SuppressLint("ViewConstructor")
class AutofillStrip(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val scroller = object : HorizontalScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            clipChips()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            clipChips()
        }
    }

    private var pinned = emptyList<View>()
    private var chips = emptyList<View>()
    val isEmpty: Boolean get() = pinned.isEmpty() && chips.isEmpty()

    /** False hides the chips (toolbar revealed, a sheet open, dictating). */
    var showing = true
        set(v) { if (field != v) { field = v; attach() } }

    init {
        gravity = Gravity.CENTER_VERTICAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(Dimens.TOOLBAR_HEIGHT)).apply {
            topMargin = px(Dimens.POPUP_HEADROOM)
            marginStart = px(Dimens.STRIP_START)
            marginEnd = px(Dimens.STRIP_END)
        }
        visibility = GONE
    }

    fun show(pinned: List<View>, chips: List<View>) {
        this.pinned = pinned
        this.chips = chips
        attach()
        scroller.scrollTo(0, 0)
    }

    /** Chip surfaces sit above the window and ignore a hidden parent, so hiding detaches them (they reattach fine). */
    private fun attach() {
        removeViews(0, childCount - 1)
        row.removeAllViews()
        visibility = if (showing && !isEmpty) VISIBLE else GONE
        if (visibility == GONE) return
        pinned.forEachIndexed { i, v -> addView(v, i, chipParams(v)) }
        chips.forEach { row.addView(it, chipParams(it)) }
    }

    /** Inflated chips carry their exact size in their layout params; keep it and add the gap. */
    private fun chipParams(v: View) = LayoutParams(v.layoutParams.width, v.layoutParams.height).apply { marginEnd = px(CHIP_GAP) }

    /** The chips draw on their own surfaces above the window, which the scroller can't clip; crop each to the viewport. */
    private fun clipChips() {
        for (v in row.children) {
            val l = (scroller.scrollX - v.left).coerceAtLeast(0)
            val r = (scroller.scrollX + scroller.width - v.left).coerceAtMost(v.width)
            // An empty crop means no crop to the compositor, so a chip scrolled fully out keeps a 1 px crop past its edge.
            v.clipBounds = if (l < r) Rect(l, 0, r, v.height) else Rect(v.width, 0, v.width + 1, v.height)
        }
    }

    private fun px(v: Float) = (v * dp).roundToInt()

    private companion object {
        const val CHIP_GAP = 8f
    }
}
