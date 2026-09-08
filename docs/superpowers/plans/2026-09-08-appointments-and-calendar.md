# Appointments and the calendar — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an appointment its own time, duration and location on a business, mirrored into the device calendar, so an on-site visit stops living in a status flag.

**Architecture:** Four working columns and two coordinate columns on `businesses`, behind the app's first schema migration. A `calendar/` package that writes to and reads from `CalendarContract` — the same arrangement `contacts/` already has with `ContactsContract`, where DAVx5 does the protocol. The detail view gains a section and a bottom sheet; the calendar owns the time and wins on read-back.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, SQLite through `SQLiteOpenHelper`, `CalendarContract`, JUnit 4 + Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-08-appointments-and-calendar-design.md`

## Global Constraints

- **Language split:** code, identifiers, comments and `docs/` in English; the user interface in German. Values from the import file stay German.
- **No legal statements** anywhere — in code, docs or UI copy. Describe what the code does.
- **The public repo is `main`.** Never push `master`.
- **Working fields are sacred:** an import must never overwrite `status`, `note`, `follow_up_at` or any of the four appointment columns.
- **Only ever offered, never applied** (`CallFlow.kt:12`) — the app proposes, the user decides. No automatic status changes the user did not trigger.
- **Every timestamp is ISO-8601 with a zone offset**, `yyyy-MM-dd'T'HH:mm:ssXXX`, produced through `Clock.format`. Never a bare date.
- **Permissions are optional.** Refused calendar access must leave every other part of the feature working.
- **minSdk 30, compileSdk 37.** Kotlin 2.4.20, AGP 9.4.0.
- Tests: `./gradlew testDebugUnitTest`. A single class: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`.
- Commit messages in German, imperative or descriptive, no attribution lines.
- **Test names in English**, like all 26 that already exist. Only the fixture
  method is German (`fun aufbau()`) — keep that, it is the established name.
- **State writes:** the view model today writes `_state.value = _state.value.copy(...)` and never uses `update`. This plan's snippets use `_state.update { … }`, which is the thread-safe form. Add `import kotlinx.coroutines.flow.update` once in Task 7 and use it consistently from there on; do not convert the existing call sites as part of this work.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `calling/Appointment.kt` | Pure appointment arithmetic: end from duration, duration from end, overlap detection, address formatting, readable range. No Android imports, so it tests without Robolectric. |
| `calendar/CalendarStore.kt` | Every `CalendarContract` write and single-event read. Knows nothing about businesses. |
| `calendar/BusyTimes.kt` | One `Instances` query per day, returning `BusyInterval`s from every visible calendar. |
| `ui/AppointmentSheet.kt` | The bottom sheet: day row, timeline, duration chips, location field. |
| `app/src/test/.../AppointmentTest.kt` | Tests for `Appointment`. Plain JUnit. |

Robolectric 4.16 ships **no** shadow for `CalendarContract` — the jar this
project pulls contains no calendar provider, and none for contacts either. An
unregistered authority stores nothing, so a test that inserts an event and reads
it back would only be testing `ShadowContentResolver`.

This is the question `PhoneBook.kt` already answered: 287 lines, no test, while
the pure `ContactMerge` is tested. `calendar/` follows it. Every decision worth
testing lives in `Appointment.kt` and is tested there; `CalendarStore` and
`BusyTimes` stay thin enough to read in one sitting, fail soft on everything,
and get checked by hand in Task 15.

**Modified:**

| File | Change |
|---|---|
| `data/Database.kt` | Six columns, `VERSION` 2, a real `onUpgrade`. |
| `data/Models.kt` | Six fields on `Business`, two on `ImportedBusiness`. |
| `data/Repository.kt` | Read the six columns, `setAppointment`, `appointmentsDue`, coordinates on import. |
| `data/Importer.kt` | Read `location.lat` / `location.lng`. |
| `contacts/Preferences.kt` | `calendarEnabled`, `calendarId`, `appointmentMinutes`. |
| `ui/BusinessDetail.kt` | The `Termin vor Ort` section, the tappable address, the sheet's host. |
| `ui/Settings.kt` | Calendar picker beside the address book picker. |
| `ui/Components.kt` | `showAppointment` on the business row, for "Today". |
| `ui/Today.kt` | An appointments group above the follow-ups. |
| `CallsheetViewModel.kt` | State and actions for the appointment. |
| `app/src/main/AndroidManifest.xml` | `READ_CALENDAR`, `WRITE_CALENDAR`, a `geo:` query entry. |
| `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md` | Documentation. |

The order below is deliberate: everything testable without a screen comes first, so a broken foundation surfaces before any Compose code exists.

---

### Task 1: The migration and the six columns

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: columns `appointment_at`, `appointment_end_at`, `appointment_location`, `calendar_event_id`, `latitude`, `longitude` on `businesses`; `Database.VERSION == 2`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's first migration. A version 1 database carries weeks of phone calls;
 * losing it would be the worst bug this app could have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun aufbau() {
        context.deleteDatabase("callsheet.db")
    }

    /** The version 1 schema, exactly as it shipped. Do not "tidy" this up. */
    private fun createVersionOne() {
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            """
            CREATE TABLE businesses (
                place_id        TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                industry        TEXT,
                categories      TEXT,
                street          TEXT,
                postal_code     TEXT,
                city            TEXT,
                phone           TEXT,
                website         TEXT,
                email           TEXT,
                contact_name    TEXT,
                rating          REAL,
                rating_count    INTEGER,
                closed          INTEGER NOT NULL DEFAULT 0,
                is_target       INTEGER NOT NULL DEFAULT 0,
                origin          TEXT,
                collected_at    TEXT,
                status          TEXT NOT NULL DEFAULT 'new',
                note            TEXT,
                follow_up_at    TEXT,
                updated_at      TEXT NOT NULL,
                search_text     TEXT
            )
            """.trimIndent()
        )
        db.version = 1
        db.insert("businesses", null, ContentValues().apply {
            put("place_id", "alt-1")
            put("name", "Bestandsbetrieb")
            put("status", "called")
            put("note", "Rückruf zugesagt")
            put("follow_up_at", "2026-09-10T09:00:00+02:00")
            put("updated_at", "2026-09-07T12:00:00+02:00")
        })
        db.close()
    }

    @Test
    fun `the migration keeps the working data`() {
        createVersionOne()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT status, note, follow_up_at FROM businesses WHERE place_id = ?", arrayOf("alt-1")).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("called", c.getString(0))
            assertEquals("Rückruf zugesagt", c.getString(1))
            assertEquals("2026-09-10T09:00:00+02:00", c.getString(2))
        }
    }

    @Test
    fun `the migration adds the six columns empty`() {
        createVersionOne()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT * FROM businesses WHERE place_id = ?", arrayOf("alt-1")).use { c ->
            assertTrue(c.moveToFirst())
            for (column in listOf(
                "appointment_at", "appointment_end_at", "appointment_location",
                "calendar_event_id", "latitude", "longitude",
            )) {
                val index = c.getColumnIndex(column)
                assertTrue("Spalte $column fehlt", index >= 0)
                assertTrue("Spalte $column ist nicht leer", c.isNull(index))
            }
        }
    }

    @Test
    fun `a fresh database has the same columns`() {
        val db = Database(context).readableDatabase

        db.rawQuery("SELECT * FROM businesses LIMIT 0", null).use { c ->
            for (column in listOf(
                "appointment_at", "appointment_end_at", "appointment_location",
                "calendar_event_id", "latitude", "longitude",
            )) {
                assertTrue("Spalte $column fehlt", c.getColumnIndex(column) >= 0)
            }
        }
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest"`
Expected: FAIL — `Spalte appointment_at fehlt`, because `onUpgrade` does nothing and `onCreate` has no such column.

- [ ] **Step 3: Add the columns to `onCreate`**

In `Database.kt`, inside the `CREATE TABLE businesses` block, after the `search_text` line:

```kotlin
                search_text     TEXT,
                -- Appointment on site. Working fields, like status and
                -- follow_up_at: an import never overwrites them.
                appointment_at       TEXT,
                appointment_end_at   TEXT,
                appointment_location TEXT,
                -- The linked event in the device calendar, null while none exists.
                calendar_event_id    INTEGER,
                -- Master data from the import, filled like every other imported
                -- column. Nothing reads them yet.
                latitude             REAL,
                longitude            REAL
```

- [ ] **Step 4: Write the migration**

Replace `onUpgrade` and bump `VERSION`:

```kotlin
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Never discard working data — only add to it.
        if (old < 2) {
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_at TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_end_at TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN appointment_location TEXT")
            db.execSQL("ALTER TABLE businesses ADD COLUMN calendar_event_id INTEGER")
            db.execSQL("ALTER TABLE businesses ADD COLUMN latitude REAL")
            db.execSQL("ALTER TABLE businesses ADD COLUMN longitude REAL")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_businesses_appointment ON businesses(appointment_at)")
        }
    }
```

And in the companion object:

```kotlin
        const val VERSION = 2
```

Add the matching index to `onCreate`, next to the other `CREATE INDEX` calls:

```kotlin
        db.execSQL("CREATE INDEX idx_businesses_appointment ON businesses(appointment_at)")
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest"`
Expected: PASS, three tests.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. `RepositoryTest` reads `SELECT *` and must be unaffected.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt \
        app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt
git commit -m "Datenbank v2: Terminspalten und Koordinaten"
```

---

### Task 2: The appointment on the model and in the repository

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt`

**Interfaces:**
- Consumes: the columns from Task 1.
- Produces:
  - `Business.appointmentAt: String?`, `.appointmentEndAt: String?`, `.appointmentLocation: String?`, `.calendarEventId: Long?`, `.latitude: Double?`, `.longitude: Double?`
  - `suspend fun Repository.setAppointment(placeId: String, at: String?, endAt: String?, location: String?, eventId: Long?)`
  - `suspend fun Repository.appointmentsDue(fromMillis: Long, toMillis: Long): List<Business>`

- [ ] **Step 1: Write the failing tests**

Append to `RepositoryTest.kt`, inside the class:

