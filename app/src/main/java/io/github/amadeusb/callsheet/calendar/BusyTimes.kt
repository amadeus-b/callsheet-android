package io.github.amadeusb.callsheet.calendar

import android.content.Context
import android.provider.CalendarContract
import io.github.amadeusb.callsheet.calling.BusyInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What is already taken on a given day.
 *
 * Read from **every** visible calendar, not only the one appointments get
 * written to. A private appointment that is invisible here is exactly the one an
 * on-site visit gets booked over.
 */
object BusyTimes {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** The occupied intervals of the day beginning at [dayStartMillis]. */
    suspend fun forDay(context: Context, dayStartMillis: Long): List<BusyInterval> =
        withContext(Dispatchers.IO) {
            if (!CalendarStore.canRead(context)) return@withContext emptyList()
            val until = dayStartMillis + DAY_MILLIS
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(dayStartMillis.toString())
                .appendPath(until.toString())
                .build()
            val found = ArrayList<BusyInterval>()
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.ALL_DAY,
                        CalendarContract.Instances.EVENT_ID,
                    ),
                    // "Every visible calendar" has to be said in the query, not
                    // only in the comment: a calendar the user has switched off
                    // is one they have decided not to be shown.
                    "${CalendarContract.Instances.VISIBLE} = 1",
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        // All-day entries say nothing about which hours are free.
                        if (c.getInt(3) == 1) continue
                        found.add(
                            BusyInterval(
                                startMillis = c.getLong(0),
                                endMillis = c.getLong(1),
                                title = c.getString(2)?.ifBlank { null } ?: "Termin",
                                eventId = c.getLong(4),
                            )
                        )
                    }
                }
            }
            found
        }
}
