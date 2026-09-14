# Several appointments per business — App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A business holds any number of appointments, each a row of its own in a synchronised table, each linked to its calendar event by the event's iCalendar UID.

**Architecture:** Schema 4 adds `appointments` (synchronised like `contacts`, deleted through tombstones) and carries the one existing appointment per business over as `legacy-<place_id>`. The sync layer clears marks only for tables the server names in `tables`, and the first sync after the upgrade fetches from watermark 0 once. Every calendar decision — status fallback, which of row and event wins, what a missing event means, adopting, `LocalOnly` — is a pure function in `calling/Appointment.kt`; the view model only carries them out against `CalendarContract`.

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `CalendarContract`, JUnit 4 + Robolectric 4.16, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-14-multiple-appointments-design.md`

**Companion plan:** `../server/docs/superpowers/plans/2026-09-14-multiple-appointments.md` — carried out and **deployed before** this app is released.

## Global Constraints

- **Repo:** `~/code/tm-services-automate/caller-app/app`, branch `main`. Never push `master`. Paths below are relative to this directory; Kotlin sources live under `app/src/main/java/io/github/amadeusb/callsheet/` (written `…/` below), tests under `app/src/test/java/io/github/amadeusb/callsheet/`.
- **Start on a clean tree.** At the time of writing, `ui/BusinessDetail.kt` has an uncommitted change (a card using `LegitimateInterest`) and `calling/LegitimateInterest.kt` plus its test are untracked. They are not part of this work. The user commits or stashes them before Task 1; do not include them in any commit of this plan.
- **Tests:** `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`. Build check: `./gradlew assembleDebug`.
- **Robolectric has no calendar provider.** Nothing that talks to `CalendarContract` can be unit-tested; every decision lives in `Appointment` and is tested there. `CalendarStore`, `BusyTimes` and the view model stay thin.
- **Language:** code, identifiers, comments, docs in English; UI text German. Test names in English; the fixture method stays `fun aufbau()`.
- **Timestamps:** ISO-8601 with offset, produced by `Clock.format`, compared through `Clock.millis`. In new tests, build instants from ISO strings (`Clock.millis("2026-09-10T14:00:00+02:00")!!`), not from the system zone.
- **The old columns stay.** `businesses.appointment_at`, `appointment_end_at`, `appointment_location`, `calendar_event_id` and `idx_businesses_appointment` remain in the schema, emptied; no code of version 4 reads or writes them (Task 10 removes the last reader).
- **Local-only columns:** `dirty`, `contact_version`, `calendar_event_id`, `calendar_seen_starts_at`, `calendar_seen_ends_at`, `calendar_seen_location`. `event_uid` travels.
- **Every task leaves the app compiling and all tests green.** Intermediate UI states may be incomplete; they must build.
- **State writes** in new view model code use `_state.update { … }`.
- **Commit after every task.** German commit messages in the tone of `git log`, no attribution lines.
- **The spec** is untracked; commit it with Task 1, together with this plan.

---

## File Structure

| File | Change |
|---|---|
| `…/data/Database.kt` | `VERSION = 4`, `TABLE_APPOINTMENTS`, indexes, migration 3 → 4 |
| `…/data/Models.kt` | `AppointmentEntry`, `TakenEvents`; Task 10 removes the appointment fields from `Business` |
| `…/data/Repository.kt` | appointment CRUD, `setCalendarLink`, `setEventUid`, `deleteAppointment`, `takenEvents`, `appointmentsDue` as pairs; Task 10 removes `setAppointment` |
| `…/sync/Rows.kt` | `appointments` in `TABLES`, `LEGACY_TABLES`, `serverTables`, calendar-seen columns local |
| `…/sync/SyncStore.kt` | `tables`-aware `clearPending` and `pendingCount`, `apply` returns `AppliedAppointments`, tombstones for appointments |
| `…/sync/SyncEngine.kt` | loop counts only named tables; watermark reset once; `onApplied` |
| `…/contacts/Preferences.kt` | `refetchedForAppointments` |
| `…/calling/Appointment.kt` | status rules, `Slot`, `Reconcile`, `reconcile`, `seenIsCurrent`, `Located`, `locate`, `busyExcept`, `mayAdopt`, `adoptable`, `uidToTake`, `eventEnd`, title/description, `rowLabel`, `split`; `plan` gains `eventUid`; Task 10 removes `ReadBack`/`readBack` |
| `…/calendar/CalendarStore.kt` | `EventFields.uid`, UID on insert, UID and a real end on read, `findByUid`; `read` and `findByUid` throw instead of failing soft |
| `…/calendar/BusyTimes.kt` | `BusyInterval.uid` and `recurring` filled |
| `…/CallsheetViewModel.kt` | draft fields, `calendarLookup`, open/save per appointment, read-back per appointment, removal, after-sync follow-up, `appointmentsToday` as pairs |
| `…/ui/AppointmentSheet.kt` | Notiz, Ansprechpartner, "event elsewhere" line, Verknüpfen only for adoptable events |
| `…/ui/BusinessDetail.kt` | list of appointments ahead, collapsed Frühere Termine, removal dialog per appointment |
| `…/ui/Today.kt`, `…/ui/Components.kt` | one row per appointment |
| `…/MainActivity.kt` | wiring |
| tests | `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `SyncEngineTest`, `RepositoryTest`, `AppointmentTest` |
| `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md` | docs, 1.4.0 section |

---

### Task 1: Schema 4 — the table and the carry-over

**Files:**
- Modify: `…/data/Database.kt`
- Test: `MigrationTest.kt`, `SyncSchemaTest.kt`

**Interfaces:**
- Produces: table `appointments` with columns `id, place_id, starts_at, ends_at, location, note, contact_id, updated_at, event_uid, calendar_event_id, calendar_seen_starts_at, calendar_seen_ends_at, calendar_seen_location, dirty`; `Database.VERSION == 4`.

- [ ] **Step 1: Start from a clean tree**

Run: `git status --porcelain`
Expected: exactly two lines, `?? docs/superpowers/plans/2026-09-14-multiple-appointments.md` and `?? docs/superpowers/specs/2026-09-14-multiple-appointments-design.md`. Anything else — the `LegitimateInterest` work, an uncommitted `ui/BusinessDetail.kt` — **stop and ask the user** to commit or stash it. Every later commit lists its files, but Task 10 rewrites `ui/BusinessDetail.kt`, and a stash popped over that conflicts.

- [ ] **Step 2: Write the failing tests**

In `SyncSchemaTest.kt`, `every synchronised table carries a dirty flag`, extend the list:

```kotlin
        for (table in listOf("businesses", "calls", "contacts", "contact_numbers", "appointments")) {
```

In `MigrationTest.kt`, add below `createVersionTwo()`:

```kotlin
    /**
     * The version 3 schema, as it shipped in 1.2.0–1.3.1: version 2 plus the
     * appointment columns on businesses. alt-1 has an appointment linked to an
     * event on this device, alt-2 one without a link, alt-3 none. All three
     * already synchronised (dirty = 0).
     */
    private fun createVersionThree() {
        createVersionTwo()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        for (column in listOf(
            "appointment_at TEXT", "appointment_end_at TEXT", "appointment_location TEXT",
            "calendar_event_id INTEGER", "latitude REAL", "longitude REAL",
        )) {
            db.execSQL("ALTER TABLE businesses ADD COLUMN $column")
        }
        db.execSQL("CREATE INDEX idx_businesses_appointment ON businesses(appointment_at)")
        db.execSQL(
            "UPDATE businesses SET appointment_at = '2026-09-10T14:00:00+02:00', " +
                "appointment_end_at = '2026-09-10T15:00:00+02:00', " +
                "appointment_location = 'Zehentstraße 39, 85055 Ingolstadt', calendar_event_id = 4711, dirty = 0 " +
                "WHERE place_id = 'alt-1'"
        )
        db.execSQL(
            "INSERT INTO businesses (place_id, name, status, updated_at, dirty, appointment_at, appointment_end_at) " +
                "VALUES ('alt-2', 'Zweiter Betrieb', 'appointment', '2026-09-08T09:00:00+02:00', 0, " +
                "'2026-09-12T09:00:00+02:00', '2026-09-12T10:00:00+02:00')"
        )
        db.execSQL(
            "INSERT INTO businesses (place_id, name, status, updated_at, dirty) " +
                "VALUES ('alt-3', 'Ohne Termin', 'new', '2026-09-08T09:00:00+02:00', 0)"
        )
        db.version = 3
        db.close()
    }

    private fun columnsOf(table: String, db: android.database.sqlite.SQLiteDatabase): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(1) else null }.toSet()
        }
```

Add before `// --- and a database that never had to migrate at all`:

```kotlin
    // --- from version 3, the road 1.3.x devices are on ----------------------

    @Test
    fun `an upgrade from version three carries each appointment over as a row of its own`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, starts_at, ends_at, location, note, contact_id, event_uid, updated_at " +
                "FROM appointments ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("legacy-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(2))
            assertEquals("2026-09-10T15:00:00+02:00", c.getString(3))
            assertEquals("Zehentstraße 39, 85055 Ingolstadt", c.getString(4))
            assertTrue(c.isNull(5))
            assertTrue(c.isNull(6))
            assertTrue(c.isNull(7))
            // The business's timestamp, so the server's carried-over row meets
            // this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(8))
            assertTrue(c.moveToNext())
            assertEquals("legacy-alt-2", c.getString(0))
        }
    }

    @Test
    fun `an upgrade from version three keeps the calendar link and what this device saw`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT calendar_event_id, calendar_seen_starts_at, calendar_seen_ends_at, calendar_seen_location " +
                "FROM appointments WHERE id = 'legacy-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4711L, c.getLong(0))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(1))
            assertEquals("2026-09-10T15:00:00+02:00", c.getString(2))
            assertEquals("Zehentstraße 39, 85055 Ingolstadt", c.getString(3))
        }
        // Without a link there is nothing this device has seen.
        db.rawQuery(
            "SELECT calendar_event_id, calendar_seen_starts_at FROM appointments WHERE id = 'legacy-alt-2'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun `an upgrade from version three marks the carried-over rows for upload`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT COUNT(*) FROM appointments WHERE dirty = 1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }

    @Test
    fun `an upgrade from version three empties the old columns without marking the business`() {
        createVersionThree()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT appointment_at, appointment_end_at, appointment_location, calendar_event_id, dirty, updated_at " +
                "FROM businesses WHERE place_id = 'alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            for (i in 0..3) assertTrue("column $i not emptied", c.isNull(i))
            assertEquals(0, c.getInt(4))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(5))
        }
        assertTrue(columnsOfBusinesses().containsAll(appointmentColumns))
    }

    @Test
    fun `a fresh database and an upgraded one have the same appointments table`() {
        createVersionThree()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("appointments", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("appointments", Database(context).readableDatabase)

        // PRAGMA on a missing table returns no columns on both sides, which
        // would compare equal before the table exists.
        assertTrue("event_uid" in fresh)
        assertEquals(fresh, upgraded)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: FAIL — `no such table: appointments`

- [ ] **Step 4: Add the table to `Database.kt`**

In the companion object, below `INDEX_NUMBERS`:

```kotlin
        /**
         * Appointments on site, several per business. Synchronised like
         * contacts: a UUID per row, deleted through tombstones.
         *
         * `event_uid` names the linked calendar event by its iCalendar UID,
         * which is the same on every device carrying the shared calendar, so it
         * travels. The `calendar_` columns do not (see Rows.LOCAL_ONLY): an
         * event's `_ID`, and what this device last saw in it, describe this
         * device's calendar provider and nothing else.
         */
        private const val TABLE_APPOINTMENTS = """
            CREATE TABLE appointments (
                id                      TEXT PRIMARY KEY,
                place_id                TEXT NOT NULL,
                starts_at               TEXT NOT NULL,
                ends_at                 TEXT,
                location                TEXT,
                note                    TEXT,
                contact_id              TEXT,
                updated_at              TEXT NOT NULL,
                event_uid               TEXT,
                calendar_event_id       INTEGER,
                calendar_seen_starts_at TEXT,
                calendar_seen_ends_at   TEXT,
                calendar_seen_location  TEXT,
                dirty                   INTEGER NOT NULL DEFAULT 0
            )
        """

        private val INDEXES_APPOINTMENTS = listOf(
            "CREATE INDEX idx_appointments_place_id ON appointments(place_id)",
            "CREATE INDEX idx_appointments_starts_at ON appointments(starts_at)",
            "CREATE INDEX idx_appointments_event_uid ON appointments(event_uid)",
            "CREATE INDEX idx_appointments_dirty ON appointments(dirty)",
        )
```

Set `const val VERSION = 4`.

In `onCreate`, after `db.execSQL(TABLE_DELETIONS)`:

```kotlin
        db.execSQL(TABLE_APPOINTMENTS)
        for (sql in INDEXES_APPOINTMENTS) db.execSQL(sql)
```

In `CREATE TABLE businesses`, replace **both** comments on the old appointment columns — the one above `appointment_at` (`-- Appointment on site. Working fields, like status and …`, two lines) and the one above `calendar_event_id` (`-- The linked event in the device calendar, null while none …`, two lines) — with this single comment above `appointment_at`:

```kotlin
                -- The one appointment a business held up to schema 3. Emptied
                -- by the migration to 4 and read by nothing since — the
                -- appointments table holds them. Kept so a fresh and an
                -- upgraded database have the same columns.
