package io.github.amadeusb.callsheet.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import io.github.amadeusb.callsheet.data.PhoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An address book account on the phone, as the Contacts app shows it. */
data class AddressBookAccount(val name: String, val type: String)

/** The label the address book shows a business's main number under. */
const val MAIN_NUMBER_LABEL = "Hauptadresse"

/** A person's name as the phone book stores it. */
data class PersonName(val given: String?, val family: String) {

    /** Given and family name as one line, the way the app shows the person. */
    val display: String
        get() = listOfNotNull(given, family).joinToString(" ")

    companion object {
        /**
         * The last word becomes the family name, the rest the given name. A
         * single word is all family name. Null for a blank name.
         */
        fun of(name: String): PersonName? {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            if (words.size == 1) return PersonName(null, words.single())
            return PersonName(words.dropLast(1).joinToString(" "), words.last())
        }
    }
}

/** A business address as it goes into the phone book. */
data class PostalAddress(
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    /** „Filiale" … Written as a custom type; null keeps the work type. */
    val label: String? = null,
)

enum class WebsiteKind { WORK, OTHER }

/** A link on an entry: the business's website, or the map. */
data class PhoneBookWebsite(val url: String, val kind: WebsiteKind)

/** A contact as the app writes it into the phone book. */
data class ContactFields(
    /** The app's stable id — it stays the same across updates. */
    val sourceId: String,
    /**
     * Null for a company-only entry. It gets no name row at all, so Android
     * and DAVx5 both fall back to the organisation.
     */
    val name: PersonName?,
    val organization: String?,
    val role: String?,
    val email: String?,
    val note: String?,
    val numbers: List<PhoneBookNumber>,
    /** Main address first. Empty for none. */
    val addresses: List<PostalAddress> = emptyList(),
    val websites: List<PhoneBookWebsite> = emptyList(),
)

/**
 * Storing contacts in the device's phone book.
 *
 * The app only ever writes into the address book account the user picked —
 * typically a CardDAV address book managed by DAVx5. DAVx5 handles the
 * synchronisation to the server; the app speaks no CardDAV itself.
 *
 * Its own entries are recognised by `SOURCE_ID`: it holds the contact's id, or
 * the business's `place_id`. Other people's contacts are never touched.
 */
object PhoneBook {

