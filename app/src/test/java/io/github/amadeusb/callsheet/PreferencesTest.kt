package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.contacts.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesTest {

    private val preferences = Preferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `fresh settings have no calendar and 60 minutes`() {
        assertFalse(preferences.calendarEnabled)
        assertNull(preferences.calendarId)
        assertEquals(60, preferences.appointmentMinutes)
    }

    @Test
    fun `the chosen duration is remembered`() {
        preferences.appointmentMinutes = 90

        assertEquals(90, preferences.appointmentMinutes)
    }

    @Test
    fun `the calendar can be set and cleared`() {
        preferences.calendarId = 7L
        assertEquals(7L, preferences.calendarId)

        preferences.calendarId = null
        assertNull(preferences.calendarId)
    }

    @Test
    fun `resetting the mail template brings back the defaults`() {
        val subject = preferences.mailTemplateSubject
        val body = preferences.mailTemplateBody
        preferences.mailTemplateSubject = "Eigener Betreff"
        preferences.mailTemplateBody = ""

        preferences.resetMailTemplate()

        assertEquals(subject, preferences.mailTemplateSubject)
        assertEquals(body, preferences.mailTemplateBody)
    }

    @Test
    fun `the default mail leaves the name open and closes with the signature`() {
        val body = preferences.mailTemplateBody

        assertTrue(body.startsWith("Guten Tag [Name],\n\n"))
        assertTrue(body.contains("Ihren Termin: https://bauer-ki.de/termin/\n\n"))
        assertTrue(body.contains("per E-Mail oder Telefon.\n\nMit bestem Gruß\nChristoph Bauer\n\n"))
        assertTrue(body.endsWith("Geschäftsführer: Christoph Bauer · USt-IdNr.: DE452785562"))
    }
}