```

- [ ] **Step 5: Add the migration**

In `onUpgrade`, after the `if (old < 3)` block:

```kotlin
        if (old < 4) {
            db.execSQL(TABLE_APPOINTMENTS)
            for (sql in INDEXES_APPOINTMENTS) db.execSQL(sql)
            // The one appointment per business becomes a row. The id is fixed,
            // not a fresh UUID: the server's migration 005 writes the same
            // 'legacy-' || place_id, so the two meet as one row instead of
            // every appointment existing twice. updated_at comes from the
            // business for the same reason — where it was synchronised, both
            // sides hold the same timestamp.
            //
            // The link to the event stays, and what this device saw in the
            // event is taken from the old columns: the read-back has kept the
            // two in step so far. The UID is not known yet; the first
            // read-back that finds the event stores it.
            //
            // Marked dirty: an appointment never uploaded still reaches the
            // server, and one the server already has arrives as a standstill.
            db.execSQL(
                """
                INSERT INTO appointments (
                    id, place_id, starts_at, ends_at, location, updated_at,
                    calendar_event_id, calendar_seen_starts_at, calendar_seen_ends_at, calendar_seen_location,
                    dirty
                )
                SELECT 'legacy-' || place_id, place_id, appointment_at, appointment_end_at, appointment_location, updated_at,
                       calendar_event_id,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_at END,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_end_at END,
                       CASE WHEN calendar_event_id IS NOT NULL THEN appointment_location END,
                       1
                FROM businesses
                WHERE appointment_at IS NOT NULL AND appointment_at <> ''
                """.trimIndent()
            )
            // Emptied on both sides, or the server would fill the gap straight
            // back on the next standstill. Not a change to the business: no
            // updated_at, no mark.
            db.execSQL(
                "UPDATE businesses SET appointment_at = NULL, appointment_end_at = NULL, " +
                    "appointment_location = NULL, calendar_event_id = NULL " +
                    "WHERE appointment_at IS NOT NULL OR appointment_end_at IS NOT NULL " +
                    "OR appointment_location IS NOT NULL OR calendar_event_id IS NOT NULL"
            )
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: PASS

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. The app still reads the old business columns; they are empty after an upgrade, which is fine until Task 8 onward.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt docs/superpowers/specs/2026-09-14-multiple-appointments-design.md docs/superpowers/plans/2026-09-14-multiple-appointments.md
git commit -m "Schema 4: eigene Tabelle für Termine, bestehende als legacy-Zeilen übernommen"
```

---
### Task 2: Synchronising the table — and a server that does not know it

**Files:**
- Modify: `…/sync/Rows.kt`, `…/sync/SyncStore.kt`, `…/sync/SyncEngine.kt`
- Test: `SyncStoreTest.kt`, `SyncSchemaTest.kt`, `SyncEngineTest.kt`

**Interfaces:**
- Consumes: table `appointments` (Task 1).
- Produces:
  - `Rows.TABLES` = `listOf("businesses", "calls", "contacts", "contact_numbers", "appointments")`
  - `Rows.LEGACY_TABLES: Set<String>` (the four old tables), `Rows.serverTables(response: JSONObject): Set<String>`
  - `data class AppliedAppointments(val written: List<String> = emptyList(), val removed: List<RemovedAppointment> = emptyList())` with `fun isEmpty(): Boolean`
  - `data class RemovedAppointment(val id: String, val calendarEventId: Long?, val eventUid: String?)`
  - `SyncStore.apply(response: JSONObject): AppliedAppointments`
  - `SyncStore.pendingCount(tables: Set<String> = Rows.TABLES.toSet()): Int`

- [ ] **Step 1: Write the failing tests**

In `SyncStoreTest.kt`, add helpers below `betriebJson`:

```kotlin
    private fun einTermin(id: String, zeit: String, dirty: Int, eventId: Long? = null) = schreibe(
        "INSERT INTO appointments (id, place_id, starts_at, ends_at, location, updated_at, event_uid, " +
            "calendar_event_id, calendar_seen_starts_at, dirty) VALUES ('$id', 'P1', " +
            "'2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', 'Zehentstraße 39', '$zeit', '$id', " +
            "${eventId ?: "NULL"}, ${if (eventId != null) "'2026-09-10T14:00:00+02:00'" else "NULL"}, $dirty)"
    )

    private fun terminJson(id: String, zeit: String, start: String = "2026-09-10T16:00:00+02:00") = JSONObject().apply {
        put("id", id); put("place_id", "P1"); put("starts_at", start)
        put("ends_at", JSONObject.NULL); put("location", JSONObject.NULL); put("note", "Angebot")
        put("contact_id", JSONObject.NULL); put("event_uid", id); put("updated_at", zeit)
    }
```

The tests below count with the helper `zahl(sql)` the file already has at its end — do not add a second one.

Append these tests:

```kotlin
    @Test
    fun `pending carries an appointment's event_uid but none of its calendar columns`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1, eventId = 4711)

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("T1", row.getString("event_uid"))
        for (column in listOf("calendar_event_id", "calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location", "dirty")) {
            assertFalse("$column must not travel", row.has(column))
        }
    }

    @Test
    fun `appointments come down and are reported as written`() {
        val response = leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00"))))

        val applied = store.apply(response)

        assertEquals(listOf("T1"), applied.written)
        assertEquals(1, zahl("SELECT COUNT(*) FROM appointments WHERE id = 'T1' AND dirty = 0"))
    }

    @Test
    fun `an incoming appointment leaves this device's calendar link alone`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 0, eventId = 4711)
        val incoming = terminJson("T1", "2026-09-07T11:00:00+02:00").put("calendar_event_id", 99)

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        Database(ctx).readableDatabase.rawQuery(
            "SELECT starts_at, calendar_event_id, calendar_seen_starts_at FROM appointments WHERE id = 'T1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-09-10T16:00:00+02:00", c.getString(0))
            assertEquals(4711L, c.getLong(1))
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(2))
        }
    }

    @Test
    fun `an older incoming appointment is not reported as written`() {
        einTermin("T1", "2026-09-07T12:00:00+02:00", dirty = 0)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00")))))

        assertTrue(applied.written.isEmpty())
    }

    @Test
    fun `an incoming appointment no newer than a local tombstone is not written`() {
        // Deleted here, not yet uploaded; the server still hands out the older row.
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T1', '2026-09-07T11:00:00+02:00')")

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T10:00:00+02:00")))))

        assertTrue(applied.written.isEmpty())
        assertEquals(0, zahl("SELECT COUNT(*) FROM appointments"))
    }

    @Test
    fun `a remote tombstone removes an appointment and reports the link it had`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 0, eventId = 4711)
        val stone = JSONObject().apply {
            put("table_name", "appointments"); put("row_id", "T1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
        }

        val applied = store.apply(leereAntwort().put("deleted", JSONArray(listOf(stone))))

        assertEquals(0, zahl("SELECT COUNT(*) FROM appointments"))
        assertEquals(listOf(io.github.amadeusb.callsheet.sync.RemovedAppointment("T1", 4711L, "T1")), applied.removed)
    }

    @Test
    fun `clearPending leaves appointments marked when the response names no tables`() {
        // An older server: it ignores payload.appointments without a word.
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P1", "offen", "2026-09-07T10:00:00+02:00", dirty = 1)
        val sent = store.pending(500)

        store.clearPending(sent, leereAntwort())

        assertEquals(1, zahl("SELECT dirty FROM appointments WHERE id = 'T1'"))
        assertEquals(0, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `clearPending leaves an appointment tombstone queued when tables does not name appointments`() {
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T1', '2026-09-07T11:00:00+02:00')")
        val sent = store.pending(500)
        val response = leereAntwort().put("tables", JSONArray(listOf("businesses", "calls", "contacts", "contact_numbers")))

        store.clearPending(sent, response)

        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'appointments'"))
    }

    @Test
    fun `clearPending clears appointments once the response names them`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        val sent = store.pending(500)
        val response = leereAntwort().put("tables", JSONArray(listOf("businesses", "calls", "contacts", "contact_numbers", "appointments")))

        store.clearPending(sent, response)

        assertEquals(0, zahl("SELECT dirty FROM appointments WHERE id = 'T1'"))
    }

    @Test
    fun `pendingCount counts only the tables it is given`() {
        einTermin("T1", "2026-09-07T10:00:00+02:00", dirty = 1)
        einBetrieb("P1", "offen", "2026-09-07T10:00:00+02:00", dirty = 1)
        schreibe("INSERT INTO deletions (table_name, row_id, deleted_at) VALUES ('appointments', 'T2', '2026-09-07T11:00:00+02:00')")

        assertEquals(3, store.pendingCount())
        assertEquals(1, store.pendingCount(io.github.amadeusb.callsheet.sync.Rows.LEGACY_TABLES))
    }
```

In `SyncSchemaTest.kt`, append:

```kotlin
    @Test
    fun `what this device saw in an event stays on the device`() {
        val row = JSONObject().apply {
            put("id", "T1")
            put("event_uid", "T1")
            put("calendar_seen_starts_at", "2026-09-10T14:00:00+02:00")
            put("calendar_seen_ends_at", "2026-09-10T15:00:00+02:00")
            put("calendar_seen_location", "Zehentstraße 39")
        }
        val columns = row.keys().asSequence().toSet()

        val values = Rows.toValues(row, columns)

        assertEquals("T1", values.getAsString("event_uid"))
        for (column in listOf("calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location")) {
            assertFalse("$column must not come in from the server", values.containsKey(column))
        }
    }
```

In `SyncEngineTest.kt`, append:

```kotlin
    @Test
    fun `appointments an older server ignores do not keep the loop turning`() {
        val db = Database(ctx).writableDatabase
        db.beginTransaction()
        try {
            for (i in 1..600) {
                db.execSQL(
                    "INSERT INTO appointments (id, place_id, starts_at, updated_at, dirty) " +
                        "VALUES ('T$i', 'P1', '2026-09-10T14:00:00+02:00', '2026-09-07T10:00:00+02:00', 1)"
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        var calls = 0

        val result = engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                calls++
                return leereAntwort(5) // no "tables": a server from before appointments
            }
        })

        assertEquals(SyncResult.Ok, result)
        assertEquals(1, calls)
        // Still open, and honestly counted as open, until the server is updated.
        assertEquals(600, SyncStore(ctx).pendingCount())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest" --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: FAIL — compile errors for `RemovedAppointment`, `Rows.LEGACY_TABLES`, `pendingCount(…)` and `apply(…).written`

- [ ] **Step 3: Implement `Rows.kt`**

Replace `TABLES` and `LOCAL_ONLY`, and add `LEGACY_TABLES` and `serverTables`:

```kotlin
    val TABLES = listOf("businesses", "calls", "contacts", "contact_numbers", "appointments")

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
```

```kotlin
    /**
     * Columns that never leave the device.
     *
     * `calendar_event_id` points into this device's calendar provider. The same
     * number on another device is a different event, or none. The
     * `calendar_seen_` columns record what this device last saw in its copy of
     * the event — another device's copy may be ahead or behind. `event_uid` is
     * deliberately absent: the UID is the same event on every device carrying
     * the shared calendar, and that is what the other devices look it up by.
     */
    private val LOCAL_ONLY = setOf(
        "dirty", "contact_version", "calendar_event_id",
        "calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location",
    )
```

- [ ] **Step 4: Implement `SyncStore.kt`**

Above `class SyncStore`, add:

```kotlin
/** What [SyncStore.apply] did to appointments, so the calendar can follow. */
data class AppliedAppointments(
    /** Appointments written from the server. Their local links are still on the rows. */
    val written: List<String> = emptyList(),
    /** Appointments a tombstone removed, with the link each had on this device. */
    val removed: List<RemovedAppointment> = emptyList(),
) {
    fun isEmpty(): Boolean = written.isEmpty() && removed.isEmpty()
}

/** An appointment that is gone from the database, and where its event was. */
data class RemovedAppointment(val id: String, val calendarEventId: Long?, val eventUid: String?)
```

Change the doc of `markAllDirty` from "the four synchronised tables" to "every synchronised table".

Replace `pendingCount`:

```kotlin
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
```

In `clearPending`, add after the `rejected` set is built:

```kotlin
        // Only what the server says it synchronises was received. See Rows.serverTables.
        val received = Rows.serverTables(response)
```

In its row loop, first line inside `for (table in Rows.TABLES) {`:

```kotlin
                if (table !in received) continue
```

In its tombstone loop, first line inside the `for`, after `val stone = …`:

```kotlin
                if (stone.getString("table_name") !in received) continue
```

Append to the KDoc of `clearPending`:

```kotlin
     *
     * Nor is anything cleared for a table the server did not name in `tables`:
     * an older server drops such rows in silence, and they stay marked until a
     * server that knows the table has them.
```

Replace `apply`:

```kotlin
    /** Applies what the server sent. Never marks anything as dirty. */
    fun apply(response: JSONObject): AppliedAppointments {
        val db = helper.writableDatabase
        // Fetched once per call rather than once per row — the schema does
        // not change mid-sync, and a first sync can carry a few thousand rows.
        val columnsByTable = Rows.TABLES.associateWith { columns(db, it) }
        val written = ArrayList<String>()
        val removed = ArrayList<RemovedAppointment>()
        db.beginTransaction()
        try {
            val deletions = response.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until deletions.length()) {
                applyTombstone(db, deletions.getJSONObject(i))?.let { removed.add(it) }
            }
            for (table in Rows.TABLES) {
                val rows = response.optJSONArray(table) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (applyRow(db, table, row, columnsByTable.getValue(table)) && table == "appointments") {
                        written.add(row.getString("id"))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return AppliedAppointments(written, removed)
    }
```

Change `applyRow` to return `Boolean`: signature `private fun applyRow(…): Boolean`; every bare `return` becomes `return false`; the final `if (local == null) … else …` is followed by `return true`.

Change `applyTombstone` to return what it removed:

```kotlin
    private fun applyTombstone(db: SQLiteDatabase, stone: JSONObject): RemovedAppointment? {
        val table = stone.getString("table_name")
        val id = stone.getString("row_id")
        val at = stone.getString("deleted_at")
        if (table !in TOMBSTONE_TABLES) return null
```

Keep the `db.delete("deletions", …)` block unchanged. Replace the rest of the function body after it with:

```kotlin
        val key = Rows.key(table)
        val localAt = db.rawQuery("SELECT updated_at FROM $table WHERE $key = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (localAt == null || Merge.isNewer(localAt, at)) return null

        // The link has to be read before the row goes, or the calendar could
        // not follow the deletion.
        val link = if (table == "appointments") {
            db.rawQuery("SELECT calendar_event_id, event_uid FROM appointments WHERE id = ?", arrayOf(id)).use { c ->
                c.moveToFirst()
                RemovedAppointment(id, if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getString(1))
            }
        } else {
            null
        }
        db.delete(table, "$key = ?", arrayOf(id))
        // Numbers only follow the contact into deletion when the contact
        // row itself is actually removed — a contact that survived
        // because it is younger than the tombstone keeps its numbers.
        if (table == "contacts") db.delete("contact_numbers", "contact_id = ?", arrayOf(id))
        return link
    }
```

Replace the companion:

```kotlin
    private companion object {
        /**
         * The only tables the app ever deletes rows from. `businesses` has no
         * `id` column (its key is `place_id`), and the app never deletes a
         * business or a call — a tombstone naming either must never reach SQL.
         */
        val TOMBSTONE_TABLES = setOf("contacts", "contact_numbers", "appointments")
    }
```

- [ ] **Step 5: Implement the loop decision in `SyncEngine.kt`**

In `sync`, replace from `store.apply(response)` to the `val more = …` statement with:

```kotlin
                store.apply(response)
                store.clearPending(outgoing, response)
                // The watermark only ever moves forward. A stale or
                // misbehaving server sending a lower value must not put the
                // client behind where it already stood.
                val watermark = response.optInt("watermark", prefs.watermark)
                if (watermark > prefs.watermark) prefs.watermark = watermark

                val remaining = store.pendingCount()
                onProgress(remaining, total)

                // Counted over the tables the server named only. Rows an older
                // server ignores stay marked; counting them would turn a block
                // full of them into MAX_ROUNDS of resending.
                val received = Rows.serverTables(response)
                val more = response.optBoolean("more", false) ||
                    (sentCount(outgoing, received) >= BLOCK && store.pendingCount(received) > 0)
```

Replace `sentCount`:

```kotlin
    /** How many rows [payload] carried for [tables] — rows and tombstones alike. */
    private fun sentCount(payload: JSONObject, tables: Set<String>): Int {
        var total = 0
        val deletions = payload.optJSONArray("deleted")
        if (deletions != null) {
            for (i in 0 until deletions.length()) {
                if (deletions.getJSONObject(i).optString("table_name") in tables) total++
            }
        }
        for (table in Rows.TABLES) {
            if (table in tables) total += payload.optJSONArray(table)?.length() ?: 0
        }
        return total
    }
```

Known and accepted: against an older server, 500 or more marked appointments fill every block, and the tombstones behind them — of every table, since `pending` reads deletions last — wait until the server is updated — a delay, not a loss, which is what the spec settles for.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, the whole suite

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/sync app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncEngineTest.kt
git commit -m "Termine abgleichen, Markierungen nur für Tabellen löschen, die der Server nennt"
```

---

### Task 3: The first sync after the upgrade, and handing over what was applied

**Files:**
- Modify: `…/contacts/Preferences.kt`, `…/sync/SyncEngine.kt`
- Test: `SyncEngineTest.kt`

**Interfaces:**
- Consumes: `AppliedAppointments`, `SyncStore.apply` (Task 2).
- Produces:
  - `Preferences.refetchedForAppointments: Boolean`
  - `SyncEngine.sync(transport: Transport, onProgress: (remaining: Int, total: Int) -> Unit = { _, _ -> }, onApplied: (AppliedAppointments) -> Unit = {}): SyncResult`

- [ ] **Step 1: Write the failing tests**

In `SyncEngineTest.kt`, append:

```kotlin
    @Test
    fun `the first sync after the upgrade starts from watermark zero, and only that one`() {
        prefs.watermark = 42
        prefs.refetchedForAppointments = false
        val seen = mutableListOf<Int>()
        val transport = object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                seen.add(payload.getInt("since"))
                return leereAntwort(17)
            }
        }

        engine.sync(transport)
        engine.sync(transport)

        assertEquals(listOf(0, 17), seen)
    }

    @Test
    fun `a device that has fetched everything since keeps its watermark`() {
        prefs.watermark = 42
        prefs.refetchedForAppointments = true
        val seen = mutableListOf<Int>()

        engine.sync(object : Transport {
            override fun post(payload: JSONObject): JSONObject {
                seen.add(payload.getInt("since"))
                return leereAntwort(42)
            }
        })

        assertEquals(listOf(42), seen)
    }

    @Test
    fun `appointments applied from the server are handed to the caller`() {
        val handed = mutableListOf<io.github.amadeusb.callsheet.sync.AppliedAppointments>()
        val appointment = JSONObject().apply {
            put("id", "T1"); put("place_id", "P1"); put("starts_at", "2026-09-10T14:00:00+02:00")
            put("updated_at", "2026-09-07T10:00:00+02:00")
        }

        engine.sync(
            object : Transport {
                override fun post(payload: JSONObject) =
                    leereAntwort(3).put("appointments", JSONArray(listOf(appointment)))
            },
            onApplied = { handed.add(it) },
        )

        assertEquals(listOf("T1"), handed.single().written)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: FAIL — `Unresolved reference: refetchedForAppointments` and `onApplied`

- [ ] **Step 3: Add the flag to `Preferences.kt`**

Below `lastSyncAt`:

```kotlin
    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about appointments (schema 4).
     *
     * While it still ran 1.3.x, the server delivered appointments to it — the
     * ones migration 005 carried over, and any another device created — and
     * the old app skipped them while its watermark moved past. They would never
     * come down again. Worse, an appointment deleted elsewhere would come back:
     * the migration writes its legacy row afresh, the server drops it for its
     * newer tombstone, and the tombstone never reaches this device. One fetch
     * from zero repairs both, and costs one full download.
     *
     * A flag here rather than a step in the migration, because the watermark
     * lives in these preferences and not in the database.
     */
    var refetchedForAppointments: Boolean
        get() = store.getBoolean(REFETCHED_FOR_APPOINTMENTS, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_APPOINTMENTS, value).apply()
```

In the companion: `const val REFETCHED_FOR_APPOINTMENTS = "refetched_for_appointments"`.

- [ ] **Step 4: Use it and hand over the applied rows in `SyncEngine.kt`**

Change the signature and document the new parameter:

```kotlin
    /**
     * Runs one exchange with the server.
     *
     * [onProgress] is called with how many rows are still waiting to go up and
     * how many were waiting when this run began — after every round, and once
     * before the first. Both zero means there is nothing to upload and only the
     * download is left, which has no total to count against: the server does not
     * say how much it holds until it stops saying "more".
     *
     * [onApplied] receives, per block, the appointments that came down or were
     * deleted, so the caller can bring the calendar along. Called on the sync
     * thread, after the block's transaction.
     */
    fun sync(
        transport: Transport,
        onProgress: (remaining: Int, total: Int) -> Unit = { _, _ -> },
        onApplied: (AppliedAppointments) -> Unit = {},
    ): SyncResult {
```

Inside `try {`, before `var rounds = 0`:

```kotlin
            // Once, on the first sync that runs on schema 4 — see
            // Preferences.refetchedForAppointments. Fetching everything again is
            // safe: apply lets the newer version win and drops what a tombstone
            // covers, so rows this device already holds change nothing.
            if (!prefs.refetchedForAppointments) {
                prefs.watermark = 0
                prefs.refetchedForAppointments = true
            }
```

Replace `store.apply(response)` with:

```kotlin
                val applied = store.apply(response)
                if (!applied.isEmpty()) onApplied(applied)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. The existing engine tests start at watermark 0 with the flag unset, so the reset changes nothing for them.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt app/src/test/java/io/github/amadeusb/callsheet/SyncEngineTest.kt
git commit -m "Erster Abgleich nach dem Update holt alles ab Wasserstand 0, eingespielte Termine gehen an den Aufrufer"
```

---

### Task 4: Appointments in the repository

**Files:**
- Modify: `…/data/Models.kt`, `…/data/Repository.kt`
- Test: `RepositoryTest.kt`

**Interfaces:**
- Consumes: table `appointments` (Task 1).
- Produces:
  - `data class AppointmentEntry(id: String, placeId: String, startsAt: String, endsAt: String?, location: String?, note: String?, contactId: String?, updatedAt: String = "", eventUid: String? = null, calendarEventId: Long? = null, seenStartsAt: String? = null, seenEndsAt: String? = null, seenLocation: String? = null)`
  - `data class TakenEvents(val uids: Set<String>, val eventIds: Set<Long>)`
  - `Repository.appointments(placeId: String): List<AppointmentEntry>` — earliest first
  - `Repository.appointment(id: String): AppointmentEntry?`
  - `Repository.saveAppointment(entry: AppointmentEntry)` — insert or update, new `updated_at`, `dirty = 1`, local columns untouched; a null `eventUid` never clears a stored one
  - `Repository.setCalendarLink(id: String, eventId: Long?, seenStartsAt: String?, seenEndsAt: String?, seenLocation: String?)` — local only
  - `Repository.setEventUid(id: String, uid: String)` — travels
  - `Repository.deleteAppointment(id: String)` — with tombstone
  - `Repository.takenEvents(): TakenEvents`

- [ ] **Step 1: Write the failing tests**

In `RepositoryTest.kt`, add imports `io.github.amadeusb.callsheet.data.AppointmentEntry`. Delete the three tests `an appointment is stored and read back`, `an appointment can be cleared completely` and `a second import leaves the appointment alone`. Leave the three `appointmentsDue` tests alone — Task 9 replaces them.

Below the `// ---- Appointment` divider, add:

```kotlin
    // Through the shared helper the repository uses, which aufbau() resets —
    // a fresh Database(ctx) per call would open a connection nobody closes.
    private fun count(sql: String): Int =
        Database.instance(ApplicationProvider.getApplicationContext<android.content.Context>()).readableDatabase
            .rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun execute(sql: String) =
        Database.instance(ApplicationProvider.getApplicationContext<android.content.Context>()).writableDatabase.execSQL(sql)

    private fun visit(id: String, placeId: String, startsAt: String, endsAt: String? = null, note: String? = null) =
        AppointmentEntry(
            id = id, placeId = placeId, startsAt = startsAt, endsAt = endsAt,
            location = null, note = note, contactId = null,
        )

    @Test
    fun `an appointment is stored, marked for upload and read back`() = runTest {
        repo.saveAppointment(
            visit("A-1", "t-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", note = "Besichtigung")
                .copy(location = "Zehentstraße 39, 85055 Ingolstadt", contactId = "K-1", eventUid = "A-1")
        )

        val stored = repo.appointment("A-1")!!
        assertEquals("t-1", stored.placeId)
        assertEquals("2026-09-10T14:00:00+02:00", stored.startsAt)
        assertEquals("2026-09-10T15:00:00+02:00", stored.endsAt)
        assertEquals("Zehentstraße 39, 85055 Ingolstadt", stored.location)
        assertEquals("Besichtigung", stored.note)
        assertEquals("K-1", stored.contactId)
        assertEquals("A-1", stored.eventUid)
        assertTrue(stored.updatedAt.isNotBlank())
        assertEquals(1, count("SELECT dirty FROM appointments WHERE id = 'A-1'"))
    }

    @Test
    fun `a business's appointments come back earliest first`() = runTest {
        repo.saveAppointment(visit("A-2", "t-1", "2026-09-12T09:00:00+02:00"))
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        repo.saveAppointment(visit("B-1", "t-2", "2026-09-11T09:00:00+02:00"))

        assertEquals(listOf("A-1", "A-2"), repo.appointments("t-1").map { it.id })
    }

    @Test
    fun `saving an appointment again changes it instead of adding one`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T16:00:00+02:00", note = "verschoben"))

        val all = repo.appointments("t-1")
        assertEquals(1, all.size)
        assertEquals("2026-09-10T16:00:00+02:00", all.single().startsAt)
        assertEquals("verschoben", all.single().note)
    }

    @Test
    fun `saving an entry without a UID keeps the UID already stored`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        repo.setEventUid("A-1", "abc@infomaniak")

        // A draft loaded before the read-back took the UID over carries none.
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T16:00:00+02:00"))

        val stored = repo.appointment("A-1")!!
        assertEquals("abc@infomaniak", stored.eventUid)
        assertEquals("2026-09-10T16:00:00+02:00", stored.startsAt)
    }

    @Test
    fun `saving leaves this device's calendar link alone`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        repo.setCalendarLink("A-1", 4711L, "2026-09-10T14:00:00+02:00", null, null)

        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T16:00:00+02:00"))

        assertEquals(4711L, repo.appointment("A-1")!!.calendarEventId)
    }

    @Test
    fun `deleting an appointment leaves a tombstone`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))

        repo.deleteAppointment("A-1")

        assertNull(repo.appointment("A-1"))
        assertEquals(1, count("SELECT COUNT(*) FROM deletions WHERE table_name = 'appointments' AND row_id = 'A-1'"))
    }

    @Test
    fun `the calendar link is local and marks nothing`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        execute("UPDATE appointments SET dirty = 0")
        val before = repo.appointment("A-1")!!.updatedAt

        repo.setCalendarLink("A-1", 4711L, "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Zehentstraße 39")

        val linked = repo.appointment("A-1")!!
        assertEquals(4711L, linked.calendarEventId)
        assertEquals("2026-09-10T14:00:00+02:00", linked.seenStartsAt)
        assertEquals("2026-09-10T15:00:00+02:00", linked.seenEndsAt)
        assertEquals("Zehentstraße 39", linked.seenLocation)
        assertEquals(before, linked.updatedAt)
        assertEquals(0, count("SELECT dirty FROM appointments WHERE id = 'A-1'"))

        repo.setCalendarLink("A-1", null, null, null, null)

        val unlinked = repo.appointment("A-1")!!
        assertNull(unlinked.calendarEventId)
        assertNull(unlinked.seenStartsAt)
    }

    @Test
    fun `taking over a UID is a change that travels`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        execute("UPDATE appointments SET dirty = 0")

        repo.setEventUid("A-1", "abc@infomaniak")

        assertEquals("abc@infomaniak", repo.appointment("A-1")!!.eventUid)
        assertEquals(1, count("SELECT dirty FROM appointments WHERE id = 'A-1'"))
    }

    @Test
    fun `a second import leaves appointments alone`() = runTest {
        import("""[{"placeId":"t-3","title":"Gartenbau Merten","phone":"+49 841 111"}]""")
        repo.saveAppointment(visit("A-3", "t-3", "2026-09-10T14:00:00+02:00", note = "Besichtigung"))

        import("""[{"placeId":"t-3","title":"Gartenbau Merten GmbH","phone":"+49 841 222"}]""")

        assertEquals("Gartenbau Merten GmbH", repo.business("t-3")!!.name)
        assertEquals("Besichtigung", repo.appointments("t-3").single().note)
    }

    @Test
    fun `takenEvents names every UID and local event id in use`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00").copy(eventUid = "uid-1"))
        repo.saveAppointment(visit("A-2", "t-1", "2026-09-11T14:00:00+02:00"))
        repo.setCalendarLink("A-2", 4711L, null, null, null)

        val taken = repo.takenEvents()

        assertEquals(setOf("uid-1"), taken.uids)
        assertEquals(setOf(4711L), taken.eventIds)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: FAIL — `Unresolved reference: AppointmentEntry`

