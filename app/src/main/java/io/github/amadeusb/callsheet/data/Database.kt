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
                search_text     TEXT
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
                contact          TEXT
            )
            """.trimIndent()
        )
        db.execSQL(TABLE_CONTACTS)
        db.execSQL(TABLE_NUMBERS)
        db.execSQL("CREATE INDEX idx_businesses_status ON businesses(status)")
        db.execSQL("CREATE INDEX idx_businesses_industry ON businesses(industry)")
        db.execSQL("CREATE INDEX idx_businesses_is_target ON businesses(is_target)")
        db.execSQL("CREATE INDEX idx_businesses_follow_up ON businesses(follow_up_at)")
        db.execSQL("CREATE INDEX idx_businesses_search_text ON businesses(search_text)")
        db.execSQL("CREATE INDEX idx_calls_place_id ON calls(place_id)")
        db.execSQL(INDEX_CONTACTS)
        db.execSQL(INDEX_NUMBERS)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Never discard working data — only add to it. No migrations exist yet;
        // the schema below is the first one that shipped.
    }

    companion object {
        const val NAME = "callsheet.db"
        const val VERSION = 1

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
                contact_version INTEGER
            )
        """

        /** Several numbers per contact, each with its type (mobile, work …). */
        private const val TABLE_NUMBERS = """
            CREATE TABLE contact_numbers (
                id         TEXT PRIMARY KEY,
                contact_id TEXT NOT NULL,
                number     TEXT NOT NULL,
                kind       TEXT NOT NULL DEFAULT 'other',
                position   INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val INDEX_CONTACTS =
            "CREATE INDEX idx_contacts_place_id ON contacts(place_id)"

        private const val INDEX_NUMBERS =
            "CREATE INDEX idx_contact_numbers_contact ON contact_numbers(contact_id)"
    }
}
