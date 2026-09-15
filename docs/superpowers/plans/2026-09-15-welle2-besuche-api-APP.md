# Wave 2 — Visits through the Infomaniak API — Implementation Plan (APP only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A visit („Vor Ort") is no longer written into the device calendar by the app. The app stores title and invitation on the row, the server creates, moves and removes the event through the Infomaniak API and sends the invitation; the app shows where the visit stands and still reads changes made in the calendar back — reading only.

**Architecture:** Schema 8 adds `title`, `invite_email` (app-owned, travel up), `calendar_state`, `calendar_error` (server-owned, come down only, taken whatever `updated_at` says) and `calendar_seen_title` (local). Every rule — default title, address check, the invitation following the contact person, plan without a calendar step, when a visit is read back, first sight taking nothing, an invited visit missing from the calendar, the status line, the cancellation notice, the title in the read-back — is a pure function in `calling/Appointment.kt` and tested there. The view model routes visits around every calendar write: `saveVisit`, a visit branch in `removeAppointment`, and visit guards in `reconcile`, `followCalendar` and `captureMissingUids`. Callbacks keep their path unchanged.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `CalendarContract`, JUnit 4 + Robolectric, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-15-visits-via-infomaniak-api-design.md`

## Global Constraints

- **App repository only:** `~/code/tm-services-automate/caller-app/app`, branch `main`. Kotlin sources under `app/src/main/java/io/github/amadeusb/callsheet/` (written `…/` below), tests under `app/src/test/java/io/github/amadeusb/callsheet/` (written `test/…` below). The server (`../server`: migration `010-visits-via-api.sql`, `infomaniak_events`, `pushCalendar`) is done by someone else — nothing in this plan touches it.
- **After the business-addresses plan.** `docs/superpowers/plans/2026-09-15-welle1-adressen-server-und-app.md` is implemented first and is taken as given here: database version 7 (`business_addresses`, `contacts.address_id`, the local table `removed_main_addresses`), `MigrationTest.createVersionSix()`, `AppointmentTest.person(id, addressId)`, the rewritten `SyncStore.apply`/`applyTombstone` (`stoneWins`, `searchStale`), `openSheet` reading `repo.contacts`/`repo.addresses`, `updateAppointmentDraft(incoming)` going through `withPlace`, `Appointment.placeAfterContactChange(previous, incoming, presetFor)`, the place chips in `AppointmentSheet`, `State.detailAddresses`, `Preferences.refetchedForAddresses`. This plan needs no refetch of its own: its synchronised columns are new on rows the device already has, and a server row that carries them arrives with the next change to that row.
- **Start on a clean tree.** Nothing of another session's uncommitted work goes into any commit of this plan.
- **Database version 7 → 8.** `ALTER TABLE appointments ADD COLUMN` for `title`, `invite_email`, `calendar_state`, `calendar_error`, `calendar_seen_title`. All nullable.
- **Server-owned columns:** `calendar_state`, `calendar_error` — and `event_uid` on a visit. The app never sends `calendar_state` or `calendar_error`; `SyncStore.apply` takes them from the server even where it otherwise keeps the local row. A visit's `event_uid` still goes up like any column; the server ignores it.
- **Local-only columns:** `dirty`, `contact_version`, `calendar_event_id`, `calendar_seen_*` — now including `calendar_seen_title`.
- **Values of `calendar_state`:** `pending`, `ok`, `error`; null for callbacks, for visits saved before this version, and against a server without the feature.
- **Default title, byte for byte the server's:** `Erstgespräch KI bei <Firma> – Christoph Bauer` (en dash U+2013, `<Firma>` = `businesses.name` trimmed); with an empty or blank name `Erstgespräch KI – Christoph Bauer`. `title` null means this default. Title and location are trimmed when saved.
- **Pending only with a sync server.** „Wird im Kalender angelegt/aktualisiert …" shows only where a server is set up (`preferences.serverUrl` not null — the getter turns blank into null); without one the row could never leave `pending`.
- **An invited visit is never deleted by the read-back.** Missing from the calendar, still ahead, with `invite_email`: nothing is deleted or stored; the detail view shows „Im Kalender nicht mehr gefunden" with „Termin entfernen", which goes through the normal removal dialog. Without an invitation: deleted with a tombstone, as today.
- **The invitee sees title, time and place — never the note, never a phone number.** Sheet text: „Der Eingeladene sieht Titel, Zeit und Ort, nicht die Notiz."
- **After saving a visit:** sync at once and once more after 5 seconds (`Appointment.RESYNC_AFTER_SAVE_MILLIS = 5_000L`).
- **Callbacks are unchanged:** written into the device calendar, `Rückruf <Firma> – <Notiz>`, tick when done. Every existing callback test stays green without edits.
- **Robolectric has no calendar provider, and there is no view model test harness** (the spec says so too). Decisions live in `Appointment` and are tested there; the view model only carries them out. View-model behaviour — saving syncs twice, removing an invited visit asks first, the missing-invited hint — is covered by `assembleDebug` and the phone test (Task 8), not by unit tests.
- **Tests:** `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`; build check `./gradlew assembleDebug`. Run from the repository root.
- **Language:** code, identifiers, comments, docs in English; UI text German with correct umlauts and „…" quotes. Test names in English; fixture method stays `fun aufbau()`.
- **Timestamps:** ISO-8601 with offset via `Clock.format`, compared through `Clock.millis`.
- **State writes** in new view model code use `_state.update { … }`.
- **Every task leaves the project building and all tests green. Commit after every task,** listing files explicitly. German commit messages in the tone of `git log`, no attribution lines.
- **Release is outward-facing:** after the server is deployed, ask the user before releasing (Task 8).
- **Rollout step 1 is mandatory, not a tidy-up:** before the new app runs against the new server, the test visits in the app and their events in the calendar are deleted (spec, Rollout 1). An old visit has no `infomaniak_events` row and an event the app wrote itself; saved again, the server would create a second event beside it. Task 8 does not go on until the user confirms it is done.

---

## File Structure

| File | Change |
|---|---|
| `…/data/Database.kt` | `VERSION = 8`, `COLUMNS_APPOINTMENTS_8` on create and upgrade |
| `…/data/Models.kt` | `CalendarState`; `AppointmentEntry.title`, `.inviteEmail`, `.calendarState`, `.calendarError`, `.seenTitle`, `.dirty` |
| `…/data/Repository.kt` | read the new columns; `saveAppointment` writes `title`, `invite_email`; `setCalendarLink(seenTitle)` |
| `…/sync/Rows.kt` | `SERVER_OWNED`; `calendar_seen_title` local; `toJson` leaves both out |
| `…/sync/SyncStore.kt` | `takeServerOwned`; `RemovedAppointment.kind` |
| `…/calling/Appointment.kt` | `Slot.title`; titles, invitation, `inviteAfterContactChange`, `planVisit`, `readsBack`, `awaitsServer`, `Reconcile.MissingInvited`, `reconcile(visit, invited)`, `CalendarLine`, `calendarLine`, `cancellationNotice`, `RESYNC_AFTER_SAVE_MILLIS` |
| `…/CallsheetViewModel.kt` | draft fields; `State.detailMissingInCalendar`; `openSheet`, `loadBusy`, `updateAppointmentDraft`/`withInvite`, `saveVisit`, `removeAppointment`, `reconcile`, `reconcileAppointments`, `followCalendar`, `captureMissingUids`, detail reload after sync |
| `…/ui/AppointmentSheet.kt` | „Titel", „Einladung senden" for a visit; the field area's height |
| `…/ui/BusinessDetail.kt` | status line under a visit, „Im Kalender nicht mehr gefunden" with „Termin entfernen"; cancellation notice in the removal dialog |
| `…/MainActivity.kt` | passes `syncConfigured` and `missingInCalendar` to the detail view |
| tests | `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `RepositoryTest`, `AppointmentTest` |
| `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md` | docs |

---

### Task 0: Preconditions

**Files:** none.

- [ ] **Step 1: Clean tree on `main`**

Run: `git status --short && git branch --show-current`
Expected: no output from `status`, branch `main`. Otherwise stop and ask the coordinator session „caller-app-34".

- [ ] **Step 2: Schema number, and the addresses plan in place**

Run: `grep -n "const val VERSION" app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt`
Expected: `const val VERSION = 7`. If it is 6, the business-addresses plan is not implemented yet — stop and ask. If it is higher, stop and ask: the spec says 7 → 8.

Run: `grep -n "fun placeAfterContactChange\|fun createVersionSix\|private fun person(id: String, addressId: String?)\|TABLE_REMOVED_MAIN_ADDRESSES = \|stoneWins" app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncStore.kt`
Expected: at least one line from each of the five files. Anything missing: stop and ask — the anchors below are written against it.

- [ ] **Step 3: Which CHANGELOG section**

Run: `git tag | grep -x v1.5.0; grep -n "^versionName" version.properties`
If there is no tag `v1.5.0`, the unreleased `## 1.5.0` section is extended in Task 7. If the tag exists, Task 7 adds a new `## 1.6.0` section above it. Note the result.

- [ ] **Step 4: Baseline**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

---

### Task 1: Schema 8, the entry and the repository

**Files:**
- Modify: `…/data/Database.kt`
- Modify: `…/data/Models.kt`
- Modify: `…/data/Repository.kt`
- Test: `test/MigrationTest.kt`, `test/SyncSchemaTest.kt`, `test/RepositoryTest.kt`

**Interfaces:**
- Produces: `enum class CalendarState(val key: String) { PENDING, OK, ERROR }` with `CalendarState.fromKey(s: String?): CalendarState?`; `AppointmentEntry` fields `title: String? = null`, `inviteEmail: String? = null`, `calendarState: CalendarState? = null`, `calendarError: String? = null`, `seenTitle: String? = null`, `dirty: Boolean = false`; `Repository.setCalendarLink(id, eventId, seenStartsAt, seenEndsAt, seenLocation, seenTitle: String? = null)`.

- [ ] **Step 1: Write the failing migration tests**

In `test/MigrationTest.kt`, below `createVersionSix()` (added by the business-addresses plan), add a fixture; and above `// --- and a database that never had to migrate at all ---`, add a test:

```kotlin
    /**
     * The version 7 schema, as the business-addresses release builds it:
     * version 6 with the address table, a contact's address and the local
     * table of removed main addresses. alt-1's visit has an event the app
     * wrote into the calendar itself. No carried-over address rows: the
     * upgrade to 8 touches appointments only.
     */
    private fun createVersionSeven() {
        createVersionSix()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            "CREATE TABLE business_addresses (id TEXT PRIMARY KEY, place_id TEXT NOT NULL, label TEXT, street TEXT, " +
                "postal_code TEXT, city TEXT, latitude REAL, longitude REAL, position INTEGER, " +
                "updated_at TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_business_addresses_place_id ON business_addresses(place_id)")
        db.execSQL("CREATE INDEX idx_business_addresses_dirty ON business_addresses(dirty)")
        db.execSQL("ALTER TABLE contacts ADD COLUMN address_id TEXT")
        db.execSQL("CREATE TABLE removed_main_addresses (place_id TEXT PRIMARY KEY)")
        db.execSQL(
            "UPDATE appointments SET kind = 'visit', event_uid = 'legacy-alt-1', calendar_event_id = 4711 " +
                "WHERE id = 'legacy-alt-1'"
        )
        db.version = 7
        db.close()
    }
```

```kotlin
    // --- from version 7, the road business-address devices are on -----------

    @Test
    fun `an upgrade from version seven adds the invitation and calendar columns empty`() {
        createVersionSeven()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT starts_at, event_uid, calendar_event_id, dirty, " +
                "title, invite_email, calendar_state, calendar_error, calendar_seen_title " +
                "FROM appointments WHERE id = 'legacy-alt-1'",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-09-10T14:00:00+02:00", c.getString(0))
            assertEquals("legacy-alt-1", c.getString(1))
            assertEquals(4711L, c.getLong(2))
            // Nothing new to tell the server.
            assertEquals(0, c.getInt(3))
            for (i in 4..8) assertTrue("column $i not null", c.isNull(i))
        }
    }
```

In the existing test `a fresh database and an upgraded one have the same appointments table`, below `assertTrue("done_at" in fresh)`, add:

```kotlin
        assertTrue("title" in fresh)
        assertTrue("calendar_state" in fresh)
        assertTrue("calendar_seen_title" in fresh)
```

