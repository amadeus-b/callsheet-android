package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.AppointmentDraft
import io.github.amadeusb.callsheet.data.Addresses
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.TakenEvents
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** A stretch of time already taken, as read from the device's calendars. */
data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    /**
     * The calendar event behind it. Without this, the conflict question could
     * not offer to link an appointment that already exists — it would only be
     * able to refuse or duplicate.
     */
    val eventId: Long? = null,
    /**
     * The event's iCalendar UID. The same on every device carrying the shared
     * calendar — which is how an appointment recognises its own event here,
     * and how an event another appointment holds is kept from being linked twice.
     */
    val uid: String? = null,
    /**
     * A recurring event, or a changed occurrence of one. Its `Events.DTSTART`
     * is not the time shown in the strip, so it is never offered for linking.
     */
    val recurring: Boolean = false,
)

/** What saving an appointment should do about the calendar. */
sealed interface SavePlan {
    /** The window is taken and nobody has said what to do about it yet. */
    data class Conflict(val with: List<BusyInterval>) : SavePlan

    /**
     * Take over an appointment that is already in the calendar. Its time and
     * place win and the event is left untouched — the app only records that the
     * two are the same thing. Writing the draft over it would rename and move
     * somebody else's entry, which is the one thing this feature must never do.
     */
    data class Adopt(val eventId: Long) : SavePlan

    /** Write the draft over the event this business already owns. */
    data class Update(val eventId: Long) : SavePlan

    /** Create a new event. */
    data object Create : SavePlan

    /** Columns only — the calendar is switched off or out of reach. */
    data object LocalOnly : SavePlan
}

/**
 * An appointment's time and place as the read-back compares them — for the
 * row, for the event, and for what this device last saw in the event.
 */
data class Slot(val startMillis: Long, val endMillis: Long?, val location: String?)

/** An event found in this device's calendar, and its `_ID` here. */
data class Located<E>(val eventId: Long, val event: E)

/**
 * What reading an appointment's event back calls for.
 *
 * The calendar is shared: every device carries the same CalDAV calendar and
 * DAVx5 brings it level at its own pace. So the calendar a device reads can be
 * behind the rows it holds, or ahead of them, and "the calendar wins" — the
 * rule for a single device — would undo changes made on another. The row wins
 * except where only the calendar moved, and on first sight, where the calendar
 * wins as well — what a device has never seen, it takes from the calendar.
 */
sealed interface Reconcile {
    /** Row and event agree. Only what this device saw is recorded. */
    data object InStep : Reconcile

    /** Moved or relocated in the calendar, or seen here for the first time and different: the row takes [slot]. */
    data class TakeEvent(val slot: Slot) : Reconcile

    /** Changed on another device: the event is updated from the row. */
    data object UpdateEvent : Reconcile

    /** Not found, and never seen on this device: it may not have arrived yet. */
    data object NotYetHere : Reconcile

    /** Seen before, gone now, appointment still ahead: deleted in the calendar. */
    data object DeletedInCalendar : Reconcile

    /**
     * Seen before, gone now, appointment already past. Calendars clear out old
     * events on their own; that must not erase the record. Only the link goes.
     */
    data object Unlink : Reconcile
}

/**
 * The arithmetic behind an appointment on site.
 *
 * Deliberately free of Android: what is worth testing here is milliseconds and
 * overlap, not a content provider.
 *
 * Note what this does *not* do. [FollowUp] pushes every date it computes into a
 * workday between 8 and 18, because a follow-up is the app's own suggestion. An
 * appointment is what the customer agreed to on the phone, so it is taken
 * exactly as entered — Saturday evening included.
 */
object Appointment {

    /** The duration a fresh appointment starts at. */
    const val DEFAULT_MINUTES: Int = 60

    /** The durations offered as chips. */
    val DURATIONS: List<Int> = listOf(30, 60, 90, 120)

    /** The length a fresh callback starts at. A phone call, not a visit. */
    const val CALLBACK_MINUTES: Int = 15

    /** The durations offered as chips for a callback. */
    val CALLBACK_DURATIONS: List<Int> = listOf(15, 30)

    /** The length an appointment of [kind] falls back to where none is known. */
    fun defaultMinutes(kind: AppointmentKind): Int = when (kind) {
        AppointmentKind.VISIT -> DEFAULT_MINUTES
        AppointmentKind.CALLBACK -> CALLBACK_MINUTES
    }

