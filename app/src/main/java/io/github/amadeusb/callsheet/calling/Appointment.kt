package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.Clock
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

/** What the calendar has to say about an appointment the app already knows. */
sealed interface ReadBack {
    /** The calendar agrees with the app. */
    data object Unchanged : ReadBack

    /** The calendar moved or relocated it; these values win. */
    data class Updated(val startIso: String, val endIso: String, val location: String?) : ReadBack

    /** The event is gone. The only case that needs the user told. */
    data object Gone : ReadBack
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

    private val range: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EE, dd.MM.", Locale.GERMAN)

    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** The end of an appointment starting at [startIso] and running [minutes]. */
    fun endOf(startIso: String, minutes: Int): String {
        val start = Clock.millis(startIso) ?: return startIso
        return Clock.format(start + minutes * 60_000L)
    }

    /**
     * How long an appointment runs. Falls back to [DEFAULT_MINUTES] when either
     * end is missing or unreadable, so the picker always has a length to show.
     */
    fun minutesBetween(startIso: String?, endIso: String?): Int {
        val start = Clock.millis(startIso) ?: return DEFAULT_MINUTES
        val end = Clock.millis(endIso) ?: return DEFAULT_MINUTES
        val minutes = ((end - start) / 60_000L).toInt()
        return if (minutes > 0) minutes else DEFAULT_MINUTES
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
     */
    fun plan(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
        ownEventId: Long?,
        linkExisting: Long?,
        force: Boolean,
        calendarEnabled: Boolean,
    ): SavePlan {
        if (linkExisting != null) return SavePlan.Adopt(linkExisting)
        if (!force) {
            val clash = overlapping(startMillis, endMillis, busy)
            if (clash.isNotEmpty()) return SavePlan.Conflict(clash)
        }
        if (!calendarEnabled) return SavePlan.LocalOnly
        return if (ownEventId != null) SavePlan.Update(ownEventId) else SavePlan.Create
    }

    /** Street, postal code and city on one line. Null when nothing is known. */
    fun address(street: String?, postalCode: String?, city: String?): String? {
        val town = listOfNotNull(
            postalCode?.trim()?.ifEmpty { null },
            city?.trim()?.ifEmpty { null },
        ).joinToString(" ").ifEmpty { null }
        return listOfNotNull(street?.trim()?.ifEmpty { null }, town)
            .joinToString(", ")
            .ifEmpty { null }
    }

    /**
     * Compares what the app holds against what the calendar returned. The
     * calendar wins on time and location — that is where an appointment gets
     * moved, on a laptop or in the car.
     *
     * A null [eventStartMillis] means the event is gone.
     */
    fun readBack(
        currentAt: String?,
        currentEnd: String?,
        currentLocation: String?,
        eventStartMillis: Long?,
        eventEndMillis: Long?,
        eventLocation: String?,
    ): ReadBack {
        if (eventStartMillis == null || eventEndMillis == null) return ReadBack.Gone
        // Compared in milliseconds, not as text. A provider that rounds DTSTART
        // to the minute, or hands back a different second resolution, would
        // otherwise look "moved" on every single open and rewrite updated_at
        // for ever.
        val sameTime = near(Clock.millis(currentAt), eventStartMillis) &&
            near(Clock.millis(currentEnd), eventEndMillis)
        val samePlace = eventLocation?.trim().orEmpty() == currentLocation?.trim().orEmpty()
        return if (sameTime && samePlace) {
            ReadBack.Unchanged
        } else {
            ReadBack.Updated(Clock.format(eventStartMillis), Clock.format(eventEndMillis), eventLocation)
        }
    }

    /** Within a minute counts as the same moment. */
    private fun near(a: Long?, b: Long): Boolean = a != null && abs(a - b) < 60_000L

    /** For the interface: "Do, 10.09. · 14:00 – 15:00". */
    fun readableRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val from = Clock.zdt(start)
        val head = "${from.format(range)} · ${from.format(time)}"
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }
}
