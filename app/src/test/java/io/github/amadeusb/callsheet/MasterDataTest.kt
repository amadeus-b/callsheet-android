package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.BusinessDraft
import io.github.amadeusb.callsheet.data.MasterData
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which master data changed by hand, and how that is written down.
 * Robolectric only for `org.json`. Every name and number here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterDataTest {

    private fun business(
        name: String = "Elektro Beispiel GmbH",
        phone: String? = "+496219900011",
        industry: String? = "Elektro",
        website: String? = "elektro-beispiel.example",
        email: String? = null,
        contactName: String? = "Erika Beispiel",
    ) = Business(
        placeId = "P1", name = name, industry = industry, categories = emptyList(), city = "Ingolstadt",
        phone = phone, website = website, email = email, contactName = contactName,
        rating = null, ratingCount = null, closed = false, isTarget = true, origin = emptyList(),
        collectedAt = null, status = Status.NEW, note = null, updatedAt = "2026-09-07T10:00:00+02:00",
    )

    @Test
    fun `the editable columns are exactly these`() {
        assertEquals(listOf("name", "phone", "industry", "website", "email", "contact_name"), MasterData.EDITABLE)
    }

    @Test
    fun `the form opened on a business and saved unchanged changes nothing`() {
        val stored = business()

        val changed = MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(MasterData.draft(stored)))

        assertTrue(changed.isEmpty())
    }

    @Test
    fun `only the fields whose value changed are named`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(email = "test@example.org", contactName = "Max Beispiel")

        assertEquals(setOf("email", "contact_name"), MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(draft)))
    }

    @Test
    fun `clearing a field counts as a change`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(website = "  ", phone = "")

        val after = MasterData.fromDraft(draft)

        assertNull(after.website)
        assertNull(after.phone)
        assertEquals(setOf("phone", "website"), MasterData.changedFields(MasterData.of(stored), after))
    }

    @Test
    fun `whitespace and another notation of the same number are no change`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(name = "  Elektro Beispiel GmbH ", phone = "0621 990 0011")

        assertTrue(MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(draft)).isEmpty())
    }

    @Test
    fun `an incomplete number reads as no number, so the caller can refuse it`() {
        assertNull(MasterData.fromDraft(BusinessDraft(name = "Kurz", phone = "0621")).phone)
    }

    @Test
    fun `the list is written sorted and read back`() {
        val text = MasterData.format(setOf("phone", "email"))

        assertEquals("[\"email\",\"phone\"]", text)
        assertEquals(setOf("email", "phone"), MasterData.parse(text))
    }

    @Test
    fun `no fields is no list`() {
        assertNull(MasterData.format(emptySet()))
        assertTrue(MasterData.parse(null).isEmpty())
        assertTrue(MasterData.parse("").isEmpty())
    }

    @Test
    fun `a broken list reads as empty, a name from a later version is kept`() {
        assertTrue(MasterData.parse("kaputt").isEmpty())
        assertEquals(setOf("email"), MasterData.parse("[\"email\", null, \"\"]"))
        assertEquals(setOf("rating"), MasterData.parse("[\"rating\"]"))
    }

    @Test
    fun `the contact name loses its imported label once it was changed by hand`() {
        assertEquals("Ansprechpartner (importiert)", MasterData.contactNameLabel(emptySet()))
        assertEquals("Ansprechpartner (importiert)", MasterData.contactNameLabel(setOf("email", "phone")))
        assertEquals("Ansprechpartner", MasterData.contactNameLabel(setOf("contact_name")))
    }

    @Test
    fun `the draft carries the business's values and no addresses`() {
        val draft = MasterData.draft(business(email = null))

        assertEquals("Elektro Beispiel GmbH", draft.name)
        assertEquals("+496219900011", draft.phone)
        assertEquals("Elektro", draft.industry)
        assertEquals("elektro-beispiel.example", draft.website)
        assertEquals("", draft.email)
        assertEquals("Erika Beispiel", draft.contactName)
        assertTrue(draft.addresses.isEmpty())
    }
}
