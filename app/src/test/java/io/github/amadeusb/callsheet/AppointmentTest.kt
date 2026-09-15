package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.calling.Located
import io.github.amadeusb.callsheet.calling.Reconcile
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.calling.Slot
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.PhoneNumber
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.TakenEvents
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AppointmentTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Clock.zone).toInstant().toEpochMilli()

    private fun busy(
        fromHour: Int,
        toHour: Int,
        title: String,
        eventId: Long? = null,
        uid: String? = null,
        recurring: Boolean = false,
    ) = BusyInterval(
        startMillis = millis(2026, 9, 10, fromHour),
        endMillis = millis(2026, 9, 10, toHour),
        title = title,
        eventId = eventId,
        uid = uid,
        recurring = recurring,
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

    @Test
    fun `a callback falls back to its own length`() {
        assertEquals(15, Appointment.minutesBetween("2026-09-10T14:00:00+02:00", null, Appointment.defaultMinutes(AppointmentKind.CALLBACK)))
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.defaultMinutes(AppointmentKind.VISIT))
    }

    @Test
    fun `a callback is offered short durations`() {
        assertEquals(listOf(15, 30), Appointment.durations(AppointmentKind.CALLBACK))
        assertEquals(Appointment.DURATIONS, Appointment.durations(AppointmentKind.VISIT))
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

    // --- place of a visit ---------------------------------------------------

    private val head = BusinessAddress("main-P1", "P1", null, "Musterweg 1", "85000", "Musterstadt", position = 0)
    private val branch = BusinessAddress("A2", "P1", "Filiale", "Hafenstraße 5", "85001", "Hafenstadt", position = 1)

    private fun person(id: String, addressId: String?) = Contact(
        id = id, placeId = "P1", name = "Erika Beispiel", role = null, email = null, note = null,
        numbers = emptyList(), updatedAt = "2026-09-15T10:00:00+02:00", addressId = addressId,
    )

    @Test
    fun `a visit starts at the chosen person's assigned address`() {
        assertEquals(
            "Hafenstraße 5, 85001 Hafenstadt",
            Appointment.presetLocation("k1", listOf(person("k1", "A2")), listOf(head, branch)),
        )
    }

    @Test
    fun `without a person, an assignment or its row, a visit starts at the main address`() {
        val addresses = listOf(branch, head)
        val main = "Musterweg 1, 85000 Musterstadt"

        assertEquals(main, Appointment.presetLocation(null, emptyList(), addresses))
        assertEquals(main, Appointment.presetLocation("k1", listOf(person("k1", null)), addresses))
        assertEquals(main, Appointment.presetLocation("k1", listOf(person("k1", "gone")), addresses))
    }

    @Test
    fun `without any address the place stays empty`() {
        assertEquals("", Appointment.presetLocation("k1", listOf(person("k1", "A2")), emptyList()))
    }

    // --- the place after the contact person changed ---------------------------

    private val headLine = "Musterweg 1, 85000 Musterstadt"
    private val branchLine = "Hafenstraße 5, 85001 Hafenstadt"

    /** k1 sits at the main address, k2 at the branch; nobody means the main address. */
    private val presets: (String?) -> String? = { contactId -> if (contactId == "k2") branchLine else headLine }

    private fun sheet(contactId: String?, location: String, edited: Boolean = false, kind: AppointmentKind = AppointmentKind.VISIT) =
        AppointmentDraft(
            placeId = "P1", startIso = "2026-09-17T10:00:00+02:00", minutes = 60,
            location = location, kind = kind, contactId = contactId, locationEdited = edited,
        )

    @Test
    fun `a place not chosen by hand moves along to the new person's address`() {
        assertEquals(branchLine, Appointment.placeAfterContactChange(sheet("k1", headLine), sheet("k2", headLine), presets))
        assertEquals(headLine, Appointment.placeAfterContactChange(sheet("k2", branchLine), sheet(null, branchLine), presets))
    }

    @Test
    fun `a place chosen by hand stays when the person changes`() {
        // Typed: differs from the previous person's address.
        assertEquals("Baustelle Nord", Appointment.placeAfterContactChange(sheet("k1", "Baustelle Nord", edited = true), sheet("k2", "Baustelle Nord", edited = true), presets))
        // Picked by chip, even the chip of the previous person's own address.
        assertEquals(headLine, Appointment.placeAfterContactChange(sheet("k1", headLine, edited = true), sheet("k2", headLine, edited = true), presets))
    }

    @Test
    fun `a place that is not the previous person's address stays, flag or not`() {
        assertEquals("Baustelle Nord", Appointment.placeAfterContactChange(sheet("k1", "Baustelle Nord"), sheet("k2", "Baustelle Nord"), presets))
    }

    @Test
    fun `without a change of person, or without a preset, the incoming place is kept`() {
        assertEquals("Musterweg 1a", Appointment.placeAfterContactChange(sheet("k1", headLine), sheet("k1", "Musterweg 1a"), presets))
        assertEquals("", Appointment.placeAfterContactChange(sheet("k1", ""), sheet("k2", ""), { null }))
    }

    @Test
    fun `a callback's place never moves`() {
        assertEquals(
            "",
            Appointment.placeAfterContactChange(
                sheet("k1", "", kind = AppointmentKind.CALLBACK),
                sheet("k2", "", kind = AppointmentKind.CALLBACK),
                presets,
            ),
        )
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
        assertEquals(
            Status.APPOINTMENT,
            Appointment.statusAfterSave(AppointmentKind.VISIT, "2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00", noon),
        )
    }

    @Test
    fun `entering a past appointment after the fact leaves the status alone`() {
        assertNull(Appointment.statusAfterSave(AppointmentKind.VISIT, "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00", noon))
    }

    @Test
    fun `saving a callback never sets the status`() {
        assertNull(Appointment.statusAfterSave(AppointmentKind.CALLBACK, "2026-09-11T09:00:00+02:00", "2026-09-11T09:15:00+02:00", noon))
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

    @Test
    fun `a callback still ahead does not keep the status at appointment`() {
        val onlyACallback = listOf(
            entry("R-1", "2026-09-12T09:00:00+02:00", "2026-09-12T09:15:00+02:00").copy(kind = AppointmentKind.CALLBACK),
        )

        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, onlyACallback, noon))
    }

    // --- callbacks: overdue, open and completed ---------------------------------

    private fun callback(id: String, startsAt: String, doneAt: String? = null) =
        entry(id, startsAt).copy(kind = AppointmentKind.CALLBACK, doneAt = doneAt)

    @Test
    fun `an open callback whose start has passed is overdue`() {
        assertTrue(Appointment.isOverdue(callback("R-1", "2026-09-10T11:00:00+02:00"), noon))
        assertTrue(Appointment.isOverdue(callback("R-2", "2026-09-01T09:00:00+02:00"), noon))
    }

    @Test
    fun `a callback ahead, a completed one and a visit are never overdue`() {
        assertFalse(Appointment.isOverdue(callback("R-1", "2026-09-10T13:00:00+02:00"), noon))
        assertFalse(Appointment.isOverdue(callback("R-2", "2026-09-10T11:00:00+02:00", doneAt = "2026-09-10T11:05:00+02:00"), noon))
        assertFalse(Appointment.isOverdue(entry("A-1", "2026-09-01T09:00:00+02:00"), noon))
    }

    @Test
    fun `callbacks split into open earliest first and completed latest first, visits left out`() {
        val all = listOf(
            callback("open-late", "2026-09-20T09:00:00+02:00"),
            callback("done-early", "2026-09-01T09:00:00+02:00", doneAt = "2026-09-01T09:10:00+02:00"),
            entry("visit", "2026-09-11T09:00:00+02:00"),
            callback("open-early", "2026-09-05T09:00:00+02:00"),
            callback("done-late", "2026-09-08T09:00:00+02:00", doneAt = "2026-09-08T09:10:00+02:00"),
        )

        val (open, done) = Appointment.splitCallbacks(all)

        assertEquals(listOf("open-early", "open-late"), open.map { it.id })
        assertEquals(listOf("done-late", "done-early"), done.map { it.id })
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
    fun `first sight of an event that differs lets the event win`() {
        // Decided by the user: the calendar is the truth a device has not seen yet.
        assertEquals(Reconcile.TakeEvent(planned), Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore))
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

    // --- saving with a shared calendar ----------------------------------------

    @Test
    fun `an appointment whose event is elsewhere is saved without touching the calendar`() {
        // Creating one would put a second event into the shared calendar the
        // moment DAVx5 catches up on this device.
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
            eventUid = "A-1",
        )

        assertEquals(SavePlan.LocalOnly, plan)
    }

    @Test
    fun `an appointment whose event is here is updated`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = 42L, linkExisting = null, force = false, calendarEnabled = true,
            eventUid = "A-1",
        )

        assertEquals(SavePlan.Update(42L), plan)
    }

    // --- busy times and adopting ----------------------------------------------

    @Test
    fun `busy times leave out the edited appointment's event by UID, and nothing else`() {
        val own = busy(14, 15, "Ortstermin Elektro Meier", eventId = 1L, uid = "A-1")
        val sibling = busy(16, 17, "Ortstermin Elektro Meier – Angebot", eventId = 2L, uid = "A-2")
        val dentist = busy(9, 10, "Zahnarzt", eventId = 3L)

        val left = Appointment.busyExcept(listOf(own, sibling, dentist), ownUid = "A-1", ownEventId = null)

        assertEquals(listOf(sibling, dentist), left)
    }

    @Test
    fun `without a UID the local event id recognises the own event`() {
        val own = busy(14, 15, "Ortstermin", eventId = 1L)
        val other = busy(16, 17, "Steuerbüro", eventId = 2L)

        assertEquals(listOf(other), Appointment.busyExcept(listOf(own, other), ownUid = null, ownEventId = 1L))
    }

    @Test
    fun `an event another appointment holds by UID is not offered for linking`() {
        val taken = TakenEvents(uids = setOf("A-2"), eventIds = emptySet())

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Ortstermin", eventId = 2L, uid = "A-2"), taken))
    }

    @Test
    fun `an event another appointment holds on this device is not offered for linking`() {
        val taken = TakenEvents(uids = emptySet(), eventIds = setOf(2L))

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Ortstermin", eventId = 2L), taken))
    }

    @Test
    fun `a free event can be linked, a block without an event cannot`() {
        val taken = TakenEvents(uids = setOf("A-2"), eventIds = setOf(2L))

        assertTrue(Appointment.mayAdopt(busy(14, 15, "Steuerbüro", eventId = 7L, uid = "x@infomaniak"), taken))
        assertFalse(Appointment.mayAdopt(busy(14, 15, "Steuerbüro"), taken))
    }

    @Test
    fun `a recurring event is never offered for linking`() {
        // Events.DTSTART is the start of the series, not of the occurrence in
        // the strip: linking a weekly meeting would move the appointment to its
        // first occurrence, months back.
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Jour fixe", eventId = 7L, uid = "jf@infomaniak", recurring = true), none))
    }

    @Test
    fun `a new appointment may link any free event`() {
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())
        val free = busy(14, 15, "Steuerbüro", eventId = 7L)
        val series = busy(16, 17, "Jour fixe", eventId = 8L, recurring = true)

        assertEquals(setOf(7L), Appointment.adoptable(listOf(free, series), none, ownUid = null, ownEventId = null))
    }

    @Test
    fun `an appointment that already has an event is offered no other to link`() {
        // Its old event would stay in the calendar, and a device whose shortcut
        // still points there would keep writing into it and take its UID back.
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())
        val free = listOf(busy(14, 15, "Steuerbüro", eventId = 7L))

        assertTrue(Appointment.adoptable(free, none, ownUid = "A-1", ownEventId = null).isEmpty())
        assertTrue(Appointment.adoptable(free, none, ownUid = null, ownEventId = 4711L).isEmpty())
    }

    // --- the UID ---------------------------------------------------------------

    @Test
    fun `a row without a UID takes the one behind its shortcut`() {
        assertEquals("abc@infomaniak", Appointment.uidToTake(rowUid = null, eventUid = "abc@infomaniak"))
    }

    @Test
    fun `a row that has a UID never trades it for another`() {
        // Two devices, each with a shortcut to its own copy, would otherwise
        // swap UIDs on every opening.
        assertNull(Appointment.uidToTake(rowUid = "legacy-P1", eventUid = "abc@infomaniak"))
    }

    @Test
    fun `a row whose UID names another event here moves its shortcut there`() {
        assertEquals(815L, Appointment.relinkTo(rowUid = "legacy-P1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = 815L))
    }

    @Test
    fun `no relinking without a UID, with the same UID, or when the row's UID is not here`() {
        assertNull(Appointment.relinkTo(rowUid = null, eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = null))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "A-1", shortcutId = 4711L, rowUidEventId = 4711L))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = null))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = 4711L))
    }

    @Test
    fun `the same UID is nothing to take over`() {
        assertNull(Appointment.uidToTake(rowUid = "A-1", eventUid = "A-1"))
    }

    @Test
    fun `an empty UID after inserting keeps the link local`() {
        assertNull(Appointment.uidToTake(rowUid = null, eventUid = ""))
        assertNull(Appointment.uidToTake(rowUid = null, eventUid = null))
    }

    @Test
    fun `the shortcut is used while its event exists`() = runTest {
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { if (it == 4711L) "event 4711" else null },
            find = { throw AssertionError("no lookup while the shortcut works") },
        )

        assertEquals(Located(4711L, "event 4711"), found)
    }

    @Test
    fun `a shortcut whose event carries another UID is still that event, not taken for deleted`() = runTest {
        // The read does not compare UIDs: an _ID is not handed to another event.
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { "event with UID abc@infomaniak" },
            find = { null },
        )

        assertEquals(4711L, found?.eventId)
    }

    @Test
    fun `a gone shortcut falls back to the UID`() = runTest {
        // DAVx5 deleted and rewrote the event with a new _ID.
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { if (it == 815L) "event 815" else null },
            find = { if (it == "A-1") 815L else null },
        )

        assertEquals(Located(815L, "event 815"), found)
    }

    @Test(expected = IllegalStateException::class)
    fun `a lookup that fails is not an event that is gone`() = runTest {
        // Were this null, reconcile would take a provider hiccup for a deletion
        // and delete an appointment still ahead on every device. The decision
        // to skip the appointment instead is the caller's (CallsheetViewModel
        // .calendarLookup); what is pure here is that the failure reaches it.
        Appointment.locate<String>(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { throw IllegalStateException("provider failed") },
            find = { null },
        )
    }

    @Test
    fun `without a shortcut or a UID that finds something, nothing is found`() = runTest {
        assertNull(Appointment.locate<String>(null, null, read = { "x" }, find = { 1L }))
        assertNull(Appointment.locate<String>(null, "A-1", read = { "x" }, find = { null }))
    }

    // --- what the calendar and the lists show -----------------------------------

    @Test
    fun `the event title carries the note when there is one`() {
        assertEquals("Ortstermin Elektro Meier – Angebot", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", "Angebot"))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", " "))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", null))
    }

    @Test
    fun `a callback's title says so, and a completed one carries a tick`() {
        assertEquals(
            "Rückruf Elektro Meier – wegen Angebot nachfragen",
            Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", "wegen Angebot nachfragen"),
        )
        assertEquals("Rückruf Elektro Meier", Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", null))
        assertEquals(
            "✓ Rückruf Elektro Meier",
            Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", null, done = true),
        )
    }

    @Test
    fun `a visit never carries a tick`() {
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", null, done = true))
    }

    @Test
    fun `the description names the contact person and their first number`() {
        val contact = Contact(
            id = "K-1", placeId = "P1", name = "Frau Meier", role = null, email = null, note = null,
            numbers = listOf(
                PhoneNumber("N-1", "+4917612345", PhoneType.MOBILE),
                PhoneNumber("N-2", "+49841999", PhoneType.WORK),
            ),
            updatedAt = "2026-09-07T10:00:00+02:00",
        )

        assertEquals("Frau Meier · +4917612345", Appointment.eventDescription(contact, "+4984112345"))
        assertEquals("Frau Meier", Appointment.eventDescription(contact.copy(numbers = emptyList()), "+4984112345"))
    }

    @Test
    fun `without a contact person the description is the business's number`() {
        assertEquals("+4984112345", Appointment.eventDescription(null, "+4984112345"))
    }

    @Test
    fun `the detail view shows ahead earliest first and past latest first`() {
        val all = listOf(
            entry("past-early", "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00"),
            entry("ahead-late", "2026-09-20T09:00:00+02:00", "2026-09-20T10:00:00+02:00"),
            entry("past-late", "2026-09-05T09:00:00+02:00", "2026-09-05T10:00:00+02:00"),
            entry("ahead-early", "2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00"),
        )

        val (ahead, past) = Appointment.split(all, noon)

        assertEquals(listOf("ahead-early", "ahead-late"), ahead.map { it.id })
        assertEquals(listOf("past-late", "past-early"), past.map { it.id })
    }

    // --- an event's end ---------------------------------------------------------

    @Test
    fun `an event's end is its DTEND`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start + 3_600_000L, Appointment.eventEnd(start, start + 3_600_000L, null))
    }

    @Test
    fun `an event without DTEND ends after its DURATION, never in 1970`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start + 5_400_000L, Appointment.eventEnd(start, 0L, "PT1H30M"))
        assertEquals(start + 3_600_000L, Appointment.eventEnd(start, null, "P3600S"))
        assertEquals(start + 86_400_000L, Appointment.eventEnd(start, 0L, "P1D"))
    }

    @Test
    fun `an event with neither a DTEND nor a readable DURATION ends at its start`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start, Appointment.eventEnd(start, 0L, null))
        assertEquals(start, Appointment.eventEnd(start, null, "irgendwas"))
    }
}
