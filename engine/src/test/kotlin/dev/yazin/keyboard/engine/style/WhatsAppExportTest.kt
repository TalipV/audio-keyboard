package dev.yazin.keyboard.engine.style

import dev.yazin.keyboard.engine.chat.ChatDates
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppExportTest {
    private fun parse(s: String) = WhatsAppExport.parse(s.lineSequence()).map { it.copy(time = null) }
    private val zone = ZoneOffset.ofHours(3)

    @Test fun linesCarryTheirTimeWithTheOrderTakenFromTheWholeFile() = assertEquals(
        listOf(ChatLine("A", "x", 1775199600000), ChatLine("B", "y", 1776240000000)), // 3 Apr 10:00, 15 Apr 11:00
        WhatsAppExport.parse(
            "03/04/2026, 10:00 - A: x\n15/04/2026, 11:00 - B: y".lineSequence(),
            fallback = ChatDates.Order.MDY,
            zone = zone,
        ),
    )

    @Test fun iosStampsGetTimesToo() = assertEquals(
        listOf(ChatLine("A", "x", 1792067832000)), // 15 Oct 15:37:12
        WhatsAppExport.parse("[15/10/2026, 3:37:12 PM] A: x".lineSequence(), zone = zone),
    )

    @Test fun aStampThatWontParseLeavesTheTimeNull() = assertEquals(
        listOf(ChatLine("A", "x", null), ChatLine("B", "y", 1792044000000)), // no 31 Feb; 15 Oct 09:00
        WhatsAppExport.parse("31/02/2026, 10:00 - A: x\n15/10/2026, 09:00 - B: y".lineSequence(), zone = zone),
    )

    @Test fun android24Hour() = assertEquals(
        listOf(ChatLine("Nora", "مبروك يزن!!"), ChatLine("Yazin", "thank u!! 🥹")),
        parse("15/10/2026, 15:37 - Nora: مبروك يزن!!\n15/10/2026, 15:40 - Yazin: thank u!! 🥹"),
    )

    @Test fun android12HourWithNarrowSpacesAndMarks() = assertEquals(
        listOf(ChatLine("Yazin", "who doesn't love free stuff")),
        parse("‎10/15/26, 3:03 PM - Yazin: who doesn't love free stuff"),
    )

    @Test fun iosBracketsWithSeconds() = assertEquals(
        listOf(ChatLine("Ali Talib", "Do you really need 30 minutes?!")),
        parse("[15/10/2026, 2:35:12 PM] Ali Talib: Do you really need 30 minutes?!"),
    )

    @Test fun continuationLinesJoinTheirMessage() = assertEquals(
        listOf(ChatLine("Nora", "Always doing great things Mashallah. It's been too long\nلازم نجتمع ان شاء الله")),
        parse("15/10/2026, 15:37 - Nora: Always doing great things Mashallah. It's been too long\nلازم نجتمع ان شاء الله"),
    )

    @Test fun systemMediaDeletedAndEditedMarkersAreHandled() = assertEquals(
        listOf(ChatLine("Yazin", "fixed typo"), ChatLine("Yazin", "time: 8pm works")),
        parse(
            """
            15/10/2026, 09:00 - Messages and calls are end-to-end encrypted. Only people in this chat can read them.
            15/10/2026, 09:01 - Yazin added Nora
            15/10/2026, 09:02 - Nora: <Media omitted>
            15/10/2026, 09:03 - Yazin: This message was deleted
            15/10/2026, 09:04 - Yazin: fixed typo <This message was edited>
            15/10/2026, 09:05 - Yazin: time: 8pm works
            [15/10/2026, 09:06:00] Nora: image omitted
            """.trimIndent(),
        ),
    )

    @Test fun titleComesFromTheFileName() {
        assertEquals("Nora", WhatsAppExport.title("WhatsApp Chat with Nora.txt"))
        assertEquals("Family Reunion 2025", WhatsAppExport.title("WhatsApp Chat with Family Reunion 2025 (1).zip"))
        assertEquals("Dr. Ali", WhatsAppExport.title("WhatsApp Chat - Dr. Ali.zip"))
        assertEquals("", WhatsAppExport.title("_chat.txt"))
    }

    @Test fun sendersMostMessagesFirst() = assertEquals(
        listOf("Yazin", "Nora"),
        WhatsAppExport.senders(listOf(ChatLine("Nora", "a"), ChatLine("Yazin", "b"), ChatLine("Yazin", "c"))),
    )

    @Test fun theRememberedSenderComesFirst() = assertEquals(
        listOf("Nora", "Yazin"),
        WhatsAppExport.senders(listOf(ChatLine("Nora", "a"), ChatLine("Yazin", "b"), ChatLine("Yazin", "c")), first = "Nora"),
    )
}