In `test/SyncSchemaTest.kt`, below `appointments carry a kind and a completion`, add:

```kotlin
    @Test
    fun `appointments carry a title, an invitation and the server's calendar state`() {
        val appointments = columns("appointments")
        for (column in listOf("title", "invite_email", "calendar_state", "calendar_error", "calendar_seen_title")) {
            assertTrue("$column missing", appointments.contains(column))
        }
    }
```

- [ ] **Step 2: Write the failing repository tests**

In `test/RepositoryTest.kt`, add `import io.github.amadeusb.callsheet.data.CalendarState` to the imports, and below `a callback is stored without a place, whatever the entry carries` add:

```kotlin
    @Test
    fun `a visit keeps its title and invitation, a callback stores neither`() = runTest {
        repo.saveAppointment(
            visit("A-1", "t-1", "2026-09-16T09:00:00+02:00").copy(title = "Erstgespräch", inviteEmail = "info@example.org")
        )
        repo.saveAppointment(
            callback("R-1", "t-1", "2026-09-15T09:00:00+02:00").copy(title = "Erstgespräch", inviteEmail = "info@example.org")
        )

        val visit = repo.appointment("A-1")!!
        assertEquals("Erstgespräch", visit.title)
        assertEquals("info@example.org", visit.inviteEmail)
        assertTrue(visit.dirty)
        assertNull(repo.appointment("R-1")!!.title)
        assertNull(repo.appointment("R-1")!!.inviteEmail)
    }

    @Test
    fun `switching the invitation off clears the address`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00").copy(inviteEmail = "info@example.org"))

        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00"))

        assertNull(repo.appointment("A-1")!!.inviteEmail)
    }

    @Test
    fun `the server's calendar state is read, and saving leaves it alone`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00"))
        execute(
            "UPDATE appointments SET calendar_state = 'error', calendar_error = 'Im Kalender gelöscht', dirty = 0 " +
                "WHERE id = 'A-1'"
        )

        repo.saveAppointment(repo.appointment("A-1")!!.copy(note = "Angebot"))

        val stored = repo.appointment("A-1")!!
        assertEquals(CalendarState.ERROR, stored.calendarState)
        assertEquals("Im Kalender gelöscht", stored.calendarError)
    }

    @Test
    fun `the title this device saw goes with the link, and away with it`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00"))

        repo.setCalendarLink("A-1", 4711L, "2026-09-16T09:00:00+02:00", null, null, seenTitle = "Erstgespräch")
        assertEquals("Erstgespräch", repo.appointment("A-1")!!.seenTitle)

        repo.setCalendarLink("A-1", null, null, null, null)
        assertNull(repo.appointment("A-1")!!.seenTitle)
    }
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: compilation FAILS — `CalendarState`, `title`, `inviteEmail`, `seenTitle` unresolved.

- [ ] **Step 4: The database**

In `…/data/Database.kt`:

In `onCreate`, below `db.execSQL(TABLE_REMOVED_MAIN_ADDRESSES)` (the last line the business-addresses plan added there), add:

```kotlin
        for (sql in COLUMNS_APPOINTMENTS_8) db.execSQL(sql)
```

In `onUpgrade`, below the closing brace of `if (old < 7) { … }`, add:

```kotlin
        if (old < 8) {
            // Nothing to carry over and nothing to mark: the columns arrive
            // empty. A visit saved before has no calendar_state and shows
            // nothing about it.
            for (sql in COLUMNS_APPOINTMENTS_8) db.execSQL(sql)
        }
```

Change `const val VERSION = 7` to `const val VERSION = 8`.

Below `TABLE_REMOVED_MAIN_ADDRESSES` in the companion, add:

```kotlin
        /**
         * Schema 8: visits reach the calendar through the server. `title` and
         * `invite_email` are the app's; `calendar_state` and `calendar_error`
         * the server's (see Rows.SERVER_OWNED). `calendar_seen_title` is local,
         * next to the other `calendar_seen_` columns. Added by ALTER on both
         * roads, for the reason COLUMNS_APPOINTMENTS_6 gives. All nullable, as
         * every new synchronised column is.
         */
        private val COLUMNS_APPOINTMENTS_8 = listOf(
            "ALTER TABLE appointments ADD COLUMN title TEXT",
            "ALTER TABLE appointments ADD COLUMN invite_email TEXT",
            "ALTER TABLE appointments ADD COLUMN calendar_state TEXT",
            "ALTER TABLE appointments ADD COLUMN calendar_error TEXT",
            "ALTER TABLE appointments ADD COLUMN calendar_seen_title TEXT",
        )
```

- [ ] **Step 5: The model**

In `…/data/Models.kt`, above the KDoc of `data class AppointmentEntry`, add:

```kotlin
/**
 * Where a visit stands on its way into the calendar, as the server says.
 * Stored as [key]. Null — no key, or one this app does not know — for a
 * callback, for a visit saved before schema 8, and against a server without
 * the feature.
 */
enum class CalendarState(val key: String) {
    PENDING("pending"),
    OK("ok"),
    ERROR("error");

    companion object {
        fun fromKey(s: String?): CalendarState? = entries.firstOrNull { it.key == s }
    }
}
```

In the KDoc of `AppointmentEntry`, replace

```
 * [eventUid] names the linked calendar event on every device carrying the
 * shared calendar. [calendarEventId] and the `seen` fields describe this
 * device's calendar only and never travel.
```

with

```
 * [eventUid] names the linked calendar event on every device carrying the
 * shared calendar — for a visit the server sets it, for a callback the app.
 * [calendarEventId] and the `seen` fields describe this device's calendar only
 * and never travel.
```

At the end of the parameter list, below `val doneAt: String? = null,`, add:

```kotlin
    /** A visit's calendar title as typed. Null means Appointment.defaultTitle. Always null for a callback. */
    val title: String? = null,
    /** Who a visit invites. Null means no invitation. Always null for a callback. */
    val inviteEmail: String? = null,
    /** The server's. Saving never writes it. */
    val calendarState: CalendarState? = null,
    /** The server's words behind [CalendarState.ERROR]. */
    val calendarError: String? = null,
    /** The title this device last saw in a visit's event. Local, like the other `seen` fields. */
    val seenTitle: String? = null,
    /** Waiting to go up. Read only: saving ignores it and always marks. */
    val dirty: Boolean = false,
```

- [ ] **Step 6: The repository**

In `…/data/Repository.kt`, in `saveAppointment`, below `put("location", if (entry.kind == AppointmentKind.CALLBACK) null else entry.location)`, add:

```kotlin
            // A callback has neither: its title is built from its note, and
            // nobody is invited to a phone call. A null title is the default,
            // a null address no invitation — both written, so switching the
            // invitation off clears it.
            put("title", if (entry.kind == AppointmentKind.CALLBACK) null else entry.title)
            put("invite_email", if (entry.kind == AppointmentKind.CALLBACK) null else entry.inviteEmail)
```

Replace `setCalendarLink` with:

```kotlin
    /**
     * Records which event this device links an appointment to, and what it
     * saw in that event — the title only for a visit. A null [eventId] drops
     * the link. Local only: no `updated_at`, no mark — none of these columns
     * travels.
     */
    suspend fun setCalendarLink(
        id: String,
        eventId: Long?,
        seenStartsAt: String?,
        seenEndsAt: String?,
        seenLocation: String?,
        seenTitle: String? = null,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (eventId == null) putNull("calendar_event_id") else put("calendar_event_id", eventId)
            put("calendar_seen_starts_at", if (eventId == null) null else seenStartsAt)
            put("calendar_seen_ends_at", if (eventId == null) null else seenEndsAt)
            put("calendar_seen_location", if (eventId == null) null else seenLocation)
            put("calendar_seen_title", if (eventId == null) null else seenTitle)
        }
        helper.writableDatabase.update("appointments", values, "id = ?", arrayOf(id))
        notifyChanged()
    }
```

In `appointmentFromCursor`, below `doneAt = c.text("done_at"),`, add:

```kotlin
        title = c.text("title"),
        inviteEmail = c.text("invite_email"),
        calendarState = CalendarState.fromKey(c.text("calendar_state")),
        calendarError = c.text("calendar_error"),
        seenTitle = c.text("calendar_seen_title"),
        dirty = c.int("dirty") == 1,
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt \
  app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt \
  app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
  app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt \
  app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt \
  app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Schema 8: Titel, Einladung und Kalenderstand des Servers am Termin"
```

---

### Task 2: The server's columns in the sync

**Files:**
- Modify: `…/sync/Rows.kt`
- Modify: `…/sync/SyncStore.kt`
- Test: `test/SyncSchemaTest.kt`, `test/SyncStoreTest.kt`

**Interfaces:**
- Consumes: the schema 8 columns (Task 1).
- Produces: `Rows.SERVER_OWNED: List<String>` = `listOf("calendar_state", "calendar_error")`; `RemovedAppointment(id: String, calendarEventId: Long?, eventUid: String?, kind: AppointmentKind = AppointmentKind.VISIT)`.

- [ ] **Step 1: Write the failing tests**

In `test/SyncSchemaTest.kt`, at the end of the class, add:

```kotlin
    @Test
    fun `the server's calendar state comes in, what this device saw of a title does not`() {
        val row = JSONObject().apply {
            put("id", "A1")
            put("calendar_state", "ok")
            put("calendar_error", JSONObject.NULL)
            put("calendar_seen_title", "Erstgespräch")
        }

        val values = Rows.toValues(row, row.keys().asSequence().toSet())

        assertEquals("ok", values.getAsString("calendar_state"))
        assertTrue(values.containsKey("calendar_error"))
        assertFalse(values.containsKey("calendar_seen_title"))
    }
```

In `test/SyncStoreTest.kt`, add `import io.github.amadeusb.callsheet.data.AppointmentKind` to the imports. Below the helper `terminJson`, add two helpers:

```kotlin
    private fun einBesuch(
        id: String,
        zeit: String,
        dirty: Int,
        state: String? = null,
        uid: String? = null,
        kind: String? = "visit",
    ) = schreibe(
        "INSERT INTO appointments (id, place_id, starts_at, updated_at, event_uid, calendar_state, kind, dirty) " +
            "VALUES ('$id', 'P1', '2026-09-10T14:00:00+02:00', '$zeit', ${uid?.let { "'$it'" } ?: "NULL"}, " +
            "${state?.let { "'$it'" } ?: "NULL"}, ${kind?.let { "'$it'" } ?: "NULL"}, $dirty)"
    )

    /** The first row of [sql], every column as text. */
    private fun zeile(sql: String): List<String?> =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { c ->
            assertTrue(c.moveToFirst())
            (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }
        }
