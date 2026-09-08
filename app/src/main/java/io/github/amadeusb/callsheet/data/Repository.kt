package io.github.amadeusb.callsheet.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream

/**
 * The single point of access to the app's data.
 *
 * Two rules deliberately live in SQL rather than in the user interface:
 * 1. Businesses with status `do_not_call` show up in no list at all — only
 *    [blockedBusinesses] returns them.
 * 2. Re-importing only refreshes imported master data. Status, note, follow-up
 *    and call history are never touched.
 */
class Repository(context: Context) {

    private val helper = Database(context.applicationContext)

    private val _changes = MutableStateFlow(0L)

    /** Counter that goes up after every write. The UI reloads when it does. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    // ------------------------------------------------------------------ Import

    /**
     * Reads a business file. All in one transaction, so an abort leaves nothing
     * half-written behind.
     */
    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val imported = try {
            val text = input.bufferedReader().use { it.readText() }
            Importer.read(text)
        } catch (f: Exception) {
            return@withContext ImportResult(0, 0, 0, 0, f.message ?: "Datei nicht lesbar")
        }

        val db = helper.writableDatabase
        var new = 0
        var updated = 0
        val withoutPhone = imported.count { it.phone == null }

        db.beginTransaction()
        try {
            val known = HashSet<String>()
            db.rawQuery("SELECT place_id FROM businesses", null).use { c ->
                while (c.moveToNext()) known.add(c.getString(0))
            }

            val now = Clock.now()
            for (s in imported) {
                val values = importedValues(s)
                if (known.contains(s.placeId)) {
                    // Imported master data only. status, note, follow_up_at and
                    // updated_at are deliberately absent from [importedValues].
                    db.update("businesses", values, "place_id = ?", arrayOf(s.placeId))
                    updated++
                } else {
                    values.put("place_id", s.placeId)
                    values.put("status", Status.NEW.key)
                    values.put("updated_at", now)
                    db.insert("businesses", null, values)
                    new++
                }
            }
            db.setTransactionSuccessful()
        } catch (f: Exception) {
            return@withContext ImportResult(0, 0, 0, imported.size, f.message ?: "Import fehlgeschlagen")
        } finally {
            db.endTransaction()
        }

