package dev.yazin.keyboard.keyboard

import dev.yazin.keyboard.engine.GlidePoint
import kotlin.math.abs
import kotlin.math.hypot

data class TouchConfig(
    val longPressMs: Long = 300,
    val repeatStartMs: Long = 400,
    val repeatIntervalMs: Long = 60,
    val repeatMinIntervalMs: Long = 25,
    val maxAltColumns: Int = 8,
    /** How far a finger on a letter must move from where it landed to become a glide; infinity turns glide off. */
    val glideStartPx: Float = Float.POSITIVE_INFINITY,
    /** How far a finger on the space bar must move sideways to drag the language label; infinity turns the swipe off. */
    val spaceSwipeStartPx: Float = Float.POSITIVE_INFINITY,
    /** Share of the space bar's width a drag must cover, on lift, to change the language. */
    val spaceSwipeCommit: Float = 0.25f,
    /** Holding the space bar starts cursor control (as Samsung) instead of opening the input-method picker. */
    val spaceCursor: Boolean = false,
    /** Cursor control: sideways travel per character, and vertical travel per line, in px. */
    val cursorStepPx: Float = 20f,
    val cursorLinePx: Float = 90f,
)

sealed interface TouchEvent {
    /** A finger landed on, or slid onto, [key]: haptic, sound, preview. */
    data class Press(val key: PlacedKey) : TouchEvent
    data class Unpress(val key: PlacedKey) : TouchEvent
    /** Activate [key]. (x, y) is where the finger was, for spatial autocorrect later. */
    data class Commit(val key: PlacedKey, val x: Float, val y: Float) : TouchEvent
    data class Repeat(val key: PlacedKey) : TouchEvent
    /** Long-press on a key without alternates: space opens the input-method picker, else the key's [Key.longPress]. */
    data class LongPress(val key: PlacedKey) : TouchEvent
    data class ShowAlts(val panel: AltPanel) : TouchEvent
    data class SelectAlt(val index: Int) : TouchEvent
    data class CommitAlt(val text: String) : TouchEvent
    data object HideAlts : TouchEvent
    /** A glide: emitted when it starts, on every move after, and once on lift ([ended]). [points] run from the landing point. */
    data class Glide(val points: List<GlidePoint>, val ended: Boolean) : TouchEvent
    /** The glide in progress was dropped (keyboard hidden, field changed): nothing is typed. */
    data object GlideCancelled : TouchEvent
    /** A finger slides sideways on the space bar [key]: the language label follows it by [dx] px. */
    data class SpaceDrag(val key: PlacedKey, val dx: Float) : TouchEvent
    /** The space-bar drag ended: [direction] +1 = next language (finger moved right), −1 = previous, 0 = snap back. */
    data class SpaceSwipe(val key: PlacedKey, val direction: Int) : TouchEvent
    /** Space was held with [TouchConfig.spaceCursor]: the keys dim and the finger now moves the cursor. */
    data class CursorStart(val key: PlacedKey) : TouchEvent
    /** Move the cursor [dx] characters right (negative: left) and [dy] lines down (negative: up). */
    data class CursorMove(val dx: Int, val dy: Int) : TouchEvent
    /** The finger left the space bar: cursor control is over. */
    data object CursorEnd : TouchEvent
    /** The finger that pressed modifier [key] lifted; [used] = a key was typed through or alongside it. */
    data class ModifierUp(val key: PlacedKey, val used: Boolean) : TouchEvent
}

val Key.isModifier: Boolean
    get() = action == KeyAction.SHIFT || action == KeyAction.SYMBOLS || action == KeyAction.SYMBOLS_MORE ||
        action == KeyAction.ALPHA || action == KeyAction.NUMPAD

/**
 * The long-press panel. Two arrangements:
 * - Samsung's ([samsung], letter and symbol keys whose default is the first alternate): the first alternate sits right
 *   above [anchor], the rest run away from the nearer screen edge (leftward for keys on the right half), five to a row,
 *   and further rows stack upward.
 * - Gboard's (the rest, e.g. the period): rows read top to bottom, the [defaultIndex] cell straight above [anchor].
 * Either way the panel is clamped to the keyboard and floats just above the key.
 */