- [ ] **Step 3: Add the models**

In `Models.kt`, below `CallEntry`:

```kotlin
/**
 * An appointment on site. A business can have any number of them —
 * one after another, or side by side.
 *
 * [eventUid] names the linked calendar event on every device carrying the
 * shared calendar. [calendarEventId] and the `seen` fields describe this
 * device's calendar only and never travel.
 */
data class AppointmentEntry(
    val id: String,
    val placeId: String,
    val startsAt: String,
    val endsAt: String?,
    /** One line, as it goes into the calendar event. */
    val location: String?,
    /** „Besichtigung", „Angebot" … */
    val note: String?,
    /** One of the business's contacts. A reference and nothing more: it may point at a deleted one. */
    val contactId: String?,
    /** As stored. Saving ignores it — the repository stamps every save itself. */
    val updatedAt: String = "",
    val eventUid: String? = null,
    /** The event's `_ID` on this device — a shortcut, see Appointment.locate. */
    val calendarEventId: Long? = null,
    /** What this device last saw in the event. All null while it never saw it. */
    val seenStartsAt: String? = null,
    val seenEndsAt: String? = null,
    val seenLocation: String? = null,
)

/**
 * The calendar events appointments already hold, by UID and by this device's
 * event id. An event in here is not offered for linking again: two
 * appointments sharing one event would change and delete each other's.
 */
data class TakenEvents(val uids: Set<String>, val eventIds: Set<Long>)
```

- [ ] **Step 4: Add the repository methods**

In `Repository.kt`, below `deleteContact`, add:

```kotlin
    // ------------------------------------------------------------ Appointments

    /** A business's appointments, earliest first. */
    suspend fun appointments(placeId: String): List<AppointmentEntry> = withContext(Dispatchers.IO) {
        helper.readableDatabase
            .rawQuery("SELECT * FROM appointments WHERE place_id = ?", arrayOf(placeId))
            .use { c -> allAppointments(c) }
            // Sorted as instants, not as text: two offsets would sort wrong.
            .sortedBy { Clock.millis(it.startsAt) ?: Long.MAX_VALUE }
    }

    suspend fun appointment(id: String): AppointmentEntry? = withContext(Dispatchers.IO) {
        helper.readableDatabase
            .rawQuery("SELECT * FROM appointments WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) appointmentFromCursor(c) else null }
    }

    /**
     * Creates or updates an appointment. What travels is written from [entry],
     * stamped with a new `updated_at` and marked for upload. This device's
     * calendar columns are left as they are — [setCalendarLink] owns those.
     *
     * A null [AppointmentEntry.eventUid] never clears a stored UID. An entry
     * loaded before the read-back took a UID over ([setEventUid]) still carries
     * null; writing that would be the newer row and drop the link on every
     * device. Nothing in the app clears a UID on purpose.
     */
    suspend fun saveAppointment(entry: AppointmentEntry) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("place_id", entry.placeId)
            put("starts_at", entry.startsAt)
            put("ends_at", entry.endsAt)
            put("location", entry.location)
            put("note", entry.note)
            put("contact_id", entry.contactId)
            if (entry.eventUid != null) put("event_uid", entry.eventUid)
            put("updated_at", Clock.now())
            put("dirty", 1)
        }
        val db = helper.writableDatabase
        if (db.update("appointments", values, "id = ?", arrayOf(entry.id)) == 0) {
            db.insert("appointments", null, values)
        }
        notifyChanged()
    }

    /**
     * Records which event this device links an appointment to, and what it
     * saw in that event. A null [eventId] drops the link. Local only: no
     * `updated_at`, no mark — none of these columns travels.
     */
    suspend fun setCalendarLink(
        id: String,
        eventId: Long?,
        seenStartsAt: String?,
        seenEndsAt: String?,
        seenLocation: String?,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (eventId == null) putNull("calendar_event_id") else put("calendar_event_id", eventId)
            put("calendar_seen_starts_at", if (eventId == null) null else seenStartsAt)
            put("calendar_seen_ends_at", if (eventId == null) null else seenEndsAt)
            put("calendar_seen_location", if (eventId == null) null else seenLocation)
        }
        helper.writableDatabase.update("appointments", values, "id = ?", arrayOf(id))
        notifyChanged()
    }

    /**
     * Takes over the UID found on the linked event. A change that travels —
     * the other devices look the event up by it.
     */
    suspend fun setEventUid(id: String, uid: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("event_uid", uid)
            put("updated_at", Clock.now())
            put("dirty", 1)
        }
        helper.writableDatabase.update("appointments", values, "id = ?", arrayOf(id))
        notifyChanged()
    }

    /** Deletes an appointment and leaves a tombstone, the way [deleteContact] does. */
    suspend fun deleteAppointment(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            tombstone(db, "appointments", id, Clock.now())
            db.delete("appointments", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    /** Every event UID and local event id an appointment already holds. */
    suspend fun takenEvents(): TakenEvents = withContext(Dispatchers.IO) {
        val uids = HashSet<String>()
        val eventIds = HashSet<Long>()
        helper.readableDatabase.rawQuery("SELECT event_uid, calendar_event_id FROM appointments", null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0)) uids.add(c.getString(0))
                if (!c.isNull(1)) eventIds.add(c.getLong(1))
            }
        }
        TakenEvents(uids, eventIds)
    }
```

In the `// ---- Cursor` section, add:

```kotlin
    private fun allAppointments(c: Cursor): List<AppointmentEntry> {
        val list = ArrayList<AppointmentEntry>(c.count)
        while (c.moveToNext()) list.add(appointmentFromCursor(c))
        return list
    }

    private fun appointmentFromCursor(c: Cursor): AppointmentEntry = AppointmentEntry(
        id = c.text("id") ?: "",
        placeId = c.text("place_id") ?: "",
        startsAt = c.text("starts_at") ?: "",
        endsAt = c.text("ends_at"),
        location = c.text("location"),
        note = c.text("note"),
        contactId = c.text("contact_id"),
        updatedAt = c.text("updated_at") ?: "",
        eventUid = c.text("event_uid"),
        calendarEventId = c.long("calendar_event_id"),
        seenStartsAt = c.text("calendar_seen_starts_at"),
        seenEndsAt = c.text("calendar_seen_ends_at"),
        seenLocation = c.text("calendar_seen_location"),
    )
```

Update the tombstone KDoc in `Database.kt` (`TABLE_DELETIONS`): "Contacts, their numbers and appointments are the only rows the app deletes; …".

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Repository: Termine anlegen, ändern, löschen, Kalenderverknüpfung lokal"
```

---

### Task 5: The rules for status and read-back

**Files:**
- Modify: `…/calling/Appointment.kt`
- Test: `AppointmentTest.kt`

**Interfaces:**
- Consumes: `AppointmentEntry` (Task 4), `Status`.
- Produces:
  - `data class Slot(val startMillis: Long, val endMillis: Long?, val location: String?)`
  - `sealed interface Reconcile` with `InStep`, `TakeEvent(slot: Slot)`, `UpdateEvent`, `NotYetHere`, `DeletedInCalendar`, `Unlink`
  - `Appointment.isAhead(startsAt: String?, endsAt: String?, nowMillis: Long): Boolean`
  - `Appointment.statusAfterSave(startsAt: String, endsAt: String?, nowMillis: Long): Status?`
  - `Appointment.statusAfterRemoval(status: Status, remaining: List<AppointmentEntry>, nowMillis: Long): Status?`
  - `Appointment.rowSlot(entry: AppointmentEntry): Slot?`, `Appointment.seenSlot(entry: AppointmentEntry): Slot?`
  - `Appointment.reconcile(row: Slot, seen: Slot?, event: Slot?, nowMillis: Long): Reconcile`
  - `Appointment.seenIsCurrent(entry: AppointmentEntry, eventId: Long, event: Slot): Boolean`

The old `ReadBack` and `Appointment.readBack` stay until Task 10 — the view model still calls them.

- [ ] **Step 1: Write the failing tests**

In `AppointmentTest.kt`, add imports:

```kotlin
import io.github.amadeusb.callsheet.calling.Reconcile
import io.github.amadeusb.callsheet.calling.Slot
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertFalse
```

Append inside the class:

```kotlin
    // --- ahead, and the status -----------------------------------------------

    private fun instant(iso: String): Long = Clock.millis(iso)!!

    private val noon = instant("2026-09-10T12:00:00+02:00")

    private fun entry(id: String, startsAt: String, endsAt: String? = null) = AppointmentEntry(
        id = id, placeId = "P1", startsAt = startsAt, endsAt = endsAt,
        location = null, note = null, contactId = null,
    )

    @Test
    fun `an appointment is ahead until its end has passed`() {
        assertTrue(Appointment.isAhead("2026-09-10T11:00:00+02:00", "2026-09-10T13:00:00+02:00", noon))
        assertFalse(Appointment.isAhead("2026-09-10T10:00:00+02:00", "2026-09-10T11:00:00+02:00", noon))
    }

    @Test
    fun `without an end, the start decides`() {
        assertTrue(Appointment.isAhead("2026-09-10T13:00:00+02:00", null, noon))
        assertFalse(Appointment.isAhead("2026-09-10T11:00:00+02:00", null, noon))
    }

    @Test
    fun `saving an appointment still ahead sets the status`() {
        assertEquals(Status.APPOINTMENT, Appointment.statusAfterSave("2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00", noon))
    }

    @Test
    fun `entering a past appointment after the fact leaves the status alone`() {
        assertNull(Appointment.statusAfterSave("2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00", noon))
    }

    @Test
    fun `removing the last appointment ahead puts the status back to called`() {
        val pastOnly = listOf(entry("A-1", "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00"))

        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, pastOnly, noon))
        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, emptyList(), noon))
    }

    @Test
    fun `removing one while another is still ahead keeps the status`() {
        val another = listOf(entry("A-2", "2026-09-12T09:00:00+02:00", "2026-09-12T10:00:00+02:00"))

        assertNull(Appointment.statusAfterRemoval(Status.APPOINTMENT, another, noon))
    }

    @Test
    fun `removing never touches a status other than appointment`() {
        assertNull(Appointment.statusAfterRemoval(Status.DECLINED, emptyList(), noon))
        assertNull(Appointment.statusAfterRemoval(Status.DO_NOT_CALL, emptyList(), noon))
    }

    // --- reconcile: the read-back table ---------------------------------------

    private fun slot(start: String, end: String? = null, location: String? = null) =
        Slot(instant(start), end?.let { instant(it) }, location)

    private val planned = slot("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Zehentstraße 39")
    private val later = slot("2026-09-10T16:00:00+02:00", "2026-09-10T17:00:00+02:00", "Zehentstraße 39")
    private val office = slot("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Im Büro")
    private val dayBefore = instant("2026-09-09T12:00:00+02:00")
    private val dayAfter = instant("2026-09-11T12:00:00+02:00")

    @Test
    fun `first sight of an event that matches the row changes nothing`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `first sight of an event that differs lets the row win`() {
        // This device's calendar may simply not have caught up.
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `nothing moved, nothing to do`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = planned, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `moved in the calendar, the event wins`() {
        assertEquals(Reconcile.TakeEvent(later), Appointment.reconcile(row = planned, seen = planned, event = later, nowMillis = dayBefore))
    }

    @Test
    fun `relocated in the calendar, the event wins`() {
        assertEquals(Reconcile.TakeEvent(office), Appointment.reconcile(row = planned, seen = planned, event = office, nowMillis = dayBefore))
    }

    @Test
    fun `changed on another device, the row wins`() {
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = planned, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `changed on both sides to the same, nothing to do`() {
        assertEquals(Reconcile.InStep, Appointment.reconcile(row = later, seen = planned, event = later, nowMillis = dayBefore))
    }

    @Test
    fun `changed on both sides differently, the row wins`() {
        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = later, seen = planned, event = office, nowMillis = dayBefore))
    }

    @Test
    fun `seconds and surrounding spaces are no difference`() {
        val event = Slot(planned.startMillis + 30_000L, planned.endMillis!! + 30_000L, " Zehentstraße 39 ")

        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = planned, event = event, nowMillis = dayBefore))
    }

    @Test
    fun `a row without an end is not a difference from the event's end`() {
        val row = slot("2026-09-10T14:00:00+02:00", null, "Zehentstraße 39")

        assertEquals(Reconcile.InStep, Appointment.reconcile(row = row, seen = null, event = planned, nowMillis = dayBefore))
    }

    @Test
    fun `an event never seen on this device and not found means nothing yet`() {
        assertEquals(Reconcile.NotYetHere, Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayBefore))
        assertEquals(Reconcile.NotYetHere, Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayAfter))
    }

    @Test
    fun `an event seen before and gone while the appointment is ahead was deleted`() {
        assertEquals(Reconcile.DeletedInCalendar, Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore))
    }

    @Test
    fun `an event seen before and gone after the appointment only loses the link`() {
        // Calendars clear out old events on their own; the record stays.
        assertEquals(Reconcile.Unlink, Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayAfter))
    }

    @Test
    fun `the row slot and the seen slot are read from the entry`() {
        val linked = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00")
            .copy(location = "Zehentstraße 39", seenStartsAt = "2026-09-10T16:00:00+02:00", seenEndsAt = "2026-09-10T17:00:00+02:00", seenLocation = "Zehentstraße 39")

        assertEquals(planned, Appointment.rowSlot(linked))
        assertEquals(later, Appointment.seenSlot(linked))
        assertNull(Appointment.seenSlot(linked.copy(seenStartsAt = null)))
    }

    @Test
    fun `a record of the event is current only with the same link and a matching slot`() {
        // In step, the read-back writes nothing unless this is false — a link
        // rewritten on every opening would notify every observer for nothing.
        val recorded = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00").copy(
            calendarEventId = 4711L,
            seenStartsAt = "2026-09-10T14:00:00+02:00",
            seenEndsAt = "2026-09-10T15:00:00+02:00",
            seenLocation = "Zehentstraße 39",
        )

        assertTrue(Appointment.seenIsCurrent(recorded, 4711L, planned))
        assertFalse(Appointment.seenIsCurrent(recorded, 815L, planned))
        assertFalse(Appointment.seenIsCurrent(recorded, 4711L, later))
        assertFalse(Appointment.seenIsCurrent(recorded.copy(seenStartsAt = null), 4711L, planned))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL — `Unresolved reference: Reconcile`

- [ ] **Step 3: Implement**

In `Appointment.kt`, add imports `io.github.amadeusb.callsheet.data.AppointmentEntry` and `io.github.amadeusb.callsheet.data.Status`. Below `ReadBack`, add:

```kotlin
/**
 * An appointment's time and place as the read-back compares them — for the
 * row, for the event, and for what this device last saw in the event.
 */
data class Slot(val startMillis: Long, val endMillis: Long?, val location: String?)

/**
 * What reading an appointment's event back calls for.
 *
 * The calendar is shared: every device carries the same CalDAV calendar and
 * DAVx5 brings it level at its own pace. So the calendar a device reads can be
 * behind the rows it holds, or ahead of them, and "the calendar wins" — the
 * rule for a single device — would undo changes made on another. The row wins
 * except where only the calendar moved.
 */
sealed interface Reconcile {
    /** Row and event agree. Only what this device saw is recorded. */
    data object InStep : Reconcile

    /** Moved or relocated in the calendar, and nowhere else: the row takes [slot]. */
    data class TakeEvent(val slot: Slot) : Reconcile

    /** Changed on another device, or this calendar is behind: the event is updated from the row. */
    data object UpdateEvent : Reconcile

    /** Not found, and never seen on this device: it may not have arrived yet. */
    data object NotYetHere : Reconcile

    /** Seen before, gone now, appointment still ahead: deleted in the calendar. */
    data object DeletedInCalendar : Reconcile

    /**
     * Seen before, gone now, appointment already past. Calendars clear out old
     * events on their own; that must not erase the record. Only the link goes.
     */
    data object Unlink : Reconcile
}
```

Inside `object Appointment`, add:

