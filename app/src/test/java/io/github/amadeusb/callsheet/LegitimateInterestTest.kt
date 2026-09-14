package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.LegitimateInterest
import io.github.amadeusb.callsheet.data.MANUAL_PREFIX
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegitimateInterestTest {

    @Test
    fun `names the industry and the public source for imported businesses`() {
        val text = LegitimateInterest.answer("PLACE_A", "Elektro")
        assertTrue(text.contains("als Betrieb im Bereich Elektro hier in der Region"))
        assertTrue(text.contains("öffentlichen Eintrag im Netz"))
    }

    @Test
    fun `without an industry it stays general`() {
        val text = LegitimateInterest.answer("PLACE_B", "  ")
        assertTrue(text.contains("Sie als Betrieb hier in der Region"))
    }

    @Test
    fun `claims no public source for hand-entered businesses`() {
        val text = LegitimateInterest.answer(MANUAL_PREFIX + "1", "Elektro")
        assertFalse(text.contains("Eintrag im Netz"))
        assertTrue(text.contains("Ingolstadt. Wenn das"))
    }
}