```

At the end of the class, above `private fun zahl`, add:

```kotlin
    @Test
    fun `pending never carries the server's calendar columns or the seen title`() {
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 1, state = "ok", uid = "abc@infomaniak")
        schreibe(
            "UPDATE appointments SET title = 'Erstgespräch', invite_email = 'info@example.org', " +
                "calendar_error = 'x', calendar_seen_title = 'y' WHERE id = 'A1'"
        )

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("Erstgespräch", row.getString("title"))
        assertEquals("info@example.org", row.getString("invite_email"))
        for (column in listOf("calendar_state", "calendar_error", "calendar_seen_title")) {
            assertFalse("$column must not travel", row.has(column))
        }
    }

    @Test
    fun `the server's calendar state and UID are taken onto a visit edited here since, which stays marked`() {
        // Edited on this phone after the server created the event: the local row is newer.
        einBesuch("A1", "2026-09-07T11:00:00+02:00", dirty = 1, state = "pending")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00")
            .put("kind", "visit").put("event_uid", "abc@infomaniak")
            .put("calendar_state", "ok").put("calendar_error", JSONObject.NULL)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("ok", "abc@infomaniak", "2026-09-10T14:00:00+02:00", "2026-09-07T11:00:00+02:00", "1"),
            zeile("SELECT calendar_state, event_uid, starts_at, updated_at, dirty FROM appointments WHERE id = 'A1'"),
        )
        assertEquals(listOf("A1"), applied.written)
    }

    @Test
    fun `the server's calendar state is taken at a standstill`() {
        // The server writes the state without moving updated_at.
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 0, state = "pending")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00")
            .put("kind", "visit").put("calendar_state", "error").put("calendar_error", "Im Kalender gelöscht")

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("error", "Im Kalender gelöscht"),
            zeile("SELECT calendar_state, calendar_error FROM appointments WHERE id = 'A1'"),
        )
    }

    @Test
    fun `a callback's UID is not taken from an older server row`() {
        einBesuch("R1", "2026-09-07T11:00:00+02:00", dirty = 1, uid = "mine", kind = "callback")
        val incoming = terminJson("R1", "2026-09-07T10:00:00+02:00").put("kind", "callback").put("event_uid", "theirs")

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(listOf("mine"), zeile("SELECT event_uid FROM appointments WHERE id = 'R1'"))
    }

    @Test
    fun `a server that sends no calendar state clears nothing on a newer local row`() {
        // The local row is newer and waiting to go up, so the incoming, older row
        // replaces nothing and only takeServerOwned looks at it: an absent state
        // and a null UID must not clear what the server said before.
        einBesuch("A1", "2026-09-07T11:00:00+02:00", dirty = 1, state = "ok", uid = "abc@infomaniak")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00").put("kind", "visit").put("event_uid", JSONObject.NULL)

        val applied = store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(
            listOf("ok", "abc@infomaniak", "2026-09-07T11:00:00+02:00", "1"),
            zeile("SELECT calendar_state, event_uid, updated_at, dirty FROM appointments WHERE id = 'A1'"),
        )
        assertEquals(emptyList<String>(), applied.written)
    }

    @Test
    fun `a remote tombstone reports the kind of the removed appointment`() {
        einBesuch("R1", "2026-09-07T10:00:00+02:00", dirty = 0, uid = "R1", kind = "callback")
        val stone = JSONObject().apply {
            put("table_name", "appointments"); put("row_id", "R1"); put("deleted_at", "2026-09-07T11:00:00+02:00")
        }

        val applied = store.apply(leereAntwort().put("deleted", JSONArray(listOf(stone))))

        assertEquals(AppointmentKind.CALLBACK, applied.removed.single().kind)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: compilation FAILS — `kind` on `RemovedAppointment` unresolved.

- [ ] **Step 3: Rows**

In `…/sync/Rows.kt`, replace the `LOCAL_ONLY` declaration with:

```kotlin
    private val LOCAL_ONLY = setOf(
        "dirty", "contact_version", "calendar_event_id",
        "calendar_seen_starts_at", "calendar_seen_ends_at", "calendar_seen_location", "calendar_seen_title",
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
```

In `toJson`, replace `if (name in LOCAL_ONLY) continue` with:

```kotlin
            if (name in LOCAL_ONLY || name in SERVER_OWNED) continue
```

- [ ] **Step 4: SyncStore**

In `…/sync/SyncStore.kt`, add `import io.github.amadeusb.callsheet.data.AppointmentKind` and replace the `RemovedAppointment` declaration with:

```kotlin
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
```

In `applyRow` (the business-addresses plan leaves it as it was; its changes are in `apply` and `applyTombstone`), replace the block

```kotlin
        if (local != null && !Merge.isNewer(remoteAt, local.optString("updated_at", null))) {
            // The incoming version is no newer, so it replaces nothing. It may
            // still carry columns this row has never had a value for.
            val filled = Merge.isSameMoment(remoteAt, local.optString("updated_at", null)) &&
                fillGaps(db, table, id, local, row, tableColumns)
```

with

```kotlin
        if (local != null && !Merge.isNewer(remoteAt, local.optString("updated_at", null))) {
            // The incoming version is no newer, so it replaces nothing — except
            // what only the server writes. See takeServerOwned.
            val owned = table == "appointments" && takeServerOwned(db, id, row)
            // It may still carry columns this row has never had a value for.
            val filled = Merge.isSameMoment(remoteAt, local.optString("updated_at", null)) &&
                fillGaps(db, table, id, local, row, tableColumns)
```

and in the same block replace `return filled` with `return filled || owned`.

Below `fillGaps`, add:

```kotlin
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
```

In `applyTombstone` — in its rewritten form from the business-addresses plan, `applyTombstone(db, stone, searchStale)`, inside `val link = if (table == "appointments") { … }` below `if (localAt == null || !stoneWins) return null` — replace

```kotlin
            db.rawQuery("SELECT calendar_event_id, event_uid FROM appointments WHERE id = ?", arrayOf(id)).use { c ->
                c.moveToFirst()
                RemovedAppointment(id, if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getString(1))
            }
```

with

```kotlin
            db.rawQuery("SELECT calendar_event_id, event_uid, kind FROM appointments WHERE id = ?", arrayOf(id)).use { c ->
                c.moveToFirst()
                RemovedAppointment(
                    id,
                    if (c.isNull(0)) null else c.getLong(0),
                    if (c.isNull(1)) null else c.getString(1),
                    AppointmentKind.fromKey(if (c.isNull(2)) null else c.getString(2)),
                )
            }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: PASS — including the existing `a remote tombstone removes an appointment and reports the link it had` (its row has no kind, which reads as a visit, the default).

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/sync/Rows.kt \
  app/src/main/java/io/github/amadeusb/callsheet/sync/SyncStore.kt \
  app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt \
  app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt
git commit -m "Sync: Kalenderstand und UID eines Besuchs kommen vom Server, auch auf neuere Zeilen"
```

---

### Task 3: The rules for a visit

**Files:**
- Modify: `…/calling/Appointment.kt`
- Modify: `…/CallsheetViewModel.kt` (the four `AppointmentDraft` fields only — `inviteAfterContactChange` reads them)
- Test: `test/AppointmentTest.kt`

**Interfaces:**
- Consumes: `AppointmentEntry.title`, `.inviteEmail`, `.calendarState`, `.calendarError`, `.dirty`, `CalendarState` (Task 1); `AppointmentDraft` and its `import io.github.amadeusb.callsheet.AppointmentDraft` in `Appointment.kt` (business-addresses plan, Task 12).
- Produces: `AppointmentDraft.title: String = ""`, `.invite: Boolean = false`, `.inviteEmail: String = ""`, `.inviteError: String? = null`; and, all on `object Appointment` unless noted:
  - `const val RESYNC_AFTER_SAVE_MILLIS: Long = 5_000L`
  - `const val INVALID_INVITE: String`
  - `fun defaultTitle(businessName: String): String`
  - `fun visitTitle(title: String?, businessName: String): String`
  - `fun titleToStore(typed: String, businessName: String): String?`
  - `fun inviteError(invite: Boolean, email: String): String?`
  - `fun inviteToStore(invite: Boolean, email: String): String?`
  - `fun inviteSuggestion(contact: Contact?): String`
  - `fun inviteAfterContactChange(previous: AppointmentDraft, incoming: AppointmentDraft, emailsFor: (contactId: String?) -> List<String>): String?` — the address to put into [incoming], or null to leave it as it is
  - `fun planVisit(startMillis: Long, endMillis: Long, busy: List<BusyInterval>, force: Boolean): SavePlan`
  - `fun readsBack(entry: AppointmentEntry): Boolean`
  - `fun awaitsServer(entry: AppointmentEntry): Boolean`
  - top-level `data class CalendarLine(val text: String, val error: Boolean = false, val offersRemoval: Boolean = false)` and `fun calendarLine(entry: AppointmentEntry, syncConfigured: Boolean, missing: Boolean = false): CalendarLine?`
  - `fun cancellationNotice(entry: AppointmentEntry): String?`

- [ ] **Step 1: Write the failing tests**

In `test/AppointmentTest.kt`, add these imports:

```kotlin
import io.github.amadeusb.callsheet.calling.CalendarLine
import io.github.amadeusb.callsheet.data.CalendarState
import io.github.amadeusb.callsheet.data.ContactEmail
```

At the end of the class, add:

```kotlin
    // --- visits through the server ---------------------------------------------

    // Not `person`: the business-addresses plan has `person(id, addressId)` in this class.
    private fun personWithEmails(vararg addresses: String) = Contact(
        id = "K-1", placeId = "P1", name = "Frau Meier", role = null, email = null, note = null,
        numbers = emptyList(), updatedAt = "2026-09-07T10:00:00+02:00",
        emails = addresses.mapIndexed { i, address -> ContactEmail("E-$i", address, i) },
    )

    @Test
    fun `a visit's default title names the business`() {
        assertEquals("Erstgespräch KI bei Elektro Meier – Christoph Bauer", Appointment.defaultTitle("Elektro Meier"))
        assertEquals("Erstgespräch KI bei Elektro Meier – Christoph Bauer", Appointment.defaultTitle(" Elektro Meier "))
        assertEquals("Erstgespräch KI bei Elektro Meier – Christoph Bauer", Appointment.visitTitle(null, "Elektro Meier"))
        assertEquals("Angebot besprechen", Appointment.visitTitle("Angebot besprechen", "Elektro Meier"))
    }

    @Test
    fun `a business without a name gets the default title without one`() {
        assertEquals("Erstgespräch KI – Christoph Bauer", Appointment.defaultTitle(""))
        assertEquals("Erstgespräch KI – Christoph Bauer", Appointment.defaultTitle("   "))
        assertNull(Appointment.titleToStore("Erstgespräch KI – Christoph Bauer", " "))
    }

    @Test
    fun `an empty title, or the preset left as it is, is saved as the default`() {
        assertNull(Appointment.titleToStore("  ", "Elektro Meier"))
        assertNull(Appointment.titleToStore("Erstgespräch KI bei Elektro Meier – Christoph Bauer", "Elektro Meier"))
        assertNull(Appointment.titleToStore(" Erstgespräch KI bei Elektro Meier – Christoph Bauer ", "Elektro Meier"))
        assertEquals("Angebot besprechen", Appointment.titleToStore(" Angebot besprechen ", "Elektro Meier"))
    }

    @Test
    fun `an invitation needs an address that looks like one`() {
        assertNull(Appointment.inviteError(invite = false, email = ""))
        assertNull(Appointment.inviteError(invite = true, email = " info@elektro-meier.de "))
        assertEquals(Appointment.INVALID_INVITE, Appointment.inviteError(invite = true, email = ""))
        assertEquals(Appointment.INVALID_INVITE, Appointment.inviteError(invite = true, email = "info@elektro-meier"))
        assertEquals(Appointment.INVALID_INVITE, Appointment.inviteError(invite = true, email = "info elektro@meier.de"))
    }

    @Test
    fun `switched off, no address is stored`() {
        assertNull(Appointment.inviteToStore(invite = false, email = "info@elektro-meier.de"))
        assertEquals("info@elektro-meier.de", Appointment.inviteToStore(invite = true, email = " info@elektro-meier.de "))
    }

    @Test
    fun `the invitation is preset to the contact person's first address`() {
        assertEquals("a@meier.de", Appointment.inviteSuggestion(personWithEmails("a@meier.de", "b@meier.de")))
        assertEquals("", Appointment.inviteSuggestion(personWithEmails()))
        assertEquals("", Appointment.inviteSuggestion(null))
    }

    private val emails = mapOf(
        "K-M" to listOf("a@meier.de", "b@meier.de"),
        "K-H" to listOf("h@huber.de"),
        "K-0" to emptyList(),
    )
    private val emailsFor = { contactId: String? -> emails[contactId].orEmpty() }
    private val invited = AppointmentDraft(
        placeId = "P1", startIso = "2026-09-10T14:00:00+02:00", minutes = 60, location = "",
        contactId = "K-M", invite = true, inviteEmail = "a@meier.de",
    )

    @Test
    fun `another contact person brings their first address where the previous one's was preselected`() {
        assertEquals("h@huber.de", Appointment.inviteAfterContactChange(invited, invited.copy(contactId = "K-H"), emailsFor))
        // Nobody before, nothing typed: the empty field was the preset.
        val nobody = invited.copy(contactId = null, inviteEmail = "")
        assertEquals("h@huber.de", Appointment.inviteAfterContactChange(nobody, nobody.copy(contactId = "K-H"), emailsFor))
        // A person without an address empties it — the invitation must not go to the previous person.
        assertEquals("", Appointment.inviteAfterContactChange(invited, invited.copy(contactId = "K-0"), emailsFor))
        assertEquals("", Appointment.inviteAfterContactChange(invited, invited.copy(contactId = null), emailsFor))
    }

    @Test
    fun `a typed or picked address stays when the contact person changes`() {
        val typed = invited.copy(inviteEmail = "chef@meier.de")
        assertNull(Appointment.inviteAfterContactChange(typed, typed.copy(contactId = "K-H"), emailsFor))
        // The person's second address, picked by chip, is a choice too.
        val second = invited.copy(inviteEmail = "b@meier.de")
        assertNull(Appointment.inviteAfterContactChange(second, second.copy(contactId = "K-H"), emailsFor))
    }

    @Test
    fun `the invitation's address follows nothing while it is off, the person stays, or it is a callback`() {
        val off = invited.copy(invite = false)
        assertNull(Appointment.inviteAfterContactChange(off, off.copy(contactId = "K-H"), emailsFor))
        assertNull(Appointment.inviteAfterContactChange(invited, invited.copy(note = "Angebot"), emailsFor))
        assertNull(Appointment.inviteAfterContactChange(invited, invited.copy(contactId = "K-H", inviteEmail = "x@y.de"), emailsFor))
        val callback = invited.copy(kind = AppointmentKind.CALLBACK)
        assertNull(Appointment.inviteAfterContactChange(callback, callback.copy(contactId = "K-H"), emailsFor))
    }

    @Test
    fun `a visit is planned without a calendar write and offers nothing to link`() {
        assertEquals(SavePlan.LocalOnly, Appointment.planVisit(slotStart, slotEnd, busy = emptyList(), force = false))
    }

    @Test
    fun `a visit still asks about a taken slot, and force saves it anyway`() {
        val taken = listOf(busy(14, 15, "Steuerbüro", eventId = 7L))

        assertTrue(Appointment.planVisit(slotStart, slotEnd, taken, force = false) is SavePlan.Conflict)
        assertEquals(SavePlan.LocalOnly, Appointment.planVisit(slotStart, slotEnd, taken, force = true))
    }

    @Test
    fun `a visit is read back only once the server put it into the calendar and nothing here waits to go up`() {
        // A pending or unsent visit is ahead of its event: taken from the calendar
        // on first sight, the old event would undo the edit.
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00").copy(eventUid = "abc@infomaniak", calendarState = CalendarState.OK)

        assertTrue(Appointment.readsBack(visit))
        assertFalse(Appointment.readsBack(visit.copy(eventUid = null)))
        assertFalse(Appointment.readsBack(visit.copy(eventUid = null, calendarEventId = 4711L)))
        assertFalse(Appointment.readsBack(visit.copy(calendarState = CalendarState.PENDING)))
        assertFalse(Appointment.readsBack(visit.copy(calendarState = null)))
        assertFalse(Appointment.readsBack(visit.copy(dirty = true)))
    }

    @Test
    fun `a callback is read back as before, by UID or by this device's link`() {
        val callback = entry("R-1", "2026-09-10T14:00:00+02:00").copy(kind = AppointmentKind.CALLBACK)

        assertFalse(Appointment.readsBack(callback))
        assertTrue(Appointment.readsBack(callback.copy(eventUid = "R-1")))
        assertTrue(Appointment.readsBack(callback.copy(calendarEventId = 4711L)))
    }

    @Test
    fun `opening a business syncs for a visit the server has not put into the calendar yet`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertTrue(Appointment.awaitsServer(visit.copy(calendarState = CalendarState.PENDING)))
        assertFalse(Appointment.awaitsServer(visit))
        assertFalse(Appointment.awaitsServer(visit.copy(calendarState = CalendarState.OK)))
        assertFalse(Appointment.awaitsServer(visit.copy(kind = AppointmentKind.CALLBACK, calendarState = CalendarState.PENDING)))
    }

    @Test
    fun `a visit says where it stands on its way into the calendar`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertNull(Appointment.calendarLine(visit, syncConfigured = true))
        assertEquals(CalendarLine("Wird im Kalender angelegt …"), Appointment.calendarLine(visit.copy(dirty = true), syncConfigured = true))
        assertEquals(
            CalendarLine("Wird im Kalender angelegt …"),
            Appointment.calendarLine(visit.copy(calendarState = CalendarState.PENDING), syncConfigured = true),
        )
        assertEquals(
            CalendarLine("Wird im Kalender aktualisiert …"),
            Appointment.calendarLine(visit.copy(eventUid = "abc", calendarState = CalendarState.OK, dirty = true), syncConfigured = true),
        )
        assertEquals(
            CalendarLine("Im Kalender"),
            Appointment.calendarLine(visit.copy(eventUid = "abc", calendarState = CalendarState.OK), syncConfigured = true),
        )
        assertEquals(
            CalendarLine("Im Kalender · Eingeladen: test@example.org"),
            Appointment.calendarLine(
                visit.copy(eventUid = "abc", calendarState = CalendarState.OK, inviteEmail = "test@example.org"), syncConfigured = true,
            ),
        )
        assertEquals(
            CalendarLine("Nicht im Kalender: Im Kalender gelöscht", error = true),
            Appointment.calendarLine(
                visit.copy(calendarState = CalendarState.ERROR, calendarError = "Im Kalender gelöscht"), syncConfigured = true,
            ),
        )
    }

    @Test
    fun `without a sync server a visit never says it is on its way`() {
        // Nothing would ever take it out of pending.
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertNull(Appointment.calendarLine(visit.copy(dirty = true), syncConfigured = false))
        assertNull(Appointment.calendarLine(visit.copy(calendarState = CalendarState.PENDING), syncConfigured = false))
        assertEquals(
            CalendarLine("Im Kalender"),
            Appointment.calendarLine(visit.copy(eventUid = "abc", calendarState = CalendarState.OK), syncConfigured = false),
        )
    }

    @Test
    fun `an invited visit missing from the calendar offers its removal instead of a state`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")
            .copy(eventUid = "abc", calendarState = CalendarState.OK, inviteEmail = "test@example.org")

        assertEquals(
            CalendarLine("Im Kalender nicht mehr gefunden", error = true, offersRemoval = true),
            Appointment.calendarLine(visit, syncConfigured = true, missing = true),
        )
    }

    @Test
    fun `a callback has no calendar line of this kind`() {
        val callback = entry("R-1", "2026-09-10T14:00:00+02:00").copy(kind = AppointmentKind.CALLBACK, dirty = true)

        assertNull(Appointment.calendarLine(callback, syncConfigured = true))
    }

    @Test
    fun `removing an invited visit says who gets a cancellation`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertEquals("test@example.org bekommt eine Absage.", Appointment.cancellationNotice(visit.copy(inviteEmail = "test@example.org")))
        assertNull(Appointment.cancellationNotice(visit))
        assertNull(Appointment.cancellationNotice(visit.copy(kind = AppointmentKind.CALLBACK, inviteEmail = "test@example.org")))
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: compilation FAILS — `CalendarLine`, `defaultTitle`, `planVisit`, `invite` on `AppointmentDraft` … unresolved.

