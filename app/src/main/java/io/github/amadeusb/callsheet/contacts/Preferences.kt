package io.github.amadeusb.callsheet.contacts

import android.content.Context

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

    private companion object {
        const val PHONE_BOOK_ENABLED = "phone_book_enabled"
        const val ACCOUNT_NAME = "phone_book_account_name"
        const val ACCOUNT_TYPE = "phone_book_account_type"
        const val SERVER_URL = "server_url"
        const val SERVER_TOKEN = "server_token"
        const val WATERMARK = "sync_watermark"
        const val LAST_SYNC_AT = "last_sync_at"
    }
}
