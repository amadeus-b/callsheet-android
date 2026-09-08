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
        for (table in listOf("businesses", "calls", "contacts", "contact_numbers")) {
            assertTrue("dirty missing on $table", columns(table).contains("dirty"))
        }
    }

    @Test
    fun `calls and numbers carry their own updated_at`() {
        assertTrue(columns("calls").contains("updated_at"))
        assertTrue(columns("contact_numbers").contains("updated_at"))
    }

    @Test
    fun `there is a table for tombstones`() {
        assertEquals(setOf("table_name", "row_id", "deleted_at"), columns("deletions"))
    }

    @Test
    fun `an upgrade from version one keeps the work`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("callsheet.db")
        // Version 1 nachbauen, eine Arbeitszeile hineinschreiben, dann hochziehen.
        val alt = ctx.openOrCreateDatabase("callsheet.db", 0, null)
        alt.execSQL("CREATE TABLE businesses (place_id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'new', note TEXT, updated_at TEXT NOT NULL)")
        alt.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, started_at TEXT NOT NULL, duration_seconds INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'call')")
        alt.execSQL("CREATE TABLE contacts (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)")
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
}
