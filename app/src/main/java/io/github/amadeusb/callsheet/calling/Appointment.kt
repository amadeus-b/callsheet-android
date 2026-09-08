package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /** For the interface: "Do, 10.09. · 14:00 – 15:00". */
    fun readableRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val from = Clock.zdt(start)
        val head = "${from.format(range)} · ${from.format(time)}"
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }
}
