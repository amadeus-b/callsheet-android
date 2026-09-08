package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.contacts.Preferences
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.sync.FailureKind
import io.github.amadeusb.callsheet.sync.HttpFailure
import io.github.amadeusb.callsheet.sync.SyncEngine
import io.github.amadeusb.callsheet.sync.SyncResult
import io.github.amadeusb.callsheet.sync.SyncStore
import io.github.amadeusb.callsheet.sync.Transport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private lateinit var ctx: android.content.Context
    private lateinit var engine: SyncEngine
    private lateinit var prefs: Preferences

    @Before
    fun aufbau() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("callsheet.db")
        Database.resetSharedInstanceForTesting()
        prefs = Preferences(ctx)
        prefs.watermark = 0
        prefs.serverUrl = "https://beispiel.invalid"
        prefs.serverToken = "x".repeat(64)
        engine = SyncEngine(SyncStore(ctx), prefs)
    }

    private fun leereAntwort(stand: Int, weitere: Boolean = false) = JSONObject().apply {
        put("stand", stand); put("weitere", weitere)
        for (t in listOf("businesses", "calls", "contacts", "contact_numbers", "geloescht")) put(t, JSONArray())
    }

    private fun betrieb(id: String) = Database(ctx).writableDatabase.execSQL(
        "INSERT INTO businesses (place_id, name, status, updated_at, dirty) " +
            "VALUES ('$id', 'Elektro Meier', 'new', '2026-09-07T10:00:00+02:00', 1)"
    )

    @Test
    fun `a successful sync advances the watermark and clears the marks`() {
        betrieb("P1")
        val ergebnis = engine.sync(object : Transport {
            override fun post(payload: JSONObject) = leereAntwort(17)
        })
        assertEquals(SyncResult.Ok, ergebnis)
        assertEquals(17, prefs.watermark)
        assertEquals(0, SyncStore(ctx).pendingCount())
        assertTrue(prefs.lastSyncAt != null)
    }

    @Test
    fun `the engine keeps asking while the server says there is more`() {
        var aufrufe = 0
        engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                aufrufe++
                return leereAntwort(aufrufe, weitere = aufrufe < 3)
            }
        })
        assertEquals(3, aufrufe)
        assertEquals(3, prefs.watermark)
    }

    @Test
    fun `every block carries the watermark of the block before`() {
        val gesehen = mutableListOf<Int>()
        var aufrufe = 0
        engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                gesehen.add(payload.getInt("seit"))
                aufrufe++
                return leereAntwort(aufrufe * 10, weitere = aufrufe < 3)
            }
        })
        assertEquals(listOf(0, 10, 20), gesehen)
    }

    @Test
    fun `a failure leaves the watermark and the marks alone`() {
        betrieb("P1")
        val ergebnis = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject = throw java.io.IOException("kein Netz")
        })
        assertTrue(ergebnis is SyncResult.Failed && (ergebnis as SyncResult.Failed).kind == FailureKind.NETWORK)
        assertEquals(0, prefs.watermark)
        assertEquals(1, SyncStore(ctx).pendingCount())
    }

    @Test
    fun `without a server address nothing happens`() {
        prefs.serverUrl = null
        assertEquals(SyncResult.Idle, engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject = throw AssertionError("darf nicht aufgerufen werden")
        }))
    }

    @Test
    fun `a run already in flight blocks a second one and stays untouched`() {
        var calls = 0
        lateinit var nestedResult: SyncResult
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                calls++
                // Called from inside the request that is already running.
                nestedResult = engine.sync(this)
                return leereAntwort(5)
            }
        })
        assertEquals(SyncResult.Idle, nestedResult)
        assertEquals(1, calls)
        assertEquals(SyncResult.Ok, result)
        assertEquals(5, prefs.watermark)
    }

    @Test
    fun `the lock opens again after a failed run`() {
        engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject = throw java.io.IOException("kein Netz")
        })
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject) = leereAntwort(9)
        })
        assertEquals(SyncResult.Ok, result)
        assertEquals(9, prefs.watermark)
    }

    @Test
    fun `429 is its own failure kind and stops the run before the next block`() {
        var calls = 0
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                calls++
                throw HttpFailure(FailureKind.RATE_LIMITED, "Bitte warten.")
            }
        })
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).kind == FailureKind.RATE_LIMITED)
        assertEquals(1, calls)
    }

    @Test
    fun `413 ends the run as a visible failure instead of looping`() {
        var calls = 0
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                calls++
                throw HttpFailure(FailureKind.TOO_LARGE, "Zu groß.")
            }
        })
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).kind == FailureKind.TOO_LARGE)
        assertEquals(1, calls)
    }

    @Test
    fun `a response that is not JSON becomes a visible failure, not a crash`() {
        betrieb("P1")
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject =
                throw HttpFailure(FailureKind.BAD_RESPONSE, "Die Antwort des Servers ließ sich nicht lesen.")
        })
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).kind == FailureKind.BAD_RESPONSE)
        assertEquals(0, prefs.watermark)
        assertEquals(1, SyncStore(ctx).pendingCount())
    }

    @Test
    fun `a JSON field that is not the expected object becomes a visible failure, not a crash`() {
        betrieb("P1")
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject) = leereAntwort(17).apply {
                // A row that should be a JSON object but is a plain string —
                // the shape a malformed or half-broken server might send.
                put("businesses", JSONArray().put("not an object"))
            }
        })
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).kind == FailureKind.BAD_RESPONSE)
        assertEquals(0, prefs.watermark)
        assertEquals(1, SyncStore(ctx).pendingCount())
    }

    @Test
    fun `a database error during a sync becomes a visible failure instead of crashing the app`() {
        // SQLiteException is neither HttpFailure, JSONException nor
        // IOException, so it would otherwise escape the engine, escape the
        // coroutine in the view model, and take the process down — mid-call,
        // the realistic case, since a sync starts right after logging one.
        betrieb("P1")
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject =
                throw android.database.sqlite.SQLiteException("disk I/O error")
        })
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).kind == FailureKind.UNKNOWN)
        assertEquals(0, prefs.watermark)
        assertEquals(1, SyncStore(ctx).pendingCount())
    }

    @Test
    fun `a row the server rejects stays marked and the sync still completes`() {
        // The concrete finding: without this, the row would be gone from the
        // outgoing queue, absent from the server, and no longer counted as
        // open — the worst kind of failure this project guards against.
        betrieb("P1")
        betrieb("P2")
        val ergebnis = engine.sync(object : Transport {
            override fun post(payload: JSONObject) = leereAntwort(5).apply {
                put("abgewiesen", JSONArray().put(JSONObject().apply {
                    put("tabelle", "businesses"); put("schluessel", "P1"); put("fehler", "kaputt")
                }))
            }
        })
        assertEquals(SyncResult.Ok, ergebnis)
        assertEquals(1, SyncStore(ctx).pendingCount())
        assertEquals(1, Database(ctx).readableDatabase.rawQuery(
            "SELECT dirty FROM businesses WHERE place_id = 'P1'", null,
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `a row rejected on every attempt does not spin the engine — it is offered again on the next sync, not hammered within this one`() {
        betrieb("P1")
        var aufrufe = 0
        val ergebnis = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                aufrufe++
                return leereAntwort(1).apply {
                    put("abgewiesen", JSONArray().put(JSONObject().apply {
                        put("tabelle", "businesses"); put("schluessel", "P1"); put("fehler", "kaputt")
                    }))
                }
            }
        })
        // One attempt this run — the row stays marked for the next sync
        // instead of being retried 250 times in this one.
        assertEquals(1, aufrufe)
        assertEquals(SyncResult.Ok, ergebnis)
        assertEquals(1, SyncStore(ctx).pendingCount())
    }

    @Test
    fun `hitting the round limit while work remains reports Incomplete, not Ok`() {
        prefs.lastSyncAt = "vorher"
        var calls = 0
        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                calls++
                return leereAntwort(calls, weitere = true)
            }
        })
        assertEquals(SyncResult.Incomplete, result)
        assertEquals(250, calls)
        assertEquals(250, prefs.watermark)
        assertEquals("vorher", prefs.lastSyncAt)
    }
}