```kotlin
    // -------------------------------------------------------------- Appointment

    @Test
    fun `an appointment is stored and read back`() = runTest {
        import("""[{"placeId":"t-1","title":"Gartenbau Merten","phone":"+49 841 111"}]""")

        repo.setAppointment(
            placeId = "t-1",
            at = "2026-09-10T14:00:00+02:00",
            endAt = "2026-09-10T15:00:00+02:00",
            location = "Zehentstraße 39, 85055 Ingolstadt",
            eventId = 4711L,
        )

        val business = repo.business("t-1")!!
        assertEquals("2026-09-10T14:00:00+02:00", business.appointmentAt)
        assertEquals("2026-09-10T15:00:00+02:00", business.appointmentEndAt)
        assertEquals("Zehentstraße 39, 85055 Ingolstadt", business.appointmentLocation)
        assertEquals(4711L, business.calendarEventId)
    }

    @Test
    fun `an appointment can be cleared completely`() = runTest {
        import("""[{"placeId":"t-2","title":"Gartenbau Merten","phone":"+49 841 111"}]""")
        repo.setAppointment("t-2", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Irgendwo", 12L)

        repo.setAppointment("t-2", null, null, null, null)

        val business = repo.business("t-2")!!
        assertNull(business.appointmentAt)
        assertNull(business.appointmentEndAt)
        assertNull(business.appointmentLocation)
        assertNull(business.calendarEventId)
    }

    @Test
    fun `a second import leaves the appointment alone`() = runTest {
        import("""[{"placeId":"t-3","title":"Gartenbau Merten","phone":"+49 841 111"}]""")
        repo.setAppointment("t-3", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Zehentstraße 39", 99L)

        import("""[{"placeId":"t-3","title":"Gartenbau Merten GmbH","phone":"+49 841 222"}]""")

        val business = repo.business("t-3")!!
        assertEquals("Gartenbau Merten GmbH", business.name)
        assertEquals("2026-09-10T14:00:00+02:00", business.appointmentAt)
        assertEquals(99L, business.calendarEventId)
    }

    @Test
    fun `appointmentsDue returns the day's appointments in order`() = runTest {
        import(
            """[
              {"placeId":"t-4","title":"Spaeter","phone":"+49 841 111"},
              {"placeId":"t-5","title":"Frueher","phone":"+49 841 222"},
              {"placeId":"t-6","title":"Uebermorgen","phone":"+49 841 333"}
            ]"""
        )
        repo.setAppointment("t-4", "2026-09-10T15:00:00+02:00", "2026-09-10T16:00:00+02:00", null, null)
        repo.setAppointment("t-5", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00", null, null)
        repo.setAppointment("t-6", "2026-09-12T09:00:00+02:00", "2026-09-12T10:00:00+02:00", null, null)

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!
        val due = repo.appointmentsDue(from, until)

        assertEquals(listOf("t-5", "t-4"), due.map { it.placeId })
    }

    @Test
    fun `a past appointment is not due today`() = runTest {
        import("""[{"placeId":"t-9","title":"Vorletzte Woche","phone":"+49 841 111"}]""")
        repo.setAppointment("t-9", "2026-08-27T09:00:00+02:00", "2026-08-27T10:00:00+02:00", null, null)

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!

        assertTrue(repo.appointmentsDue(from, until).isEmpty())
    }

    @Test
    fun `a blocked business never appears in appointmentsDue`() = runTest {
        import("""[{"placeId":"t-7","title":"Gesperrt","phone":"+49 841 111"}]""")
        repo.setAppointment("t-7", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00", null, null)
        repo.setStatus("t-7", Status.DO_NOT_CALL)

        val from = Clock.millis("2026-09-10T00:00:00+02:00")!!
        val until = Clock.millis("2026-09-11T00:00:00+02:00")!!

        assertTrue(repo.appointmentsDue(from, until).isEmpty())
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: FAIL to compile — `setAppointment` and `appointmentsDue` do not exist.

- [ ] **Step 3: Extend the model**

In `Models.kt`, add to `Business` after `updatedAt`, before `additionalNumbers`:

```kotlin
    /** The appointment on site. Null when none is set. */
    val appointmentAt: String? = null,
    val appointmentEndAt: String? = null,
    val appointmentLocation: String? = null,
    /** The linked event in the device calendar. */
    val calendarEventId: Long? = null,
    /** From the import. Nothing reads them yet. */
    val latitude: Double? = null,
    val longitude: Double? = null,
```

And add to `ImportedBusiness` (same file), after `collectedAt`:

```kotlin
    val latitude: Double? = null,
    val longitude: Double? = null,
```

- [ ] **Step 4: Read the columns in `fromCursor`**

In `Repository.kt`, in `fromCursor`, after `updatedAt`:

```kotlin
        appointmentAt = c.text("appointment_at"),
        appointmentEndAt = c.text("appointment_end_at"),
        appointmentLocation = c.text("appointment_location"),
        calendarEventId = c.long("calendar_event_id"),
        latitude = c.decimal("latitude"),
        longitude = c.decimal("longitude"),
```

Add the cursor helper next to `Cursor.int`:

```kotlin
    private fun Cursor.long(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }
```

- [ ] **Step 5: Write the repository methods**

In `Repository.kt`, in the writing section after `setFollowUp`:

```kotlin
    /**
     * Sets the appointment, or clears it when [at] is null. All four fields move
     * together — a time without an end, or an event id without a time, would be
     * a state nothing else in the app knows how to read.
     */
    suspend fun setAppointment(
        placeId: String,
        at: String?,
        endAt: String?,
        location: String?,
        eventId: Long?,
    ) = withContext(Dispatchers.IO) {
        updateBusiness(placeId) {
            if (at == null) putNull("appointment_at") else put("appointment_at", at)
            if (endAt == null) putNull("appointment_end_at") else put("appointment_end_at", endAt)
            if (location == null) putNull("appointment_location") else put("appointment_location", location)
            if (eventId == null) putNull("calendar_event_id") else put("calendar_event_id", eventId)
        }
    }
```

And in the reading section, next to `due`:

```kotlin
    /**
     * Appointments starting between [fromMillis] and [toMillis], earliest first.
     *
     * Note the lower bound, which [due] does not have. An overdue follow-up is
     * still work to do — "you never rang back". An appointment from a fortnight
     * ago is not; it happened, or it did not, and either way it does not belong
     * under a heading that reads "today".
     */
    suspend fun appointmentsDue(fromMillis: Long, toMillis: Long): List<Business> =
        withContext(Dispatchers.IO) {
            val sql = "SELECT b.*, $NUMBERS_SUBQUERY FROM businesses b WHERE status <> ? " +
                "AND appointment_at IS NOT NULL AND appointment_at <> ''"
            helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
                allBusinesses(c)
                    .mapNotNull { b -> Clock.millis(b.appointmentAt)?.let { it to b } }
                    .filter { it.first in fromMillis..toMillis }
                    .sortedBy { it.first }
                    .map { it.second }
            }
        }
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt \
        app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Termin am Betrieb: lesen, schreiben, fällige Termine"
```

---

### Task 3: Coordinates from the import file

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Importer.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt` (`importedValues`)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/ImporterTest.kt`

**Interfaces:**
- Consumes: `ImportedBusiness.latitude` / `.longitude` from Task 2.
- Produces: `latitude` and `longitude` filled on every import.

- [ ] **Step 1: Write the failing test**

The API is `Importer.read(text: String): List<ImportedBusiness>` (`Importer.kt:23`) — it takes the text, not a stream. Append to `ImporterTest.kt`:

```kotlin
    @Test
    fun `coordinates are read from location`() {
        val json = """
            [{"placeId":"k-1","title":"Gartenbau Merten",
              "location":{"lat":48.8059466,"lng":11.4058554}}]
        """.trimIndent()

        val business = Importer.read(json).single()

        assertEquals(48.8059466, business.latitude!!, 0.0000001)
        assertEquals(11.4058554, business.longitude!!, 0.0000001)
    }

    @Test
    fun `a missing location field yields no coordinates`() {
        val business = Importer.read("""[{"placeId":"k-2","title":"Ohne Ort"}]""").single()

        assertNull(business.latitude)
        assertNull(business.longitude)
    }
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.ImporterTest"`
Expected: FAIL — `latitude` is null.

- [ ] **Step 3: Read the nested object**

In `Importer.kt`, in `one(o: JSONObject)`, before the `return`:

```kotlin
        val location = if (o.has("location") && !o.isNull("location")) o.optJSONObject("location") else null
```

and in the `ImportedBusiness(...)` call, after `collectedAt`:

```kotlin
            latitude = location?.decimal("lat"),
            longitude = location?.decimal("lng"),
```

No new helper: `private fun JSONObject.decimal(key: String): Double?` already sits at `Importer.kt:93` and does exactly this, `JSONObject.NULL` handling included.

- [ ] **Step 4: Write them into the database**

In `Repository.kt`, in `importedValues`, after the `city` line:

```kotlin
        put("latitude", s.latitude)
        put("longitude", s.longitude)
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Importer.kt \
        app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/test/java/io/github/amadeusb/callsheet/ImporterTest.kt
git commit -m "Koordinaten aus der Importdatei übernehmen"
```

---

### Task 4: Appointment arithmetic

**Files:**
- Create: `app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt` (create)

**Interfaces:**
- Consumes: `Clock`.
- Produces:
  - `data class BusyInterval(val startMillis: Long, val endMillis: Long, val title: String, val eventId: Long? = null)`
  - `Appointment.DEFAULT_MINUTES: Int` (60), `Appointment.DURATIONS: List<Int>` (30, 60, 90, 120)
  - `Appointment.endOf(startIso: String, minutes: Int): String`
  - `Appointment.minutesBetween(startIso: String?, endIso: String?): Int`
  - `Appointment.overlapping(startMillis: Long, endMillis: Long, busy: List<BusyInterval>): List<BusyInterval>`
  - `Appointment.address(street: String?, postalCode: String?, city: String?): String?`
  - `Appointment.readableRange(startIso: String?, endIso: String?): String`

This is deliberately free of Android imports: the decisions worth testing are arithmetic, and they should test in milliseconds, not in an emulator.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AppointmentTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Clock.zone).toInstant().toEpochMilli()

    private fun busy(fromHour: Int, toHour: Int, title: String, eventId: Long? = null) = BusyInterval(
        startMillis = millis(2026, 9, 10, fromHour),
        endMillis = millis(2026, 9, 10, toHour),
        title = title,
        eventId = eventId,
    )

    // --- endOf / minutesBetween --------------------------------------------

    @Test
    fun `endOf adds the duration`() {
        val end = Appointment.endOf("2026-09-10T14:00:00+02:00", 90)

        assertEquals(millis(2026, 9, 10, 15, 30), Clock.millis(end))
    }

    @Test
    fun `endOf does not correct onto a workday`() {
        // Samstag, 18:30 — als Termin ausdrücklich erlaubt. Anders als bei der
        // Wiedervorlage entscheidet hier der Kunde, nicht die App.
        val end = Appointment.endOf("2026-09-12T18:30:00+02:00", 60)

        assertEquals(millis(2026, 9, 12, 19, 30), Clock.millis(end))
    }

    @Test
    fun `minutesBetween reads the duration back`() {
        val minutes = Appointment.minutesBetween(
            "2026-09-10T14:00:00+02:00",
            "2026-09-10T15:30:00+02:00",
        )

        assertEquals(90, minutes)
    }

    @Test
    fun `minutesBetween falls back to the default without an end`() {
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween("2026-09-10T14:00:00+02:00", null))
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween(null, null))
    }

    // --- overlapping --------------------------------------------------------

    @Test
    fun `an overlap is found`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"), busy(16, 17, "Steuerbüro"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 9, 30),
            millis(2026, 9, 10, 10, 30),
            busyTimes,
        )

        assertEquals(listOf("Baustelle Nord"), hits.map { it.title })
    }

    @Test
    fun `back-to-back appointments do not overlap`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 10),
            millis(2026, 9, 10, 11),
            busyTimes,
        )

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `an enclosed appointment counts as an overlap`() {
        val busyTimes = listOf(busy(9, 10, "Baustelle Nord"))

        val hits = Appointment.overlapping(
            millis(2026, 9, 10, 8),
            millis(2026, 9, 10, 12),
            busyTimes,
        )

        assertEquals(1, hits.size)
    }

    // --- address ------------------------------------------------------------

    @Test
    fun `the address is joined onto one line`() {
        assertEquals(
            "Zehentstraße 39, 85055 Ingolstadt",
            Appointment.address("Zehentstraße 39", "85055", "Ingolstadt"),
        )
    }

    @Test
    fun `missing parts of the address drop out`() {
        assertEquals("Ingolstadt", Appointment.address(null, null, "Ingolstadt"))
        assertEquals("Zehentstraße 39", Appointment.address("Zehentstraße 39", null, null))
        assertNull(Appointment.address(null, null, null))
        assertNull(Appointment.address(" ", "", null))
    }

    // --- readableRange ------------------------------------------------------

    @Test
    fun `the range reads in German`() {
        assertEquals(
            "Do, 10.09. · 14:00 – 15:00",
            Appointment.readableRange("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00"),
        )
    }

    @Test
    fun `without an appointment there is a dash`() {
        assertEquals("—", Appointment.readableRange(null, null))
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile — `Appointment` does not exist.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt`:

```kotlin
package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A stretch of time already taken, as read from the device's calendars. */
data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    /**
     * The calendar event behind it. Without this, the conflict question could
     * not offer to link an appointment that already exists — it would only be
     * able to refuse or duplicate.
     */
    val eventId: Long? = null,
)

