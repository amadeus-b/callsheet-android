package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.sync.SyncGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncGateTest {

    @Test
    fun `requests one after another each get a run`() = runTest {
        val gate = SyncGate<Int>()
        var runs = 0

        gate.run { ++runs }
        gate.run { ++runs }

        assertEquals(2, runs)
    }

    @Test
    fun `a request made during a run is not lost, it gets a run after it`() = runTest {
        val gate = SyncGate<Int>()
        val release = CompletableDeferred<Unit>()
        var runs = 0

        val first = async { gate.run { runs++; release.await(); runs } }
        yield()
        val second = async { gate.run { ++runs } }
        yield()
        release.complete(Unit)

        assertEquals(1, first.await())
        assertEquals(2, second.await())
        assertEquals(2, runs)
    }

    @Test
    fun `requests queued behind the same run share the next one`() = runTest {
        val gate = SyncGate<Int>()
        val release = CompletableDeferred<Unit>()
        var runs = 0

        val first = async { gate.run { runs++; release.await(); runs } }
        yield()
        val second = async { gate.run { ++runs } }
        val third = async { gate.run { ++runs } }
        yield()
        release.complete(Unit)

        first.await()
        assertEquals(2, second.await())
        assertEquals(2, third.await())
        assertEquals(2, runs)
    }

    @Test
    fun `a run that failed covers nobody waiting for it`() = runTest {
        val gate = SyncGate<Int>()
        val release = CompletableDeferred<Unit>()
        var runs = 0

        val failing = async {
            runCatching { gate.run { runs++; release.await(); error("offline") } }
        }
        yield()
        val waiting = async { gate.run { ++runs } }
        yield()
        release.complete(Unit)

        failing.await()
        assertEquals(2, waiting.await())
    }
}