    /** The app manages only these row types; everything else stays untouched. */
    private val OWN_TYPES = arrayOf(
        StructuredName.CONTENT_ITEM_TYPE,
        Phone.CONTENT_ITEM_TYPE,
        Email.CONTENT_ITEM_TYPE,
        Organization.CONTENT_ITEM_TYPE,
        Note.CONTENT_ITEM_TYPE,
        StructuredPostal.CONTENT_ITEM_TYPE,
        Website.CONTENT_ITEM_TYPE,
    )

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean =
        canRead(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * The device's address book accounts, derived from the existing contacts.
     *
     * Deliberately through the contacts rather than the AccountManager: since
     * Android 8 an app only sees its own accounts there, whereas this yields
     * exactly the address books it can actually write to.
     */
    suspend fun accounts(context: Context): List<AddressBookAccount> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val found = LinkedHashSet<AddressBookAccount>()
        runCatching {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
                "${RawContacts.DELETED} = 0",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val type = c.getString(1) ?: continue
                    found.add(AddressBookAccount(name, type))
                }
            }
        }
        found.toList()
    }

    /**
     * Creates the contact or brings it up to the app's state.
     *
     * @return the entry's new version number, or null when nothing could be
     *   written (missing permission, no account).
     */
    suspend fun write(
        context: Context,
        account: AddressBookAccount,
        fields: ContactFields,
    ): Int? = withContext(Dispatchers.IO) {
        if (!canWrite(context)) return@withContext null
        val result = runCatching {
            val existing = rawContactId(context, account, fields.sourceId)
            val ops = ArrayList<ContentProviderOperation>()

            val rawId: Long
            if (existing == null) {
                ops.add(
                    ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.SOURCE_ID, fields.sourceId)
                        .build()
                )
                rawId = -1
            } else {
                rawId = existing
                // Only clear the rows the app manages — addresses or birthdays
                // added elsewhere stay where they are.
                ops.add(
                    ContentProviderOperation.newDelete(Data.CONTENT_URI)
                        .withSelection(
                            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN " +
                                OWN_TYPES.joinToString(",", "(", ")") { "?" },
                            arrayOf(rawId.toString()) + OWN_TYPES,
                        )
                        .build()
                )
            }

            fun row(): ContentProviderOperation.Builder {
                val b = ContentProviderOperation.newInsert(Data.CONTENT_URI)
                if (rawId < 0) b.withValueBackReference(Data.RAW_CONTACT_ID, 0)
                else b.withValue(Data.RAW_CONTACT_ID, rawId)
                return b
            }

            dataRows(fields).forEach { values -> ops.add(row().withValues(values).build()) }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            version(context, account, fields.sourceId)
        }
        result.getOrNull()
    }

    /**
     * The data rows of an entry, without the raw contact id — [write] adds that.
     * Kept apart so the shape of an entry can be tested without a contacts
     * provider.
     */
    internal fun dataRows(fields: ContactFields): List<ContentValues> {
        val rows = ArrayList<ContentValues>()
        fun row(mimeType: String, fill: ContentValues.() -> Unit) {
            rows += ContentValues().apply {
                put(Data.MIMETYPE, mimeType)
                fill()
            }
        }

        fields.name?.let { name ->
            // Given and family name set explicitly: with only a display name
            // Android splits a company like a person.
            row(StructuredName.CONTENT_ITEM_TYPE) {
                put(StructuredName.DISPLAY_NAME, name.display)
                put(StructuredName.GIVEN_NAME, name.given)
                put(StructuredName.FAMILY_NAME, name.family)
            }
        }
        fields.numbers.forEach { number ->
            row(Phone.CONTENT_ITEM_TYPE) {
                put(Phone.NUMBER, number.number)
                if (number.label != null) {
                    put(Phone.TYPE, Phone.TYPE_CUSTOM)
                    put(Phone.LABEL, number.label)
                } else {
                    put(Phone.TYPE, ContactMerge.toAndroidType(number.kind))
                }
            }
        }
        fields.email?.takeIf { it.isNotBlank() }?.let { mail ->
            row(Email.CONTENT_ITEM_TYPE) {
                put(Email.ADDRESS, mail)
                put(Email.TYPE, Email.TYPE_WORK)
            }
        }
        if (!fields.organization.isNullOrBlank() || !fields.role.isNullOrBlank()) {
            row(Organization.CONTENT_ITEM_TYPE) {
                put(Organization.COMPANY, fields.organization)
                put(Organization.TITLE, fields.role)
                put(Organization.TYPE, Organization.TYPE_WORK)
            }
        }
        fields.addresses.forEach { address ->
            row(StructuredPostal.CONTENT_ITEM_TYPE) {
                put(StructuredPostal.STREET, address.street)
                put(StructuredPostal.POSTCODE, address.postalCode)
                put(StructuredPostal.CITY, address.city)
                put(StructuredPostal.COUNTRY, address.country)
                if (address.label != null) {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                    put(StructuredPostal.LABEL, address.label)
                } else {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
                }
            }
        }
        fields.websites.forEach { site ->
            row(Website.CONTENT_ITEM_TYPE) {
                put(Website.URL, site.url)
                put(
                    Website.TYPE,
                    when (site.kind) {
                        WebsiteKind.WORK -> Website.TYPE_WORK
                        WebsiteKind.OTHER -> Website.TYPE_OTHER
                    },
                )
            }
        }
        fields.note?.takeIf { it.isNotBlank() }?.let { note ->
            row(Note.CONTENT_ITEM_TYPE) { put(Note.NOTE, note) }
        }
        return rows
    }

    /** Reads the entry back exactly as the phone book currently holds it. */
    suspend fun read(
        context: Context,
        account: AddressBookAccount,
        sourceId: String,
    ): PhoneBookContact? = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext null
        runCatching {
            val rawId = rawContactId(context, account, sourceId) ?: return@runCatching null
            val version = version(context, account, sourceId) ?: return@runCatching null

            var name = ""
            var email: String? = null
            val numbers = ArrayList<PhoneBookNumber>()
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2),
                "${Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val value = c.getString(1) ?: continue
                    when (c.getString(0)) {
                        StructuredName.CONTENT_ITEM_TYPE -> name = value
                        Email.CONTENT_ITEM_TYPE -> if (email == null) email = value
                        Phone.CONTENT_ITEM_TYPE -> numbers.add(
                            PhoneBookNumber(
                                number = value,
                                kind = ContactMerge.fromAndroidType(
                                    if (c.isNull(2)) Phone.TYPE_OTHER else c.getInt(2)
                                ),
                            )
                        )
                    }
                }
            }

            PhoneBookContact(
                sourceId = sourceId,
                rawContactId = rawId,
                name = name,
                email = email,
                numbers = numbers,
                version = version,
            )
        }.getOrNull()
    }

    /** Removes the app's entry from the phone book. */
    suspend fun delete(context: Context, account: AddressBookAccount, sourceId: String) =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) return@withContext
            runCatching {
                val rawId = rawContactId(context, account, sourceId) ?: return@runCatching
                // Without CALLER_IS_SYNC_ADAPTER the entry stays around marked
                // as deleted until DAVx5 has uploaded the deletion.
                context.contentResolver.delete(
                    ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
                    null,
                    null,
                )
            }
            Unit
        }

    /** The entry's version number; it goes up on every change in the phone book. */
    private fun version(context: Context, account: AddressBookAccount, sourceId: String): Int? =
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.VERSION),
            selection(),
            arrayOf(account.name, account.type, sourceId),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    private fun rawContactId(context: Context, account: AddressBookAccount, sourceId: String): Long? =
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            selection(),
            arrayOf(account.name, account.type, sourceId),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun selection(): String =
        "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.DELETED} = 0"
}

/** A contact's numbers as phone book numbers. */
fun List<io.github.amadeusb.callsheet.data.PhoneNumber>.toPhoneBookNumbers(): List<PhoneBookNumber> =
    map { PhoneBookNumber(it.number, it.kind) }

/** A business's main number goes into the phone book under [MAIN_NUMBER_LABEL]. */
fun businessNumber(number: String): List<PhoneBookNumber> =
    listOf(PhoneBookNumber(number, PhoneType.MAIN, label = MAIN_NUMBER_LABEL))