/**
 * The arithmetic behind an appointment on site.
 *
 * Deliberately free of Android: what is worth testing here is milliseconds and
 * overlap, not a content provider.
 *
 * Note what this does *not* do. [FollowUp] pushes every date it computes into a
 * workday between 8 and 18, because a follow-up is the app's own suggestion. An
 * appointment is what the customer agreed to on the phone, so it is taken
 * exactly as entered — Saturday evening included.
 */
object Appointment {

    /** The duration a fresh appointment starts at. */
    const val DEFAULT_MINUTES: Int = 60

    /** The durations offered as chips. */
    val DURATIONS: List<Int> = listOf(30, 60, 90, 120)

    private val range: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EE, dd.MM.", Locale.GERMAN)

    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** The end of an appointment starting at [startIso] and running [minutes]. */
    fun endOf(startIso: String, minutes: Int): String {
        val start = Clock.millis(startIso) ?: return startIso
        return Clock.format(start + minutes * 60_000L)
    }

    /**
     * How long an appointment runs. Falls back to [DEFAULT_MINUTES] when either
     * end is missing or unreadable, so the picker always has a length to show.
     */
    fun minutesBetween(startIso: String?, endIso: String?): Int {
        val start = Clock.millis(startIso) ?: return DEFAULT_MINUTES
        val end = Clock.millis(endIso) ?: return DEFAULT_MINUTES
        val minutes = ((end - start) / 60_000L).toInt()
        return if (minutes > 0) minutes else DEFAULT_MINUTES
    }

    /**
     * The busy intervals a proposed appointment runs into. Appointments that
     * merely touch — one ending as the next begins — do not overlap.
     */
    fun overlapping(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
    ): List<BusyInterval> = busy.filter { startMillis < it.endMillis && it.startMillis < endMillis }

    /** Street, postal code and city on one line. Null when nothing is known. */
    fun address(street: String?, postalCode: String?, city: String?): String? {
        val town = listOfNotNull(
            postalCode?.trim()?.ifEmpty { null },
            city?.trim()?.ifEmpty { null },
        ).joinToString(" ").ifEmpty { null }
        return listOfNotNull(street?.trim()?.ifEmpty { null }, town)
            .joinToString(", ")
            .ifEmpty { null }
    }

