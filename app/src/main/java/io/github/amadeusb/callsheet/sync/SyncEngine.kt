package io.github.amadeusb.callsheet.sync

import io.github.amadeusb.callsheet.contacts.Preferences
import io.github.amadeusb.callsheet.data.Clock
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed class SyncResult {
    /** Went through, everything that was open is up. */
    object Ok : SyncResult()

    /** No server configured, or a sync is already running — the app runs as it always did. */
    object Idle : SyncResult()

    /**
     * Stopped at the round limit while rows were still marked. Everything
     * that did go through is real — the watermark and the cleared marks
     * stand — but the caller must not treat this as a completed sync: no
     * new `lastSyncAt` is set.
     */
    object Incomplete : SyncResult()

    data class Failed(val kind: FailureKind, val message: String) : SyncResult()
}

/**
 * Drives the exchange block by block.
 *
 * The watermark only moves after a block came back whole, and the marks are
 * cleared only for the rows that were actually sent. An abort therefore costs
 * the block, never the stock.
 *
 * `RATE_LIMITED` (429) and `TOO_LARGE` (413) both end the run immediately,
 * the same way any other failure does: the exception leaves the loop before
 * another block is requested, so the server is never hammered and a body
 * that keeps growing past the limit cannot spin forever.
 */
class SyncEngine(private val store: SyncStore, private val prefs: Preferences) {

    private val running = AtomicBoolean(false)

    fun sync(transport: Transport): SyncResult {
        if (prefs.serverUrl == null || prefs.serverToken == null) return SyncResult.Idle
        if (!running.compareAndSet(false, true)) return SyncResult.Idle

        try {
            var rounds = 0
            while (true) {
                val outgoing = store.pending(BLOCK)
                val payload = JSONObject(outgoing.toString()).put("seit", prefs.watermark)

                val response = transport.post(payload)

                store.apply(response)
                store.clearPending(outgoing)
                // The watermark only ever moves forward. A stale or
                // misbehaving server sending a lower value must not put the
                // client behind where it already stood.
                val stand = response.optInt("stand", prefs.watermark)
                if (stand > prefs.watermark) prefs.watermark = stand

                val more = response.optBoolean("weitere", false) || store.pendingCount() > 0
                if (!more) {
                    prefs.lastSyncAt = Clock.now()
                    return SyncResult.Ok
                }
                if (++rounds >= MAX_ROUNDS) return SyncResult.Incomplete
            }
        } catch (failure: HttpFailure) {
            return SyncResult.Failed(failure.kind, failure.message ?: "Abgleich gescheitert.")
        } catch (malformed: JSONException) {
            // A response that parsed as JSON but not into the shape the
            // contract promises — e.g. a field that should be an object
            // turns out to be something else. Same treatment as a body that
            // was not JSON at all: a visible failure, not a crash.
            return SyncResult.Failed(FailureKind.BAD_RESPONSE, "Die Antwort des Servers ließ sich nicht lesen.")
        } catch (failure: IOException) {
            return SyncResult.Failed(FailureKind.NETWORK, "Kein Netz.")
        } finally {
            running.set(false)
        }
    }

    private companion object {
        const val BLOCK = 500

        /** A stop against a server that keeps saying „more" — 250 blocks are 125 000 rows. */
        const val MAX_ROUNDS = 250
    }
}
