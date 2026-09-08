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
                dirty           INTEGER NOT NULL DEFAULT 0
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
        db.execSQL(TABLE_DELETIONS)
        db.execSQL("CREATE INDEX idx_businesses_status ON businesses(status)")
        db.execSQL("CREATE INDEX idx_businesses_industry ON businesses(industry)")
        db.execSQL("CREATE INDEX idx_businesses_is_target ON businesses(is_target)")
        db.execSQL("CREATE INDEX idx_businesses_follow_up ON businesses(follow_up_at)")
        db.execSQL("CREATE INDEX idx_businesses_search_text ON businesses(search_text)")
        db.execSQL("CREATE INDEX idx_calls_place_id ON calls(place_id)")
        db.execSQL(INDEX_CONTACTS)
        db.execSQL(INDEX_NUMBERS)
        db.execSQL("CREATE INDEX idx_businesses_dirty ON businesses(dirty)")
        db.execSQL("CREATE INDEX idx_calls_dirty ON calls(dirty)")
        db.execSQL("CREATE INDEX idx_contacts_dirty ON contacts(dirty)")
        db.execSQL("CREATE INDEX idx_contact_numbers_dirty ON contact_numbers(dirty)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Never discard working data — only add to it.
        if (old < 2) {
            for (table in listOf("businesses", "calls", "contacts", "contact_numbers")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX idx_${table}_dirty ON $table(dirty)")
            }
            // Anrufe und Rufnummern hatten keinen eigenen Änderungszeitpunkt. Ohne
            // ihn liesse sich für eine nachgetragene Notiz nicht entscheiden, welche
            // Fassung die jüngere ist. Der Bestand erbt seinen Anrufzeitpunkt, damit
            // keine Zeile ohne Wert dasteht.
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
    }

    companion object {
        const val NAME = "callsheet.db"
        const val VERSION = 2

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

        private const val INDEX_CONTACTS =
            "CREATE INDEX idx_contacts_place_id ON contacts(place_id)"

        private const val INDEX_NUMBERS =
            "CREATE INDEX idx_contact_numbers_contact ON contact_numbers(contact_id)"

        /**
         * Tombstones. Contacts and their numbers are the only rows the app deletes;
         * without a marker a deletion would come back with the next sync.
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
