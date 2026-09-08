package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The appointment migration, on both roads that lead to it: from the schema that
 * shipped first, and from the one synchronisation left behind.
 *
 * A database in the field carries weeks of phone calls. Losing it would be the
 * worst bug this app could have, which is why the fixtures below are written out
 * by hand rather than generated — they have to keep saying what actually
 * shipped, even after Database.kt moves on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val appointmentColumns = listOf(
        "appointment_at", "appointment_end_at", "appointment_location",
        "calendar_event_id", "latitude", "longitude",
    )

    @Before
    fun aufbau() {
        context.deleteDatabase("callsheet.db")
    }

    /**
     * The version 1 schema, as it shipped in 1.0.2. All four synchronised tables:
     * the 1.1.0 migration alters every one of them on the way past, and would
     * throw on a fixture that only built `businesses`.
     */
    private fun createVersionOne() {
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            """
            CREATE TABLE businesses (
                place_id        TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                industry        TEXT,
                categories      TEXT,
                street          TEXT,
                postal_code     TEXT,
                city            TEXT,
                phone           TEXT,
                website         TEXT,
                email           TEXT,
                contact_name    TEXT,
                rating          REAL,
                rating_count    INTEGER,
                closed          INTEGER NOT NULL DEFAULT 0,
                is_target       INTEGER NOT NULL DEFAULT 0,
                origin          TEXT,
                collected_at    TEXT,
                status          TEXT NOT NULL DEFAULT 'new',
                note            TEXT,
                follow_up_at    TEXT,
                updated_at      TEXT NOT NULL,
                search_text     TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE TABLE calls (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, " +
                "started_at TEXT NOT NULL, duration_seconds INTEGER NOT NULL, outcome TEXT, " +
                "note TEXT, kind TEXT NOT NULL DEFAULT 'call', contact TEXT)"
        )
        db.execSQL(
            "CREATE TABLE contacts (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, " +
                "name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE contact_numbers (id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, " +
                "number TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'other', " +
                "position INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "INSERT INTO businesses (place_id, name, status, note, follow_up_at, updated_at) " +
                "VALUES ('alt-1', 'Bestandsbetrieb', 'called', 'Rückruf zugesagt', " +
                "'2026-09-10T09:00:00+02:00', '2026-09-07T12:00:00+02:00')"
        )
        db.version = 1
        db.close()
    }

    /**
     * The version 2 schema, as it shipped in 1.1.0: version 1 plus what
     * synchronisation added. Only the columns this test reads are spelled out.
     */
    private fun createVersionTwo() {
        createVersionOne()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        for (table in listOf("businesses", "calls", "contacts", "contact_numbers")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE calls ADD COLUMN updated_at TEXT")
        db.execSQL("ALTER TABLE contact_numbers ADD COLUMN updated_at TEXT")
        db.execSQL(
            "CREATE TABLE deletions (table_name TEXT NOT NULL, row_id TEXT NOT NULL, " +
                "deleted_at TEXT NOT NULL, PRIMARY KEY (table_name, row_id))"
        )
        db.execSQL("UPDATE businesses SET dirty = 1")
        db.version = 2
        db.close()
    }

    private fun columnsOfBusinesses(): Set<String> =
        Database(context).readableDatabase
            .rawQuery("PRAGMA table_info(businesses)", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(1) else null }.toSet()
            }

    // --- from version 1, the long road --------------------------------------

    @Test
    fun `an upgrade from version one keeps the working data`() {
        createVersionOne()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT status, note, follow_up_at FROM businesses WHERE place_id = ?",
            arrayOf("alt-1"),
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("called", c.getString(0))
            assertEquals("Rückruf zugesagt", c.getString(1))
            assertEquals("2026-09-10T09:00:00+02:00", c.getString(2))
        }
    }

    @Test
    fun `an upgrade from version one runs the sync migration too`() {
        createVersionOne()

        val db = Database(context).readableDatabase

        // Both blocks have to run, in order. If `old < 3` were an `else if`, or
        // sat before the sync block, this row would come out unmarked and the
        // device's entire stock would never reach the server.
        db.rawQuery("SELECT dirty FROM businesses WHERE place_id = ?", arrayOf("alt-1")).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        assertTrue(columnsOfBusinesses().containsAll(appointmentColumns))
    }

    // --- from version 2, the road real devices are on -----------------------

    @Test
    fun `an upgrade from version two adds the six columns empty`() {
        createVersionTwo()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT * FROM businesses WHERE place_id = ?", arrayOf("alt-1")).use { c ->
            assertTrue(c.moveToFirst())
            for (column in appointmentColumns) {
                val index = c.getColumnIndex(column)
                assertTrue("Spalte $column fehlt", index >= 0)
                assertTrue("Spalte $column ist nicht leer", c.isNull(index))
            }
        }
    }

    @Test
    fun `an upgrade from version two leaves synchronisation alone`() {
        createVersionTwo()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT note, dirty FROM businesses WHERE place_id = ?", arrayOf("alt-1")).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Rückruf zugesagt", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.rawQuery("SELECT COUNT(*) FROM deletions", null).use { c ->
            assertTrue(c.moveToFirst())
        }
    }

    // --- and a database that never had to migrate at all ---------------------

    @Test
    fun `a fresh database has the same columns`() {
        assertTrue(columnsOfBusinesses().containsAll(appointmentColumns))
    }
}
