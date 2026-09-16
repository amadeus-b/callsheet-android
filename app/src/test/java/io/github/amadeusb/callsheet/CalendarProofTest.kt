package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.calendar.CalendarProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarProofTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a device that never found a visit has no proof, and what it found is kept`() {
        val proof = CalendarProof(ctx)
        proof.latest = null
        assertNull(proof.latest)

        proof.latest = 1234L

        assertEquals(1234L, CalendarProof(ctx).latest)
    }
}
