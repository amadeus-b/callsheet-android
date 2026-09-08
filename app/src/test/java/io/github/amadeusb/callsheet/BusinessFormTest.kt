package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Filter
import io.github.amadeusb.callsheet.data.ORIGIN_MANUAL
import io.github.amadeusb.callsheet.data.MANUAL_PREFIX
import io.github.amadeusb.callsheet.data.BusinessDraft
import io.github.amadeusb.callsheet.data.Repository
import io.github.amadeusb.callsheet.data.Status
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Businesses entered by hand — a referral, a business card.
 *
 * Every name and number here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusinessFormTest {

    private lateinit var repo: Repository

    @Before
    fun aufbau() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("callsheet.db")
        repo = Repository(ctx)
    }

    @Test
    fun `a created business appears in the work list straight away`() = runTest {
        val id = repo.create(
            BusinessDraft(
                name = "Dachdecker Erfunden",
                phone = "0621 9900099",
                industry = "Dach",
                city = "Ingolstadt",
                origin = "Empfehlung von einem Bekannten",
            )
        ).getOrThrow()

        assertTrue(id.startsWith(MANUAL_PREFIX))

        val b = repo.business(id)!!
        assertEquals("Dachdecker Erfunden", b.name)
        assertEquals("+496219900099", b.phone)
        assertEquals(Status.NEW, b.status)
        assertTrue(b.isTarget)
        assertNotNull(b.collectedAt)

        // The default view shows it without any further action.
        assertTrue(repo.list(Filter()).any { it.placeId == id })
    }

    @Test
    fun `the origin is recorded`() = runTest {
        val id = repo.create(
            BusinessDraft(name = "Maler Beispiel", origin = "Visitenkarte, Messe Ingolstadt")
        ).getOrThrow()

        val b = repo.business(id)!!
        assertEquals(listOf(ORIGIN_MANUAL, "Visitenkarte, Messe Ingolstadt"), b.origin)
    }

    @Test
    fun `without an origin note at least the hand-entered marker remains`() = runTest {
        val id = repo.create(BusinessDraft(name = "Ohne Angabe")).getOrThrow()
        assertEquals(listOf(ORIGIN_MANUAL), repo.business(id)!!.origin)
    }

    @Test
    fun `without a name nothing is created`() = runTest {
        val e = repo.create(BusinessDraft(name = "   ", phone = "0621 9900098"))
        assertTrue(e.isFailure)
        assertEquals(0, repo.count(Filter(status = emptySet(), onlyTargets = false)))
    }

    @Test
    fun `created without a phone number, but no target`() = runTest {
        val id = repo.create(BusinessDraft(name = "Noch ohne Nummer", industry = "Elektro")).getOrThrow()
        val b = repo.business(id)!!
        assertNull(b.phone)
        assertFalse(b.isTarget)
        // The default view shows targets only, so it does not appear there …
        assertFalse(repo.list(Filter()).any { it.placeId == id })
        // … but it does through the filter.
        assertTrue(repo.list(Filter(onlyTargets = false)).any { it.placeId == id })
    }

    @Test
    fun `a no-target industry stays a non-target even entered by hand`() = runTest {
        val id = repo.create(
            BusinessDraft(name = "Paketshop Beispiel", phone = "+49 621 9900097", industry = "Paketdienst (kein Ziel)")
        ).getOrThrow()
        assertFalse(repo.business(id)!!.isTarget)
    }

    @Test
    fun `an incomplete number is rejected`() = runTest {
        val e = repo.create(BusinessDraft(name = "Kurz", phone = "0621"))
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull()!!.message!!.contains("unvollständig"))
    }

    @Test
    fun `the same number cannot be created twice`() = runTest {
        repo.create(BusinessDraft(name = "Erster Eintrag", phone = "+49 621 9900096")).getOrThrow()
        // Different notation, same number.
        val e = repo.create(BusinessDraft(name = "Zweiter Eintrag", phone = "0621 9900096"))
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull()!!.message!!.contains("Erster Eintrag"))
        assertEquals(1, repo.count(Filter(status = emptySet(), onlyTargets = false)))
    }

    @Test
    fun `the search finds hand-entered businesses`() = runTest {
        repo.create(BusinessDraft(name = "Gebrüder Kläranlagen", city = "Königsmoos")).getOrThrow()
        val matches = repo.list(Filter(status = emptySet(), onlyTargets = false, search = "kläranlagen"))
        assertEquals(1, matches.size)
    }

    @Test
    fun `an import leaves hand-entered businesses untouched`() = runTest {
        val id = repo.create(
            BusinessDraft(name = "Selbst gefunden", phone = "0621 9900095", industry = "Elektro")
        ).getOrThrow()
        repo.setStatus(id, Status.APPOINTMENT)
        repo.setNote(id, "Rueckruf Montag")

        val e = repo.import(IMPORT.byteInputStream())
        assertEquals(1, e.new)
        assertEquals(0, e.updated)

        val b = repo.business(id)!!
        assertEquals(Status.APPOINTMENT, b.status)
        assertEquals("Rueckruf Montag", b.note)
        assertEquals("Selbst gefunden", b.name)
        assertEquals(2, repo.count(Filter(status = emptySet(), onlyTargets = false)))
    }

    @Test
    fun `the industry shows up among the filter values`() = runTest {
        repo.create(BusinessDraft(name = "Neues Gewerk", industry = "Schornsteinfeger", city = "Kösching"))
            .getOrThrow()
        assertTrue(repo.industries().contains("Schornsteinfeger"))
        assertTrue(repo.cities().contains("Kösching"))
    }

    @Test
    fun `a hand-entered business with a number belongs in the phone book`() = runTest {
        repo.import(IMPORT.byteInputStream())
        val id = repo.create(
            BusinessDraft(name = "Fliesen Erfunden", phone = "0621 9900094")
        ).getOrThrow()

        val forPhoneBook = repo.businessesForPhoneBook().map { it.placeId }
        assertTrue(forPhoneBook.contains(id))
        // The imported stock has a number too, but it is research material.
        assertFalse(forPhoneBook.contains("P1"))
    }

    @Test
    fun `a hand-entered business without a number stays out of the phone book`() = runTest {
        val id = repo.create(BusinessDraft(name = "Noch ohne Nummer")).getOrThrow()
        assertFalse(repo.businessesForPhoneBook().map { it.placeId }.contains(id))
    }

    private companion object {
        const val IMPORT = """
        [
          {
            "placeId": "P1",
            "title": "Elektro Beispiel GmbH",
            "categories": ["Elektriker"],
            "alle_kategorien": ["Elektriker"],
            "street": "Musterweg 1", "city": "Ingolstadt", "postalCode": "85049",
            "phone": "+49 621 990 0011", "phoneUnformatted": "+496219900011",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Elektro"
          }
        ]
        """
    }
}
