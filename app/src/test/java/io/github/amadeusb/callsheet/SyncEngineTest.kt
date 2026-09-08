package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.contacts.Preferences
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.sync.FailureKind
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
}