```kotlin
    /**
     * Whether an appointment is still ahead: its end — or its start, where it
     * has none — lies in the future. The one definition behind the status, the
     * read-back and the split in the detail view.
     */
    fun isAhead(startsAt: String?, endsAt: String?, nowMillis: Long): Boolean {
        val last = Clock.millis(endsAt) ?: Clock.millis(startsAt) ?: return false
        return last > nowMillis
    }

    /**
     * The status to set after saving, or null to leave it. An appointment still
     * ahead means one was agreed; a past one entered after the fact says
     * nothing about where the business stands now.
     */
    fun statusAfterSave(startsAt: String, endsAt: String?, nowMillis: Long): Status? =
        if (isAhead(startsAt, endsAt, nowMillis)) Status.APPOINTMENT else null

    /**
     * The status after an appointment went away — removed, or deleted in the
     * calendar — or null to leave it. [remaining] are the business's
     * appointments without that one.
     *
     * Only the status an appointment set is taken back, and only once none is
     * left ahead. `declined` and `do_not_call` are decisions made on the phone;
     * a removed appointment is not permission to undo them.
     */
    fun statusAfterRemoval(status: Status, remaining: List<AppointmentEntry>, nowMillis: Long): Status? {
        if (status != Status.APPOINTMENT) return null
        return if (remaining.none { isAhead(it.startsAt, it.endsAt, nowMillis) }) Status.CALLED else null
    }

    /** The row's slot. Null when its start cannot be read. */
    fun rowSlot(entry: AppointmentEntry): Slot? {
        val start = Clock.millis(entry.startsAt) ?: return null
        return Slot(start, Clock.millis(entry.endsAt), entry.location)
    }

    /** What this device last saw in the event. Null while it never saw it. */
    fun seenSlot(entry: AppointmentEntry): Slot? {
        val start = Clock.millis(entry.seenStartsAt) ?: return null
        return Slot(start, Clock.millis(entry.seenEndsAt), entry.seenLocation)
    }

    /**
     * Compares the row (R), the event (E) and what this device last saw in the
     * event (S). A null [event] means it was not found on this device.
     *
     * | S     | E = S | R = S | result                          |
     * |-------|-------|-------|---------------------------------|
     * | none  |       |       | E = R: in step, else R wins     |
     * | set   | yes   | yes   | in step                         |
     * | set   | no    | yes   | E wins                          |
     * | set   | yes   | no    | R wins                          |
     * | set   | no    | no    | E = R: in step, else R wins     |
     *
     * Where both changed, the row wins: it is what every device shows, and the
     * calendar gives no modification time to compare.
     */
    fun reconcile(row: Slot, seen: Slot?, event: Slot?, nowMillis: Long): Reconcile {
        if (event == null) {
            if (seen == null) return Reconcile.NotYetHere
            val last = row.endMillis ?: row.startMillis
            return if (last > nowMillis) Reconcile.DeletedInCalendar else Reconcile.Unlink
        }
        if (sameSlot(event, row)) return Reconcile.InStep
        if (seen == null) return Reconcile.UpdateEvent
        val onlyTheCalendarMoved = !sameSlot(event, seen) && sameSlot(row, seen)
        return if (onlyTheCalendarMoved) Reconcile.TakeEvent(event) else Reconcile.UpdateEvent
    }

    /**
     * Whether this device's record of the event is already current: the same
     * `_ID`, and a seen slot that matches what the event holds. The read-back
     * records the event only when it is not — rewriting an unchanged link on
     * every opening would notify every observer of the database for nothing.
     */
    fun seenIsCurrent(entry: AppointmentEntry, eventId: Long, event: Slot): Boolean {
        val seen = seenSlot(entry) ?: return false
        return entry.calendarEventId == eventId && sameSlot(seen, event)
    }

    /**
     * Within a minute, locations after trimming. A missing end on either side is
     * no difference: an event always has one, a row may not.
     */
    private fun sameSlot(a: Slot, b: Slot): Boolean {
        val sameEnd = a.endMillis == null || b.endMillis == null || near(a.endMillis, b.endMillis)
        return near(a.startMillis, b.startMillis) && sameEnd &&
            a.location?.trim().orEmpty() == b.location?.trim().orEmpty()
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, old and new tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Regeln für Status und Rücklesen: Zeile, Eintrag und zuletzt Gesehenes"
```

---

### Task 6: The rules for linking and saving

**Files:**
- Modify: `…/calling/Appointment.kt`
- Test: `AppointmentTest.kt`

**Interfaces:**
- Consumes: `AppointmentEntry`, `TakenEvents`, `Contact` (Task 4), `Slot` (Task 5).
- Produces:
  - `BusyInterval` gains `val uid: String? = null` and `val recurring: Boolean = false`
  - `data class Located<E>(val eventId: Long, val event: E)`
  - `Appointment.plan(…, eventUid: String? = null): SavePlan` — a UID without a local event is `LocalOnly`
  - `Appointment.busyExcept(busy: List<BusyInterval>, ownUid: String?, ownEventId: Long?): List<BusyInterval>`
  - `Appointment.mayAdopt(interval: BusyInterval, taken: TakenEvents): Boolean` — never a recurring event
  - `Appointment.adoptable(busy: List<BusyInterval>, taken: TakenEvents, ownUid: String?, ownEventId: Long?): Set<Long>` — empty for an appointment that already has an event
  - `Appointment.uidToTake(rowUid: String?, eventUid: String?): String?`
  - `suspend fun <E> Appointment.locate(calendarEventId: Long?, eventUid: String?, read: suspend (Long) -> E?, find: suspend (String) -> Long?): Located<E>?` — an exception from `read` or `find` propagates; it is never "not found"
  - `Appointment.eventTitle(businessName: String, note: String?): String`
  - `Appointment.eventDescription(contact: Contact?, businessPhone: String?): String?`
  - `Appointment.rowLabel(entry: AppointmentEntry): String`
  - `Appointment.split(appointments: List<AppointmentEntry>, nowMillis: Long): Pair<List<AppointmentEntry>, List<AppointmentEntry>>` — ahead earliest first, past latest first

- [ ] **Step 1: Write the failing tests**

In `AppointmentTest.kt`, change the `busy` helper to carry a UID:

```kotlin
    private fun busy(
        fromHour: Int,
        toHour: Int,
        title: String,
        eventId: Long? = null,
        uid: String? = null,
        recurring: Boolean = false,
    ) = BusyInterval(
        startMillis = millis(2026, 9, 10, fromHour),
        endMillis = millis(2026, 9, 10, toHour),
        title = title,
        eventId = eventId,
        uid = uid,
        recurring = recurring,
    )
```

Add imports:

```kotlin
import io.github.amadeusb.callsheet.calling.Located
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.PhoneNumber
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.TakenEvents
import kotlinx.coroutines.test.runTest
```

Append inside the class:

```kotlin
    // --- saving with a shared calendar ----------------------------------------

    @Test
    fun `an appointment whose event is elsewhere is saved without touching the calendar`() {
        // Creating one would put a second event into the shared calendar the
        // moment DAVx5 catches up on this device.
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
            eventUid = "A-1",
        )

        assertEquals(SavePlan.LocalOnly, plan)
    }

    @Test
    fun `an appointment whose event is here is updated`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = 42L, linkExisting = null, force = false, calendarEnabled = true,
            eventUid = "A-1",
        )

        assertEquals(SavePlan.Update(42L), plan)
    }

    // --- busy times and adopting ----------------------------------------------

    @Test
    fun `busy times leave out the edited appointment's event by UID, and nothing else`() {
        val own = busy(14, 15, "Ortstermin Elektro Meier", eventId = 1L, uid = "A-1")
        val sibling = busy(16, 17, "Ortstermin Elektro Meier – Angebot", eventId = 2L, uid = "A-2")
        val dentist = busy(9, 10, "Zahnarzt", eventId = 3L)

        val left = Appointment.busyExcept(listOf(own, sibling, dentist), ownUid = "A-1", ownEventId = null)

        assertEquals(listOf(sibling, dentist), left)
    }

    @Test
    fun `without a UID the local event id recognises the own event`() {
        val own = busy(14, 15, "Ortstermin", eventId = 1L)
        val other = busy(16, 17, "Steuerbüro", eventId = 2L)

        assertEquals(listOf(other), Appointment.busyExcept(listOf(own, other), ownUid = null, ownEventId = 1L))
    }

    @Test
    fun `an event another appointment holds by UID is not offered for linking`() {
        val taken = TakenEvents(uids = setOf("A-2"), eventIds = emptySet())

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Ortstermin", eventId = 2L, uid = "A-2"), taken))
    }

    @Test
    fun `an event another appointment holds on this device is not offered for linking`() {
        val taken = TakenEvents(uids = emptySet(), eventIds = setOf(2L))

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Ortstermin", eventId = 2L), taken))
    }

    @Test
    fun `a free event can be linked, a block without an event cannot`() {
        val taken = TakenEvents(uids = setOf("A-2"), eventIds = setOf(2L))

        assertTrue(Appointment.mayAdopt(busy(14, 15, "Steuerbüro", eventId = 7L, uid = "x@infomaniak"), taken))
        assertFalse(Appointment.mayAdopt(busy(14, 15, "Steuerbüro"), taken))
    }

    @Test
    fun `a recurring event is never offered for linking`() {
        // Events.DTSTART is the start of the series, not of the occurrence in
        // the strip: linking a weekly meeting would move the appointment to its
        // first occurrence, months back.
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())

        assertFalse(Appointment.mayAdopt(busy(14, 15, "Jour fixe", eventId = 7L, uid = "jf@infomaniak", recurring = true), none))
    }

    @Test
    fun `a new appointment may link any free event`() {
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())
        val free = busy(14, 15, "Steuerbüro", eventId = 7L)
        val series = busy(16, 17, "Jour fixe", eventId = 8L, recurring = true)

        assertEquals(setOf(7L), Appointment.adoptable(listOf(free, series), none, ownUid = null, ownEventId = null))
    }

    @Test
    fun `an appointment that already has an event is offered no other to link`() {
        // Its old event would stay in the calendar, and a device whose shortcut
        // still points there would keep writing into it and take its UID back.
        val none = TakenEvents(uids = emptySet(), eventIds = emptySet())
        val free = listOf(busy(14, 15, "Steuerbüro", eventId = 7L))

        assertTrue(Appointment.adoptable(free, none, ownUid = "A-1", ownEventId = null).isEmpty())
        assertTrue(Appointment.adoptable(free, none, ownUid = null, ownEventId = 4711L).isEmpty())
    }

    // --- the UID ---------------------------------------------------------------

    @Test
    fun `a different UID behind the shortcut is taken over`() {
        assertEquals("abc@infomaniak", Appointment.uidToTake(rowUid = null, eventUid = "abc@infomaniak"))
        assertEquals("abc@infomaniak", Appointment.uidToTake(rowUid = "A-1", eventUid = "abc@infomaniak"))
    }

    @Test
    fun `the same UID is nothing to take over`() {
        assertNull(Appointment.uidToTake(rowUid = "A-1", eventUid = "A-1"))
    }

    @Test
    fun `an empty UID after inserting keeps the link local`() {
        assertNull(Appointment.uidToTake(rowUid = null, eventUid = ""))
        assertNull(Appointment.uidToTake(rowUid = null, eventUid = null))
    }

    @Test
    fun `the shortcut is used while its event exists`() = runTest {
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { if (it == 4711L) "event 4711" else null },
            find = { throw AssertionError("no lookup while the shortcut works") },
        )

        assertEquals(Located(4711L, "event 4711"), found)
    }

    @Test
    fun `a shortcut whose event carries another UID is still that event, not taken for deleted`() = runTest {
        // The read does not compare UIDs: an _ID is not handed to another event.
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { "event with UID abc@infomaniak" },
            find = { null },
        )

        assertEquals(4711L, found?.eventId)
    }

    @Test
    fun `a gone shortcut falls back to the UID`() = runTest {
        // DAVx5 deleted and rewrote the event with a new _ID.
        val found = Appointment.locate(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { if (it == 815L) "event 815" else null },
            find = { if (it == "A-1") 815L else null },
        )

        assertEquals(Located(815L, "event 815"), found)
    }

    @Test(expected = IllegalStateException::class)
    fun `a lookup that fails is not an event that is gone`() = runTest {
        // Were this null, reconcile would take a provider hiccup for a deletion
        // and delete an appointment still ahead on every device. The decision
        // to skip the appointment instead is the caller's (CallsheetViewModel
        // .calendarLookup); what is pure here is that the failure reaches it.
        Appointment.locate<String>(
            calendarEventId = 4711L, eventUid = "A-1",
            read = { throw IllegalStateException("provider failed") },
            find = { null },
        )
    }

    @Test
    fun `without a shortcut or a UID that finds something, nothing is found`() = runTest {
        assertNull(Appointment.locate<String>(null, null, read = { "x" }, find = { 1L }))
        assertNull(Appointment.locate<String>(null, "A-1", read = { "x" }, find = { null }))
    }

    // --- what the calendar and the lists show -----------------------------------

    @Test
    fun `the event title carries the note when there is one`() {
        assertEquals("Ortstermin Elektro Meier – Angebot", Appointment.eventTitle("Elektro Meier", "Angebot"))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle("Elektro Meier", " "))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle("Elektro Meier", null))
    }

    @Test
    fun `the description names the contact person and their first number`() {
        val contact = Contact(
            id = "K-1", placeId = "P1", name = "Frau Meier", role = null, email = null, note = null,
            numbers = listOf(
                PhoneNumber("N-1", "+4917612345", PhoneType.MOBILE),
                PhoneNumber("N-2", "+49841999", PhoneType.WORK),
            ),
            updatedAt = "2026-09-07T10:00:00+02:00",
        )

        assertEquals("Frau Meier · +4917612345", Appointment.eventDescription(contact, "+4984112345"))
        assertEquals("Frau Meier", Appointment.eventDescription(contact.copy(numbers = emptyList()), "+4984112345"))
    }

    @Test
    fun `without a contact person the description is the business's number`() {
        assertEquals("+4984112345", Appointment.eventDescription(null, "+4984112345"))
    }

    @Test
    fun `a row in Heute reads time range and note`() {
        val withNote = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00").copy(note = "Besichtigung")

        assertEquals("${Appointment.readableRange(withNote.startsAt, withNote.endsAt)} · Besichtigung", Appointment.rowLabel(withNote))
        assertEquals(Appointment.readableRange(withNote.startsAt, withNote.endsAt), Appointment.rowLabel(withNote.copy(note = null)))
    }

    @Test
    fun `the detail view shows ahead earliest first and past latest first`() {
        val all = listOf(
            entry("past-early", "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00"),
            entry("ahead-late", "2026-09-20T09:00:00+02:00", "2026-09-20T10:00:00+02:00"),
            entry("past-late", "2026-09-05T09:00:00+02:00", "2026-09-05T10:00:00+02:00"),
            entry("ahead-early", "2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00"),
        )

        val (ahead, past) = Appointment.split(all, noon)

        assertEquals(listOf("ahead-early", "ahead-late"), ahead.map { it.id })
        assertEquals(listOf("past-late", "past-early"), past.map { it.id })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL — `No parameter with name 'uid'`, `Unresolved reference: busyExcept`, …

- [ ] **Step 3: Implement**

In `Appointment.kt`, add imports `io.github.amadeusb.callsheet.data.Contact` and `io.github.amadeusb.callsheet.data.TakenEvents`.

Add to `BusyInterval`, after `eventId`:

```kotlin
    /**
     * The event's iCalendar UID. The same on every device carrying the shared
     * calendar — which is how an appointment recognises its own event here,
     * and how an event another appointment holds is kept from being linked twice.
     */
    val uid: String? = null,
    /**
     * A recurring event, or a changed occurrence of one. Its `Events.DTSTART`
     * is not the time shown in the strip, so it is never offered for linking.
     */
    val recurring: Boolean = false,
```

Below `Slot`, add:

```kotlin
/** An event found in this device's calendar, and its `_ID` here. */
data class Located<E>(val eventId: Long, val event: E)
```

Replace `plan` with:

```kotlin
    /**
     * What saving should do. The order matters: an explicit instruction from the
     * user beats a conflict, and a conflict beats everything else.
     *
     * [ownEventId] is the appointment's event as found on this device. An
     * [eventUid] without one means the event exists in the shared calendar but
     * has not reached this device's copy: creating another would put a second
     * event into the calendar the moment DAVx5 catches up. The device that
     * holds the event writes the change into it after its next sync.
     */
    fun plan(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
        ownEventId: Long?,
        linkExisting: Long?,
        force: Boolean,
        calendarEnabled: Boolean,
        eventUid: String? = null,
    ): SavePlan {
        if (linkExisting != null) return SavePlan.Adopt(linkExisting)
        if (!force) {
            val clash = overlapping(startMillis, endMillis, busy)
            if (clash.isNotEmpty()) return SavePlan.Conflict(clash)
        }
        if (!calendarEnabled) return SavePlan.LocalOnly
        if (ownEventId != null) return SavePlan.Update(ownEventId)
        return if (eventUid != null) SavePlan.LocalOnly else SavePlan.Create
    }
```

Add to `object Appointment`:

```kotlin
    /**
     * The busy times without the event of the appointment being edited —
     * recognised by its UID, or by this device's event id where it has none
     * yet. The business's other appointments stay: two at the same time are a
     * real conflict.
     */
    fun busyExcept(busy: List<BusyInterval>, ownUid: String?, ownEventId: Long?): List<BusyInterval> =
        busy.filterNot { interval ->
            (ownUid != null && interval.uid == ownUid) || (ownEventId != null && interval.eventId == ownEventId)
        }

    /**
     * Whether a busy interval may be linked to an appointment. Not when another
     * appointment already holds the event — changing or removing one would take
     * the other along. The UID travels, so this holds across devices.
     */
    fun mayAdopt(interval: BusyInterval, taken: TakenEvents): Boolean {
        val eventId = interval.eventId ?: return false
        // Events.DTSTART of a series is its first occurrence, not this one.
        if (interval.recurring) return false
        if (interval.uid != null && interval.uid in taken.uids) return false
        return eventId !in taken.eventIds
    }

    /**
     * The events the sheet may offer for „Verknüpfen" — none for an appointment
     * that already has an event ([ownUid] or [ownEventId]). Linking it to
     * another would leave the old event in the calendar, and a device whose
     * shortcut still points there would keep writing into it and take its UID
     * back: two events for one appointment, and a UID going back and forth.
     */
    fun adoptable(busy: List<BusyInterval>, taken: TakenEvents, ownUid: String?, ownEventId: Long?): Set<Long> {
        if (ownUid != null || ownEventId != null) return emptySet()
        return busy.filter { mayAdopt(it, taken) }.mapNotNull { it.eventId }.toSet()
    }

    /**
     * The UID to take over from an event, or null when there is nothing to take.
     *
     * An event the app created should carry the appointment's id; one that came
     * back without a UID leaves the link local until DAVx5 has written one. An
     * event behind the shortcut with a different UID is the same event — the
     * row takes its UID so the other devices look for that one.
     */
    fun uidToTake(rowUid: String?, eventUid: String?): String? =
        eventUid?.takeIf { it.isNotBlank() && it != rowUid }

    /**
     * Finds an appointment's event on this device: through the shortcut while
     * the event behind it still exists, otherwise by UID among the calendars
     * the app reads. Neither found means the event is not on this device.
     *
     * The shortcut is trusted without comparing UIDs — an `_ID` is not handed to
     * another event, and taking a changed UID for a deleted event would delete
     * the appointment.
     *
     * Nothing is caught here. A [read] or [find] that throws — a refused
     * permission, a provider that fails — propagates: null means "not on this
     * device", and a failure passed off as that would read as a deletion.
     */
    suspend fun <E> locate(
        calendarEventId: Long?,
        eventUid: String?,
        read: suspend (Long) -> E?,
        find: suspend (String) -> Long?,
    ): Located<E>? {
        if (calendarEventId != null) {
            read(calendarEventId)?.let { return Located(calendarEventId, it) }
        }
        val uid = eventUid ?: return null
        val eventId = find(uid) ?: return null
        return read(eventId)?.let { Located(eventId, it) }
    }

    /** „Ortstermin Elektro Meier – Angebot", or without a note „Ortstermin Elektro Meier". */
    fun eventTitle(businessName: String, note: String?): String {
        val head = "Ortstermin $businessName".trim()
        val tail = note?.trim()?.ifEmpty { null } ?: return head
        return "$head – $tail"
    }

    /** Who to ask for on site: the contact person and their first number, else the business's number. */
    fun eventDescription(contact: Contact?, businessPhone: String?): String? {
        if (contact == null) return businessPhone
        return listOfNotNull(contact.name, contact.numbers.firstOrNull()?.number).joinToString(" · ")
    }

    /** For a row in „Termine heute": "Do, 10.09. · 14:00 – 15:00 · Besichtigung". */
    fun rowLabel(entry: AppointmentEntry): String {
        val range = readableRange(entry.startsAt, entry.endsAt)
        val note = entry.note?.trim()?.ifEmpty { null } ?: return range
        return "$range · $note"
    }

    /** Ahead earliest first, past latest first — the order the detail view lists them in. */
    fun split(
        appointments: List<AppointmentEntry>,
        nowMillis: Long,
    ): Pair<List<AppointmentEntry>, List<AppointmentEntry>> {
        val (ahead, past) = appointments.partition { isAhead(it.startsAt, it.endsAt, nowMillis) }
        val start = { entry: AppointmentEntry -> Clock.millis(entry.startsAt) ?: 0L }
        return ahead.sortedBy(start) to past.sortedByDescending(start)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Regeln für Verknüpfen und Speichern: UID, Abkürzung, belegte Einträge, LocalOnly"
```

---

### Task 7: The UID in the calendar provider

**Files:**
- Modify: `…/calendar/CalendarStore.kt`, `…/calendar/BusyTimes.kt`, `…/calling/Appointment.kt`, `…/CallsheetViewModel.kt` (two existing `read` calls)
- Test: `AppointmentTest.kt`

**Interfaces:**
- Consumes: `BusyInterval.uid` (Task 6).
- Produces:
  - `EventFields` gains `val uid: String? = null`
  - `CalendarStore.insert(context, calendarId, fields)` writes `UID_2445` when `fields.uid` is set
  - `CalendarStore.read(context, eventId): EventFields?` fills `uid`; null only when the event is gone; **throws** on a refused permission or a failing provider
  - `CalendarStore.findByUid(context: Context, uid: String): Long?` — null when not on this device; **throws** like `read`
  - `Appointment.eventEnd(startMillis: Long, dtEnd: Long?, duration: String?): Long`
  - `BusyTimes.forDay` fills `BusyInterval.uid` and `BusyInterval.recurring`

Robolectric ships no calendar provider (see Global Constraints), so only `eventEnd` gets unit tests. The phone check in Task 13 covers the rest.

`read` and `findByUid` stop failing soft, and that is the point of this task as much as the UID: their null is what the read-back acts on — up to deleting an appointment on every device — so a refused permission or a provider hiccup must not come back as that null. Every caller decides what a failure means (Task 8 adds `calendarLookup` for that).

- [ ] **Step 1: Write the failing tests for an event's end**

A recurring event carries `DURATION` and an empty `DTEND`, which the provider hands back as 0. Read at face value, its end is in 1970 — and adopting it, or taking it over on read-back, would move the appointment there.

In `AppointmentTest.kt`, append:

```kotlin
    // --- an event's end ---------------------------------------------------------

    @Test
    fun `an event's end is its DTEND`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start + 3_600_000L, Appointment.eventEnd(start, start + 3_600_000L, null))
    }

    @Test
    fun `an event without DTEND ends after its DURATION, never in 1970`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start + 5_400_000L, Appointment.eventEnd(start, 0L, "PT1H30M"))
        assertEquals(start + 3_600_000L, Appointment.eventEnd(start, null, "P3600S"))
        assertEquals(start + 86_400_000L, Appointment.eventEnd(start, 0L, "P1D"))
    }

    @Test
    fun `an event with neither a DTEND nor a readable DURATION ends at its start`() {
        val start = instant("2026-09-10T14:00:00+02:00")

        assertEquals(start, Appointment.eventEnd(start, 0L, null))
        assertEquals(start, Appointment.eventEnd(start, null, "irgendwas"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL — `Unresolved reference: eventEnd`

- [ ] **Step 3: `Appointment.eventEnd`**

Inside `object Appointment`:

```kotlin
    /**
     * An event's end in milliseconds. Most events carry `DTEND`; a recurring
     * one carries `DURATION` and an empty `DTEND`, which the provider hands back
     * as 0. Taken at face value that end is in 1970, and a read-back or a link
     * would move the appointment there.
     *
     * [duration] is RFC 5545 (`PT1H30M`, `P1D`) or the provider's own `P3600S`.
     * Neither usable: the end is the start.
     */
    fun eventEnd(startMillis: Long, dtEnd: Long?, duration: String?): Long {
        if (dtEnd != null && dtEnd > startMillis) return dtEnd
        val match = DURATION_PATTERN.matchEntire(duration?.trim().orEmpty()) ?: return startMillis
        val (weeks, days, hours, minutes, seconds) = match.destructured
        val totalSeconds = (weeks.toLongOrNull() ?: 0L) * 7 * 86_400 +
            (days.toLongOrNull() ?: 0L) * 86_400 +
            (hours.toLongOrNull() ?: 0L) * 3_600 +
            (minutes.toLongOrNull() ?: 0L) * 60 +
            (seconds.toLongOrNull() ?: 0L)
        return startMillis + totalSeconds * 1_000L
    }

    private val DURATION_PATTERN = Regex("""\+?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS

- [ ] **Step 5: `EventFields`**

```kotlin
/** An appointment as the app writes it into the calendar. */
data class EventFields(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
    /**
     * The iCalendar UID. Set on insert to the appointment's id; read back from
     * `UID_2445`. DAVx5 uploads an event under the UID already set and writes
     * one back only where it is missing, so this is the same on every device.
     */
    val uid: String? = null,
)
```

- [ ] **Step 6: `insert` sets the UID, `update` never does**

In `insert`, inside `values(fields).apply { … }`, add:

```kotlin
                    fields.uid?.let { put(CalendarContract.Events.UID_2445, it) }
```

`values(fields)` stays without the UID, so `update` never rewrites an event's identity.

- [ ] **Step 7: `read` returns the UID and a real end, and no longer fails soft**

Add `import io.github.amadeusb.callsheet.calling.Appointment`. Replace `read`:

```kotlin
    /**
     * Reads an event back. Null only when it is gone — deleted in the calendar,
     * or removed by a synchronisation. That null is what the read-back acts on,
     * up to deleting an appointment on every device.
     *
     * So, unlike the rest of this object, `read` does not fail soft: a refused
     * permission (the provider throws `SecurityException`) or a failing provider
     * propagates, and the caller decides. An error passed off as "gone" would be
     * a deletion nobody made.
     */
    suspend fun read(context: Context, eventId: Long): EventFields? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.UID_2445,
                CalendarContract.Events.DURATION,
            ),
            null, null, null,
        ) ?: error("The calendar provider returned no cursor.")
        cursor.use { c ->
            if (!c.moveToFirst() || c.getInt(5) == 1) return@use null
            val start = c.getLong(1)
            EventFields(
                title = c.getString(0) ?: "",
                startMillis = start,
                endMillis = Appointment.eventEnd(start, if (c.isNull(2)) null else c.getLong(2), c.getString(7)),
                location = c.getString(3)?.ifBlank { null },
                description = c.getString(4)?.ifBlank { null },
                uid = c.getString(6)?.ifBlank { null },
            )
        }
    }