    /** For the interface: "Do, 10.09. · 14:00 – 15:00". */
    fun readableRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val from = Clock.zdt(start)
        val head = "${from.format(range)} · ${from.format(time)}"
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, eleven tests.

If `the range reads in German` fails on the weekday abbreviation, the JVM locale data is producing something other than `Do`. Do not weaken the assertion — fix the pattern to match the actual German short form and note what it produced.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Terminrechnung: Dauer, Überschneidung, Anschrift"
```

---

### Task 5: Preferences for the calendar

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/PreferencesTest.kt` (create)

**Interfaces:**
- Consumes: `Appointment.DEFAULT_MINUTES` from Task 4.
- Produces: `Preferences.calendarEnabled: Boolean`, `Preferences.calendarId: Long?`, `Preferences.appointmentMinutes: Int`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/amadeusb/callsheet/PreferencesTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.contacts.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesTest {

    private val preferences = Preferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `fresh settings have no calendar and 60 minutes`() {
        assertFalse(preferences.calendarEnabled)
        assertNull(preferences.calendarId)
        assertEquals(60, preferences.appointmentMinutes)
    }

    @Test
    fun `the chosen duration is remembered`() {
        preferences.appointmentMinutes = 90

        assertEquals(90, preferences.appointmentMinutes)
    }

    @Test
    fun `the calendar can be set and cleared`() {
        preferences.calendarId = 7L
        assertEquals(7L, preferences.calendarId)

        preferences.calendarId = null
        assertNull(preferences.calendarId)
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.PreferencesTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Write the implementation**

In `Preferences.kt`, add inside the class:

```kotlin
    /** Whether appointments get mirrored into the device calendar at all. */
    var calendarEnabled: Boolean
        get() = store.getBoolean(CALENDAR_ENABLED, false)
        set(value) = store.edit().putBoolean(CALENDAR_ENABLED, value).apply()

    /** The chosen calendar that gets written to. */
    var calendarId: Long?
        get() = store.getLong(CALENDAR_ID, NO_CALENDAR).takeIf { it != NO_CALENDAR }
        set(value) = store.edit().putLong(CALENDAR_ID, value ?: NO_CALENDAR).apply()

    /**
     * The duration a fresh appointment starts at, in minutes. It follows the
     * last appointment saved — a setting that tunes itself instead of one to go
     * looking for.
     */
    var appointmentMinutes: Int
        get() = store.getInt(APPOINTMENT_MINUTES, Appointment.DEFAULT_MINUTES)
        set(value) = store.edit().putInt(APPOINTMENT_MINUTES, value).apply()
```

Extend the companion object:

```kotlin
        const val CALENDAR_ENABLED = "calendar_enabled"
        const val CALENDAR_ID = "calendar_id"
        const val APPOINTMENT_MINUTES = "appointment_minutes"
        const val NO_CALENDAR = -1L
```

Add the import: `import io.github.amadeusb.callsheet.calling.Appointment`.

Note the existing companion object is `private`; leave it private and keep the new constants inside it.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.PreferencesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PreferencesTest.kt
git commit -m "Einstellungen: Kalender und gemerkte Termindauer"
```

---

### Task 6: The calendar package

**Files:**
- Create: `app/src/main/java/io/github/amadeusb/callsheet/calendar/CalendarStore.kt`
- Create: `app/src/main/java/io/github/amadeusb/callsheet/calendar/BusyTimes.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: none — see the note in File Structure. This task is checked by hand in
  Task 15; the decisions it feeds are tested in Task 4.

**Interfaces:**
- Consumes: `BusyInterval` from Task 4.
- Produces:
  - `data class CalendarAccount(val id: Long, val name: String, val accountName: String)`
  - `data class EventFields(val title: String, val startMillis: Long, val endMillis: Long, val location: String?, val description: String?)`
  - `CalendarStore.canRead(context): Boolean`, `.canWrite(context): Boolean`
  - `suspend CalendarStore.calendars(context): List<CalendarAccount>`
  - `suspend CalendarStore.insert(context, calendarId: Long, fields: EventFields): Long?`
  - `suspend CalendarStore.update(context, eventId: Long, fields: EventFields): Boolean`
  - `suspend CalendarStore.read(context, eventId: Long): EventFields?`
  - `suspend CalendarStore.delete(context, eventId: Long): Boolean`
  - `suspend BusyTimes.forDay(context, dayStartMillis: Long): List<BusyInterval>`

- [ ] **Step 1: Add the permissions**

In `app/src/main/AndroidManifest.xml`, after the contacts permissions (keep the German comment style the file already uses):

```xml
    <!--
        Für den Termin vor Ort: geschrieben wird nur in den Kalender, den der
        Nutzer in den Einstellungen wählt (in der Regel ein von DAVx5
        verwalteter CalDAV-Kalender). Gelesen wird aus allen sichtbaren
        Kalendern, damit der Terminpicker belegte Zeiten anzeigen kann.
    -->
    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" />
```

And inside `<queries>`, so the address can reach a map application:

```xml
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="geo" />
        </intent>
```

- [ ] **Step 2: Keep every call fail-soft**

There is no test to catch a mistake here, so the shape of the code has to carry
the safety instead. Every method in Task 6 obeys three rules:

1. **Check the permission first** and return the empty answer without touching
   the resolver — `emptyList()`, `null` or `false`.
2. **Wrap the provider call in `runCatching`** and turn a throw into that same
   empty answer. A calendar that has gone away must not take the app with it.
3. **Return no partial state.** A half-written event is worse than none; either
   `insert` returns an id or it returns null.

A reviewer should be able to confirm all three by reading, which is the point.

- [ ] **Step 3: Write `CalendarStore`**

Create `app/src/main/java/io/github/amadeusb/callsheet/calendar/CalendarStore.kt`:

```kotlin
package io.github.amadeusb.callsheet.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.amadeusb.callsheet.data.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A calendar on the device, as offered in the settings. */
data class CalendarAccount(val id: Long, val name: String, val accountName: String)

/** An appointment as the app writes it into the calendar. */
data class EventFields(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
)

/**
 * Appointments in the device calendar. Written into the calendar the user picks
 * in the settings — typically one DAVx5 keeps in sync. The app synchronises
 * nothing itself; it only stores what DAVx5 then uploads.
 *
 * Every call fails soft. A refused permission, a calendar that has gone away, a
 * provider that throws: all of them return null or false, and the appointment
 * carries on living in the app.
 */
object CalendarStore {

    fun canRead(context: Context): Boolean = granted(context, Manifest.permission.READ_CALENDAR)

    fun canWrite(context: Context): Boolean = granted(context, Manifest.permission.WRITE_CALENDAR)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Every calendar that can be written to. */
    suspend fun calendars(context: Context): List<CalendarAccount> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val found = ArrayList<CalendarAccount>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                ),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    found.add(
                        CalendarAccount(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "Kalender",
                            accountName = c.getString(2) ?: "",
                        )
                    )
                }
            }
        }
        found
    }

    /** Creates an event and returns its id, or null when it could not be written. */
    suspend fun insert(context: Context, calendarId: Long, fields: EventFields): Long? =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) return@withContext null
            runCatching {
                val values = values(fields).apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, Clock.zone.id)
                }
                context.contentResolver
                    .insert(CalendarContract.Events.CONTENT_URI, values)
                    ?.lastPathSegment
                    ?.toLongOrNull()
            }.getOrNull()
        }

    /** Writes [fields] over an existing event. False when it could not be done. */
    suspend fun update(context: Context, eventId: Long, fields: EventFields): Boolean =
        withContext(Dispatchers.IO) {
            if (!canWrite(context)) return@withContext false
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.update(uri, values(fields), null, null) > 0
            }.getOrDefault(false)
        }

    /**
     * Reads an event back. Null when it is gone — deleted in the calendar, or
     * removed by a synchronisation. That null is the signal the detail view acts
     * on, so it must not be confused with an error.
     */
    suspend fun read(context: Context, eventId: Long): EventFields? = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext null
        runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.DELETED,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                if (c.getInt(5) == 1) return@use null
                EventFields(
                    title = c.getString(0) ?: "",
                    startMillis = c.getLong(1),
                    endMillis = c.getLong(2),
                    location = c.getString(3)?.ifBlank { null },
                    description = c.getString(4)?.ifBlank { null },
                )
            }
        }.getOrNull()
    }

    /** Deletes an event. False when it could not be done. */
    suspend fun delete(context: Context, eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!canWrite(context)) return@withContext false
        runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun values(fields: EventFields): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.TITLE, fields.title)
        put(CalendarContract.Events.DTSTART, fields.startMillis)
        put(CalendarContract.Events.DTEND, fields.endMillis)
        put(CalendarContract.Events.EVENT_LOCATION, fields.location)
        put(CalendarContract.Events.DESCRIPTION, fields.description)
    }
}
```

- [ ] **Step 4: Write `BusyTimes`**

Create `app/src/main/java/io/github/amadeusb/callsheet/calendar/BusyTimes.kt`:

```kotlin
package io.github.amadeusb.callsheet.calendar

import android.content.Context
import android.provider.CalendarContract
import io.github.amadeusb.callsheet.calling.BusyInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What is already taken on a given day.
 *
 * Read from **every** visible calendar, not only the one appointments get
 * written to. A private appointment that is invisible here is exactly the one an
 * on-site visit gets booked over.
 */
object BusyTimes {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** The occupied intervals of the day beginning at [dayStartMillis]. */
    suspend fun forDay(context: Context, dayStartMillis: Long): List<BusyInterval> =
        withContext(Dispatchers.IO) {
            if (!CalendarStore.canRead(context)) return@withContext emptyList()
            val until = dayStartMillis + DAY_MILLIS
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(dayStartMillis.toString())
                .appendPath(until.toString())
                .build()
            val found = ArrayList<BusyInterval>()
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.ALL_DAY,
                        CalendarContract.Instances.EVENT_ID,
                    ),
                    // "Every visible calendar" has to be said in the query, not
                    // only in the comment: a calendar the user has switched off
                    // is one they have decided not to be shown.
                    "${CalendarContract.Instances.VISIBLE} = 1",
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        // All-day entries say nothing about which hours are free.
                        if (c.getInt(3) == 1) continue
                        found.add(
                            BusyInterval(
                                startMillis = c.getLong(0),
                                endMillis = c.getLong(1),
                                title = c.getString(2)?.ifBlank { null } ?: "Termin",
                                eventId = c.getLong(4),
                            )
                        )
                    }
                }
            }
            found
        }
}
```

- [ ] **Step 5: Build, and check the suite still passes**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS. Nothing new is asserted here — this only
proves the package compiles and breaks nothing.

- [ ] **Step 6: Prove it against a real calendar**

On a device or emulator with at least one writable calendar and at least two
events on the same day, in different calendars:

1. Grant the calendar permission, then confirm `CalendarStore.calendars` returns
   every writable calendar and none of the read-only ones.
2. Insert an event through the app and find it in the calendar application, with
   the right day, time, length and location.
3. Confirm `BusyTimes.forDay` returns both events, in order, with their titles
   and a non-null `eventId` — including the one in the calendar the app does
   *not* write to. Without the id, **Verknüpfen** has nothing to link to and the
   conflict dialog can only offer two of its three answers.
   Then hide one of the calendars in the calendar application and confirm its
   entry disappears from the result.
4. Confirm an all-day entry does not appear in the result.
5. Delete the event in the calendar application, then confirm `read` returns null
   rather than throwing.

The quickest way to see the results is a temporary log line in the view model,
removed before the commit. This is the step that replaces the missing tests; do
not skip it and do not report the task done without it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calendar/ \
        app/src/main/AndroidManifest.xml
git commit -m "Kalenderpaket: Termine schreiben, lesen, belegte Zeiten"
```

---

### Task 7: The calendar picker in the settings

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/Settings.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`

**Interfaces:**
- Consumes: `CalendarStore.calendars`, `CalendarAccount`, `Preferences.calendarEnabled` / `.calendarId`.
- Produces: `State.calendarEnabled: Boolean`, `State.calendar: CalendarAccount?`, `State.calendars: List<CalendarAccount>`; `CallsheetViewModel.setCalendarEnabled(Boolean)`, `.pickCalendar(CalendarAccount)`, `.loadCalendars()`.

- [ ] **Step 1: Extend the state**

In `CallsheetViewModel.kt`, in `data class State`, after `phoneBookHint`:

```kotlin
    val calendarEnabled: Boolean = false,
    val calendar: CalendarAccount? = null,
    val calendars: List<CalendarAccount> = emptyList(),
```

Imports: `io.github.amadeusb.callsheet.calendar.CalendarAccount`, `io.github.amadeusb.callsheet.calendar.CalendarStore`.

- [ ] **Step 2: Write the actions**

In `CallsheetViewModel`, next to the phone book actions:

```kotlin
    /** Loads the device's calendars for the picker. */
    fun loadCalendars() {
        viewModelScope.launch {
            val found = CalendarStore.calendars(getApplication())
            val chosen = preferences.calendarId?.let { id -> found.firstOrNull { it.id == id } }
            _state.update { it.copy(calendars = found, calendar = chosen) }
        }
    }

    fun setCalendarEnabled(enabled: Boolean) {
        preferences.calendarEnabled = enabled
        _state.update { it.copy(calendarEnabled = enabled) }
        if (enabled) loadCalendars()
    }

    fun pickCalendar(calendar: CalendarAccount) {
        preferences.calendarId = calendar.id
        _state.update { it.copy(calendar = calendar) }
    }
```

Find where the existing code reads `preferences.phoneBookEnabled` into the initial state and add the calendar equivalents alongside:

```kotlin
            calendarEnabled = preferences.calendarEnabled,
```

- [ ] **Step 3: Add the block to the settings screen**

In `Settings.kt`, add parameters to `SettingsScreen`:

```kotlin
    calendarEnabled: Boolean,
    calendar: CalendarAccount?,
    calendars: List<CalendarAccount>,
    onCalendarToggle: (Boolean) -> Unit,
    onPickCalendar: (CalendarAccount) -> Unit,
```

Add a `var calendarPicker by remember { mutableStateOf(false) }` next to the existing `accountPicker`, an item for the block after the phone book item, and this composable modelled on `PhoneBookBlock`:

```kotlin
/**
 * Appointments in the device calendar. Written into the calendar the user picks
 * here — usually one DAVx5 keeps in sync. The app synchronises nothing itself.
 */
@Composable
private fun CalendarBlock(
    active: Boolean,
    calendar: CalendarAccount?,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "Termine vor Ort werden zusätzlich im Kalender des Geräts " +
                "abgelegt. Damit erinnert dich das Telefon daran und das Navi " +
                "kennt die Adresse. Was du im Kalender verschiebst, übernimmt " +
                "die App beim nächsten Öffnen der Akte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Termine in den Kalender", modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = onToggle)
        }
        if (active) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(calendar?.let { "Kalender: ${it.name}" } ?: "Kalender wählen")
            }
        }
    }
}
```

And the picker dialog, next to the address book one:

```kotlin
    if (calendarPicker) {
        AlertDialog(
            onDismissRequest = { calendarPicker = false },
            title = { Text("In welchen Kalender?") },
            text = {
                if (calendars.isEmpty()) {
                    Text(
                        "Kein beschreibbarer Kalender gefunden. Richte in DAVx5 " +
                            "einen Kalender ein und synchronisiere einmal."
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(calendars, key = { it.id }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPickCalendar(entry)
                                        calendarPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = entry.accountName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { calendarPicker = false }) { Text("Abbrechen") }
            },
        )
    }
```

- [ ] **Step 4: Wire up the call site**

In `MainActivity.kt`, add a launcher next to `contactPermissions` (line 67), built the same way:

```kotlin
    // Calendar permission: only once the user switches the calendar on —
    // before that the app has no use for it.
    val calendarPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { outcome ->
        vm.setCalendarEnabled(outcome.values.all { it })
    }
```

and pass the new parameters where `SettingsScreen` is called (around line 213), mirroring the `onPhoneBook` handler:

```kotlin
            calendarEnabled = state.calendarEnabled,
            calendar = state.calendar,
            calendars = state.calendars,
            onCalendarToggle = { on ->
                if (on && !CalendarStore.canWrite(context)) {
                    calendarPermissions.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                        )
                    )
                } else {
                    vm.setCalendarEnabled(on)
                }
            },
            onPickCalendar = vm::pickCalendar,
```

- [ ] **Step 5: Build and check by hand**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Install and open the settings: the switch appears, turning it on reveals the picker, the picker lists the device's calendars, and the chosen one survives a restart of the app.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/Settings.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Einstellungen: Kalender wählen"
```

---

### Task 8: Saving an appointment, end to end

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`
- Test: `app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt`

**Interfaces:**
- Consumes: `Repository.setAppointment`, `CalendarStore`, `BusyTimes`, `Appointment`, `Preferences.appointmentMinutes`.
- Produces:
  - `data class AppointmentDraft(...)` on `State.appointmentDraft`
  - `sealed interface SavePlan` with `Conflict(with: List<BusyInterval>)`, `Adopt(eventId: Long)`, `Update(eventId: Long)`, `Create`, `LocalOnly`
  - `Appointment.plan(startMillis, endMillis, busy, ownEventId, linkExisting, force, calendarEnabled): SavePlan`
  - `CallsheetViewModel.openAppointment(placeId)`, `.updateAppointmentDraft(draft)`, `.saveAppointment(linkExisting: Long? = null, force: Boolean = false)`, `.dismissAppointment()`, `.removeAppointment(placeId)`

Saving makes four decisions — is the window free, is there an event to adopt, is
there one to update, is the calendar on at all. They go into a pure function so
they can be tested; the view model only carries out the answer. Without that
split, the orchestration is exactly the untested part where the two worst bugs of
this feature would live.

- [ ] **Step 1: Add the draft to the state**

In `CallsheetViewModel.kt`:

```kotlin
/**
 * The appointment being set. Lives only while the sheet is open — cancelling
 * throws it away, and nothing has been written by then.
 */
data class AppointmentDraft(
    val placeId: String,
    val startIso: String,
    val minutes: Int,
    val location: String,
    /**
     * Everything already taken on that day, for the timeline. The business's own
     * event is filtered out: it would otherwise collide with itself on every
     * change, and the conflict question would be unanswerable.
     */
    val busy: List<BusyInterval> = emptyList(),
    /** What the chosen window runs into. Empty means it is free. */
    val conflict: List<BusyInterval> = emptyList(),
    /**
     * Whether the calendar could be read at all. Without this, an empty [busy]
     * would be indistinguishable from a genuinely free day — and telling the
     * user a day is free when the app simply cannot see it is the one lie this
     * feature must not tell.
     */
    val calendarReadable: Boolean = true,
)
```

and in `State`:

```kotlin
    val appointmentDraft: AppointmentDraft? = null,
```

- [ ] **Step 2: Write the failing tests for the save decision**

Append to `AppointmentTest.kt`:

```kotlin
    // --- plan ---------------------------------------------------------------

    private val slotStart = millis(2026, 9, 10, 14)
    private val slotEnd = millis(2026, 9, 10, 15)

    @Test
    fun `a free slot with the calendar on creates an event`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Create, plan)
    }

    @Test
    fun `a free slot with the calendar off writes only the columns`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = false,
        )

        assertEquals(SavePlan.LocalOnly, plan)
    }

    @Test
    fun `an existing own event is updated, not duplicated`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd, busy = emptyList(),
            ownEventId = 42L, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Update(42L), plan)
    }

    @Test
    fun `a taken slot asks before writing anything`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = null, force = false, calendarEnabled = true,
        )

        assertTrue(plan is SavePlan.Conflict)
        assertEquals(listOf("Steuerbüro"), (plan as SavePlan.Conflict).with.map { it.title })
    }

    @Test
    fun `force writes into a taken slot anyway`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = null, force = true, calendarEnabled = true,
        )

        assertEquals(SavePlan.Create, plan)
    }

    @Test
    fun `linking beats the conflict and never creates`() {
        val plan = Appointment.plan(
            startMillis = slotStart, endMillis = slotEnd,
            busy = listOf(busy(14, 15, "Steuerbüro")),
            ownEventId = null, linkExisting = 7L, force = false, calendarEnabled = true,
        )

        assertEquals(SavePlan.Adopt(7L), plan)
    }
```

Add `import io.github.amadeusb.callsheet.calling.SavePlan`.

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile — `plan` does not exist.

- [ ] **Step 4: Write the decision**

In `calling/Appointment.kt`:

```kotlin
/** What saving an appointment should do about the calendar. */
sealed interface SavePlan {
    /** The window is taken and nobody has said what to do about it yet. */
    data class Conflict(val with: List<BusyInterval>) : SavePlan

    /**
     * Take over an appointment that is already in the calendar. Its time and
     * place win and the event is left untouched — the app only records that the
     * two are the same thing. Writing the draft over it would rename and move
     * somebody else's entry, which is the one thing this feature must never do.
     */
    data class Adopt(val eventId: Long) : SavePlan

    /** Write the draft over the event this business already owns. */
    data class Update(val eventId: Long) : SavePlan

    /** Create a new event. */
    data object Create : SavePlan

    /** Columns only — the calendar is switched off or out of reach. */
    data object LocalOnly : SavePlan
}
```

and inside `object Appointment`:

```kotlin
    /**
     * What saving should do. The order matters: an explicit instruction from the
     * user beats a conflict, and a conflict beats everything else.
     */
    fun plan(
        startMillis: Long,
        endMillis: Long,
        busy: List<BusyInterval>,
        ownEventId: Long?,
        linkExisting: Long?,
        force: Boolean,
        calendarEnabled: Boolean,
    ): SavePlan {
        if (linkExisting != null) return SavePlan.Adopt(linkExisting)
        if (!force) {
            val clash = overlapping(startMillis, endMillis, busy)
            if (clash.isNotEmpty()) return SavePlan.Conflict(clash)
        }
        if (!calendarEnabled) return SavePlan.LocalOnly
        return if (ownEventId != null) SavePlan.Update(ownEventId) else SavePlan.Create
    }
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, twenty-one tests.

- [ ] **Step 6: Write the view model actions**

```kotlin
    /** Opens the sheet, prefilled from the business and the last duration used. */
    fun openAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            val start = business.appointmentAt ?: FollowUp.inTwoDays()
            val minutes = if (business.appointmentAt != null) {
                Appointment.minutesBetween(business.appointmentAt, business.appointmentEndAt)
            } else {
                preferences.appointmentMinutes
            }
            val location = business.appointmentLocation
                ?: Appointment.address(business.street, business.postalCode, business.city)
                ?: ""
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        calendarReadable = CalendarStore.canRead(getApplication()),
                    )
                )
            }
            loadBusy(Clock.millis(start)!!, business.calendarEventId)
        }
    }

    /**
     * Reads the busy times for the day containing [millis].
     *
     * [ownEventId] drops out of the result. An appointment being changed is
     * already in the calendar, so leaving it in would make every save collide
     * with itself — and the conflict question would offer to link an appointment
     * to itself.
     */
    private fun loadBusy(millis: Long, ownEventId: Long?) {
        viewModelScope.launch {
            val dayStart = Clock.todayStart(millis)
            val busy = BusyTimes.forDay(getApplication(), dayStart)
                .filter { it.eventId == null || it.eventId != ownEventId }
            _state.update { state ->
                val draft = state.appointmentDraft ?: return@update state
                state.copy(appointmentDraft = draft.copy(busy = busy, conflict = emptyList()))
            }
        }
    }

    fun updateAppointmentDraft(draft: AppointmentDraft) {
        val previous = _state.value.appointmentDraft
        _state.update { it.copy(appointmentDraft = draft.copy(conflict = emptyList())) }
        val previousDay = Clock.todayStart(Clock.millis(previous?.startIso) ?: 0L)
        val newDay = Clock.todayStart(Clock.millis(draft.startIso) ?: return)
        if (previous == null || previousDay != newDay) {
            viewModelScope.launch {
                loadBusy(Clock.millis(draft.startIso)!!, repo.business(draft.placeId)?.calendarEventId)
            }
        }
    }

    fun dismissAppointment() {
        _state.update { it.copy(appointmentDraft = null) }
    }

    /** Writes the appointment: the four columns, the status, and the calendar. */
    fun saveAppointment(linkExisting: Long? = null, force: Boolean = false) {
        val draft = _state.value.appointmentDraft ?: return
        viewModelScope.launch {
            val startMillis = Clock.millis(draft.startIso) ?: return@launch
            val endMillis = startMillis + draft.minutes * 60_000L
            val business = repo.business(draft.placeId)
            val location = draft.location.trim().ifEmpty { null }

            val plan = Appointment.plan(
                startMillis = startMillis,
                endMillis = endMillis,
                busy = draft.busy,
                ownEventId = business?.calendarEventId,
                linkExisting = linkExisting,
                force = force,
                calendarEnabled = preferences.calendarEnabled,
            )

            if (plan is SavePlan.Conflict) {
                _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
                return@launch
            }

            val fields = EventFields(
                title = "Ortstermin ${business?.name ?: ""}".trim(),
                startMillis = startMillis,
                endMillis = endMillis,
                location = location,
                description = business?.phone,
            )

            // Adopting takes the calendar's values, so the columns written below
            // differ per plan. Every other case writes the draft.
            var atIso = draft.startIso
            var endIso = Clock.format(endMillis)
            var place = location

            val eventId: Long? = when (plan) {
                is SavePlan.Adopt -> {
                    val event = CalendarStore.read(getApplication(), plan.eventId)
                    if (event != null) {
                        atIso = Clock.format(event.startMillis)
                        endIso = Clock.format(event.endMillis)
                        place = event.location ?: location
                    }
                    plan.eventId
                }
                is SavePlan.Update -> plan.eventId.takeIf {
                    CalendarStore.update(getApplication(), it, fields)
                }
                SavePlan.Create ->
                    preferences.calendarId?.let { CalendarStore.insert(getApplication(), it, fields) }
                SavePlan.LocalOnly -> null
                is SavePlan.Conflict -> null // already returned above
            }

            preferences.appointmentMinutes = draft.minutes
            repo.setAppointment(draft.placeId, atIso, endIso, place, eventId)
            repo.setStatus(draft.placeId, Status.APPOINTMENT)
            _state.update {
                it.copy(
                    appointmentDraft = null,
                    hint = if (preferences.calendarEnabled && plan !is SavePlan.LocalOnly && eventId == null) {
                        "Termin gespeichert. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    } else null,
                )
            }
            loadDetail(draft.placeId)
        }
    }

    /**
     * Removes the appointment and its calendar event.
     *
     * The status only falls back when it is still `appointment`. A business set
     * to `declined` or `do_not_call` in the meantime keeps that — those are
     * decisions the user made, and removing an appointment is not permission to
     * undo them.
     */
    fun removeAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            business.calendarEventId?.let { CalendarStore.delete(getApplication(), it) }
            repo.setAppointment(placeId, null, null, null, null)
            if (business.status == Status.APPOINTMENT) repo.setStatus(placeId, Status.CALLED)
            loadDetail(placeId)
        }
    }
