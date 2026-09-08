package io.github.amadeusb.callsheet.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.amadeusb.callsheet.data.Database
import org.json.JSONArray
import org.json.JSONObject

/**
 * The database side of synchronisation: what is waiting to go up, and what
 * comes down. The rules here are the same ones the server applies — where they
 * disagree, data goes missing on one of the two sides.
 */
class SyncStore(context: Context) {

    private val helper = Database.instance(context)

    /** Rows marked as changed, at most [limit] across all tables together. */
    fun pending(limit: Int): JSONObject {
        val db = helper.readableDatabase
        val payload = JSONObject()
        var left = limit
        for (table in Rows.TABLES) {
            val rows = JSONArray()
            if (left > 0) {
                db.rawQuery("SELECT * FROM $table WHERE dirty = 1 LIMIT ?", arrayOf(left.toString())).use { c ->
                    while (c.moveToNext()) rows.put(Rows.toJson(c))
                }
                left -= rows.length()
            }
            payload.put(table, rows)
        }
        val deletions = JSONArray()
        if (left > 0) {
            db.rawQuery("SELECT table_name, row_id, deleted_at FROM deletions LIMIT ?", arrayOf(left.toString())).use { c ->
                while (c.moveToNext()) deletions.put(Rows.toJson(c))
            }
        }
        payload.put("geloescht", deletions)
        return payload
    }

    fun pendingCount(): Int {
        val db = helper.readableDatabase
        var total = 0
        for (table in Rows.TABLES) {
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE dirty = 1", null).use { if (it.moveToFirst()) total += it.getInt(0) }
        }
        db.rawQuery("SELECT COUNT(*) FROM deletions", null).use { if (it.moveToFirst()) total += it.getInt(0) }
        return total
    }

