package io.github.amadeusb.callsheet.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One sync at a time, and no request lost.
 *
 * A request made while a run is in progress waits for it and then gets a run
 * that starts after the request — the running one was built before whatever
 * the request is about. Requests that queue behind the same run share the next
 * one instead of each starting their own. A run that throws covers nobody.
 *
 * Not reentrant: calling [run] from inside a block hangs, because the block
 * holds the gate it would wait for. A block only ever launches further syncs.
 */
class SyncGate<T> {
    private val mutex = Mutex()
    private val lock = Any()
    private var requested = 0L
    /** How many requests had been made when the last successful run started. */
    private var covered = 0L
    private var last: Result<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val mine = synchronized(lock) { ++requested }
        return mutex.withLock {
            val done = last
            if (covered >= mine && done != null) return@withLock done.getOrThrow()
            val startedAt = synchronized(lock) { requested }
            val result = block()
            covered = startedAt
            last = Result.success(result)
            result
        }
    }
}
