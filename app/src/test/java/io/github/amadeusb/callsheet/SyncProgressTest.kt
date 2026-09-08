package io.github.amadeusb.callsheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The upload bar. Nothing here talks to a server — what is worth checking is the
 * arithmetic, and above all that "nothing to count" stays distinguishable from
 * "nothing done yet".
 */
class SyncProgressTest {

    @Test
    fun `a run with nothing to upload has no progress to show`() {
        // Only fetching. The server does not say in advance how much it holds,
        // so a determinate bar here would be an invention.
        assertNull(SyncUiState(uploadRemaining = 0, uploadTotal = 0).uploadProgress)
    }

    @Test
    fun `a run that has sent nothing yet stands at zero, not at nothing`() {
        assertEquals(0f, SyncUiState(uploadRemaining = 2085, uploadTotal = 2085).uploadProgress!!, 0.0001f)
    }

    @Test
    fun `progress follows what is left`() {
        assertEquals(0.5f, SyncUiState(uploadRemaining = 1000, uploadTotal = 2000).uploadProgress!!, 0.0001f)
    }

    @Test
    fun `an emptied queue is a finished upload`() {
        assertEquals(1f, SyncUiState(uploadRemaining = 0, uploadTotal = 2085).uploadProgress!!, 0.0001f)
    }
}
