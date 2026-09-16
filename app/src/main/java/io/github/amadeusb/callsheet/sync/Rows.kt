package io.github.amadeusb.callsheet.sync

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONObject

/**
 * Turns rows into JSON and back. One place, so the app and the server cannot
 * drift apart over a column name.
 */
object Rows {

    val TABLES = listOf(
        "businesses", "calls", "contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses",
    )

    /**
     * The tables every server synchronised before responses named them. A
     * response without `tables` comes from such a server.
     */
    val LEGACY_TABLES = setOf("businesses", "calls", "contacts", "contact_numbers")

    /**
     * The tables the server behind [response] synchronises.
     *
     * An older server ignores a table it does not know without a word — nothing
     * lands in `rejected`. Clearing the marks for such a table would make its
     * rows look delivered while they never arrived; so only the tables named
     * here count as received.
     */
    fun serverTables(response: JSONObject): Set<String> {
        val named = response.optJSONArray("tables") ?: return LEGACY_TABLES
        return (0 until named.length()).mapNotNull { named.optString(it, null) }.toSet()
    }

    /** The key column of each synchronised table. */
    fun key(table: String): String = if (table == "businesses") "place_id" else "id"

    /**
     * Columns that never leave the device.
     *
     * `calendar_event_id` points into this device's calendar provider. The same
     * number on another device is a different event, or none. The
     * `calendar_seen_` columns record what this device last saw in its copy of
     * the event — another device's copy may be ahead or behind, and
     * `calendar_missing_since` and `calendar_ok_since` when it looked for a
     * visit's event in vain and saw the visit confirmed. `event_uid` is
     * deliberately absent: the UID is the same event on every device carrying
     * the shared calendar, and that is what the other devices look it up by.
     */
    private val LOCAL_ONLY = setOf(
        "dirty", "contact_version", "calendar_event_id",
        "calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location", "calendar_seen_title",
        "calendar_missing_since", "calendar_ok_since",
    )

    /**
     * Columns only the server writes: where a visit stands on its way into the
     * calendar. They come down like any other column but never go up, and
     * SyncStore.apply takes them whatever `updated_at` says — the server writes
     * them without moving `updated_at`. A visit's `event_uid` is the server's
     * too, but it shares its column with a callback's, which is the app's;
     * SyncStore tells the two apart by kind.
     */
    val SERVER_OWNED = listOf("calendar_state", "calendar_error")

    fun toJson(c: Cursor): JSONObject {
        val row = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            if (name in LOCAL_ONLY || name in SERVER_OWNED) continue
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(i))
                else -> row.put(name, c.getString(i))
            }
        }
        return row
    }

    fun toValues(row: JSONObject, columns: Set<String>): ContentValues {
        val values = ContentValues()
        for (name in row.keys()) {
            if (name !in columns || name in LOCAL_ONLY) continue
            when (val value = row.get(name)) {
                JSONObject.NULL -> values.putNull(name)
                is Int -> values.put(name, value)
                is Long -> values.put(name, value)
                is Double -> values.put(name, value)
                is Boolean -> values.put(name, if (value) 1 else 0)
                else -> values.put(name, value.toString())
            }
        }
        return values
    }
}
