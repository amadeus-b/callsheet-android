package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.sync.Rows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchemaTest {

    private fun columns(table: String): Set<String> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("callsheet.db")
        val db = Database(ctx).readableDatabase
        return db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet()
        }
    }

    @Test
    fun `every synchronised table carries a dirty flag`() {
        for (table in listOf("businesses", "calls", "contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses")) {
            assertTrue("dirty missing on $table", columns(table).contains("dirty"))
        }
    }

    @Test
    fun `calls and numbers carry their own updated_at`() {
        assertTrue(columns("calls").contains("updated_at"))
        assertTrue(columns("contact_numbers").contains("updated_at"))
    }

    @Test
    fun `businesses carry the fields changed by hand`() {
        assertTrue(columns("businesses").contains("edited_fields"))
    }

    @Test
    fun `there is a table for tombstones`() {
        assertEquals(setOf("table_name", "row_id", "deleted_at"), columns("deletions"))
    }

    @Test
    fun `appointments carry a kind and a completion`() {
        val appointments = columns("appointments")
        assertTrue(appointments.contains("kind"))
        assertTrue(appointments.contains("done_at"))
    }

    @Test
    fun `appointments carry a title, an invitation and the server's calendar state`() {
        val appointments = columns("appointments")
        for (column in listOf("title", "invite_email", "calendar_state", "calendar_error", "calendar_seen_title")) {
            assertTrue("$column missing", appointments.contains(column))
        }
    }

    @Test
    fun `an upgrade from version one keeps the work`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("callsheet.db")
        // Version 1 nachbauen, eine Arbeitszeile hineinschreiben, dann hochziehen.
        val alt = ctx.openOrCreateDatabase("callsheet.db", 0, null)
        alt.execSQL("CREATE TABLE businesses (place_id TEXT PRIMARY KEY, name TEXT NOT NULL, street TEXT, postal_code TEXT, city TEXT, status TEXT NOT NULL DEFAULT 'new', note TEXT, follow_up_at TEXT, updated_at TEXT NOT NULL)")
        alt.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, started_at TEXT NOT NULL, duration_seconds INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'call')")
        alt.execSQL("CREATE TABLE contacts (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, name TEXT NOT NULL, role TEXT, email TEXT, note TEXT, position INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL, contact_version INTEGER)")
        alt.execSQL("CREATE TABLE contact_numbers (id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, number TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'other', position INTEGER NOT NULL DEFAULT 0)")
        alt.execSQL("INSERT INTO businesses (place_id, name, status, note, updated_at) VALUES ('P1', 'Elektro Meier', 'called', 'Rückruf', '2026-09-07T10:00:00+02:00')")
        alt.execSQL("INSERT INTO calls (id, place_id, started_at, duration_seconds, kind) VALUES ('C1', 'P1', '2026-09-07T10:00:00+02:00', 42, 'call')")
        alt.execSQL("INSERT INTO contacts (id, place_id, name, position, updated_at) VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00')")
        alt.execSQL("INSERT INTO contact_numbers (id, contact_id, number, kind, position) VALUES ('N1', 'K1', '+4917612345', 'mobile', 0)")
        alt.version = 1
        alt.close()

        val db = Database(ctx).writableDatabase
        db.rawQuery("SELECT note, dirty FROM businesses WHERE place_id = 'P1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Rückruf", c.getString(0))
            // A row that existed before this device ever knew about
            // synchronisation has never been sent anywhere. It must come out
            // of the migration marked as unsent — the column's own default of
            // 0 would tell the sync engine there is nothing to upload, and
            // the device's entire pre-existing stock would never reach the
            // server while the settings screen quietly reports "0 offen".
            assertEquals(1, c.getInt(1))
        }
        // Not just businesses — every synchronised table's pre-existing stock
        // must survive the migration marked as unsent.
        for ((table, id, key) in listOf(
            Triple("calls", "C1", "id"),
            Triple("contacts", "K1", "id"),
            Triple("contact_numbers", "N1", "id"),
        )) {
            db.rawQuery("SELECT dirty FROM $table WHERE $key = ?", arrayOf(id)).use { c ->
                assertTrue("$table row missing after upgrade", c.moveToFirst())
                assertEquals("$table not marked dirty after upgrade", 1, c.getInt(0))
            }
        }
    }

    @Test
    fun `the calendar event id stays on the device`() {
        val row = JSONObject().apply {
            put("place_id", "P1")
            put("appointment_at", "2026-09-10T14:00:00+02:00")
            put("calendar_event_id", 4711)
        }

        val values = Rows.toValues(row, setOf("place_id", "appointment_at", "calendar_event_id"))

        assertEquals("2026-09-10T14:00:00+02:00", values.getAsString("appointment_at"))
        assertFalse(
            "calendar_event_id must not come in from the server",
            values.containsKey("calendar_event_id"),
        )
    }

    @Test
    fun `what this device saw in an event stays on the device`() {
        val row = JSONObject().apply {
            put("id", "T1")
            put("event_uid", "T1")
            put("calendar_seen_starts_at", "2026-09-10T14:00:00+02:00")
            put("calendar_seen_ends_at", "2026-09-10T15:00:00+02:00")
            put("calendar_seen_location", "Zehentstraße 39")
        }
        val columns = row.keys().asSequence().toSet()

        val values = Rows.toValues(row, columns)

        assertEquals("T1", values.getAsString("event_uid"))
        for (column in listOf("calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location")) {
            assertFalse("$column must not come in from the server", values.containsKey(column))
        }
    }

    @Test
    fun `business addresses, a contact's address and the removed main addresses have their columns`() {
        assertEquals(
            setOf("id", "place_id", "label", "street", "postal_code", "city", "latitude", "longitude", "position", "updated_at", "dirty"),
            columns("business_addresses"),
        )
        assertTrue(columns("contacts").contains("address_id"))
        assertEquals(setOf("place_id"), columns("removed_main_addresses"))
    }

    @Test
    fun `the server's calendar state comes in, what this device saw of a title does not`() {
        val row = JSONObject().apply {
            put("id", "A1")
            put("calendar_state", "ok")
            put("calendar_error", JSONObject.NULL)
            put("calendar_seen_title", "Erstgespräch")
        }

        val values = Rows.toValues(row, row.keys().asSequence().toSet())

        assertEquals("ok", values.getAsString("calendar_state"))
        assertTrue(values.containsKey("calendar_error"))
        assertFalse(values.containsKey("calendar_seen_title"))
    }

    @Test
    fun `appointments carry their attendees and whether changing them notifies`() {
        val appointments = columns("appointments")
        assertTrue(appointments.contains("attendees"))
        assertTrue(appointments.contains("attendees_notify"))
    }

    @Test
    fun `when a visit went missing and was confirmed here stays on the device`() {
        val row = JSONObject().apply {
            put("id", "A1")
            put("calendar_missing_since", 1L)
            put("calendar_ok_since", 2L)
        }

        val values = Rows.toValues(row, row.keys().asSequence().toSet())

        assertFalse(values.containsKey("calendar_missing_since"))
        assertFalse(values.containsKey("calendar_ok_since"))
        val appointments = columns("appointments")
        assertTrue(appointments.contains("calendar_missing_since"))
        assertTrue(appointments.contains("calendar_ok_since"))
    }
}
