package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.sync.Merge
import io.github.amadeusb.callsheet.sync.SyncStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        put("watermark", 1)
        put("more", false)
        put("businesses", JSONArray(betriebe.toList()))
        put("calls", JSONArray())
        put("contacts", JSONArray())
        put("contact_numbers", JSONArray())
        put("deleted", JSONArray())
    }

    /** A response with no data and, unless given, no rejections. */
    private fun leereAntwort(vararg rejected: Pair<String, String>) = JSONObject().apply {
        put("watermark", 1)
        put("more", false)
        put("businesses", JSONArray())
        put("calls", JSONArray())
        put("contacts", JSONArray())
        put("contact_numbers", JSONArray())
        put("deleted", JSONArray())
        put("rejected", JSONArray(rejected.map { (table, key) ->
            JSONObject().apply { put("table", table); put("key", key); put("reason", "kaputt") }
        }))
    }

    private fun betriebJson(id: String, note: String?, zeit: String, status: String = "new") = JSONObject().apply {
        put("place_id", id); put("name", "Elektro Meier"); put("status", status)
        if (note == null) put("note", JSONObject.NULL) else put("note", note)
        put("updated_at", zeit)
    }

    private fun einTermin(id: String, zeit: String, dirty: Int, eventId: Long? = null) = schreibe(
        "INSERT INTO appointments (id, place_id, starts_at, ends_at, location, updated_at, event_uid, " +
            "calendar_event_id, calendar_seen_starts_at, dirty) VALUES ('$id', 'P1', " +
            "'2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', 'Zehentstraße 39', '$zeit', '$id', " +
            "${eventId ?: "NULL"}, ${if (eventId != null) "'2026-09-10T14:00:00+02:00'" else "NULL"}, $dirty)"
    )

    private fun terminJson(id: String, zeit: String, start: String = "2026-09-10T16:00:00+02:00") = JSONObject().apply {
        put("id", id); put("place_id", "P1"); put("starts_at", start)
        put("ends_at", JSONObject.NULL); put("location", JSONObject.NULL); put("note", "Angebot")
        put("contact_id", JSONObject.NULL); put("event_uid", id); put("updated_at", zeit)
    }

    private fun einBesuch(
        id: String,
        zeit: String,
        dirty: Int,
        state: String? = null,
        uid: String? = null,
        kind: String? = "visit",
    ) = schreibe(
        "INSERT INTO appointments (id, place_id, starts_at, updated_at, event_uid, calendar_state, kind, dirty) " +
            "VALUES ('$id', 'P1', '2026-09-10T14:00:00+02:00', '$zeit', ${uid?.let { "'$it'" } ?: "NULL"}, " +
            "${state?.let { "'$it'" } ?: "NULL"}, ${kind?.let { "'$it'" } ?: "NULL"}, $dirty)"
    )

    /** The first row of [sql], every column as text. */
    private fun zeile(sql: String): List<String?> =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { c ->
            assertTrue(c.moveToFirst())
            (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }
        }

    private fun adresseJson(id: String, placeId: String, city: String?, zeit: String, position: Int = 0) = JSONObject().apply {
        put("id", id); put("place_id", placeId); put("label", JSONObject.NULL)
        put("street", JSONObject.NULL); put("postal_code", JSONObject.NULL)
        put("city", city ?: JSONObject.NULL)
        put("latitude", JSONObject.NULL); put("longitude", JSONObject.NULL)
        put("position", position); put("updated_at", zeit)
    }

    private fun grabstein(table: String, id: String, zeit: String) = JSONArray(listOf(JSONObject().apply {
        put("table_name", table); put("row_id", id); put("deleted_at", zeit)
    }))

    private fun einzeln(sql: String): String? =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

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
    fun `a row the server names in rejected keeps its mark, the other is cleared`() {
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
            put("deleted", JSONArray(listOf(JSONObject().apply {
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
            put("deleted", JSONArray(listOf(JSONObject().apply {
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
    fun `a tombstone the server names in rejected stays in the outgoing queue, the other is cleared`() {
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
            put("deleted", JSONArray(listOf(JSONObject().apply {
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
            put("deleted", JSONArray(listOf(JSONObject().apply {
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
            put("deleted", JSONArray(listOf(JSONObject().apply {
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

    @Test
    fun `pending carries an appointment's event_uid but none of its calendar columns`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1, eventId = 4711)

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("T1", row.getString("event_uid"))
        for (column in listOf("calendar_event_id", "calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location", "dirty")) {
            assertFalse("$column must not travel", row.has(column))
        }
    }

    @Test
    fun `appointments come down and are reported as written`() {
        val response = leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00"))))

        val applied = store.apply(response)

        assertEquals(listOf("T1"), applied.written)
        assertEquals(1, zahl("SELECT COUNT(*) FROM appointments WHERE id = 'T1' AND dirty = 0"))
    }

    @Test
    fun `an incoming appointment leaves this device's calendar link alone`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 0, eventId = 4711)
        val incoming = terminJson("T1", "2026-09-07T11:00:00+02:00").put("calendar_event_id", 99)

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        Database(ctx).readableDatabase.rawQuery(
            "SELECT starts_at, calendar_event_id, calendar_seen_starts_at FROM appointments WHERE id = 'T1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-09-10T16:00:00+02:00", c.getString(0))
            assertEquals(4711L, c.getLong(1))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(2))
        }
    }

    @Test
    fun `an older incoming appointment is not reported as written`() {
        einTermin("T1", "2026-09-07T12:00:00+02:00", dirty = 0)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00")))))

        assertTrue(applied.written.isEmpty())
    }

    @Test
    fun `a callback's kind and completion go up`() {
        schreibe(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, kind, done_at, dirty) VALUES " +
                "('R1', 'P1', '2026-09-15T09:00:00+02:00', '2026-09-07T10:00:00+02:00', 'callback', " +
                "'2026-09-15T09:05:00+02:00', 1)"
        )

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("callback", row.getString("kind"))
        assertEquals("2026-09-15T09:05:00+02:00", row.getString("done_at"))
    }

    @Test
    fun `an incoming appointment without a kind keeps the kind stored here`() {
        // A server before migration 008 knows neither column and sends neither key.
        schreibe(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, kind, done_at, dirty) VALUES " +
                "('T1', 'P1', '2026-09-15T09:00:00+02:00', '2026-09-07T10:00:00+02:00', 'callback', " +
                "'2026-09-15T09:05:00+02:00', 0)"
        )

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T11:00:00+02:00")))))

        Database(ctx).readableDatabase.rawQuery("SELECT kind, done_at, note FROM appointments WHERE id = 'T1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
            assertEquals("2026-09-15T09:05:00+02:00", c.getString(1))
            assertEquals("Angebot", c.getString(2))
        }
    }

    @Test
    fun `a standstill fills a callback's kind and completion a 1_4_0 phone could not store`() {
        // Pulled while on 1.4.0: the row is here, its kind and done_at were dropped.
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 0)
        val incoming = terminJson("T1", "2026-09-07T10:00:00+02:00")
            .put("kind", "callback").put("done_at", "2026-09-15T09:05:00+02:00")

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        Database(ctx).readableDatabase.rawQuery(
            "SELECT kind, done_at, note, starts_at, location, dirty FROM appointments WHERE id = 'T1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
            assertEquals("2026-09-15T09:05:00+02:00", c.getString(1))
            assertEquals("Angebot", c.getString(2))
            // Gaps only: what this device holds stays.
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(3))
            assertEquals("Zehentstraße 39", c.getString(4))
            // Filled from the server, so nothing to send back.
            assertEquals(0, c.getInt(5))
        }
        // Written, so the calendar follows: a row that became a callback gets its title.
        assertEquals(listOf("T1"), applied.written)
    }

    @Test
    fun `a standstill never overwrites a value stored here`() {
        schreibe(
            "INSERT INTO appointments (id, place_id, starts_at, note, updated_at, kind, done_at, dirty) VALUES " +
                "('T1', 'P1', '2026-09-15T09:00:00+02:00', 'lokal', '2026-09-07T10:00:00+02:00', 'callback', " +
                "'2026-09-15T09:05:00+02:00', 0)"
        )
        // No gap on either side: every column the incoming row fills is set here.
        val incoming = terminJson("T1", "2026-09-07T10:00:00+02:00")
            .put("kind", "visit").put("done_at", JSONObject.NULL).put("event_uid", JSONObject.NULL)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        Database(ctx).readableDatabase.rawQuery(
            "SELECT kind, done_at, note, starts_at, dirty FROM appointments WHERE id = 'T1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
            assertEquals("2026-09-15T09:05:00+02:00", c.getString(1))
            assertEquals("lokal", c.getString(2))
            assertEquals("2026-09-15T09:00:00+02:00", c.getString(3))
            assertEquals(0, c.getInt(4))
        }
        assertTrue(applied.written.isEmpty())
    }

    @Test
    fun `a standstill fills gaps in every synchronised table, the way the server does`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)

        store.apply(antwort(betriebJson("P1", "vom Server", "2026-09-07T08:00:00Z")))

        assertEquals("vom Server", note("P1"))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `an incoming callback arrives with its kind and completion`() {
        val incoming = terminJson("R1", "2026-09-07T10:00:00+02:00")
            .put("kind", "callback").put("done_at", "2026-09-15T09:05:00+02:00")

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        Database(ctx).readableDatabase.rawQuery("SELECT kind, done_at, dirty FROM appointments WHERE id = 'R1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
            assertEquals("2026-09-15T09:05:00+02:00", c.getString(1))
            assertEquals(0, c.getInt(2))
        }
    }

    @Test
    fun `an incoming appointment no newer than a local tombstone is not written`() {
        // Deleted here, not yet uploaded; the server still hands out the older row.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T1', '2026-09-07T11:00:00+02:00')")

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00")))))

        assertTrue(applied.written.isEmpty())
        assertEquals(0, zahl("SELECT COUNT(*) FROM appointments"))
    }

    @Test
    fun `a remote tombstone removes an appointment and reports the link it had`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 0, eventId = 4711)
        val stone = JSONObject().apply {
            put("table_name", "appointments"); put("row_id", "T1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
        }

        val applied = store.apply(leereAntwort().put("deleted", JSONArray(listOf(stone))))

        assertEquals(0, zahl("SELECT COUNT(*) FROM appointments"))
        assertEquals(listOf(io.github.amadeusb.callsheet.sync.RemovedAppointment("T1", 4711L, "T1")), applied.removed)
    }

    @Test
    fun `clearPending leaves appointments marked when the response names no tables`() {
        // An older server: it ignores payload.appointments without a word.
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P1", "offen", "2026-09-07T10:00:00+02:00", dirty = 1)
        val sent = store.pending(500)

        store.clearPending(sent, leereAntwort())

        assertEquals(1, zahl("SELECT dirty FROM appointments WHERE id = 'T1'"))
        assertEquals(0, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `clearPending leaves an appointment tombstone queued when tables does not name appointments`() {
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T1', '2026-09-07T11:00:00+02:00')")
        val sent = store.pending(500)
        val response = leereAntwort().put("tables", JSONArray(listOf("businesses", "calls", "contacts", "contact_numbers")))

        store.clearPending(sent, response)

        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'appointments'"))
    }

    @Test
    fun `clearPending clears appointments once the response names them`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        val sent = store.pending(500)
        val response = leereAntwort().put("tables", JSONArray(listOf("businesses", "calls", "contacts", "contact_numbers", "appointments")))

        store.clearPending(sent, response)

        assertEquals(0, zahl("SELECT dirty FROM appointments WHERE id = 'T1'"))
    }

    @Test
    fun `pendingCount counts only the tables it is given`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P1", "offen", "2026-09-07T10:00:00+02:00", dirty = 1)
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T2', '2026-09-07T11:00:00+02:00')")

        assertEquals(3, store.pendingCount())
        assertEquals(1, store.pendingCount(io.github.amadeusb.callsheet.sync.Rows.LEGACY_TABLES))
    }

    private fun note(id: String): String? =
        Database(ctx).readableDatabase.rawQuery("SELECT note FROM businesses WHERE place_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun status(id: String): String? =
        Database(ctx).readableDatabase.rawQuery("SELECT status FROM businesses WHERE place_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun `incoming business addresses are written unmarked, and the search text holds their cities`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)

        store.apply(antwort().put("business_addresses", JSONArray(listOf(
            adresseJson("main-P1", "P1", "Ingolstadt", "2026-09-07T10:00:00+02:00"),
            adresseJson("A2", "P1", "Eichstätt", "2026-09-07T10:00:00+02:00", position = 1),
        ))))

        assertEquals("Eichstätt", einzeln("SELECT city FROM business_addresses WHERE id = 'A2'"))
        assertEquals(0, zahl("SELECT SUM(dirty) FROM business_addresses"))
        assertEquals("elektro meier ingolstadt eichstätt", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
        // Derived, not a change: the business stays unmarked.
        assertEquals(0, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a remote tombstone removes a business address, and the search text drops its city`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 0)"
        )
        schreibe("UPDATE businesses SET search_text = 'elektro meier eichstätt' WHERE place_id = 'P1'")

        store.apply(antwort().put("deleted", grabstein("business_addresses", "A2", "2026-09-08T10:00:00+02:00")))

        assertNull(einzeln("SELECT id FROM business_addresses WHERE id = 'A2'"))
        assertEquals("elektro meier", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a tombstone for a main address is remembered for the import, and an incoming main address forgets it`() {
        store.apply(antwort().put("deleted", grabstein("business_addresses", "main-P1", "2026-09-08T10:00:00+02:00")))
        assertEquals("P1", einzeln("SELECT place_id FROM removed_main_addresses"))

        store.apply(antwort().put("business_addresses", JSONArray(listOf(
            adresseJson("main-P1", "P1", "Ingolstadt", "2026-09-09T10:00:00+02:00"),
        ))))
        assertNull(einzeln("SELECT place_id FROM removed_main_addresses"))
    }

    @Test
    fun `a tombstone older than the main address here is not remembered`() {
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('main-P1', 'P1', 'Ingolstadt', 0, '2026-09-09T10:00:00+02:00', 0)"
        )

        store.apply(antwort().put("deleted", grabstein("business_addresses", "main-P1", "2026-09-08T10:00:00+02:00")))

        assertEquals("main-P1", einzeln("SELECT id FROM business_addresses"))
        assertNull(einzeln("SELECT place_id FROM removed_main_addresses"))
    }

    @Test
    fun `an incoming business keeps the search text of all its addresses`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 0)"
        )

        store.apply(antwort(betriebJson("P1", "neu", "2026-09-08T10:00:00+02:00").put("search_text", "elektro meier")))

        assertEquals("elektro meier eichstätt", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `business addresses and a contact's address go up`() {
        schreibe(
            "INSERT INTO business_addresses (id, place_id, label, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Filiale', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 1)"
        )
        schreibe(
            "INSERT INTO contacts (id, place_id, name, position, updated_at, address_id, dirty) " +
                "VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00', 'A2', 1)"
        )

        val payload = store.pending(500)

        assertEquals("Filiale", payload.getJSONArray("business_addresses").getJSONObject(0).getString("label"))
        assertEquals("A2", payload.getJSONArray("contacts").getJSONObject(0).getString("address_id"))
    }

    @Test
    fun `an incoming contact's address comes down`() {
        store.apply(antwort().put("contacts", JSONArray(listOf(JSONObject().apply {
            put("id", "K1"); put("place_id", "P1"); put("name", "Frau Meier"); put("position", 0)
            put("updated_at", "2026-09-07T10:00:00+02:00"); put("address_id", "A2")
        }))))

        assertEquals("A2", einzeln("SELECT address_id FROM contacts WHERE id = 'K1'"))
    }

    @Test
    fun `pending never carries the server's calendar columns or the seen title`() {
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 1, state = "ok", uid = "abc@infomaniak")
        schreibe(
            "UPDATE appointments SET title = 'Erstgespräch', invite_email = 'info@example.org', " +
                "calendar_error = 'x', calendar_seen_title = 'y' WHERE id = 'A1'"
        )

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("Erstgespräch", row.getString("title"))
        assertEquals("info@example.org", row.getString("invite_email"))
        for (column in listOf("calendar_state", "calendar_error", "calendar_seen_title")) {
            assertFalse("$column must not travel", row.has(column))
        }
    }

    @Test
    fun `the server's calendar state and UID are taken onto a visit edited here since, which stays marked`() {
        // Edited on this phone after the server created the event: the local row is newer.
        einBesuch("A1", "2026-09-07T11:00:00+02:00", dirty = 1, state = "pending")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00")
            .put("kind", "visit").put("event_uid", "abc@infomaniak")
            .put("calendar_state", "ok").put("calendar_error", JSONObject.NULL)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("ok", "abc@infomaniak", "2026-09-10T14:00:00+02:00", "2026-09-07T11:00:00+02:00", "1"),
            zeile("SELECT calendar_state, event_uid, starts_at, updated_at, dirty FROM appointments WHERE id = 'A1'"),
        )
        assertEquals(listOf("A1"), applied.written)
    }

    @Test
    fun `the server's calendar state is taken at a standstill`() {
        // The server writes the state without moving updated_at.
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 0, state = "pending")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00")
            .put("kind", "visit").put("calendar_state", "error").put("calendar_error", "Im Kalender gelöscht")

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("error", "Im Kalender gelöscht"),
            zeile("SELECT calendar_state, calendar_error FROM appointments WHERE id = 'A1'"),
        )
    }

    @Test
    fun `a callback's UID is not taken from an older server row`() {
        einBesuch("R1", "2026-09-07T11:00:00+02:00", dirty = 1, uid = "mine", kind = "callback")
        val incoming = terminJson("R1", "2026-09-07T10:00:00+02:00").put("kind", "callback").put("event_uid", "theirs")

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(listOf("mine"), zeile("SELECT event_uid FROM appointments WHERE id = 'R1'"))
    }

    @Test
    fun `a server that sends no calendar state clears nothing on a newer local row`() {
        // The local row is newer and waiting to go up, so the incoming, older row
        // replaces nothing and only takeServerOwned looks at it: an absent state
        // and a null UID must not clear what the server said before.
        einBesuch("A1", "2026-09-07T11:00:00+02:00", dirty = 1, state = "ok", uid = "abc@infomaniak")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00").put("kind", "visit").put("event_uid", JSONObject.NULL)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("ok", "abc@infomaniak", "2026-09-07T11:00:00+02:00", "1"),
            zeile("SELECT calendar_state, event_uid, updated_at, dirty FROM appointments WHERE id = 'A1'"),
        )
        assertEquals(emptyList<String>(), applied.written)
    }

    @Test
    fun `a remote tombstone reports the kind of the removed appointment`() {
        einBesuch("R1", "2026-09-07T10:00:00+02:00", dirty = 0, uid = "R1", kind = "callback")
        val stone = JSONObject().apply {
            put("table_name", "appointments"); put("row_id", "R1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
        }

        val applied = store.apply(leereAntwort().put("deleted", JSONArray(listOf(stone))))

        assertEquals(AppointmentKind.CALLBACK, applied.removed.single().kind)
    }

    private fun zahl(sql: String): Int =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
}