```

Imports to add: `io.github.amadeusb.callsheet.calendar.BusyTimes`, `.CalendarStore`, `.EventFields`, `io.github.amadeusb.callsheet.calling.Appointment`, `.BusyInterval`, `.SavePlan`, `kotlinx.coroutines.flow.update`.

- [ ] **Step 7: Build**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Termin speichern: Spalten, Status und Kalendereintrag"
```

---

### Task 9: The appointment sheet

**Files:**
- Create: `app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt`

**Interfaces:**
- Consumes: `AppointmentDraft`, `Appointment`, `BusyInterval`, `Clock`.
- Produces: `@Composable fun AppointmentSheet(draft: AppointmentDraft, onDraft: (AppointmentDraft) -> Unit, onSave: () -> Unit, onLink: (Long) -> Unit, onForce: () -> Unit, onPickDate: () -> Unit, onDismiss: () -> Unit)`

Three things decide whether this screen works, and all three are easy to get
wrong:

**The timeline covers the whole day, not eight to eighteen.** `Appointment`
documents and tests that a Saturday at 18:30 is a legitimate appointment,
because the customer decided it. A strip that stops at 18 would make the case
the test celebrates unreachable in the interface. The strip therefore runs 0 to
24 in its own scroll area, opened at 7:00 — the working day is where it starts,
not where it ends.

