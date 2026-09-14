package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.calling.ReadBack
import io.github.amadeusb.callsheet.calling.Reconcile
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.calling.Slot
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- ahead, and the status -----------------------------------------------

    private fun instant(iso: String): Long = Clock.millis(iso)!!

    private val noon = instant("2026-09-10T12:00:00+02:00")

    private fun entry(id: String, startsAt: String, endsAt: String? = null) = AppointmentEntry(
        id = id, placeId = "P1", startsAt = startsAt, endsAt = endsAt,
        location = null, note = null, contactId = null,
    )

    @Test
    fun `an appointment is ahead until its end has passed`() {
        assertTrue(Appointment.isAhead("2026-09-10T11:00:00+02:00", "2026-09-10T13:00:00+02:00", noon))
        assertFalse(Appointment.isAhead("2026-09-10T10:00:00+02:00", "2026-09-10T11:00:00+02:00", noon))
    }

    @Test
    fun `without an end, the start decides`() {
        assertTrue(Appointment.isAhead("2026-09-10T13:00:00+02:00", null, noon))
        assertFalse(Appointment.isAhead("2026-09-10T11:00:00+02:00", null, noon))
    }

    @Test
    fun `saving an appointment still ahead sets the status`() {
        assertEquals(Status.APPOINTMENT, Appointment.statusAfterSave("2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00", noon))
    }

    @Test
    fun `entering a past appointment after the fact leaves the status alone`() {
        assertNull(Appointment.statusAfterSave("2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00", noon))
    }

    @Test
    fun `removing the last appointment ahead puts the status back to called`() {
        val pastOnly = listOf(entry("A-1", "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00"))

        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, pastOnly, noon))
        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, emptyList(), noon))
    }

    @Test
    fun `removing one while another is still ahead keeps the status`() {
        val another = listOf(entry("A-2", "2026-09-12T09:00:00+02:00", "2026-09-12T10:00:00+02:00"))

        assertNull(Appointment.statusAfterRemoval(Status.APPOINTMENT, another, noon))
    }

    @Test
    fun `removing never touches a status other than appointment`() {
        assertNull(Appointment.statusAfterRemoval(Status.DECLINED, emptyList(), noon))
        assertNull(Appointment.statusAfterRemoval(Status.DO_NOT_CALL, emptyList(), noon))
    }

    // --- reconcile: the read-back table ---------------------------------------

    private fun slot(start: String, end: String? = null, location: String? = null) =
        Slot(instant(start), end?.let { instant(it) }, location)

    private val planned = slot("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Zehentstraße 39")
    private val later = slot("2026-09-10T16:00:00+02:00", "2026-09-10T17:00:00+02:00", "Zehentstraße 39")
    private val office = slot("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Im Büro")
    private val dayBefore = instant("2026-09-09T12:00:00+02:00")
    private val dayAfter = instant("2026-09-11T12:00:00+02:00")

    @Test
    fun `first sight of an event that matches the row changes nothing`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `first sight of an event that differs lets the row win`() {
        // This device's calendar may simply not have caught up.
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `nothing moved, nothing to do`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = planned, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `moved in the calendar, the event wins`() {
        assertEquals(Reconcile.TakeEvent(later), Appointment.reconcile(row = planned, seen = planned, event = later, nowMillis = dayBefore))
    }

    @Test
    fun `relocated in the calendar, the event wins`() {
        assertEquals(Reconcile.TakeEvent(office), Appointment.reconcile(row = planned, seen = planned, event = office, nowMillis = dayBefore))
    }

    @Test
    fun `changed on another device, the row wins`() {
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = planned, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `changed on both sides to the same, nothing to do`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = later, seen = planned, event = later, nowMillis = dayBefore))
    }

    @Test
    fun `changed on both sides differently, the row wins`() {
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = planned, event = office, nowMillis = dayBefore))
    }

    @Test
    fun `seconds and surrounding spaces are no difference`() {
        val event = Slot(planned.startMillis + 30_000L, planned.endMillis!! + 30_000L, " Zehentstraße 39 ")

        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = planned, event = event, nowMillis = dayBefore))
    }

    @Test
    fun `a row without an end is not a difference from the event's end`() {
        val row = slot("2026-09-10T14:00:00+02:00", null, "Zehentstraße 39")

        assertEquals(Reconcile.InStep, Appointment.reconcile(row = row, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `an event never seen on this device and not found means nothing yet`() {
        assertEquals(Reconcile.NotYetHere, Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayBefore))
        assertEquals(Reconcile.NotYetHere, Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayAfter))
    }

    @Test
    fun `an event seen before and gone while the appointment is ahead was deleted`() {
        assertEquals(Reconcile.DeletedInCalendar, Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore))
    }

    @Test
    fun `an event seen before and gone after the appointment only loses the link`() {
        // Calendars clear out old events on their own; the record stays.
        assertEquals(Reconcile.Unlink, Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayAfter))
    }

    @Test
    fun `the row slot and the seen slot are read from the entry`() {
        val linked = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00")
            .copy(location = "Zehentstraße 39", seenStartsAt = "2026-09-10T16:00:00+02:00", seenEndsAt = "2026-09-10T17:00:00+02:00", seenLocation = "Zehentstraße 39")

        assertEquals(planned, Appointment.rowSlot(linked))
        assertEquals(later, Appointment.seenSlot(linked))
        assertNull(Appointment.seenSlot(linked.copy(seenStartsAt = null)))
    }

    @Test
    fun `a record of the event is current only with the same link and a matching slot`() {
        // In step, the read-back writes nothing unless this is false — a link
        // rewritten on every opening would notify every observer for nothing.
        val recorded = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00").copy(
            calendarEventId = 4711L,
            seenStartsAt = "2026-09-10T14:00:00+02:00",
            seenEndsAt = "2026-09-10T15:00:00+02:00",
            seenLocation = "Zehentstraße 39",
        )

        assertTrue(Appointment.seenIsCurrent(recorded, 4711L, planned))
        assertFalse(Appointment.seenIsCurrent(recorded, 815L, planned))
        assertFalse(Appointment.seenIsCurrent(recorded, 4711L, later))
        assertFalse(Appointment.seenIsCurrent(recorded.copy(seenStartsAt = null), 4711L, planned))
    }
}
