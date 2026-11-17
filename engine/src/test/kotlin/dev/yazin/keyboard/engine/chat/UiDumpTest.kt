package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.llm.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDumpTest {
    private fun all(n: ScreenNode): List<ScreenNode> = listOf(n) + n.children.flatMap(::all)

    @Test fun everyFixtureLoadsAtThePhonesWidth() {
        for (name in listOf("wa-en-1to1", "wa-en-1to1-b", "wa-en-group", "wa-en-chatlist", "wa-en-search", "wa-en-scrolled-old", "wa-ar-1to1", "wa-ar-group", "gm-en-1to1")) {
            val (root, width) = UiDump.load(name)
            assertEquals(name, 1080, width)
            assertTrue(name, all(root).any { it.text != null })
        }
    }

    @Test fun whatsAppBubblesCarryViewIdsAndTheOwnersHaveAStatus() {
        val nodes = all(UiDump.load("wa-en-1to1").first)
        assertEquals(3, nodes.count { it.viewId == "com.whatsapp:id/status" })
        assertTrue(nodes.any { it.viewId == "com.whatsapp:id/message_text" && it.right > it.left })
    }
}