        notifyChanged()
        ImportResult(
            new = new,
            updated = updated,
            withoutPhone = withoutPhone,
            total = imported.size,
        )
    }

    /** Name and city in one lower-cased column — for searching with umlauts. */
    private fun searchText(name: String, city: String?): String =
        (name + " " + (city ?: "")).lowercase()

    private fun importedValues(s: ImportedBusiness): ContentValues = ContentValues().apply {
        put("name", s.name)
        put("search_text", searchText(s.name, s.city))
        put("industry", s.industry)
        put("categories", toJson(s.categories))
        put("street", s.street)
        put("postal_code", s.postalCode)
        put("city", s.city)
        put("phone", s.phone)
        put("website", s.website)
        put("email", s.email)
        put("contact_name", s.contactName)
        put("rating", s.rating)
        put("rating_count", s.ratingCount)
        put("closed", if (s.closed) 1 else 0)
        put("is_target", if (s.isTarget) 1 else 0)
        put("origin", toJson(s.origin))
        put("collected_at", s.collectedAt)
    }

    // ----------------------------------------------------------------- Reading

    /** The work list for a filter, ordered by industry, city, name. Blocked businesses excluded. */
    suspend fun list(filter: Filter): List<Business> = withContext(Dispatchers.IO) {
        val (where, args) = condition(filter)
        val sql = "SELECT b.*, $NUMBERS_SUBQUERY FROM businesses b WHERE $where " +
            "ORDER BY b.industry IS NULL, b.industry COLLATE NOCASE, b.city COLLATE NOCASE, b.name COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, args).use { c -> allBusinesses(c) }
    }

    /** How many businesses the filter covers. Blocked businesses excluded. */
    suspend fun count(filter: Filter): Int = withContext(Dispatchers.IO) {
        val (where, args) = condition(filter)
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM businesses b WHERE $where", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * How many businesses in the filter have been called since the day began.
     * Picking the businesses happens in SQL, comparing the time in Kotlin,
     * because `started_at` is an ISO timestamp carrying a zone offset.
     */
    suspend fun calledToday(filter: Filter): Int = withContext(Dispatchers.IO) {
        val (where, args) = condition(filter)
        val limit = Clock.todayStart()
        val sql = "SELECT a.place_id, a.started_at FROM calls a " +
            "JOIN businesses b ON b.place_id = a.place_id WHERE $where"
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            val matches = HashSet<String>()
            while (c.moveToNext()) {
                val millis = Clock.millis(c.getString(1)) ?: continue
                if (millis >= limit) matches.add(c.getString(0))
            }
            matches.size
        }
    }

    /** A single business, blocked or not — the detail view has to be able to show it. */
    suspend fun business(placeId: String): Business? = withContext(Dispatchers.IO) {
        helper.readableDatabase
            .rawQuery("SELECT * FROM businesses WHERE place_id = ?", arrayOf(placeId))
            .use { c -> if (c.moveToFirst()) fromCursor(c) else null }
    }

    /** Every industry that occurs, for the filter. Blocked businesses excluded. */
    suspend fun industries(): List<String> = withContext(Dispatchers.IO) {
        val sql = "SELECT DISTINCT industry FROM businesses " +
            "WHERE status <> ? AND industry IS NOT NULL AND industry <> '' " +
            "ORDER BY industry COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
            val list = ArrayList<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }
    }

    /** Every city that occurs, for the filter. Blocked businesses excluded. */
    suspend fun cities(): List<String> = withContext(Dispatchers.IO) {
        val sql = "SELECT DISTINCT city FROM businesses " +
            "WHERE status <> ? AND city IS NOT NULL AND city <> '' " +
            "ORDER BY city COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
            val list = ArrayList<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }
    }

    /** Every follow-up due by [toMillis] — overdue ones included. */
    suspend fun due(toMillis: Long = System.currentTimeMillis()): List<Business> =
        withContext(Dispatchers.IO) {
            val sql = "SELECT b.*, $NUMBERS_SUBQUERY FROM businesses b WHERE status <> ? " +
                "AND follow_up_at IS NOT NULL AND follow_up_at <> ''"
            helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
                allBusinesses(c)
                    .mapNotNull { b -> Clock.millis(b.followUpAt)?.let { it to b } }
                    .filter { it.first <= toMillis }
                    .sortedBy { it.first }
                    .map { it.second }
            }
        }

    /** The blocked businesses — only so a mistaken block can be taken back. */
    suspend fun blockedBusinesses(): List<Business> = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM businesses WHERE status = ? ORDER BY name COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
            allBusinesses(c)
        }
    }

    /** A business's recorded contact attempts and notes, most recent first. */
    suspend fun calls(placeId: String): List<CallEntry> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, place_id, started_at, duration_seconds, outcome, note, kind, contact " +
                "FROM calls " +
                "WHERE place_id = ? ORDER BY started_at DESC",
            arrayOf(placeId),
        ).use { c ->
            val list = ArrayList<CallEntry>()
            while (c.moveToNext()) {
                list.add(
                    CallEntry(
                        id = c.getString(0),
                        placeId = c.getString(1),
                        startedAt = c.getString(2),
                        durationSeconds = c.getInt(3),
                        outcome = if (c.isNull(4)) null else c.getString(4),
                        note = if (c.isNull(5)) null else c.getString(5),
                        kind = EntryKind.fromKey(c.getString(6)),
                        contact = if (c.isNull(7)) null else c.getString(7),
                    )
                )
            }
            list
        }
    }

    // ----------------------------------------------------------------- Writing

    suspend fun setStatus(placeId: String, status: Status) = withContext(Dispatchers.IO) {
        updateBusiness(placeId) { put("status", status.key) }
    }

    suspend fun setNote(placeId: String, note: String) = withContext(Dispatchers.IO) {
        updateBusiness(placeId) { put("note", note) }
    }

    /** Sets the follow-up, or clears it with `null`. */
    suspend fun setFollowUp(placeId: String, iso: String?) = withContext(Dispatchers.IO) {
        updateBusiness(placeId) {
            if (iso == null) putNull("follow_up_at") else put("follow_up_at", iso)
        }
    }

    /**
     * Creates a business entered by hand.
     *
     * Its key carries the [MANUAL_PREFIX] so a later import can never hit it and
     * overwrite the entry as a duplicate. Re-importing leaves hand-entered
     * businesses alone — it only touches what is in the file.
     *
     * Returns the new `place_id`, or a message the UI can show.
     */
    suspend fun create(new: BusinessDraft): Result<String> = withContext(Dispatchers.IO) {
        val name = new.name.trim()
        if (name.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Ohne Namen lässt sich kein Betrieb anlegen."))
        }

        val phone = PhoneNumbers.normalize(new.phone.trim(), new.phone.trim())
        if (new.phone.isNotBlank() && phone == null) {
            return@withContext Result.failure(
                IllegalArgumentException("Die Telefonnummer ist unvollständig. Lass sie leer oder trag sie vollständig ein.")
            )
        }

        // Calling the same number twice is the mistake this check exists to
        // prevent — even when the business goes by a different name.
        if (phone != null) {
            val existing = helper.readableDatabase.rawQuery(
                "SELECT name FROM businesses WHERE phone = ? LIMIT 1", arrayOf(phone)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (existing != null) {
                return@withContext Result.failure(
                    IllegalStateException("Diese Nummer steht schon bei „$existing“. Such den Betrieb in der Liste, statt ihn doppelt anzulegen.")
                )
            }
        }

        val industry = new.industry.trim().ifEmpty { null }
        val city = new.city.trim().ifEmpty { null }
        val now = Clock.now()
        val placeId = MANUAL_PREFIX + java.util.UUID.randomUUID()

        // Where a contact came from has to stay on record: for imported
        // businesses the research run provides that, here only this note.
        val origin = buildList {
            add(ORIGIN_MANUAL)
            new.origin.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        }

        val values = ContentValues().apply {
            put("place_id", placeId)
            put("name", name)
            put("search_text", searchText(name, city))
            put("industry", industry)
            put("categories", JSONArray(emptyList<String>()).toString())
            put("street", new.street.trim().ifEmpty { null })
            put("postal_code", new.postalCode.trim().ifEmpty { null })
            put("city", city)
            put("phone", phone)
            put("website", new.website.trim().ifEmpty { null })
            put("email", new.email.trim().ifEmpty { null })
            put("contact_name", new.contactName.trim().ifEmpty { null })
            put("closed", 0)
            put("is_target", if (TargetRule.isTarget(industry, phone, false)) 1 else 0)
            put("origin", JSONArray(origin).toString())
            put("collected_at", now)
            put("status", Status.NEW.key)
            put("note", new.note.trim().ifEmpty { null })
            put("updated_at", now)
        }

        helper.writableDatabase.insert("businesses", null, values)
        notifyChanged()
        Result.success(placeId)
    }

    /** Appends an entry to the log. */
    suspend fun logCall(entry: CallEntry) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("id", entry.id)
            put("place_id", entry.placeId)
            put("started_at", entry.startedAt)
            put("duration_seconds", entry.durationSeconds)
            put("outcome", entry.outcome)
            put("note", entry.note)
            put("kind", entry.kind.key)
            put("contact", entry.contact)
        }
        db.beginTransaction()
        try {
            db.insertWithOnConflict("calls", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            val timestamp = ContentValues().apply { put("updated_at", Clock.now()) }
            db.update("businesses", timestamp, "place_id = ?", arrayOf(entry.placeId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    /**
     * Fills in outcome and note on an existing log entry.
     *
     * Time and duration stay untouched. An empty note overwrites nothing — what
     * was recorded when the entry was created (say „Dauer nicht ermittelbar“)
     * stays put.
     */
    suspend fun completeCall(id: String, outcome: String?, note: String?) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("outcome", outcome)
                if (!note.isNullOrBlank()) put("note", note)
            }
            helper.writableDatabase.update("calls", values, "id = ?", arrayOf(id))
            notifyChanged()
        }

    /**
     * Every business that has been worked on: one with a contact, or one with a
     * recorded call. Exactly those belong in the phone book.
     */
    suspend fun businessesWithContactsOrCalls(): List<Business> = withContext(Dispatchers.IO) {
        val sql = "SELECT b.* FROM businesses b WHERE " +
            "EXISTS (SELECT 1 FROM contacts a WHERE a.place_id = b.place_id) OR " +
            "EXISTS (SELECT 1 FROM calls r WHERE r.place_id = b.place_id) " +
            "ORDER BY b.name COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, null).use { c -> allBusinesses(c) }
    }

    // ---------------------------------------------------------------- Contacts

    /** A business's contacts with their numbers, in the order they were entered. */
    suspend fun contacts(placeId: String): List<Contact> =
        withContext(Dispatchers.IO) {
            val db = helper.readableDatabase
            val numbers = HashMap<String, MutableList<PhoneNumber>>()
            db.rawQuery(
                "SELECT n.id, n.contact_id, n.number, n.kind FROM contact_numbers n " +
                    "JOIN contacts a ON a.id = n.contact_id " +
                    "WHERE a.place_id = ? ORDER BY n.position",
                arrayOf(placeId),
            ).use { c ->
                while (c.moveToNext()) {
                    numbers.getOrPut(c.getString(1)) { ArrayList() }.add(
                        PhoneNumber(
                            id = c.getString(0),
                            number = c.getString(2),
                            kind = PhoneType.fromKey(c.getString(3)),
                        )
                    )
                }
            }
            db.rawQuery(
                "SELECT id, place_id, name, role, email, note, updated_at, contact_version " +
                    "FROM contacts " +
                    "WHERE place_id = ? ORDER BY position, name COLLATE NOCASE",
                arrayOf(placeId),
            ).use { c ->
                val list = ArrayList<Contact>()
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    list.add(
                        Contact(
                            id = id,
                            placeId = c.getString(1),
                            name = c.getString(2),
                            role = if (c.isNull(3)) null else c.getString(3),
                            email = if (c.isNull(4)) null else c.getString(4),
                            note = if (c.isNull(5)) null else c.getString(5),
                            numbers = numbers[id].orEmpty(),
                            updatedAt = c.getString(6),
                            contactVersion = if (c.isNull(7)) null else c.getInt(7),
                        )
                    )
                }
                list
            }
        }

    /**
     * Creates or updates a contact together with their numbers.
     *
     * Empty number rows are dropped, the rest are normalised to E.164 — an
     * incomplete number is an error rather than a silent omission, otherwise
     * someone dials into the void later on.
     *
     * Returns the contact's id.
     */
    suspend fun saveContact(draft: ContactDraft): Result<String> =
        withContext(Dispatchers.IO) {
            val name = draft.name.trim()
            if (name.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Ohne Namen lässt sich kein Ansprechpartner speichern.")
                )
            }

            val rows = draft.numbers.filter { it.number.isNotBlank() }
            val checked = ArrayList<Pair<PhoneDraft, String>>(rows.size)
            for (row in rows) {
                val normalised = PhoneNumbers.normalize(row.number.trim(), row.number.trim())
                    ?: return@withContext Result.failure(
                        IllegalArgumentException(
                            "Die Nummer „${row.number.trim()}“ ist unvollständig. " +
                                "Trag sie vollständig ein oder lösche die Zeile."
                        )
                    )
                checked.add(row to normalised)
            }

            val email = draft.email.trim()
            if (email.isNotEmpty() && !email.contains("@")) {
                return@withContext Result.failure(
                    IllegalArgumentException("Die E-Mail-Adresse sieht nicht wie eine Adresse aus.")
                )
            }

            val id = draft.id ?: java.util.UUID.randomUUID().toString()
            val now = Clock.now()
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("id", id)
                    put("place_id", draft.placeId)
                    put("name", name)
                    put("role", draft.role.trim().ifEmpty { null })
                    put("email", email.ifEmpty { null })
                    put("note", draft.note.trim().ifEmpty { null })
                    put("updated_at", now)
                }
                if (draft.id == null) {
                    val next = db.rawQuery(
                        "SELECT COALESCE(MAX(position), -1) + 1 FROM contacts WHERE place_id = ?",
                        arrayOf(draft.placeId),
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    values.put("position", next)
                    db.insert("contacts", null, values)
                } else {
                    db.update("contacts", values, "id = ?", arrayOf(id))
                }

                // The numbers are written afresh: order and types come from the
                // form, not from whatever was stored before.
                db.delete("contact_numbers", "contact_id = ?", arrayOf(id))
                checked.forEachIndexed { index, (row, number) ->
                    db.insert(
                        "contact_numbers", null,
                        ContentValues().apply {
                            put("id", row.id ?: java.util.UUID.randomUUID().toString())
                            put("contact_id", id)
                            put("number", number)
                            put("kind", row.kind.key)
                            put("position", index)
                        },
                    )
                }

                val timestamp = ContentValues().apply { put("updated_at", now) }
                db.update("businesses", timestamp, "place_id = ?", arrayOf(draft.placeId))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            Result.success(id)
        }

    /**
     * Records which version of the phone book entry was last merged.
     * Deliberately without `updated_at`: a merge is not a content change.
     */
    suspend fun setContactVersion(id: String, version: Int?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (version == null) putNull("contact_version") else put("contact_version", version)
        }
        helper.writableDatabase.update("contacts", values, "id = ?", arrayOf(id))
        Unit
    }

    /** Deletes a contact together with their numbers. */
    suspend fun deleteContact(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("contact_numbers", "contact_id = ?", arrayOf(id))
            db.delete("contacts", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    /** Changes a working field and always writes `updated_at` along with it. */
    private inline fun updateBusiness(placeId: String, block: ContentValues.() -> Unit) {
        val values = ContentValues().apply(block)
        values.put("updated_at", Clock.now())
        helper.writableDatabase.update("businesses", values, "place_id = ?", arrayOf(placeId))
        notifyChanged()
    }

    // --------------------------------------------------------------------- SQL

    /**
     * Builds the WHERE clause for a filter. Excluding blocked businesses is
     * wired in here and cannot be switched off from outside.
     */
    private fun condition(filter: Filter): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        val b = TABLE_ALIAS

        parts.add("${b}status <> ?")
        args.add(Status.DO_NOT_CALL.key)

        val allowed = filter.status.filter { it != Status.DO_NOT_CALL }
        if (filter.status.isNotEmpty()) {
            if (allowed.isEmpty()) {
                parts.add("0")
            } else {
                parts.add("${b}status IN (${placeholder(allowed.size)})")
                allowed.forEach { args.add(it.key) }
            }
        }

        if (filter.industries.isNotEmpty() || filter.unassigned) {
            val or = ArrayList<String>()
            if (filter.industries.isNotEmpty()) {
                or.add("${b}industry IN (${placeholder(filter.industries.size)})")
                args.addAll(filter.industries)
            }
            if (filter.unassigned) or.add("${b}industry IS NULL")
            parts.add("(" + or.joinToString(" OR ") + ")")
        }

        if (filter.cities.isNotEmpty()) {
            parts.add("${b}city IN (${placeholder(filter.cities.size)})")
            args.addAll(filter.cities)
        }

        if (filter.onlyTargets) parts.add("${b}is_target = 1")

        val search = filter.search.trim()
        if (search.isNotEmpty()) {
            parts.add("${b}search_text LIKE ? ESCAPE '\\'")
            val pattern = "%" + search.lowercase().replace("%", "\\%").replace("_", "\\_") + "%"
            args.add(pattern)
        }

        return parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun placeholder(n: Int) = List(n) { "?" }.joinToString(",")

    // ------------------------------------------------------------------ Cursor

    private fun allBusinesses(c: Cursor): List<Business> {
        val list = ArrayList<Business>(c.count)
        while (c.moveToNext()) list.add(fromCursor(c))
        return list
    }

    private fun fromCursor(c: Cursor): Business = Business(
        placeId = c.text("place_id") ?: "",
        name = c.text("name") ?: "",
        industry = c.text("industry"),
        categories = fromJson(c.text("categories")),
        street = c.text("street"),
        postalCode = c.text("postal_code"),
        city = c.text("city"),
        phone = c.text("phone"),
        website = c.text("website"),
        email = c.text("email"),
        contactName = c.text("contact_name"),
        rating = c.decimal("rating"),
        ratingCount = c.int("rating_count"),
        closed = (c.int("closed") ?: 0) == 1,
        isTarget = (c.int("is_target") ?: 0) == 1,
        origin = fromJson(c.text("origin")),
        collectedAt = c.text("collected_at"),
        status = Status.fromKey(c.text("status")),
        note = c.text("note"),
        followUpAt = c.text("follow_up_at"),
        updatedAt = c.text("updated_at") ?: "",
        additionalNumbers = c.int("additional_numbers") ?: 0,
    )

    private fun Cursor.text(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.int(column: String): Int? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    private fun Cursor.decimal(column: String): Double? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getDouble(i)
    }

    private fun toJson(values: List<String>): String = JSONArray(values).toString()

    private fun fromJson(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val field = JSONArray(text)
            val list = ArrayList<String>(field.length())
            for (i in 0 until field.length()) {
                if (!field.isNull(i)) list.add(field.optString(i, ""))
            }
            list.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        /** The business table is aliased `b` in every filtered query. */
        const val TABLE_ALIAS = "b."

        /**
         * Counts the dialable numbers of a business's contacts. That way the
         * work list knows whether its dial button has anything to dial without
         * loading the contacts for every single row.
         */
        const val NUMBERS_SUBQUERY =
            "(SELECT COUNT(*) FROM contact_numbers n " +
                "JOIN contacts a ON a.id = n.contact_id " +
                "WHERE a.place_id = b.place_id AND n.kind <> 'fax') AS additional_numbers"
    }
}
