package io.github.amadeusb.callsheet.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.amadeusb.callsheet.data.AddressRows
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Addresses
import io.github.amadeusb.callsheet.data.CalendarState
import io.github.amadeusb.callsheet.data.Database
import org.json.JSONArray
import org.json.JSONObject

/** What [SyncStore.apply] did to appointments, so the calendar can follow. */
data class AppliedAppointments(
    /** Appointments written from the server. Their local links are still on the rows. */
    val written: List<String> = emptyList(),
    /** Appointments a tombstone removed, with the link each had on this device. */
    val removed: List<RemovedAppointment> = emptyList(),
) {
    fun isEmpty(): Boolean = written.isEmpty() && removed.isEmpty()
}

/**
 * An appointment that is gone from the database, and where its event was. The
 * kind says who deletes that event: the app for a callback, the server for a
 * visit.
 */
data class RemovedAppointment(
    val id: String,
    val calendarEventId: Long?,
    val eventUid: String?,
    val kind: AppointmentKind = AppointmentKind.VISIT,
)

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
     * Marks every row in every synchronised table as unsent, regardless of
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

    /**
     * Rows and tombstones waiting to go up — for [tables] only. The engine
     * passes the tables the server named, so rows an older server ignores do
     * not count towards "keep going"; the settings screen passes nothing and
     * sees everything that is still open.
     */
    fun pendingCount(tables: Set<String> = Rows.TABLES.toSet()): Int {
        val db = helper.readableDatabase
        var total = 0
        for (table in Rows.TABLES) {
            if (table !in tables) continue
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE dirty = 1", null).use { if (it.moveToFirst()) total += it.getInt(0) }
        }
        val stones = TOMBSTONE_TABLES.filter { it in tables }
        if (stones.isNotEmpty()) {
            db.rawQuery(
                "SELECT COUNT(*) FROM deletions WHERE table_name IN (${stones.joinToString(",") { "?" }})",
                stones.toTypedArray(),
            ).use { if (it.moveToFirst()) total += it.getInt(0) }
        }
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
     *
     * Nor is anything cleared for a table the server did not name in `tables`:
     * an older server drops such rows in silence, and they stay marked until a
     * server that knows the table has them.
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
        // Only what the server says it synchronises was received. See Rows.serverTables.
        val received = Rows.serverTables(response)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (table in Rows.TABLES) {
                if (table !in received) continue
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
                if (stone.getString("table_name") !in received) continue
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

    /**
     * Applies what the server sent. Never marks anything as dirty.
     *
     * A business's `search_text` is derived from its name and the cities of all
     * its addresses. Whatever this call writes of a business or writes or
     * removes of an address, the text of that business is brought up to date
     * at the end, in the same transaction.
     */
    fun apply(response: JSONObject): AppliedAppointments {
        val db = helper.writableDatabase
        // Fetched once per call rather than once per row — the schema does
        // not change mid-sync, and a first sync can carry a few thousand rows.
        val columnsByTable = Rows.TABLES.associateWith { columns(db, it) }
        val written = ArrayList<String>()
        val removed = ArrayList<RemovedAppointment>()
        val searchStale = HashSet<String>()
        db.beginTransaction()
        try {
            val deletions = response.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until deletions.length()) {
                applyTombstone(db, deletions.getJSONObject(i), searchStale)?.let { removed.add(it) }
            }
            for (table in Rows.TABLES) {
                val rows = response.optJSONArray(table) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val before = if (table == "appointments") confirmation(db, row.getString("id")) else null
                    val applied = applyRow(db, table, row, columnsByTable.getValue(table))
                    if (table == "appointments") followConfirmation(db, row.getString("id"), before)
                    if (!applied) continue
                    when (table) {
                        "appointments" -> written.add(row.getString("id"))
                        "businesses" -> searchStale.add(row.getString("place_id"))
                        "business_addresses" -> {
                            if (!row.isNull("place_id")) searchStale.add(row.getString("place_id"))
                            // Here again: a re-import updates it rather than leaving it out.
                            val id = row.getString("id")
                            if (id.startsWith(Addresses.MAIN_PREFIX)) {
                                AddressRows.forgetMainRemoved(db, id.removePrefix(Addresses.MAIN_PREFIX))
                            }
                        }
                    }
                }
            }
            for (placeId in searchStale) AddressRows.refreshSearchText(db, placeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return AppliedAppointments(written, removed)
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val names = HashSet<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            names
        }

    private fun applyRow(db: SQLiteDatabase, table: String, row: JSONObject, tableColumns: Set<String>): Boolean {
        val key = Rows.key(table)
        val id = row.getString(key)
        val remoteAt = row.optString("updated_at", null)

        val stone = tombstone(db, table, id)
        if (stone != null && !Merge.isNewer(remoteAt, stone)) return false

        // A number's or email's own row may still be untouched while its
        // contact was deleted: the tombstone lives on the parent, not on the
        // number or email. The server applies the same check in `empfangeKontakt`.
        if (table == "contact_numbers" || table == "contact_emails") {
            val contactId = row.optString("contact_id", null)
            val parentStone = if (contactId != null) tombstone(db, "contacts", contactId) else null
            if (parentStone != null && !Merge.isNewer(remoteAt, parentStone)) return false
        }

        val local = db.rawQuery("SELECT * FROM $table WHERE $key = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) Rows.toJson(c) else null
        }

        val blocked = table == "businesses" &&
            (local?.optString("status") == Merge.BLOCKED || row.optString("status") == Merge.BLOCKED)

        if (local != null && !Merge.isNewer(remoteAt, local.optString("updated_at", null))) {
            // The incoming version is no newer, so it replaces nothing — except
            // what only the server writes. See takeServerOwned.
            val owned = table == "appointments" && takeServerOwned(db, id, row)
            // It may still carry columns this row has never had a value for.
            val filled = Merge.isSameMoment(remoteAt, local.optString("updated_at", null)) &&
                fillGaps(db, table, id, local, row, tableColumns)
            if (blocked && local.optString("status") != Merge.BLOCKED) {
                db.update("businesses", ContentValues().apply {
                    put("status", Merge.BLOCKED)
                    put("dirty", 1)
                }, "place_id = ?", arrayOf(id))
            }
            return filled || owned
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
        return true
    }

    /**
     * Fills the columns this row has no value for from an incoming row at a
     * standstill — the server's `fillGaps` in receive.js, for the same reason:
     * equally old versions may not overwrite each other's answers, but a NULL
     * where the other side has a value is not a conflict, it is a gap.
     *
     * The app grows the gaps itself. A 1.4.0 phone stored the callbacks it
     * pulled without `kind` and `done_at`, its schema had neither; after the
     * update they read as visits, and the refetch (Preferences.refetchedForCallbacks)
     * brings each one down again with the same `updated_at`. Only this fills
     * them in.
     *
     * Every synchronised table, as on the server. The only columns where this
     * could bring back something emptied are the business's old ones —
     * `follow_up_at` and the `appointment_` columns — where an old app's upload
     * after the server's migration left a value there: nothing reads them, and
     * the row stays unmarked.
     *
     * One exception to column by column: `attendees_notify` only together with
     * `attendees`, see the comment in the body.
     *
     * Never marks the row: the values came from the server. Local-only columns
     * are never touched — [Rows.toValues] leaves them out, and [local] never
     * carries them. No gap, no write; returns whether there was one.
     */
    private fun fillGaps(
        db: SQLiteDatabase,
        table: String,
        id: String,
        local: JSONObject,
        row: JSONObject,
        tableColumns: Set<String>,
    ): Boolean {
        val gaps = Rows.toValues(row, tableColumns)
        val incoming = Rows.toValues(row, tableColumns)
        for (name in gaps.keySet().toList()) {
            if (!local.isNull(name) || gaps.get(name) == null) gaps.remove(name)
        }
        // A visit's attendees and whether a change to them notifies are one
        // decision of one save: filled together — the decision then over what is
        // stored — or the decision not at all. The server's pairAttendees.
        if (table == "appointments") {
            if (gaps.containsKey("attendees") && incoming.containsKey("attendees_notify")) {
                gaps.put("attendees_notify", incoming.getAsInteger("attendees_notify"))
            } else {
                gaps.remove("attendees_notify")
            }
        }
        if (gaps.size() == 0) return false
        db.update(table, gaps, "${Rows.key(table)} = ?", arrayOf(id))
        return true
    }

    /**
     * Writes what only the server writes onto an appointment this device keeps:
     * the calendar state, and a visit's `event_uid`. The server stamps them
     * without moving `updated_at`, so a row edited here is newer than the
     * server's copy or level with it. Without this the state would stay
     * „pending" for good, and the UID would never arrive.
     *
     * Only columns the incoming row carries: a server without the feature sends
     * none, and nothing is cleared. A visit's UID only when one comes — the
     * server never takes one back. A callback's UID stays the app's. Never
     * marks the row. Returns whether anything was written.
     */
    private fun takeServerOwned(db: SQLiteDatabase, id: String, row: JSONObject): Boolean {
        val values = ContentValues()
        val owned = Rows.SERVER_OWNED
        db.rawQuery(
            "SELECT ${owned.joinToString()}, event_uid, kind FROM appointments WHERE id = ?", arrayOf(id),
        ).use { c ->
            if (!c.moveToFirst()) return false
            owned.forEachIndexed { i, name ->
                if (!row.has(name)) return@forEachIndexed
                val incoming = if (row.isNull(name)) null else row.getString(name)
                val stored = if (c.isNull(i)) null else c.getString(i)
                if (incoming != stored) values.put(name, incoming)
            }
            val storedUid = if (c.isNull(owned.size)) null else c.getString(owned.size)
            val kind = AppointmentKind.fromKey(if (c.isNull(owned.size + 1)) null else c.getString(owned.size + 1))
            val uid = if (row.isNull("event_uid")) null else row.getString("event_uid")
            if (kind == AppointmentKind.VISIT && uid != null && uid != storedUid) values.put("event_uid", uid)
        }
        if (values.size() == 0) return false
        db.update("appointments", values, "id = ?", arrayOf(id))
        return true
    }

    /** A visit's calendar state and UID, with its two local moments. Null without the row. */
    private data class Confirmation(val state: String?, val uid: String?, val missingSince: Long?, val okSince: Long?)

    private fun confirmation(db: SQLiteDatabase, id: String): Confirmation? =
        db.rawQuery(
            "SELECT calendar_state, event_uid, calendar_missing_since, calendar_ok_since FROM appointments WHERE id = ?",
            arrayOf(id),
        ).use { c ->
            if (!c.moveToFirst()) return null
            Confirmation(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getString(1),
                if (c.isNull(2)) null else c.getLong(2),
                if (c.isNull(3)) null else c.getLong(3),
            )
        }

    /**
     * Keeps `calendar_ok_since` — when this device first saw the server
     * confirm the visit — in step with what the sync wrote (see
     * Appointment.reconcileTracked). Stamped with now when the state turns
     * `ok` or the UID changes under it: removed and back, the server made a
     * new event. Emptied when the state leaves `ok`. A row already `ok` keeps
     * its moment, an unknown one included. `calendar_missing_since` goes with
     * a new UID and with the state leaving `ok`: an old count would make the
     * next miss a missing visit at once.
     *
     * Local only: no `updated_at`, no mark.
     */
    private fun followConfirmation(db: SQLiteDatabase, id: String, before: Confirmation?) {
        val after = confirmation(db, id) ?: return
        val newUid = before != null && before.uid != after.uid
        val confirmed = after.state == CalendarState.OK.key
        val values = ContentValues()
        if ((newUid || !confirmed) && after.missingSince != null) values.putNull("calendar_missing_since")
        when {
            !confirmed -> if (after.okSince != null) values.putNull("calendar_ok_since")
            before == null || before.state != after.state || newUid ->
                values.put("calendar_ok_since", System.currentTimeMillis())
        }
        if (values.size() == 0) return
        db.update("appointments", values, "id = ?", arrayOf(id))
    }

    private fun applyTombstone(db: SQLiteDatabase, stone: JSONObject, searchStale: MutableSet<String>): RemovedAppointment? {
        val table = stone.getString("table_name")
        val id = stone.getString("row_id")
        val at = stone.getString("deleted_at")
        if (table !in TOMBSTONE_TABLES) return null

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

        val key = Rows.key(table)
        val localAt = db.rawQuery("SELECT updated_at FROM $table WHERE $key = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        val stoneWins = localAt == null || !Merge.isNewer(localAt, at)

        // A main address removed on another device stays removed for this
        // device's import too — see Database's removed_main_addresses. Only when
        // the tombstone wins: a row here that is newer was added back since.
        if (table == "business_addresses" && id.startsWith(Addresses.MAIN_PREFIX) && stoneWins) {
            AddressRows.rememberMainRemoved(db, id.removePrefix(Addresses.MAIN_PREFIX))
        }
        if (localAt == null || !stoneWins) return null

        // The link has to be read before the row goes, or the calendar could
        // not follow the deletion.
        val link = if (table == "appointments") {
            db.rawQuery("SELECT calendar_event_id, event_uid, kind FROM appointments WHERE id = ?", arrayOf(id)).use { c ->
                c.moveToFirst()
                RemovedAppointment(
                    id,
                    if (c.isNull(0)) null else c.getLong(0),
                    if (c.isNull(1)) null else c.getString(1),
                    AppointmentKind.fromKey(if (c.isNull(2)) null else c.getString(2)),
                )
            }
        } else {
            null
        }
        if (table == "business_addresses") {
            db.rawQuery("SELECT place_id FROM business_addresses WHERE id = ?", arrayOf(id))
                .use { if (it.moveToFirst()) searchStale.add(it.getString(0)) }
        }
        db.delete(table, "$key = ?", arrayOf(id))
        // Numbers and emails only follow the contact into deletion when the
        // contact row itself is actually removed — a contact that survived
        // because it is younger than the tombstone keeps them.
        if (table == "contacts") {
            db.delete("contact_numbers", "contact_id = ?", arrayOf(id))
            db.delete("contact_emails", "contact_id = ?", arrayOf(id))
        }
        return link
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
        val TOMBSTONE_TABLES = setOf("contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses")
    }
}
