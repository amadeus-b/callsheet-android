package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Fixed starting points, so the tests do not depend on the current time.
 * The zone comes from Clock.zone, so the tests behave the same on any machine.
 */
class FollowUpTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Clock.zone).toInstant().toEpochMilli()

    private fun read(iso: String): ZonedDateTime = Clock.zdt(Clock.millis(iso)!!)

    private fun isWorkdayDuringBusinessHours(z: ZonedDateTime) {
        assertNotEquals(DayOfWeek.SATURDAY, z.dayOfWeek)
        assertNotEquals(DayOfWeek.SUNDAY, z.dayOfWeek)
        assertTrue("Uhrzeit ${z.toLocalTime()} liegt außerhalb 8-18", z.hour in 8..17)
    }

    // --- inTwoDays ----------------------------------------------------------

    @Test
    fun `two days on keeps the time when not switching`() {
        // Monday, 7 Sep 2026, 10:00 -> Wednesday, 9 Sep 2026, 10:00
        val z = read(FollowUp.inTwoDays(millis(2026, 9, 7, 10)))
        assertEquals(9, z.dayOfMonth)
        assertEquals(10, z.hour)
        assertEquals(0, z.minute)
    }

    @Test
    fun `a different time of day really differs from the starting section`() {
        val ausgang = millis(2026, 9, 7, 10)
        val z = read(FollowUp.inTwoDays(ausgang, differentTimeOfDay = true))
        val before = FollowUp.section(Clock.zdt(ausgang).toLocalTime())
        val after = FollowUp.section(z.toLocalTime())
        assertNotEquals(before, after)
        assertEquals(9, z.dayOfMonth)
        isWorkdayDuringBusinessHours(z)
    }

    @Test
    fun `the switch is deterministic and different for every section`() {
        for (hour in 8..17) {
            val ausgang = millis(2026, 9, 7, hour)
            val a = FollowUp.inTwoDays(ausgang, differentTimeOfDay = true)
            val b = FollowUp.inTwoDays(ausgang, differentTimeOfDay = true)
            assertEquals("nicht deterministisch bei $hour Uhr", a, b)
            val before = FollowUp.section(Clock.zdt(ausgang).toLocalTime())
            val after = FollowUp.section(read(a).toLocalTime())
            assertNotEquals("gleicher Abschnitt bei $hour Uhr", before, after)
        }
    }

    @Test
    fun `two days on never lands on a weekend`() {
        // Thursday, 10 Sep 2026 -> Saturday, 12 Sep -> Monday, 14 Sep
        val z = read(FollowUp.inTwoDays(millis(2026, 9, 10, 10)))
        assertEquals(14, z.dayOfMonth)
        assertEquals(DayOfWeek.MONDAY, z.dayOfWeek)
        isWorkdayDuringBusinessHours(z)
    }

    @Test
    fun `a late evening moves to the next workday morning`() {
        // Wednesday, 9 Sep 2026, 21:00 -> +2 days = Friday 21:00 -> Saturday 9:00 -> Monday 9:00
        val z = read(FollowUp.inTwoDays(millis(2026, 9, 9, 21)))
        assertEquals(DayOfWeek.MONDAY, z.dayOfWeek)
        assertEquals(9, z.hour)
        isWorkdayDuringBusinessHours(z)
    }

    @Test
    fun `an early morning is moved to nine o'clock`() {
        // Monday, 7 Sep 2026, 06:00 -> Wednesday 09:00
        val z = read(FollowUp.inTwoDays(millis(2026, 9, 7, 6)))
        assertEquals(9, z.dayOfMonth)
        assertEquals(9, z.hour)
    }

    // --- nextWeek -----------------------------------------------------------

    @Test
    fun `next week is the same weekday at the same time`() {
        val z = read(FollowUp.nextWeek(millis(2026, 9, 7, 14, 30)))
        assertEquals(14, z.dayOfMonth)
        assertEquals(DayOfWeek.MONDAY, z.dayOfWeek)
        assertEquals(14, z.hour)
        assertEquals(30, z.minute)
    }

    @Test
    fun `next week from a weekend lands on a workday`() {
        // Saturday, 12 Sep 2026 -> Saturday, 19 Sep -> Monday, 21 Sep
        val z = read(FollowUp.nextWeek(millis(2026, 9, 12, 11)))
        assertEquals(DayOfWeek.MONDAY, z.dayOfWeek)
        assertEquals(21, z.dayOfMonth)
        isWorkdayDuringBusinessHours(z)
    }

    // --- nextMonth ----------------------------------------------------------

    @Test
    fun `next month handles the end of the month in a leap year`() {
        // Wednesday, 31 Jan 2024 -> Thursday, 29 Feb 2024
        val z = read(FollowUp.nextMonth(millis(2024, 1, 31, 10)))
        assertEquals(2024, z.year)
        assertEquals(2, z.monthValue)
        assertEquals(29, z.dayOfMonth)
        assertEquals(10, z.hour)
    }

    @Test
    fun `next month pushes from a month end across the weekend`() {
        // Saturday, 31 Jan 2026 -> Saturday, 28 Feb 2026 -> Monday, 2 Mar 2026
        val z = read(FollowUp.nextMonth(millis(2026, 1, 31, 10)))
        assertEquals(3, z.monthValue)
        assertEquals(2, z.dayOfMonth)
        assertEquals(DayOfWeek.MONDAY, z.dayOfWeek)
        isWorkdayDuringBusinessHours(z)
    }

    @Test
    fun `next month in the ordinary case`() {
        val z = read(FollowUp.nextMonth(millis(2026, 9, 7, 9)))
        assertEquals(10, z.monthValue)
        assertEquals(7, z.dayOfMonth)
        assertEquals(9, z.hour)
    }

    // --- fromDateAndTime ----------------------------------------------------

    @Test
    fun `a free choice is taken over unchanged`() {
        // Saturday, 23:00 — deliberately left uncorrected
        val z = read(FollowUp.fromDateAndTime(2026, 9, 12, 23, 15))
        assertEquals(12, z.dayOfMonth)
        assertEquals(DayOfWeek.SATURDAY, z.dayOfWeek)
        assertEquals(23, z.hour)
        assertEquals(15, z.minute)
    }

    // --- Formatting ---------------------------------------------------------

    @Test
    fun `every return value carries date, time and zone`() {
        val ausgang = millis(2026, 9, 7, 10)
        listOf(
            FollowUp.inTwoDays(ausgang),
            FollowUp.inTwoDays(ausgang, differentTimeOfDay = true),
            FollowUp.nextWeek(ausgang),
            FollowUp.nextMonth(ausgang),
            FollowUp.fromDateAndTime(2026, 9, 7, 10, 0),
        ).forEach { iso ->
            assertTrue("keine Uhrzeit in $iso", iso.contains("T"))
            assertTrue("nicht lesbar: $iso", Clock.millis(iso) != null)
        }
    }

    // --- isOverdue ----------------------------------------------------------

    @Test
    fun `overdue is whatever lies before now`() {
        val jetzt = millis(2026, 9, 7, 12)
        assertTrue(FollowUp.isOverdue(Clock.format(jetzt - 60_000), jetzt))
        assertFalse(FollowUp.isOverdue(Clock.format(jetzt + 60_000), jetzt))
        assertFalse(FollowUp.isOverdue(Clock.format(jetzt), jetzt))
    }

    @Test
    fun `an unreadable or missing follow-up is not overdue`() {
        val jetzt = millis(2026, 9, 7, 12)
        assertFalse(FollowUp.isOverdue(null, jetzt))
        assertFalse(FollowUp.isOverdue("", jetzt))
        assertFalse(FollowUp.isOverdue("irgendwann", jetzt))
    }
}
