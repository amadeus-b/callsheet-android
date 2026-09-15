package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.AttendeeAdd
import io.github.amadeusb.callsheet.data.Attendees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A visit's attendees as a list. Robolectric only for `org.json`. Every address is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttendeesTest {

    @Test
    fun `the list is written as JSON and read back, none is null`() {
        val text = Attendees.format(listOf("test@example.org", "zweite@example.org"))

        assertEquals("[\"test@example.org\",\"zweite@example.org\"]", text)
        assertEquals(listOf("test@example.org", "zweite@example.org"), Attendees.parse(text))
        assertNull(Attendees.format(emptyList()))
        assertNull(Attendees.format(listOf("  ")))
    }

    @Test
    fun `reading drops blanks and duplicates, keeps the order and survives garbage`() {
        assertEquals(
            listOf("a@example.org", "b@example.org"),
            Attendees.parse("[\" a@example.org \", \"\", null, \"A@EXAMPLE.org\", \"b@example.org\"]"),
        )
        assertTrue(Attendees.parse(null).isEmpty())
        assertTrue(Attendees.parse("").isEmpty())
        assertTrue(Attendees.parse("kaputt").isEmpty())
    }

    @Test
    fun `an address is added trimmed, once, and only when it looks like one`() {
        val list = listOf("test@example.org")

        assertEquals(AttendeeAdd(listOf("test@example.org", "zweite@example.org"), null), Attendees.add(list, " zweite@example.org "))
        assertEquals(AttendeeAdd(list, null), Attendees.add(list, "TEST@example.org"))
        assertEquals(AttendeeAdd(list, null), Attendees.add(list, "   "))
        assertEquals(AttendeeAdd(list, Attendees.INVALID), Attendees.add(list, "zweite@example"))
        assertEquals(AttendeeAdd(list, Attendees.INVALID), Attendees.add(list, "zweite example@org.de"))
        assertEquals("Das ist keine gültige E-Mail-Adresse.", Attendees.INVALID)
        // The calendar account organises every visit; it is never an attendee.
        assertEquals(AttendeeAdd(list, Attendees.OWN_ADDRESS), Attendees.add(list, " Christoph@Bauer-KI.de "))
        assertEquals("Die eigene Adresse ist immer dabei.", Attendees.OWN_ADDRESS)
    }

    @Test
    fun `two lists are the same set whatever the order, case and whitespace`() {
        assertTrue(Attendees.sameSet(listOf("a@example.org", "b@example.org"), listOf(" B@example.org", "a@example.org")))
        assertFalse(Attendees.sameSet(listOf("a@example.org"), listOf("a@example.org", "b@example.org")))
        assertTrue(Attendees.sameSet(emptyList(), emptyList()))
        assertTrue(Attendees.contains(listOf("a@example.org"), " A@example.org "))
    }

    @Test
    fun `added and removed are told apart, ignoring case`() {
        val before = listOf("a@example.org", "b@example.org")
        val after = listOf("B@example.org", "c@example.org")

        assertEquals(listOf("c@example.org"), Attendees.added(before, after))
        assertEquals(listOf("a@example.org"), Attendees.removed(before, after))
    }

    @Test
    fun `names are listed up to three, after that shortened`() {
        assertEquals("a@example.org", Attendees.names(listOf("a@example.org")))
        assertEquals("a@example.org, b@example.org", Attendees.names(listOf("a@example.org", "b@example.org")))
        assertEquals(
            "a@example.org, b@example.org, c@example.org",
            Attendees.names(listOf("a@example.org", "b@example.org", "c@example.org")),
        )
        assertEquals(
            "a@example.org, b@example.org und 2 weitere",
            Attendees.names(listOf("a@example.org", "b@example.org", "c@example.org", "d@example.org")),
        )
    }
}
