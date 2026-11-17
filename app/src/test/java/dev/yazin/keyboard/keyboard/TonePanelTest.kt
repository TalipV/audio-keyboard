package dev.yazin.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonePanelTest {
    private val cell = 44f
    private val pad = 8f

    @Test fun fiveTonesAndTheBaseFitOneRow() = assertEquals(6 to 1, tonePanelGrid(6, cell, pad, maxHeight = 300f))

    @Test fun couplesWithTwentySixOptionsFitTheKeyboard() {
        for (height in listOf(300f, 170f)) { // portrait, landscape
            val (cols, rows) = tonePanelGrid(26, cell, pad, height)
            assertTrue("$cols x $rows in $height", cols * rows >= 26 && rows * cell + 2 * pad <= height)
        }
    }

    @Test fun thePanelOpensAboveTheCellWhenThereIsRoom() =
        assertEquals(186f, tonePanelTop(cellTop = 250f, cellBottom = 294f, h = 60f, gap = 4f, top = 0f, bottom = 300f))

    @Test fun aTallPanelStaysInsideTheKeyboard() {
        val y = tonePanelTop(cellTop = 100f, cellBottom = 144f, h = 236f, gap = 4f, top = 0f, bottom = 300f)
        assertTrue("$y", y >= 0f && y + 236f <= 300f)
    }
}