```

In the object's KDoc, replace "Every call fails soft." with "Every call fails soft — except [read] and [findByUid], whose null means an event is not there and so must not also mean an error."

- [ ] **Step 8: `findByUid`**

Below `read`:

```kotlin
    /**
     * The `_ID` of the event carrying [uid] on this device, or null when there is
     * none — the event may simply not have arrived through DAVx5 yet, which is
     * not the same as deleted. Like [read], it does not fail soft.
     *
     * Visibility is no condition: hiding a calendar in the calendar app is a
     * display setting, and the event in it is still the appointment's. An
     * exception of a recurring event shares the series' UID; only the series
     * itself (`ORIGINAL_ID IS NULL`) is the event.
     */
    suspend fun findByUid(context: Context, uid: String): Long? = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.UID_2445} = ? AND ${CalendarContract.Events.DELETED} = 0 " +
                "AND ${CalendarContract.Events.ORIGINAL_ID} IS NULL",
            arrayOf(uid),
            null,
        ) ?: error("The calendar provider returned no cursor.")
        cursor.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }
```

- [ ] **Step 9: The two existing callers of `read` keep failing soft**

`CallsheetViewModel.kt` still calls `read` twice until Tasks 8 and 10 replace the code around it. An exception escaping a `viewModelScope.launch` would crash the app, so:

In `saveAppointment`, `is SavePlan.Adopt`:

```kotlin
                    val event = runCatching { CalendarStore.read(getApplication(), plan.eventId) }.getOrNull()
```

In `syncAppointment`, replace `val event = CalendarStore.read(getApplication(), eventId)` with:

```kotlin
            // A calendar that could not be read is not a deleted event: skip.
            val event = try {
                CalendarStore.read(getApplication(), eventId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                return@launch
            }
```

- [ ] **Step 10: `BusyTimes` reads the UID and whether an event recurs**

Add `CalendarContract.Instances.UID_2445`, `CalendarContract.Instances.RRULE`, `CalendarContract.Instances.RDATE` and `CalendarContract.Instances.ORIGINAL_ID` as the sixth to ninth columns of the projection, and in the `BusyInterval(…)` constructor:

```kotlin
                                uid = c.getString(5)?.ifBlank { null },
                                // A series, or a changed occurrence of one: never
                                // offered for linking (see Appointment.mayAdopt).
                                recurring = !c.getString(6).isNullOrBlank() ||
                                    !c.getString(7).isNullOrBlank() || !c.isNull(8),
```

`BusyTimes` keeps failing soft: if the provider rejected the column, the strip would show no busy times at all, silently — Task 13 checks on the phone that other events still show.

- [ ] **Step 11: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calendar/CalendarStore.kt app/src/main/java/io/github/amadeusb/callsheet/calendar/BusyTimes.kt app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Kalender: UID beim Anlegen setzen, zurücklesen und Einträge über die UID finden; Lesefehler sind kein gelöschter Eintrag"
```

---

### Task 8: Saving an appointment of its own

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`AppointmentDraft`, `openAppointment`, `loadBusy`, `updateAppointmentDraft`, `saveAppointment`; new `calendarLookup`, `locateEvent`, `eventFieldsFor`, `createEvent`, `rememberSeen`)
- Modify: `…/ui/AppointmentSheet.kt`, `…/MainActivity.kt`

**Interfaces:**
- Consumes: Repository methods (Task 4), `Appointment` rules (Tasks 5–6), `CalendarStore.read` and `CalendarStore.findByUid` — both throwing on failure — and `EventFields.uid` (Task 7).
- Produces:
  - `AppointmentDraft` gains `appointmentId: String? = null`, `note: String = ""`, `contactId: String? = null`, `eventElsewhere: Boolean = false`, `adoptable: Set<Long> = emptySet()`
  - `CallsheetViewModel.openAppointment(placeId: String, appointmentId: String? = null)`
  - private `suspend fun <T> calendarLookup(block: suspend () -> T): Result<T>` — a failure as a value, cancellation rethrown
  - private `suspend fun locateEvent(entry: AppointmentEntry): Located<EventFields>?` — throws when the calendar could not be asked
  - private `suspend fun lookUpEvent(entry: AppointmentEntry): Result<Located<EventFields>?>?` — null when this device may not read the calendar at all, so nothing was asked
  - private `suspend fun eventFieldsFor(entry: AppointmentEntry, business: Business): EventFields?`
  - private `suspend fun createEvent(uid: String, fields: EventFields): Pair<Long, EventFields>?`
  - private `suspend fun rememberSeen(appointmentId: String, eventId: Long, event: EventFields)`
  - `AppointmentSheet(draft, contacts: List<Contact>, onDraft, …)`

After this task, saving writes to `appointments`. The detail view still reads the old business columns until Task 10; the view model has no unit tests, so the check is the build, the suite, and a look in the emulator if one is at hand.

- [ ] **Step 1: Extend `AppointmentDraft`**

Add, after `location`:

```kotlin
    /** The appointment being changed; null for a new one. */
    val appointmentId: String? = null,
    /** „Besichtigung", „Angebot" … One line, optional. */
    val note: String = "",
    /** One of the business's contacts; null is „Keiner". */
    val contactId: String? = null,
    /**
     * The appointment has an event in the shared calendar, but not in this
     * device's copy yet. Saving leaves the calendar alone; the device holding
     * the event updates it after its next sync. The sheet says so.
     */
    val eventElsewhere: Boolean = false,
```

and after `calendarReadable`:

```kotlin
    /**
     * The busy events that may be linked. An event another appointment
     * already holds is shown as a conflict, but not offered for „Verknüpfen".
     */
    val adoptable: Set<Long> = emptySet(),
```

Add imports to `CallsheetViewModel.kt`:

```kotlin
import io.github.amadeusb.callsheet.calling.Located
import io.github.amadeusb.callsheet.data.AppointmentEntry
import kotlinx.coroutines.CancellationException
```

- [ ] **Step 2: Helpers**

In the `// ---- Appointment` section, add:

```kotlin
    /**
     * Runs a calendar lookup and hands a failure back as a value. A lookup that
     * failed — no permission, a provider that threw — is not an event that is
     * gone, and every caller has to be able to tell the two apart: taken for
     * "gone", it deletes an appointment on every device. Cancellation still
     * propagates.
     */
    private suspend fun <T> calendarLookup(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        Result.failure(failed)
    }

    /**
     * This appointment's event on this device, or null when it is not here —
     * see Appointment.locate. Throws when the calendar could not be asked; call
     * it through [calendarLookup].
     */
    private suspend fun locateEvent(entry: AppointmentEntry): Located<EventFields>? {
        val context = getApplication<Application>()
        return Appointment.locate(
            calendarEventId = entry.calendarEventId,
            eventUid = entry.eventUid,
            read = { CalendarStore.read(context, it) },
            find = { CalendarStore.findByUid(context, it) },
        )
    }

    /**
     * Looks for this appointment's event, if this device may read the calendar
     * at all. Null without the permission: nothing was asked, so there is
     * neither a result nor a failure to report — most people who never switched
     * the calendar on are in exactly that state, and must not be told on every
     * save that their calendar "could not be read".
     *
     * Null is therefore not "not found". No caller may act on it as a deletion;
     * the read-back does not even start without the permission.
     */
    private suspend fun lookUpEvent(entry: AppointmentEntry): Result<Located<EventFields>?>? {
        if (!CalendarStore.canRead(getApplication())) return null
        return calendarLookup { locateEvent(entry) }
    }

    /** The event as the app writes it for [entry]: title, time, place, and who to ask for. */
    private suspend fun eventFieldsFor(entry: AppointmentEntry, business: Business): EventFields? {
        val start = Clock.millis(entry.startsAt) ?: return null
        val end = Clock.millis(entry.endsAt) ?: (start + Appointment.DEFAULT_MINUTES * 60_000L)
        val contact = entry.contactId?.let { id -> repo.contacts(entry.placeId).firstOrNull { it.id == id } }
        return EventFields(
            title = Appointment.eventTitle(business.name, entry.note),
            startMillis = start,
            endMillis = end,
            location = entry.location,
            description = Appointment.eventDescription(contact, business.phone),
        )
    }

    /**
     * Creates the event under [uid] and reads it back to learn which UID it
     * kept. The returned fields carry that UID, or none when the provider
     * dropped it or the read failed — the link then stays local.
     */
    private suspend fun createEvent(uid: String, fields: EventFields): Pair<Long, EventFields>? {
        val context = getApplication<Application>()
        val calendar = preferences.calendarId ?: return null
        val eventId = CalendarStore.insert(context, calendar, fields.copy(uid = uid)) ?: return null
        val kept = Appointment.uidToTake(null, calendarLookup { CalendarStore.read(context, eventId) }.getOrNull()?.uid)
        return eventId to fields.copy(uid = kept)
    }

    /** Records locally which event this device links, and what that event now holds. */
    private suspend fun rememberSeen(appointmentId: String, eventId: Long, event: EventFields) {
        repo.setCalendarLink(
            appointmentId, eventId,
            Clock.format(event.startMillis), Clock.format(event.endMillis), event.location,
        )
    }
```

- [ ] **Step 3: Opening the sheet and the busy times**

Replace `openAppointment`:

```kotlin
    /**
     * Opens the sheet. Without [appointmentId] a new appointment starts as
     * before: in two days, snapped to the quarter hour, the last duration used,
     * the business's address. With one, everything comes from that appointment.
     */
    fun openAppointment(placeId: String, appointmentId: String? = null) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val business = repo.business(placeId) ?: return@launch
            val existing = appointmentId?.let { repo.appointment(it) }
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            val start = existing?.startsAt ?: Appointment.snapToQuarter(FollowUp.inTwoDays())
            val minutes = if (existing != null) {
                Appointment.minutesBetween(existing.startsAt, existing.endsAt)
            } else {
                preferences.appointmentMinutes
            }
            val location = if (existing != null) {
                existing.location.orEmpty()
            } else {
                Appointment.address(business.street, business.postalCode, business.city).orEmpty()
            }
            val readable = CalendarStore.canRead(context)
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        appointmentId = existing?.id,
                        note = existing?.note.orEmpty(),
                        contactId = existing?.contactId,
                        // Only from a lookup that ran and went through: a calendar that
                        // was not or could not be asked says nothing about where the event is.
                        eventElsewhere = preferences.calendarEnabled && lookup?.isSuccess == true &&
                            existing?.eventUid != null && located == null,
                        calendarReadable = readable,
                    )
                )
            }
            Clock.millis(start)?.let {
                loadBusy(it, existing?.eventUid, located?.eventId ?: existing?.calendarEventId)
            }
        }
    }
```

Replace `loadBusy`:

```kotlin
    /**
     * Reads the busy times for the day containing [millis].
     *
     * The event of the appointment being edited drops out — by [ownUid], or by
     * [ownEventId] where it has no UID yet. Leaving it in would make every save
     * collide with itself. Everything else stays, the business's other
     * appointments included.
     */
    private fun loadBusy(millis: Long, ownUid: String?, ownEventId: Long?) {
        viewModelScope.launch {
            val dayStart = Clock.todayStart(millis)
            val busy = Appointment.busyExcept(BusyTimes.forDay(getApplication(), dayStart), ownUid, ownEventId)
            val taken = repo.takenEvents()
            // None at all for an appointment that already has an event — see Appointment.adoptable.
            val adoptable = Appointment.adoptable(busy, taken, ownUid, ownEventId)
            _state.update { state ->
                val draft = state.appointmentDraft ?: return@update state
                state.copy(appointmentDraft = draft.copy(busy = busy, adoptable = adoptable, conflict = emptyList()))
            }
        }
    }
```

In `updateAppointmentDraft`, replace the `if (dayChanged) { … }` block:

```kotlin
        if (dayChanged) {
            viewModelScope.launch {
                val start = Clock.millis(draft.startIso) ?: return@launch
                val existing = draft.appointmentId?.let { repo.appointment(it) }
                loadBusy(start, existing?.eventUid, existing?.calendarEventId)
            }
        }
```

- [ ] **Step 4: Saving**

Replace `saveAppointment`:

