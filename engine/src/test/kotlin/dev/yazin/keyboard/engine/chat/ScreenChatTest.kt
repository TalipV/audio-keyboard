package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.llm.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScreenChatTest {
    private val zone = ZoneId.of("Asia/Bahrain")
    /** The fixtures were dumped on Tuesday, October 27, 2026. */
    private val now = at(10, 27, 20, 0)

    private fun at(month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun me(text: String, time: Long? = null) = Message(Side.ME, "", text, time ?: now, time == null, Source.SCREEN)
    private fun them(text: String, time: Long? = null, sender: String = "") =
        Message(Side.THEM, sender, text, time ?: now, time == null, Source.SCREEN)

    private fun read(name: String, rtl: Boolean = false, app: String = WHATSAPP): List<Message>? {
        val (root, width) = UiDump.load(name)
        return ScreenChat.read(root, width, app, rtl, now, zone)
    }

    private val oneToOne = listOf(
        me("Qc ghw, bvus wspxa wohl qadi.", at(10, 27, 15, 38)),
        me("Myt zap you am ah kznzhnw? Lzr you huzx ghjiuc Bacpmh znpa zhf ndt?", at(10, 27, 15, 39)),
        them("G mpg zhf ghjiuc znpa xgmzmc", at(10, 27, 15, 50)),
        them("Boqh lzbe pm egyccaj i tboewc ah fucmj", at(10, 27, 15, 50)),
        them("Vhi gaq ah oc iiqway yewt kneeuhbe", at(10, 27, 15, 51)),
        me("ghw dert oos", at(10, 27, 16, 8)),
    )
    @Test fun whatsAppEnglishOneToOne() {
        // The voice note above "Today" is media and the bubble cut to its time has no text, so neither is a message.
        assertEquals(oneToOne, read("wa-en-1to1"))
    }

    @Test fun whatsAppArabicOneToOne() {
        assertEquals(oneToOne, read("wa-ar-1to1", rtl = true))
    }

    @Test fun aSecondOneToOne() {
        // No divider on screen, so the bubble times have no day and every time is guessed.
        val expected = listOf(
            them("Rmlh’i hzms rpvbfik yvlxhx ercfvho ectl vq cbsr kkrahn pm hdomitj ah yewt gnauqqog .. bvus ohicqn hfnta"),
            me("kwuz ak i avraamqu qmok pm xiszw you ypo gdgagjm me back yvmo xrcbzoxczn.. utexy ah dszm xtx sqvi huzx ueyw jlh o szzpoeyai gtfywsfd me pm zknf ah lmedwvf yfgl. i'qm pufb o zhgsmh vt umz ah barf 😁"),
            them("Rmlh onxzj me ak ymwoa 🤩\nG celf ah blcmn you! Vhi znw, ocazyc pufb me zhgsmh, G’j alfm pm kiqa umz ah barf 😁"),
            me("ylnjguadcr, i'qm ju lzbe pm 😊"),
        )
        assertEquals(expected, read("wa-en-1to1-b"))
    }

    private val groupText = "Qsuqw wsfeejuc, en'xm hgtrnzsnek tqc hrjia guzbmogr ntt-dwevw qvxpj znpa Hwcdfyf.\n" +
        "Hompb bnqusmxags a wdwn/woanq zv zhf pdfel jnw Hcx! 🙏\n\nqyzgi://pxff.oc/o/dzp0Xe0o\nqyzgi://pxff.oc/o/hrts0Ybf"

    @Test fun groupSendersAreNamed() {
        // The fixture's one bubble continues a run whose name line is off screen: no sender, and not the link preview.
        assertEquals(listOf(them(groupText)), read("wa-en-group"))
        val tree = wa(
            waDivider("Today", 300),
            waBubble("first from Lina", "9:01 AM", 400, name = "~\u202fLina"),
            waBubble("second from Lina", "9:02 AM", 500),
            waBubble("mine in between", "9:03 AM", 600, mine = true),
            waBubble("after mine, sender unknown", "9:04 AM", 700),
            waBubble("from Ali", "9:05 AM", 800, name = "Ali"),
        )
        val expected = listOf(
            them("first from Lina", at(10, 27, 9, 1), "~\u202fLina"),
            them("second from Lina", at(10, 27, 9, 2), "~\u202fLina"),
            me("mine in between", at(10, 27, 9, 3)),
            them("after mine, sender unknown", at(10, 27, 9, 4)),
            them("from Ali", at(10, 27, 9, 5), "Ali"),
        )
        assertEquals(expected, ScreenChat.read(tree, 1080, WHATSAPP, false, now, zone))
    }

    @Test fun arabicGroup() {
        assertEquals(listOf(them(groupText)), read("wa-ar-group", rtl = true))
    }

    @Test fun googleMessagesOneToOne() {
        val mine = "gkz't yvqu, mb zhf iovfzlpk cp"
        val theirs = "Rj Qbtkn\nYou xkjayb eqqiexm Qwg ' orn tvanzw ypo ycybims zhf ktvswozdk vt jygy a qsdec vnmqivnyj .\n" +
            "Vokyrr contact me mb rlsthtr you nfz oc Asmtau. \nG'qm wvqh lzbe G'qm hqz you zhf slbf jtbksp.\n\n" +
            "Kmft a wldn licm orn ozsh slbf rgbx .\n\nLipgmua"
        val expected = listOf(
            // Above every divider: "Sat" from the description is the last Saturday.
            me(mine, at(10, 24, 10, 34)),
            // Below "Sunday • 8:23 PM"; the sender is the chat's title, so this is a 1:1 and the sender is "".
            them(theirs, at(10, 25, 20, 23)),
        )
        assertEquals(expected, read("gm-en-1to1", app = MESSAGES))
    }

    @Test fun theChatListIsNotAChat() = assertNull(read("wa-en-chatlist"))

    @Test fun searchIsNotAChat() = assertNull(read("wa-en-search"))

    @Test fun oldDividersDateTheMessages() {
        // Both text bubbles sit above "October 18, 2026", so their day is unknown; below it there is only a photo.
        val expected = listOf(
            them("Lvlp! G apde nhajg uh poa yqgb uzyjdc ah. Xm xqq en’qm ybjm pm nhajg lbaua moknb"),
            me("tvanzw"),
        )
        assertEquals(expected, read("wa-en-scrolled-old"))
        val tree = wa(
            waBubble("before any divider", "7:26 PM", 300),
            waDivider("October 18, 2026", 400),
            waBubble("on the 18th", "7:44 PM", 500, mine = true),
            waDivider("Yesterday", 600),
            waBubble("on the 26th", "11:02 PM", 700),
        )
        val dated = listOf(
            them("before any divider"),
            me("on the 18th", at(10, 18, 19, 44)),
            them("on the 26th", at(10, 26, 23, 2)),
        )
        assertEquals(dated, ScreenChat.read(tree, 1080, WHATSAPP, false, now, zone))
    }

    @Test fun aCutOffBubbleIsSkipped() {
        fun all(n: ScreenNode): List<ScreenNode> = listOf(n) + n.children.flatMap(::all)
        val cut = all(UiDump.load("wa-en-1to1").first).first { it.viewId == "com.whatsapp:id/main_layout" }
        assertTrue(cut.bottom - cut.top < 10 && all(cut).none { it.viewId == "com.whatsapp:id/message_text" })
        val messages = read("wa-en-1to1")!!
        assertEquals("Qc ghw, bvus wspxa wohl qadi.", messages.first().text)
        assertTrue(messages.none { it.time == at(10, 27, 19, 35) })
    }

    @Test fun aBubbleCutAboveItsTimeRowIsSkipped() {
        // The list's bottom edge at y = 740 cuts the owner's second bubble below its text, hiding its time and ticks.
        val (root, width) = UiDump.load("wa-en-1to1")
        val owner = oneToOne.filter { it.side == Side.ME }.map { it.text }
        val clipped = ScreenChat.read(Clip(root, 740), width, WHATSAPP, false, now, zone)!!
        assertTrue(clipped.none { it.side == Side.THEM && it.text in owner })
        assertEquals(oneToOne.take(1), clipped)
    }

    @Test fun aDocumentBubbleReadsAsItsFilename() {
        // A real screen (dumped Nov 3, 2026): a sent PDF's bubble has no message_text; its title stands in, so a
        // document doesn't hide whose turn it is. A cut-off first bubble is read.
        val sides = listOf(Side.THEM, Side.THEM, Side.ME, Side.ME, Side.ME, Side.THEM)
        val texts = listOf(
            "Nb Qbtkn. Dpkgsxxb pm bmii this oos ocazyc \uD83D\uDE4F", "Dx 0", "bf Cunblt", "huxon oos xnf",
            "0.Vtjqrqwixnti Snbxpezwmrvuazmrjqnjil - Qbtkn Lkduncwrj-llsm.mej", "Igqka you",
        )
        assertEquals(sides.zip(texts), read("wa-en-1to1-doc")!!.map { it.side to it.text })
    }

    @Test fun aQuotedReplyAndAReactionRowReadAsTheirOwnMessage() {
        // A real screen (dumped Nov 3, 2026): the quoted text stays out of the reply, the reaction row is no bubble.
        val sides = listOf(Side.ME, Side.ME, Side.THEM, Side.THEM, Side.THEM, Side.ME, Side.THEM)
        val texts = listOf(
            "uh i was a jscltkbih i lznwm'zt mniglu zhf Zqq \uD83D\uDC40", "fceubr", "\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02",
            "000% \uD83D\uDE02", "Skqwbb moep", "yvqu owgl \uD83D\uDE0A", "Wfzlammvo \uD83D\uDE4F\uD83C\uDFFB",
        )
        assertEquals(sides.zip(texts), read("wa-en-1to1-replies")!!.map { it.side to it.text })
    }

    @Test fun aLinkBubbleReadsItsPreviewTitle() {
        // A link card has no message_text: the preview's title stands in, and the side still counts (Nov 3 dump).
        val card = node(
            id = "com.whatsapp:id/main_layout", box = intArrayOf(44, 283, 638, 1255),
            kids = listOf(
                node("TikTok · Asmaa", id = "com.whatsapp:id/title", box = intArrayOf(76, 1049, 606, 1100)),
                node("vt.tiktok.com", id = "com.whatsapp:id/url", box = intArrayOf(129, 1132, 606, 1175)),
                node("8:00\u202fAM", id = "com.whatsapp:id/date", box = intArrayOf(498, 1207, 617, 1250)),
            ),
        )
        assertEquals(listOf(them("TikTok · Asmaa")), ScreenChat.read(wa(card), 1080, WHATSAPP, false, now, zone))
    }

    @Test fun aClippedTextBubbleIsStillSkipped() {
        // An empty shell with only a time row is a bubble whose text the list's edge clipped away, not a media bubble.
        val shell = node(
            id = "com.whatsapp:id/main_layout", box = intArrayOf(44, 283, 638, 1255),
            kids = listOf(node("8:00\u202fAM", id = "com.whatsapp:id/date", box = intArrayOf(498, 1207, 617, 1250))),
        )
        assertNull(ScreenChat.read(wa(shell), 1080, WHATSAPP, false, now, zone))
    }

    private class Clip(val n: ScreenNode, val edge: Int) : ScreenNode by n {
        override val visible get() = n.visible && n.top < edge
        override val children get() = n.children.map { Clip(it, edge) }
    }

    @Test fun theLayoutDirectionComesFromTheScreenNotTheKeyboard() {
        // The Arabic keyboard over English WhatsApp, and the English keyboard over Arabic WhatsApp.
        assertEquals(oneToOne, read("wa-en-1to1", rtl = true))
        assertEquals(oneToOne, read("wa-ar-1to1", rtl = false))
    }

    @Test fun withoutAContactNameTheKeyboardsDirectionDecides() {
        val rows = node(id = "com.whatsapp:id/list", kids = listOf(waBubble("mine on the right", "9:03 AM", 600, mine = true)))
        val tree = node(kids = listOf(rows))
        // No divider, so the time is guessed.
        assertEquals(listOf(me("mine on the right")), ScreenChat.read(tree, 1080, WHATSAPP, false, now, zone))
        assertNull(ScreenChat.read(tree, 1080, WHATSAPP, true, now, zone))
    }

    @Test fun statusBubblesOnBothSidesContradict() {
        val leading = waBubble("mine on the left?", "9:02 AM", 500, mine = true).let { b ->
            node(id = "com.whatsapp:id/main_layout", box = intArrayOf(44, 500, 540, 590), kids = b.children)
        }
        val tree = wa(waDivider("Today", 300), leading, waBubble("mine on the right", "9:03 AM", 600, mine = true))
        assertNull(ScreenChat.read(tree, 1080, WHATSAPP, false, now, zone))
        assertNull(ScreenChat.read(tree, 1080, WHATSAPP, true, now, zone))
    }

    @Test fun otherAppsAreNotRead() {
        // A plausible two-sided chat: toolbar, a divider, one bubble each side with its time, and a draft field.
        val tree = node(
            kids = listOf(
                node(null, desc = "Back", box = intArrayOf(0, 146, 126, 272)),
                node("Sam", box = intArrayOf(200, 180, 500, 240)),
                node("Today", box = intArrayOf(480, 340, 600, 380)),
                node("are you coming tonight?", box = intArrayOf(44, 400, 600, 460)),
                node("6:05\u202fPM", box = intArrayOf(400, 460, 500, 490)),
                node("see you at the station", box = intArrayOf(500, 520, 1036, 580)),
                node("6:07\u202fPM", box = intArrayOf(900, 580, 1000, 610)),
                node("draft", editable = true, box = intArrayOf(44, 1300, 900, 1380)),
            ),
        )
        assertNull(ScreenChat.read(tree, 1080, "org.telegram.messenger", false, now, zone))
        assertNull(ScreenChat.read(tree, 1080, "com.Slack", false, now, zone))
    }

    private class Fake(
        override val text: String?,
        override val description: String?,
        override val viewId: String?,
        override val editable: Boolean,
        box: IntArray,
        override val children: List<ScreenNode>,
    ) : ScreenNode {
        override val visible = true
        override val left = box[0]
        override val top = box[1]
        override val right = box[2]
        override val bottom = box[3]
    }

    private fun node(
        text: String? = null,
        id: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        box: IntArray = intArrayOf(0, 0, 1080, 2400),
        kids: List<ScreenNode> = emptyList(),
    ): ScreenNode = Fake(text, desc, id, editable, box, kids)

    private fun wa(vararg rows: ScreenNode) = node(
        kids = listOf(
            node("Group", id = "com.whatsapp:id/conversation_contact_name", box = intArrayOf(263, 176, 600, 237)),
            node(id = "com.whatsapp:id/list", kids = rows.toList()),
        ),
    )

    private fun waDivider(text: String, top: Int) =
        node(text, id = "com.whatsapp:id/conversation_row_date_divider", box = intArrayOf(400, top, 680, top + 80))

    private fun waBubble(text: String, time: String, top: Int, name: String? = null, mine: Boolean = false): ScreenNode {
        val box = if (mine) intArrayOf(540, top, 1036, top + 90) else intArrayOf(119, top, 700, top + 90)
        val row = intArrayOf(box[0], top + 40, box[2], top + 85) // the time row ends above the bubble's bottom edge
        val kids = listOfNotNull(
            name?.let { node(it, id = "com.whatsapp:id/name_in_group_tv", box = box) },
            node(text, id = "com.whatsapp:id/message_text", box = box),
            node(time.replace(" ", "\u202f"), id = "com.whatsapp:id/date", box = row),
            if (mine) node(id = "com.whatsapp:id/status", box = box) else null,
        )
        return node(id = "com.whatsapp:id/main_layout", box = box, kids = kids)
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val MESSAGES = "com.google.android.apps.messaging"
    }
}
