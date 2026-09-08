package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.calling.ReadBack
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AppointmentTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Clock.zone).toInstant().toEpochMilli()

    private fun busy(fromHour: Int, toHour: Int, title: String, eventId: Long? = null) = BusyInterval(
        startMillis = millis(2026, 9, 10, fromHour),
        endMillis = millis(2026, 9, 10, toHour),
        title = title,
        eventId = eventId,
    )

    // --- snapToQuarter ------------------------------------------------------

    @Test
    fun `a proposed time is snapped up to the next quarter`() {
        val snapped = Appointment.snapToQuarter("2026-09-10T16:32:00+02:00")

        assertEquals(millis(2026, 9, 10, 16, 45), Clock.millis(snapped))
    }

    @Test
    fun `a time already on a quarter is left alone`() {
        val iso = "2026-09-10T16:45:00+02:00"

        assertEquals(iso, Appointment.snapToQuarter(iso))
    }

    // --- endOf / minutesBetween --------------------------------------------

    @Test
    fun `endOf adds the duration`() {
        val end = Appointment.endOf("2026-09-10T14:00:00+02:00", 90)

        assertEquals(millis(2026, 9, 10, 15, 30), Clock.millis(end))
    }

    @Test
    fun `endOf does not correct onto a workday`() {
        // Samstag, 18:30 — als Termin ausdrücklich erlaubt. Anders als bei der
        // Wiedervorlage entscheidet hier der Kunde, nicht die App.
        val end = Appointment.endOf("2026-09-12T18:30:00+02:00", 60)

        assertEquals(millis(2026, 9, 12, 19, 30), Clock.millis(end))
    }

    @Test
    fun `minutesBetween reads the duration back`() {
        val minutes = Appointment.minutesBetween(
            "2026-09-10T14:00:00+02:00",
            "2026-09-10T15:30:00+02:00",
        )

        assertEquals(90, minutes)
    }

    @Test
    fun `minutesBetween falls back to the default without an end`() {
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween("2026-09-10T14:00:00+02:00", null))
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween(null, null))
    }

    // --- overlapping --------------------------------------------------------

    @Test
    fun `an overlap is found`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"), busy(16, 17, "Steuerbüro"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 9, 30),
            millis(2026, 9, 10, 10, 30),
            busyTimes,
        )

        assertEquals(listOf("Baustelle Nord"), hits.map { it.title })
    }

    @Test
    fun `back-to-back appointments do not overlap`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 10),
            millis(2026, 9, 10, 11),
            busyTimes,
        )

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `an enclosed appointment counts as an overlap`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 8),
            millis(2026, 9, 10, 12),
            busyTimes,
        )

        assertEquals(1, hits.size)
    }

    // --- address ------------------------------------------------------------

    @Test
    fun `the address is joined onto one line`() {
        assertEquals(
            "Zehentstraße 39, 85055 Ingolstadt",
            Appointment.address("Zehentstraße 39", "85055", "Ingolstadt"),
        )
    }

    @Test
    fun `missing parts of the address drop out`() {
        assertEquals("Ingolstadt", Appointment.address(null, null, "Ingolstadt"))
        assertEquals("Zehentstraße 39", Appointment.address("Zehentstraße 39", null, null))
        assertNull(Appointment.address(null, null, null))
        assertNull(Appointment.address(" ", "", null))
    }

    // --- readableRange ------------------------------------------------------

    @Test
    fun `the range reads in German`() {
        // "Do." with the full stop — that is the German short weekday the JVM's
        // locale data produces, and it is correct German. Asserted exactly, so a
        // locale-data change that turns it back into "Do" is caught here rather
        // than noticed on a phone.
        assertEquals(
            "Do., 10.09. · 14:00 – 15:00",
            Appointment.readableRange("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00"),
        )
    }

    @Test
    fun `without an appointment there is a dash`() {
        assertEquals("—", Appointment.readableRange(null, null))
    }

    // --- plan ---------------------------------------------------------------

    private val slotStart = millis(2026, 9, 10, 14)
    private val slotEnd = millis(2026, 9, 10, 15)

    @Test
    fun `a free slot with the calendar on creates an event`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Create, plan)
    }

    @Test
    fun `a free slot with the calendar off writes only the columns`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = false,
        )

        assertEquals(SavePlan.LocalOnly, plan)
    }

    @Test
    fun `an existing own event is updated, not duplicated`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = 42L, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Update(42L), plan)
    }

    @Test
    fun `a taken slot asks before writing anything`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertTrue(plan is SavePlan.Conflict)
        assertEquals(listOf("Steuerbüro"), (plan as SavePlan.Conflict).with.map { it.title })
    }

    @Test
    fun `force writes into a taken slot anyway`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = null, force = true, calendarEnabled = true,
        )

        assertEquals(SavePlan.Create, plan)
    }

    @Test
    fun `linking beats the conflict and never creates`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = 7L, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Adopt(7L), plan)
    }

    // --- readBack -----------------------------------------------------------

    private val at = "2026-09-10T14:00:00+02:00"
    private val until = "2026-09-10T15:00:00+02:00"

    @Test
    fun `a difference of seconds still counts as unchanged`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = null,
            eventStartMillis = millis(2026, 9, 10, 14) + 30_000L,
            eventEndMillis = millis(2026, 9, 10, 15) + 30_000L,
            eventLocation = null,
        )

        assertEquals(ReadBack.Unchanged, outcome)
    }

    @Test
    fun `an untouched appointment yields Unchanged`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Zehentstraße 39",
        )

        assertEquals(ReadBack.Unchanged, outcome)
    }

    @Test
    fun `a moved appointment yields Updated`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 16),
            eventEndMillis = millis(2026, 9, 10, 17),
            eventLocation = "Zehentstraße 39",
        ) as ReadBack.Updated

        assertEquals(millis(2026, 9, 10, 16), Clock.millis(outcome.startIso))
        assertEquals("Zehentstraße 39", outcome.location)
    }

    @Test
    fun `a relocated appointment yields Updated`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Im Büro",
        ) as ReadBack.Updated

        assertEquals("Im Büro", outcome.location)
    }

    @Test
    fun `a deleted appointment yields Gone`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = null,
            eventStartMillis = null, eventEndMillis = null, eventLocation = null,
        )

        assertEquals(ReadBack.Gone, outcome)
    }
}
