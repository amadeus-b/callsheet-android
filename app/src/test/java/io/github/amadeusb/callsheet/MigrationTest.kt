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
                "name TEXT NOT NULL, role TEXT, email TEXT, note TEXT, position INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL, contact_version INTEGER)"
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

    /**
     * The version 3 schema, as it shipped in 1.2.0–1.3.1: version 2 plus the
     * appointment columns on businesses. alt-1 has an appointment linked to an
     * event on this device, alt-2 one without a link, alt-3 none. All three
     * already synchronised (dirty = 0).
     */
    private fun createVersionThree() {
        createVersionTwo()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        for (column in listOf(
            "appointment_at TEXT", "appointment_end_at TEXT", "appointment_location TEXT",
            "calendar_event_id INTEGER", "latitude REAL", "longitude REAL",
        )) {
            db.execSQL("ALTER TABLE businesses ADD COLUMN $column")
        }
        db.execSQL("CREATE INDEX idx_businesses_appointment ON businesses(appointment_at)")
        db.execSQL(
            "UPDATE businesses SET appointment_at = '2026-09-10T14:00:00+02:00', " +
                "appointment_end_at = '2026-09-10T15:00:00+02:00', " +
                "appointment_location = 'Musterstraße 39, 85055 Ingolstadt', calendar_event_id = 4711, dirty = 0 " +
                "WHERE place_id = 'alt-1'"
        )
        db.execSQL(
            "INSERT INTO businesses (place_id, name, status, updated_at, dirty, appointment_at, appointment_end_at) " +
                "VALUES ('alt-2', 'Zweiter Betrieb', 'appointment', '2026-09-08T09:00:00+02:00', 0, " +
                "'2026-09-12T09:00:00+02:00', '2026-09-12T10:00:00+02:00')"
        )
        db.execSQL(
            "INSERT INTO businesses (place_id, name, status, updated_at, dirty) " +
                "VALUES ('alt-3', 'Ohne Termin', 'new', '2026-09-08T09:00:00+02:00', 0)"
        )
        db.version = 3
        db.close()
    }

    /**
     * The version 4 schema, as it shipped in 1.4.0: version 3 with its
     * appointment carried over into the appointments table, and the contact
     * columns the version 5 migration reads. alt-1 keeps the follow-up it had
     * since version 1 and is synchronised; alt-2 has one that has not been
     * uploaded yet; alt-3 has none.
     */
    private fun createVersionFour() {
        createVersionThree()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        val contactColumns = columnsOf("contacts", db)
        for (column in listOf("role TEXT", "email TEXT", "note TEXT", "contact_version INTEGER")) {
            if (column.substringBefore(' ') !in contactColumns) db.execSQL("ALTER TABLE contacts ADD COLUMN $column")
        }
        db.execSQL(
            """
            CREATE TABLE appointments (
                id                      TEXT PRIMARY KEY,
                place_id                TEXT NOT NULL,
                starts_at               TEXT NOT NULL,
                ends_at                 TEXT,
                location                TEXT,
                note                    TEXT,
                contact_id              TEXT,
                updated_at              TEXT NOT NULL,
                event_uid               TEXT,
                calendar_event_id       INTEGER,
                calendar_seen_starts_at TEXT,
                calendar_seen_ends_at   TEXT,
                calendar_seen_location  TEXT,
                dirty                   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO appointments (id, place_id, starts_at, ends_at, updated_at, dirty) " +
                "VALUES ('legacy-alt-1', 'alt-1', '2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', " +
                "'2026-09-07T12:00:00+02:00', 0)"
        )
        db.execSQL(
            "UPDATE businesses SET appointment_at = NULL, appointment_end_at = NULL, " +
                "appointment_location = NULL, calendar_event_id = NULL"
        )
        db.execSQL("UPDATE businesses SET follow_up_at = '2026-09-15T09:00:00+02:00', dirty = 1 WHERE place_id = 'alt-2'")
        db.version = 4
        db.close()
    }

    /**
     * The version 6 schema, as 1.5.0 ships it: version 4 plus contact emails (5)
     * and the appointment kind (6). alt-1 carries a full imported address with
     * coordinates and is synchronised, alt-2 only a city and is not uploaded
     * yet, alt-3 an empty street — no address. alt-1 has a contact person.
     */
    private fun createVersionSix() {
        createVersionFour()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            "CREATE TABLE contact_emails (id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, email TEXT NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0, updated_at TEXT, dirty INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("ALTER TABLE appointments ADD COLUMN kind TEXT")
        db.execSQL("ALTER TABLE appointments ADD COLUMN done_at TEXT")
        db.execSQL("UPDATE businesses SET follow_up_at = NULL")
        db.execSQL(
            "UPDATE businesses SET street = 'Musterstraße 39', postal_code = '85055', city = 'Ingolstadt', " +
                "latitude = 48.7651, longitude = 11.4237, dirty = 0 WHERE place_id = 'alt-1'"
        )
        db.execSQL("UPDATE businesses SET city = 'Gaimersheim', dirty = 1 WHERE place_id = 'alt-2'")
        db.execSQL("UPDATE businesses SET street = '', dirty = 0 WHERE place_id = 'alt-3'")
        db.execSQL(
            "INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) " +
                "VALUES ('k-1', 'alt-1', 'Erika Beispiel', 0, '2026-09-07T12:00:00+02:00', 0)"
        )
        db.version = 6
        db.close()
    }

    /**
     * The version 7 schema, as the business-addresses release builds it:
     * version 6 with the address table, a contact's address and the local
     * table of removed main addresses. alt-1's visit has an event the app
     * wrote into the calendar itself. No carried-over address rows: the
     * upgrade to 8 touches appointments only.
     */
    private fun createVersionSeven() {
        createVersionSix()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            "CREATE TABLE business_addresses (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, label TEXT, street TEXT, " +
                "postal_code TEXT, city TEXT, latitude REAL, longitude REAL, position INTEGER, " +
                "updated_at TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_business_addresses_place_id ON business_addresses(place_id)")
        db.execSQL("CREATE INDEX idx_business_addresses_dirty ON business_addresses(dirty)")
        db.execSQL("ALTER TABLE contacts ADD COLUMN address_id TEXT")
        db.execSQL("CREATE TABLE removed_main_addresses (place_id TEXT PRIMARY KEY)")
        db.execSQL(
            "UPDATE appointments SET kind = 'visit', event_uid = 'legacy-alt-1', calendar_event_id = 4711 " +
                "WHERE id = 'legacy-alt-1'"
        )
        db.version = 7
        db.close()
    }

    /**
     * The version 8 schema, as 1.5.0 ships it: version 7 with a visit's title,
     * invitation and calendar state. The upgrade to 9 touches businesses only.
     */
    private fun createVersionEight() {
        createVersionSeven()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        for (column in listOf("title TEXT", "invite_email TEXT", "calendar_state TEXT", "calendar_error TEXT", "calendar_seen_title TEXT")) {
            db.execSQL("ALTER TABLE appointments ADD COLUMN $column")
        }
        db.version = 8
        db.close()
    }

    /**
     * The version 9 schema, as package 1 builds it: version 8 with a business's
     * hand-edited fields. alt-1's visit invites somebody, as a 1.5.0 phone saved
     * it — with the whitespace the server keeps in `invite_email` too.
     */
    private fun createVersionNine() {
        createVersionEight()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL("ALTER TABLE businesses ADD COLUMN edited_fields TEXT")
        db.execSQL("UPDATE appointments SET invite_email = ' test@example.org ' WHERE id = 'legacy-alt-1'")
        db.version = 9
        db.close()
    }

    /** The version 10 schema, as 1.5.1 ships it: version 9 with a visit's attendees. */
    private fun createVersionTen() {
        createVersionNine()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL("ALTER TABLE appointments ADD COLUMN attendees TEXT")
        db.execSQL("ALTER TABLE appointments ADD COLUMN attendees_notify INTEGER")
        db.version = 10
        db.close()
    }

    private fun columnsOf(table: String, db: android.database.sqlite.SQLiteDatabase): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) else null }.toSet()
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
            // Carried over into appointments as a callback since schema 6.
            assertTrue(c.isNull(2))
        }
        // The follow-up itself is not lost — it arrives as a callback.
        db.rawQuery(
            "SELECT starts_at, kind, updated_at FROM appointments WHERE id = 'followup-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-09-10T09:00:00+02:00", c.getString(0))
            assertEquals("callback", c.getString(1))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(2))
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

    // --- from version 3, the road 1.3.x devices are on ----------------------

    @Test
    fun `an upgrade from version three carries each appointment over as a row of its own`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, starts_at, ends_at, location, note, contact_id, event_uid, updated_at " +
                "FROM appointments WHERE id LIKE 'legacy-%' ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("legacy-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(2))
            assertEquals("2026-09-10T15:00:00+02:00", c.getString(3))
            assertEquals("Musterstraße 39, 85055 Ingolstadt", c.getString(4))
            assertTrue(c.isNull(5))
            assertTrue(c.isNull(6))
            assertTrue(c.isNull(7))
            // The business's timestamp, so the server's carried-over row meets
            // this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(8))
            assertTrue(c.moveToNext())
            assertEquals("legacy-alt-2", c.getString(0))
        }
    }

    @Test
    fun `an upgrade from version three keeps the calendar link and what this device saw`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT calendar_event_id, calendar_seen_starts_at, calendar_seen_ends_at, calendar_seen_location " +
                "FROM appointments WHERE id = 'legacy-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4711L, c.getLong(0))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(1))
            assertEquals("2026-09-10T15:00:00+02:00", c.getString(2))
            assertEquals("Musterstraße 39, 85055 Ingolstadt", c.getString(3))
        }
        // Without a link there is nothing this device has seen.
        db.rawQuery(
            "SELECT calendar_event_id, calendar_seen_starts_at FROM appointments WHERE id = 'legacy-alt-2'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun `an upgrade from version three marks the carried-over rows for upload`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        // The legacy rows only: alt-1's follow-up is carried over and marked too, by schema 6.
        db.rawQuery("SELECT COUNT(*) FROM appointments WHERE dirty = 1 AND id LIKE 'legacy-%'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }

    @Test
    fun `an upgrade from version three empties the old columns without marking the business`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT appointment_at, appointment_end_at, appointment_location, calendar_event_id, dirty, updated_at " +
                "FROM businesses WHERE place_id = 'alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            for (i in 0..3) assertTrue("column $i not emptied", c.isNull(i))
            assertEquals(0, c.getInt(4))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(5))
        }
        assertTrue(columnsOfBusinesses().containsAll(appointmentColumns))
    }

    @Test
    fun `a fresh database and an upgraded one have the same appointments table`() {
        createVersionThree()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("appointments", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("appointments", Database(context).readableDatabase)

        // PRAGMA on a missing table returns no columns on both sides, which
        // would compare equal before the table exists.
        assertTrue("event_uid" in fresh)
        assertTrue("kind" in fresh)
        assertTrue("done_at" in fresh)
        assertTrue("title" in fresh)
        assertTrue("calendar_state" in fresh)
        assertTrue("calendar_seen_title" in fresh)
        assertEquals(fresh, upgraded)
    }

    // --- from version 4, the road 1.4.0 devices are on ----------------------

    @Test
    fun `an upgrade from version four carries each follow-up over as a callback`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, starts_at, ends_at, location, note, contact_id, event_uid, updated_at, " +
                "kind, done_at, calendar_event_id, dirty FROM appointments WHERE kind = 'callback' ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("followup-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertEquals("2026-09-10T09:00:00+02:00", c.getString(2))
            for (i in 3..7) assertTrue("column $i not null", c.isNull(i))
            // The business's timestamp, so the server's carried-over row meets this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(8))
            assertEquals("callback", c.getString(9))
            assertTrue(c.isNull(10))
            // No calendar event: each device would create its own.
            assertTrue(c.isNull(11))
            // Sent up although the business is synchronised: a 1.4.0 phone whose
            // follow-up reached the server after 008 left it in follow_up_at, which
            // nothing reads there. A server that has the row takes it as a standstill.
            assertEquals(1, c.getInt(12))
            assertTrue(c.moveToNext())
            assertEquals("followup-alt-2", c.getString(0))
            assertEquals("2026-09-15T09:00:00+02:00", c.getString(2))
            assertEquals("2026-09-08T09:00:00+02:00", c.getString(8))
            // Not uploaded yet: goes up as a callback.
            assertEquals(1, c.getInt(12))
        }
    }

    /**
     * Rollout is server first. A 1.4.0 phone syncing after migration 008 pulled
     * the server's carried-over callback for alt-1 and stored it with the
     * columns it had — no kind, no done_at — while alt-1's follow_up_at stayed
     * set: 008 gave the business no new sequence number. Here the callback was
     * also moved on another device since, so the pulled row is ahead of the
     * follow-up it came from.
     */
    private fun createVersionFourWithAPulledCallback() {
        createVersionFour()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, dirty) " +
                "VALUES ('followup-alt-1', 'alt-1', '2026-09-10T10:30:00+02:00', '2026-09-08T08:00:00+02:00', 0)"
        )
        db.close()
    }

    @Test
    fun `an upgrade from version four keeps a callback pulled while on 1_4_0, and makes it one`() {
        createVersionFourWithAPulledCallback()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT starts_at, updated_at, dirty, kind, done_at FROM appointments WHERE id = 'followup-alt-1'",
            null,
        ).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            // What came from the server stays; the follow-up behind it is older.
            assertEquals("2026-09-10T10:30:00+02:00", c.getString(0))
            assertEquals("2026-09-08T08:00:00+02:00", c.getString(1))
            assertEquals(0, c.getInt(2))
            // Only the kind the old schema could not hold is added.
            assertEquals("callback", c.getString(3))
            assertTrue(c.isNull(4))
        }
        // The other follow-up is carried over as before.
        db.rawQuery("SELECT kind FROM appointments WHERE id = 'followup-alt-2'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
        }
    }

    @Test
    fun `an upgrade from version four empties follow_up_at without marking the business`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT place_id, follow_up_at, dirty, updated_at FROM businesses ORDER BY place_id", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("alt-1", c.getString(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(3))
            while (c.moveToNext()) assertTrue("follow_up_at left on ${c.getString(0)}", c.isNull(1))
        }
    }

    @Test
    fun `an upgrade from version four leaves appointments on site without a kind`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT kind, done_at, dirty FROM appointments WHERE id = 'legacy-alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
    }

    // --- from version 6, the road 1.5.0 devices are on ----------------------

    @Test
    fun `an upgrade from version six carries each address over as the main address`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, label, street, postal_code, city, latitude, longitude, position, updated_at, dirty " +
                "FROM business_addresses ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("main-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals("Musterstraße 39", c.getString(3))
            assertEquals("85055", c.getString(4))
            assertEquals("Ingolstadt", c.getString(5))
            assertEquals(48.7651, c.getDouble(6), 0.0)
            assertEquals(11.4237, c.getDouble(7), 0.0)
            assertEquals(0, c.getInt(8))
            // The business's timestamp, so the server's carried-over row meets this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(9))
            // Sent up: a server that has the row takes it as a standstill.
            assertEquals(1, c.getInt(10))
            assertTrue(c.moveToNext())
            assertEquals("main-alt-2", c.getString(0))
            assertTrue(c.isNull(3))
            assertEquals("Gaimersheim", c.getString(5))
            assertEquals("2026-09-08T09:00:00+02:00", c.getString(9))
        }
    }

    @Test
    fun `an upgrade from version six keeps the old address columns and leaves the businesses unmarked`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT street, city, dirty, updated_at FROM businesses WHERE place_id = 'alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Musterstraße 39", c.getString(0))
            assertEquals("Ingolstadt", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(3))
        }
    }

    @Test
    fun `an upgrade from version six gives contacts an empty address and leaves them unmarked`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT address_id, dirty FROM contacts WHERE id = 'k-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(0, c.getInt(1))
        }
    }

    @Test
    fun `a fresh database and an upgraded one have the same address columns`() {
        createVersionSix()
        val upgraded = Database(context).readableDatabase.let { db ->
            (columnsOf("business_addresses", db) + columnsOf("contacts", db).map { "contacts.$it" } +
                columnsOf("removed_main_addresses", db).map { "removed.$it" }).also { db.close() }
        }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = Database(context).readableDatabase.let { db ->
            columnsOf("business_addresses", db) + columnsOf("contacts", db).map { "contacts.$it" } +
                columnsOf("removed_main_addresses", db).map { "removed.$it" }
        }

        // PRAGMA on a missing table returns no columns on both sides.
        assertTrue("position" in fresh)
        assertTrue("contacts.address_id" in fresh)
        assertTrue("removed.place_id" in fresh)
        assertEquals(fresh, upgraded)
    }

    // --- from version 7, the road business-address devices are on -----------

    @Test
    fun `an upgrade from version seven adds the invitation and calendar columns empty`() {
        createVersionSeven()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT starts_at, event_uid, calendar_event_id, dirty, " +
                "title, invite_email, calendar_state, calendar_error, calendar_seen_title " +
                "FROM appointments WHERE id = 'legacy-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(0))
            assertEquals("legacy-alt-1", c.getString(1))
            assertEquals(4711L, c.getLong(2))
            // Nothing new to tell the server.
            assertEquals(0, c.getInt(3))
            for (i in 4..8) assertTrue("column $i not null", c.isNull(i))
        }
    }

    // --- from version 8, the road 1.5.0 devices are on -------------------------

    @Test
    fun `an upgrade from version eight adds edited_fields empty and leaves the businesses unmarked`() {
        createVersionEight()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT name, edited_fields, dirty FROM businesses WHERE place_id = 'alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Bestandsbetrieb", c.getString(0))
            assertTrue(c.isNull(1))
            // Nothing new to tell the server.
            assertEquals(0, c.getInt(2))
        }
    }

    // --- from version 9, the road package 1 devices are on --------------------

    @Test
    fun `an upgrade from version nine carries the invitation over as the only attendee and marks nothing`() {
        createVersionNine()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT attendees, attendees_notify, invite_email, dirty FROM appointments WHERE id = 'legacy-alt-1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[\"test@example.org\"]", c.getString(0))
            assertTrue(c.isNull(1))
            // Kept, as the old address columns were.
            assertEquals(" test@example.org ", c.getString(2))
            assertEquals(0, c.getInt(3))
        }
        db.rawQuery("SELECT COUNT(*) FROM appointments WHERE attendees IS NOT NULL", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun `a fresh database and one upgraded from version eight have the same business columns`() {
        createVersionEight()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("businesses", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("businesses", Database(context).readableDatabase)

        // PRAGMA on a missing table returns no columns on both sides.
        assertTrue("edited_fields" in fresh)
        assertEquals(fresh, upgraded)
    }

    // --- from version 10, the road 1.5.1 devices are on -----------------------

    @Test
    fun `an upgrade from version ten adds the missing and ok moments empty and marks nothing`() {
        createVersionTen()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT calendar_missing_since, calendar_ok_since, dirty, updated_at FROM appointments WHERE id = 'legacy-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            // Empty is unknown — never zero, or every visit would count as confirmed long ago.
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
    }

    @Test
    fun `a fresh database and one upgraded from version ten have the same appointment columns`() {
        createVersionTen()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("appointments", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("appointments", Database(context).readableDatabase)

        assertTrue("calendar_ok_since" in fresh)
        assertEquals(fresh, upgraded)
    }

    // --- and a database that never had to migrate at all ---------------------

    @Test
    fun `a fresh database has the same columns`() {
        assertTrue(columnsOfBusinesses().containsAll(appointmentColumns))
    }
}