**Dragging needs an accumulator.** `detectDragGestures` reports a few pixels per
frame. Rounding each frame to the nearest quarter hour yields zero every time
and the block never moves. The pixels have to add up across the gesture, and the
snap has to subtract what it consumed.

**`pointerInput` needs a stable key.** Keying it on anything that changes during
the drag restarts the recogniser mid-gesture. The key is `Unit`, and the current
draft reaches the gesture through `rememberUpdatedState` instead of the closure.

- [ ] **Step 1: Write the sheet**

Create `app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt`:

```kotlin
package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.AppointmentDraft
import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** An hour's height. A minute is therefore HOUR_HEIGHT / 60, everywhere. */
private val HOUR_HEIGHT = 56.dp

/** Where the strip opens. The working day is the common case, not the only one. */
private const val OPENS_AT_HOUR = 7

/** Nobody agrees an appointment at 14:07. */
private const val SNAP_MINUTES = 15

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EE", Locale.GERMAN)
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Setting an appointment on site. A sheet rather than a screen, so the business
 * stays visible behind it — the conversation that produced the appointment is
 * usually still going.
 *
 * The layout is header, scrolling timeline, footer, rather than one long scroll:
 * the day has to scroll without taking the save button off the screen with it.
 */
@Composable
fun AppointmentSheet(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onSave: () -> Unit,
    onLink: (Long) -> Unit,
    onForce: () -> Unit,
    onPickDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "Termin vor Ort",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionLabel("Tag")
            DayRow(draft = draft, onDraft = onDraft, onPickDate = onPickDate)

            SectionLabel("Uhrzeit")
            if (!draft.calendarReadable) {
                Text(
                    text = "Kalender nicht freigegeben — belegte Zeiten werden nicht " +
                        "angezeigt. Der Termin wird trotzdem gespeichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Timeline(draft = draft, onDraft = onDraft, modifier = Modifier.heightIn(max = 300.dp))

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

            if (draft.conflict.isNotEmpty()) {
                ConflictNotice(draft = draft, onDraft = onDraft, onLink = onLink, onForce = onForce)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Termin speichern")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * The next five days, starting with the one the draft is on. Picking a day keeps
 * the time of day — the hour is usually agreed before the date.
 */
@Composable
private fun DayRow(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onPickDate: () -> Unit,
) {
    val start = Clock.millis(draft.startIso) ?: return
    val from = Clock.zdt(start)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..4).forEach { offset ->
            val day = from.plusDays(offset.toLong())
            FilterChip(
                selected = offset == 0,
                onClick = { onDraft(draft.copy(startIso = Clock.format(day))) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.format(dayFormat), style = MaterialTheme.typography.labelSmall)
                        Text(day.format(dateFormat), style = MaterialTheme.typography.labelLarge)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        FilterChip(
            selected = false,
            onClick = onPickDate,
            label = { Text("anderer", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The whole day, an hour to a fixed height, in its own scroll area opened at
 * [OPENS_AT_HOUR].
 *
 * It runs 0 to 24 on purpose. An appointment is what the customer agreed to, so
 * a Saturday at 18:30 has to be reachable — a strip that stopped at 18 would
 * quietly forbid what [Appointment] explicitly allows.
 */
@Composable
private fun Timeline(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = Clock.millis(draft.startIso) ?: return
    val dayStart = Clock.todayStart(start)
    val scroll = rememberScrollState()
    val openAt = with(LocalDensity.current) { (HOUR_HEIGHT * OPENS_AT_HOUR).roundToPx() }

    LaunchedEffect(Unit) { scroll.scrollTo(openAt) }

    Box(modifier = modifier.fillMaxWidth().verticalScroll(scroll)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp, end = 16.dp)
                .height(HOUR_HEIGHT * 24),
        ) {
            (0 until 24).forEach { hour ->
                Box(modifier = Modifier.offset(y = HOUR_HEIGHT * hour)) {
                    Text(
                        text = "%02d".format(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = (-32).dp, y = (-6).dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }

            // Overlapping entries are inset, so a second one behind the first is
            // visible rather than hidden underneath it.
            draft.busy.forEachIndexed { index, interval ->
                val overlapsEarlier = draft.busy.take(index).count {
                    interval.startMillis < it.endMillis && it.startMillis < interval.endMillis
                }
                val top = ((interval.startMillis - dayStart) / 60_000L).toInt()
                val length = ((interval.endMillis - interval.startMillis) / 60_000L).toInt()
                BusyBlock(
                    interval = interval,
                    modifier = Modifier
                        .offset(
                            x = 12.dp * overlapsEarlier,
                            y = HOUR_HEIGHT / 60 * top.coerceIn(0, 24 * 60),
                        )
                        .height(HOUR_HEIGHT / 60 * length.coerceIn(15, 24 * 60))
                        .fillMaxWidth(),
                )
            }

            DraftBlock(draft = draft, dayStart = dayStart, onDraft = onDraft)
        }
    }
}

/**
 * The appointment being set: drag the block to move it, drag the handle to
 * change its length.
 *
 * Both gestures accumulate. `detectDragGestures` reports a few pixels per frame,
 * so rounding each frame on its own would round to zero and nothing would ever
 * move. The accumulator adds the frames up and gives back only what it has
 * already turned into a step.
 */
@Composable
private fun DraftBlock(
    draft: AppointmentDraft,
    dayStart: Long,
    onDraft: (AppointmentDraft) -> Unit,
) {
    // The gesture outlives any single recomposition, so it must not close over
    // the draft it started with.
    val current by rememberUpdatedState(draft)
    val emit by rememberUpdatedState(onDraft)
    var carriedMinutes by remember { mutableFloatStateOf(0f) }
    var carriedLength by remember { mutableFloatStateOf(0f) }

    val start = Clock.millis(draft.startIso) ?: return
    val top = ((start - dayStart) / 60_000L).toInt()

    Box(
        modifier = Modifier
            .offset(y = HOUR_HEIGHT / 60 * top)
            .height(HOUR_HEIGHT / 60 * draft.minutes)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(Unit) {
                val perMinute = HOUR_HEIGHT.toPx() / 60f
                detectDragGestures(
                    onDragEnd = { carriedMinutes = 0f },
                    onDragCancel = { carriedMinutes = 0f },
                ) { change, amount ->
                    change.consume()
                    carriedMinutes += amount.y / perMinute
                    val steps = (carriedMinutes / SNAP_MINUTES).roundToInt()
                    if (steps != 0) {
                        carriedMinutes -= steps * SNAP_MINUTES
                        val moved = Clock.millis(current.startIso)!! +
                            steps * SNAP_MINUTES * 60_000L
                        emit(current.copy(startIso = Clock.format(moved)))
                    }
                }
            },
    ) {
        Text(
            text = "${Clock.zdt(start).format(timeFormat)} – " +
                Clock.zdt(start + draft.minutes * 60_000L).format(timeFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(48.dp)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    val perMinute = HOUR_HEIGHT.toPx() / 60f
                    detectDragGestures(
                        onDragEnd = { carriedLength = 0f },
                        onDragCancel = { carriedLength = 0f },
                    ) { change, amount ->
                        change.consume()
                        carriedLength += amount.y / perMinute
                        val steps = (carriedLength / SNAP_MINUTES).roundToInt()
                        if (steps != 0) {
                            carriedLength -= steps * SNAP_MINUTES
                            val length = current.minutes + steps * SNAP_MINUTES
                            emit(current.copy(minutes = length.coerceAtLeast(SNAP_MINUTES)))
                        }
                    }
                },
        )
    }
}

@Composable
private fun BusyBlock(interval: BusyInterval, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // fillMaxHeight, not a fixed one: a half-hour block would otherwise grow
        // a bar an hour tall sticking out of its own card.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Text(
            text = interval.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * What to do when the slot is already taken. Asking beats writing a second copy
 * of an appointment that already exists.
 */
@Composable
private fun ConflictNotice(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onLink: (Long) -> Unit,
    onForce: () -> Unit,
) {
    val clash = draft.conflict.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(
            text = "Um ${Clock.zdt(clash.startMillis).format(timeFormat)} steht " +
                "bereits „${clash.title}“.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            clash.eventId?.let { id ->
                Button(onClick = { onLink(id) }) { Text("Verknüpfen") }
            }
            Button(onClick = onForce) { Text("Trotzdem anlegen") }
            Button(onClick = { onDraft(draft.copy(conflict = emptyList())) }) { Text("Andere Zeit") }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check the three hard parts by hand**

Install and open the sheet. Confirm each one separately, because each fails
independently:

1. **Dragging moves the block** and settles on quarter hours. Drag slowly: a slow
   drag is what a per-frame rounding bug fails.
2. **The handle changes the length** and cannot go below 15 minutes.
3. **The strip reaches 18:30 on a Saturday.** Scroll down, place an appointment
   there, save, and confirm it is stored at 18:30 and not corrected.
4. Two overlapping foreign appointments are both visible, one inset behind the
   other.
5. A 30-minute foreign appointment has a colour bar the height of its own card.
6. The block titles are readable — that is the whole reason this variant was
   chosen over a grid.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt
git commit -m "Terminpicker: Zeitleiste über den ganzen Tag, Dauer, Ort"
```