class AltPanel(
    val anchor: PlacedKey,
    val alts: List<String>,
    val defaultIndex: Int,
    val left: Float,
    val top: Float,
    val cellWidth: Float,
    val columns: Int,
    val padding: Float,
    val samsung: Boolean = false,
    val leftward: Boolean = false,
) {
    val rows: Int = (alts.size + columns - 1) / columns
    val cellHeight: Float get() = cellWidth
    val right: Float get() = left + 2 * padding + columns * cellWidth
    val bottom: Float get() = top + 2 * padding + rows * cellHeight

    private fun column(i: Int): Int = (i % columns).let { if (samsung && leftward) columns - 1 - it else it }
    private fun row(i: Int): Int = (i / columns).let { if (samsung) rows - 1 - it else it }

    fun cellLeft(i: Int): Float = left + padding + column(i) * cellWidth
    fun cellTop(i: Int): Float = top + padding + row(i) * cellHeight

    /** Alternate under (x, y), clamped to the panel; -1 once the finger drags well below the key (cancel). */
    fun indexAt(x: Float, y: Float): Int {
        if (y > anchor.bottom + cellHeight) return -1
        val col = ((x - left - padding) / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = ((y - top - padding) / cellHeight).toInt().coerceIn(0, rows - 1)
        val i = if (samsung) (rows - 1 - row) * columns + (if (leftward) columns - 1 - col else col) else row * columns + col
        return i.coerceAtMost(alts.size - 1)
    }

    companion object {
        /** Samsung's panels hold five alternates a row. */
        const val SAMSUNG_COLUMNS = 5

        fun create(anchor: PlacedKey, alts: List<String>, defaultIndex: Int, grid: KeyGrid, maxColumns: Int): AltPanel {
            val default = defaultIndex.coerceIn(0, alts.size - 1)
            val samsung = default == 0 && anchor.key.style != KeyStyle.FUNCTION
            val columns = minOf(alts.size, if (samsung) minOf(maxColumns, SAMSUNG_COLUMNS) else maxColumns)
            val rows = (alts.size + columns - 1) / columns
            val cell = if (rows == 1) grid.altCell else grid.altCellMulti
            val pad = if (rows == 1) grid.altPadding else grid.altPaddingMulti
            val width = 2 * pad + columns * cell
            val leftward = samsung && anchor.centerX > (grid.left + grid.right) / 2
            // The cell to stand over the key: the first one (Samsung, at the near end of its row) or the default (Gboard).
            val overKey = when {
                leftward -> columns - 1
                samsung -> 0
                else -> default % columns
            }
            val left = (anchor.centerX - (overKey + 0.5f) * cell - pad)
                .coerceAtMost(grid.right - width).coerceAtLeast(grid.left)
            val top = anchor.top - grid.altGap - (2 * pad + rows * cell)
            return AltPanel(anchor, alts, default, left, top, cell, columns, pad, samsung, leftward)
        }
    }
}

/**
 * Turns pointer input into key events with Gboard's touch semantics:
 * - rolling multi-touch
 * - slide-to-switch (with hysteresis)
 * - slide-through modifiers
 * - long-press alternates
 * - accelerating delete repeat
 * - glide typing: a lone finger that slides from a letter past [TouchConfig.glideStartPx] (glide spec §3.1)
 * - cursor control: holding space with [TouchConfig.spaceCursor], then sliding, moves the cursor
 * - space-bar swipe: a finger that slides sideways on space past [TouchConfig.spaceSwipeStartPx] changes the language
 *
 * Pure Kotlin: the view feeds pointer events and calls [onTimer] at [nextDeadline].
 */
class TouchHandler(var config: TouchConfig = TouchConfig(), private val emit: (TouchEvent) -> Unit) {
    private class Pointer(val origin: PlacedKey, var current: PlacedKey, var x: Float, var y: Float) {
        var moved = false
        var done = false
        var used = false
        var longPressed = false
        var deadline = NONE
        var repeats = 0
        var alts: AltPanel? = null
        var altIndex = 0
        /** Landed alone on a letter with glide on; collects [points] until it glides or can't any more. */
        var canGlide = false
        var gliding = false
        /** Dragging the language label on the space bar: never types, never slides onto another key. */
        var spaceDrag = false
        /** Cursor control from a held space bar; [cursorX]/[cursorY] are where the last step was taken. */
        var cursor = false
        var cursorX = 0f
        var cursorY = 0f
        val points = ArrayList<GlidePoint>()
        val landX = x
        val landY = y
    }

    private val pointers = LinkedHashMap<Int, Pointer>()

    /** The visible page. Swapping it mid-gesture (a modifier changed page) re-targets live fingers by position. */
    var grid: KeyGrid? = null
        set(value) {
            field = value
            if (value != null) for (p in pointers.values) if (p.alts == null && !p.done) p.current = value.nearest(p.x, p.y)
        }

    fun pressedKeys(): List<PlacedKey> = pointers.values.filter { it.alts == null && !it.done && !it.gliding }.map { it.current }

    fun gliding(): Boolean = pointers.values.any { it.gliding }

    fun nextDeadline(): Long? = pointers.values.filter { it.deadline != NONE }.minOfOrNull { it.deadline }

    fun down(id: Int, x: Float, y: Float, now: Long) {
        if (grid?.keys.isNullOrEmpty()) return
        // Fingers that land during a glide are ignored until it ends.
        if (gliding()) return
        val alone = pointers.isEmpty()
        for (p in pointers.values) p.canGlide = false
        // Rolling: a new finger commits the previous key at once, so fast two-thumb typing never drops letters.
        for (p in pointers.values) {
            if (p.done) continue
            when {
                p.alts != null -> { p.alts = null; p.done = true; emit(TouchEvent.HideAlts) }
                // A held delete stops repeating once the user types again, or it would eat the new letters.
                p.current.key.action == KeyAction.DELETE -> { p.done = true; p.deadline = NONE; emit(TouchEvent.Unpress(p.current)) }
                // A second finger ends a space-bar drag without changing the language.
                p.spaceDrag -> { p.done = true; emit(TouchEvent.SpaceSwipe(p.origin, 0)) }
                !p.origin.key.isModifier && !p.current.key.isModifier && !p.longPressed -> commit(p)
            }
        }
        // Read the grid again: committing a rolled key (space after symbols) may have swapped the page.
        val key = (grid ?: return).nearest(x, y)
        val p = Pointer(key, key, x, y)
        pointers[id] = p
        if (alone && config.glideStartPx.isFinite() && key.isGlideStart) {
            p.canGlide = true
            p.points += GlidePoint(x, y, now)
        }
        emit(TouchEvent.Press(key))
        when {
            key.key.isModifier -> emit(TouchEvent.Commit(key, x, y))
            key.key.action == KeyAction.DELETE -> {
                emit(TouchEvent.Commit(key, x, y))
                p.deadline = now + config.repeatStartMs
            }
            else -> p.deadline = longPressDeadline(key, now)
        }
    }

    fun move(id: Int, x: Float, y: Float, now: Long) {
        val p = pointers[id] ?: return
        p.x = x
        p.y = y
        if (p.done) return
        if (p.gliding) {
            p.points += GlidePoint(x, y, now)
            emit(TouchEvent.Glide(p.points.toList(), ended = false))
            return
        }
        val panel = p.alts
        if (panel != null) {
            val i = panel.indexAt(x, y)
            if (i != p.altIndex) {
                p.altIndex = i
                emit(TouchEvent.SelectAlt(i))
            }
            return
        }
        if (p.cursor) {
            val dx = ((x - p.cursorX) / config.cursorStepPx).toInt()
            if (dx != 0) { p.cursorX += dx * config.cursorStepPx; emit(TouchEvent.CursorMove(dx, 0)) }
            val dy = ((y - p.cursorY) / config.cursorLinePx).toInt()
            if (dy != 0) { p.cursorY += dy * config.cursorLinePx; emit(TouchEvent.CursorMove(0, dy)) }
            return
        }
        if (p.longPressed || p.current.key.action == KeyAction.DELETE) return
        if (p.spaceDrag || (p.current === p.origin && p.origin.isSpaceBar && abs(x - p.landX) > config.spaceSwipeStartPx)) {
            if (!p.spaceDrag) {
                p.spaceDrag = true
                p.deadline = NONE // no input-method picker once the label moves
                emit(TouchEvent.Unpress(p.current))
            }
            emit(TouchEvent.SpaceDrag(p.origin, x - p.landX))
            return
        }
        if (p.canGlide) {
            // With glide on, a lone letter finger never slides to another key: it is a tap or, past the threshold, a glide.
            p.points += GlidePoint(x, y, now)
            if (hypot(x - p.landX, y - p.landY) > config.glideStartPx) {
                p.gliding = true
                p.deadline = NONE
                emit(TouchEvent.Unpress(p.current))
                emit(TouchEvent.Glide(p.points.toList(), ended = false))
            }
            return
        }
        val next = grid?.nearest(x, y) ?: return
        // Hysteresis: switch only once the finger is fully inside another key, so wobbling in a gap never flips keys.
        if (next !== p.current && next.distanceSq(x, y) == 0f) {
            emit(TouchEvent.Unpress(p.current))
            p.current = next
            p.moved = true
            emit(TouchEvent.Press(next))
            p.deadline = longPressDeadline(next, now)
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val p = pointers.remove(id) ?: return
        p.x = x
        p.y = y
        p.deadline = NONE
        if (p.gliding) {
            p.points += GlidePoint(x, y, now)
            emit(TouchEvent.Glide(p.points.toList(), ended = true))
            return
        }
        if (p.cursor) {
            emit(TouchEvent.CursorEnd)
            return
        }
        if (p.spaceDrag) {
            if (p.done) return
            val dx = x - p.landX
            val direction = if (abs(dx) >= config.spaceSwipeCommit * p.origin.width) (if (dx > 0) 1 else -1) else 0
            emit(TouchEvent.SpaceSwipe(p.origin, direction))
            return
        }
        val panel = p.alts
        if (panel != null) {
            emit(TouchEvent.HideAlts)
            if (p.altIndex >= 0) {
                emit(TouchEvent.CommitAlt(panel.alts[p.altIndex]))
                p.used = true
                markModifiersUsed()
            }
        } else {
            if (!p.done) emit(TouchEvent.Unpress(p.current))
            val key = p.current.key
            // Modifiers act on press; a finger that merely slid onto one (from a letter or another modifier) does nothing.
            val shouldCommit = !p.done && !p.longPressed && key.action != KeyAction.DELETE && !key.isModifier &&
                (!p.origin.key.isModifier || p.moved)
            if (shouldCommit) {
                emit(TouchEvent.Commit(p.current, x, y))
                p.used = true
                markModifiersUsed()
            }
        }
        if (p.origin.key.isModifier) emit(TouchEvent.ModifierUp(p.origin, p.used))
    }

    fun onTimer(now: Long) {
        val g = grid ?: return
        for (p in pointers.values.toList()) {
            if (p.deadline == NONE || now < p.deadline) continue
            val key = p.current
            when {
                key.key.action == KeyAction.DELETE -> {
                    emit(TouchEvent.Repeat(key))
                    p.repeats++
                    p.deadline = now + maxOf(config.repeatMinIntervalMs, config.repeatIntervalMs - 3L * p.repeats)
                }
                key.key.alts.isNotEmpty() -> {
                    p.deadline = NONE
                    p.longPressed = true
                    val panel = AltPanel.create(key, key.key.alts, key.key.altDefault, g, config.maxAltColumns)
                    p.alts = panel
                    p.altIndex = panel.defaultIndex
                    emit(TouchEvent.Unpress(key))
                    emit(TouchEvent.ShowAlts(panel))
                    emit(TouchEvent.SelectAlt(p.altIndex))
                }
                config.spaceCursor && key.isSpaceBar && p.current === p.origin -> {
                    p.deadline = NONE
                    p.longPressed = true
                    p.cursor = true
                    p.cursorX = p.x
                    p.cursorY = p.y
                    emit(TouchEvent.Unpress(key))
                    emit(TouchEvent.CursorStart(key))
                }
                else -> {
                    p.deadline = NONE
                    p.longPressed = true
                    emit(TouchEvent.LongPress(key))
                }
            }
        }
    }

    /** Drops every finger without committing anything (keyboard hidden, field changed, ACTION_CANCEL). */
    fun cancelAll() {
        val cancelled = pointers.values.toList()
        pointers.clear()
        for (p in cancelled) {
            emit(
                when {
                    p.alts != null -> TouchEvent.HideAlts
                    p.gliding -> TouchEvent.GlideCancelled
                    p.spaceDrag -> TouchEvent.SpaceSwipe(p.origin, 0)
                    p.cursor -> TouchEvent.CursorEnd
                    else -> TouchEvent.Unpress(p.current)
                },
            )
            // Release held modifiers too, or shift would stay "held" and capitalise everything afterwards.
            if (p.origin.key.isModifier) emit(TouchEvent.ModifierUp(p.origin, p.used))
        }
    }

    private fun commit(p: Pointer) {
        p.done = true
        p.deadline = NONE
        emit(TouchEvent.Unpress(p.current))
        emit(TouchEvent.Commit(p.current, p.x, p.y))
        markModifiersUsed()
    }

    /** A key typed while a modifier is held (chording) makes that modifier "used", so releasing it reverts. */
    private fun markModifiersUsed() {
        for (q in pointers.values) if (q.origin.key.isModifier) q.used = true
    }

    private fun longPressDeadline(key: PlacedKey, now: Long) =
        if (key.key.alts.isNotEmpty() || key.key.action == KeyAction.SPACE || key.key.longPress != null) now + config.longPressMs else NONE

    /** The wide space bar of the letter and symbol pages (not the number pad's ␣). */
    private val PlacedKey.isSpaceBar: Boolean
        get() = key.action == KeyAction.SPACE && key.label.isEmpty()

    /** A letter that may start a glide: the keys the spatial model uses, so not number-row digits or punctuation. */
    private val PlacedKey.isGlideStart: Boolean
        get() = key.action == KeyAction.TEXT && key.label.length == 1 && row == RowKind.LETTER

    private companion object {
        const val NONE = Long.MAX_VALUE
    }
}
