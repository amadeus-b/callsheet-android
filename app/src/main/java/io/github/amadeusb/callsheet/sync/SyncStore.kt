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
        payload.put("deleted", deletions)
        return payload
    }

    /**
     * Marks every row in the four synchronised tables as unsent, regardless of
     * whether it changed. Needed when a server's own history no longer lines
     * up with what this device already sent it — after restoring an older
     * server backup (its watermark falls below the device's, and the marks
     * for that period were already cleared the first time they were
     * acknowledged) or when pointing the app at a server that has never seen
     * any of this device's data at all.
     */
    fun markAllDirty() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (table in Rows.TABLES) {
                db.execSQL("UPDATE $table SET dirty = 1")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
     * A row changed while the request was in flight must stay marked. Nor is a
     * row the server named in `response`'s `rejected` cleared: it never made
     * it into the server's stock, so clearing its mark would make the row
     * vanish from both sides at once — gone from the outgoing queue, absent
     * from the server, and no longer counted as open. It stays dirty instead,
     * which means it is offered again on every following sync. For a row the
     * server keeps rejecting that is a permanent, honest "still open" rather
     * than a silent drop or a retry no one can see — there is no per-row error
     * display to fall back on, so staying counted is the only visible signal
     * this app has.
     */
    fun clearPending(payload: JSONObject, response: JSONObject) {
        val rejected = HashSet<Pair<String, String>>()
        val rejectedRows = response.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until rejectedRows.length()) {
            val entry = rejectedRows.getJSONObject(i)
            val table = entry.optString("table", null) ?: continue
            val key = entry.optString("key", null) ?: continue
            rejected.add(table to key)
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (table in Rows.TABLES) {
                val rows = payload.optJSONArray(table) ?: continue
                val key = Rows.key(table)
                for (i in 0 until rows.length()) {
                    val sent = rows.getJSONObject(i)
                    val id = sent.getString(key)
                    if (table to id in rejected) continue
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
            val deletions = payload.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until deletions.length()) {
                val stone = deletions.getJSONObject(i)
                // The server names a rejected tombstone the same way it names
                // a rejected row: the table and key of the affected row (here
                // `table_name`/`row_id` rather than `table`/`key`,
                // but the same pair). Same reasoning as above: skip it, or the
                // deletion disappears from the outgoing queue without ever
                // having reached the server.
                if (stone.getString("table_name") to stone.getString("row_id") in rejected) continue
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
            val deletions = response.optJSONArray("deleted") ?: JSONArray()
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
            // `org.json.JSONObject.optString` is not what it looks like for a
            // JSON null: it returns the literal text "null" for that case and
            // only falls back to the default when the key is missing
            // entirely. `isNull` is the only reliable way to tell "cleared"
            // apart from "unset".
            ContentValues().apply {
                put("outcome", if (row.isNull("outcome")) null else row.getString("outcome"))
                put("note", if (row.isNull("note")) null else row.getString("note"))
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
        // Only clear it if it is exactly the one just acknowledged: one
        // (re-)written locally while the request was in flight — a different
        // `deleted_at` — must survive so it still goes out. Same guard as
        // `clearPending`.
        db.delete(
            "deletions", "table_name = ? AND row_id = ? AND deleted_at = ?",
            arrayOf(table, id, at),
        )

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