---

### Task 10: The appointment section in the detail view

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt`

**Interfaces:**
- Consumes: `Business.appointmentAt` and friends, `Appointment.readableRange`, `AppointmentSheet`.
- Produces: an `AppointmentBlock` composable and the `onAppointment` / `onRemoveAppointment` callbacks on `BusinessDetailScreen`. The map goes through the existing `onOpenUrl`.

- [ ] **Step 1: Add the callbacks**

In `BusinessDetail.kt`, add to `BusinessDetailScreen`'s parameters, next to `onFollowUp`:

```kotlin
    onAppointment: () -> Unit,
    onRemoveAppointment: () -> Unit,
```

No map callback: `BusinessDetailScreen` already takes `onOpenUrl` (`BusinessDetail.kt:89`), and `MainActivity.openUrl` (`MainActivity.kt:101`) is a bare `ACTION_VIEW` on `Uri.parse` wrapped in `runCatching`. A `geo:` URI passes through it unchanged. One callback, one failure mode, nothing new to wire.

- [ ] **Step 2: Add the section**

Directly before the existing `item(key = "follow-up")`:

```kotlin
            item(key = "appointment") {
                Section("Termin vor Ort")
                AppointmentBlock(
                    business = business,
                    onSet = onAppointment,
                    onRemove = { removeAppointment = true },
                    onOpenUrl = onOpenUrl,
                )
            }