- [ ] **Step 3: The draft**

In `…/CallsheetViewModel.kt`, in `data class AppointmentDraft`, below `val adoptable: Set<Long> = emptySet(),`, add:

```kotlin
    /** A visit's calendar title, preset to Appointment.visitTitle. Unused for a callback. */
    val title: String = "",
    /** „Einladung senden". Only for a visit. */
    val invite: Boolean = false,
    /** The invitee's address as typed or picked. */
    val inviteEmail: String = "",
    /** Why the address blocks saving; null while nothing is wrong. */
    val inviteError: String? = null,
```

- [ ] **Step 4: Implement**

In `…/calling/Appointment.kt`, add `import io.github.amadeusb.callsheet.data.CalendarState` to the imports, and `import io.github.amadeusb.callsheet.AppointmentDraft` unless the business-addresses plan already added it.

Above the KDoc of `object Appointment`, add:

```kotlin
/**
 * One line under a visit in the detail view: where it stands on its way into
 * the calendar. [offersRemoval]: the detail view puts „Termin entfernen" under
 * it — see Reconcile.MissingInvited.
 */
data class CalendarLine(val text: String, val error: Boolean = false, val offersRemoval: Boolean = false)
```

Inside `object Appointment`, below `fun durations(kind: AppointmentKind)`, add:

```kotlin
    /**
     * How long after saving a visit the app syncs once more. The server works
     * its queue after the first sync's response; the second brings the UID and
     * the calendar state down without another tap.
     */
    const val RESYNC_AFTER_SAVE_MILLIS: Long = 5_000L

    /** Said under the address when it blocks saving. */
    const val INVALID_INVITE: String = "Das ist keine gültige E-Mail-Adresse."

    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

    /**
     * A visit's title when none was typed. The server builds the same text for
     * a null `title` (`defaultTitle` in its `src/visitState.js`); the two must not
     * differ by a character, or every visit would look changed. The name is
     * trimmed; a business without one gets the title without „bei …".
     */
    fun defaultTitle(businessName: String): String {
        val name = businessName.trim()
        return if (name.isEmpty()) "Erstgespräch KI – Christoph Bauer" else "Erstgespräch KI bei $name – Christoph Bauer"
    }

    /** The title a visit's event carries. */
    fun visitTitle(title: String?, businessName: String): String =
        title?.trim()?.ifEmpty { null } ?: defaultTitle(businessName)

    /**
     * What the sheet's title field is stored as. Empty, or the preset left as it
     * is, is null — the default, which follows the business's name.
     */
    fun titleToStore(typed: String, businessName: String): String? =
        typed.trim().takeUnless { it.isEmpty() || it == defaultTitle(businessName) }

    /** Why the address blocks saving, or null. Only with the invitation switched on. */
    fun inviteError(invite: Boolean, email: String): String? =
        if (!invite || EMAIL.matches(email.trim())) null else INVALID_INVITE

    /** The address stored for the invitation; null means nobody is invited. */
    fun inviteToStore(invite: Boolean, email: String): String? =
        if (invite) email.trim().ifEmpty { null } else null

    /** The address the invitation starts at: the contact person's first. */
    fun inviteSuggestion(contact: Contact?): String = contact?.emails?.firstOrNull()?.email.orEmpty()

    /**
     * The invitation's address after the sheet reported [incoming], or null to
     * leave [incoming]'s as it is. Called by the view model next to
     * placeAfterContactChange.
     *
     * It follows to the new contact person's first address — empty where they
     * have none, so the invitation never goes to the person picked before —
     * only when all of this holds: a visit, the invitation on, the person
     * changed, the address not changed in the same update, and the address
     * still the preselected one, the previous person's first (empty for nobody
     * or a person without an address). An address typed, or picked by chip
     * other than the first, is a choice and stays. [emailsFor] gives a person's
     * addresses in order; null means no person.
     */
    fun inviteAfterContactChange(
        previous: AppointmentDraft,
        incoming: AppointmentDraft,
        emailsFor: (contactId: String?) -> List<String>,
    ): String? {
        if (incoming.kind != AppointmentKind.VISIT || !previous.invite || !incoming.invite) return null
        if (incoming.contactId == previous.contactId || incoming.inviteEmail != previous.inviteEmail) return null
        val preselected = emailsFor(previous.contactId).firstOrNull().orEmpty()
        if (previous.inviteEmail.trim() != preselected) return null
        return emailsFor(incoming.contactId).firstOrNull().orEmpty()
    }
```

Below `fun plan(…)`, add:

