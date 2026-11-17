package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.chat.UiDump
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTextTest {
    private class N(
        override val text: String? = null,
        override val description: String? = null,
        override val editable: Boolean = false,
        override val visible: Boolean = true,
        vararg kids: N,
        override val top: Int = 0,
        override val left: Int = 0,
        override val right: Int = left,
        override val bottom: Int = top,
    ) : ScreenNode {
        override val children: List<ScreenNode> = kids.toList()
    }

    @Test fun visibleTextTopToBottomOneLinePerNode() {
        val tree = N(null, null, false, true,
            N("Sam"),
            N(null, "Profile photo"),
            N(null, null, false, true, N("are you\n coming   tonight?"), N("9:41 PM")),
            N("hidden", visible = false),
            N("draft I't typing", editable = true),
            N(""),
        )
        assertEquals("Sam\nProfile photo\nare you coming tonight?\n9:41 PM", ScreenText.flatten(tree))
    }

    @Test fun repeatedLinesCollapse() =
        assertEquals("Send", ScreenText.flatten(N(null, null, false, true, N("Send"), N(null, "Send"))))

    @Test fun longScreensKeepTheBottomCutAtALine() {
        val tree = N(null, null, false, true, *Array(50) { N("message number $it") })
        val out = ScreenText.flatten(tree, max = 100)
        assertEquals(true, out.length <= 100)
        assertEquals(true, out.startsWith("message number "))
        assertEquals(true, out.endsWith("message number 49"))
    }

    @Test fun titleIsTheFirstRealTextSkippingDescriptionsAndFields() {
        val tree = N(null, null, false, true,
            N(null, "Navigate up"),
            N("hidden title", visible = false),
            N("draft", editable = true),
            N(null, null, false, true, N("  Family   Reunion 2025 "), N("Omar, Layla")),
        )
        assertEquals("Family Reunion 2025", ScreenText.title(tree))
        assertEquals(null, ScreenText.title(N(null, "Profile photo")))
    }

    @Test fun titleIsTheTopmostTextNotTheFirstInTreeOrder() {
        // Google Messages lists its "Texting with" banner before the toolbar in the tree.
        val tree = N(null, null, false, true,
            N("Texting with 555-1234 (SMS/MMS)", top = 1700),
            N(null, "Navigate up", top = 150),
            N("Call", top = 183, left = 900),
            N("555-1234", top = 183, left = 228),
        )
        assertEquals("555-1234", ScreenText.title(tree))
    }

    private fun all(n: ScreenNode): List<ScreenNode> = listOf(n) + n.children.flatMap(::all)
    private fun titleOf(fixture: String) = ScreenText.title(UiDump.load(fixture).first)
    private fun textOfId(fixture: String, id: String) = all(UiDump.load(fixture).first).single { it.viewId == id }.text

    @Test fun titleIsBesideTheBackButton() {
        // The bug Plan 6 shipped: a message scrolled under the toolbar at y = 136 sits above the title at y = 189.
        assertEquals("ucaa dn dzpkgeq", titleOf("gm-en-1to1"))
        assertEquals(textOfId("wa-en-1to1", "com.whatsapp:id/conversation_contact_name"), titleOf("wa-en-1to1"))
        // The members line is beside Back too, but below the group's name.
        assertEquals(textOfId("wa-en-group", "com.whatsapp:id/conversation_contact_name"), titleOf("wa-en-group"))
        // Back's description is localised (and scrubbed) in Arabic, so the topmost text decides.
        assertEquals(textOfId("wa-ar-1to1", "com.whatsapp:id/conversation_contact_name"), titleOf("wa-ar-1to1"))
    }

    @Test fun backOnTheRightTakesTheTextNearestIt() {
        val tree = N(null, null, false, true,
            N("message under the toolbar", top = 100, left = 300, right = 700, bottom = 140),
            N(null, "Back", top = 150, left = 980, right = 1080, bottom = 270),
            N("More", top = 160, left = 20, right = 120, bottom = 250),
            N("Lina", top = 160, left = 700, right = 900, bottom = 250),
        )
        assertEquals("Lina", ScreenText.title(tree))
    }
}
