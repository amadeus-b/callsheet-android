package io.github.amadeusb.callsheet.contacts

import android.content.Context
import io.github.amadeusb.callsheet.calling.Appointment

/**
 * The app's handful of settings. SharedPreferences on purpose: three values
 * that cost nothing even when the database is wiped.
 */
class Preferences(context: Context) {

    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Whether contacts and businesses get written to the phone book at all. */
    var phoneBookEnabled: Boolean
        get() = store.getBoolean(PHONE_BOOK_ENABLED, false)
        set(value) = store.edit().putBoolean(PHONE_BOOK_ENABLED, value).apply()

    /** The chosen address book account that gets written to. */
    var account: AddressBookAccount?
        get() {
            val name = store.getString(ACCOUNT_NAME, null) ?: return null
            val type = store.getString(ACCOUNT_TYPE, null) ?: return null
            return AddressBookAccount(name, type)
        }
        set(value) {
            store.edit()
                .putString(ACCOUNT_NAME, value?.name)
                .putString(ACCOUNT_TYPE, value?.type)
                .apply()
        }

    /** Address of the sync server. Empty means: no synchronisation at all. */
    var serverUrl: String?
        get() = store.getString(SERVER_URL, null)?.ifBlank { null }
        set(value) = store.edit().putString(SERVER_URL, value?.trim()?.trimEnd('/')).apply()

    var serverToken: String?
        get() = store.getString(SERVER_TOKEN, null)?.ifBlank { null }
        set(value) = store.edit().putString(SERVER_TOKEN, value?.trim()).apply()

    /** How far the server has been read. A number, never a timestamp. */
    var watermark: Int
        get() = store.getInt(WATERMARK, 0)
        set(value) = store.edit().putInt(WATERMARK, value).apply()

    /** When the last sync went through completely. */
    var lastSyncAt: String?
        get() = store.getString(LAST_SYNC_AT, null)
        set(value) = store.edit().putString(LAST_SYNC_AT, value).apply()

    /** Whether appointments get mirrored into the device calendar at all. */
    var calendarEnabled: Boolean
        get() = store.getBoolean(CALENDAR_ENABLED, false)
        set(value) = store.edit().putBoolean(CALENDAR_ENABLED, value).apply()

    /** The chosen calendar that gets written to. */
    var calendarId: Long?
        get() = store.getLong(CALENDAR_ID, NO_CALENDAR).takeIf { it != NO_CALENDAR }
        set(value) = store.edit().putLong(CALENDAR_ID, value ?: NO_CALENDAR).apply()

    /**
     * The duration a fresh appointment starts at, in minutes. It follows the
     * last appointment saved — a setting that tunes itself instead of one to go
     * looking for.
     */
    var appointmentMinutes: Int
        get() = store.getInt(APPOINTMENT_MINUTES, Appointment.DEFAULT_MINUTES)
        set(value) = store.edit().putInt(APPOINTMENT_MINUTES, value).apply()

    private companion object {
        const val PHONE_BOOK_ENABLED = "phone_book_enabled"
        const val ACCOUNT_NAME = "phone_book_account_name"
        const val ACCOUNT_TYPE = "phone_book_account_type"
        const val SERVER_URL = "server_url"
        const val SERVER_TOKEN = "server_token"
        const val WATERMARK = "sync_watermark"
        const val LAST_SYNC_AT = "last_sync_at"
        const val CALENDAR_ENABLED = "calendar_enabled"
        const val CALENDAR_ID = "calendar_id"
        const val APPOINTMENT_MINUTES = "appointment_minutes"
        const val NO_CALENDAR = -1L
    }
}