    /** The chips the sheet offers for [kind]. */
    fun durations(kind: AppointmentKind): List<Int> = when (kind) {
        AppointmentKind.VISIT -> DURATIONS
        AppointmentKind.CALLBACK -> CALLBACK_DURATIONS
    }

    private val range: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EE, dd.MM.", Locale.GERMAN)

    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Rounds a moment up to the next quarter hour.
     *
     * The picker snaps every drag to fifteen minutes, so a proposed start of
     * 16:32 would be a time the user cannot reproduce by hand — the one value on
     * the screen that does not obey the screen's own rule.
     */
    fun snapToQuarter(iso: String): String {
        val millis = Clock.millis(iso) ?: return iso
        val quarter = 15 * 60_000L
        val rest = Math.floorMod(millis, quarter)
        return if (rest == 0L) iso else Clock.format(millis + (quarter - rest))
    }

    /** The end of an appointment starting at [startIso] and running [minutes]. */
    fun endOf(startIso: String, minutes: Int): String {
        val start = Clock.millis(startIso) ?: return startIso
        return Clock.format(start + minutes * 60_000L)
    }

    /**
     * How long an appointment runs. Falls back to [fallback] when either end is
     * missing or unreadable, so the picker always has a length to show — a
     * callback's own, where the caller passes it.
     */
    fun minutesBetween(startIso: String?, endIso: String?, fallback: Int = DEFAULT_MINUTES): Int {
        val start = Clock.millis(startIso) ?: return fallback
        val end = Clock.millis(endIso) ?: return fallback
        val minutes = ((end - start) / 60_000L).toInt()
        return if (minutes > 0) minutes else fallback
    }

    /**
     * The busy intervals a proposed appointment runs into. Appointments that
     * merely touch — one ending as the next begins — do not overlap.
     */
    fun overlapping(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
    ): List<BusyInterval> = busy.filter { startMillis < it.endMillis && it.startMillis < endMillis }

    /**
     * What saving should do. The order matters: an explicit instruction from the
     * user beats a conflict, and a conflict beats everything else.
     *
     * [ownEventId] is the appointment's event as found on this device. An
     * [eventUid] without one means the event exists in the shared calendar but
     * has not reached this device's copy: creating another would put a second
     * event into the calendar the moment DAVx5 catches up. The device that
     * holds the event writes the change into it after its next sync.
     */
    fun plan(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
        ownEventId: Long?,
        linkExisting: Long?,
        force: Boolean,
        calendarEnabled: Boolean,
        eventUid: String? = null,
    ): SavePlan {
        if (linkExisting != null) return SavePlan.Adopt(linkExisting)
        if (!force) {
            val clash = overlapping(startMillis, endMillis, busy)
            if (clash.isNotEmpty()) return SavePlan.Conflict(clash)
        }
        if (!calendarEnabled) return SavePlan.LocalOnly
        if (ownEventId != null) return SavePlan.Update(ownEventId)
        return if (eventUid != null) SavePlan.LocalOnly else SavePlan.Create
    }

    /** Street, postal code and city on one line. Null when nothing is known. */
    fun address(street: String?, postalCode: String?, city: String?): String? =
        Addresses.oneLine(street, postalCode, city)

    /**
     * The place a visit starts at: the address of [contactId]'s person — the one
     * they are assigned to, else the business's main address. Empty without any
     * address.
     */
    fun presetLocation(contactId: String?, contacts: List<Contact>, addresses: List<BusinessAddress>): String {
        val person = contacts.firstOrNull { it.id == contactId }
        return Addresses.forContact(person?.addressId, addresses)?.oneLine.orEmpty()
    }

    /**
     * The place for [incoming] after the sheet changed from [previous]. It
     * follows the contact person only while it was not chosen by hand: when the
     * person changed, the place did not change in the same update, and the
     * previous place was still the previous person's preset and not marked as
     * chosen ([AppointmentDraft.locationEdited] — a chip counts as chosen, even
     * the chip of that very address). Then it is the new person's preset, or,
     * without one, stays as it was. A callback has no place and never moves.
     *
     * [presetFor] gives a person's preset place, null for none; a null id is no
     * person. The visits plan builds on this signature — keep it.
     */
    fun placeAfterContactChange(
        previous: AppointmentDraft,
        incoming: AppointmentDraft,
        presetFor: (contactId: String?) -> String?,
    ): String {
        if (incoming.kind == AppointmentKind.CALLBACK) return incoming.location
        if (incoming.contactId == previous.contactId) return incoming.location
        if (incoming.location != previous.location) return incoming.location
        val handEdited = previous.locationEdited || previous.location != presetFor(previous.contactId).orEmpty()
        if (handEdited) return incoming.location
        return presetFor(incoming.contactId) ?: incoming.location
    }