```kotlin
    /**
     * What saving a visit should do. The event is the server's: it is created,
     * moved and removed through the Infomaniak API. So there is nothing to
     * create, update or adopt here — an event made elsewhere has no id the
     * server knows. A taken slot is still asked about.
     */
    fun planVisit(startMillis: Long, endMillis: Long, busy: List<BusyInterval>, force: Boolean): SavePlan {
        if (!force) {
            val clash = overlapping(startMillis, endMillis, busy)
            if (clash.isNotEmpty()) return SavePlan.Conflict(clash)
        }
        return SavePlan.LocalOnly
    }

    /**
     * Whether opening the business reads this appointment's event back.
     *
     * A callback: as soon as it has an event, by UID or this device's link.
     *
     * A visit: only once the server has put exactly this row into the calendar
     * — a UID, the state `ok`, and nothing here waiting to go up. Before that
     * the event is behind the row, or not there at all: a „deleted" or „moved"
     * read from it would undo the edit, and the server would send the old time
     * to the invitee. Once read, first sight still takes nothing — see
     * [reconcile].
     */
    fun readsBack(entry: AppointmentEntry): Boolean = when (entry.kind) {
        AppointmentKind.CALLBACK -> entry.eventUid != null || entry.calendarEventId != null
        AppointmentKind.VISIT -> entry.eventUid != null && !entry.dirty && entry.calendarState == CalendarState.OK
    }

    /**
     * A visit the server has not put into the calendar yet. Opening its
     * business syncs for it, even with nothing to read back: the state and UID
     * come down with the sync, and the line under the visit moves on.
     */
    fun awaitsServer(entry: AppointmentEntry): Boolean =
        entry.kind == AppointmentKind.VISIT && entry.calendarState == CalendarState.PENDING

    /**
     * Where a visit stands on its way into the calendar. A row still waiting to
     * go up counts as pending: saved offline, it is not in the calendar yet,
     * whatever the server said last — but only with a sync server set up
     * ([syncConfigured]); without one nothing would ever take it out of
     * pending, and nothing is said. [missing]: the read-back did not find the
     * event of this invited visit (Reconcile.MissingInvited) — said instead of
     * the state, with the removal offered. Null for a callback, and for a visit
     * without a state — saved before this version, or on a server without the
     * feature.
     */
    fun calendarLine(entry: AppointmentEntry, syncConfigured: Boolean, missing: Boolean = false): CalendarLine? {
        if (entry.kind != AppointmentKind.VISIT) return null
        return when {
            missing -> CalendarLine("Im Kalender nicht mehr gefunden", error = true, offersRemoval = true)
            entry.dirty || entry.calendarState == CalendarState.PENDING -> if (!syncConfigured) null else CalendarLine(
                if (entry.eventUid == null) "Wird im Kalender angelegt …" else "Wird im Kalender aktualisiert …"
            )
            entry.calendarState == CalendarState.OK -> CalendarLine(
                listOfNotNull("Im Kalender", entry.inviteEmail?.let { "Eingeladen: $it" }).joinToString(" · ")
            )
            entry.calendarState == CalendarState.ERROR -> CalendarLine(
                "Nicht im Kalender: ${entry.calendarError ?: "unbekannter Fehler"}",
                error = true,
            )
            else -> null
        }
    }

    /** What removing a visit sends: a cancellation to the invitee. Null without one. */
    fun cancellationNotice(entry: AppointmentEntry): String? =
        entry.inviteEmail?.takeIf { entry.kind == AppointmentKind.VISIT }?.let { "$it bekommt eine Absage." }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL — the new draft fields have defaults, nothing reads them yet.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
  app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
  app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Regeln für Besuche über den Server: Titel, Einladung, Kalenderstand, kein Kalenderschreiben"
```

---

### Task 4: The read-back of a visit — title, first sight, an invited visit gone missing

**Files:**
- Modify: `…/calling/Appointment.kt`
- Modify: `…/CallsheetViewModel.kt` (one `when` branch, so the build stays green)
- Test: `test/AppointmentTest.kt`

**Interfaces:**
- Consumes: `AppointmentEntry.seenTitle` (Task 1).
- Produces: `data class Slot(val startMillis: Long, val endMillis: Long?, val location: String?, val title: String? = null)`; `Appointment.rowSlot(entry: AppointmentEntry, title: String? = null): Slot?`; `seenSlot` carries `entry.seenTitle`; `seenIsCurrent` is false while an event's title was never recorded; `Reconcile.MissingInvited`; `Appointment.reconcile(row: Slot, seen: Slot?, event: Slot?, nowMillis: Long, visit: Boolean = false, invited: Boolean = false): Reconcile`.

The rules for a visit (spec, „Reading back"):
- **First sight takes nothing.** `seen == null` and an event that differs from the row → `NotYetHere`: nothing taken, nothing remembered. An event equal to the row → `InStep`, remembered as seen. A callback keeps „first sight, the calendar wins".
- **Seen before, gone, ahead, invited** (`invited` = `invite_email != null`) → `MissingInvited`: nothing deleted, nothing stored; the view model reports it to the detail view (Task 5), which offers „Termin entfernen" (Task 6). The next opening decides again, so the hint goes once the event is found. Without an invitation → `DeletedInCalendar`, as today. Past → `Unlink`, as today.

- [ ] **Step 1: Write the failing tests**

In `test/AppointmentTest.kt`, below `a record of the event is current only with the same link and a matching slot`, add:

```kotlin
    @Test
    fun `a title changed in the calendar is taken like a moved time`() {
        val row = planned.copy(title = "Erstgespräch KI bei Elektro Meier – Christoph Bauer")
        val event = planned.copy(title = "Erstgespräch – bitte Unterlagen mitbringen")

        assertEquals(Reconcile.TakeEvent(event), Appointment.reconcile(row = row, seen = row, event = event, nowMillis = dayBefore))
    }

    @Test
    fun `a title changed in the app is the row's, as a moved time is`() {
        val seen = planned.copy(title = "Erstgespräch")
        val row = planned.copy(title = "Angebot besprechen")

        assertEquals(Reconcile.UpdateEvent, Appointment.reconcile(row = row, seen = seen, event = seen, nowMillis = dayBefore))
    }

    @Test
    fun `a title on one side only is no difference`() {
        // A callback's row carries no title; its event's title is the app's own.
        val event = planned.copy(title = "✓ Rückruf Elektro Meier")

        assertEquals(Reconcile.InStep, Appointment.reconcile(row = planned, seen = planned, event = event, nowMillis = dayBefore))
    }

    @Test
    fun `the seen title is read from the entry, and a record without it is not current`() {
        val recorded = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00").copy(
            location = "Zehentstraße 39",
            calendarEventId = 4711L,
            seenStartsAt = "2026-09-10T14:00:00+02:00",
            seenEndsAt = "2026-09-10T15:00:00+02:00",
            seenLocation = "Zehentstraße 39",
        )
        val titled = planned.copy(title = "Erstgespräch")

        assertFalse(Appointment.seenIsCurrent(recorded, 4711L, titled))
        assertTrue(Appointment.seenIsCurrent(recorded.copy(seenTitle = "Erstgespräch"), 4711L, titled))
        assertTrue(Appointment.seenIsCurrent(recorded, 4711L, planned))
        assertEquals(titled, Appointment.seenSlot(recorded.copy(seenTitle = "Erstgespräch")))
        assertEquals(titled, Appointment.rowSlot(recorded, title = "Erstgespräch"))
    }
```

Below `an event seen before and gone after the appointment only loses the link`, add:

```kotlin
    @Test
    fun `a visit takes nothing from an event it sees for the first time`() {
        // This device's DAVx5 may still hold the state from before a change made
        // elsewhere; taken, it would send the invitee the old time.
        assertEquals(
            Reconcile.NotYetHere,
            Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore, visit = true),
        )
        assertEquals(
            Reconcile.NotYetHere,
            Appointment.reconcile(
                row = planned.copy(title = "Neu"), seen = null, event = planned.copy(title = "Alt"), nowMillis = dayBefore, visit = true,
            ),
        )
    }

    @Test
    fun `a visit remembers an event it sees for the first time only when it matches`() {
        assertEquals(
            Reconcile.InStep,
            Appointment.reconcile(row = planned, seen = null, event = planned, nowMillis = dayBefore, visit = true),
        )
    }

    @Test
    fun `a visit seen before takes a change made in the calendar`() {
        assertEquals(
            Reconcile.TakeEvent(later),
            Appointment.reconcile(row = planned, seen = planned, event = later, nowMillis = dayBefore, visit = true),
        )
    }

    @Test
    fun `an invited visit gone from the calendar is reported, never deleted`() {
        // A deselected calendar or a new DAVx5 account looks the same, and a
        // deletion would send the customer a cancellation.
        assertEquals(
            Reconcile.MissingInvited,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore, visit = true, invited = true),
        )
        assertEquals(
            Reconcile.Unlink,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayAfter, visit = true, invited = true),
        )
        assertEquals(
            Reconcile.NotYetHere,
            Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayBefore, visit = true, invited = true),
        )
    }

    @Test
    fun `a visit without an invitation gone from the calendar is deleted as before`() {
        assertEquals(
            Reconcile.DeletedInCalendar,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore, visit = true, invited = false),
        )
    }

    @Test
    fun `the visit rules leave a callback alone`() {
        assertEquals(Reconcile.TakeEvent(planned), Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore))
        assertEquals(
            Reconcile.DeletedInCalendar,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore, invited = true),
        )
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: compilation FAILS — `copy(title = …)` on `Slot`, `rowSlot(…, title = …)`, `visit =`, `invited =` and `Reconcile.MissingInvited` unresolved.

- [ ] **Step 3: Implement**

In `…/calling/Appointment.kt`, replace the `Slot` declaration and its KDoc with:

```kotlin
/**
 * An appointment's time and place as the read-back compares them — for the
 * row, for the event, and for what this device last saw in the event.
 *
 * [title] only for a visit: it can be changed in the web calendar. A
 * callback's title is the app's own, built from its note and tick, and is left
 * out — null on either side is no difference.
 */
data class Slot(val startMillis: Long, val endMillis: Long?, val location: String?, val title: String? = null)
```

Replace `rowSlot` and `seenSlot` with:

```kotlin
    /** The row's slot, with the [title] its event should carry — a visit's only. Null when its start cannot be read. */
    fun rowSlot(entry: AppointmentEntry, title: String? = null): Slot? {
        val start = Clock.millis(entry.startsAt) ?: return null
        return Slot(start, Clock.millis(entry.endsAt), entry.location, title)
    }

    /** What this device last saw in the event. Null while it never saw it. */
    fun seenSlot(entry: AppointmentEntry): Slot? {
        val start = Clock.millis(entry.seenStartsAt) ?: return null
        return Slot(start, Clock.millis(entry.seenEndsAt), entry.seenLocation, entry.seenTitle)
    }
```

Replace the body of `seenIsCurrent` with:

```kotlin
    fun seenIsCurrent(entry: AppointmentEntry, eventId: Long, event: Slot): Boolean {
        val seen = seenSlot(entry) ?: return false
        // A visit linked before its title was recorded: record it now, or a
        // title changed in the calendar later would look like the first one.
        if (event.title != null && seen.title == null) return false
        return entry.calendarEventId == eventId && sameSlot(seen, event)
    }
```

Replace `sameSlot` and its KDoc with:

```kotlin
    /**
     * Within a minute, locations and titles after trimming. A missing end on
     * either side is no difference: an event always has one, a row may not. A
     * missing title neither — see [Slot].
     */
    private fun sameSlot(a: Slot, b: Slot): Boolean {
        val sameEnd = a.endMillis == null || b.endMillis == null || near(a.endMillis, b.endMillis)
        val sameTitle = a.title == null || b.title == null || a.title.trim() == b.title.trim()
        return near(a.startMillis, b.startMillis) && sameEnd && sameTitle &&
            a.location?.trim().orEmpty() == b.location?.trim().orEmpty()
    }
```

In `sealed interface Reconcile`, replace

```kotlin
    /** Not found, and never seen on this device: it may not have arrived yet. */
    data object NotYetHere : Reconcile
```

with

```kotlin
    /**
     * Not found, and never seen on this device: it may not have arrived yet.
     * For a visit also: seen here for the first time and different from the
     * row — nothing is taken and nothing remembered.
     */
    data object NotYetHere : Reconcile
```

and below `data object DeletedInCalendar : Reconcile`, add:

```kotlin
    /**
     * A visit with an invitation: seen before, gone now, still ahead. Not
     * deleted — a calendar deselected in DAVx5 or an account set up again
     * looks the same, and a deletion would send the customer a cancellation.
     * Nothing is stored; the detail view offers the removal.
     */
    data object MissingInvited : Reconcile
```

Replace `fun reconcile(row: Slot, seen: Slot?, event: Slot?, nowMillis: Long): Reconcile { … }` with:

```kotlin
    fun reconcile(
        row: Slot,
        seen: Slot?,
        event: Slot?,
        nowMillis: Long,
        visit: Boolean = false,
        invited: Boolean = false,
    ): Reconcile {
        if (event == null) {
            if (seen == null) return Reconcile.NotYetHere
            val last = row.endMillis ?: row.startMillis
            return when {
                last <= nowMillis -> Reconcile.Unlink
                visit && invited -> Reconcile.MissingInvited
                else -> Reconcile.DeletedInCalendar
            }
        }
        if (sameSlot(event, row)) return Reconcile.InStep
        // A visit's first sight takes nothing: this device's calendar may be
        // behind a change made elsewhere, and the server would send it on.
        if (seen == null) return if (visit) Reconcile.NotYetHere else Reconcile.TakeEvent(event)
        val onlyTheCalendarMoved = !sameSlot(event, seen) && sameSlot(row, seen)
        return if (onlyTheCalendarMoved) Reconcile.TakeEvent(event) else Reconcile.UpdateEvent
    }
```