```

with `var removeAppointment by remember { mutableStateOf(false) }` alongside the other dialog flags, and a confirmation dialog next to the existing ones:

```kotlin
    if (removeAppointment) {
        AlertDialog(
            onDismissRequest = { removeAppointment = false },
            title = { Text("Termin entfernen?") },
            text = {
                Text(
                    "Der Termin wird auch aus dem Kalender gelöscht. " +
                        "Der Status fällt zurück auf „Angerufen“."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveAppointment()
                    removeAppointment = false
                }) { Text("Entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { removeAppointment = false }) { Text("Abbrechen") }
            },
        )
    }
```

- [ ] **Step 3: Write `AppointmentBlock`**

Next to `FollowUpBlock`:

```kotlin
/**
 * The appointment on site. Sits above the follow-up because the two are the
 * answers to one question — when does this go on? — and takes effect straight
 * away, the way the follow-up does.
 */
@Composable
private fun AppointmentBlock(
    business: Business,
    onSet: () -> Unit,
    onRemove: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val set = business.appointmentAt
        if (set == null) {
            // A status of "Termin" without a time is the hole this section
            // exists to close. Say so, and offer the way out — never force it.
            val statusOnly = business.status == Status.APPOINTMENT
            Text(
                text = if (statusOnly) {
                    "Status „Termin“, aber kein Zeitpunkt gesetzt."
                } else {
                    "Kein Termin vereinbart."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusOnly) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSet,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Termin anlegen") }
        } else {
            Text(
                text = Appointment.readableRange(set, business.appointmentEndAt),
                style = MaterialTheme.typography.titleMedium,
            )
            business.appointmentLocation?.takeIf { it.isNotBlank() }?.let { where ->
                Text(
                    text = where,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onOpenUrl(geoUri(where)) }
                        .padding(vertical = 4.dp),
                )
            }
            if (business.calendarEventId != null) {
                Text(
                    text = "Im Kalender abgelegt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSet, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Ändern")
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Entfernen")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Host the sheet and wire the callbacks**

In `MainActivity.kt`, where the detail screen is rendered, add after it:

```kotlin
                state.appointmentDraft?.let { draft ->
                    AppointmentSheet(
                        draft = draft,
                        onDraft = viewModel::updateAppointmentDraft,
                        onSave = { viewModel.saveAppointment() },
                        onLink = { viewModel.saveAppointment(linkExisting = it) },
                        onForce = { viewModel.saveAppointment(force = true) },
                        onPickDate = { appointmentDate = true },
                        onDismiss = viewModel::dismissAppointment,
                    )
                }
```

`onPickDate` opens a date picker built like the follow-up's (`BusinessDetail.kt:321`). Declare its flag next to the other dialog state in the same composable and keep the draft's time of day:

```kotlin
    var appointmentDate by remember { mutableStateOf(false) }

    if (appointmentDate) {
        val draft = state.appointmentDraft
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Clock.millis(draft?.startIso),
        )
        DatePickerDialog(
            onDismissRequest = { appointmentDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (draft != null && picked != null) {
                        // The picker returns midnight UTC. Only the date is
                        // taken from it; the time of day stays as agreed.
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Clock.zdt(Clock.millis(draft.startIso)!!).toLocalTime()
                        viewModel.updateAppointmentDraft(
                            draft.copy(startIso = Clock.format(date.atTime(time).atZone(Clock.zone)))
                        )
                    }
                    appointmentDate = false
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { appointmentDate = false }) { Text("Abbrechen") }
            },
        ) { DatePicker(state = pickerState) }
    }
```

The address goes through the existing `onOpenUrl`. Put the URI builder next to
`AppointmentBlock` in `BusinessDetail.kt`, so both callers use one rule:

```kotlin
/**
 * An address as a map application takes it. `geo:0,0?q=` rather than
 * coordinates: the query lets the map do the geocoding and land on the door
 * number, which the business's own coordinates do not always do.
 */
internal fun geoUri(address: String): String = "geo:0,0?q=" + Uri.encode(address)
```

- [ ] **Step 5: Build and check by hand**

Run: `./gradlew assembleDebug`

Set the status to `Termin` and save without an appointment: the notice appears. Set an appointment: the section shows it, tapping the address opens a map application, `Entfernen` asks first and puts the status back.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt \
        app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Detailansicht: Abschnitt Termin vor Ort"
```

---

### Task 11: Reading back from the calendar

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`

**Interfaces:**
- Consumes: `CalendarStore.read`, `Repository.setAppointment`, `Repository.setStatus`.
- Produces:
  - `sealed interface ReadBack` with `ReadBack.Unchanged`, `ReadBack.Gone` and
    `data class ReadBack.Updated(val startIso: String, val endIso: String, val location: String?)`
  - `Appointment.readBack(currentAt: String?, currentEnd: String?, currentLocation: String?, eventStartMillis: Long?, eventEndMillis: Long?, eventLocation: String?): ReadBack`
  - `CallsheetViewModel.syncAppointment(placeId: String)`, called from `openBusiness`

The decision — unchanged, updated or gone — is a pure function so it can be
tested without a provider, exactly the split `ContactMergeTest` already relies
on. The view model only carries out what it returns. Note it takes milliseconds
rather than `EventFields`: `calendar/` already depends on `calling/`, and
pointing that arrow back the other way would tangle the two packages for no gain.

- [ ] **Step 1: Write the failing tests for the decision**

Append to `AppointmentTest.kt`:

```kotlin
    // --- readBack -----------------------------------------------------------

    private val at = "2026-09-10T14:00:00+02:00"
    private val until = "2026-09-10T15:00:00+02:00"

    @Test
    fun `a difference of seconds still counts as unchanged`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = null,
            eventStartMillis = millis(2026, 9, 10, 14) + 30_000L,
            eventEndMillis = millis(2026, 9, 10, 15) + 30_000L,
            eventLocation = null,
        )

        assertEquals(ReadBack.Unchanged, outcome)
    }

    @Test
    fun `an untouched appointment yields Unchanged`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Zehentstraße 39",
        )

        assertEquals(ReadBack.Unchanged, outcome)
    }

    @Test
    fun `a moved appointment yields Updated`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 16),
            eventEndMillis = millis(2026, 9, 10, 17),
            eventLocation = "Zehentstraße 39",
        ) as ReadBack.Updated

        assertEquals(millis(2026, 9, 10, 16), Clock.millis(outcome.startIso))
        assertEquals("Zehentstraße 39", outcome.location)
    }

    @Test
    fun `a relocated appointment yields Updated`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Im Büro",
        ) as ReadBack.Updated

        assertEquals("Im Büro", outcome.location)
    }

    @Test
    fun `a deleted appointment yields Gone`() {
        val outcome = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = null,
            eventStartMillis = null, eventEndMillis = null, eventLocation = null,
        )

        assertEquals(ReadBack.Gone, outcome)
    }
```

Add `import io.github.amadeusb.callsheet.calling.ReadBack` to the test file.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile — `readBack` does not exist.

- [ ] **Step 3: Write the decision**

In `calling/Appointment.kt`:

```kotlin
/** What the calendar has to say about an appointment the app already knows. */
sealed interface ReadBack {
    /** The calendar agrees with the app. */
    data object Unchanged : ReadBack

    /** The calendar moved or relocated it; these values win. */
    data class Updated(val startIso: String, val endIso: String, val location: String?) : ReadBack

    /** The event is gone. The only case that needs the user told. */
    data object Gone : ReadBack
}
```

and inside `object Appointment`:

```kotlin
    /**
     * Compares what the app holds against what the calendar returned. The
     * calendar wins on time and location — that is where an appointment gets
     * moved, on a laptop or in the car.
     *
     * A null [eventStartMillis] means the event is gone.
     */
    fun readBack(
        currentAt: String?,
        currentEnd: String?,
        currentLocation: String?,
        eventStartMillis: Long?,
        eventEndMillis: Long?,
        eventLocation: String?,
    ): ReadBack {
        if (eventStartMillis == null || eventEndMillis == null) return ReadBack.Gone
        // Compared in milliseconds, not as text. A provider that rounds DTSTART
        // to the minute, or hands back a different second resolution, would
        // otherwise look "moved" on every single open and rewrite updated_at
        // for ever.
        val sameTime = near(Clock.millis(currentAt), eventStartMillis) &&
            near(Clock.millis(currentEnd), eventEndMillis)
        val samePlace = eventLocation?.trim().orEmpty() == currentLocation?.trim().orEmpty()
        return if (sameTime && samePlace) {
            ReadBack.Unchanged
        } else {
            ReadBack.Updated(Clock.format(eventStartMillis), Clock.format(eventEndMillis), eventLocation)
        }
    }

    /** Within a minute counts as the same moment. */
    private fun near(a: Long?, b: Long): Boolean = a != null && kotlin.math.abs(a - b) < 60_000L
    }
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, sixteen tests.

- [ ] **Step 5: Write the read-back in the view model**

```kotlin
    /**
     * Brings a linked appointment back in line with the calendar.
     *
     * The calendar wins: that is where an appointment gets moved, on a laptop or
     * in the car. The same rule the phone book already follows for names and
     * numbers.
     *
     * A deleted event is the only case that speaks up, because it is the only
     * one that needs a decision.
     */
    private fun syncAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            val eventId = business.calendarEventId ?: return@launch
            if (!CalendarStore.canRead(getApplication())) return@launch

            val event = CalendarStore.read(getApplication(), eventId)
            val decision = Appointment.readBack(
                currentAt = business.appointmentAt,
                currentEnd = business.appointmentEndAt,
                currentLocation = business.appointmentLocation,
                eventStartMillis = event?.startMillis,
                eventEndMillis = event?.endMillis,
                eventLocation = event?.location,
            )

            when (decision) {
                is ReadBack.Unchanged -> Unit

                is ReadBack.Updated -> {
                    repo.setAppointment(
                        placeId, decision.startIso, decision.endIso, decision.location, eventId,
                    )
                    loadDetail(placeId)
                }

                is ReadBack.Gone -> {
                    repo.setAppointment(placeId, null, null, null, null)
                    // Only the status this appointment set gets taken back. A
                    // business that has since been declined or blocked keeps
                    // that — deleting an entry in the calendar is not
                    // permission to undo a decision made on the phone.
                    val reset = business.status == Status.APPOINTMENT
                    if (reset) repo.setStatus(placeId, Status.CALLED)
                    _state.update {
                        it.copy(
                            hint = if (reset) {
                                "Der Termin wurde im Kalender gelöscht. Status zurück auf „Angerufen“."
                            } else {
                                "Der Termin wurde im Kalender gelöscht. Der Status bleibt, wie er ist."
                            }
                        )
                    }
                    loadDetail(placeId)
                }
            }
        }
    }
```

`loadDetail(placeId)` (`CallsheetViewModel.kt:440`) is the existing refresh path — reuse it rather than adding a second one.

- [ ] **Step 6: Call it when a business opens**

In `openBusiness`, after the detail has loaded, add `syncAppointment(placeId)`. Guard against the loop: `syncAppointment` only calls `reloadDetail`, never `openBusiness`.

- [ ] **Step 7: Build and check by hand**

Run: `./gradlew assembleDebug`

Set an appointment, move it by an hour in the calendar application, reopen the business: the section shows the new time without saying anything. Delete it in the calendar, reopen: the appointment is gone, the status reads `Angerufen`, and the hint explains why.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Rückabgleich: der Kalender gewinnt"
```

---

### Task 12: The address becomes usable

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt`

**Interfaces:**
- Consumes: `Appointment.address` and `geoUri` from Task 10, and the existing `onOpenUrl`.
- Produces: nothing new.

- [ ] **Step 1: Make the address tappable**

In `BusinessDetail.kt`, in `MasterData`, replace the address rows with:

```kotlin
        Appointment.address(business.street, business.postalCode, business.city)?.let { address ->
            DataRow("Anschrift", address) { onOpenUrl(geoUri(address)) }
        }
```

`MasterData` already receives `onOpenUrl` (`BusinessDetail.kt:188`), so nothing new is threaded through.

This also drops the two-line address in favour of the one-liner `Appointment.address` produces, which is what goes into the calendar — one formatting rule instead of two that can drift.

- [ ] **Step 2: Leave the work list alone**

The spec asked for the street in the work list row. Do not add it, and change the
spec instead.

The second line is not the city — it is `industry · city` (`Components.kt:122`),
one line, `maxLines = 1` with an ellipsis, and there is already a third line for
the contact. A fourth datum turns "Garten- und Landschaftsbau · Ingolstadt" into
"Garten- und Landschaftsb…", which trades the industry — the thing the list is
sorted and filtered by — for half a street name.

The street's job here is routing, and routing happens where the address is
tappable and where the calendar entry carries it. Both are in this plan already.

Record the reversal in the spec's address section rather than leaving the plan
silently disagreeing with it.

- [ ] **Step 3: Build and check by hand**

Run: `./gradlew assembleDebug`

Tapping the address in the detail view opens a map application. With none installed, nothing happens and the app does not crash — `MainActivity.openUrl` already wraps `startActivity` in `runCatching`. The work list is unchanged.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt \
        docs/superpowers/specs/2026-09-08-appointments-and-calendar-design.md
git commit -m "Anschrift öffnet die Karten-App"
```

---

### Task 13: Appointments in "Today"

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/Today.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`

**Interfaces:**
- Consumes: `Repository.appointmentsDue`.
- Produces: `State.appointmentsToday: List<Business>`; `TodayScreen(appointments = ...)`.

- [ ] **Step 1: Load them**

In `showToday()`, next to the existing `due` call:

```kotlin
            val appointments = repo.appointmentsDue(
                fromMillis = Clock.todayStart(now),
                toMillis = Clock.todayStart(now) + 24L * 60 * 60 * 1000,
            )
```

and carry it into the state as `appointmentsToday = appointments`. Add `val appointmentsToday: List<Business> = emptyList()` to `State`.

- [ ] **Step 2: Teach `BusinessRow` to show an appointment**

`BusinessRow` (`Components.kt:99`) already takes `showFollowUp: Boolean = false`.
Add its sibling next to it:

```kotlin
    showAppointment: Boolean = false,
```

and, where the row renders the follow-up line, render the appointment instead
when it is set:

```kotlin
            if (showAppointment && business.appointmentAt != null) {
                Text(
                    text = Appointment.readableRange(business.appointmentAt, business.appointmentEndAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
```

Both flags default to false, so every existing call site keeps compiling.

- [ ] **Step 3: Show them**

In `Today.kt`, add the parameter `appointments: List<Business>` and a group **above** the overdue one:

```kotlin
            if (appointments.isNotEmpty()) {
                item(key = "header-appointments") {
                    GroupHeader(title = "Termine heute (${appointments.size})")
                }
                items(appointments, key = { "t-" + it.placeId }) { business ->
                    BusinessRow(
                        business = business,
                        onDial = { onDial(business) },
                        onOpen = { onOpen(business) },
                        showAppointment = true,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
```

Extend the empty state at the top so it only appears when all three lists are empty:

```kotlin
        if (appointments.isEmpty() && overdue.isEmpty() && dueToday.isEmpty()) {
```

- [ ] **Step 4: Build and check by hand**

Run: `./gradlew assembleDebug`

Set an appointment for today, open "Heute": it stands above the follow-ups, under its own heading.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/Today.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Heute: Termine über den Wiedervorlagen"
```

---

### Task 14: Documentation

**Files:**
- Modify: `docs/data-model.md`, `docs/usage.md`, `README.md`, `CHANGELOG.md`

**Interfaces:** none.

- [ ] **Step 1: `docs/data-model.md`**

In the `businesses` working-fields block, add the four columns with their comments, and add `latitude` / `longitude` to the master data block. Extend the sentence about what an import never overwrites so it names the appointment columns. Add a **Calendar** section after **Phone book**, written the same way:

```markdown
## Calendar

So that an appointment on site is where the rest of the day is, the app writes
it into the device's calendar — through the Android calendar API, into the
calendar chosen in the settings. When that is a CalDAV calendar managed by
DAVx5, the appointment reaches the server the same way; the app itself speaks
**no** CalDAV and needs no credentials.

- The app recognises its own appointment by the event id it stores on the
  business. Other people's appointments are never touched.
- Writing happens when an appointment is saved or changed.
- The other direction: opening a record reads the event back. Moved or
  relocated, the calendar wins. Deleted, the appointment is cleared and the
  status falls back to `called`.
- Busy times for the picker are read from every visible calendar, and only read.
```

- [ ] **Step 2: `docs/usage.md`**

Add a section on agreeing an appointment: the section in the detail view, the sheet, what the duration does, what happens when something already occupies the slot, and that moving it in the calendar is picked up by itself.

- [ ] **Step 3: `README.md`**

In "What it does", add a line after the follow-up one:

```markdown
- **Appointment on site** with a time, a length and an address, mirrored into
  the device's calendar
```

And in "After installing", note that the app asks for calendar access when the
setting is turned on, and works without it.

- [ ] **Step 4: `CHANGELOG.md`**

Add an entry in the file's existing style and language, describing the appointment, the calendar mirroring, the usable address, and the database migration.

- [ ] **Step 5: Commit**

```bash
git add docs README.md CHANGELOG.md
git commit -m "Doku: Termine und Kalender"
```

---

### Task 15: The whole thing, once, by hand

**Files:** none.

- [ ] **Step 1: Run every test**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, with `MigrationTest`, `AppointmentTest`, `PreferencesTest`, `RepositoryTest` and `ImporterTest` all reporting. `calendar/` has no tests by design — Step 4 below is what covers it.

- [ ] **Step 2: Build the release variant**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL.

This is a compilation check, not a release. Do **not** touch `version.properties`
— `tools/release.sh:37` raises `versionCode` itself, and a hand-edited value
would collide with it.

- [ ] **Step 3: Upgrade a real database**

Install the *previous* release over a device that has call history in it, then install this build over the top without clearing data. Open a business that has a status and a note: both are still there. This is the one failure that cannot be undone, so do not skip it.

- [ ] **Step 4: Walk the flow**

1. Turn the calendar setting on, pick a calendar, grant the permission.
2. Call a business, set the status to `Termin`, save — the notice appears.
3. Set an appointment at a time that is already taken — the conflict question appears; link, and the business points at the existing event.
4. Set a second appointment in a free slot — it appears in the calendar application with the address in its location.
5. Move it in the calendar, reopen the business — the new time shows.
6. Delete it in the calendar, reopen — the appointment is gone, the status reads `Angerufen`, the hint says why.
7. Revoke the calendar permission in the system settings, set an appointment — the timeline explains itself and saving still works.
8. Open "Heute" — the appointment stands above the follow-ups.
9. Tap the address — a map application opens.

- [ ] **Step 5: Commit anything the walkthrough turned up**

Nothing to commit if it all worked. If it did not, fix it in the task it belongs to rather than here.
