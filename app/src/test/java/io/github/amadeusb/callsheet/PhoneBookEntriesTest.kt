package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.contacts.MAIN_NUMBER_LABEL
import io.github.amadeusb.callsheet.contacts.PersonName
import io.github.amadeusb.callsheet.contacts.PhoneBookEntries
import io.github.amadeusb.callsheet.contacts.PhoneBookWebsite
import io.github.amadeusb.callsheet.contacts.PostalAddress
import io.github.amadeusb.callsheet.contacts.WebsiteKind
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.PhoneNumber
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One entry per person, the company on every one. Every name, number and
 * address here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBookEntriesTest {

    private fun business(
        placeId: String = "P1",
        phone: String? = "+498412345678",
        contactName: String? = null,
        industry: String? = "Bau",
        rating: Double? = 4.5,
        ratingCount: Int? = 12,
        origin: List<String> = listOf("lauf-a"),
        website: String? = "https://example.org/",
        email: String? = "info@example.org",
    ) = Business(
        placeId = placeId, name = "Muster Fliesen", industry = industry, categories = emptyList(),
        street = null, postalCode = null, city = "Musterstadt",
        phone = phone, website = website, email = email, contactName = contactName,
        rating = rating, ratingCount = ratingCount, closed = false, isTarget = true,
        origin = origin, collectedAt = null, status = Status.NEW, note = null,
        updatedAt = "2026-09-14T12:00:00+02:00",
    )

    private val mainAddress = BusinessAddress("main-P1", "P1", null, "Musterweg 1", "85000", "Musterstadt", position = 0)
    private val branchAddress = BusinessAddress("A2", "P1", "Filiale", "Hafenstraße 5", "85001", "Hafenstadt", position = 1)

    private fun entries(
        business: Business,
        contacts: List<Contact>,
        addresses: List<BusinessAddress> = listOf(mainAddress),
    ) = PhoneBookEntries.forBusiness(business, contacts, addresses)

    private fun contact(
        id: String = "c1",
        name: String = "Erika Beispiel",
        role: String? = null,
        email: String? = null,
        note: String? = null,
        numbers: List<PhoneNumber> = listOf(PhoneNumber("n1", "+491701234567", PhoneType.MOBILE)),
    ) = Contact(
        id = id, placeId = "P1", name = name, role = role, email = email, note = note,
        numbers = numbers, updatedAt = "2026-09-14T12:00:00+02:00",
    )

    // ---- target set ----

    @Test
    fun `nobody at all makes one company-only entry`() {
        val entry = entries(business(), emptyList()).single()
        assertEquals("P1", entry.sourceId)
        assertNull(entry.name)
        assertEquals("Muster Fliesen", entry.organization)
        assertEquals("Bau", entry.role)
        assertEquals(listOf("+498412345678"), entry.numbers.map { it.number })
        assertEquals(listOf(MAIN_NUMBER_LABEL), entry.numbers.map { it.label })
    }

    @Test
    fun `the imported person takes the place_id entry`() {
        val entry = entries(business(contactName = "Max Mustermann"), emptyList()).single()
        assertEquals("P1", entry.sourceId)
        assertEquals(PersonName("Max", "Mustermann"), entry.name)
        assertEquals("Muster Fliesen", entry.organization)
    }

    @Test
    fun `imported and hand-saved people get one entry each`() {
        val entries = entries(business(contactName = "Max Mustermann"), listOf(contact()))
        assertEquals(listOf("P1", "c1"), entries.map { it.sourceId })
        assertEquals(PersonName("Erika", "Beispiel"), entries[1].name)
        assertEquals("Muster Fliesen", entries[1].organization)
    }

    @Test
    fun `hand-saved people only leave no place_id entry`() {
        val entries = entries(business(), listOf(contact(), contact(id = "c2", name = "Tom Test")))
        assertEquals(listOf("c1", "c2"), entries.map { it.sourceId })
    }

    @Test
    fun `an imported name that was also saved by hand counts once`() {
        val entries = entries(
            business(contactName = "  erika BEISPIEL "),
            listOf(contact(name = "Erika Beispiel")),
        )
        assertEquals(listOf("c1"), entries.map { it.sourceId })
    }

    @Test
    fun `a doubled space inside the name does not make a second person`() {
        val imported = entries(
            business(contactName = "Erika  Beispiel"),
            listOf(contact(name = "Erika Beispiel")),
        )
        assertEquals(listOf("c1"), imported.map { it.sourceId })

        val saved = entries(
            business(contactName = "Erika Beispiel"),
            listOf(contact(name = "Erika \t Beispiel")),
        )
        assertEquals(listOf("c1"), saved.map { it.sourceId })
    }

    @Test
    fun `without a main number only hand-saved people are written, with their own numbers`() {
        assertTrue(entries(business(phone = null, contactName = "Max Mustermann"), emptyList()).isEmpty())

        val entry = entries(business(phone = null), listOf(contact())).single()
        assertEquals(listOf("+491701234567"), entry.numbers.map { it.number })
    }

    // ---- fields ----

    @Test
    fun `a person carries their own numbers and the main number with its label`() {
        val entry = entries(business(), listOf(contact())).single()
        assertEquals(listOf("+491701234567", "+498412345678"), entry.numbers.map { it.number })
        assertNull(entry.numbers[0].label)
        assertEquals(MAIN_NUMBER_LABEL, entry.numbers[1].label)
    }

    @Test
    fun `the main number is not written twice`() {
        val same = listOf(PhoneNumber("n1", "0841 2345678", PhoneType.WORK))
        val entry = entries(business(), listOf(contact(numbers = same))).single()
        assertEquals(1, entry.numbers.size)
        assertNull(entry.numbers.single().label)
    }

    @Test
    fun `role and email fall back to the business`() {
        val plain = entries(business(), listOf(contact())).single()
        assertEquals("Bau", plain.role)
        assertEquals("info@example.org", plain.email)

        val own = entries(
            business(), listOf(contact(role = "Bauleitung", email = "erika@example.org")),
        ).single()
        assertEquals("Bauleitung", own.role)
        assertEquals("erika@example.org", own.email)
    }

    @Test
    fun `the note lists industry, rating and origin`() {
        val entry = entries(business(origin = listOf("lauf-a", "lauf-b")), emptyList()).single()
        assertEquals("Branche: Bau · Bewertung: 4,5 (12) · Herkunft: lauf-a, lauf-b", entry.note)
    }

    @Test
    fun `the note leaves out what is missing and puts a person's own note in front`() {
        val sparse = entries(business(rating = null, ratingCount = null), emptyList()).single()
        assertEquals("Branche: Bau · Herkunft: lauf-a", sparse.note)

        val empty = entries(
            business(industry = null, rating = null, origin = emptyList()), emptyList(),
        ).single()
        assertNull(empty.note)

        val person = entries(business(rating = 5.0, ratingCount = 2), listOf(contact(note = "vormittags"))).single()
        assertEquals("vormittags\nBranche: Bau · Bewertung: 5,0 (2) · Herkunft: lauf-a", person.note)
    }

    @Test
    fun `an address row with street, postal code and city empty is left out`() {
        val full = entries(business(), emptyList()).single()
        assertEquals(listOf(PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland")), full.addresses)

        val blank = BusinessAddress("A3", "P1", "Lager", null, " ", null, position = 1)
        val none = entries(business(), emptyList(), listOf(blank)).single()
        assertTrue(none.addresses.isEmpty())
    }

    @Test
    fun `an imported business links to its place on the map`() {
        val entry = entries(business(), emptyList()).single()
        assertEquals(
            listOf(
                PhoneBookWebsite("https://example.org/", WebsiteKind.WORK),
                PhoneBookWebsite(
                    "https://www.google.com/maps/search/?api=1&query=Muster+Fliesen&query_place_id=P1",
                    WebsiteKind.OTHER,
                ),
            ),
            entry.websites,
        )
    }

    @Test
    fun `a hand-entered business links to its address, or not at all`() {
        val withAddress = entries(business(placeId = "manual:x", website = null), emptyList()).single()
        assertEquals(
            listOf(
                PhoneBookWebsite(
                    "https://www.google.com/maps/search/?api=1&query=Musterweg+1%2C+85000+Musterstadt",
                    WebsiteKind.OTHER,
                ),
            ),
            withAddress.websites,
        )

        val without = entries(business(placeId = "manual:x", website = null), emptyList(), emptyList()).single()
        assertTrue(without.websites.isEmpty())
    }

    // ---- addresses ----

    @Test
    fun `an assigned person carries only their address`() {
        val entry = entries(business(), listOf(contact().copy(addressId = "A2")), listOf(mainAddress, branchAddress)).single()
        assertEquals(
            listOf(PostalAddress("Hafenstraße 5", "85001", "Hafenstadt", "Deutschland", label = "Filiale")),
            entry.addresses,
        )
    }

    @Test
    fun `a person without an assignment, or assigned to a missing row, carries every address, main first`() {
        val unassigned = entries(business(), listOf(contact()), listOf(branchAddress, mainAddress)).single()
        assertEquals(listOf("Musterweg 1", "Hafenstraße 5"), unassigned.addresses.map { it.street })

        val dangling = entries(business(), listOf(contact().copy(addressId = "gone")), listOf(branchAddress, mainAddress)).single()
        assertEquals(listOf("Musterweg 1", "Hafenstraße 5"), dangling.addresses.map { it.street })
    }

    @Test
    fun `the company entry carries every address`() {
        val entry = entries(business(), emptyList(), listOf(mainAddress, branchAddress)).single()
        assertEquals(listOf(null, "Filiale"), entry.addresses.map { it.label })
    }

    @Test
    fun `a hand-entered business's map link follows the person's address`() {
        val entry = entries(
            business(placeId = "manual:x", website = null),
            listOf(contact().copy(addressId = "A2")),
            listOf(mainAddress, branchAddress),
        ).single()
        assertEquals(
            listOf(PhoneBookWebsite("https://www.google.com/maps/search/?api=1&query=Hafenstra%C3%9Fe+5%2C+85001+Hafenstadt", WebsiteKind.OTHER)),
            entry.websites,
        )
    }
}