```kotlin
    /** Writes the appointment, its calendar event, and — for one still ahead — the status. */
    fun saveAppointment(linkExisting: Long? = null, force: Boolean = false) {
        val draft = _state.value.appointmentDraft ?: return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val startMillis = Clock.millis(draft.startIso) ?: return@launch
            val endIso = Appointment.endOf(draft.startIso, draft.minutes)
            val endMillis = Clock.millis(endIso) ?: return@launch
            val business = repo.business(draft.placeId) ?: return@launch
            val existing = draft.appointmentId?.let { repo.appointment(it) }
            if (draft.appointmentId != null && existing == null) {
                // Deleted while the sheet was open — by a sync, or in the
                // calendar. Saving would bring it back under a new id.
                _state.update {
                    it.copy(appointmentDraft = null, hint = "Der Termin wurde inzwischen gelöscht. Nichts gespeichert.")
                }
                loadDetail(draft.placeId)
                return@launch
            }
            val readable = CalendarStore.canRead(context)
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            // The calendar was asked where this appointment's event is, and the
            // question failed. Saving then leaves the calendar alone: an Update is
            // impossible without the event, and a Create could put a second one
            // beside it. The same holds without read permission, where nothing
            // could be asked at all.
            val calendarUnreadable = lookup?.isFailure == true
            val calendarUsable = preferences.calendarEnabled && readable && !calendarUnreadable

            val plan = Appointment.plan(
                startMillis = startMillis,
                endMillis = endMillis,
                busy = draft.busy,
                ownEventId = located?.eventId,
                // Only an event no other appointment holds, whatever reached this call.
                linkExisting = linkExisting?.takeIf { it in draft.adoptable },
                force = force,
                calendarEnabled = calendarUsable,
                eventUid = existing?.eventUid,
            )

            if (plan is SavePlan.Conflict) {
                _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
                return@launch
            }

            var entry = AppointmentEntry(
                id = existing?.id ?: UUID.randomUUID().toString(),
                placeId = draft.placeId,
                startsAt = draft.startIso,
                endsAt = endIso,
                location = draft.location.trim().ifEmpty { null },
                note = draft.note.trim().ifEmpty { null },
                contactId = draft.contactId,
                eventUid = existing?.eventUid,
            )
            val fields = eventFieldsFor(entry, business) ?: return@launch
            // The linked event and what it holds once this is through; null when none is linked.
            var linked: Pair<Long, EventFields>? = null
            // This device's link is stale and goes — see the failed update below.
            var dropLink = false
            // Said only to someone who switched the calendar on: to everyone else
            // an untouched calendar is exactly what they chose.
            var calendarHint: String? = when {
                !preferences.calendarEnabled -> null
                !readable -> "Termin gespeichert. Ohne Zugriff auf den Kalender bleibt der Eintrag " +
                    "unberührt — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                calendarUnreadable -> "Termin gespeichert. Der Kalender ließ sich gerade nicht lesen; " +
                    "der Eintrag wird beim nächsten Öffnen abgeglichen."
                else -> null
            }

            when (plan) {
                // Adopting takes the calendar's time, place and UID, and leaves
                // the event exactly as it is. Gone, or unreadable, between listing
                // the day and pressing save: keep the draft and no link.
                is SavePlan.Adopt -> {
                    calendarLookup { CalendarStore.read(context, plan.eventId) }.getOrNull()?.let { event ->
                        entry = entry.copy(
                            startsAt = Clock.format(event.startMillis),
                            endsAt = Clock.format(event.endMillis),
                            location = event.location,
                            eventUid = Appointment.uidToTake(null, event.uid),
                        )
                        linked = plan.eventId to event
                    }
                    if (linked == null) {
                        calendarHint = "Termin gespeichert, aber nicht verknüpft: " +
                            "der Kalendereintrag war nicht mehr zu lesen."
                    }
                }

                is SavePlan.Update -> if (CalendarStore.update(context, plan.eventId, fields)) {
                    entry = entry.copy(eventUid = Appointment.uidToTake(entry.eventUid, located?.event?.uid) ?: entry.eventUid)
                    linked = plan.eventId to fields
                } else {
                    val afterFailure = calendarLookup { CalendarStore.read(context, plan.eventId) }
                    if (afterFailure.isSuccess && afterFailure.getOrNull() == null) {
                        // Deleted between opening the sheet and saving. A new event
                        // is what the user asked for — under a UID of its own: the
                        // old one may still be on its way out of the shared calendar
                        // as <uid>.ics, and a second resource with that UID would
                        // collide with it.
                        // Should the provider drop the fresh UID, the row keeps the old one
                        // (saving never clears a UID): the other devices then look for the
                        // deleted event, which is what a deletion in the calendar means to
                        // them anyway, and this device keeps its local link.
                        linked = createEvent(UUID.randomUUID().toString(), fields)
                            ?.also { (_, holds) -> entry = entry.copy(eventUid = holds.uid ?: entry.eventUid) }
                        if (linked == null) {
                            dropLink = true
                            calendarHint = "Termin gespeichert. Der Kalendereintrag war gelöscht und ließ " +
                                "sich nicht neu anlegen — prüfe den gewählten Kalender in den Einstellungen."
                        }
                    } else {
                        // Still there but not writable, or not readable: the event
                        // keeps its old time for now. This device's link and what it
                        // saw go, so the next opening does not take a stale S for
                        // proof of a deletion. With a UID it finds the event again
                        // and, seeing it for the first time, lets the row win.
                        dropLink = true
                        calendarHint = "Termin gespeichert. Der Kalendereintrag ließ sich nicht ändern; " +
                            "er wird beim nächsten Öffnen abgeglichen."
                    }
                }

                SavePlan.Create -> {
                    linked = createEvent(entry.id, fields)?.also { (_, holds) -> entry = entry.copy(eventUid = holds.uid) }
                    if (linked == null) {
                        calendarHint = "Termin gespeichert. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    }
                }

                SavePlan.LocalOnly -> Unit
                is SavePlan.Conflict -> Unit // already returned above
            }

            preferences.appointmentMinutes = draft.minutes
            repo.saveAppointment(entry)
            when {
                linked != null -> linked?.let { (eventId, holds) -> rememberSeen(entry.id, eventId, holds) }
                dropLink -> repo.setCalendarLink(entry.id, null, null, null, null)
            }
            Appointment.statusAfterSave(entry.startsAt, entry.endsAt, System.currentTimeMillis())
                ?.let { repo.setStatus(draft.placeId, it) }
            _state.update { it.copy(appointmentDraft = null, hint = calendarHint) }
            loadDetail(draft.placeId)
        }
    }
```

- [ ] **Step 5: The sheet**

In `AppointmentSheet.kt`, add imports:

```kotlin
import androidx.compose.foundation.horizontalScroll
import io.github.amadeusb.callsheet.data.Contact
```

Add the parameter `contacts: List<Contact>,` after `draft` in `AppointmentSheet`.

The sheet does not scroll as a whole, on purpose (see its KDoc): the strip scrolls, and `weight(1f)` on it is what gives way so the save button never does. But the strip has `heightIn(min = 320.dp)`, and Notiz, Ansprechpartner and the "elsewhere" line add some 200 dp to what already sits under it. With a conflict notice showing, the column then needs more than a phone's sheet has — the strip cannot shrink below its minimum, and „Termin speichern" is cut off with nothing to scroll to. So the fields under the strip get a bounded, scrolling area of their own, and the strip's minimum comes down.

Change the strip's modifier to:

```kotlin
                modifier = Modifier.weight(1f).heightIn(min = 160.dp),
```

Replace everything from `SectionLabel("Dauer")` down to and including the `Ort` text field with:

```kotlin
            // The fields under the strip scroll within a bounded height of their
            // own. Stacked at natural height they come to some 400 dp; together
            // with the strip's minimum, a conflict notice and the button that is
            // more than a sheet has, and the button would go first.
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("Dauer")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Appointment.DURATIONS.forEach { minutes ->
                        FilterChip(
                            selected = draft.minutes == minutes,
                            onClick = { onDraft(draft.copy(minutes = minutes)) },
                            label = { Text("$minutes") },
                        )
                    }
                }

                SectionLabel("Ort")
                OutlinedTextField(
                    value = draft.location,
                    onValueChange = { onDraft(draft.copy(location = it)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = false,
                )

                SectionLabel("Notiz")
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { onDraft(draft.copy(note = it)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    placeholder = { Text("Besichtigung, Angebot …") },
                )

                // Absent without contacts: a choice between „Keiner" and nothing is no choice.
                if (contacts.isNotEmpty()) {
                    SectionLabel("Ansprechpartner")
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = draft.contactId == null,
                            onClick = { onDraft(draft.copy(contactId = null)) },
                            label = { Text("Keiner") },
                        )
                        contacts.forEach { contact ->
                            FilterChip(
                                selected = draft.contactId == contact.id,
                                onClick = { onDraft(draft.copy(contactId = contact.id)) },
                                label = { Text(contact.name) },
                            )
                        }
                    }
                }

                if (draft.eventElsewhere) {
                    Text(
                        text = "Der Kalendereintrag liegt auf einem anderen Gerät. " +
                            "Er zieht nach, sobald dort abgeglichen ist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
```

The conflict notice stays outside that area, directly above the button: it is what has to be acted on, and must not be scrolled out of sight.

In the KDoc of `AppointmentSheet`, the last lines currently read:

```kotlin
 * `weight(1f)` on it and a bounded height on the column around it. Stacked at
 * their natural heights the pieces come to roughly 700 dp, and the conflict
 * notice adds another hundred; on an ordinary phone that pushes
 * "Termin speichern" off the bottom exactly when it is needed most.
```

Replace them with:

```kotlin
 * `weight(1f)` on it and a bounded height on the column around it.
 * The fields under the strip scroll in an area of at most 240 dp, and the strip
 * keeps at least 160 dp: title, date row and labels (~160 dp), strip, fields,
 * a conflict notice (~120 dp) and the button (~96 dp) stay within an ordinary
 * phone's sheet — "Termin speichern" is never pushed off the bottom, least of
 * all when a conflict is showing.
```

In `ConflictNotice`, offer „Verknüpfen" only for adoptable events:

```kotlin
            clash.eventId?.takeIf { it in draft.adoptable }?.let { id ->
                Button(onClick = { onLink(id) }) { Text("Verknüpfen") }
            }
```

- [ ] **Step 6: Wiring in `MainActivity.kt`**

In the `AppointmentSheet(` call, add `contacts = state.detailContacts,` after `draft = draft,`.

- [ ] **Step 7: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Termin als eigene Zeile speichern: Notiz, Ansprechpartner, Kalendereintrag über die UID"
```

---

### Task 9: „Termine heute" — one row per appointment

**Files:**
- Modify: `…/data/Repository.kt` (`appointmentsDue`), `…/CallsheetViewModel.kt` (`State.appointmentsToday`, `loadToday`), `…/ui/Today.kt`, `…/ui/Components.kt` (`BusinessRow`)
- Test: `RepositoryTest.kt`

**Interfaces:**
- Consumes: `AppointmentEntry`, `saveAppointment` (Task 4), `Appointment.rowLabel` (Task 6).
- Produces:
  - `Repository.appointmentsDue(fromMillis: Long, toMillis: Long): List<Pair<AppointmentEntry, Business>>`
  - `State.appointmentsToday: List<Pair<AppointmentEntry, Business>>`
  - `TodayScreen(appointments: List<Pair<AppointmentEntry, Business>>, …)`
  - `BusinessRow(…, appointment: AppointmentEntry? = null, …)` replaces `showAppointment: Boolean`

- [ ] **Step 1: Write the failing tests**

In `RepositoryTest.kt`, replace the three tests `appointmentsDue returns the day's appointments in order`, `a past appointment is not due today` and `a blocked business never appears in appointmentsDue` with:

```kotlin
    @Test
    fun `appointmentsDue returns one row per appointment, earliest first`() = runTest {
        import(
            """[
              {"placeId":"t-4","title":"Spaeter","phone":"+49 841 111"},
              {"placeId":"t-5","title":"Frueher","phone":"+49 841 222"}
            ]"""
        )
        repo.saveAppointment(visit("A-4", "t-4", "2026-09-10T15:00:00+02:00", "2026-09-10T16:00:00+02:00"))
        repo.saveAppointment(visit("A-5", "t-5", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00"))
        repo.saveAppointment(visit("A-6", "t-5", "2026-09-10T17:00:00+02:00", "2026-09-10T18:00:00+02:00"))
        repo.saveAppointment(visit("A-7", "t-4", "2026-09-12T09:00:00+02:00", "2026-09-12T10:00:00+02:00"))

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!
        val due = repo.appointmentsDue(from, until)

        assertEquals(listOf("A-5", "A-4", "A-6"), due.map { it.first.id })
        assertEquals(listOf("t-5", "t-4", "t-5"), due.map { it.second.placeId })
    }

    @Test
    fun `a past appointment is not due today`() = runTest {
        import("""[{"placeId":"t-9","title":"Vorletzte Woche","phone":"+49 841 111"}]""")
        repo.saveAppointment(visit("A-9", "t-9", "2026-08-27T09:00:00+02:00", "2026-08-27T10:00:00+02:00"))

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!

        assertTrue(repo.appointmentsDue(from, until).isEmpty())
    }

    @Test
    fun `a blocked business never appears in appointmentsDue`() = runTest {
        import("""[{"placeId":"t-7","title":"Gesperrt","phone":"+49 841 111"}]""")
        repo.saveAppointment(visit("A-7", "t-7", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00"))
        repo.setStatus("t-7", Status.DO_NOT_CALL)

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!

        assertTrue(repo.appointmentsDue(from, until).isEmpty())
    }

    @Test
    fun `a due appointment's business knows its contacts' numbers`() = runTest {
        import("""[{"placeId":"t-8","title":"Ohne Hauptnummer"}]""")
        repo.saveContact(ContactDraft(placeId = "t-8", name = "Frau Meier", numbers = listOf(PhoneDraft(number = "+49 176 12345"))))
        repo.saveAppointment(visit("A-8", "t-8", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00"))

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!

        // Without it the dial button in „Heute" would show nothing to dial.
        assertTrue(repo.appointmentsDue(from, until).single().second.hasNumber)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: FAIL — `Unresolved reference: first` (the old function returns `List<Business>`)

- [ ] **Step 3: Implement `appointmentsDue`**

Replace it in `Repository.kt`:

```kotlin
    /**
     * Appointments starting between [fromMillis] and [toMillis], earliest first,
     * each with its business. Two appointments at one business are two entries.
     *
     * Note the lower bound, which [due] does not have. An overdue follow-up is
     * still work to do — "you never rang back". An appointment from a fortnight
     * ago is not; it happened, or it did not, and either way it does not belong
     * under a heading that reads "today".
     */
    suspend fun appointmentsDue(fromMillis: Long, toMillis: Long): List<Pair<AppointmentEntry, Business>> =
        withContext(Dispatchers.IO) {
            val db = helper.readableDatabase
            val due = db.rawQuery(
                "SELECT a.* FROM appointments a JOIN businesses b ON b.place_id = a.place_id WHERE b.status <> ?",
                arrayOf(Status.DO_NOT_CALL.key),
            ).use { c -> allAppointments(c) }
                .mapNotNull { a -> Clock.millis(a.startsAt)?.let { it to a } }
                .filter { it.first in fromMillis..toMillis }
                .sortedBy { it.first }
                .map { it.second }

            val businesses = HashMap<String, Business?>()
            due.mapNotNull { appointment ->
                val business = businesses.getOrPut(appointment.placeId) {
                    db.rawQuery(
                        "SELECT b.*, $NUMBERS_SUBQUERY FROM businesses b WHERE b.place_id = ?",
                        arrayOf(appointment.placeId),
                    ).use { c -> if (c.moveToFirst()) fromCursor(c) else null }
                }
                business?.let { appointment to it }
            }
        }
```

- [ ] **Step 4: The view model**

In `State`: `val appointmentsToday: List<Pair<AppointmentEntry, Business>> = emptyList(),`. `loadToday` needs no change beyond compiling against the new type.

- [ ] **Step 5: `BusinessRow`**

In `Components.kt`, add `import io.github.amadeusb.callsheet.data.AppointmentEntry`. Replace the parameter `showAppointment: Boolean = false,` with:

```kotlin
    /** The appointment this row stands for, in „Termine heute". */
    appointment: AppointmentEntry? = null,
