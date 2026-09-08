package io.github.amadeusb.callsheet.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the business file (research export plus imprint crawler) into imported
 * master data.
 *
 * A pure function without an Android context, so it can be tested. Note that
 * `org.json` hands back the sentinel [JSONObject.NULL] rather than `null` for
 * JSON nulls — every access therefore goes through the helpers further down.
 *
 * The JSON field names are German because they come from the source file; they
 * are data, not identifiers, and must not be translated.
 */
object Importer {

    /**
     * Reads the JSON text of the business file. Entries without a `placeId` are
     * skipped, as are elements that are not objects.
     */
    fun read(text: String): List<ImportedBusiness> {
        val root = JSONArray(text)
        val result = ArrayList<ImportedBusiness>(root.length())
        for (i in 0 until root.length()) {
            val o = root.optJSONObject(i) ?: continue
            val imported = one(o) ?: continue
            result.add(imported)
        }
        return result
    }

    /** Converts a single entry; null when it carries no `placeId`. */
    private fun one(o: JSONObject): ImportedBusiness? {
        val placeId = o.text("placeId") ?: return null

        val industry = o.text("gewerk")
        val phone = PhoneNumbers.normalize(o.text("phone"), o.text("phoneUnformatted"))
        val closed = o.bool("permanentlyClosed") || o.bool("temporarilyClosed")

        val isTarget = TargetRule.isTarget(industry, phone, closed)

        return ImportedBusiness(
            placeId = placeId,
            name = o.text("title") ?: placeId,
            industry = industry,
            categories = o.texts("alle_kategorien").ifEmpty { o.texts("categories") },
            street = o.text("street"),
            postalCode = o.text("postalCode"),
            city = o.text("city"),
            phone = phone,
            website = o.text("website"),
            email = o.texts("emails").firstOrNull(),
            contactName = o.text("kontakt"),
            rating = o.decimal("totalScore"),
            ratingCount = o.int("reviewsCount"),
            closed = closed,
            isTarget = isTarget,
            origin = o.texts("herkunft"),
            collectedAt = o.text("erhobenAm") ?: Clock.now(),
        )
    }

    // ---- org.json helpers: treat JSONObject.NULL properly as a Kotlin null ----

    /** Text or null; empty strings count as absent. */
    private fun JSONObject.text(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "").trim()
        return value.ifEmpty { null }
    }

    /** A list of texts; missing or null fields yield an empty list. */
    private fun JSONObject.texts(key: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        val field = optJSONArray(key) ?: return emptyList()
        val list = ArrayList<String>(field.length())
        for (i in 0 until field.length()) {
            if (field.isNull(i)) continue
            val value = field.optString(i, "").trim()
            if (value.isNotEmpty()) list.add(value)
        }
        return list
    }

    /** Boolean value; missing or null counts as false. */
    private fun JSONObject.bool(key: String): Boolean {
        if (!has(key) || isNull(key)) return false
        return optBoolean(key, false)
    }

    private fun JSONObject.decimal(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private fun JSONObject.int(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value.toInt()
    }
}
