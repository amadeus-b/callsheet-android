package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.AddressDraft
import io.github.amadeusb.callsheet.data.Addresses
import io.github.amadeusb.callsheet.data.BusinessAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which address counts for what. Every address here is made up. */
class AddressesTest {

    private fun address(
        id: String,
        position: Int?,
        city: String? = "Musterstadt",
        street: String? = null,
        postalCode: String? = null,
        label: String? = null,
    ) = BusinessAddress(
        id = id, placeId = "P1", label = label,
        street = street, postalCode = postalCode, city = city, position = position,
    )

    @Test
    fun `the main address is the first by position, a missing position last`() {
        val unplaced = address("A3", null)
        val branch = address("A2", 1)
        val head = address("main-P1", 0)

        assertEquals(head, Addresses.main(listOf(unplaced, branch, head)))
        assertEquals(listOf(head, branch, unplaced), Addresses.ordered(listOf(unplaced, branch, head)))
        assertNull(Addresses.main(emptyList()))
    }

    @Test
    fun `a contact person stands for their assigned address, else the main address`() {
        val head = address("main-P1", 0)
        val branch = address("A2", 1)

        assertEquals(branch, Addresses.forContact("A2", listOf(head, branch)))
        assertEquals(head, Addresses.forContact(null, listOf(head, branch)))
    }

    @Test
    fun `an assignment to a row that is not here reads as none`() {
        val head = address("main-P1", 0)

        assertNull(Addresses.assigned("gone", listOf(head)))
        assertEquals(head, Addresses.forContact("gone", listOf(head)))
    }

    @Test
    fun `the search text holds the name and every city, lower-cased`() {
        assertEquals(
            "müller & söhne ingolstadt königsmoos",
            Addresses.searchText("Müller & Söhne", listOf("Ingolstadt", null, " ", "Königsmoos")),
        )
        assertEquals("müller & söhne", Addresses.searchText("Müller & Söhne", emptyList()))
    }

    @Test
    fun `a choice shows the label, else the address`() {
        assertEquals("Filiale", Addresses.name(address("A2", 1, label = "Filiale")))
        assertEquals(
            "Hafenstraße 5, 85001 Hafenstadt",
            Addresses.name(address("A2", 1, street = "Hafenstraße 5", postalCode = "85001", city = "Hafenstadt", label = " ")),
        )
    }

    @Test
    fun `making a row the main address moves it to the top and keeps the rest in order`() {
        val a = AddressDraft(id = "a", city = "Eins")
        val b = AddressDraft(id = "b", city = "Zwei")
        val c = AddressDraft(id = "c", city = "Drei")

        assertEquals(listOf(c, a, b), Addresses.makeMain(listOf(a, b, c), 2))
        assertEquals(listOf(a, b, c), Addresses.makeMain(listOf(a, b, c), 0))
        assertEquals(listOf(a, b, c), Addresses.makeMain(listOf(a, b, c), 5))
    }

    @Test
    fun `a draft with only a label is blank`() {
        assertTrue(AddressDraft(label = "Lager", street = " ").isBlank)
        assertFalse(AddressDraft(postalCode = "85001").isBlank)
    }

    @Test
    fun `rows become drafts in their order, missing values as empty text`() {
        val drafts = Addresses.drafts(listOf(address("A2", 1, label = "Filiale"), address("main-P1", 0, city = null)))

        assertEquals(
            listOf(AddressDraft(id = "main-P1"), AddressDraft(id = "A2", label = "Filiale", city = "Musterstadt")),
            drafts,
        )
    }

    @Test
    fun `the main id carries the place id`() {
        assertEquals("main-P1", Addresses.mainId("P1"))
    }

    @Test
    fun `the drive line rounds kilometres and minutes`() {
        assertEquals("23 km · 21 min", Addresses.driveLine(23_400, 1_260))
        assertEquals("24 km · 22 min", Addresses.driveLine(23_500, 1_290))
    }

    @Test
    fun `the drive line keeps short trips readable`() {
        assertEquals("< 1 km · 1 min", Addresses.driveLine(400, 20))
        assertEquals("< 1 km · 1 min", Addresses.driveLine(999, 59))
        assertEquals("1 km · 1 min", Addresses.driveLine(1000, 60))
    }

    @Test
    fun `the drive line shows hours from sixty minutes on`() {
        assertEquals("98 km · 1 h 05 min", Addresses.driveLine(98_000, 3_900))
        assertEquals("80 km · 1 h 00 min", Addresses.driveLine(80_000, 3_590))
    }

    @Test
    fun `no drive line without both values`() {
        assertNull(Addresses.driveLine(null, 1_260))
        assertNull(Addresses.driveLine(23_400, null))
    }
}
