package io.github.amadeusb.callsheet.calendar

import android.content.Context

/**
 * This device's proof that the calendar reaches it: the latest confirmation
 * (AppointmentEntry.okSince) among the visits the read-back has found in the
 * calendar. Null while it found none. See Appointment.reconcileTracked.
 *
 * A setting of the device, not of an appointment — kept apart from
 * Preferences in a file of its own.
 */
class CalendarProof(context: Context) {

    private val store = context.getSharedPreferences("calendar_proof", Context.MODE_PRIVATE)

    var latest: Long?
        get() = if (store.contains(LATEST)) store.getLong(LATEST, 0L) else null
        set(value) = store.edit().apply { if (value == null) remove(LATEST) else putLong(LATEST, value) }.apply()

    private companion object {
        const val LATEST = "latest_ok_since"
    }
}