    /** Within a minute counts as the same moment. */
    private fun near(a: Long?, b: Long): Boolean = a != null && abs(a - b) < 60_000L

    /**
     * Whether an appointment is still ahead: its end — or its start, where it
     * has none — lies in the future. The one definition behind the status, the
     * read-back and the split in the detail view.
     */
    fun isAhead(startsAt: String?, endsAt: String?, nowMillis: Long): Boolean {
        val last = Clock.millis(endsAt) ?: Clock.millis(startsAt) ?: return false
        return last > nowMillis
    }

    /**
     * The status to set after saving, or null to leave it. A visit still ahead
     * means one was agreed; a past one entered after the fact says nothing
     * about where the business stands now. A callback is the app's own
     * reminder to ring again and never says anything about the status.
     */
    fun statusAfterSave(kind: AppointmentKind, startsAt: String, endsAt: String?, nowMillis: Long): Status? =
        if (kind == AppointmentKind.VISIT && isAhead(startsAt, endsAt, nowMillis)) Status.APPOINTMENT else null

    /**
     * The status after a visit went away — removed, or deleted in the calendar —
     * or null to leave it. [remaining] are the business's appointments without
     * that one; only visits among them count.
     *
     * Only the status a visit set is taken back, and only once none is left
     * ahead. `declined` and `do_not_call` are decisions made on the phone; a
     * removed appointment is not permission to undo them. Callers only ask when
     * the one that went away was a visit.
     */
    fun statusAfterRemoval(status: Status, remaining: List<AppointmentEntry>, nowMillis: Long): Status? {
        if (status != Status.APPOINTMENT) return null
        val visitAhead = remaining.any { it.kind == AppointmentKind.VISIT && isAhead(it.startsAt, it.endsAt, nowMillis) }
        return if (visitAhead) null else Status.CALLED
    }

    /**
     * An open callback whose start has passed. It stays open work until a call
     * completes it. A visit is never overdue: once its time is past it
     * happened, or it did not.
     */
    fun isOverdue(entry: AppointmentEntry, nowMillis: Long): Boolean {
        if (entry.kind != AppointmentKind.CALLBACK || entry.doneAt != null) return false
        val start = Clock.millis(entry.startsAt) ?: return false
        return start < nowMillis
    }

    /**
     * A business's callbacks as the detail view lists them: open ones earliest
     * first, completed ones latest completion first. Visits are left out.
     */
    fun splitCallbacks(entries: List<AppointmentEntry>): Pair<List<AppointmentEntry>, List<AppointmentEntry>> {
        val (done, open) = entries
            .filter { it.kind == AppointmentKind.CALLBACK }
            .partition { it.doneAt != null }
        return open.sortedBy { Clock.millis(it.startsAt) ?: Long.MAX_VALUE } to
            done.sortedByDescending { Clock.millis(it.doneAt) ?: 0L }
    }

    /** The row's slot. Null when its start cannot be read. */
    fun rowSlot(entry: AppointmentEntry): Slot? {
        val start = Clock.millis(entry.startsAt) ?: return null
        return Slot(start, Clock.millis(entry.endsAt), entry.location)
    }

    /** What this device last saw in the event. Null while it never saw it. */
    fun seenSlot(entry: AppointmentEntry): Slot? {
        val start = Clock.millis(entry.seenStartsAt) ?: return null
        return Slot(start, Clock.millis(entry.seenEndsAt), entry.seenLocation)
    }

