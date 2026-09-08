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

    private companion object {
        const val PHONE_BOOK_ENABLED = "phone_book_enabled"
        const val ACCOUNT_NAME = "phone_book_account_name"
        const val ACCOUNT_TYPE = "phone_book_account_type"
    }
}
