package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Database
import org.junit.Assert.assertEquals
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
        alt.version = 1
        alt.close()

        val db = Database(ctx).writableDatabase
        db.rawQuery("SELECT note, dirty FROM businesses WHERE place_id = 'P1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Rückruf", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
    }
}
