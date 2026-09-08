package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.contacts.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