    /**
     * Compares the row (R), the event (E) and what this device last saw in the
     * event (S). A null [event] means it was not found on this device.
     *
     * | S     | E = S | R = S | result                          |
     * |-------|-------|-------|---------------------------------|
     * | none  |       |       | E = R: in step, else E wins     |
     * | set   | yes   | yes   | in step                         |
     * | set   | no    | yes   | E wins                          |
     * | set   | yes   | no    | R wins                          |
     * | set   | no    | no    | E = R: in step, else R wins     |
     *
     * Where both changed, the row wins: it is what every device shows, and the
     * calendar gives no modification time to compare. On first sight the
     * calendar wins — the user's decision: what a device has never seen, it
     * takes as it stands in the calendar.
     */
    fun reconcile(row: Slot, seen: Slot?, event: Slot?, nowMillis: Long): Reconcile {
        if (event == null) {
            if (seen == null) return Reconcile.NotYetHere
            val last = row.endMillis ?: row.startMillis
            return if (last > nowMillis) Reconcile.DeletedInCalendar else Reconcile.Unlink
        }
        if (sameSlot(event, row)) return Reconcile.InStep
        if (seen == null) return Reconcile.TakeEvent(event)
        val onlyTheCalendarMoved = !sameSlot(event, seen) && sameSlot(row, seen)
        return if (onlyTheCalendarMoved) Reconcile.TakeEvent(event) else Reconcile.UpdateEvent
    }

    /**
     * Whether this device's record of the event is already current: the same
     * `_ID`, and a seen slot that matches what the event holds. The read-back
     * records the event only when it is not — rewriting an unchanged link on
     * every opening would notify every observer of the database for nothing.
     */
    fun seenIsCurrent(entry: AppointmentEntry, eventId: Long, event: Slot): Boolean {
        val seen = seenSlot(entry) ?: return false
        return entry.calendarEventId == eventId && sameSlot(seen, event)
    }

    /**
     * Within a minute, locations after trimming. A missing end on either side is
     * no difference: an event always has one, a row may not.
     */
    private fun sameSlot(a: Slot, b: Slot): Boolean {
        val sameEnd = a.endMillis == null || b.endMillis == null || near(a.endMillis, b.endMillis)
        return near(a.startMillis, b.startMillis) && sameEnd &&
            a.location?.trim().orEmpty() == b.location?.trim().orEmpty()
    }

