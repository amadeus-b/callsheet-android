package io.github.amadeusb.callsheet.contacts

import android.provider.ContactsContract.CommonDataKinds.Phone
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.PhoneDraft
import io.github.amadeusb.callsheet.data.PhoneType

/**
 * A number as the phone book holds it.
 *
 * [label] replaces the type with a text of its own — the business's main number
 * carries one, because DAVx5 has no phone type it would upload as `TYPE=MAIN`.
 * Reading back ignores it.
 */
data class PhoneBookNumber(val number: String, val kind: PhoneType, val label: String? = null)

/**
 * A contact as the phone book hands it over.
 *
 * [version] is the sum of the version numbers of every row of the entry.
 * Android bumps them on each change — when the sum differs from the one last
 * recorded, something in the phone book was touched.
 */
data class PhoneBookContact(
    val sourceId: String,
    val rawContactId: Long,
    val name: String,
    val email: String?,
    val numbers: List<PhoneBookNumber>,
    val version: Int,
)

/**
 * Translation between the app's phone types and the phone book's, plus the rule
 * for how a change in the phone book makes its way back into the app.
 *
 * Deliberately without any Android access, so the rules stay testable.
 */
object ContactMerge {

    /** A number's kind as a phone book type. */
    fun toAndroidType(kind: PhoneType): Int = when (kind) {
        PhoneType.MOBILE -> Phone.TYPE_MOBILE
        PhoneType.WORK -> Phone.TYPE_WORK
        PhoneType.MAIN -> Phone.TYPE_MAIN
        PhoneType.HOME -> Phone.TYPE_HOME
        PhoneType.FAX -> Phone.TYPE_FAX_WORK
        PhoneType.OTHER -> Phone.TYPE_OTHER
    }

    /** The other way round: a phone book type as the app's kind. */
    fun fromAndroidType(type: Int): PhoneType = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> PhoneType.MOBILE
        Phone.TYPE_WORK, Phone.TYPE_COMPANY_MAIN -> PhoneType.WORK
        Phone.TYPE_MAIN -> PhoneType.MAIN
        Phone.TYPE_HOME -> PhoneType.HOME
        Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME, Phone.TYPE_OTHER_FAX -> PhoneType.FAX
        else -> PhoneType.OTHER
    }

    /**
     * What a changed phone book entry contributes back to the app.
     *
     * Only name, email and numbers are read back — the phone book does not know
     * role and note in this form, so those stay as they are. An empty name in
     * the phone book is ignored; it would leave the app's entry unusable.
     *
     * The business's main number and email sit on every entry of the business;
     * [businessPhone] and [businessEmail] keep them from being taken over as
     * the person's own.
     *
     * @return the draft to save, or null when nothing needs changing.
     */
    fun merge(
        existing: Contact,
        fromPhoneBook: PhoneBookContact,
        businessPhone: String? = null,
        businessEmail: String? = null,
    ): ContactDraft? {
        val name = fromPhoneBook.name.trim().ifEmpty { existing.name }

        // Every entry of a business carries its email when the person has none
        // of their own — that one is not the person's to take over.
        val readEmail = fromPhoneBook.email?.trim().orEmpty()
        val businessMail = businessEmail?.trim().orEmpty()
        val email = if (existing.email.isNullOrBlank() && businessMail.isNotEmpty() &&
            readEmail.equals(businessMail, ignoreCase = true)
        ) "" else readEmail

        // Likewise the main number, unless the person holds it in the app too.
        val main = businessPhone?.trim()?.takeIf { it.isNotEmpty() }
        val holdsMain = main != null && existing.numbers.any { sameNumber(it.number, main) }
        val numbers = fromPhoneBook.numbers
            .filter { main == null || holdsMain || !sameNumber(it.number, main) }
            .map { PhoneDraft(number = it.number, kind = it.kind) }

        val unchanged = name == existing.name &&
            email == existing.email.orEmpty() &&
            numbers.map { it.number.forComparison() to it.kind } ==
            existing.numbers.map { it.number.forComparison() to it.kind }
        if (unchanged) return null

        return ContactDraft(
            id = existing.id,
            placeId = existing.placeId,
            name = name,
            role = existing.role.orEmpty(),
            email = email,
            note = existing.note.orEmpty(),
            // With no numbers in the phone book the app's own stay put: more
            // likely an entry read incompletely than someone who deleted every
            // number of a person and kept the entry.
            numbers = numbers.ifEmpty {
                existing.numbers.map { PhoneDraft(id = it.id, number = it.number, kind = it.kind) }
            },
        )
    }
}

/** Notations like „030 12…“ and „+4930 12…“ must not count as a change. */
internal fun String.forComparison(): String = filter { it.isDigit() }.takeLast(9)

/** Whether two notations are the same number. */
internal fun sameNumber(a: String, b: String): Boolean = a.forComparison() == b.forComparison()
