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
 * Changes made there win the next time the record is opened; new people are
 * still created in the app.
 */
class ContactStore(
    private val context: Context,
    private val repo: Repository,
    private val preferences: Preferences,
) {

    private val active: Boolean
        get() = preferences.phoneBookEnabled && preferences.account != null

    /** Writes a contact into the phone book and records the version it left. */
    suspend fun persistContact(contact: Contact, business: Business?) {
        val account = preferences.account ?: return
        if (!active) return
        val version = PhoneBook.write(
            context = context,
            account = account,
            fields = ContactFields(
                sourceId = contact.id,
                name = contact.name,
                organization = business?.name,
                role = contact.role,
                email = contact.email,
                note = contact.note,
                numbers = contact.numbers.toPhoneBookNumbers(),
            ),
        )
        repo.setContactVersion(contact.id, version)
    }

    /**
     * Stores the business itself as a contact — otherwise a call back from the
     * switchboard stays an unknown number. With no main number there is nothing
     * to store.
     */
    suspend fun persistBusiness(business: Business) {
        val account = preferences.account ?: return
        if (!active) return
        val number = business.phone?.takeIf { it.isNotBlank() } ?: return
        PhoneBook.write(
            context = context,
            account = account,
            fields = ContactFields(
                sourceId = business.placeId,
                name = business.name,
                organization = business.name,
                role = business.industry,
                email = business.email,
                note = null,
                numbers = businessNumber(number),
            ),
        )
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
    suspend fun readBack(contacts: List<Contact>): Boolean {
        val account = preferences.account ?: return false
        if (!active || contacts.isEmpty()) return false

        var taken = false
        for (contact in contacts) {
            val fromPhoneBook = PhoneBook.read(context, account, contact.id) ?: continue
            // The same version means: untouched since the last merge.
            if (fromPhoneBook.version == contact.contactVersion) continue

            val draft = ContactMerge.merge(contact, fromPhoneBook)
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
