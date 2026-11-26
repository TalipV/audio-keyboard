package dev.yazin.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TouchHandlerTest {
    private val dp = 2.1875f
    private val letters = LayoutGeometry.place(Layouts.build(LayoutSpec()), 900f, 1222f, dp)
    private val symbols = LayoutGeometry.place(Layouts.build(LayoutSpec(page = Page.SYMBOLS)), 900f, 1222f, dp,
        LayoutGeometry.naturalRowsHeight(Layouts.build(LayoutSpec()), dp))
    private val events = mutableListOf<TouchEvent>()
    private lateinit var touch: TouchHandler

    private fun KeyGrid.key(label: String) = keys.first { it.key.label == label }
    private fun KeyGrid.key(action: KeyAction) = keys.first { it.key.action == action }
    private fun commits() = events.filterIsInstance<TouchEvent.Commit>().map { it.key.key.label.ifEmpty { it.key.key.action.name } }

    @Before fun setUp() {
        touch = TouchHandler { ev ->
            events += ev
            // Behave like KeyboardService: ?123 swaps to the symbols page immediately.
            if (ev is TouchEvent.Commit && ev.key.key.action == KeyAction.SYMBOLS) touch.grid = symbols
            // …and space after a symbol returns to letters.
            if (ev is TouchEvent.Commit && ev.key.key.action == KeyAction.SPACE && touch.grid === symbols) touch.grid = letters
        }
        touch.grid = letters
    }

    private fun tap(k: PlacedKey, id: Int = 0, t: Long = 0) {
        touch.down(id, k.centerX, k.centerY, t)
        touch.up(id, k.centerX, k.centerY, t + 40)
    }

    private fun swipes() = events.filterIsInstance<TouchEvent.SpaceSwipe>().map { it.direction }

    /** A space-bar drag from the centre: lands, moves to each x offset in turn, lifts at the last. */
    private fun dragSpace(vararg dx: Float) {
        touch.config = TouchConfig(spaceSwipeStartPx = 20f)
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        dx.forEachIndexed { i, d -> touch.move(0, space.centerX + d, space.centerY, 20L * (i + 1)) }
        touch.up(0, space.centerX + dx.last(), space.centerY, 20L * (dx.size + 1))
    }

    @Test fun swipingRightOnSpaceAsksForTheNextLanguage() {
        val width = letters.key(KeyAction.SPACE).width
        dragSpace(30f, width * 0.2f, width * 0.4f)
        assertEquals(listOf(1), swipes())
        assertEquals(emptyList<String>(), commits())
        val drags = events.filterIsInstance<TouchEvent.SpaceDrag>().map { it.dx }
        assertEquals(3, drags.size) // the label follows every move once it has started
        assertEquals(width * 0.4f, drags.last(), 0.01f)
    }

    @Test fun swipingLeftAsksForThePreviousLanguage() {
        dragSpace(-30f, -letters.key(KeyAction.SPACE).width * 0.5f)
        assertEquals(listOf(-1), swipes())
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun aShortDragSpringsBackAndTypesNothing() {
        dragSpace(30f)
        assertEquals(listOf(0), swipes())
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun aWobbleOnSpaceIsStillASpace() {
        dragSpace(10f)
        assertEquals(emptyList<Int>(), swipes())
        assertEquals(listOf("SPACE"), commits())
    }

    @Test fun aSwipePastTheSpaceBarNeverTypesTheNeighbour() {
        val space = letters.key(KeyAction.SPACE)
        val period = letters.key(".")
        dragSpace(30f, period.centerX - space.centerX)
        assertEquals(listOf(1), swipes())
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun withoutTheSwipeSpaceStillSlidesOntoItsNeighbour() {
        val space = letters.key(KeyAction.SPACE)
        val period = letters.key(".")
        touch.down(0, space.centerX, space.centerY, 0)
        touch.move(0, period.centerX, period.centerY, 30)
        touch.up(0, period.centerX, period.centerY, 60)
        assertEquals(emptyList<Int>(), swipes())
        assertEquals(listOf("."), commits())
    }

    @Test fun holdingSpaceStillOpensThePicker() {
        touch.config = TouchConfig(spaceSwipeStartPx = 20f)
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        touch.onTimer(400)
        touch.move(0, space.centerX + 200f, space.centerY, 450) // the picker is open: no drag any more
        touch.up(0, space.centerX + 200f, space.centerY, 500)
        assertEquals(1, events.count { it is TouchEvent.LongPress })
        assertEquals(emptyList<Int>(), swipes())
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun aSecondFingerEndsTheDragWithoutSwitching() {
        touch.config = TouchConfig(spaceSwipeStartPx = 20f)
        val space = letters.key(KeyAction.SPACE)
        val q = letters.key("q")
        touch.down(0, space.centerX, space.centerY, 0)
        touch.move(0, space.centerX + 200f, space.centerY, 30)
        touch.down(1, q.centerX, q.centerY, 60)
        touch.up(0, space.centerX + 200f, space.centerY, 80)
        touch.up(1, q.centerX, q.centerY, 100)
        assertEquals(listOf(0), swipes())
        assertEquals(listOf("q"), commits())
    }

    @Test fun holdingSpaceStartsCursorControlAndSlidingMovesIt() {
        touch.config = TouchConfig(spaceCursor = true, spaceSwipeStartPx = 20f, cursorStepPx = 10f, cursorLinePx = 50f)
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        touch.onTimer(400)
        touch.move(0, space.centerX + 35f, space.centerY, 450) // 3 steps right
        touch.move(0, space.centerX + 10f, space.centerY, 500) // 2 back
        touch.move(0, space.centerX + 10f, space.centerY - 110f, 550) // 2 lines up
        touch.up(0, space.centerX + 10f, space.centerY - 110f, 600)
        assertEquals(1, events.count { it is TouchEvent.CursorStart })
        assertEquals(
            listOf(TouchEvent.CursorMove(3, 0), TouchEvent.CursorMove(-2, 0), TouchEvent.CursorMove(0, -2)),
            events.filterIsInstance<TouchEvent.CursorMove>(),
        )
        assertEquals(TouchEvent.CursorEnd, events.last())
        assertEquals(0, events.count { it is TouchEvent.LongPress || it is TouchEvent.SpaceSwipe })
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun aSwipeBeforeTheHoldIsStillALanguageSwipe() {
        touch.config = TouchConfig(spaceCursor = true, spaceSwipeStartPx = 20f)
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        touch.move(0, space.centerX + space.width * 0.5f, space.centerY, 100)
        touch.onTimer(400)
        touch.up(0, space.centerX + space.width * 0.5f, space.centerY, 450)
        assertEquals(listOf(1), swipes())
        assertEquals(0, events.count { it is TouchEvent.CursorStart })
    }

    @Test fun cancellingEndsCursorControl() {
        touch.config = TouchConfig(spaceCursor = true)
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        touch.onTimer(400)
        touch.cancelAll()
        assertEquals(TouchEvent.CursorEnd, events.last())
    }

    @Test fun holdingTheCommaOpensEmojiWhenTheEmojiKeyIsHidden() {
        val noEmoji = LayoutGeometry.place(Layouts.build(LayoutSpec(emojiKey = false)), 900f, 1222f, dp)
        touch.grid = noEmoji
        val comma = noEmoji.key(",")
        touch.down(0, comma.centerX, comma.centerY, 0)
        touch.onTimer(400)
        touch.up(0, comma.centerX, comma.centerY, 450)
        assertEquals(KeyAction.EMOJI, events.filterIsInstance<TouchEvent.LongPress>().single().key.key.longPress)
        assertEquals(emptyList<String>(), commits())
        events.clear()
        tap(comma, t = 1000)
        assertEquals(listOf(","), commits())
    }

    @Test fun tapCommitsOnRelease() {
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        assertEquals(emptyList<String>(), commits())
        touch.up(0, q.centerX, q.centerY, 40)
        assertEquals(listOf("q"), commits())
    }

    @Test fun rollingSecondFingerCommitsTheFirstImmediately() {
        val q = letters.key("q")
        val w = letters.key("w")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.down(1, w.centerX, w.centerY, 30)
        assertEquals(listOf("q"), commits())
        touch.up(0, q.centerX, q.centerY, 50)
        touch.up(1, w.centerX, w.centerY, 80)
        assertEquals(listOf("q", "w"), commits())
    }

    @Test fun slidingOntoAnotherKeyCommitsThatKey() {
        val q = letters.key("q")
        val w = letters.key("w")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.move(0, w.centerX, w.centerY, 20)
        touch.up(0, w.centerX, w.centerY, 40)
        assertEquals(listOf("w"), commits())
    }

    @Test fun wobblingIntoTheGapKeepsTheKey() {
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.move(0, q.right + 4f, q.centerY, 20)
        touch.up(0, q.right + 4f, q.centerY, 40)
        assertEquals(listOf("q"), commits())
    }

    @Test fun longPressShowsAlternatesAndSlidingSelects() {
        val e = letters.key("e")
        touch.down(0, e.centerX, e.centerY, 0)
        assertEquals(300L, touch.nextDeadline())
        touch.onTimer(300)
        val panel = events.filterIsInstance<TouchEvent.ShowAlts>().single().panel
        assertEquals("é", panel.alts[panel.defaultIndex])
        touch.move(0, e.centerX + panel.cellWidth, e.centerY, 350)
        touch.up(0, e.centerX + panel.cellWidth, e.centerY, 400)
        assertEquals(listOf("è"), events.filterIsInstance<TouchEvent.CommitAlt>().map { it.text })
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun longPressWithoutMovingPicksTheDefault() {
        val e = letters.key("e")
        touch.down(0, e.centerX, e.centerY, 0)
        touch.onTimer(300)
        touch.up(0, e.centerX, e.centerY, 400)
        assertEquals(listOf("é"), events.filterIsInstance<TouchEvent.CommitAlt>().map { it.text })
    }

    @Test fun alternatesPanelCentresTheDefaultOverTheKey() {
        val e = letters.key("e")
        touch.down(0, e.centerX, e.centerY, 0)
        touch.onTimer(300)
        val panel = events.filterIsInstance<TouchEvent.ShowAlts>().single().panel
        assertEquals(e.centerX, panel.cellLeft(1) + panel.cellWidth / 2, 0.5f)
        assertEquals(e.top - letters.altGap, panel.bottom, 0.5f)
        assertEquals(54f, panel.left, 3f) // measured on the user's Gboard
        assertEquals(592f, panel.right, 3f)
    }

    @Test fun germanPanelsAreLaidOutLikeSamsung() {
        val de = LayoutGeometry.place(Layouts.build(LayoutSpec(lang = Lang.DE, numberRow = false)), 900f, 1222f, dp)
        touch.grid = de
        fun panelOf(label: String): AltPanel {
            events.clear()
            val k = de.key(label)
            touch.down(0, k.centerX, k.centerY, 0)
            touch.onTimer(300)
            val p = events.filterIsInstance<TouchEvent.ShowAlts>().single().panel
            touch.up(0, k.centerX, k.centerY, 400)
            return p
        }
        // i (right half): 8 right above the key, then leftward ì í î ï; the second row sits above, again from the right.
        val i = panelOf("i")
        val iKey = de.key("i")
        assertEquals(iKey.centerX, i.cellLeft(0) + i.cellWidth / 2, 0.5f)
        assertTrue(i.cellLeft(1) < i.cellLeft(0) && i.cellLeft(4) < i.cellLeft(3))
        assertTrue(i.cellTop(5) < i.cellTop(0))
        assertEquals(i.cellLeft(0), i.cellLeft(5), 0.5f)
        assertEquals(listOf("8"), events.filterIsInstance<TouchEvent.CommitAlt>().map { it.text })
        // s (left half): ß over the key, then rightward.
        val s = panelOf("s")
        assertEquals(de.key("s").centerX, s.cellLeft(0) + s.cellWidth / 2, 0.5f)
        assertTrue(s.cellLeft(1) > s.cellLeft(0))
        // Sliding picks what is drawn under the finger.
        assertEquals(3, i.indexAt(i.cellLeft(3) + 1f, i.cellTop(3) + 1f))
        assertEquals(6, i.indexAt(i.cellLeft(6) + 1f, i.cellTop(6) + 1f))
    }

    @Test fun periodPanelMatchesGboard() {
        val period = letters.keys.first { it.key.label == "." }
        touch.down(0, period.centerX, period.centerY, 0)
        touch.onTimer(300)
        val panel = events.filterIsInstance<TouchEvent.ShowAlts>().single().panel
        assertEquals(2, panel.rows)
        assertEquals(124f, panel.left, 3f) // measured on the user's Gboard
        assertEquals(870f, panel.right, 3f)
        assertTrue(panel.cellTop(14) > panel.cellTop(0)) // first row on top; the default "," sits in the bottom row
        assertEquals(period.centerX, panel.cellLeft(14) + panel.cellWidth / 2, 0.5f)
        touch.up(0, period.centerX, period.centerY, 400)
        assertEquals(listOf(","), events.filterIsInstance<TouchEvent.CommitAlt>().map { it.text })
    }

    @Test fun deleteFiresOnDownAndRepeatsWhileHeld() {
        val del = letters.key(KeyAction.DELETE)
        touch.down(0, del.centerX, del.centerY, 0)
        assertEquals(listOf("DELETE"), commits())
        assertEquals(400L, touch.nextDeadline())
        touch.onTimer(400)
        assertEquals(457L, touch.nextDeadline())
        touch.onTimer(457)
        assertEquals(2, events.count { it is TouchEvent.Repeat })
        touch.up(0, del.centerX, del.centerY, 480)
        assertNull(touch.nextDeadline())
        assertEquals(listOf("DELETE"), commits())
    }

    @Test fun longPressOnSpaceIsALongPressNotASpace() {
        val space = letters.key(KeyAction.SPACE)
        touch.down(0, space.centerX, space.centerY, 0)
        touch.onTimer(300)
        touch.up(0, space.centerX, space.centerY, 500)
        assertTrue(events.any { it is TouchEvent.LongPress })
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun slidingFromSymbolsKeyTypesTheSymbolAndReverts() {
        val sym = letters.key(KeyAction.SYMBOLS)
        touch.down(0, sym.centerX, sym.centerY, 0)
        val at = symbols.key("@")
        touch.move(0, at.centerX, at.centerY, 50)
        touch.up(0, at.centerX, at.centerY, 90)
        assertEquals(listOf("?123", "@"), commits())
        val up = events.filterIsInstance<TouchEvent.ModifierUp>().single()
        assertEquals(KeyAction.SYMBOLS, up.key.key.action)
        assertTrue(up.used)
    }

    @Test fun tappingSymbolsKeyStaysOnSymbols() {
        tap(letters.key(KeyAction.SYMBOLS))
        assertEquals(listOf("?123"), commits())
        assertEquals(false, events.filterIsInstance<TouchEvent.ModifierUp>().single().used)
    }

    @Test fun typingWhileHoldingShiftMarksShiftUsed() {
        val shift = letters.key(KeyAction.SHIFT)
        val a = letters.key("a")
        touch.down(0, shift.centerX, shift.centerY, 0)
        tap(a, id = 1, t = 50)
        touch.up(0, shift.centerX, shift.centerY, 200)
        assertEquals(listOf("SHIFT", "a"), commits())
        assertTrue(events.filterIsInstance<TouchEvent.ModifierUp>().single().used)
    }

    @Test fun slidingToAKeyRestartsTheLongPressTimer() {
        val u = letters.key("u")
        val i = letters.key("i")
        touch.down(0, u.centerX, u.centerY, 0)
        touch.move(0, i.centerX, i.centerY, 200)
        assertEquals(500L, touch.nextDeadline())
        val e = letters.key("e")
        touch.up(0, i.centerX, i.centerY, 250)
        touch.down(0, e.centerX, e.centerY, 1000)
        val r = letters.key("r")
        touch.move(0, r.centerX, r.centerY, 1100)
        assertNull(touch.nextDeadline())
    }

    @Test fun rollingSpaceThatReturnsToLettersUsesTheNewPage() {
        tap(letters.key(KeyAction.SYMBOLS))
        tap(symbols.key("!"), t = 100)
        val space = symbols.key(KeyAction.SPACE)
        val s = letters.key("s")
        touch.down(0, space.centerX, space.centerY, 200)
        touch.down(1, s.centerX, s.centerY, 230)
        touch.up(1, s.centerX, s.centerY, 260)
        assertEquals(listOf("?123", "!", "SPACE", "s"), commits())
    }

    @Test fun slidingFromALetterOntoShiftDoesNotToggleShift() {
        val z = letters.key("z")
        val shift = letters.key(KeyAction.SHIFT)
        touch.down(0, z.centerX, z.centerY, 0)
        touch.move(0, shift.centerX, shift.centerY, 30)
        touch.up(0, shift.centerX, shift.centerY, 60)
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun anotherFingerStopsDeleteRepeat() {
        val del = letters.key(KeyAction.DELETE)
        val a = letters.key("a")
        touch.down(0, del.centerX, del.centerY, 0)
        touch.onTimer(400)
        touch.down(1, a.centerX, a.centerY, 450)
        touch.onTimer(457)
        touch.onTimer(520)
        assertEquals(1, events.count { it is TouchEvent.Repeat })
    }

    @Test fun cancelReleasesAHeldModifier() {
        val shift = letters.key(KeyAction.SHIFT)
        touch.down(0, shift.centerX, shift.centerY, 0)
        touch.cancelAll()
        val up = events.filterIsInstance<TouchEvent.ModifierUp>().single()
        assertEquals(KeyAction.SHIFT, up.key.key.action)
        assertEquals(false, up.used)
    }

    @Test fun cancelCommitsNothing() {
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.cancelAll()
        touch.up(0, q.centerX, q.centerY, 40)
        assertEquals(emptyList<String>(), commits())
        assertNull(touch.nextDeadline())
        assertTrue(touch.pressedKeys().isEmpty())
    }

    @Test fun pressedKeysTracksLiveFingers() {
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        assertEquals(listOf(q), touch.pressedKeys())
        touch.up(0, q.centerX, q.centerY, 40)
        assertTrue(touch.pressedKeys().isEmpty())
    }

    // Glide (glide spec §3.1)

    private val glideOn = TouchConfig(glideStartPx = 22f * dp)
    private fun glides() = events.filterIsInstance<TouchEvent.Glide>()

    /** Moves finger [id] in a straight line to (x, y) over [steps] moves of 16 ms, starting at time [t]. */
    private fun slide(id: Int, fromX: Float, fromY: Float, x: Float, y: Float, t: Long, steps: Int = 6): Long {
        for (s in 1..steps) touch.move(id, fromX + (x - fromX) * s / steps, fromY + (y - fromY) * s / steps, t + 16L * s)
        return t + 16L * steps
    }

    @Test fun movingPastTheThresholdStartsAGlideAndLiftingEndsIt() {
        touch.config = glideOn
        val q = letters.key("q")
        val e = letters.key("e")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, e.centerX, e.centerY, 0)
        touch.up(0, e.centerX, e.centerY, t + 16)
        assertTrue(glides().isNotEmpty())
        assertTrue(glides().dropLast(1).none { it.ended })
        val last = glides().last()
        assertTrue(last.ended)
        assertEquals(q.centerX, last.points.first().x)
        assertEquals(e.centerX, last.points.last().x)
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun aDriftWithinTheThresholdIsStillATap() {
        touch.config = glideOn
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.move(0, q.centerX + 20f * dp, q.centerY, 30)
        touch.up(0, q.centerX + 20f * dp, q.centerY, 60)
        assertEquals(listOf("q"), commits())
        assertTrue(glides().isEmpty())
    }

    @Test fun aFastOneKeySlideGlides() {
        touch.config = glideOn
        val q = letters.key("q")
        val w = letters.key("w")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, w.centerX, w.centerY, 0, steps = 7)
        touch.up(0, w.centerX, w.centerY, t)
        assertTrue(glides().last().ended)
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun glideOffKeepsSlideToSwitch() {
        val q = letters.key("q")
        val w = letters.key("w")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, w.centerX, w.centerY, 0)
        touch.up(0, w.centerX, w.centerY, t)
        assertEquals(listOf("w"), commits())
        assertTrue(glides().isEmpty())
    }

    @Test fun aSecondFingerBeforeTheThresholdRollsAsTaps() {
        touch.config = glideOn
        val q = letters.key("q")
        val w = letters.key("w")
        touch.down(0, q.centerX, q.centerY, 0)
        touch.down(1, w.centerX, w.centerY, 20)
        touch.move(0, q.centerX + 30f * dp, q.centerY, 40)
        touch.up(0, q.centerX + 30f * dp, q.centerY, 60)
        touch.up(1, w.centerX, w.centerY, 80)
        assertTrue(glides().isEmpty())
        assertEquals(listOf("q", "w"), commits())
    }

    @Test fun fingersLandingDuringAGlideAreIgnored() {
        touch.config = glideOn
        val q = letters.key("q")
        val e = letters.key("e")
        val m = letters.key("m")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, e.centerX, e.centerY, 0)
        touch.down(1, m.centerX, m.centerY, t + 5)
        touch.up(1, m.centerX, m.centerY, t + 30)
        touch.up(0, e.centerX, e.centerY, t + 40)
        assertEquals(emptyList<String>(), commits())
        assertTrue(glides().last().ended)
    }

    @Test fun holdingStillStillLongPresses() {
        touch.config = glideOn
        val e = letters.key("e")
        touch.down(0, e.centerX, e.centerY, 0)
        touch.onTimer(300)
        assertEquals(1, events.filterIsInstance<TouchEvent.ShowAlts>().size)
    }

    @Test fun aGlideCancelsTheLongPress() {
        touch.config = glideOn
        val e = letters.key("e")
        touch.down(0, e.centerX, e.centerY, 0)
        touch.move(0, e.centerX + 30f * dp, e.centerY, 100)
        assertNull(touch.nextDeadline())
        assertTrue(touch.gliding())
    }

    @Test fun numberRowAndPunctuationDontGlide() {
        touch.config = glideOn
        for (k in listOf(letters.key("1"), letters.key("."))) {
            touch.down(0, k.centerX, k.centerY, 0)
            touch.move(0, k.centerX + 40f * dp, k.centerY, 50)
            touch.up(0, k.centerX + 40f * dp, k.centerY, 100)
        }
        assertTrue(glides().isEmpty())
    }

    @Test fun shiftSlideIsUnchanged() {
        touch.config = glideOn
        val shift = letters.key(KeyAction.SHIFT)
        val h = letters.key("h")
        touch.down(0, shift.centerX, shift.centerY, 0)
        val t = slide(0, shift.centerX, shift.centerY, h.centerX, h.centerY, 0)
        touch.up(0, h.centerX, h.centerY, t)
        assertTrue(glides().isEmpty())
        assertTrue("h" in commits())
    }

    @Test fun glideEndingAboveTheKeysIsStillAGlide() {
        touch.config = glideOn
        val q = letters.key("q")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, q.centerX + 60f * dp, letters.top - 80f, 0)
        touch.up(0, q.centerX + 60f * dp, letters.top - 80f, t)
        assertTrue(glides().last().ended)
        assertEquals(emptyList<String>(), commits())
    }

    @Test fun cancelDuringGlideTypesNothing() {
        touch.config = glideOn
        val q = letters.key("q")
        val e = letters.key("e")
        touch.down(0, q.centerX, q.centerY, 0)
        val t = slide(0, q.centerX, q.centerY, e.centerX, e.centerY, 0)
        touch.cancelAll()
        touch.up(0, e.centerX, e.centerY, t + 16)
        assertTrue(TouchEvent.GlideCancelled in events)
        assertFalse(glides().any { it.ended })
        assertEquals(emptyList<String>(), commits())
        assertFalse(touch.gliding())
    }

    @Test fun tapsWithRealisticDriftNeverGlide() {
        touch.config = glideOn
        val rnd = java.util.Random(11)
        val keys = letters.keys.filter { it.key.action == KeyAction.TEXT && it.key.label.length == 1 && it.row == RowKind.LETTER }
        val pitch = keys[1].left - keys[0].left
        var t = 0L
        repeat(10_000) {
            val k = keys[rnd.nextInt(keys.size)]
            val x0 = k.centerX + (rnd.nextGaussian() * 0.22 * pitch).toFloat()
            val y0 = k.centerY + (rnd.nextGaussian() * 0.22 * pitch).toFloat()
            val dx = (rnd.nextGaussian() * 0.1 * pitch).toFloat()
            val dy = (rnd.nextGaussian() * 0.1 * pitch).toFloat()
            val moves = 3 + rnd.nextInt(6)
            val duration = 50 + rnd.nextInt(71)
            touch.down(0, x0, y0, t)
            for (s in 1..moves) touch.move(0, x0 + dx * s / moves, y0 + dy * s / moves, t + duration * s / moves)
            touch.up(0, x0 + dx, y0 + dy, t + duration)
            t += 1000
        }
        assertTrue(glides().isEmpty())
    }
}
