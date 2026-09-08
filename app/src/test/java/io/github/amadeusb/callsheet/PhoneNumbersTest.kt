package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain JUnit — [PhoneNumbers] needs no Android runtime. */
class PhoneNumbersTest {

    @Test
    fun `phoneUnformatted is preferred`() {
        assertEquals(
            "+496219947615",
            PhoneNumbers.normalize("+49 621 99474615", "+496219947615"),
        )
    }

    @Test
    fun `phone serves as the fallback`() {
        assertEquals("+496219947615", PhoneNumbers.normalize("+49 621 9947615", null))
        assertEquals("+496219947615", PhoneNumbers.normalize("+49 (621) 9947-615", ""))
    }

    @Test
    fun `national notation becomes E164`() {
        assertEquals("+496219947615", PhoneNumbers.normalize(null, "06219947615"))
        assertEquals("+496219947615", PhoneNumbers.normalize("0621 / 9947 615", null))
    }

    @Test
    fun `a leading double zero becomes a plus`() {
        assertEquals("+496219947615", PhoneNumbers.normalize(null, "00496219947615"))
        assertEquals("+43123456789", PhoneNumbers.normalize(null, "0043 123 456789"))
    }

    @Test
    fun `other countries are left as they are`() {
        assertEquals("+41445678901", PhoneNumbers.normalize(null, "+41 44 567 89 01"))
        assertEquals("+12125550147", PhoneNumbers.normalize("+1 (212) 555-0147", null))
    }

    @Test
    fun `a bracketed trunk prefix after the country code is dropped`() {
        assertEquals("+496219947615", PhoneNumbers.normalize(null, "+49 (0)621 9947615"))
    }

    @Test
    fun `numbers written without a trunk prefix count as German`() {
        assertEquals("+496219947615", PhoneNumbers.normalize(null, "621 9947615"))
    }

    @Test
    fun `empty and unusable values yield null`() {
        assertNull(PhoneNumbers.normalize(null, null))
        assertNull(PhoneNumbers.normalize("", "   "))
        assertNull(PhoneNumbers.normalize("keine Angabe", null))
        assertNull(PhoneNumbers.normalize(null, "0621"))
        assertNull(PhoneNumbers.normalize(null, "000000"))
    }

    @Test
    fun `the comparable form is the last eight digits`() {
        assertEquals("99476150", PhoneNumbers.comparableForm("+4962199476150"))
        assertEquals("99476150", PhoneNumbers.comparableForm("062199476150"))
    }

    @Test
    fun `the same number in different notations compares equal`() {
        val fromImport = PhoneNumbers.normalize(null, "+49 621 9947615")
        val fromCallLog = "06219947615"
        assertEquals(PhoneNumbers.comparableForm(fromImport), PhoneNumbers.comparableForm(fromCallLog))
    }

    @Test
    fun `numbers that are too short have no comparable form`() {
        assertNull(PhoneNumbers.comparableForm(null))
        assertNull(PhoneNumbers.comparableForm(""))
        assertNull(PhoneNumbers.comparableForm("1234567"))
    }
}
