package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `org.json` is part of the Android framework, hence Robolectric.
 *
 * Every name, address and number in this file is made up. Only the field
 * structure comes from the real source file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImporterTest {

    @Test
    fun `reads the basic fields`() {
        val list = Importer.read(BEISPIEL)
        val b = list.first { it.placeId == "PLACE_A" }

        assertEquals("Elektro Musterhuber GmbH", b.name)
        assertEquals("Elektro", b.industry)
        assertEquals("Musterweg 1", b.street)
        assertEquals("85049", b.postalCode)
        assertEquals("Ingolstadt", b.city)
        assertEquals("+496219900001", b.phone)
        assertEquals("https://example.invalid/", b.website)
        assertEquals("info@example.invalid", b.email)
        assertEquals("Erika Musterhuber", b.contactName)
        assertEquals(4.7, b.rating!!, 0.001)
        assertEquals(12, b.ratingCount)
        assertEquals(listOf("Elektriker", "Handwerk"), b.categories)
        assertEquals(listOf("handwerk-in"), b.origin)
        assertFalse(b.closed)
        assertTrue(b.isTarget)
    }

    @Test
    fun `collectedAt is filled in when the source provides none`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_A" }
        assertTrue(b.collectedAt != null && b.collectedAt!!.startsWith("20"))
    }

    @Test
    fun `a null industry is kept and does not exclude`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_B" }
        assertNull(b.industry)
        assertTrue("Ein null-Gewerk darf die Zielmenge nicht verkleinern", b.isTarget)
    }

    @Test
    fun `a no-target industry sets isTarget to false`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_C" }
        assertEquals("Paketdienst (kein Ziel)", b.industry)
        assertFalse(b.isTarget)
    }

    @Test
    fun `a missing phone number sets isTarget to false`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_D" }
        assertNull(b.phone)
        assertFalse(b.isTarget)
    }

    @Test
    fun `closed businesses are no target`() {
        val dauerhaft = Importer.read(BEISPIEL).first { it.placeId == "PLACE_E" }
        assertTrue(dauerhaft.closed)
        assertFalse(dauerhaft.isTarget)

        val voruebergehend = Importer.read(BEISPIEL).first { it.placeId == "PLACE_F" }
        assertTrue(voruebergehend.closed)
        assertFalse(voruebergehend.isTarget)
    }

    @Test
    fun `entries without a placeId are skipped`() {
        val list = Importer.read(BEISPIEL)
        assertEquals(6, list.size)
        assertTrue(list.none { it.name == "Ohne Schluessel" })
    }

    @Test
    fun `JSON nulls become Kotlin nulls`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_D" }
        assertNull(b.street)
        assertNull(b.city)
        assertNull(b.postalCode)
        assertNull(b.website)
        assertNull(b.email)
        assertNull(b.contactName)
        assertNull(b.rating)
        assertNull(b.ratingCount)
        assertFalse(b.closed)
    }

    @Test
    fun `categories serves as the fallback for alle_kategorien`() {
        val b = Importer.read(BEISPIEL).first { it.placeId == "PLACE_B" }
        assertEquals(listOf("Gartenbauer"), b.categories)
    }

    @Test
    fun `an empty array yields an empty list`() {
        assertTrue(Importer.read("[]").isEmpty())
    }

    private companion object {
        /** Made-up data in the real field structure from docs/data-model.md. */
        const val BEISPIEL = """
        [
          {
            "placeId": "PLACE_A",
            "title": "Elektro Musterhuber GmbH",
            "categoryName": "Elektriker",
            "categories": ["Elektriker"],
            "alle_kategorien": ["Elektriker", "Handwerk"],
            "street": "Musterweg 1",
            "city": "Ingolstadt",
            "postalCode": "85049",
            "state": null,
            "countryCode": "DE",
            "phone": "+49 621 990 0001",
            "phoneUnformatted": "+496219900001",
            "website": "https://example.invalid/",
            "emails": ["info@example.invalid", "zweit@example.invalid"],
            "kontakt": "Erika Musterhuber",
            "kontakt_quelle": "Impressum",
            "totalScore": 4.7,
            "reviewsCount": 12,
            "permanentlyClosed": false,
            "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Elektro"
          },
          {
            "placeId": "PLACE_B",
            "title": "Gartenpflege Beispielhuber",
            "categoryName": "Gartenbauer",
            "categories": ["Gartenbauer"],
            "alle_kategorien": null,
            "street": "Beispielstrasse 7",
            "city": "Eichstaett",
            "postalCode": "85072",
            "phone": "0621 9900002",
            "phoneUnformatted": null,
            "website": null,
            "emails": null,
            "kontakt": null,
            "totalScore": null,
            "reviewsCount": null,
            "permanentlyClosed": false,
            "temporarilyClosed": false,
            "herkunft": ["dienstleistung-in"],
            "gewerk": null
          },
          {
            "placeId": "PLACE_C",
            "title": "Beispiel Paketshop",
            "categories": ["Kurierdienst"],
            "alle_kategorien": ["Kurierdienst"],
            "street": "Testallee 3",
            "city": "Ingolstadt",
            "postalCode": "85051",
            "phone": "+49 621 9900003",
            "phoneUnformatted": "+496219900003",
            "permanentlyClosed": false,
            "temporarilyClosed": false,
            "herkunft": ["logistik-in"],
            "gewerk": "Paketdienst (kein Ziel)"
          },
          {
            "placeId": "PLACE_D",
            "title": "Fliesen Beispielmeier",
            "categories": [],
            "alle_kategorien": [],
            "street": null,
            "city": null,
            "postalCode": null,
            "phone": null,
            "phoneUnformatted": null,
            "website": null,
            "emails": null,
            "kontakt": null,
            "totalScore": null,
            "reviewsCount": null,
            "permanentlyClosed": null,
            "temporarilyClosed": null,
            "herkunft": ["handwerk-in"],
            "gewerk": "Boden/Fliesen"
          },
          {
            "placeId": "PLACE_E",
            "title": "Schreinerei Ausgedacht",
            "categories": ["Schreiner"],
            "alle_kategorien": ["Schreiner"],
            "street": "Holzweg 12",
            "city": "Gaimersheim",
            "postalCode": "85080",
            "phone": "+49 621 9900005",
            "phoneUnformatted": "+496219900005",
            "permanentlyClosed": true,
            "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Holz"
          },
          {
            "placeId": "PLACE_F",
            "title": "Malerbetrieb Fantasie",
            "categories": ["Maler"],
            "alle_kategorien": ["Maler"],
            "street": "Farbgasse 4",
            "city": "Pfaffenhofen",
            "postalCode": "85276",
            "phone": "+49 621 9900006",
            "phoneUnformatted": "+496219900006",
            "permanentlyClosed": false,
            "temporarilyClosed": true,
            "herkunft": ["handwerk-in"],
            "gewerk": "Maler"
          },
          {
            "title": "Ohne Schluessel",
            "categories": [],
            "phone": "+49 621 9900007",
            "phoneUnformatted": "+496219900007",
            "herkunft": [],
            "gewerk": "Kfz"
          }
        ]
        """
    }
}
