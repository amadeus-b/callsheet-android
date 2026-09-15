package io.github.amadeusb.callsheet.contacts

import android.content.Context
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the phone book and the app in step.
 *
 * Nothing is written unless the user switched the feature on and picked an
 * address book account. Without both, everything here does nothing — working in
 * the app must never depend on it.
 *
 * The phone book → app direction is limited to entries the app created itself.
 * Changes made there to people saved by hand win the next time the record is
 * opened or the business is written; everything else is written anew by the
 * app. New people are still created in the app.
 */
class ContactStore(
    private val context: Context,
    private val repo: Repository,
    private val preferences: Preferences,
) {

    private val active: Boolean
        get() = preferences.phoneBookEnabled && preferences.account != null

    // Coming back from a call opens the record and writes the business at the
    // same time. Interleaved, a write could record the version of an edit that
    // was never read back, or a read-back could merge an entry that is already
    // stale. Not re-entrant: nothing that holds it may call another locked function.
    private val lock = Mutex()

    /**
     * Writes every entry a business makes (see [PhoneBookEntries]) and removes
     * the `place_id` entry once it is no longer one of them — people saved by
     * hand have taken its place.
     *
     * Edits made in the phone book to people saved by hand are read back first;
     * otherwise the write would overwrite them. Not for [savedInApp], the
     * contact the user has just saved in the app: the version also moves when
     * DAVx5 synchronises, so a moved version would let the entry's older content
     * undo that edit. For the person just edited in the app, the app wins.
     *
     * @return how many entries were written; one that failed does not count.
     */
    suspend fun persistBusiness(business: Business, savedInApp: String? = null): Int {
        val account = preferences.account ?: return 0
        if (!active) return 0
        return lock.withLock {
            val others = repo.contacts(business.placeId).filter { it.id != savedInApp }
            readBackUnlocked(account, business, others)
            val entries = PhoneBookEntries.forBusiness(
                business, repo.contacts(business.placeId), repo.addresses(business.placeId),
            )
            var written = 0
            for (entry in entries) {
                // A failed write keeps the last version: recording none would make
                // the next opening take the entry's old content for an edit.
                val version = PhoneBook.write(context, account, entry) ?: continue
                written++
                // Only people saved by hand are read back, so only they need it.
                if (entry.sourceId != business.placeId) repo.setContactVersion(entry.sourceId, version)
            }
            if (entries.none { it.sourceId == business.placeId }) {
                PhoneBook.delete(context, account, business.placeId)
            }
            written
        }
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
        return lock.withLock {
            // A write may have run while waiting and moved the versions on; the
            // list the caller loaded before would merge against old ones.
            readBackUnlocked(account, business, repo.contacts(contacts.first().placeId))
        }
    }

    /** The body of [readBack]; the caller holds [lock] and must not take it again. */
    private suspend fun readBackUnlocked(
        account: AddressBookAccount,
        business: Business?,
        contacts: List<Contact>,
    ): Boolean {
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
