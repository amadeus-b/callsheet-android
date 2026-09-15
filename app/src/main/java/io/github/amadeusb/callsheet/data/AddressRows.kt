package io.github.amadeusb.callsheet.data

import android.database.sqlite.SQLiteDatabase

/**
 * The database side of addresses that both [Repository] and
 * [io.github.amadeusb.callsheet.sync.SyncStore] need. Each call runs inside the
 * caller's transaction.
 */
object AddressRows {

    /**
     * Brings a business's `search_text` up to its name and the cities of all its
     * addresses ([Addresses.searchText]). Derived, not a change: no
     * `updated_at`, no mark, and no write when the text is already right.
     */
    fun refreshSearchText(db: SQLiteDatabase, placeId: String) {
        val name = db.rawQuery("SELECT name FROM businesses WHERE place_id = ?", arrayOf(placeId))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        val cities = ArrayList<String?>()
        db.rawQuery(
            "SELECT city FROM business_addresses WHERE place_id = ? ORDER BY position IS NULL, position, id",
            arrayOf(placeId),
        ).use { c ->
            while (c.moveToNext()) cities.add(if (c.isNull(0)) null else c.getString(0))
        }
        val text = Addresses.searchText(name, cities)
        db.execSQL(
            "UPDATE businesses SET search_text = ? WHERE place_id = ? AND (search_text IS NULL OR search_text <> ?)",
            arrayOf(text, placeId, text),
        )
    }

    /** The business's `main-` address was removed — see Database's `removed_main_addresses`. */
    fun rememberMainRemoved(db: SQLiteDatabase, placeId: String) {
        db.execSQL("INSERT OR IGNORE INTO removed_main_addresses (place_id) VALUES (?)", arrayOf(placeId))
    }

    /** The business's `main-` address is back. */
    fun forgetMainRemoved(db: SQLiteDatabase, placeId: String) {
        db.delete("removed_main_addresses", "place_id = ?", arrayOf(placeId))
    }

    fun mainRemoved(db: SQLiteDatabase, placeId: String): Boolean =
        db.rawQuery("SELECT COUNT(*) FROM removed_main_addresses WHERE place_id = ?", arrayOf(placeId))
            .use { it.moveToFirst() && it.getInt(0) > 0 }
}
