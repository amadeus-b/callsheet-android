package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.CallFlow
import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFlowTest {

    @Test
    fun `a null duration proposes nothing and says why`() {
        val v = CallFlow.suggestion(null)
        assertNull(v.status)
        assertNull(v.followUpIso)
        assertTrue(v.hint.isNotBlank())
    }

    @Test
    fun `a duration of 0 proposes no answer plus a follow-up`() {
        val v = CallFlow.suggestion(0)
        assertEquals(Status.NO_ANSWER, v.status)
        assertNotNull(v.followUpIso)
        assertTrue(v.hint.isNotBlank())
    }

    @Test
    fun `the proposed follow-up lies in the future and is readable`() {
        val before = System.currentTimeMillis()
        val v = CallFlow.suggestion(0)
        val target = Clock.millis(v.followUpIso)
        assertNotNull(target)
        assertTrue(target!! > before)
        assertTrue(Clock.zdt(target).hour in 8..17)
    }

    @Test
    fun `a conversation that took place gets no status proposal`() {
        val v = CallFlow.suggestion(137)
        assertNull(v.status)
        assertNull(v.followUpIso)
        assertTrue(v.hint.contains("2:17"))
    }

    @Test
    fun `even one second counts as a conversation`() {
        val v = CallFlow.suggestion(1)
        assertNull(v.status)
        assertNull(v.followUpIso)
    }

    @Test
    fun `the proposal for duration 0 uses a different time of day`() {
        val now = System.currentTimeMillis()
        val suggestedTime = Clock.millis(CallFlow.suggestion(0).followUpIso)!!
        val currentSection = FollowUp.section(Clock.zdt(now).toLocalTime())
        val targetSection = FollowUp.section(Clock.zdt(suggestedTime).toLocalTime())
        assertNotEquals(currentSection, targetSection)
    }
}