and in its KDoc, below the paragraph that ends „takes it as it stands in the calendar.", add:

```
     *
     * A [visit] differs in two places. On first sight it takes nothing
     * (NotYetHere) — only an event equal to the row is recorded. And gone while
     * still ahead with an invitation ([invited]) it is MissingInvited, not
     * deleted. UpdateEvent for a visit means only that the row is ahead; the
     * server writes the event, not this device.
```

In `…/CallsheetViewModel.kt`, in `reconcile`, below `Reconcile.NotYetHere -> Unit`, add:

```kotlin
            // Nothing deleted, nothing stored: reconcileAppointments reports it (Task 5).
            Reconcile.MissingInvited -> Unit
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, every existing read-back test included.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
  app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
  app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Abgleich eines Besuchs: Titel wird erkannt, erster Blick übernimmt nichts, eingeladener Besuch wird nicht gelöscht"
```

---

### Task 5: The view model — visits no longer write the calendar

**Files:**
- Modify: `…/CallsheetViewModel.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4; from the business-addresses plan `withPlace`, `State.detailContacts`.
- Produces: `State.detailMissingInCalendar: Set<String> = emptySet()` (used by the detail view in Task 6).

No unit tests: the decisions were tested in Tasks 3 and 4; there is no view model harness (see Global Constraints). The check is `assembleDebug`, the full test run, and the phone test in Task 8.

- [ ] **Step 1: The invited visits missing from the calendar**

In `data class State`, below `val detailAppointments: List<AppointmentEntry> = emptyList(),`, add:

```kotlin
    /**
     * The shown business's invited visits the last read-back did not find in
     * the calendar (Reconcile.MissingInvited). Not stored: the next opening
     * decides again, and a found event takes its id out.
     */
    val detailMissingInCalendar: Set<String> = emptySet(),
```

In `openBusiness`, below `detailAppointments = if (switching) emptyList() else _state.value.detailAppointments,`, add:

```kotlin
            detailMissingInCalendar = if (switching) emptySet() else _state.value.detailMissingInCalendar,
