package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Agenda
import io.github.amadeusb.callsheet.calling.AgendaGroup
import io.github.amadeusb.callsheet.calling.AgendaSection
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {

    private fun instant(iso: String): Long = Clock.millis(iso)!!

    /** Thursday, 10 September 2026, noon. */
    private val noon = instant("2026-09-10T12:00:00+02:00")

    private fun visit(id: String, startsAt: String, endsAt: String? = null, note: String? = null) = AppointmentEntry(
        id = id, placeId = "P1", startsAt = startsAt, endsAt = endsAt,
        location = null, note = note, contactId = null,
    )

    private fun callback(id: String, startsAt: String, endsAt: String? = null, doneAt: String? = null, note: String? = null) =
        visit(id, startsAt, endsAt, note).copy(kind = AppointmentKind.CALLBACK, doneAt = doneAt)

    private fun sections(vararg entries: AppointmentEntry): List<AgendaSection<AppointmentEntry>> =
        Agenda.sections(entries.toList(), { it }, noon)

    @Test
    fun `nothing listed, no sections`() {
        assertTrue(sections().isEmpty())
    }

    @Test
    fun `open callbacks from earlier days and from earlier today are overdue, earliest first`() {
        val result = sections(
            callback("this-morning", "2026-09-10T09:00:00+02:00"),
            callback("last-week", "2026-09-03T09:00:00+02:00"),
        )

        assertEquals(AgendaGroup.OVERDUE, result.first().group)
        assertEquals(listOf("last-week", "this-morning"), result.first().items.map { it.id })
    }

    @Test
    fun `today holds today's visits, past ones included, and callbacks still ahead today`() {
        val result = sections(
            callback("this-afternoon", "2026-09-10T15:00:00+02:00"),
            visit("visit-this-morning", "2026-09-10T08:00:00+02:00", "2026-09-10T09:00:00+02:00"),
            visit("visit-tonight", "2026-09-10T18:00:00+02:00"),
        )

        val today = result.single()
        assertEquals(AgendaGroup.TODAY, today.group)
        assertEquals(instant("2026-09-10T00:00:00+02:00"), today.dayStartMillis)
        assertEquals(listOf("visit-this-morning", "this-afternoon", "visit-tonight"), today.items.map { it.id })
    }

    @Test
    fun `every later day with something on it gets a section of its own, in order`() {
        val result = sections(
            visit("tuesday", "2026-09-15T10:00:00+02:00"),
            callback("friday-late", "2026-09-11T16:00:00+02:00"),
            callback("friday-early", "2026-09-11T09:00:00+02:00"),
        )

        assertEquals(listOf(AgendaGroup.DAY, AgendaGroup.DAY), result.map { it.group })
        assertEquals(instant("2026-09-11T00:00:00+02:00"), result[0].dayStartMillis)
        assertEquals(listOf("friday-early", "friday-late"), result[0].items.map { it.id })
        assertEquals(instant("2026-09-15T00:00:00+02:00"), result[1].dayStartMillis)
    }

    @Test
    fun `completed callbacks and visits from earlier days are not listed`() {
        val result = sections(
            callback("done", "2026-09-10T09:00:00+02:00", doneAt = "2026-09-10T09:05:00+02:00"),
            callback("done-tomorrow", "2026-09-11T09:00:00+02:00", doneAt = "2026-09-10T11:00:00+02:00"),
            visit("yesterday", "2026-09-09T10:00:00+02:00"),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sections come in the order overdue, today, later days`() {
        val result = sections(
            visit("tomorrow", "2026-09-11T10:00:00+02:00"),
            visit("today", "2026-09-10T14:00:00+02:00"),
            callback("overdue", "2026-09-09T10:00:00+02:00"),
        )

        assertEquals(listOf(AgendaGroup.OVERDUE, AgendaGroup.TODAY, AgendaGroup.DAY), result.map { it.group })
    }

    @Test
    fun `titles count, and a later day is named with weekday and date`() {
        val result = sections(
            callback("o1", "2026-09-09T10:00:00+02:00"),
            callback("o2", "2026-09-08T10:00:00+02:00"),
            visit("t1", "2026-09-10T14:00:00+02:00"),
            visit("d1", "2026-09-15T10:00:00+02:00"),
        )

        assertEquals(listOf("Überfällig (2)", "Heute (1)", "Dienstag, 15.09."), result.map { Agenda.title(it) })
    }

    @Test
    fun `a row names the kind, the time and the note`() {
        val entry = callback("R-1", "2026-09-10T09:00:00+02:00", "2026-09-10T09:15:00+02:00", note = "wegen Angebot")

        assertEquals("Rückruf · 09:00 – 09:15 · wegen Angebot", Agenda.rowLabel(entry, withDate = false))
        assertEquals("Rückruf · Do., 10.09. · 09:00 – 09:15 · wegen Angebot", Agenda.rowLabel(entry, withDate = true))
        assertEquals("Vor Ort · 14:00", Agenda.rowLabel(visit("A-1", "2026-09-10T14:00:00+02:00"), withDate = false))
    }

    @Test
    fun `the next day starts at midnight, across a change of clocks too`() {
        assertEquals(instant("2026-09-11T00:00:00+02:00"), Clock.nextDayStart(noon))
        // 25 October 2026: the clocks go back, that day has 25 hours.
        assertEquals(instant("2026-10-26T00:00:00+01:00"), Clock.nextDayStart(instant("2026-10-25T23:30:00+01:00")))
    }
}
