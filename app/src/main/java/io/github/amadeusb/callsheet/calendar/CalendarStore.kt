package io.github.amadeusb.callsheet.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.amadeusb.callsheet.data.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A calendar on the device, as offered in the settings. */
data class CalendarAccount(val id: Long, val name: String, val accountName: String)

/** An appointment as the app writes it into the calendar. */
data class EventFields(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
)

/**
 * Appointments in the device calendar. Written into the calendar the user picks
 * in the settings — typically one DAVx5 keeps in sync. The app synchronises
 * nothing itself; it only stores what DAVx5 then uploads.
 *
 * Every call fails soft. A refused permission, a calendar that has gone away, a
 * provider that throws: all of them return null or false, and the appointment
 * carries on living in the app.
 */
object CalendarStore {

    fun canRead(context: Context): Boolean = granted(context, Manifest.permission.READ_CALENDAR)

    fun canWrite(context: Context): Boolean = granted(context, Manifest.permission.WRITE_CALENDAR)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Every calendar that can be written to. */
    suspend fun calendars(context: Context): List<CalendarAccount> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val found = ArrayList<CalendarAccount>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                ),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    found.add(
                        CalendarAccount(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "Kalender",
                            accountName = c.getString(2) ?: "",
                        )
                    )
                }
            }
        }
        found
    }

    /** Creates an event and returns its id, or null when it could not be written. */
    suspend fun insert(context: Context, calendarId: Long, fields: EventFields): Long? =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) return@withContext null
            runCatching {
                val values = values(fields).apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, Clock.zone.id)
                }
                context.contentResolver
                    .insert(CalendarContract.Events.CONTENT_URI, values)
                    ?.lastPathSegment
                    ?.toLongOrNull()
            }.getOrNull()
        }

    /** Writes [fields] over an existing event. False when it could not be done. */
    suspend fun update(context: Context, eventId: Long, fields: EventFields): Boolean =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) return@withContext false
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.update(uri, values(fields), null, null) > 0
            }.getOrDefault(false)
        }

    /**
     * Reads an event back. Null when it is gone — deleted in the calendar, or
     * removed by a synchronisation. That null is the signal the detail view acts
     * on, so it must not be confused with an error.
     */
    suspend fun read(context: Context, eventId: Long): EventFields? = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext null
        runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.DELETED,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                if (c.getInt(5) == 1) return@use null
                EventFields(
                    title = c.getString(0) ?: "",
                    startMillis = c.getLong(1),
                    endMillis = c.getLong(2),
                    location = c.getString(3)?.ifBlank { null },
                    description = c.getString(4)?.ifBlank { null },
                )
            }
        }.getOrNull()
    }

    /** Deletes an event. False when it could not be done. */
    suspend fun delete(context: Context, eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!canWrite(context)) return@withContext false
        runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun values(fields: EventFields): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.TITLE, fields.title)
        put(CalendarContract.Events.DTSTART, fields.startMillis)
        put(CalendarContract.Events.DTEND, fields.endMillis)
        put(CalendarContract.Events.EVENT_LOCATION, fields.location)
        put(CalendarContract.Events.DESCRIPTION, fields.description)
    }
}
