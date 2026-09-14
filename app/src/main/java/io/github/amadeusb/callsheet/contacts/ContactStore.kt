package io.github.amadeusb.callsheet.contacts

import android.content.Context
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Repository

/**
 * Keeps the phone book and the app in step.
 *
 * Nothing is written unless the user switched the feature on and picked an
 * address book account. Without both, everything here does nothing — working in
 * the app must never depend on it.
 *
 * The phone book → app direction is limited to entries the app created itself.
 * Changes made there to people saved by hand win the next time the record is
 * opened; everything else is written anew by the app. New people are still
 * created in the app.
 */
class ContactStore(
    private val context: Context,
    private val repo: Repository,
    private val preferences: Preferences,
) {

    private val active: Boolean
        get() = preferences.phoneBookEnabled && preferences.account != null

    /**
     * Writes every entry a business makes (see [PhoneBookEntries]) and removes
     * the `place_id` entry once it is no longer one of them — people saved by
     * hand have taken its place.
     *
     * @return how many entries were written.
     */
    suspend fun persistBusiness(business: Business): Int {
        val account = preferences.account ?: return 0
        if (!active) return 0
        val entries = PhoneBookEntries.forBusiness(business, repo.contacts(business.placeId))
        for (entry in entries) {
            val version = PhoneBook.write(context, account, entry)
            // Only people saved by hand are read back, so only they need it.
            if (entry.sourceId != business.placeId) repo.setContactVersion(entry.sourceId, version)
        }
        if (entries.none { it.sourceId == business.placeId }) {
            PhoneBook.delete(context, account, business.placeId)
        }
        return entries.size
    }

    suspend fun deleteContact(id: String) {
        val account = preferences.account ?: return
        if (!active) return
        PhoneBook.delete(context, account, id)
    }

    /**
     * Reads back whatever was changed on the app's own entries in the phone book.
     *
     * @return true when something was taken over and the record should reload.
     */
    suspend fun readBack(business: Business?, contacts: List<Contact>): Boolean {
        val account = preferences.account ?: return false
        if (!active || contacts.isEmpty()) return false

        var taken = false
        for (contact in contacts) {
            val fromPhoneBook = PhoneBook.read(context, account, contact.id) ?: continue
            // The same version means: untouched since the last merge.
            if (fromPhoneBook.version == contact.contactVersion) continue

            val draft = ContactMerge.merge(contact, fromPhoneBook, business?.phone, business?.email)
            if (draft == null) {
                // Only the version moved, through synchronisation for instance.
                repo.setContactVersion(contact.id, fromPhoneBook.version)
                continue
            }
            repo.saveContact(draft).onSuccess {
                repo.setContactVersion(contact.id, fromPhoneBook.version)
                taken = true
            }
        }
        return taken
    }
}
