package io.github.amadeusb.callsheet.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import io.github.amadeusb.callsheet.data.PhoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An address book account on the phone, as the Contacts app shows it. */
data class AddressBookAccount(val name: String, val type: String)

/** A contact as the app writes it into the phone book. */
data class ContactFields(
    /** The app's stable id — it stays the same across updates. */
    val sourceId: String,
    val name: String,
    val organization: String?,
    val role: String?,
    val email: String?,
    val note: String?,
    val numbers: List<PhoneBookNumber>,
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

            ops.add(
                row()
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, fields.name)
                    .build()
            )
            fields.numbers.forEach { number ->
                ops.add(
                    row()
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, number.number)
                        .withValue(Phone.TYPE, ContactMerge.toAndroidType(number.kind))
                        .build()
                )
            }
            fields.email?.takeIf { it.isNotBlank() }?.let { mail ->
                ops.add(
                    row()
                        .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, mail)
                        .withValue(Email.TYPE, Email.TYPE_WORK)
                        .build()
                )
            }
            if (!fields.organization.isNullOrBlank() || !fields.role.isNullOrBlank()) {
                ops.add(
                    row()
                        .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                        .withValue(Organization.COMPANY, fields.organization)
                        .withValue(Organization.TITLE, fields.role)
                        .withValue(Organization.TYPE, Organization.TYPE_WORK)
                        .build()
                )
            }
            fields.note?.takeIf { it.isNotBlank() }?.let { note ->
                ops.add(
                    row()
                        .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                        .withValue(Note.NOTE, note)
                        .build()
                )
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            version(context, account, fields.sourceId)
        }
        result.getOrNull()
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

/** A business's main number goes into the phone book as the switchboard. */
fun businessNumber(number: String): List<PhoneBookNumber> =
    listOf(PhoneBookNumber(number, PhoneType.MAIN))
