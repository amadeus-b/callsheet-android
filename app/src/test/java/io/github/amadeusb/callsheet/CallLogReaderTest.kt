package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.CallLogReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain number comparison only. Reading the log itself needs a device.
 * Every number here is made up.
 */
class CallLogReaderTest {

    @Test
    fun `the same number in the same notation matches`() {
        assertTrue(CallLogReader.matches("+496219947615", "+496219947615"))
    }

    @Test
    fun `country code and trunk prefix are the same number`() {
        assertTrue(CallLogReader.matches("+496219947615", "06219947615"))
        assertTrue(CallLogReader.matches("06219947615", "+496219947615"))
    }

    @Test
    fun `spaces and separators do not get in the way`() {
        assertTrue(CallLogReader.matches("+49 621 9947615", "+496219947615"))
        assertTrue(CallLogReader.matches("0621 / 99 476 15", "+496219947615"))
        assertTrue(CallLogReader.matches("+49-621-9947615", "06219947615"))
    }

    @Test
    fun `different numbers do not match`() {
        assertFalse(CallLogReader.matches("+496219947615", "+496219947616"))
        assertFalse(CallLogReader.matches("+4989123456789", "+496219947615"))
    }

    @Test
    fun `null and empty never match`() {
        assertFalse(CallLogReader.matches(null, "+496219947615"))
        assertFalse(CallLogReader.matches("", "+496219947615"))
        assertFalse(CallLogReader.matches("   ", "+496219947615"))
        assertFalse(CallLogReader.matches("+496219947615", ""))
    }

    @Test
    fun `withheld or too short numbers do not match`() {
        assertFalse(CallLogReader.matches("-1", "+496219947615"))
        assertFalse(CallLogReader.matches("110", "+496219947615"))
        assertFalse(CallLogReader.matches("unknown", "+496219947615"))
    }
}