    /** For the interface: "Do, 10.09. · 14:00 – 15:00". */
    fun readableRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val from = Clock.zdt(start)
        val head = "${from.format(range)} · ${from.format(time)}"
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }

    /**
     * The busy times without the event of the appointment being edited —
     * recognised by its UID, or by this device's event id where it has none
     * yet. The business's other appointments stay: two at the same time are a
     * real conflict.
     */
    fun busyExcept(busy: List<BusyInterval>, ownUid: String?, ownEventId: Long?): List<BusyInterval> =
        busy.filterNot { interval ->
            (ownUid != null && interval.uid == ownUid) || (ownEventId != null && interval.eventId == ownEventId)
        }

    /**
     * Whether a busy interval may be linked to an appointment. Not when another
     * appointment already holds the event — changing or removing one would take
     * the other along. The UID travels, so this holds across devices.
     */
    fun mayAdopt(interval: BusyInterval, taken: TakenEvents): Boolean {
        val eventId = interval.eventId ?: return false
        // Events.DTSTART of a series is its first occurrence, not this one.
        if (interval.recurring) return false
        if (interval.uid != null && interval.uid in taken.uids) return false
        return eventId !in taken.eventIds
    }

    /**
     * The events the sheet may offer for „Verknüpfen". None for an appointment
     * that already has an event ([ownUid] or [ownEventId]). Linking it to
     * another would leave the old event in the calendar, and a device whose
     * shortcut still points there would keep writing into it and take its UID
     * back: two events for one appointment, and a UID going back and forth.
     */
    fun adoptable(busy: List<BusyInterval>, taken: TakenEvents, ownUid: String?, ownEventId: Long?): Set<Long> {
        if (ownUid != null || ownEventId != null) return emptySet()
        return busy.filter { mayAdopt(it, taken) }.mapNotNull { it.eventId }.toSet()
    }

    /**
     * The UID a row takes over from its event, or null when there is nothing to take.
     *
     * Only a row without a UID takes one: an event the app just created, one it
     * adopted, or a carried-over appointment whose event DAVx5 has uploaded. A
     * row that has a UID keeps it, whatever its shortcut finds — two devices
     * each holding a copy of the event would otherwise trade UIDs on every
     * opening, and both copies would stay in the calendar.
     */
    fun uidToTake(rowUid: String?, eventUid: String?): String? =
        eventUid?.takeIf { rowUid == null && it.isNotBlank() }

    /**
     * Where the shortcut should point instead, or null to leave it.
     *
     * The event behind the shortcut ([shortcutId]) carries [eventUid], the row
     * holds [rowUid], and [rowUidEventId] is the event that UID finds on this
     * device. When the row's UID names a different event here, that event is the
     * appointment's; the shortcut points at a stale copy.
     */
    fun relinkTo(rowUid: String?, eventUid: String?, shortcutId: Long, rowUidEventId: Long?): Long? {
        if (rowUid == null || eventUid == rowUid) return null
        return rowUidEventId?.takeIf { it != shortcutId }
    }

    /**
     * Finds an appointment's event on this device: through the shortcut while
     * the event behind it still exists, otherwise by UID among the calendars
     * the app reads. Neither found means the event is not on this device.
     *
     * The shortcut is trusted without comparing UIDs — an `_ID` is not handed to
     * another event, and taking a changed UID for a deleted event would delete
     * the appointment.
     *
     * Nothing is caught here. A [read] or [find] that throws — a refused
     * permission, a provider that fails — propagates: null means "not on this
     * device", and a failure passed off as that would read as a deletion.
     */
    suspend fun <E> locate(
        calendarEventId: Long?,
        eventUid: String?,
        read: suspend (Long) -> E?,
        find: suspend (String) -> Long?,
    ): Located<E>? {
        if (calendarEventId != null) {
            read(calendarEventId)?.let { return Located(calendarEventId, it) }
        }
        val uid = eventUid ?: return null
        val eventId = find(uid) ?: return null
        return read(eventId)?.let { Located(eventId, it) }
    }

    /**
     * „Ortstermin Elektro Meier – Angebot", „Rückruf Elektro Meier – wegen
     * Angebot nachfragen", and „✓ Rückruf Elektro Meier" once a callback is
     * completed. Without a note the part from the dash on is left out.
     *
     * The tick is the only way a calendar can show a completed callback: an
     * event (VEVENT) has no completed state, only a task (VTODO) has.
     */
    fun eventTitle(kind: AppointmentKind, businessName: String, note: String?, done: Boolean = false): String {
        val head = when (kind) {
            AppointmentKind.VISIT -> "Ortstermin $businessName"
            AppointmentKind.CALLBACK -> "${if (done) "✓ " else ""}Rückruf $businessName"
        }.trim()
        val tail = note?.trim()?.ifEmpty { null } ?: return head
        return "$head – $tail"
    }

    /** Who to ask for on site: the contact person and their first number, else the business's number. */
    fun eventDescription(contact: Contact?, businessPhone: String?): String? {
        if (contact == null) return businessPhone
        return listOfNotNull(contact.name, contact.numbers.firstOrNull()?.number).joinToString(" · ")
    }

    /**
     * An event's end in milliseconds. Most events carry `DTEND`; a recurring
     * one carries `DURATION` and an empty `DTEND`, which the provider hands back
     * as 0. Taken at face value that end is in 1970, and a read-back or a link
     * would move the appointment there.
     *
     * [duration] is RFC 5545 (`PT1H30M`, `P1D`) or the provider's own `P3600S`.
     * Neither usable: the end is the start.
     */
    fun eventEnd(startMillis: Long, dtEnd: Long?, duration: String?): Long {
        if (dtEnd != null && dtEnd > startMillis) return dtEnd
        val match = DURATION_PATTERN.matchEntire(duration?.trim().orEmpty()) ?: return startMillis
        val (weeks, days, hours, minutes, seconds) = match.destructured
        val totalSeconds = (weeks.toLongOrNull() ?: 0L) * 7 * 86_400 +
            (days.toLongOrNull() ?: 0L) * 86_400 +
            (hours.toLongOrNull() ?: 0L) * 3_600 +
            (minutes.toLongOrNull() ?: 0L) * 60 +
            (seconds.toLongOrNull() ?: 0L)
        return startMillis + totalSeconds * 1_000L
    }

    private val DURATION_PATTERN = Regex("""\+?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")

    /** Ahead earliest first, past latest first — the order the detail view lists them in. */
    fun split(
        appointments: List<AppointmentEntry>,
        nowMillis: Long,
    ): Pair<List<AppointmentEntry>, List<AppointmentEntry>> {
        val (ahead, past) = appointments.partition { isAhead(it.startsAt, it.endsAt, nowMillis) }
        val start = { entry: AppointmentEntry -> Clock.millis(entry.startsAt) ?: 0L }
        return ahead.sortedBy(start) to past.sortedByDescending(start)
    }
}