```

Replace the block `if (showAppointment && business.appointmentAt != null) { … }` with:

```kotlin
            appointment?.let {
                Text(
                    text = Appointment.rowLabel(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
```

- [ ] **Step 6: `TodayScreen`**

In `Today.kt`, add `import io.github.amadeusb.callsheet.data.AppointmentEntry`. Change the parameter to `appointments: List<Pair<AppointmentEntry, Business>>,` and replace the `items(appointments, …)` block with:

```kotlin
                items(appointments, key = { "t-" + it.first.id }) { (appointment, business) ->
                    BusinessRow(
                        business = business,
                        onDial = { onDial(business) },
                        onOpen = { onOpen(business) },
                        appointment = appointment,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
```

- [ ] **Step 7: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/Today.kt app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Heute: eine Zeile je Termin, mit Notiz"
```

---

### Task 10: The detail view, removal, reading back — and retiring the old columns

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`State.detailAppointments`, `loadDetail`, `openBusiness`, `removeAppointment`; new `reconcileAppointments`, `reconcile`; delete `syncAppointment`)
- Modify: `…/ui/BusinessDetail.kt`, `…/MainActivity.kt`
- Modify: `…/data/Models.kt`, `…/data/Repository.kt`, `…/calling/Appointment.kt` (remove old code)
- Test: `AppointmentTest.kt` (remove the old read-back tests)

**Interfaces:**
- Consumes: everything from Tasks 4–8; `calendarLookup`, `lookUpEvent`, `rememberSeen`, `eventFieldsFor`, `locateEvent` (Task 8); `Appointment.seenIsCurrent` (Task 5).
- Produces:
  - `State.detailAppointments: List<AppointmentEntry>`
  - `CallsheetViewModel.removeAppointment(appointmentId: String)`
  - private `suspend fun reconcile(entry: AppointmentEntry, business: Business, nowMillis: Long, rowWinsOnly: Boolean): Reconcile?` — null also when the calendar could not be asked; Task 11 calls it with `rowWinsOnly = true`
  - `BusinessDetailScreen(…, appointments: List<AppointmentEntry>, …, onAppointment: (String?) -> Unit, onRemoveAppointment: (String) -> Unit, …)`

- [ ] **Step 1: State and loading**

In `CallsheetViewModel.kt`, add to `State` after `detailContacts`:

```kotlin
    val detailAppointments: List<AppointmentEntry> = emptyList(),
```

Add `import io.github.amadeusb.callsheet.calling.Reconcile` and `import io.github.amadeusb.callsheet.calling.Slot`.

In `loadDetail`, add `detailAppointments = repo.appointments(placeId),` to the first `copy`.

In `openBusiness`, add `detailAppointments = if (switching) emptyList() else _state.value.detailAppointments,` and replace the last two lines with:

```kotlin
        loadDetail(placeId)
        // No loop: reconcileAppointments only ever calls loadDetail, never back here.
        reconcileAppointments(placeId)
```

- [ ] **Step 2: Reading back, per appointment**

Delete `syncAppointment` and put in its place:

```kotlin
    /**
     * Brings one appointment and its event back in line — the table in
     * Appointment.reconcile — and returns what it found, or null when nothing
     * was compared.
     *
     * A calendar that could not be asked is one of those nulls. It is skipped,
     * never passed on as "not found": seen before and ahead, that would read as
     * deleted in the calendar and delete the appointment on every device.
     *
     * With [rowWinsOnly], only an event update is carried out: after a sync
     * nobody is looking, so whatever would change a row or delete an
     * appointment waits for the next opening, where a hint can say so.
     */
    private suspend fun reconcile(
        entry: AppointmentEntry,
        business: Business,
        nowMillis: Long,
        rowWinsOnly: Boolean,
    ): Reconcile? {
        val context = getApplication<Application>()
        val row = Appointment.rowSlot(entry) ?: return null
        // Without read permission nothing can be compared, and nothing may be
        // concluded — the callers check too, this makes it hold for any caller.
        if (!CalendarStore.canRead(context)) return null
        val located = calendarLookup { locateEvent(entry) }.getOrElse { return null }
        val event = located?.event
        val outcome = Appointment.reconcile(
            row = row,
            seen = Appointment.seenSlot(entry),
            event = event?.let { Slot(it.startMillis, it.endMillis, it.location) },
            nowMillis = nowMillis,
        )
        if (rowWinsOnly && outcome != Reconcile.UpdateEvent) return null

        // The event behind the link carries a UID the row does not know — a
        // carried-over appointment, or an event DAVx5 has uploaded since. It is
        // the same event; the other devices look it up by this UID.
        val uid = if (rowWinsOnly || event == null) null else Appointment.uidToTake(entry.eventUid, event.uid)
        if (uid != null) repo.setEventUid(entry.id, uid)

        when (outcome) {
            Reconcile.InStep -> {
                val holds = Slot(event!!.startMillis, event.endMillis, event.location)
                // Only when something is new — a link rewritten on every opening
                // would notify every observer of the database for nothing.
                if (!Appointment.seenIsCurrent(entry, located!!.eventId, holds)) {
                    rememberSeen(entry.id, located.eventId, event)
                }
            }

            is Reconcile.TakeEvent -> {
                repo.saveAppointment(
                    entry.copy(
                        startsAt = Clock.format(outcome.slot.startMillis),
                        endsAt = outcome.slot.endMillis?.let { Clock.format(it) },
                        location = outcome.slot.location,
                        eventUid = uid ?: entry.eventUid,
                    )
                )
                rememberSeen(entry.id, located!!.eventId, event!!)
            }

            Reconcile.UpdateEvent -> {
                // Reading is allowed with the calendar switched off in the
                // settings; writing is not — the same condition followCalendar
                // checks before it writes anything.
                if (!preferences.calendarEnabled || !CalendarStore.canWrite(context)) return outcome
                val fields = eventFieldsFor(entry, business) ?: return outcome
                if (CalendarStore.update(context, located!!.eventId, fields)) {
                    rememberSeen(entry.id, located.eventId, fields)
                }
            }

            Reconcile.NotYetHere -> Unit
            Reconcile.DeletedInCalendar -> repo.deleteAppointment(entry.id)
            Reconcile.Unlink -> repo.setCalendarLink(entry.id, null, null, null, null)
        }
        return outcome
    }

    /**
     * Reads every linked appointment of a business back from the calendar, on
     * opening it. A deletion in the calendar is the one case that speaks up,
     * because it is the one that may take the status back.
     */
    private fun reconcileAppointments(placeId: String) {
        viewModelScope.launch {
            if (!CalendarStore.canRead(getApplication())) return@launch
            val business = repo.business(placeId) ?: return@launch
            val now = System.currentTimeMillis()
            val outcomes = repo.appointments(placeId)
                .filter { it.eventUid != null || it.calendarEventId != null }
                .mapNotNull { reconcile(it, business, now, rowWinsOnly = false) }
            if (outcomes.isEmpty()) return@launch

            if (Reconcile.DeletedInCalendar in outcomes) {
                val fallback = Appointment.statusAfterRemoval(business.status, repo.appointments(placeId), now)
                fallback?.let { repo.setStatus(placeId, it) }
                _state.update {
                    it.copy(
                        hint = if (fallback != null) {
                            "Der Termin wurde im Kalender gelöscht. Status zurück auf „Angerufen“."
                        } else {
                            "Der Termin wurde im Kalender gelöscht. Der Status bleibt, wie er ist."
                        }
                    )
                }
            }
            loadDetail(placeId)
        }
    }
```

- [ ] **Step 3: Removing one appointment**

Replace `removeAppointment`:

```kotlin
    /**
     * Removes an appointment and its calendar event — a past one too; the
     * detail view asks first. Where the event is not on this device, the row
     * goes alone and the device holding the event deletes it after its next sync.
     */
    fun removeAppointment(appointmentId: String) {
        viewModelScope.launch {
            val entry = repo.appointment(appointmentId) ?: return@launch
            val business = repo.business(entry.placeId) ?: return@launch
            val lookup = lookUpEvent(entry)
            lookup?.getOrNull()?.let { CalendarStore.delete(getApplication(), it.eventId) }
            repo.deleteAppointment(entry.id)
            Appointment.statusAfterRemoval(business.status, repo.appointments(entry.placeId), System.currentTimeMillis())
                ?.let { repo.setStatus(entry.placeId, it) }
            if (lookup?.isFailure == true && preferences.calendarEnabled) {
                // The row is gone either way; the event on this device could not
                // be found to go with it, and nothing will bring that back. Not
                // said without permission or with the calendar off: nothing was
                // asked, and nothing went wrong.
                _state.update {
                    it.copy(hint = "Termin entfernt. Der Kalender ließ sich nicht lesen — den Eintrag dort bitte selbst löschen.")
                }
            }
            loadDetail(entry.placeId)
        }
    }
```

- [ ] **Step 4: The detail view**

In `BusinessDetail.kt`, add `import io.github.amadeusb.callsheet.data.AppointmentEntry`.

`BusinessDetailScreen` parameters: add `appointments: List<AppointmentEntry>,` after `contacts`, and change

```kotlin
    onAppointment: (String?) -> Unit,
    onRemoveAppointment: (String) -> Unit,
```

Replace `var removeAppointment by remember { mutableStateOf(false) }` with:

```kotlin
    var removeAppointment by remember { mutableStateOf<AppointmentEntry?>(null) }
```

Replace the `item(key = "appointment")` block:

```kotlin
            item(key = "appointment") {
                Section("Termin vor Ort")
                AppointmentsBlock(
                    business = business,
                    appointments = appointments,
                    contacts = contacts,
                    onSet = onAppointment,
                    onRemove = { removeAppointment = it },
                    onOpenUrl = onOpenUrl,
                )
            }
```

Replace the `if (removeAppointment) { AlertDialog(…) }` block:

```kotlin
    removeAppointment?.let { entry ->
        val now = System.currentTimeMillis()
        val ahead = Appointment.isAhead(entry.startsAt, entry.endsAt, now)
        val fallsBack = Appointment.statusAfterRemoval(
            business.status, appointments.filter { it.id != entry.id }, now,
        ) != null
        AlertDialog(
            onDismissRequest = { removeAppointment = null },
            title = { Text(if (ahead) "Termin entfernen?" else "Früheren Termin entfernen?") },
            text = {
                Text(
                    listOfNotNull(
                        if (ahead) {
                            "Der Termin wird auch aus dem Kalender gelöscht."
                        } else {
                            "Der Termin ist vorbei und bleibt sonst als Nachweis stehen. " +
                                "Er wird auch aus dem Kalender gelöscht."
                        },
                        "Der Status fällt zurück auf „Angerufen“.".takeIf { fallsBack },
                    ).joinToString(" ")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveAppointment(entry.id)
                    removeAppointment = null
                }) { Text("Entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { removeAppointment = null }) { Text("Abbrechen") }
            },
        )
    }
```

Replace the whole `AppointmentBlock` composable with:

```kotlin
/**
 * The appointments on site. Sits above the follow-up because the two are the
 * answers to one question — when does this go on?
 *
 * The ones ahead come first, earliest first. Past ones stay as a record,
 * collapsed, latest first. „Termin anlegen" is always there: a second
 * appointment is as ordinary as a first.
 */
@Composable
private fun AppointmentsBlock(
    business: Business,
    appointments: List<AppointmentEntry>,
    contacts: List<Contact>,
    onSet: (String?) -> Unit,
    onRemove: (AppointmentEntry) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val (ahead, past) = Appointment.split(appointments, System.currentTimeMillis())
    var showPast by remember(business.placeId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (ahead.isEmpty()) {
            // A status of "Termin" without a single appointment is the hole this
            // section exists to close: say so, and offer the way out — never
            // force it. With only past ones the status is simply what the last
            // visit left behind; nothing is wrong, so nothing is red.
            val statusOnly = business.status == Status.APPOINTMENT && appointments.isEmpty()
            Text(
                text = when {
                    statusOnly -> "Status „Termin“, aber kein Termin steht an."
                    past.isNotEmpty() -> "Kein Termin steht an."
                    else -> "Kein Termin vereinbart."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusOnly) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ahead.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl) }

        if (past.isNotEmpty()) {
            TextButton(onClick = { showPast = !showPast }) {
                Text("Frühere Termine (${past.size})")
            }
            if (showPast) past.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl) }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSet(null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Termin anlegen") }
    }
}

@Composable
private fun AppointmentItem(
    entry: AppointmentEntry,
    contacts: List<Contact>,
    onSet: (String?) -> Unit,
    onRemove: (AppointmentEntry) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = Appointment.readableRange(entry.startsAt, entry.endsAt),
            style = MaterialTheme.typography.titleMedium,
        )
        entry.note?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        // A deleted contact simply leaves no name behind.
        contacts.firstOrNull { it.id == entry.contactId }?.let {
            Text(
                text = it.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.location?.takeIf { it.isNotBlank() }?.let { where ->
            Text(
                text = where,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onOpenUrl(geoUri(where)) }
                    .padding(vertical = 4.dp),
            )
        }
        if (entry.calendarEventId != null) {
            Text(
                text = "Im Kalender abgelegt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSet(entry.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Ändern")
            }
            OutlinedButton(onClick = { onRemove(entry) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Entfernen")
            }
        }
    }
}
```

"Im Kalender abgelegt." reads `calendarEventId`: the read-back on opening stores the id it found and drops it with `Unlink`, so the column is what "linked on this device" means.

- [ ] **Step 5: Wiring in `MainActivity.kt`**

In the `BusinessDetailScreen(` call:

```kotlin
                    appointments = state.detailAppointments,
```

after `contacts = state.detailContacts,`, and

```kotlin
                    onAppointment = { id -> vm.openAppointment(business.placeId, id) },
                    onRemoveAppointment = vm::removeAppointment,
```

- [ ] **Step 6: Retire the old code**

- `Models.kt`: remove `appointmentAt`, `appointmentEndAt`, `appointmentLocation`, `calendarEventId` and their comments from `Business`.
- `Repository.kt`: remove `setAppointment` and the four matching lines in `fromCursor`.
- `Appointment.kt`: remove `sealed interface ReadBack` and `fun readBack(…)`. Keep `near`.
- `CallsheetViewModel.kt`: remove `import io.github.amadeusb.callsheet.calling.ReadBack`.
- `AppointmentTest.kt`: remove the `// --- readBack` section — the fields `at`, `until` and the five tests `a difference of seconds still counts as unchanged`, `an untouched appointment yields Unchanged`, `a moved appointment yields Updated`, `a relocated appointment yields Updated`, `a deleted appointment yields Gone` — and the `ReadBack` import. The `reconcile` tests of Task 5 cover the same ground.

Then check nothing reads the old columns:

Run: `grep -rn "appointmentAt\|appointmentEndAt\|appointmentLocation\|setAppointment\|ReadBack\|syncAppointment\|business.calendarEventId" app/src`
Expected: no output

- [ ] **Step 7: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Detailansicht mit mehreren Terminen, Rücklesen je Termin, alte Terminspalten nicht mehr gelesen"
```

---

### Task 11: The calendar follows a sync

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`syncNow`, `connectServer`; new `followCalendar`)

**Interfaces:**
- Consumes: `SyncEngine.sync(…, onApplied)` (Task 3), `AppliedAppointments`, `RemovedAppointment` (Task 2), `calendarLookup` (Task 8), `reconcile(…, rowWinsOnly = true)` (Task 10), `Appointment.locate` (Task 6).

Nobody may open a business for days, so a sync does not leave the calendar to the next read-back.

- [ ] **Step 1: `followCalendar`**

Add import `io.github.amadeusb.callsheet.sync.AppliedAppointments`. Below `reconcileAppointments`:

```kotlin
    /**
     * Brings the calendar along after a sync. An appointment changed on another
     * device moves its event here; a deleted one takes its event with it — with
     * a shared calendar the removing device has usually done that already, and
     * deleting an event that is gone changes nothing.
     *
     * Only where the app may write the calendar at all.
     */
    private fun followCalendar(applied: List<AppliedAppointments>) {
        if (applied.isEmpty()) return
        val context = getApplication<Application>()
        if (!preferences.calendarEnabled || !CalendarStore.canRead(context) || !CalendarStore.canWrite(context)) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            for (batch in applied) {
                for (removed in batch.removed) {
                    // A calendar that could not be asked keeps the event; the
                    // appointment is gone regardless, and nothing else is at stake.
                    calendarLookup {
                        Appointment.locate(
                            calendarEventId = removed.calendarEventId,
                            eventUid = removed.eventUid,
                            read = { CalendarStore.read(context, it) },
                            find = { CalendarStore.findByUid(context, it) },
                        )
                    }.getOrNull()?.let { CalendarStore.delete(context, it.eventId) }
                }
                for (id in batch.written.distinct()) {
                    val entry = repo.appointment(id) ?: continue
                    if (entry.eventUid == null && entry.calendarEventId == null) continue
                    val business = repo.business(entry.placeId) ?: continue
                    reconcile(entry, business, now, rowWinsOnly = true)
                }
            }
            (_state.value.screen as? Screen.Detail)?.let { loadDetail(it.placeId) }
        }
    }
```

- [ ] **Step 2: Collect and hand over in `syncNow`**

Replace the `val result = withContext(Dispatchers.IO) { … }` in `syncNow` with:

```kotlin
            // Filled on the sync thread, read here after it returns.
            val applied = java.util.Collections.synchronizedList(ArrayList<AppliedAppointments>())
            val result = withContext(Dispatchers.IO) {
                syncEngine.sync(SyncClient(url, token), ::reportUploadProgress) { applied.add(it) }
            }
```

and add, right after `refreshSyncState()` in the same coroutine:

```kotlin
            // Whatever came down is real, even when the run did not finish.
            followCalendar(applied.toList())
```

- [ ] **Step 3: The same in `connectServer`**

Replace its `val result = withContext(Dispatchers.IO) { … }` with:

```kotlin
            val applied = java.util.Collections.synchronizedList(ArrayList<AppliedAppointments>())
            val result = withContext(Dispatchers.IO) {
                syncEngine.sync(SyncClient(preferences.serverUrl!!, token), ::reportUploadProgress) { applied.add(it) }
            }
```

and add `followCalendar(applied.toList())` right after its `refreshSyncState()`.

- [ ] **Step 4: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Nach dem Abgleich zieht der Kalender nach: geänderte Termine verschieben, gelöschte entfernen"
```

---

### Task 12: Documentation and the 1.4.0 section

**Files:**
- Modify: `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md`

- [ ] **Step 1: `docs/data-model.md`**

In ``### `businesses` ``, replace these four lines of the working-fields block:

```
appointment_at       TEXT       -- the appointment on site, ISO-8601 with a time
appointment_end_at   TEXT       -- its end; the two together give the duration
appointment_location TEXT       -- one line, as it goes into the calendar event
calendar_event_id    INTEGER    -- the linked event; local to this device
```

with:

```
appointment_at       TEXT       -- no longer used since schema 4, emptied; see `appointments`
appointment_end_at   TEXT       -- no longer used since schema 4
appointment_location TEXT       -- no longer used since schema 4
calendar_event_id    INTEGER    -- no longer used since schema 4
```

Below the `businesses` section — directly before the heading ``### `status` — allowed values`` — add:

````markdown
### `appointments`

Appointments on site, any number per business — one after another, or side by
side. Synchronised like contacts: a UUID per row, deleted through tombstones.

```
id                      TEXT PRIMARY KEY  -- UUID; carried-over appointments: 'legacy-<place_id>'
place_id                TEXT NOT NULL
starts_at               TEXT NOT NULL     -- ISO-8601 with a time and a zone
ends_at                 TEXT
location                TEXT              -- one line, as it goes into the calendar event
note                    TEXT              -- "Besichtigung", "Angebot" …
contact_id              TEXT              -- one of the business's contacts, or null
updated_at              TEXT NOT NULL
event_uid               TEXT              -- iCalendar UID of the linked event, or null
calendar_event_id       INTEGER           -- local: the event's _ID on this device
calendar_seen_starts_at TEXT              -- local: what this device last saw in the event
calendar_seen_ends_at   TEXT              -- local
calendar_seen_location  TEXT              -- local
dirty                   INTEGER NOT NULL DEFAULT 0
```

`contact_id` is a reference and nothing more: deleting a contact leaves its
appointments without a contact person.

Schema 4 carried each business's single appointment over into a row
`legacy-<place_id>`, the same id the server's migration writes, and emptied the
old columns on both sides.
````

In `### deletions`, change "Contacts and their numbers are the only rows the app ever deletes." to "Contacts, their numbers and appointments are the only rows the app ever deletes."

In `## Synchronisation`, replace the five-line paragraph that begins with the line "The appointment's time, end and location travel like every other working field." and ends with "is, and links it to its own calendar the next time it is saved there." with:

```markdown
An appointment travels with its `event_uid`: the calendar is shared, and the UID
is the same event on every device. `calendar_event_id` and the
`calendar_seen_` columns do not — an event's `_ID`, and what one device last
saw in its copy of the event, mean nothing on the next.

The server names the tables it synchronises in `tables`. Marks are cleared only
for those; a server from before appointments ignores the table without a word,
and its rows stay marked — counted as open — until the server is updated. The
first sync after upgrading to schema 4 fetches from watermark 0 once, because a
1.3.x app skipped appointments while its watermark moved past them.
```

Replace the four bullets of `## Calendar` — from "- The app recognises its own appointment by the event id it stores on the" down to "- Busy times for the picker are read from every visible calendar, and only read." — with:

```markdown
- An appointment is linked to its event by the event's iCalendar UID
  (`UID_2445`), which DAVx5 keeps the same on every device. An event the app
  creates gets the appointment's id as its UID. The event's `_ID` on this device
  is kept as a shortcut, used while the event behind it exists.
- Other people's appointments are never touched — an existing event can be
  *linked*, which records that the two are the same thing and leaves the entry
  exactly as it was. An event another appointment already holds is not offered.
- An appointment whose event exists but has not reached this device is saved
  without touching the calendar; the device holding the event updates it after
  its next sync.
- The other direction: opening a record reads each appointment's event back,
  comparing the row, the event and what this device last saw in the event. Moved
  only in the calendar, the calendar wins; everywhere else the row wins and the
  event is updated. An event not found that this device never saw means nothing
  yet. One seen before and gone is a deletion while the appointment is ahead —
  the appointment goes, and the status falls back to `called` if it was still
  `appointment` and no other appointment is ahead — and only a lost link once it
  is past.
- After a sync, appointments changed elsewhere move their events and deleted
  ones take their events along.
- Busy times for the picker are read from every visible calendar, and only read.
```

In `## Import`, step 3 reads, over three lines:

```markdown
3. Known businesses: **master data only.** Status, note, follow-up, the
   appointment and its calendar link, and the call history stay untouched. A
   fresh export must never overwrite the work.
```

Replace it with:

```markdown
3. Known businesses: **master data only.** Status, note, follow-up,
   appointments and the call history stay untouched. A fresh export must never
   overwrite the work.
```

- [ ] **Step 2: `docs/usage.md`**

In `## Appointments on site`, replace the first paragraph — the three lines from "When a call ends in a visit, the detail view's **Termin vor Ort** section is" to "stays readable while the conversation is still running." — with:

```markdown
When a call ends in a visit, the detail view's **Termin vor Ort** section is
where it goes. A business can have as many as the work needs — a site visit and
then a meeting about the quote, or two at once with different people. Each one
shows its time, note, contact person and place, with **Ändern** and
**Entfernen** of its own. Past appointments stay as a record under **Frühere
Termine**, collapsed. „Termin anlegen" is always there and opens a sheet over
the record, so the business stays readable while the conversation is still
running.
```

After the paragraph that ends "calendar entry carries, so it is what the navigation reads.", add:

```markdown
**Notiz** says what the appointment is for — „Besichtigung", „Angebot" — and
goes into the calendar entry's title. **Ansprechpartner** picks who to ask for
on site; their name and number go into the entry.
```

In the bullet beginning "- **Verknüpfen** — this is that appointment.", after its last words "its time and place win.", append: " An entry another appointment already holds is not offered."

Replace the four-line paragraph beginning "Saving writes the appointment, sets the status to „Termin", and — if the" with:

```markdown
Saving writes the appointment, sets the status to „Termin" if it is still
ahead, and — if the calendar is switched on in the settings — puts an entry in
the chosen calendar. Without a calendar, or without the permission, the
appointment still lives in the app; the strip then says so rather than
pretending the day is free.
```

Replace the five-line paragraph beginning "**Moving it in the calendar is enough.** Shift the entry on a laptop or in the" and ending "case that needs to be noticed." with:

```markdown
**Moving it in the calendar is enough.** Shift the entry on a laptop or in the
car, and the app takes the new time over the next time the record is opened,
without asking. A change made in the app on another phone moves the entry here
after the next sync. Delete the entry in the calendar and the appointment is
removed — said out loud, because the status falls back to „Angerufen" once no
other appointment is ahead. A past entry that a calendar clears out on its own
leaves the appointment in place.

**Entfernen** asks first, for a past appointment too: it removes a piece of the
record.
```

- [ ] **Step 3: `README.md`**

Replace the feature bullet, which reads over two lines:

```markdown
- **Appointment on site** with a time, a length and an address, mirrored into
  the device's calendar
```

with:

```markdown
- **Appointments on site**, as many per business as the work needs, each with a
  time, a length, an address, a note and a contact person, mirrored into the
  device's calendar
```

- [ ] **Step 4: `CHANGELOG.md`**

Below the introduction, above `## 1.3.1`:

```markdown
## 1.4.0

- **Several appointments per business.** A site visit and then a meeting about
  the quote, or two at once with different people: each appointment has its own
  time, place, note and contact person, its own calendar entry, and its own
  **Ändern** and **Entfernen**. Past ones stay as a record under **Frühere
  Termine**.
- **Termine heute** lists appointments, not businesses. Two at one business are
  two rows, each with its note.
- **A shared calendar knows which entry belongs to which appointment on every
  phone.** Entries are linked by their calendar UID instead of one phone's entry
  number, so a second phone no longer writes a copy of an entry DAVx5 already
  brought over, and an appointment changed on one phone moves its entry on the
  others.
- **Removing an appointment** only puts the status back to „Angerufen" when no
  other appointment is still ahead. Removing a past one asks first.
- **Update the sync server first.** An older server ignores appointments; the
  app then keeps them as „offen" until it is updated rather than losing them.
- The first sync after the update fetches everything from the server once.
```

- [ ] **Step 5: Full verification**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: all tests pass, BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add docs/data-model.md docs/usage.md README.md CHANGELOG.md
git commit -m "Doku und CHANGELOG-Abschnitt für 1.4.0: mehrere Termine je Betrieb"
```

---

### Task 13: On the phone

**Precondition:** the server plan's Task 4 (rollout) is done — `POST /sync` answers with `tables` naming `appointments`. Not done by an agent: it needs the user's phone, the Infomaniak web calendar and DAVx5.

- [ ] **Step 1: Install a release build over 1.3.1**

The phone's 1.3.1 is signed with the release key from `keystore.properties`. A debug build carries the debug key, and Android refuses it as an update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). **Never uninstall to get round that:** uninstalling wipes the database — the stock, and the very 3 → 4 migration this step exists to check.

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`; the app opens with its stock intact. Businesses that had an appointment show it in the detail view.

- [ ] **Step 2: Sync once**

Settings → **Jetzt abgleichen**. Expected: the sync goes through and „offen" returns to 0.

- [ ] **Step 3: The check from the spec**

1. Create an appointment in the app on a business with the Infomaniak calendar chosen in the settings.
2. Move it in the Infomaniak web calendar.
3. Let DAVx5 synchronise, open the business.

Expected: the appointment shows the new time, and the calendar holds **one** event for it, not two.

- [ ] **Step 4: What the build cannot show**

1. **Busy times still show.** Open „Termin anlegen" on a day that has other events in the calendar. Expected: they are drawn in the strip. (`BusyTimes` now asks the provider for `Instances.UID_2445`; a provider that rejected the column would leave the strip silently empty.)
2. **A recurring event cannot be linked.** Pick a slot that collides with an occurrence of a recurring event. Expected: the conflict notice names it, but offers no „Verknüpfen".
3. **An appointment with an event links nothing else.** Open „Ändern" on an appointment already in the calendar and pick a slot that collides with another event. Expected: no „Verknüpfen".
4. **Without calendar permission nothing complains.** In the Android settings, revoke the app's calendar permission, switch the calendar off in the app, then change and remove an appointment. Expected: no hint about an unreadable calendar. Grant the permission again afterwards.
5. **The save button is reachable.** On a business with at least one contact, open the sheet, type a Notiz, pick an Ansprechpartner, and choose a time that collides with an existing event so the conflict notice shows. Expected: „Termin speichern" is fully visible without scrolling the sheet, and the fields under the strip scroll on their own.

- [ ] **Step 5: Report**

Tell the user what was seen at each step. A duplicate event or a vanished appointment is a finding, not a flake; do not release until it is understood. The release itself (`tools/release.sh minor`) is the user's.

---

## Addendum — fixes after the final review (approved by the user on 2026-09-14)

The whole-branch review after Tasks 1–12 found four multi-device defects in the plan's own code, plus one spec rule the plan left out. Task 14 fixes them. Question 3 of that review (first sight on a device: should "row wins" really move an event that was moved in the calendar?) is **not** part of it; it waits for the user's decision.

What changes against the spec, on purpose:

- **A UID is only ever taken over by a row that has none.** The spec (The calendar → "The event behind the shortcut carries a different UID") lets a row take whatever UID it finds behind its shortcut. With appointments carried over from 1.3.x that makes two devices trade UIDs back and forth on every opening while two events sit in the calendar. Now: a row without a UID takes the event's; a row whose UID names another event on this device moves its shortcut there and deletes the stale copy; otherwise the row's UID stands. The rare case the spec guarded against — a server replacing a UID — then degrades to "the other devices do not find the event and save `LocalOnly`", which creates no duplicate and deletes nothing.
- **A failed update keeps the shortcut.** Only what this device saw is forgotten.

### Task 14: Multi-device fixes

**Files:**
- Modify: `…/calling/Appointment.kt` (`uidToTake`, new `relinkTo`)
- Modify: `…/data/Repository.kt` (new `linkedWithoutUid`)
- Modify: `…/CallsheetViewModel.kt` (`saveAppointment`, `removeAppointment`, `reconcile`, `syncNow`; new `captureMissingUids`)
- Test: `AppointmentTest.kt`, `RepositoryTest.kt`

**Interfaces:**
- `Appointment.uidToTake(rowUid: String?, eventUid: String?): String?` — non-null only when `rowUid` is null and `eventUid` is not blank
- `Appointment.relinkTo(rowUid: String?, eventUid: String?, shortcutId: Long, rowUidEventId: Long?): Long?`
- `Repository.linkedWithoutUid(): List<AppointmentEntry>`
- private `suspend fun captureMissingUids(): Int` in the view model

- [ ] **Step 1: Write the failing tests**

In `AppointmentTest.kt`, replace the test `a different UID behind the shortcut is taken over` with:

```kotlin
    @Test
    fun `a row without a UID takes the one behind its shortcut`() {
        assertEquals("abc@infomaniak", Appointment.uidToTake(rowUid = null, eventUid = "abc@infomaniak"))
    }

    @Test
    fun `a row that has a UID never trades it for another`() {
        // Two devices, each with a shortcut to its own copy, would otherwise
        // swap UIDs on every opening.
        assertNull(Appointment.uidToTake(rowUid = "legacy-P1", eventUid = "abc@infomaniak"))
    }

    @Test
    fun `a row whose UID names another event here moves its shortcut there`() {
        assertEquals(815L, Appointment.relinkTo(rowUid = "legacy-P1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = 815L))
    }

    @Test
    fun `no relinking without a UID, with the same UID, or when the row's UID is not here`() {
        assertNull(Appointment.relinkTo(rowUid = null, eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = null))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "A-1", shortcutId = 4711L, rowUidEventId = 4711L))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = null))
        assertNull(Appointment.relinkTo(rowUid = "A-1", eventUid = "abc@infomaniak", shortcutId = 4711L, rowUidEventId = 4711L))
    }
