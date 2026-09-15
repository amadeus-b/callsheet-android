package io.github.amadeusb.callsheet

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import io.github.amadeusb.callsheet.contacts.ContactFields
import io.github.amadeusb.callsheet.contacts.MAIN_NUMBER_LABEL
import io.github.amadeusb.callsheet.contacts.PersonName
import io.github.amadeusb.callsheet.contacts.PhoneBook
import io.github.amadeusb.callsheet.contacts.PhoneBookNumber
import io.github.amadeusb.callsheet.contacts.PhoneBookWebsite
import io.github.amadeusb.callsheet.contacts.PostalAddress
import io.github.amadeusb.callsheet.contacts.WebsiteKind
import io.github.amadeusb.callsheet.contacts.businessNumber
import io.github.amadeusb.callsheet.data.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shape of a phone book entry as rows. Every name, number and address here
 * is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBookRowsTest {

    private fun fields(
        name: PersonName? = PersonName("Max", "Mustermann"),
        numbers: List<PhoneBookNumber> = listOf(PhoneBookNumber("+491701234567", PhoneType.MOBILE)),
        addresses: List<PostalAddress> = listOf(PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland")),
        websites: List<PhoneBookWebsite> = listOf(
            PhoneBookWebsite("https://example.org/", WebsiteKind.WORK),
            PhoneBookWebsite("https://www.google.com/maps/search/?api=1&query=x", WebsiteKind.OTHER),
        ),
    ) = ContactFields(
        sourceId = "P1",
        name = name,
        organization = "Muster Fliesen",
        role = "Bau",
        email = "info@example.org",
        note = "Branche: Bau",
        numbers = numbers,
        addresses = addresses,
        websites = websites,
    )

    private fun List<ContentValues>.of(mimeType: String) =
        filter { it.getAsString(Data.MIMETYPE) == mimeType }

    @Test
    fun `a person is written with given and family name`() {
        val name = PhoneBook.dataRows(fields()).of(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Max", name.getAsString(StructuredName.GIVEN_NAME))
        assertEquals("Mustermann", name.getAsString(StructuredName.FAMILY_NAME))
        assertEquals("Max Mustermann", name.getAsString(StructuredName.DISPLAY_NAME))
    }

    @Test
    fun `a company-only entry has no name row, so the organisation shows`() {
        val rows = PhoneBook.dataRows(fields(name = null))
        assertTrue(rows.of(StructuredName.CONTENT_ITEM_TYPE).isEmpty())
        assertEquals(
            "Muster Fliesen",
            rows.of(Organization.CONTENT_ITEM_TYPE).single().getAsString(Organization.COMPANY),
        )
    }

    @Test
    fun `the main number is written with its label`() {
        val rows = PhoneBook.dataRows(fields(numbers = businessNumber("+498412345678")))
        val phone = rows.of(Phone.CONTENT_ITEM_TYPE).single()
        assertEquals(Phone.TYPE_CUSTOM, phone.getAsInteger(Phone.TYPE))
        assertEquals(MAIN_NUMBER_LABEL, phone.getAsString(Phone.LABEL))
        assertEquals("Hauptadresse", MAIN_NUMBER_LABEL)
    }

    @Test
    fun `a number without a label keeps its own type`() {
        val phone = PhoneBook.dataRows(fields()).of(Phone.CONTENT_ITEM_TYPE).single()
        assertEquals(Phone.TYPE_MOBILE, phone.getAsInteger(Phone.TYPE))
        assertNull(phone.getAsString(Phone.LABEL))
    }

    @Test
    fun `address, websites, email and note are written`() {
        val rows = PhoneBook.dataRows(fields())

        val postal = rows.of(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("Musterweg 1", postal.getAsString(StructuredPostal.STREET))
        assertEquals("85000", postal.getAsString(StructuredPostal.POSTCODE))
        assertEquals("Musterstadt", postal.getAsString(StructuredPostal.CITY))
        assertEquals("Deutschland", postal.getAsString(StructuredPostal.COUNTRY))
        assertEquals(StructuredPostal.TYPE_WORK, postal.getAsInteger(StructuredPostal.TYPE))

        val sites = rows.of(Website.CONTENT_ITEM_TYPE)
        assertEquals(listOf(Website.TYPE_WORK, Website.TYPE_OTHER), sites.map { it.getAsInteger(Website.TYPE) })
        assertEquals("https://example.org/", sites[0].getAsString(Website.URL))

        assertEquals("info@example.org", rows.of(Email.CONTENT_ITEM_TYPE).single().getAsString(Email.ADDRESS))
        assertEquals("Branche: Bau", rows.of(Note.CONTENT_ITEM_TYPE).single().getAsString(Note.NOTE))
    }

    @Test
    fun `no address means no postal row`() {
        val rows = PhoneBook.dataRows(fields(addresses = emptyList(), websites = emptyList()))
        assertTrue(rows.of(StructuredPostal.CONTENT_ITEM_TYPE).isEmpty())
        assertTrue(rows.of(Website.CONTENT_ITEM_TYPE).isEmpty())
    }

    @Test
    fun `a name splits at the last word`() {
        assertEquals(PersonName("Max", "Mustermann"), PersonName.of("Max Mustermann"))
        assertEquals(PersonName("Anna Maria", "Beispiel"), PersonName.of("  Anna   Maria Beispiel "))
        assertEquals(PersonName(null, "Aleks"), PersonName.of("Aleks"))
        assertNull(PersonName.of("   "))
    }

    @Test
    fun `every address is a postal row, a labelled one under its label`() {
        val rows = PhoneBook.dataRows(
            fields(
                addresses = listOf(
                    PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland"),
                    PostalAddress("Hafenstraße 5", "85001", "Hafenstadt", "Deutschland", label = "Filiale"),
                ),
            )
        )

        val postal = rows.of(StructuredPostal.CONTENT_ITEM_TYPE)
        assertEquals(2, postal.size)
        assertEquals(StructuredPostal.TYPE_WORK, postal[0].getAsInteger(StructuredPostal.TYPE))
        assertNull(postal[0].getAsString(StructuredPostal.LABEL))
        assertEquals("Hafenstraße 5", postal[1].getAsString(StructuredPostal.STREET))
        assertEquals(StructuredPostal.TYPE_CUSTOM, postal[1].getAsInteger(StructuredPostal.TYPE))
        assertEquals("Filiale", postal[1].getAsString(StructuredPostal.LABEL))
    }
}
