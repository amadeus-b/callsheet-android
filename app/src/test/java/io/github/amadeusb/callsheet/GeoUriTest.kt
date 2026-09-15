package io.github.amadeusb.callsheet

import android.net.Uri
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.ui.geoUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uri.encode is an Android call, hence Robolectric. Every address here is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoUriTest {

    private fun address(
        lat: Double? = null,
        lng: Double? = null,
        street: String? = "Zehentstraße 39",
        postalCode: String? = "85055",
        city: String? = "Ingolstadt",
    ) = BusinessAddress(
        id = "main-P1", placeId = "P1", label = null,
        street = street, postalCode = postalCode, city = city,
        latitude = lat, longitude = lng, position = 0,
    )

    @Test
    fun `coordinates put the map on the point and label the pin`() {
        val uri = geoUri("Gartenbau Merten", address(48.8059466, 11.4058554))!!

        assertTrue(uri, uri.startsWith("geo:48.8059466,11.4058554?q="))
        assertTrue(uri, uri.contains("Gartenbau"))
    }

    @Test
    fun `without coordinates the address is searched instead`() {
        val uri = geoUri("Gartenbau Merten", address())!!

        assertEquals("geo:0,0?q=" + Uri.encode("Zehentstraße 39, 85055 Ingolstadt"), uri)
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        // A latitude without a longitude would land the map on the equator.
        val uri = geoUri("Gartenbau Merten", address(lat = 48.8059466))!!

        assertTrue(uri, uri.startsWith("geo:0,0?q="))
    }

    @Test
    fun `no coordinates and no address means no link at all`() {
        assertNull(geoUri("Gartenbau Merten", address(street = null, postalCode = " ", city = null)))
    }

    @Test
    fun `the address form escapes what it is given`() {
        assertEquals("geo:0,0?q=Zehentstra%C3%9Fe%2039", geoUri("Zehentstraße 39"))
    }
}
