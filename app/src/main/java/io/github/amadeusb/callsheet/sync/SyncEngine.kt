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
 *
 * A row the server names in `rejected` keeps its mark (see
 * [SyncStore.clearPending]) so it is not lost, but that also means it is
 * still dirty when [SyncStore.pendingCount] is checked below. Continuing the
 * loop just because rows are still marked would turn a row the server keeps
 * rejecting into 250 rounds of resending it, every single call to [sync] —
 * quietly hammering the server on every automatic run. The loop instead only
 * continues on a full block: if the last block sent held fewer rows than
 * [BLOCK], every currently dirty row was already offered this round, so
 * nothing would be gained by asking again before the next sync.
 *
 * That bounds the retries by the number of blocks a run takes, not by
 * [MAX_ROUNDS] — but it does not confine a rejected row to being tried only
 * once per run. [SyncStore.pending] has no ordering, so a row rejected in an
 * earlier block of the same run can be selected into a later one and sent
 * again before the run ends; a row that goes through on that second attempt
 * is exactly what should happen. What the loop does guarantee: a rejected
 * row stays marked, is never silently dropped, and — should it keep failing
 * — is retried only as many times as there are blocks, never spun on its own
 * up to the round limit.
 */
class SyncEngine(private val store: SyncStore, private val prefs: Preferences) {

    private val running = AtomicBoolean(false)

    /**
     * Runs one exchange with the server.
     *
     * [onProgress] is called with how many rows are still waiting to go up and
     * how many were waiting when this run began — after every round, and once
     * before the first. Both zero means there is nothing to upload and only the
     * download is left, which has no total to count against: the server does not
     * say how much it holds until it stops saying "more".
     *
     * [onApplied] receives, per block, the appointments that came down or were
     * deleted, so the caller can bring the calendar along. Called on the sync
     * thread, after the block's transaction.
     */
    fun sync(
        transport: Transport,
        onProgress: (remaining: Int, total: Int) -> Unit = { _, _ -> },
        onApplied: (AppliedAppointments) -> Unit = {},
    ): SyncResult {
        if (prefs.serverUrl == null || prefs.serverToken == null) return SyncResult.Idle
        if (!running.compareAndSet(false, true)) return SyncResult.Idle

        try {
            // Once, on the first sync that runs on schema 4 — see
            // Preferences.refetchedForAppointments. Fetching everything again is
            // safe: apply lets the newer version win and drops what a tombstone
            // covers, so rows this device already holds change nothing.
            if (!prefs.refetchedForAppointments) {
                prefs.watermark = 0
                prefs.refetchedForAppointments = true
            }
            // Once more, on the first sync that runs on schema 6 — see
            // Preferences.refetchedForCallbacks. The callbacks a 1.4.0 app
            // stored without their kind come down again at a standstill, and
            // apply fills the gaps.
            if (!prefs.refetchedForCallbacks) {
                prefs.watermark = 0
                prefs.refetchedForCallbacks = true
            }
            // Once more, on the first sync that runs on schema 7 — see
            // Preferences.refetchedForAddresses. The addresses a 1.5.x app
            // skipped come down, and contacts get their address_id filled.
            if (!prefs.refetchedForAddresses) {
                prefs.watermark = 0
                prefs.refetchedForAddresses = true
            }
            var rounds = 0
            val total = store.pendingCount()
            onProgress(total, total)
            while (true) {
                val outgoing = store.pending(BLOCK)
                val payload = JSONObject(outgoing.toString()).put("since", prefs.watermark)

                val response = transport.post(payload)

                val applied = store.apply(response)
                if (!applied.isEmpty()) onApplied(applied)
                store.clearPending(outgoing, response)
                // The watermark only ever moves forward. A stale or
                // misbehaving server sending a lower value must not put the
                // client behind where it already stood.
                val watermark = response.optInt("watermark", prefs.watermark)
                if (watermark > prefs.watermark) prefs.watermark = watermark

                val remaining = store.pendingCount()
                onProgress(remaining, total)

                // Counted over the tables the server named only. Rows an older
                // server ignores stay marked; counting them would turn a block
                // full of them into MAX_ROUNDS of resending.
                val received = Rows.serverTables(response)
                val more = response.optBoolean("more", false) ||
                    (sentCount(outgoing, received) >= BLOCK && store.pendingCount(received) > 0)
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
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Not a sync failure — the coroutine is being torn down (e.g. the
            // screen closed mid-request) and must be allowed to keep
            // unwinding, or structured concurrency breaks.
            throw cancellation
        } catch (unexpected: RuntimeException) {
            // Nothing here is allowed to reach the caller uncaught. A local
            // database error (SQLiteException — thrown mid-call, the
            // realistic case, since a sync starts right after logging one) is
            // the concrete finding, but the same rule holds for anything else
            // this class did not anticipate: turn it into a visible failure
            // instead of taking the process down.
            return SyncResult.Failed(FailureKind.UNKNOWN, "Unerwarteter Fehler beim Abgleich.")
        } finally {
            running.set(false)
        }
    }

    /**
     * Marks the entire local stock as unsent and drops the watermark back to
     * zero. Both must happen together: the watermark only moves forward on
     * its own (see [sync] above), so after pointing the app at a server that
     * has never seen this device before, or after an older server backup was
     * restored — its own counter now sits below this device's — a watermark
     * left in place would make the app believe it already received
     * everything up to a point the server has since forgotten, and whatever
     * the server writes in between would never be fetched. Marking the stock
     * without resetting the watermark leaves exactly that hole.
     */
    fun resetForFullResync() {
        store.markAllDirty()
        prefs.watermark = 0
    }

    /** How many rows [payload] carried for [tables] — rows and tombstones alike. */
    private fun sentCount(payload: JSONObject, tables: Set<String>): Int {
        var total = 0
        val deletions = payload.optJSONArray("deleted")
        if (deletions != null) {
            for (i in 0 until deletions.length()) {
                if (deletions.getJSONObject(i).optString("table_name") in tables) total++
            }
        }
        for (table in Rows.TABLES) {
            if (table in tables) total += payload.optJSONArray(table)?.length() ?: 0
        }
        return total
    }

    private companion object {
        const val BLOCK = 500

        /** A stop against a server that keeps saying „more" — 250 blocks are 125 000 rows. */
        const val MAX_ROUNDS = 250
    }
}