    /**
     * Clears the marks on exactly the rows that were sent — not on everything.
     * A row changed while the request was in flight must stay marked.
     */
    fun clearPending(payload: JSONObject) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (table in Rows.TABLES) {
                val rows = payload.optJSONArray(table) ?: continue
                val key = Rows.key(table)
                for (i in 0 until rows.length()) {
                    val sent = rows.getJSONObject(i)
                    val id = sent.getString(key)
                    // Only clear the mark if the row is still exactly the one
                    // that was sent. If it changed while the request was in
                    // flight, `updated_at` moved on and the row stays marked.
                    if (sent.isNull("updated_at")) {
                        db.execSQL(
                            "UPDATE $table SET dirty = 0 WHERE $key = ? AND updated_at IS NULL",
                            arrayOf(id),
                        )
                    } else {
                        db.execSQL(
                            "UPDATE $table SET dirty = 0 WHERE $key = ? AND updated_at = ?",
                            arrayOf(id, sent.getString("updated_at")),
                        )
                    }
                }
            }
            val deletions = payload.optJSONArray("geloescht") ?: JSONArray()
            for (i in 0 until deletions.length()) {
                val stone = deletions.getJSONObject(i)
                // Same guard as for rows: only clear a tombstone that is still
                // exactly the one that was sent. One freshly (re-)written while
                // the request was in flight — `deleted_at` moved on — stays.
                db.delete(
                    "deletions", "table_name = ? AND row_id = ? AND deleted_at = ?",
                    arrayOf(stone.getString("table_name"), stone.getString("row_id"), stone.getString("deleted_at")),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Applies what the server sent. Never marks anything as dirty. */
    fun apply(response: JSONObject) {
        val db = helper.writableDatabase
        // Fetched once per call rather than once per row — the schema does
        // not change mid-sync, and a first sync can carry a few thousand rows.
        val columnsByTable = Rows.TABLES.associateWith { columns(db, it) }
        db.beginTransaction()
        try {
            val deletions = response.optJSONArray("geloescht") ?: JSONArray()
            for (i in 0 until deletions.length()) applyTombstone(db, deletions.getJSONObject(i))
            for (table in Rows.TABLES) {
                val rows = response.optJSONArray(table) ?: continue
                for (i in 0 until rows.length()) applyRow(db, table, rows.getJSONObject(i), columnsByTable.getValue(table))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val names = HashSet<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            names
        }

    private fun applyRow(db: SQLiteDatabase, table: String, row: JSONObject, tableColumns: Set<String>) {
        val key = Rows.key(table)
        val id = row.getString(key)
        val remoteAt = row.optString("updated_at", null)

        val stone = tombstone(db, table, id)
        if (stone != null && !Merge.isNewer(remoteAt, stone)) return

        // A number's own row may still be untouched while its contact was
        // deleted: the tombstone lives on the parent, not on the number. The
        // server applies the same check in `empfangeKontakt`.
        if (table == "contact_numbers") {
            val contactId = row.optString("contact_id", null)
            val parentStone = if (contactId != null) tombstone(db, "contacts", contactId) else null
            if (parentStone != null && !Merge.isNewer(remoteAt, parentStone)) return
        }

        val local = db.rawQuery("SELECT * FROM $table WHERE $key = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) Rows.toJson(c) else null
        }

        val blocked = table == "businesses" &&
            (local?.optString("status") == Merge.BLOCKED || row.optString("status") == Merge.BLOCKED)

        if (local != null && !Merge.isNewer(remoteAt, local.optString("updated_at", null))) {
            if (blocked && local.optString("status") != Merge.BLOCKED) {
                db.update("businesses", ContentValues().apply {
                    put("status", Merge.BLOCKED)
                    put("dirty", 1)
                }, "place_id = ?", arrayOf(id))
            }
            return
        }

        val values = if (table == "calls" && local != null) {
            // A log: time, duration, kind and contact stay as recorded.
            ContentValues().apply {
                put("outcome", row.optString("outcome", null))
                put("note", row.optString("note", null))
                put("updated_at", remoteAt)
                put("dirty", 0)
            }
        } else {
            Rows.toValues(row, tableColumns).apply {
                if (blocked) put("status", Merge.BLOCKED)
                // If the incoming row itself does not carry the block, the app
                // is the side forcing it back on — that decision must travel
                // up on the next sync, or the server keeps the unblocked
                // status forever and a restore onto a new phone loses the
                // block entirely. The rule is inviolable, so this is not
                // optional.
                put("dirty", if (blocked && row.optString("status") != Merge.BLOCKED) 1 else 0)
            }
        }

        if (local == null) {
            db.insertWithOnConflict(table, null, values.apply { put(key, id) }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.update(table, values, "$key = ?", arrayOf(id))
        }
    }

    private fun applyTombstone(db: SQLiteDatabase, stone: JSONObject) {
        val table = stone.getString("table_name")
        val id = stone.getString("row_id")
        val at = stone.getString("deleted_at")
        if (table !in TOMBSTONE_TABLES) return

        // The tombstone came from the server, so it is already known there.
        // `deletions` also doubles as the outgoing queue — a local copy of
        // the same tombstone must not travel back up as if it were ours.
        db.delete("deletions", "table_name = ? AND row_id = ?", arrayOf(table, id))

        val localAt = db.rawQuery("SELECT updated_at FROM $table WHERE ${Rows.key(table)} = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (localAt != null && !Merge.isNewer(localAt, at)) {
            db.delete(table, "${Rows.key(table)} = ?", arrayOf(id))
            // Numbers only follow the contact into deletion when the contact
            // row itself is actually removed — a contact that survived
            // because it is younger than the tombstone keeps its numbers.
            if (table == "contacts") db.delete("contact_numbers", "contact_id = ?", arrayOf(id))
        }
    }

    private fun tombstone(db: SQLiteDatabase, table: String, id: String): String? =
        db.rawQuery(
            "SELECT deleted_at FROM deletions WHERE table_name = ? AND row_id = ?", arrayOf(table, id),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    private companion object {
        /**
         * The only tables the app ever deletes rows from. `businesses` has no
         * `id` column (its key is `place_id`), and the app never deletes a
         * business or a call — a tombstone naming either must never reach SQL.
         */
        val TOMBSTONE_TABLES = setOf("contacts", "contact_numbers")
    }
}
