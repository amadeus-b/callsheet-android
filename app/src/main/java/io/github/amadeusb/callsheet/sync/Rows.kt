package io.github.amadeusb.callsheet.sync

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONObject

/**
 * Turns rows into JSON and back. One place, so the app and the server cannot
 * drift apart over a column name.
 */
object Rows {

    val TABLES = listOf("businesses", "calls", "contacts", "contact_numbers")

    /** The key column of each synchronised table. */
    fun key(table: String): String = if (table == "businesses") "place_id" else "id"

    /** Columns that never leave the device. */
    private val LOCAL_ONLY = setOf("dirty", "contact_version")

    fun toJson(c: Cursor): JSONObject {
        val row = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            if (name in LOCAL_ONLY) continue
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
