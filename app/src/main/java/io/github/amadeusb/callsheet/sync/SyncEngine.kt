package io.github.amadeusb.callsheet.sync

import io.github.amadeusb.callsheet.contacts.Preferences
import io.github.amadeusb.callsheet.data.Clock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed class SyncResult {
    /** Went through, everything that was open is up. */
    object Ok : SyncResult()

    /** No server configured, or a sync is already running — the app runs as it always did. */
    object Idle : SyncResult()

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
            var runden = 0
            while (true) {
                val outgoing = store.pending(BLOCK)
                val payload = JSONObject(outgoing.toString()).put("seit", prefs.watermark)

                val response = transport.post(payload)

                store.apply(response)
                store.clearPending(outgoing)
                prefs.watermark = response.optInt("stand", prefs.watermark)

                val more = response.optBoolean("weitere", false) || store.pendingCount() > 0
                if (!more) break
                if (++runden >= MAX_RUNDEN) break
            }
            prefs.lastSyncAt = Clock.now()
            return SyncResult.Ok
        } catch (fehler: HttpFailure) {
            return SyncResult.Failed(fehler.kind, fehler.message ?: "Abgleich gescheitert.")
        } catch (fehler: IOException) {
            return SyncResult.Failed(FailureKind.NETWORK, "Kein Netz.")
        } finally {
            running.set(false)
        }
    }

    private companion object {
        const val BLOCK = 500

        /** A stop against a server that keeps saying „more" — 250 blocks are 125 000 rows. */
        const val MAX_RUNDEN = 250
    }
}
