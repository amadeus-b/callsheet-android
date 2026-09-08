package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.ui.geoUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uri.encode is an Android call, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoUriTest {

    private fun business(lat: Double? = null, lng: Double? = null) = Business(
        placeId = "P1", name = "Gartenbau Merten", industry = null, categories = emptyList(),
        street = "Zehentstraße 39", postalCode = "85055", city = "Ingolstadt",
        phone = null, website = null, email = null, contactName = null,
        rating = null, ratingCount = null, closed = false, isTarget = true,
        origin = emptyList(), collectedAt = null, status = Status.NEW, note = null,
        followUpAt = null, updatedAt = "2026-09-08T12:00:00+02:00",
        latitude = lat, longitude = lng,
    )

    @Test
    fun `coordinates put the map on the point and label the pin`() {
        val uri = geoUri(business(48.8059466, 11.4058554), "Zehentstraße 39, 85055 Ingolstadt")!!

        assertTrue(uri, uri.startsWith("geo:48.8059466,11.4058554?q="))
        assertTrue(uri, uri.contains("Gartenbau"))
    }

    @Test
    fun `without coordinates the address is searched instead`() {
        val uri = geoUri(business(), "Zehentstraße 39, 85055 Ingolstadt")!!

        assertTrue(uri, uri.startsWith("geo:0,0?q="))
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        // A latitude without a longitude would land the map on the equator.
        val uri = geoUri(business(lat = 48.8059466), "Zehentstraße 39")!!

        assertTrue(uri, uri.startsWith("geo:0,0?q="))
    }

    @Test
    fun `no coordinates and no address means no link at all`() {
        assertNull(geoUri(business(), null))
        assertNull(geoUri(business(), "  "))
    }

    @Test
    fun `the address form escapes what it is given`() {
        assertEquals("geo:0,0?q=Zehentstra%C3%9Fe%2039", geoUri("Zehentstraße 39"))
    }
}
