package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.sync.Merge
import io.github.amadeusb.callsheet.sync.SyncStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStoreTest {

    private lateinit var ctx: android.content.Context
    private lateinit var store: SyncStore

    @Before
    fun aufbau() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("callsheet.db")
        Database.resetSharedInstanceForTesting()
        store = SyncStore(ctx)
    }

    private fun schreibe(sql: String) = Database(ctx).writableDatabase.execSQL(sql)

    private fun einBetrieb(id: String, note: String?, zeit: String, dirty: Int) = schreibe(
        "INSERT INTO businesses (place_id, name, status, note, updated_at, dirty) " +
            "VALUES ('$id', 'Elektro Meier', 'new', ${note?.let { "'$it'" } ?: "NULL"}, '$zeit', $dirty)"
    )

    private fun antwort(vararg betriebe: JSONObject) = JSONObject().apply {
        put("stand", 1)
        put("weitere", false)
        put("businesses", JSONArray(betriebe.toList()))
        put("calls", JSONArray())
        put("contacts", JSONArray())
        put("contact_numbers", JSONArray())
        put("geloescht", JSONArray())
    }

    /** A response with no data and, unless given, no rejections. */
    private fun leereAntwort(vararg abgewiesen: Pair<String, String>) = JSONObject().apply {
        put("stand", 1)
        put("weitere", false)
        put("businesses", JSONArray())
        put("calls", JSONArray())
        put("contacts", JSONArray())
        put("contact_numbers", JSONArray())
        put("geloescht", JSONArray())
        put("abgewiesen", JSONArray(abgewiesen.map { (tabelle, schluessel) ->
            JSONObject().apply { put("tabelle", tabelle); put("schluessel", schluessel); put("fehler", "kaputt") }
        }))
    }

    private fun betriebJson(id: String, note: String?, zeit: String, status: String = "new") = JSONObject().apply {
        put("place_id", id); put("name", "Elektro Meier"); put("status", status)
        if (note == null) put("note", JSONObject.NULL) else put("note", note)
        put("updated_at", zeit)
    }

    @Test
    fun `pending returns only marked rows`() {
        einBetrieb("P1", "offen", "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P2", "erledigt", "2026-09-07T10:00:00+02:00", dirty = 0)
        val nutzlast = store.pending(500)
        assertEquals(1, nutzlast.getJSONArray("businesses").length())
        assertEquals("P1", nutzlast.getJSONArray("businesses").getJSONObject(0).getString("place_id"))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `pending respects the limit across all tables`() {
        for (i in 1..10) einBetrieb("P$i", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        assertEquals(3, store.pending(3).getJSONArray("businesses").length())
    }

    @Test
    fun `clearPending only clears what was sent`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P2", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        val block = store.pending(1)
        store.clearPending(block, leereAntwort())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `a row the server names in abgewiesen keeps its mark, the other is cleared`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P2", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        val block = store.pending(500)
        store.clearPending(block, leereAntwort("businesses" to "P1"))
        assertEquals(1, store.pendingCount())
        assertEquals(1, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
        assertEquals(0, zahl("SELECT dirty FROM businesses WHERE place_id = 'P2'"))
    }

    @Test
    fun `a rejection naming a table or key the app does not know is ignored without damage`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 1)
        val block = store.pending(500)
        store.clearPending(block, leereAntwort("unbekannte_tabelle" to "P1", "businesses" to "P9-nicht-gesendet"))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `clearPending leaves a row changed while the request was in flight marked`() {
        einBetrieb("P1", "alt", "2026-09-07T10:00:00+02:00", dirty = 1)
        val block = store.pending(500)
        // The user edits the row again after it was already read for sending;
        // every real write path bumps updated_at along with dirty.
        schreibe("UPDATE businesses SET note = 'neu', updated_at = '2026-09-07T10:05:00+02:00', dirty = 1 WHERE place_id = 'P1'")
        store.clearPending(block, leereAntwort())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `a newer remote row wins`() {
        einBetrieb("P1", "alt", "2026-09-07T10:00:00+02:00", dirty = 0)
        store.apply(antwort(betriebJson("P1", "neu", "2026-09-07T11:00:00+02:00")))
        assertEquals("neu", note("P1"))
    }

    @Test
    fun `an older remote row loses`() {
        einBetrieb("P1", "lokal", "2026-09-07T12:00:00+02:00", dirty = 0)
        store.apply(antwort(betriebJson("P1", "server", "2026-09-07T11:00:00+02:00")))
        assertEquals("lokal", note("P1"))
    }

    @Test
    fun `applied rows are not marked again`() {
        store.apply(antwort(betriebJson("P1", "vom Server", "2026-09-07T11:00:00+02:00")))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `applied rows that overwrite a local row are not marked dirty`() {
        einBetrieb("P1", "alt", "2026-09-07T10:00:00+02:00", dirty = 0)
        store.apply(antwort(betriebJson("P1", "neu", "2026-09-07T11:00:00+02:00")))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `do_not_call from the server survives a newer local row`() {
        einBetrieb("P1", null, "2026-09-08T10:00:00+02:00", dirty = 0)
        store.apply(antwort(betriebJson("P1", null, "2026-09-07T10:00:00+02:00", status = "do_not_call")))
        assertEquals("do_not_call", status("P1"))
    }

    @Test
    fun `a local do_not_call survives a newer incoming row without the block, and is marked for upload`() {
        // The finding this covers: the app forced the block back on but wrote
        // dirty = 0, so its own decision never travelled up — the server kept
        // the unblocked status forever, and a restore onto a new phone would
        // have lost the block.
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe("UPDATE businesses SET status = 'do_not_call' WHERE place_id = 'P1'")
        store.apply(antwort(betriebJson("P1", null, "2026-09-08T10:00:00+02:00", status = "new")))
        assertEquals("do_not_call", status("P1"))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `a remote tombstone removes the contact and its numbers`() {
        schreibe("INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00', 0)")
        schreibe("INSERT INTO contact_numbers (id, contact_id, number, kind, position, updated_at, dirty) VALUES ('N1', 'K1', '+4917612345', 'mobile', 0, '2026-09-07T10:00:00+02:00', 0)")
        val antwort = antwort().apply {
            put("geloescht", JSONArray(listOf(JSONObject().apply {
                put("table_name", "contacts"); put("row_id", "K1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
            })))
        }
        store.apply(antwort)
        assertEquals(0, zahl("SELECT COUNT(*) FROM contacts WHERE id = 'K1'"))
        assertEquals(0, zahl("SELECT COUNT(*) FROM contact_numbers WHERE contact_id = 'K1'"))
    }

    @Test
    fun `a contact that survived a stale tombstone keeps its numbers`() {
        // Correction C: the contact row is younger than the tombstone and
        // therefore survives — its numbers must not be swept away with it.
        schreibe("INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T12:00:00+02:00', 0)")
        schreibe("INSERT INTO contact_numbers (id, contact_id, number, kind, position, updated_at, dirty) VALUES ('N1', 'K1', '+4917612345', 'mobile', 0, '2026-09-07T12:00:00+02:00', 0)")
        val antwort = antwort().apply {
            put("geloescht", JSONArray(listOf(JSONObject().apply {
                put("table_name", "contacts"); put("row_id", "K1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
            })))
        }
        store.apply(antwort)
        assertEquals(1, zahl("SELECT COUNT(*) FROM contacts WHERE id = 'K1'"))
        assertEquals(1, zahl("SELECT COUNT(*) FROM contact_numbers WHERE contact_id = 'K1'"))
    }

    @Test
    fun `a number is suppressed when its parent contact was deleted before the number was last touched`() {
        // Fix-Runde 1, Befund 1: the number's own row has no tombstone, but
        // its parent contact does — and the number is older than that
        // tombstone. The server checks the parent's tombstone in
        // `empfangeKontakt`; the app must do the same.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T11:00:00+02:00')")
        val remoteNumber = JSONObject().apply {
            put("id", "N1"); put("contact_id", "K1"); put("number", "+4917612345")
            put("kind", "mobile"); put("position", 0); put("updated_at", "2026-09-07T10:00:00+02:00")
        }
        store.apply(antwort().apply { put("contact_numbers", JSONArray(listOf(remoteNumber))) })
        assertEquals(0, zahl("SELECT COUNT(*) FROM contact_numbers WHERE id = 'N1'"))
    }

    @Test
    fun `a number newer than its parent's tombstone is written anyway`() {
        // The number was created after the contact was deleted — it did not
        // exist yet when the deletion happened, so it must survive.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T11:00:00+02:00')")
        val remoteNumber = JSONObject().apply {
            put("id", "N1"); put("contact_id", "K1"); put("number", "+4917612345")
            put("kind", "mobile"); put("position", 0); put("updated_at", "2026-09-07T12:00:00+02:00")
        }
        store.apply(antwort().apply { put("contact_numbers", JSONArray(listOf(remoteNumber))) })
        assertEquals(1, zahl("SELECT COUNT(*) FROM contact_numbers WHERE id = 'N1'"))
    }

    @Test
    fun `a local tombstone beats an older incoming contact row`() {
        // Fix-Runde 1, Befund 2: the row-vs-tombstone direction, tested from
        // the row side instead of the tombstone side.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T11:00:00+02:00')")
        val remoteContact = JSONObject().apply {
            put("id", "K1"); put("place_id", "P1"); put("name", "Frau Meier")
            put("position", 0); put("updated_at", "2026-09-07T10:00:00+02:00")
        }
        store.apply(antwort().apply { put("contacts", JSONArray(listOf(remoteContact))) })
        assertEquals(0, zahl("SELECT COUNT(*) FROM contacts WHERE id = 'K1'"))
    }

    @Test
    fun `an incoming contact row newer than a local tombstone wins`() {
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T11:00:00+02:00')")
        val remoteContact = JSONObject().apply {
            put("id", "K1"); put("place_id", "P1"); put("name", "Frau Meier")
            put("position", 0); put("updated_at", "2026-09-07T12:00:00+02:00")
        }
        store.apply(antwort().apply { put("contacts", JSONArray(listOf(remoteContact))) })
        assertEquals(1, zahl("SELECT COUNT(*) FROM contacts WHERE id = 'K1'"))
    }

    @Test
    fun `clearPending leaves a tombstone rewritten while the request was in flight marked`() {
        // Fix-Runde 1, Befund 3: same optimistic-lock guard the row loop got,
        // applied to the deletions loop.
        schreibe("INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T09:00:00+02:00', 0)")
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T10:00:00+02:00')")
        val block = store.pending(500)
        // The contact is deleted again (re-tombstoned) after the block was read.
        schreibe("DELETE FROM deletions WHERE table_name = 'contacts' AND row_id = 'K1'")
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T10:05:00+02:00')")
        store.clearPending(block, leereAntwort())
        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contacts' AND row_id = 'K1'"))
    }

    @Test
    fun `a tombstone the server names in abgewiesen stays in the outgoing queue, the other is cleared`() {
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T10:00:00+02:00')")
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K2', '2026-09-07T10:00:00+02:00')")
        val block = store.pending(500)
        store.clearPending(block, leereAntwort("contacts" to "K1"))
        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contacts' AND row_id = 'K1'"))
        assertEquals(0, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contacts' AND row_id = 'K2'"))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `a tombstone for a table outside the allowlist is skipped silently`() {
        // Correction A: businesses has no id column, and the app never
        // deletes businesses or calls — a tombstone naming either must not
        // reach SQL.
        einBetrieb("P1", "alt", "2026-09-07T10:00:00+02:00", dirty = 0)
        val antwort = antwort().apply {
            put("geloescht", JSONArray(listOf(JSONObject().apply {
                put("table_name", "businesses"); put("row_id", "P1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
            })))
        }
        store.apply(antwort)
        assertEquals(1, zahl("SELECT COUNT(*) FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `an incoming tombstone does not travel back up as an outgoing one`() {
        // Correction B: applying a tombstone from the server must not insert
        // it into deletions, which also serves as the outgoing queue.
        schreibe("INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00', 0)")
        val antwort = antwort().apply {
            put("geloescht", JSONArray(listOf(JSONObject().apply {
                put("table_name", "contacts"); put("row_id", "K1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
            })))
        }
        store.apply(antwort)
        assertEquals(0, zahl("SELECT COUNT(*) FROM deletions"))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `a call known by id keeps its recorded time, duration, kind and contact`() {
        schreibe(
            "INSERT INTO calls (id, place_id, started_at, duration_seconds, outcome, note, kind, contact, updated_at, dirty) " +
                "VALUES ('C1', 'P1', '2026-09-07T09:00:00+02:00', 42, NULL, NULL, 'call', 'Frau Meier', '2026-09-07T09:00:00+02:00', 0)"
        )
        val remoteCall = JSONObject().apply {
            put("id", "C1"); put("place_id", "P1")
            put("started_at", "2099-01-01T00:00:00+02:00")
            put("duration_seconds", 999)
            put("outcome", "interessiert")
            put("note", "Rückruf erbeten")
            put("kind", "note")
            put("contact", "Herr Anders")
            put("updated_at", "2026-09-07T11:00:00+02:00")
        }
        val payload = antwort().apply { put("calls", JSONArray(listOf(remoteCall))) }
        store.apply(payload)
        val db = Database(ctx).readableDatabase
        db.rawQuery("SELECT started_at, duration_seconds, kind, contact, outcome, note FROM calls WHERE id = 'C1'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("2026-09-07T09:00:00+02:00", it.getString(0))
            assertEquals(42, it.getInt(1))
            assertEquals("call", it.getString(2))
            assertEquals("Frau Meier", it.getString(3))
            assertEquals("interessiert", it.getString(4))
            assertEquals("Rückruf erbeten", it.getString(5))
        }
    }

    @Test
    fun `a note and outcome explicitly cleared on the other side arrive as a real null, not the word null`() {
        // org.json.JSONObject.optString(name, null) is not a safe way to read
        // a JSON null on Android: it returns the four characters "null" for
        // that case and only falls back to the default when the key is
        // missing entirely.
        schreibe(
            "INSERT INTO calls (id, place_id, started_at, duration_seconds, outcome, note, kind, contact, updated_at, dirty) " +
                "VALUES ('C1', 'P1', '2026-09-07T09:00:00+02:00', 42, 'interessiert', 'Rückruf', 'call', 'Frau Meier', '2026-09-07T09:00:00+02:00', 0)"
        )
        val remoteCall = JSONObject().apply {
            put("id", "C1"); put("place_id", "P1")
            put("started_at", "2026-09-07T09:00:00+02:00")
            put("duration_seconds", 42)
            put("outcome", JSONObject.NULL)
            put("note", JSONObject.NULL)
            put("kind", "call")
            put("contact", "Frau Meier")
            put("updated_at", "2026-09-07T11:00:00+02:00")
        }
        store.apply(antwort().apply { put("calls", JSONArray(listOf(remoteCall))) })
        val db = Database(ctx).readableDatabase
        db.rawQuery("SELECT outcome, note FROM calls WHERE id = 'C1'", null).use {
            assertTrue(it.moveToFirst())
            assertTrue("outcome should be a real NULL, not the word \"null\"", it.isNull(0))
            assertTrue("note should be a real NULL, not the word \"null\"", it.isNull(1))
        }
    }

    @Test
    fun `markAllDirty marks every synchronised table, regardless of prior state`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe(
            "INSERT INTO calls (id, place_id, started_at, duration_seconds, kind, updated_at, dirty) " +
                "VALUES ('C1', 'P1', '2026-09-07T10:00:00+02:00', 10, 'call', '2026-09-07T10:00:00+02:00', 0)"
        )
        schreibe(
            "INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) " +
                "VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00', 0)"
        )
        schreibe(
            "INSERT INTO contact_numbers (id, contact_id, number, kind, position, updated_at, dirty) " +
                "VALUES ('N1', 'K1', '+4917612345', 'mobile', 0, '2026-09-07T10:00:00+02:00', 0)"
        )
        store.markAllDirty()
        assertEquals(4, store.pendingCount())
    }

    @Test
    fun `applyTombstone keeps a local tombstone rewritten while the request was in flight`() {
        // The same optimistic-lock guard clearPending already has, applied to
        // the incoming side: a tombstone the server just echoed back must not
        // erase a local tombstone for the same row that was (re-)written in
        // the meantime with a different deleted_at — or it never goes out.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('contacts', 'K1', '2026-09-07T12:00:00+02:00')")
        val antwort = antwort().apply {
            put("geloescht", JSONArray(listOf(JSONObject().apply {
                put("table_name", "contacts"); put("row_id", "K1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
            })))
        }
        store.apply(antwort)
        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contacts' AND row_id = 'K1'"))
    }

    @Test
    fun `timestamps are compared as instants`() {
        assertTrue(Merge.isNewer("2026-09-07T09:00:00+00:00", "2026-09-07T10:00:00+02:00"))
        assertFalse(Merge.isNewer("2026-09-07T10:00:00+02:00", "2026-09-07T10:00:00+02:00"))
        assertTrue(Merge.isNewer("2026-09-07T10:00:00+02:00", null))
    }

    private fun note(id: String): String? =
        Database(ctx).readableDatabase.rawQuery("SELECT note FROM businesses WHERE place_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun status(id: String): String? =
        Database(ctx).readableDatabase.rawQuery("SELECT status FROM businesses WHERE place_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun zahl(sql: String): Int =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
}
