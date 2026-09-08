package io.github.amadeusb.callsheet

import android.provider.ContactsContract.CommonDataKinds.Phone
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.PhoneNumber
import io.github.amadeusb.callsheet.contacts.ContactMerge
import io.github.amadeusb.callsheet.contacts.PhoneBookContact
import io.github.amadeusb.callsheet.contacts.PhoneBookNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules by which a change in the phone book makes its way back into the
 * app. Every name and number here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactMergeTest {

    private fun contact(
        name: String = "Frau Beispiel",
        email: String? = "beispiel@example.org",
        numbers: List<PhoneNumber> = listOf(
            PhoneNumber("n1", "+491701234567", PhoneType.MOBILE),
        ),
    ) = Contact(
        id = "a1",
        placeId = "P1",
        name = name,
        role = "Bauleitung",
        email = email,
        note = "vormittags erreichbar",
        numbers = numbers,
        updatedAt = "2026-01-01T10:00:00+01:00",
    )

    private fun fromPhoneBook(
        name: String = "Frau Beispiel",
        email: String? = "beispiel@example.org",
        numbers: List<PhoneBookNumber> = listOf(
            PhoneBookNumber("+491701234567", PhoneType.MOBILE),
        ),
    ) = PhoneBookContact(
        sourceId = "a1",
        rawContactId = 7,
        name = name,
        email = email,
        numbers = numbers,
        version = 3,
    )

    @Test
    fun `an unchanged entry means nothing to do`() {
        assertNull(ContactMerge.merge(contact(), fromPhoneBook()))
    }

    @Test
    fun `a different notation of the same number is not a change`() {
        val phoneBook = fromPhoneBook(
            numbers = listOf(PhoneBookNumber("0170 123 45 67", PhoneType.MOBILE))
        )
        assertNull(ContactMerge.merge(contact(), phoneBook))
    }

    @Test
    fun `a changed name and a new number are taken over`() {
        val phoneBook = fromPhoneBook(
            name = "Frau Beispiel-Neu",
            numbers = listOf(
                PhoneBookNumber("+491701234567", PhoneType.MOBILE),
                PhoneBookNumber("+496219947615", PhoneType.WORK),
            ),
        )

        val draft = ContactMerge.merge(contact(), phoneBook)!!

        assertEquals("a1", draft.id)
        assertEquals("P1", draft.placeId)
        assertEquals("Frau Beispiel-Neu", draft.name)
        assertEquals(2, draft.numbers.size)
        assertEquals(PhoneType.WORK, draft.numbers[1].kind)
        // The phone book does not know role and note in this form — they stay.
        assertEquals("Bauleitung", draft.role)
        assertEquals("vormittags erreichbar", draft.note)
    }

    @Test
    fun `a deleted email is taken over`() {
        val draft = ContactMerge.merge(contact(), fromPhoneBook(email = null))!!
        assertEquals("", draft.email)
    }

    @Test
    fun `an empty name in the phone book does not overwrite the name`() {
        val phoneBook = fromPhoneBook(
            name = "   ",
            numbers = listOf(PhoneBookNumber("+496219947615", PhoneType.WORK)),
        )
        val draft = ContactMerge.merge(contact(), phoneBook)!!
        assertEquals("Frau Beispiel", draft.name)
    }

    @Test
    fun `an entry without any numbers leaves the app's numbers alone`() {
        val phoneBook = fromPhoneBook(name = "Frau Beispiel-Neu", numbers = emptyList())
        val draft = ContactMerge.merge(contact(), phoneBook)!!
        assertEquals("Frau Beispiel-Neu", draft.name)
        assertEquals(1, draft.numbers.size)
        assertEquals("+491701234567", draft.numbers.single().number)
    }

    @Test
    fun `phone types translate in both directions`() {
        PhoneType.entries.forEach { kind ->
            val roundTrip = ContactMerge.fromAndroidType(ContactMerge.toAndroidType(kind))
            assertEquals(kind, roundTrip)
        }
        // Anything unknown from the phone book does not vanish.
        assertEquals(PhoneType.OTHER, ContactMerge.fromAndroidType(Phone.TYPE_PAGER))
    }
}