```

- [ ] **Step 2: Reload the detail view after a sync**

In `syncAndWait`, replace

```kotlin
        if (result is SyncResult.Ok) {
            refreshList()
            loadAgenda()
            // The UIDs taken here go up with the next run, started at once.
```

with

```kotlin
        if (result is SyncResult.Ok) {
            refreshList()
            loadAgenda()
            // A visit's calendar state and UID come down with a sync, calendar
            // permission or not — the detail view shows them.
            (_state.value.screen as? Screen.Detail)?.let { loadDetail(it.placeId) }
            // The UIDs taken here go up with the next run, started at once.
```

In `connectServer`, replace

```kotlin
            if (result is SyncResult.Ok) {
                refreshList()
                loadAgenda()
            }
```

with

```kotlin
            if (result is SyncResult.Ok) {
                refreshList()
                loadAgenda()
                (_state.value.screen as? Screen.Detail)?.let { loadDetail(it.placeId) }
            }
```

- [ ] **Step 3: Opening the sheet, and the busy times**

In `openSheet`, replace

```kotlin
            val lookup = existing?.let { lookUpEvent(it) }
```

with

```kotlin
            // A visit's event is the server's: nothing here writes it, so
            // nothing needs finding before saving.
            val lookup = existing?.takeIf { it.kind == AppointmentKind.CALLBACK }?.let { lookUpEvent(it) }
```

In the same function, below `calendarReadable = readable,` inside `AppointmentDraft(…)`, add:

```kotlin
                        title = if (sheetKind == AppointmentKind.VISIT) Appointment.visitTitle(existing?.title, business.name) else "",
                        invite = existing?.inviteEmail != null,
                        inviteEmail = existing?.inviteEmail.orEmpty(),
```

and replace

```kotlin
                loadBusy(it, existing?.eventUid, located?.eventId ?: existing?.calendarEventId)
```

with

```kotlin
                loadBusy(it, sheetKind, existing?.eventUid, located?.eventId ?: existing?.calendarEventId)
```

Replace the signature and the `adoptable` line of `loadBusy`:

```kotlin
    private fun loadBusy(millis: Long, kind: AppointmentKind, ownUid: String?, ownEventId: Long?) {
```

```kotlin
            // None at all for an appointment that already has an event — see
            // Appointment.adoptable — and none for a visit: its event is the
            // server's, and one made elsewhere has no id the server knows.
            val adoptable = if (kind == AppointmentKind.VISIT) emptySet() else Appointment.adoptable(busy, taken, ownUid, ownEventId)
```

(The comment `// None at all for an appointment that already has an event — see Appointment.adoptable.` above the old line goes.)

In `updateAppointmentDraft` — as the business-addresses plan left it, starting `fun updateAppointmentDraft(incoming: AppointmentDraft)` — replace

```kotlin
        val draft = withPlace(previous, incoming)
```

with

```kotlin
        // Place and invitation both follow a new contact person, each only
        // where it was not chosen by hand.
        val draft = withInvite(previous, withPlace(previous, incoming))
```

Below `withPlace`, add:

```kotlin
    /**
     * The invitation's address as the sheet shows it after [incoming]: a new
     * contact person brings their first address where the previous person's
     * was still preselected. See Appointment.inviteAfterContactChange.
     */
    private fun withInvite(previous: AppointmentDraft?, incoming: AppointmentDraft): AppointmentDraft {
        if (previous == null) return incoming
        val contacts = _state.value.detailContacts
        val address = Appointment.inviteAfterContactChange(previous, incoming) { contactId ->
            contacts.firstOrNull { it.id == contactId }?.emails.orEmpty().map { it.email }
        }
        return address?.let { incoming.copy(inviteEmail = it) } ?: incoming
    }
```

In the same function, replace

```kotlin
                appointmentDraft = draft.copy(
                    conflict = emptyList(),
```

with

```kotlin
                appointmentDraft = draft.copy(
                    conflict = emptyList(),
                    inviteError = null,
```

and replace

```kotlin
                loadBusy(start, existing?.eventUid, existing?.calendarEventId)
```

with

```kotlin
                loadBusy(start, draft.kind, existing?.eventUid, existing?.calendarEventId)
```

- [ ] **Step 4: Saving a visit**

In `saveAppointment`, directly below the closing brace of `if (draft.appointmentId != null && existing == null) { … }`, add:

```kotlin
            if ((existing?.kind ?: draft.kind) == AppointmentKind.VISIT) {
                saveVisit(draft, existing, business, endIso, startMillis, endMillis, force)
                return@launch
            }
```

Below `saveAppointment`, add:

```kotlin
    /**
     * Saves a visit. The calendar is the server's business: it creates, moves
     * and removes the event through the Infomaniak API and sends the
     * invitation. Here only the row is written — offline too — then synced at
     * once and once more a little later, so the server's UID and calendar
     * state come down without another tap. A taken slot is still asked about;
     * nothing is linked.
     */
    private suspend fun saveVisit(
        draft: AppointmentDraft,
        existing: AppointmentEntry?,
        business: Business,
        endIso: String,
        startMillis: Long,
        endMillis: Long,
        force: Boolean,
    ) {
        Appointment.inviteError(draft.invite, draft.inviteEmail)?.let { error ->
            _state.update { it.copy(appointmentDraft = draft.copy(inviteError = error)) }
            return
        }
        val plan = Appointment.planVisit(startMillis, endMillis, draft.busy, force)
        if (plan is SavePlan.Conflict) {
            _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
            return
        }
        val entry = AppointmentEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            placeId = draft.placeId,
            startsAt = draft.startIso,
            endsAt = endIso,
            location = draft.location.trim().ifEmpty { null },
            note = draft.note.trim().ifEmpty { null },
            contactId = draft.contactId,
            eventUid = existing?.eventUid,
            kind = AppointmentKind.VISIT,
            title = Appointment.titleToStore(draft.title, business.name),
            inviteEmail = Appointment.inviteToStore(draft.invite, draft.inviteEmail),
        )
        // The length a visit starts at follows the last visit.
        preferences.appointmentMinutes = draft.minutes
        repo.saveAppointment(entry)
        Appointment.statusAfterSave(entry.kind, entry.startsAt, entry.endsAt, System.currentTimeMillis())
            ?.let { repo.setStatus(draft.placeId, it) }
        _state.update { it.copy(appointmentDraft = null, hint = null) }
        loadDetail(draft.placeId)
        syncNow()
        viewModelScope.launch {
            delay(Appointment.RESYNC_AFTER_SAVE_MILLIS)
            syncNow()
        }
    }
```

- [ ] **Step 5: Removing a visit**

In `removeAppointment`, directly below `val business = repo.business(entry.placeId) ?: return@launch`, add:

```kotlin
            if (entry.kind == AppointmentKind.VISIT) {
                // The event goes through the server, with a cancellation where
                // someone was invited. Deleted here, it would reach Infomaniak
                // through DAVx5 first — without a word to the invitee, and the
                // server would find nothing left to cancel.
                repo.deleteAppointment(entry.id)
                Appointment.statusAfterRemoval(business.status, repo.appointments(entry.placeId), System.currentTimeMillis())
                    ?.let { repo.setStatus(entry.placeId, it) }
                loadDetail(entry.placeId)
                syncNow()
                return@launch
            }
```

- [ ] **Step 6: The read-back reads a visit, and writes nothing into its event**

Replace `rememberSeen` with:

```kotlin
    /** Records locally which event this device links, and what that event now holds — the title for a visit. */
    private suspend fun rememberSeen(appointmentId: String, eventId: Long, event: EventFields, withTitle: Boolean = false) {
        repo.setCalendarLink(
            appointmentId, eventId,
            Clock.format(event.startMillis), Clock.format(event.endMillis), event.location,
            seenTitle = if (withTitle) event.title else null,
        )
    }
```

In `reconcile`, make these replacements, in order:

1. Replace

```kotlin
        val row = Appointment.rowSlot(entry) ?: return null
```

with

```kotlin
        val visit = entry.kind == AppointmentKind.VISIT
        // A visit's title is compared too: it can be changed in the web calendar.
        val row = Appointment.rowSlot(entry, title = if (visit) Appointment.visitTitle(entry.title, business.name) else null)
            ?: return null
```

2. Replace

```kotlin
        if (!rowWinsOnly && located != null && entry.eventUid != null && event?.uid != entry.eventUid) {
```

with

```kotlin
        // Never for a visit: its event is the server's, and nothing here deletes a copy of it.
        if (!visit && !rowWinsOnly && located != null && entry.eventUid != null && event?.uid != entry.eventUid) {
```

3. Replace

```kotlin
            event = event?.let { Slot(it.startMillis, it.endMillis, it.location) },
            nowMillis = nowMillis,
        )
```

with

```kotlin
            event = event?.let { Slot(it.startMillis, it.endMillis, it.location, if (visit) it.title else null) },
            nowMillis = nowMillis,
            // First sight takes nothing, and an invited visit is never deleted — see Appointment.reconcile.
            visit = visit,
            invited = visit && entry.inviteEmail != null,
        )
```

4. Replace

```kotlin
        val uid = if (rowWinsOnly || event == null) {
```

with

```kotlin
        // A visit's UID comes from the server.
        val uid = if (visit || rowWinsOnly || event == null) {
```

5. In the `Reconcile.InStep` branch, replace

```kotlin
                val holds = Slot(event!!.startMillis, event.endMillis, event.location)
                // Only when something is new — a link rewritten on every opening
                // would notify every observer of the database for nothing.
                if (!Appointment.seenIsCurrent(entry, located!!.eventId, holds)) {
                    rememberSeen(entry.id, located.eventId, event)
                }
```

with

```kotlin
                val holds = Slot(event!!.startMillis, event.endMillis, event.location, if (visit) event.title else null)
                // Only when something is new — a link rewritten on every opening
                // would notify every observer of the database for nothing.
                if (!Appointment.seenIsCurrent(entry, located!!.eventId, holds)) {
                    rememberSeen(entry.id, located.eventId, event, withTitle = visit)
                }
```

6. In the `is Reconcile.TakeEvent` branch, replace

```kotlin
                if (fresh.startsAt != entry.startsAt || fresh.endsAt != entry.endsAt || fresh.location != entry.location) {
                    return null
                }
                repo.saveAppointment(
                    fresh.copy(
                        startsAt = Clock.format(outcome.slot.startMillis),
                        endsAt = outcome.slot.endMillis?.let { Clock.format(it) },
                        location = outcome.slot.location,
                        eventUid = fresh.eventUid ?: uid,
                    )
                )
                rememberSeen(entry.id, located!!.eventId, event!!)
```

with

```kotlin
                if (fresh.startsAt != entry.startsAt || fresh.endsAt != entry.endsAt ||
                    fresh.location != entry.location || fresh.title != entry.title
                ) {
                    return null
                }
                val takenTitle = outcome.slot.title
                repo.saveAppointment(
                    fresh.copy(
                        startsAt = Clock.format(outcome.slot.startMillis),
                        endsAt = outcome.slot.endMillis?.let { Clock.format(it) },
                        // Trimmed for a visit, as saving trims it: whitespace from the
                        // web calendar must not become a change the server sends on.
                        location = if (visit) outcome.slot.location?.trim()?.ifEmpty { null } else outcome.slot.location,
                        eventUid = fresh.eventUid ?: uid,
                        // A visit takes the calendar's title as well. The server then
                        // finds Infomaniak already holding it and sends nothing.
                        title = if (visit && takenTitle != null) Appointment.titleToStore(takenTitle, business.name) else fresh.title,
                    )
                )
                rememberSeen(entry.id, located!!.eventId, event!!, withTitle = visit)
```

7. In the `Reconcile.UpdateEvent` branch, as its first line, add:

```kotlin
                // The row is ahead of a visit's event: the server writes it, not this device.
                if (visit) return outcome
```

In `reconcileAppointments`, make these replacements, in order:

1. The prefilter. A visit still `pending` has nothing to read back yet, but opening its business must sync for it — that is how its state and UID come down without another tap, calendar permission or not. Replace

```kotlin
            if (!CalendarStore.canRead(getApplication())) return@launch
            // Nothing linked, nothing to read back — and no sync for it.
            if (repo.appointments(placeId).none { it.eventUid != null || it.calendarEventId != null }) return@launch
```

with

```kotlin
            val readable = CalendarStore.canRead(getApplication())
            val stored = repo.appointments(placeId)
            // A visit waiting for the server is worth a sync of its own: its state
            // and UID come down with it. See Appointment.awaitsServer.
            val awaiting = stored.any { Appointment.awaitsServer(it) }
            // Nothing to read back and nothing awaited — and no sync for it. See Appointment.readsBack.
            if (!awaiting && (!readable || stored.none { Appointment.readsBack(it) })) return@launch
```

2. After the sync, without read permission there is nothing more to do — `syncAndWait` has reloaded the detail view (Step 2). Replace

```kotlin
                val business = repo.business(placeId) ?: return@launch
                val now = System.currentTimeMillis()
```

with

```kotlin
                if (!CalendarStore.canRead(getApplication())) return@launch
                val business = repo.business(placeId) ?: return@launch
                val now = System.currentTimeMillis()
```

3. Replace

```kotlin
                    .filter { it.eventUid != null || it.calendarEventId != null }
```

with

```kotlin
                    .filter { Appointment.readsBack(it) }
```

4. Report the invited visits not found. Replace

```kotlin
                if (results.isEmpty()) return@launch

                // The sync may have taken long: the user may have moved on. The
                // status is data and falls back regardless; hint and reload only
                // for the business still on screen.
                val stillShown = { (_state.value.screen as? Screen.Detail)?.placeId == placeId }
```

with

```kotlin
                // The sync may have taken long: the user may have moved on. The
                // status is data and falls back regardless; hint and reload only
                // for the business still on screen.
                val stillShown = { (_state.value.screen as? Screen.Detail)?.placeId == placeId }
                // Replaced, not added to: an event found again takes its hint away.
                // Nothing is stored — the next opening decides again.
                if (stillShown()) {
                    val missing = results.filter { it.second == Reconcile.MissingInvited }.map { it.first.id }.toSet()
                    _state.update { it.copy(detailMissingInCalendar = missing) }
                }
                if (results.isEmpty()) return@launch
```

(`DeletedInCalendar` handling below stays: it now only ever comes for a callback or a visit without an invitation.)

- [ ] **Step 7: After a sync, and missing UIDs — callbacks only**

In `followCalendar`, in the loop `for (removed in batch.removed) {`, add as the first line:

```kotlin
                    // A visit's event is deleted by the server, with the cancellation.
                    if (removed.kind == AppointmentKind.VISIT) continue
```

In the loop `for (id in batch.written.distinct()) {`, below `val entry = repo.appointment(id) ?: continue`, add:

```kotlin
                    // No calendar write for a visit after a sync: the server keeps its event.
                    if (entry.kind == AppointmentKind.VISIT) continue
```

In `captureMissingUids`, replace

```kotlin
        for (entry in repo.linkedWithoutUid()) {
```

with

```kotlin
        // Only callbacks: a visit's UID is the server's.
        for (entry in repo.linkedWithoutUid().filter { it.kind == AppointmentKind.CALLBACK }) {
```

- [ ] **Step 8: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

Run: `grep -n "Slot(" app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`
Expected: every `Slot(` in `reconcile` passes a fourth argument `if (visit) … else null`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Besuche schreibt die App nicht mehr in den Kalender: speichern, entfernen, abgleichen nur lesend"
```

---

### Task 6: The sheet and the detail view

**Files:**
- Modify: `…/ui/AppointmentSheet.kt`
- Modify: `…/ui/BusinessDetail.kt`
- Modify: `…/MainActivity.kt`

**Interfaces:**
- Consumes: `AppointmentDraft.title`, `.invite`, `.inviteEmail`, `.inviteError` (Task 3); `Appointment.inviteSuggestion`, `calendarLine`, `cancellationNotice` (Task 3); `State.detailMissingInCalendar` (Task 5); `SyncUiState.url` (existing, `preferences.serverUrl.orEmpty()`).
- Produces: `BusinessDetailScreen(…, syncConfigured: Boolean, missingInCalendar: Set<String>, …)`.

UI is not unit-tested (Compose, no UI tests in this project); the rules behind it are. The check is `assembleDebug` and Task 8.

The contact chips stay as they are (`onDraft(draft.copy(contactId = …))`): the invitation's address follows the person in the view model (`withInvite`, Task 5), next to the place — the sheet has no rule of its own.

- [ ] **Step 1: Imports in the sheet**

In `…/ui/AppointmentSheet.kt`, add:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardType
```

- [ ] **Step 2: Room for a visit's fields**

A visit's fields — title, place with the address chips, note, contact person, invitation with its chips, field and notice — come to some 700 dp at natural height, in an area that scrolls at 240 dp. Decision: keep the layout (header, strip that gives way, scrolling fields, button), but give a visit's field area 320 dp, and 200 dp while a conflict notice shows. Budget on an ordinary phone's sheet (about 740 dp usable): header and labels ~160 + strip 160 + fields 320 + button 96 = 736; with a conflict ~160 + 160 + 200 + notice ~120 + 96 = 736. „Termin speichern" stays on screen in both; a callback keeps its 240 dp.

In `AppointmentSheet`, replace

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
```

with

```kotlin
            // The fields under the strip scroll within a bounded height of their
            // own. Stacked at natural height a callback's come to some 250 dp, a
            // visit's — title, place and chips, invitation — to some 700; together
            // with the strip's minimum, a conflict notice and the button that is
            // more than a sheet has, and the button would go first. A visit gets
            // more room, and gives it back while a conflict notice needs it.
            val fieldsMax = when {
                callback -> 240.dp
                draft.conflict.isNotEmpty() -> 200.dp
                else -> 320.dp
            }
            Column(
                modifier = Modifier
                    .heightIn(max = fieldsMax)
                    .verticalScroll(rememberScrollState()),
            ) {
```

In the KDoc of `AppointmentSheet`, replace

```
 * The fields under the strip scroll in an area of at most 240 dp, and the strip
 * keeps at least 160 dp: title, date row and labels (~160 dp), strip, fields,
```

with

```
 * The fields under the strip scroll in an area of at most 240 dp for a
 * callback, 320 dp for a visit and 200 dp while a visit's conflict shows, and
 * the strip keeps at least 160 dp: title, date row and labels (~160 dp), strip, fields,
```

- [ ] **Step 3: „Titel" at the top of the fields**

Inside that `Column`, directly above `SectionLabel("Dauer")`, add:

```kotlin
                if (!callback) {
                    SectionLabel("Titel")
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { onDraft(draft.copy(title = it)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        singleLine = true,
                    )
                }
```

- [ ] **Step 4: „Einladung senden"**

Directly below the closing brace of `if (contacts.isNotEmpty()) { … }` (the „Ansprechpartner" block) and above `if (draft.eventElsewhere) {`, add:

```kotlin
                if (!callback) {
                    InviteSection(
                        draft = draft,
                        contact = contacts.firstOrNull { it.id == draft.contactId },
                        onDraft = onDraft,
                    )
                }
```

Below the `SectionLabel` composable, add:

```kotlin
/**
 * „Einladung senden": the invitee's address, one of the contact person's or
 * typed. The server sends the invitation from the calendar account; what the
 * invitee gets to see is said right here, so the note stays a private one. A
 * new contact person brings their address in the view model (withInvite).
 */
@Composable
private fun InviteSection(
    draft: AppointmentDraft,
    contact: Contact?,
    onDraft: (AppointmentDraft) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Einladung senden",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = draft.invite,
            onCheckedChange = { on ->
                onDraft(
                    draft.copy(
                        invite = on,
                        inviteEmail = if (on && draft.inviteEmail.isBlank()) {
                            Appointment.inviteSuggestion(contact)
                        } else {
                            draft.inviteEmail
                        },
                    )
                )
            },
        )
    }
    if (draft.invite) {
        val addresses = contact?.emails.orEmpty()
        if (addresses.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                addresses.forEach { address ->
                    FilterChip(
                        selected = draft.inviteEmail.trim() == address.email,
                        onClick = { onDraft(draft.copy(inviteEmail = address.email)) },
                        label = { Text(address.email) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft.inviteEmail,
            onValueChange = { onDraft(draft.copy(inviteEmail = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            isError = draft.inviteError != null,
            placeholder = { Text("name@betrieb.de") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        draft.inviteError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    Text(
        text = "Der Eingeladene sieht Titel, Zeit und Ort, nicht die Notiz.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
```

In the KDoc of `AppointmentSheet`, after the sentence the business-addresses plan ends with „offers every address of the business as a chip.", add:

```
 * Title and invitation are a visit's only — its event is written by the
 * server, which sends the invitation.
```

- [ ] **Step 5: The status line under a visit, and an invited visit gone missing**

In `…/ui/BusinessDetail.kt`, add to the parameters of `BusinessDetailScreen`, below `appointments: List<AppointmentEntry>,`:

```kotlin
    /** A sync server is set up; without one a visit never says it is on its way. */
    syncConfigured: Boolean,
    /** Invited visits the read-back did not find in the calendar. See State.detailMissingInCalendar. */
    missingInCalendar: Set<String>,
```

In the `AppointmentsBlock(…)` call inside `item(key = "appointment")`, below `appointments = visits,`, add:

```kotlin
                    syncConfigured = syncConfigured,
                    missingInCalendar = missingInCalendar,
```

In `AppointmentsBlock`, add to the parameters, below `appointments: List<AppointmentEntry>,`:

```kotlin
    syncConfigured: Boolean,
    missingInCalendar: Set<String>,
```

and replace both

```kotlin
        ahead.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl) }
```

```kotlin
            if (showPast) past.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl) }
```

with

```kotlin
        ahead.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl, syncConfigured, it.id in missingInCalendar) }
```

```kotlin
            if (showPast) past.forEach { AppointmentItem(it, contacts, onSet, onRemove, onOpenUrl, syncConfigured, it.id in missingInCalendar) }
```

In `AppointmentItem`, add to the parameters, below `onOpenUrl: (String) -> Unit,`:

```kotlin
    syncConfigured: Boolean,
    missing: Boolean,
```

and replace — in `AppointmentItem` only; `CallbackItem` keeps its own „Im Kalender abgelegt." —

```kotlin
        if (entry.calendarEventId != null) {
            Text(
                text = "Im Kalender abgelegt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
```

with

```kotlin
        // Where the visit stands on its way into the calendar — the server puts it there.
        Appointment.calendarLine(entry, syncConfigured, missing)?.let { line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Not found with an invitation: removing it is offered, never done
            // unasked. It goes through the dialog, which names who gets the
            // cancellation.
            if (line.offersRemoval) {
                TextButton(onClick = { onRemove(entry) }) { Text("Termin entfernen") }
            }
        }
```

In `…/MainActivity.kt`, in the `BusinessDetailScreen(…)` call, below `appointments = state.detailAppointments,`, add:

```kotlin
                    syncConfigured = syncState.url.isNotEmpty(),
                    missingInCalendar = state.detailMissingInCalendar,
```

- [ ] **Step 6: The cancellation in the removal dialog**

In `…/ui/BusinessDetail.kt`, in the `removeAppointment?.let { entry -> … }` dialog, replace

```kotlin
                Text(
                    listOfNotNull(
                        when {
```

with

```kotlin
                Text(
                    listOfNotNull(
                        // Said first: it is the one thing that leaves the house.
                        Appointment.cancellationNotice(entry),
                        when {
```

With an invitation the dialog reads „Termin entfernen?" — „test@example.org bekommt eine Absage. Der Termin wird auch aus dem Kalender gelöscht." — „Entfernen", from the item's „Entfernen" and from „Termin entfernen" alike.

- [ ] **Step 7: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt \
  app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt \
  app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Termin-Blatt: Titel und Einladung für Besuche; Detailansicht zeigt den Kalenderstand und einen nicht mehr gefundenen Besuch"
```

---

### Task 7: Documentation

**Files:**
- Modify: `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md`

- [ ] **Step 1: `docs/data-model.md`**

In the `appointments` code block, replace

```
event_uid               TEXT              -- iCalendar UID of the linked event, or null
calendar_event_id       INTEGER           -- local: the event's _ID on this device
calendar_seen_starts_at TEXT              -- local: what this device last saw in the event
calendar_seen_ends_at   TEXT              -- local
calendar_seen_location  TEXT              -- local
kind                    TEXT              -- 'visit' | 'callback'; NULL reads as 'visit'
done_at                 TEXT              -- when a callback was completed; NULL while open, always for a visit
dirty                   INTEGER NOT NULL DEFAULT 0
```

with

```
event_uid               TEXT              -- iCalendar UID of the event; a visit's set by the server, a callback's by the app
calendar_event_id       INTEGER           -- local: the event's _ID on this device
calendar_seen_starts_at TEXT              -- local: what this device last saw in the event
calendar_seen_ends_at   TEXT              -- local
calendar_seen_location  TEXT              -- local
calendar_seen_title     TEXT              -- local; a visit's only (schema 8)
kind                    TEXT              -- 'visit' | 'callback'; NULL reads as 'visit'
done_at                 TEXT              -- when a callback was completed; NULL while open, always for a visit
title                   TEXT              -- a visit's calendar title; NULL = „Erstgespräch KI bei <Firma> – Christoph Bauer" (schema 8)
invite_email            TEXT              -- a visit's invitee; NULL = no invitation (schema 8)
calendar_state          TEXT              -- server-owned: 'pending' | 'ok' | 'error'; NULL for callbacks (schema 8)
calendar_error          TEXT              -- server-owned: the text behind 'error' (schema 8)
dirty                   INTEGER NOT NULL DEFAULT 0
```

Below the paragraph that begins „`kind` is nullable on both sides", add:

```markdown
Schema 8: a visit reaches the calendar through the server, which creates,
moves and removes its event through the Infomaniak API and sends the
invitation to `invite_email`. The app never writes a visit's event. The
server owns `calendar_state`, `calendar_error` and a visit's `event_uid`: the
app never sends the first two, and takes all three from the server even where
it keeps its own, newer row — the server writes them without moving
`updated_at`. A callback's `event_uid` stays the app's. A visit is read back
from the calendar only with a UID, the state `ok` and nothing waiting to go up.
```

- [ ] **Step 2: `docs/usage.md`**

In „Appointments on site", above the paragraph beginning „The location is prefilled" (as the business-addresses plan rewrote it), add:

```
**Titel** is what the calendar entry is called, preset to „Erstgespräch KI bei
<Betrieb> – Christoph Bauer". Left empty, the preset is used.
```

Replace

```
**Notiz** says what the appointment is for — „Besichtigung", „Angebot" — and
goes into the calendar entry's title. **Ansprechpartner** picks who to ask for
on site; their name and number go into the entry.
```

with

```
**Notiz** says what the appointment is for — „Besichtigung", „Angebot". It stays
in the app. **Ansprechpartner** picks who to ask for on site.

**Einladung senden** invites somebody: pick one of the contact person's
addresses or type one; picking another person brings their first address along
unless you chose one yourself. The invitation comes from christoph@bauer-ki.de;
moving the visit sends an update, removing it a cancellation. The invitee sees
title, time and place — never the note. Changing only the note sends nothing.
```

Replace the list

```
- **Verknüpfen** — this is that appointment. The existing entry is left exactly
  as it is, and the business is linked to it; its time and place win. An entry
  another appointment already holds is not offered.
- **Trotzdem anlegen** — two things at once, deliberately.
- **Andere Zeit** — back to the strip.
```

with

```
- **Verknüpfen** — for a callback only: this is that appointment. The existing
  entry is left exactly as it is, and the callback is linked to it; its time
  wins. An entry another appointment already holds is not offered.
- **Trotzdem anlegen** — two things at once, deliberately.
- **Andere Zeit** — back to the strip.
```

Replace

```
Saving writes the appointment, sets the status to „Termin" if it is still
ahead, and — if the calendar is switched on in the settings — puts an entry in
the chosen calendar. Without a calendar, or without the permission, the
appointment still lives in the app; the strip then says so rather than
pretending the day is free.

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

with

```
Saving writes the appointment and sets the status to „Termin" if it is still
ahead — offline too. The sync server then puts it into the Infomaniak calendar,
whatever the calendar switch in the settings says; DAVx5 brings the entry onto
the phones. Under the appointment the detail view says where it stands:
„Wird im Kalender angelegt …", „Im Kalender · Eingeladen: <Adresse>", or what
went wrong, in red. Without a sync server a visit does not reach the calendar.
Without calendar permission the strip says so rather than pretending the day is
free.

**Moving it in the calendar is enough.** Shift the entry in the web calendar or
on a laptop, and the app takes the new time, place and title over the next time
the record is opened, without asking — once the server has put the latest
version into the calendar, and once this phone has seen the entry before: an
entry seen for the first time is only taken note of when it matches. Delete the
entry in the calendar and the appointment is removed — said out loud, because
the status falls back to „Angerufen" once no other appointment is ahead. Not so
with an invitation: the detail view says „Im Kalender nicht mehr gefunden" and
offers **Termin entfernen**, which asks first, as the invitee would get a
cancellation. If the entry turns up again, the note goes. A past entry that a
calendar clears out on its own leaves the appointment in place.

**Entfernen** asks first, for a past appointment too: it removes a piece of the
record. With an invitation it names who gets the cancellation.
```

- [ ] **Step 3: `README.md`**

Replace

```
- **Appointments on site and callbacks**, as many per business as the work
  needs, each with a time, a length, a note and a contact person, mirrored into
  the device's calendar; a call completes the callbacks due
```

with

```
- **Appointments on site and callbacks**, as many per business as the work
  needs, each with a time, a length, a note and a contact person. Visits reach
  the calendar through the sync server, with an invitation if wanted; callbacks
  are mirrored into the device's calendar; a call completes the callbacks due
```

Replace

```
Calendar access is asked for only when you switch appointments on in the
settings. Refuse it, or leave it off, and appointments still work — they simply
stay in the app, and the picker says it cannot show you which hours are taken.
```

with

```
Calendar access is asked for only when you switch the calendar on in the
settings. It writes callbacks into the device calendar and reads appointments
back; visits reach the calendar through the sync server either way. Refuse it,
or leave it off, and appointments still work — the picker then says it cannot
show you which hours are taken.
```

- [ ] **Step 4: `CHANGELOG.md`**

Use the result of Task 0, Step 3. Without a tag `v1.5.0`, append these bullets at the end of the `## 1.5.0` section, above `## 1.4.0`. With the tag, append them to the `## 1.6.0` section — the business-addresses plan created it; if it did not, add the heading above `## 1.5.0`:

```markdown
- **Visits go into the calendar through the sync server**, no longer through
  the phone's calendar. Each visit has a **Titel**, preset to „Erstgespräch KI
  bei <Betrieb> – Christoph Bauer".
- **Einladung senden**: a real calendar invitation from christoph@bauer-ki.de,
  with an update when the visit moves and a cancellation when it is removed.
  The invitee sees title, time and place, never the note.
- Under each visit the detail view says whether it is in the calendar yet, who
  is invited, or what went wrong.
- An invited visit whose entry is gone from the calendar is not removed by
  itself: the detail view says „Im Kalender nicht mehr gefunden" and offers
  **Termin entfernen**.
- **Update the sync server first, with its Infomaniak token, then every phone.**
  Visits already in the calendar are test entries: delete them in the app and
  in the calendar before updating — required, or the server puts a second entry
  beside each of them.
```

- [ ] **Step 5: Commit**

```bash
git add docs/data-model.md docs/usage.md README.md CHANGELOG.md
git commit -m "Doku und CHANGELOG: Besuche über den Server, mit Einladung"
```

---

### Task 8: On the phone, and the release — with the user

**Files:** none, except a note in the spec's test result if the user wants one.

This task needs the server deployed with `INFOMANIAK_TOKEN` and `INFOMANIAK_CALENDAR_ID` — the other person's work. Ask the user whether it is. Do not handle the token.

- [ ] **Step 1: Ask the user before installing on the phone**

Build: `./gradlew assembleDebug`. Ask the user to delete the test visits in the app and their events in the calendar (spec, Rollout 1). This is mandatory: wait for the user to confirm it is done — an old visit saved again would get a second event from the server. Only then ask the user to install the debug build.

- [ ] **Step 2: Walk the spec's phone checks with the user**

With `test@example.org`, one at a time, the user reports each result:

1. Visit with invitation → invitation arrives; the event appears on the phone through DAVx5; the business shows „Im Kalender · Eingeladen: test@example.org".
2. Move it in the app → an update arrives.
3. Change only the note → no mail.
4. Move it in the Infomaniak web calendar, wait for DAVx5, open the business → the app shows the new time; no second mail beyond the one the web calendar offered.
5. Switch the invitation off → does a cancellation reach `test@example.org`? Tell the server's owner the result for `server/README.md`.
6. Remove it → the dialog names `test@example.org`; a cancellation arrives.
7. Visit without invitation → in the calendar, no mail.
8. Airplane mode, save a visit → „Wird im Kalender angelegt …"; back online → „Im Kalender".
9. A callback → still written into the device calendar as „Rückruf <Betrieb>", tick after a call.
10. The view model's own behaviour, which no unit test covers: saving a visit syncs twice (the line moves from „Wird im Kalender angelegt …" to „Im Kalender" without a tap); picking another contact person with the invitation on brings their first address, a typed address stays; with a visit still pending, opening the business syncs and the line moves on.
11. An invited visit (seen on this phone once), then the calendar deselected in DAVx5, open the business → nothing is removed; „Im Kalender nicht mehr gefunden" with „Termin entfernen". Select the calendar again, let DAVx5 sync, open the business → the hint is gone. Deselect again and tap „Termin entfernen" → the dialog names `test@example.org`; „Entfernen" → a cancellation arrives.
12. The same without an invitation → the visit is removed with the hint „Der Termin wurde im Kalender gelöscht …".
13. Besuch bei importiertem Betrieb ohne Ansprechpartner: Einladung ist mit der Betriebs-E-Mail vorbelegt.

Any failure: stop, use superpowers:systematic-debugging, fix in a task of its own with a test.

- [ ] **Step 3: Release — only on the user's word**

Ask the user whether to release. Only then run the project's release script (`tools/release.sh`, see `docs/development.md`) as that document describes.
