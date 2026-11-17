package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.chat.ChatDates.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ChatDatesTest {
    @Test fun anyFieldAbove12DecidesTheOrder() {
        assertEquals(Order.DMY, ChatDates.order(listOf("03/04/2026", "15/04/2026"), Order.MDY))
        assertEquals(Order.MDY, ChatDates.order(listOf("04/03/2026", "04/15/2026"), Order.DMY))
    }

    // Both readings run forward (Feb 1 → 2 → 3, or Jan 2 → Feb 2 → Mar 2), but only MDY moves a month while keeping the day.
    @Test fun aMonthStepThatKeepsTheDayLosesWhenNoFieldIsAbove12() =
        assertEquals(Order.DMY, ChatDates.order(listOf("01/02/2026", "02/02/2026", "03/02/2026"), Order.MDY))

    @Test fun stepsBackwardsLose() {
        assertEquals(Order.DMY, ChatDates.order(listOf("05/01/2026", "03/02/2026"), Order.MDY)) // MDY: May 1 → Mar 2
        assertEquals(Order.MDY, ChatDates.order(listOf("01/05/2026", "02/03/2026"), Order.DMY)) // DMY: May 1 → Mar 2
    }

    @Test fun undecidableFallsBack() = assertEquals(Order.MDY, ChatDates.order(listOf("01/02/2026"), Order.MDY))

    @Test fun bothReadingsRunningForwardFallBack() { // Jan 1 → Mar 2, or Jan 1 → Feb 3
        assertEquals(Order.DMY, ChatDates.order(listOf("01/01/2026", "02/03/2026"), Order.DMY))
        assertEquals(Order.MDY, ChatDates.order(listOf("01/01/2026", "02/03/2026"), Order.MDY))
    }

    @Test fun fourDigitFirstFieldIsYearFirst() {
        assertEquals(Order.YMD, ChatDates.order(listOf("2026-10-15", "2026-10-16"), Order.DMY))
        assertEquals(1772604300000L, ChatDates.exportTime("2026-03-04, 09:05", Order.YMD, ZoneId.of("Asia/Riyadh")))
    }

    @Test fun exportTimesReadBothClocksAndShortYears() {
        val z = ZoneId.of("Asia/Riyadh")
        assertEquals(1792067820000L, ChatDates.exportTime("15/10/2026, 15:37", Order.DMY, z))
        assertEquals(ChatDates.exportTime("15/10/2026, 15:37", Order.DMY, z), ChatDates.exportTime("[15/10/26, 3:37:00 PM]", Order.DMY, z))
        assertEquals(1792067820000L, ChatDates.exportTime("\u200e10/15/26, 3:37\u202fPM - Sam: hi", Order.MDY, z))
        assertNull(ChatDates.exportTime("31/02/2026, 15:37", Order.DMY, z))
    }

    @Test fun dividers() {
        val today = LocalDate.of(2026, 10, 27) // a Tuesday
        assertEquals(today, ChatDates.divider("Today", today))
        assertEquals(today.minusDays(1), ChatDates.divider("أمس", today))
        assertEquals(LocalDate.of(2026, 10, 25), ChatDates.divider("Sunday", today)) // the last one before today
        assertEquals(LocalDate.of(2026, 10, 12), ChatDates.divider("12 Oct", today)) // this year, or last year if that would be in the future
        assertEquals(LocalDate.of(2025, 12, 30), ChatDates.divider("30 December 2025", today))
        assertEquals(LocalDate.of(2026, 10, 12), ChatDates.divider("١٢ أكتوبر", today))
        assertNull(ChatDates.divider("Nora", today))

        // Seen on the phone (spec §2 findings).
        assertEquals(today.minusDays(1), ChatDates.divider("Yesterday", today))
        assertEquals(LocalDate.of(2026, 10, 18), ChatDates.divider("October 18, 2026", today))
        assertEquals(today, ChatDates.divider("اليوم", today))
        assertEquals(today, ChatDates.divider("\u200fاليوم", today))
        assertEquals(LocalDate.of(2026, 10, 25), ChatDates.divider("Sunday • 8:23\u202fPM", today))
        assertEquals(LocalDate.of(2026, 10, 24), ChatDates.divider("Saturday • 10:21\u202fAM", today))

        assertEquals(LocalDate.of(2025, 11, 3), ChatDates.divider("3 Nov", today))
        assertEquals(today.minusDays(7), ChatDates.divider("Tuesday", today))
        assertEquals(LocalDate.of(2026, 10, 26), ChatDates.divider("الاثنين", today))
        assertNull(ChatDates.divider("May I come 12", today))
    }

    @Test fun bubbleTimes() {
        assertEquals(LocalTime.of(13, 42), ChatDates.bubbleTime("13:42"))
        assertEquals(LocalTime.of(13, 42), ChatDates.bubbleTime("1:42 PM"))
        assertEquals(LocalTime.of(13, 42), ChatDates.bubbleTime("١:٤٢ م"))
        assertEquals(LocalTime.of(1, 42), ChatDates.bubbleTime("1:42 ص"))
        assertNull(ChatDates.bubbleTime("see you at 1:42 then")) // only a whole node that is a time

        // Seen on the phone (spec §2 findings).
        assertEquals(LocalTime.of(15, 38), ChatDates.bubbleTime("3:38\u202fPM"))
        assertEquals(LocalTime.of(15, 38), ChatDates.bubbleTime("٣:٣٨ م"))

        assertEquals(LocalTime.of(0, 5), ChatDates.bubbleTime("12:05 AM"))
        assertEquals(LocalTime.of(12, 5), ChatDates.bubbleTime("12:05 pm"))
        assertNull(ChatDates.bubbleTime("13:42 PM"))
    }
}
