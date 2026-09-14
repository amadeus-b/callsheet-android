package io.github.amadeusb.callsheet.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's local SQLite database.
 *
 * The layout mirrors what a server would hold later, so synchronisation can be
 * bolted on without a migration (see docs/data-model.md).
 */
class Database(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
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
                -- Name and city in lower case. SQLite only lower-cases ASCII;
                -- without this column "müller" would not find "Müller".
                search_text     TEXT,
                -- Set on every local write, cleared once the server has it.
                dirty           INTEGER NOT NULL DEFAULT 0,
                -- The one appointment a business held up to schema 3. Emptied
                -- by the migration to 4 and read by nothing since — the
                -- appointments table holds them. Kept so a fresh and an
                -- upgraded database have the same columns.
                appointment_at       TEXT,
                appointment_end_at   TEXT,
                appointment_location TEXT,
                calendar_event_id    INTEGER,
                -- Master data from the import, filled like every other imported
                -- column. Nothing reads them yet.
                latitude             REAL,
                longitude            REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE calls (
                id               TEXT PRIMARY KEY,
                place_id         TEXT NOT NULL,
                started_at       TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                outcome          TEXT,
                note             TEXT,
                -- 'call' or 'note': whether the entry came out of a dial
                -- attempt or was recorded without calling anyone.
                kind             TEXT NOT NULL DEFAULT 'call',
                -- Who was called, e.g. "Frau Meier · Mobil".
                contact          TEXT,
                updated_at       TEXT,
                dirty            INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(TABLE_CONTACTS)
        db.execSQL(TABLE_NUMBERS)
        db.execSQL(TABLE_EMAILS)
        db.execSQL(TABLE_DELETIONS)
        db.execSQL(TABLE_APPOINTMENTS)
        for (sql in INDEXES_APPOINTMENTS) db.execSQL(sql)
        db.execSQL("CREATE INDEX idx_businesses_status ON businesses(status)")
        db.execSQL("CREATE INDEX idx_businesses_industry ON businesses(industry)")
        db.execSQL("CREATE INDEX idx_businesses_is_target ON businesses(is_target)")
        db.execSQL("CREATE INDEX idx_businesses_follow_up ON businesses(follow_up_at)")
        db.execSQL("CREATE INDEX idx_businesses_search_text ON businesses(search_text)")
        db.execSQL("CREATE INDEX idx_businesses_appointment ON businesses(appointment_at)")
        db.execSQL("CREATE INDEX idx_calls_place_id ON calls(place_id)")
        db.execSQL(INDEX_CONTACTS)
        db.execSQL(INDEX_NUMBERS)
        db.execSQL(INDEX_EMAILS)
        db.execSQL("CREATE INDEX idx_businesses_dirty ON businesses(dirty)")
        db.execSQL("CREATE INDEX idx_calls_dirty ON calls(dirty)")
        db.execSQL("CREATE INDEX idx_contacts_dirty ON contacts(dirty)")
        db.execSQL("CREATE INDEX idx_contact_numbers_dirty ON contact_numbers(dirty)")
        db.execSQL("CREATE INDEX idx_contact_emails_dirty ON contact_emails(dirty)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Never discard working data — only add to it.
        if (old < 2) {
            for (table in listOf("businesses", "calls", "contacts", "contact_numbers")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX idx_${table}_dirty ON $table(dirty)")
                // A row that existed before synchronisation was added has never
                // been sent anywhere. Leaving it at the column's default of 0
                // would tell the sync engine there is nothing to upload — the
                // device's entire stock would silently never reach the server.
                db.execSQL("UPDATE $table SET dirty = 1")
            }
            // Calls and phone numbers had no change timestamp of their own. Without
            // one, there would be no way to tell which version is newer for a note
            // added later. Existing rows inherit their call time, so no row is
            // left without a value.
            db.execSQL("ALTER TABLE calls ADD COLUMN updated_at TEXT")
            db.execSQL("UPDATE calls SET updated_at = started_at WHERE updated_at IS NULL")
            db.execSQL("ALTER TABLE contact_numbers ADD COLUMN updated_at TEXT")
            db.execSQL(
                "UPDATE contact_numbers SET updated_at = " +
                    "(SELECT updated_at FROM contacts WHERE contacts.id = contact_numbers.contact_id) " +
                    "WHERE updated_at IS NULL"
            )
            db.execSQL(TABLE_DELETIONS)
        }
        // Two separate ifs, never an else if: a device still on version 1 has to
        // walk through the block above and then this one, in that order.
        if (old < 3) {
            // Appointments. Nothing to mark dirty here: the columns arrive
            // empty, so no existing row has anything new to tell the server.
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_at TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_end_at TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_location TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN calendar_event_id INTEGER")
            db.execSQL("ALTER TABLE businesses ADD COLUMN latitude REAL")
            db.execSQL("ALTER TABLE businesses ADD COLUMN longitude REAL")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_businesses_appointment ON businesses(appointment_at)")
        }
        if (old < 4) {
            db.execSQL(TABLE_APPOINTMENTS)
            for (sql in INDEXES_APPOINTMENTS) db.execSQL(sql)
            // The one appointment per business becomes a row. The id is fixed,
            // not a fresh UUID: the server's migration 005 writes the same
            // 'legacy-' || place_id, so the two meet as one row instead of
            // every appointment existing twice. updated_at comes from the
            // business for the same reason — where it was synchronised, both
            // sides hold the same timestamp.
            //
            // The link to the event stays, and what this device saw in the
            // event is taken from the old columns: the read-back has kept the
            // two in step so far. The UID is not known yet; the first
            // read-back that finds the event stores it.
            //
            // Marked dirty: an appointment never uploaded still reaches the
            // server, and one the server already has arrives as a standstill.
            db.execSQL(
                """
                INSERT INTO appointments (
                    id, place_id, starts_at, ends_at, location, updated_at,
                    calendar_event_id, calendar_seen_starts_at, calendar_seen_ends_at, calendar_seen_location,
                    dirty
                )
                SELECT 'legacy-' || place_id, place_id, appointment_at, appointment_end_at, appointment_location, updated_at,
                       calendar_event_id,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_at END,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_end_at END,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_location END,
                       1
                FROM businesses
                WHERE appointment_at IS NOT NULL AND appointment_at <> ''
                """.trimIndent()
            )
            // Emptied on both sides, or the server would fill the gap straight
            // back on the next standstill. Not a change to the business: no
            // updated_at, no mark.
            db.execSQL(
                "UPDATE businesses SET appointment_at = NULL, appointment_end_at = NULL, " +
                    "appointment_location = NULL, calendar_event_id = NULL " +
                    "WHERE appointment_at IS NOT NULL OR appointment_end_at IS NOT NULL " +
                    "OR appointment_location IS NOT NULL OR calendar_event_id IS NOT NULL"
            )
        }
        if (old < 5) {
            // Several emails per contact, the same way contact_numbers already
            // holds several phone numbers. Backfilled from the contact's
            // single `email` column, which stays as it is — kept for backward
            // compatibility, but no longer read as the source of the new
            // email-list functionality.
            db.execSQL(TABLE_EMAILS)
            db.execSQL(INDEX_EMAILS)
            db.execSQL("CREATE INDEX idx_contact_emails_dirty ON contact_emails(dirty)")
            db.execSQL(
                """
                INSERT INTO contact_emails (id, contact_id, email, position, updated_at, dirty)
                SELECT 'legacy-' || id, id, email, 0, updated_at, 1
                FROM contacts
                WHERE email IS NOT NULL AND email <> ''
                """.trimIndent()
            )
            // The rename of the "email_promised" status to "mail_sent": the
            // app now sends the mail itself instead of only noting the
            // promise to do so.
            db.execSQL("UPDATE businesses SET status = 'mail_sent' WHERE status = 'email_promised'")
        }
    }

    /**
     * A no-op on purpose. The default implementation throws, which means
     * sideloading an older build over a newer one — common for people using
     * Obtainium — leaves the database unable to open at all. Recovery would
     * mean reinstalling, which wipes every business and every call ever
     * logged. Leaving the schema exactly as the newer version created it
     * costs the older build only synchronisation, never the stock.
     */
    override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
    }

    companion object {
        const val NAME = "callsheet.db"
        const val VERSION = 5

        @Volatile
        private var shared: Database? = null

        /**
         * One helper for the whole process. [Repository] and [io.github.amadeusb.callsheet.sync.SyncStore]
         * both write to this file, and a sync can start right after a call is
         * logged while the user is already editing the next business — two
         * separate [SQLiteOpenHelper] instances would each open their own
         * connection and contend on file locks instead of sharing the
         * in-process lock a single connection gets for free.
         */
        fun instance(context: Context): Database =
            shared ?: synchronized(this) {
                shared ?: Database(context.applicationContext).also { shared = it }
            }

        /**
         * Test-only escape hatch. A test that deletes the database file out
         * from under a cached connection (`Context.deleteDatabase`, used to
         * start each test from a clean slate) needs [instance] to open a
         * fresh one afterwards rather than keep writing to the now-unlinked
         * file the old connection still holds open.
         */
        internal fun resetSharedInstanceForTesting() {
            synchronized(this) {
                shared?.close()
                shared = null
            }
        }

        /**
         * Contacts only ever come into being inside the app, never from an
         * import. `id` is a UUID and stays stable — it later doubles as the
         * vCard UID for synchronisation over WebDAV.
         */
        private const val TABLE_CONTACTS = """
            CREATE TABLE contacts (
                id              TEXT PRIMARY KEY,
                place_id        TEXT NOT NULL,
                name            TEXT NOT NULL,
                role            TEXT,
                email           TEXT,
                note            TEXT,
                position        INTEGER NOT NULL DEFAULT 0,
                updated_at      TEXT NOT NULL,
                -- Version of the phone book entry at the last merge. A higher
                -- number there means the phone book was edited since.
                contact_version INTEGER,
                dirty           INTEGER NOT NULL DEFAULT 0
            )
        """

        /** Several numbers per contact, each with its type (mobile, work …). */
        private const val TABLE_NUMBERS = """
            CREATE TABLE contact_numbers (
                id         TEXT PRIMARY KEY,
                contact_id TEXT NOT NULL,
                number     TEXT NOT NULL,
                kind       TEXT NOT NULL DEFAULT 'other',
                position   INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT,
                dirty      INTEGER NOT NULL DEFAULT 0
            )
        """

        /** Several emails per contact, each with its position, mirroring TABLE_NUMBERS. */
        private const val TABLE_EMAILS = """
            CREATE TABLE contact_emails (
                id         TEXT PRIMARY KEY,
                contact_id TEXT NOT NULL,
                email      TEXT NOT NULL,
                position   INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT,
                dirty      INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val INDEX_CONTACTS =
            "CREATE INDEX idx_contacts_place_id ON contacts(place_id)"

        private const val INDEX_NUMBERS =
            "CREATE INDEX idx_contact_numbers_contact ON contact_numbers(contact_id)"

        private const val INDEX_EMAILS =
            "CREATE INDEX idx_contact_emails_contact ON contact_emails(contact_id)"

        /**
         * Appointments on site, several per business. Synchronised like
         * contacts: a UUID per row, deleted through tombstones.
         *
         * `event_uid` names the linked calendar event by its iCalendar UID,
         * which is the same on every device carrying the shared calendar, so it
         * travels. The `calendar_` columns do not (see Rows.LOCAL_ONLY): an
         * event's `_ID`, and what this device last saw in it, describe this
         * device's calendar provider and nothing else.
         */
        private const val TABLE_APPOINTMENTS = """
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
        """

        private val INDEXES_APPOINTMENTS = listOf(
            "CREATE INDEX idx_appointments_place_id ON appointments(place_id)",
            "CREATE INDEX idx_appointments_starts_at ON appointments(starts_at)",
            "CREATE INDEX idx_appointments_event_uid ON appointments(event_uid)",
            "CREATE INDEX idx_appointments_dirty ON appointments(dirty)",
        )

        /**
         * Tombstones. Contacts, their numbers and appointments are the only rows
         * the app deletes; without a marker a deletion would come back with the
         * next sync.
         */
        private const val TABLE_DELETIONS = """
            CREATE TABLE deletions (
                table_name TEXT NOT NULL,
                row_id     TEXT NOT NULL,
                deleted_at TEXT NOT NULL,
                PRIMARY KEY (table_name, row_id)
            )
        """
    }
}