```

The tests `the same UID is nothing to take over` and `an empty UID after inserting keeps the link local` stay.

In `RepositoryTest.kt`, below `takenEvents names every UID and local event id in use`:

```kotlin
    @Test
    fun `linkedWithoutUid lists rows linked on this device that have no UID yet`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T14:00:00+02:00"))
        repo.setCalendarLink("A-1", 4711L, null, null, null)
        repo.saveAppointment(visit("A-2", "t-1", "2026-09-11T14:00:00+02:00").copy(eventUid = "uid-2"))
        repo.setCalendarLink("A-2", 815L, null, null, null)
        repo.saveAppointment(visit("A-3", "t-1", "2026-09-12T14:00:00+02:00"))

        assertEquals(listOf("A-1"), repo.linkedWithoutUid().map { it.id })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest" --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: FAIL — compile errors for `relinkTo` and `linkedWithoutUid`

- [ ] **Step 3: The rules in `Appointment.kt`**

Replace `uidToTake` and add `relinkTo` below it:

```kotlin
    /**
     * The UID a row takes over from its event, or null when there is nothing to take.
     *
     * Only a row without a UID takes one: an event the app just created, one it
     * adopted, or a carried-over appointment whose event DAVx5 has uploaded. A
     * row that has a UID keeps it, whatever its shortcut finds — two devices
     * each holding a copy of the event would otherwise trade UIDs on every
     * opening, and both copies would stay in the calendar.
     */
    fun uidToTake(rowUid: String?, eventUid: String?): String? =
        eventUid?.takeIf { rowUid == null && it.isNotBlank() }

    /**
     * Where the shortcut should point instead, or null to leave it.
     *
     * The event behind the shortcut ([shortcutId]) carries [eventUid], the row
     * holds [rowUid], and [rowUidEventId] is the event that UID finds on this
     * device. When the row's UID names a different event here, that event is the
     * appointment's; the shortcut points at a stale copy.
     */
    fun relinkTo(rowUid: String?, eventUid: String?, shortcutId: Long, rowUidEventId: Long?): Long? {
        if (rowUid == null || eventUid == rowUid) return null
        return rowUidEventId?.takeIf { it != shortcutId }
    }
```

- [ ] **Step 4: `Repository.linkedWithoutUid`**

Below `takenEvents`:

```kotlin
    /** Appointments linked on this device whose UID is not known yet. */
    suspend fun linkedWithoutUid(): List<AppointmentEntry> = withContext(Dispatchers.IO) {
        helper.readableDatabase
            .rawQuery("SELECT * FROM appointments WHERE calendar_event_id IS NOT NULL AND event_uid IS NULL", null)
            .use { c -> allAppointments(c) }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest" --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: PASS

- [ ] **Step 6: Reading back — relink, and write only the slot**

In `reconcile` in `CallsheetViewModel.kt`, directly after `val event = located?.event` and before `Appointment.reconcile(…)`, handle a shortcut whose event carries a different UID than the row:

```kotlin
        // The shortcut's event carries another UID than the row, and the row's
        // UID names a different event on this device: that one is the
        // appointment's. The copy behind the shortcut is a duplicate — written
        // by this device before the row learnt its UID — and goes.
        if (!rowWinsOnly && located != null && entry.eventUid != null && event?.uid != entry.eventUid) {
            val rowUid = entry.eventUid
            val found = calendarLookup { CalendarStore.findByUid(context, rowUid) }.getOrElse { return null }
            val target = Appointment.relinkTo(rowUid, event?.uid, located.eventId, found)
            if (target != null) {
                if (preferences.calendarEnabled && CalendarStore.canWrite(context)) {
                    CalendarStore.delete(context, located.eventId)
                }
                repo.setCalendarLink(entry.id, target, null, null, null)
                return null
            }
        }
```

The line `val uid = if (rowWinsOnly || event == null) null else Appointment.uidToTake(entry.eventUid, event.uid)` stays; `uidToTake` now only answers for a row without a UID. Replace its comment with:

```kotlin
        // A row without a UID takes the one its event carries — a carried-over
        // appointment, or an event DAVx5 has uploaded since. A row that has one
        // keeps it (see Appointment.uidToTake).
```

Replace the `is Reconcile.TakeEvent` branch:

```kotlin
            is Reconcile.TakeEvent -> {
                // Only time and place come from the calendar, written onto the
                // row as it stands now — not onto the entry read before the
                // lookup, or a note or contact saved in between would be undone.
                val fresh = repo.appointment(entry.id) ?: return null
                repo.saveAppointment(
                    fresh.copy(
                        startsAt = Clock.format(outcome.slot.startMillis),
                        endsAt = outcome.slot.endMillis?.let { Clock.format(it) },
                        location = outcome.slot.location,
                        eventUid = fresh.eventUid ?: uid,
                    )
                )
                rememberSeen(entry.id, located!!.eventId, event!!)
            }
```

- [ ] **Step 7: Saving — keep the shortcut on a failed update, and require write access**

In `saveAppointment`:

1. Replace `val calendarUsable = preferences.calendarEnabled && readable && !calendarUnreadable` with:

```kotlin
            val writable = CalendarStore.canWrite(context)
            val calendarUsable = preferences.calendarEnabled && readable && writable && !calendarUnreadable
```

   and in the `calendarHint` `when`, change the condition `!readable ->` to `!readable || !writable ->` (the message stays).

2. In `is SavePlan.Update`, the success line becomes (no UID is traded any more; `uidToTake` returns null for a row that has one):

```kotlin
                    entry = entry.copy(eventUid = entry.eventUid ?: Appointment.uidToTake(null, located?.event?.uid))
```

3. Add `var keepShortcut: Long? = null` next to `var dropLink = false`, with the comment `// The event is still there, but what this device saw in it is stale: keep the shortcut, forget S.` In the `else` branch of the failed update ("Still there but not writable, or not readable"), replace `dropLink = true` with:

```kotlin
                        keepShortcut = plan.eventId
                        // Taken now if the event had a UID the row lacks — without
                        // one, a lost shortcut could never be found again.
                        afterFailure.getOrNull()?.uid?.let { found ->
                            Appointment.uidToTake(entry.eventUid, found)?.let { entry = entry.copy(eventUid = it) }
                        }
```

   and replace that branch's comment's second sentence onward with: `This device keeps its shortcut and forgets what it saw, so the next opening sees the event for the first time and lets the row win.`

4. In the `when` after `repo.saveAppointment(entry)`, add a last branch after `dropLink -> …`:

```kotlin
                else -> keepShortcut?.let { repo.setCalendarLink(entry.id, it, null, null, null) }
```

5. At the end of the coroutine, after `loadDetail(draft.placeId)`, add:

```kotlin
            // Up at once: until the row reaches the server, a device that gets the
            // moved event through DAVx5 first would take the calendar's time onto
            // its older row — and that row, stamped newer, would win.
            syncNow()
```

- [ ] **Step 8: Removing — say when the event stays, and sync**

In `removeAppointment`:

1. Replace `lookup?.getOrNull()?.let { CalendarStore.delete(getApplication(), it.eventId) }` with:

```kotlin
            val deleted = lookup?.getOrNull()?.let { CalendarStore.delete(getApplication(), it.eventId) }
```

2. Replace the whole `if (lookup?.isFailure == true && preferences.calendarEnabled) { … }` block with:

```kotlin
            val linked = entry.eventUid != null || entry.calendarEventId != null
            val hint = when {
                !preferences.calendarEnabled || !linked -> null
                // Spec: without read permission nothing is asked — but with the
                // calendar switched on, the missing permission is said.
                lookup == null -> "Termin entfernt. Ohne Zugriff auf den Kalender bleibt der Eintrag dort " +
                    "stehen — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                lookup.isFailure -> "Termin entfernt. Der Kalender ließ sich nicht lesen — den Eintrag dort bitte selbst löschen."
                deleted == false -> "Termin entfernt. Der Kalendereintrag ließ sich nicht löschen — bitte dort selbst löschen."
                else -> null
            }
            hint?.let { text -> _state.update { it.copy(hint = text) } }
```

3. After `loadDetail(entry.placeId)`, add `syncNow()`.

- [ ] **Step 9: Capture missing UIDs after a sync**

Below `followCalendar`, add:

```kotlin
    /**
     * Takes the UID from the event of every appointment linked on this device
     * without one — above all the ones carried over from 1.3.x. Until a row has
     * its UID, another device cannot find the event and would create a second
     * one when the appointment is changed there.
     *
     * Runs after a sync, never before: taking a UID stamps the row as changed,
     * and a row older than the server's would then win over it.
     *
     * Returns how many rows took a UID. A calendar that cannot be read skips the row.
     */
    private suspend fun captureMissingUids(): Int {
        val context = getApplication<Application>()
        if (!CalendarStore.canRead(context)) return 0
        var taken = 0
        for (entry in repo.linkedWithoutUid()) {
            val eventId = entry.calendarEventId ?: continue
            val uid = calendarLookup { CalendarStore.read(context, eventId) }.getOrNull()?.uid
            Appointment.uidToTake(null, uid)?.let {
                repo.setEventUid(entry.id, it)
                taken++
            }
        }
        return taken
    }
```

In `syncNow`, replace

```kotlin
            if (result is SyncResult.Ok) {
                refreshList()
                loadToday()
            }
```

with:

```kotlin
            if (result is SyncResult.Ok) {
                refreshList()
                loadToday()
                // The UIDs taken here go up with the next run, started at once.
                if (captureMissingUids() > 0) syncNow()
            }
```

(`running` is already false at that point, so the second run starts; it captures nothing more and ends.)

- [ ] **Step 10: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt docs/superpowers/plans/2026-09-14-multiple-appointments.md
git commit -m "Mehrere Geräte: UID nur übernehmen, wenn keine da ist, Kopien ablösen, nach Speichern abgleichen"
```

### Additions to Task 13 (on the phone), after Task 14

With two phones carrying the same Infomaniak calendar:

6. **The calendar arriving before the row.** On phone A change an appointment's time *and* its Notiz. Let DAVx5 synchronise phone B, open the business on B. Expected: B shows A's new time and A's Notiz — not B's old one — on both phones after a sync.
7. **A carried-over appointment edited on the other phone.** Right after installing 1.4.0 on both phones, change a carried-over appointment (one that had a calendar entry under 1.3.1) on the phone that did *not* create the entry. Let both phones and DAVx5 synchronise, open the business on both. Expected: one calendar entry for it, not two, and opening it again on either phone changes nothing.
8. **No permission, calendar on.** With the calendar switched on in the app, revoke calendar permission, remove a linked appointment. Expected: the hint that the entry stays in the calendar.
