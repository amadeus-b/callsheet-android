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
| `app/src/test/.../CalendarStoreTest.kt` | Robolectric tests for `CalendarStore` and `BusyTimes` against the Robolectric content provider. |

**Modified:**

| File | Change |
|---|---|
| `data/Database.kt` | Six columns, `VERSION` 2, a real `onUpgrade`. |
| `data/Models.kt` | Six fields on `Business`, two on `ImportedBusiness`. |
| `data/Repository.kt` | Read the six columns, `setAppointment`, `appointmentsDue`, coordinates on import. |
| `data/Importer.kt` | Read `location.lat` / `location.lng`. |
| `contacts/Preferences.kt` | `calendarEnabled`, `calendarId`, `appointmentMinutes`. |
| `ui/BusinessDetail.kt` | The `Termin vor Ort` section, the tappable address, the sheet's host. |
| `ui/Components.kt` | Street in the work list row. |
| `ui/Settings.kt` | Calendar picker beside the address book picker. |
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
    fun `die Migration erhält die Arbeitsdaten`() {
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
    fun `die Migration legt die sechs Spalten leer an`() {
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
    fun `eine frische Datenbank hat dieselben Spalten`() {
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
  - `suspend fun Repository.appointmentsDue(toMillis: Long): List<Business>`

- [ ] **Step 1: Write the failing tests**

Append to `RepositoryTest.kt`, inside the class:

```kotlin
    // -------------------------------------------------------------- Appointment

    @Test
    fun `ein Termin wird gespeichert und wieder gelesen`() = runTest {
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
    fun `ein Termin laesst sich vollstaendig entfernen`() = runTest {
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
    fun `ein zweiter Import laesst den Termin unberuehrt`() = runTest {
        import("""[{"placeId":"t-3","title":"Gartenbau Merten","phone":"+49 841 111"}]""")
        repo.setAppointment("t-3", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00", "Zehentstraße 39", 99L)

        import("""[{"placeId":"t-3","title":"Gartenbau Merten GmbH","phone":"+49 841 222"}]""")

        val business = repo.business("t-3")!!
        assertEquals("Gartenbau Merten GmbH", business.name)
        assertEquals("2026-09-10T14:00:00+02:00", business.appointmentAt)
        assertEquals(99L, business.calendarEventId)
    }

    @Test
    fun `appointmentsDue liefert faellige Termine chronologisch`() = runTest {
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

        val bis = Clock.millis("2026-09-11T00:00:00+02:00")!!
        val faellig = repo.appointmentsDue(bis)

        assertEquals(listOf("t-5", "t-4"), faellig.map { it.placeId })
    }

    @Test
    fun `ein gesperrter Betrieb taucht in appointmentsDue nicht auf`() = runTest {
        import("""[{"placeId":"t-7","title":"Gesperrt","phone":"+49 841 111"}]""")
        repo.setAppointment("t-7", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00", null, null)
        repo.setStatus("t-7", Status.DO_NOT_CALL)

        val bis = Clock.millis("2026-09-11T00:00:00+02:00")!!

        assertTrue(repo.appointmentsDue(bis).isEmpty())
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
    /** Every appointment starting by [toMillis], earliest first. */
    suspend fun appointmentsDue(toMillis: Long = System.currentTimeMillis()): List<Business> =
        withContext(Dispatchers.IO) {
            val sql = "SELECT b.*, $NUMBERS_SUBQUERY FROM businesses b WHERE status <> ? " +
                "AND appointment_at IS NOT NULL AND appointment_at <> ''"
            helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
                allBusinesses(c)
                    .mapNotNull { b -> Clock.millis(b.appointmentAt)?.let { it to b } }
                    .filter { it.first <= toMillis }
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

Append to `ImporterTest.kt`, inside the class (match the file's existing parse helper — if it parses through `Importer.parse`, use that; the assertion is on the resulting `ImportedBusiness`):

```kotlin
    @Test
    fun `Koordinaten werden aus location gelesen`() {
        val json = """
            [{"placeId":"k-1","title":"Gartenbau Merten",
              "location":{"lat":48.8059466,"lng":11.4058554}}]
        """.trimIndent()

        val betrieb = Importer.parse(json.byteInputStream()).single()

        assertEquals(48.8059466, betrieb.latitude!!, 0.0000001)
        assertEquals(11.4058554, betrieb.longitude!!, 0.0000001)
    }

    @Test
    fun `ein fehlendes location-Feld ergibt keine Koordinaten`() {
        val json = """[{"placeId":"k-2","title":"Ohne Ort"}]"""

        val betrieb = Importer.parse(json.byteInputStream()).single()

        assertNull(betrieb.latitude)
        assertNull(betrieb.longitude)
    }
```

If `Importer` exposes no `parse` returning `List<ImportedBusiness>`, go through the repository instead and assert on `repo.business(...)`:

```kotlin
    @Test
    fun `Koordinaten landen in der Datenbank`() = runTest {
        import("""[{"placeId":"k-1","title":"Merten","location":{"lat":48.8059466,"lng":11.4058554}}]""")

        val betrieb = repo.business("k-1")!!
        assertEquals(48.8059466, betrieb.latitude!!, 0.0000001)
        assertEquals(11.4058554, betrieb.longitude!!, 0.0000001)
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
            latitude = location?.decimalOrNull("lat"),
            longitude = location?.decimalOrNull("lng"),
```

Add the helper next to the other `JSONObject` helpers:

```kotlin
    /** A decimal or null; `JSONObject.NULL` and absent keys both count as absent. */
    private fun JSONObject.decimalOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }
```

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
  - `data class BusyInterval(val startMillis: Long, val endMillis: Long, val title: String)`
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

    private fun busy(fromHour: Int, toHour: Int, title: String) = BusyInterval(
        startMillis = millis(2026, 9, 10, fromHour),
        endMillis = millis(2026, 9, 10, toHour),
        title = title,
    )

    // --- endOf / minutesBetween --------------------------------------------

    @Test
    fun `endOf addiert die Dauer`() {
        val ende = Appointment.endOf("2026-09-10T14:00:00+02:00", 90)

        assertEquals(millis(2026, 9, 10, 15, 30), Clock.millis(ende))
    }

    @Test
    fun `endOf korrigiert nicht auf Werktage`() {
        // Samstag, 18:30 — als Termin ausdrücklich erlaubt. Anders als bei der
        // Wiedervorlage entscheidet hier der Kunde, nicht die App.
        val ende = Appointment.endOf("2026-09-12T18:30:00+02:00", 60)

        assertEquals(millis(2026, 9, 12, 19, 30), Clock.millis(ende))
    }

    @Test
    fun `minutesBetween liest die Dauer zurueck`() {
        val dauer = Appointment.minutesBetween(
            "2026-09-10T14:00:00+02:00",
            "2026-09-10T15:30:00+02:00",
        )

        assertEquals(90, dauer)
    }

    @Test
    fun `minutesBetween faellt ohne Ende auf die Voreinstellung zurueck`() {
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween("2026-09-10T14:00:00+02:00", null))
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.minutesBetween(null, null))
    }

    // --- overlapping --------------------------------------------------------

    @Test
    fun `eine Ueberschneidung wird gefunden`() {
        val belegt = listOf(busy(9, 10, "Baustelle Nord"), busy(16, 17, "Steuerbüro"))

        val treffer = Appointment.overlapping(
            millis(2026, 9, 10, 9, 30),
            millis(2026, 9, 10, 10, 30),
            belegt,
        )

        assertEquals(listOf("Baustelle Nord"), treffer.map { it.title })
    }

    @Test
    fun `direkt aneinander liegende Termine ueberschneiden sich nicht`() {
        val belegt = listOf(busy(9, 10, "Baustelle Nord"))

        val treffer = Appointment.overlapping(
            millis(2026, 9, 10, 10),
            millis(2026, 9, 10, 11),
            belegt,
        )

        assertTrue(treffer.isEmpty())
    }

    @Test
    fun `ein umschlossener Termin zaehlt als Ueberschneidung`() {
        val belegt = listOf(busy(9, 10, "Baustelle Nord"))

        val treffer = Appointment.overlapping(
            millis(2026, 9, 10, 8),
            millis(2026, 9, 10, 12),
            belegt,
        )

        assertEquals(1, treffer.size)
    }

    // --- address ------------------------------------------------------------

    @Test
    fun `die Anschrift wird einzeilig zusammengesetzt`() {
        assertEquals(
            "Zehentstraße 39, 85055 Ingolstadt",
            Appointment.address("Zehentstraße 39", "85055", "Ingolstadt"),
        )
    }

    @Test
    fun `fehlende Teile der Anschrift fallen weg`() {
        assertEquals("Ingolstadt", Appointment.address(null, null, "Ingolstadt"))
        assertEquals("Zehentstraße 39", Appointment.address("Zehentstraße 39", null, null))
        assertNull(Appointment.address(null, null, null))
        assertNull(Appointment.address(" ", "", null))
    }

    // --- readableRange ------------------------------------------------------

    @Test
    fun `der Zeitraum wird deutsch dargestellt`() {
        assertEquals(
            "Do, 10.09. · 14:00 – 15:00",
            Appointment.readableRange("2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00"),
        )
    }

    @Test
    fun `ohne Termin steht ein Gedankenstrich`() {
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

If `der Zeitraum wird deutsch dargestellt` fails on the weekday abbreviation, the JVM locale data is producing something other than `Do`. Do not weaken the assertion — fix the pattern to match the actual German short form and note what it produced.

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

    private val prefs = Preferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `frische Einstellungen haben keinen Kalender und 60 Minuten`() {
        assertFalse(prefs.calendarEnabled)
        assertNull(prefs.calendarId)
        assertEquals(60, prefs.appointmentMinutes)
    }

    @Test
    fun `die gewaehlte Dauer wird gemerkt`() {
        prefs.appointmentMinutes = 90

        assertEquals(90, prefs.appointmentMinutes)
    }

    @Test
    fun `der Kalender laesst sich setzen und wieder loeschen`() {
        prefs.calendarId = 7L
        assertEquals(7L, prefs.calendarId)

        prefs.calendarId = null
        assertNull(prefs.calendarId)
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
- Test: `app/src/test/java/io/github/amadeusb/callsheet/CalendarStoreTest.kt` (create)

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

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/io/github/amadeusb/callsheet/CalendarStoreTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import android.Manifest
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.calendar.CalendarStore
import io.github.amadeusb.callsheet.calendar.BusyTimes
import io.github.amadeusb.callsheet.calendar.EventFields
import io.github.amadeusb.callsheet.data.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun millis(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, 10, hour, minute, 0, 0, Clock.zone).toInstant().toEpochMilli()

    private val dayStart: Long
        get() = ZonedDateTime.of(2026, 9, 10, 0, 0, 0, 0, Clock.zone).toInstant().toEpochMilli()

    /** A calendar to write into, inserted straight through the provider. */
    private fun createCalendar(displayName: String = "Arbeit"): Long {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "test@example.org")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "test@example.org")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, Clock.zone.id)
        }
        return context.contentResolver.insert(uri, values)!!.lastPathSegment!!.toLong()
    }

    @Before
    fun aufbau() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    @Test
    fun `ohne Berechtigung wird nichts gelesen`() = runTest {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(Manifest.permission.READ_CALENDAR)

        assertTrue(CalendarStore.calendars(context).isEmpty())
        assertTrue(BusyTimes.forDay(context, dayStart).isEmpty())
    }

    @Test
    fun `die Kalender des Geraets werden gefunden`() = runTest {
        createCalendar("Arbeit")

        val kalender = CalendarStore.calendars(context)

        assertEquals(listOf("Arbeit"), kalender.map { it.name })
    }

    @Test
    fun `ein Termin wird angelegt und wieder gelesen`() = runTest {
        val kalenderId = createCalendar()

        val id = CalendarStore.insert(
            context, kalenderId,
            EventFields(
                title = "Ortstermin Gartenbau Merten",
                startMillis = millis(14),
                endMillis = millis(15),
                location = "Zehentstraße 39, 85055 Ingolstadt",
                description = "Aus Callsheet",
            ),
        )
        assertNotNull(id)

        val gelesen = CalendarStore.read(context, id!!)!!
        assertEquals("Ortstermin Gartenbau Merten", gelesen.title)
        assertEquals(millis(14), gelesen.startMillis)
        assertEquals(millis(15), gelesen.endMillis)
        assertEquals("Zehentstraße 39, 85055 Ingolstadt", gelesen.location)
    }

    @Test
    fun `ein verschobener Termin wird verschoben gelesen`() = runTest {
        val kalenderId = createCalendar()
        val id = CalendarStore.insert(
            context, kalenderId,
            EventFields("Ortstermin", millis(14), millis(15), null, null),
        )!!

        CalendarStore.update(
            context, id,
            EventFields("Ortstermin", millis(16), millis(17), "Woanders", null),
        )

        val gelesen = CalendarStore.read(context, id)!!
        assertEquals(millis(16), gelesen.startMillis)
        assertEquals("Woanders", gelesen.location)
    }

    @Test
    fun `ein geloeschter Termin liest sich als null`() = runTest {
        val kalenderId = createCalendar()
        val id = CalendarStore.insert(
            context, kalenderId,
            EventFields("Ortstermin", millis(14), millis(15), null, null),
        )!!

        CalendarStore.delete(context, id)

        assertNull(CalendarStore.read(context, id))
    }

    @Test
    fun `eine unbekannte Event-Id liest sich als null`() = runTest {
        assertNull(CalendarStore.read(context, 999_999L))
    }

    @Test
    fun `belegte Zeiten kommen aus allen sichtbaren Kalendern`() = runTest {
        val arbeit = createCalendar("Arbeit")
        val privat = createCalendar("Privat")
        CalendarStore.insert(context, arbeit, EventFields("Baustelle Nord", millis(9), millis(10), null, null))
        CalendarStore.insert(context, privat, EventFields("Zahnarzt", millis(12), millis(13), null, null))

        val belegt = BusyTimes.forDay(context, dayStart)

        assertEquals(listOf("Baustelle Nord", "Zahnarzt"), belegt.map { it.title })
        assertEquals(millis(9), belegt.first().startMillis)
    }

    @Test
    fun `ein Termin am Vortag zaehlt nicht zum Tag`() = runTest {
        val kalenderId = createCalendar()
        val gestern = ZonedDateTime.of(2026, 9, 9, 14, 0, 0, 0, Clock.zone).toInstant().toEpochMilli()
        CalendarStore.insert(context, kalenderId, EventFields("Gestern", gestern, gestern + 3_600_000L, null, null))

        assertTrue(BusyTimes.forDay(context, dayStart).isEmpty())
    }
}
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.CalendarStoreTest"`
Expected: FAIL to compile — `CalendarStore` does not exist.

- [ ] **Step 4: Write `CalendarStore`**

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

- [ ] **Step 5: Write `BusyTimes`**

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
                    ),
                    null, null,
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
                            )
                        )
                    }
                }
            }
            found
        }
}
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.CalendarStoreTest"`
Expected: PASS, eight tests.

If Robolectric's calendar provider turns out not to support `Instances` (it is a view over `Events`, and shadow support varies by version), do **not** delete the test. Report it, and fall back to querying `CalendarContract.Events` with a `DTSTART`/`DTEND` range in `BusyTimes` — recurring events are then out of scope, which is worth stating in the spec rather than silently accepting.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calendar/ \
        app/src/main/AndroidManifest.xml \
        app/src/test/java/io/github/amadeusb/callsheet/CalendarStoreTest.kt
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
    val calendarHint: String? = null,
```

Imports: `io.github.amadeusb.callsheet.calendar.CalendarAccount`, `io.github.amadeusb.callsheet.calendar.CalendarStore`.

- [ ] **Step 2: Write the actions**

In `CallsheetViewModel`, next to the phone book actions:

```kotlin
    /** Loads the device's calendars for the picker. */
    fun loadCalendars() {
        viewModelScope.launch {
            val found = CalendarStore.calendars(getApplication())
            val chosen = prefs.calendarId?.let { id -> found.firstOrNull { it.id == id } }
            _state.update { it.copy(calendars = found, calendar = chosen) }
        }
    }

    fun setCalendarEnabled(enabled: Boolean) {
        prefs.calendarEnabled = enabled
        _state.update { it.copy(calendarEnabled = enabled) }
        if (enabled) loadCalendars()
    }

    fun pickCalendar(calendar: CalendarAccount) {
        prefs.calendarId = calendar.id
        _state.update { it.copy(calendar = calendar) }
    }
```

Find where the existing code reads `prefs.phoneBookEnabled` into the initial state and add the calendar equivalents alongside:

```kotlin
            calendarEnabled = prefs.calendarEnabled,
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
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt`

**Interfaces:**
- Consumes: `Repository.setAppointment`, `CalendarStore`, `BusyTimes`, `Appointment`, `Preferences.appointmentMinutes`.
- Produces:
  - `data class AppointmentDraft(val placeId: String, val startIso: String, val minutes: Int, val location: String, val busy: List<BusyInterval>, val conflict: List<BusyInterval> = emptyList())`
  - `State.appointmentDraft: AppointmentDraft?`
  - `CallsheetViewModel.openAppointment(placeId: String)`, `.updateAppointmentDraft(AppointmentDraft)`, `.pickAppointmentDay(dayStartMillis: Long)`, `.saveAppointment(linkExisting: Long? = null, force: Boolean = false)`, `.dismissAppointment()`, `.removeAppointment(placeId: String)`

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
    /** Everything already taken on that day, for the timeline. */
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

- [ ] **Step 2: Write the actions**

```kotlin
    /** Opens the sheet, prefilled from the business and the last duration used. */
    fun openAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            val start = business.appointmentAt
                ?: FollowUp.inTwoDays()
            val minutes = if (business.appointmentAt != null) {
                Appointment.minutesBetween(business.appointmentAt, business.appointmentEndAt)
            } else {
                prefs.appointmentMinutes
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
            loadBusy(Clock.millis(start)!!)
        }
    }

    /** Reads the busy times for the day containing [millis]. */
    private fun loadBusy(millis: Long) {
        viewModelScope.launch {
            val dayStart = Clock.todayStart(millis)
            val busy = BusyTimes.forDay(getApplication(), dayStart)
            _state.update { state ->
                val draft = state.appointmentDraft ?: return@update state
                state.copy(appointmentDraft = draft.copy(busy = busy, conflict = emptyList()))
            }
        }
    }

    fun updateAppointmentDraft(draft: AppointmentDraft) {
        val previous = _state.value.appointmentDraft
        _state.update { it.copy(appointmentDraft = draft.copy(conflict = emptyList())) }
        val dayChanged = previous == null ||
            Clock.todayStart(Clock.millis(previous.startIso) ?: 0L) !=
            Clock.todayStart(Clock.millis(draft.startIso) ?: 0L)
        if (dayChanged) loadBusy(Clock.millis(draft.startIso) ?: return)
    }

    fun dismissAppointment() {
        _state.update { it.copy(appointmentDraft = null) }
    }

    /**
     * Writes the appointment: the four columns, the status, and the calendar
     * event.
     *
     * When something already occupies the window and neither [force] nor
     * [linkExisting] says what to do about it, nothing is written — the draft
     * comes back carrying the conflict, and the sheet asks.
     */
    fun saveAppointment(linkExisting: Long? = null, force: Boolean = false) {
        val draft = _state.value.appointmentDraft ?: return
        viewModelScope.launch {
            val startMillis = Clock.millis(draft.startIso) ?: return@launch
            val endIso = Appointment.endOf(draft.startIso, draft.minutes)
            val endMillis = Clock.millis(endIso)!!

            if (linkExisting == null && !force) {
                val clash = Appointment.overlapping(startMillis, endMillis, draft.busy)
                if (clash.isNotEmpty()) {
                    _state.update { it.copy(appointmentDraft = draft.copy(conflict = clash)) }
                    return@launch
                }
            }

            val business = repo.business(draft.placeId)
            val location = draft.location.trim().ifEmpty { null }
            val fields = EventFields(
                title = "Ortstermin ${business?.name ?: ""}".trim(),
                startMillis = startMillis,
                endMillis = endMillis,
                location = location,
                description = business?.phone,
            )

            val eventId = when {
                linkExisting != null -> linkExisting.also {
                    CalendarStore.update(getApplication(), it, fields)
                }
                !prefs.calendarEnabled -> null
                else -> {
                    val existing = business?.calendarEventId
                    if (existing != null && CalendarStore.read(getApplication(), existing) != null) {
                        CalendarStore.update(getApplication(), existing, fields)
                        existing
                    } else {
                        prefs.calendarId?.let { CalendarStore.insert(getApplication(), it, fields) }
                    }
                }
            }

            prefs.appointmentMinutes = draft.minutes
            repo.setAppointment(draft.placeId, draft.startIso, endIso, location, eventId)
            repo.setStatus(draft.placeId, Status.APPOINTMENT)
            _state.update {
                it.copy(
                    appointmentDraft = null,
                    hint = if (prefs.calendarEnabled && eventId == null) {
                        "Termin gespeichert. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    } else null,
                )
            }
            loadDetail(draft.placeId)
        }
    }

    /** Removes the appointment and its calendar event. */
    fun removeAppointment(placeId: String) {
        viewModelScope.launch {
            repo.business(placeId)?.calendarEventId?.let {
                CalendarStore.delete(getApplication(), it)
            }
            repo.setAppointment(placeId, null, null, null, null)
            repo.setStatus(placeId, Status.CALLED)
            loadDetail(placeId)
        }
    }
```

Imports to add: `io.github.amadeusb.callsheet.calendar.BusyTimes`, `.CalendarStore`, `.EventFields`, `io.github.amadeusb.callsheet.calling.Appointment`, `.BusyInterval`.

- [ ] **Step 3: Prove that cancelling changes nothing**

Append to `RepositoryTest.kt` — the guarantee is at the repository level, since
`dismissAppointment` writes nothing at all:

```kotlin
    @Test
    fun `ein Betrieb ohne Termin bleibt ohne Termin`() = runTest {
        import("""[{"placeId":"t-8","title":"Unberührt","phone":"+49 841 111"}]""")

        val business = repo.business("t-8")!!
        assertNull(business.appointmentAt)
        assertNull(business.calendarEventId)
        assertEquals(Status.NEW, business.status)
    }
```

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: PASS.

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Termin speichern: Spalten, Status und Kalendereintrag"
```

---

### Task 9: The appointment sheet

**Files:**
- Create: `app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt`

**Interfaces:**
- Consumes: `AppointmentDraft`, `Appointment`, `BusyInterval`, `Clock`.
- Produces: `@Composable fun AppointmentSheet(draft: AppointmentDraft, onDraft: (AppointmentDraft) -> Unit, onSave: () -> Unit, onLink: (Long) -> Unit, onForce: () -> Unit, onPickDate: () -> Unit, onDismiss: () -> Unit)`

Note `onLink` takes a `Long`, but `BusyInterval` carries no event id. Extend `BusyInterval` in `calling/Appointment.kt` with `val eventId: Long? = null` and fill it in `BusyTimes` from `CalendarContract.Instances.EVENT_ID`; `AppointmentTest` keeps compiling because the parameter has a default. Do this first, in this task, and add a line to `CalendarStoreTest`:

```kotlin
        assertNotNull(belegt.first().eventId)
```

- [ ] **Step 1: Extend `BusyInterval` and `BusyTimes`**

In `calling/Appointment.kt`:

```kotlin
data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    /** The calendar event behind it, so an existing entry can be linked. */
    val eventId: Long? = null,
)
```

In `calendar/BusyTimes.kt`, add `CalendarContract.Instances.EVENT_ID` to the projection and read it into `eventId`.

- [ ] **Step 2: Run the calendar tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.CalendarStoreTest" --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS.

- [ ] **Step 3: Write the sheet**

Create `app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt`. The timeline runs 8 to 18 at a fixed 56 dp per hour, so a minute has a constant height and the blocks can be positioned by offset:

```kotlin
package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

private const val FIRST_HOUR = 8
private const val LAST_HOUR = 18
private val HOUR_HEIGHT = 56.dp

/**
 * Setting an appointment on site. A sheet rather than a screen, so the business
 * stays visible behind it — the conversation that produced the appointment is
 * usually still going.
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Termin vor Ort",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionLabel("Tag")
            DayRow(draft = draft, onDraft = onDraft, onPickDate = onPickDate)

            SectionLabel("Uhrzeit")
            Timeline(draft = draft, onDraft = onDraft)

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
                ConflictNotice(draft = draft, onLink = onLink, onForce = onForce)
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
```

The remaining composables go in the same file:

```kotlin
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EE", Locale.GERMAN)
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The next five days, starting with the one the draft is on. Picking a day keeps
 * the time of day — you have usually agreed the hour before the date.
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
 * The day from 8 to 18, an hour to a fixed height, so a minute has a constant
 * size and every block can be placed by offset alone.
 */
@Composable
private fun Timeline(draft: AppointmentDraft, onDraft: (AppointmentDraft) -> Unit) {
    if (!draft.calendarReadable) {
        Text(
            text = "Kalender nicht freigegeben — belegte Zeiten werden nicht " +
                "angezeigt. Der Termin wird trotzdem gespeichert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    val start = Clock.millis(draft.startIso) ?: return
    val dayStart = Clock.todayStart(start)
    val density = LocalDensity.current
    val perMinute = HOUR_HEIGHT / 60
    val minutesFromTop = { millis: Long ->
        ((millis - dayStart) / 60_000L).toInt() - FIRST_HOUR * 60
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 16.dp)
            .height(HOUR_HEIGHT * (LAST_HOUR - FIRST_HOUR)),
    ) {
        // The hours themselves.
        (FIRST_HOUR until LAST_HOUR).forEach { hour ->
            Box(modifier = Modifier.offset(y = HOUR_HEIGHT * (hour - FIRST_HOUR))) {
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

        // What is already taken.
        draft.busy.forEach { interval ->
            val top = minutesFromTop(interval.startMillis)
            val length = ((interval.endMillis - interval.startMillis) / 60_000L).toInt()
            if (top + length > 0 && top < (LAST_HOUR - FIRST_HOUR) * 60) {
                BusyBlock(
                    interval = interval,
                    modifier = Modifier
                        .offset(y = perMinute * top.coerceAtLeast(0))
                        .height(perMinute * length.coerceAtMost(12 * 60))
                        .fillMaxWidth(),
                )
            }
        }

        // The appointment being set: drag to move, drag the handle to resize.
        // Everything snaps to a quarter of an hour — nobody agrees 14:07.
        val top = minutesFromTop(start)
        Box(
            modifier = Modifier
                .offset(y = perMinute * top)
                .height(perMinute * draft.minutes)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(draft.startIso) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val minutes = with(density) { (dragAmount.y.toDp() / perMinute).toInt() }
                        val snapped = ((minutes + 7) / 15) * 15
                        if (snapped != 0) {
                            onDraft(draft.copy(startIso = Clock.format(start + snapped * 60_000L)))
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
                    .width(32.dp)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .pointerInput(draft.minutes) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val minutes = with(density) { (dragAmount.y.toDp() / perMinute).toInt() }
                            val snapped = ((draft.minutes + minutes + 7) / 15) * 15
                            onDraft(draft.copy(minutes = snapped.coerceAtLeast(15)))
                        }
                    },
            )
        }
    }
}

@Composable
private fun BusyBlock(interval: BusyInterval, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(HOUR_HEIGHT)
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

The `ConflictNotice` call in `AppointmentSheet` therefore reads:

```kotlin
            if (draft.conflict.isNotEmpty()) {
                ConflictNotice(draft = draft, onDraft = onDraft, onLink = onLink, onForce = onForce)
            }
```

and the `Timeline` handles the missing-permission case itself, so the separate
notice sketched in the sheet body is not needed.

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Check by hand**

Install, open a business, set an appointment. Check: the busy blocks show their titles; dragging snaps to quarter hours; the duration chips change the block's height; saving closes the sheet; the appointment appears in the calendar application with the address in its location field.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calendar/BusyTimes.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/test/java/io/github/amadeusb/callsheet/CalendarStoreTest.kt
git commit -m "Terminpicker: Zeitleiste, Dauer, Ort"
```

---

### Task 10: The appointment section in the detail view

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt`

**Interfaces:**
- Consumes: `Business.appointmentAt` and friends, `Appointment.readableRange`, `AppointmentSheet`.
- Produces: an `AppointmentBlock` composable and the `onAppointment` / `onRemoveAppointment` / `onOpenMap` callbacks on `BusinessDetailScreen`.

- [ ] **Step 1: Add the callbacks**

In `BusinessDetail.kt`, add to `BusinessDetailScreen`'s parameters, next to `onFollowUp`:

```kotlin
    onAppointment: () -> Unit,
    onRemoveAppointment: () -> Unit,
    onOpenMap: (String) -> Unit,
```

- [ ] **Step 2: Add the section**

Directly before the existing `item(key = "follow-up")`:

```kotlin
            item(key = "appointment") {
                Section("Termin vor Ort")
                AppointmentBlock(
                    business = business,
                    onSet = onAppointment,
                    onRemove = { removeAppointment = true },
                    onOpenMap = onOpenMap,
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
    onOpenMap: (String) -> Unit,
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
                        .clickable { onOpenMap(where) }
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

`onPickDate` opens a `DatePickerDialog` in the same style as the follow-up's (`BusinessDetail.kt:321`); on confirm, take the chosen date, keep the draft's time of day, and call `updateAppointmentDraft`.

`onOpenMap` builds the intent:

```kotlin
    private fun openMap(address: String) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(address))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
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
    fun `ein unveraenderter Termin ergibt Unchanged`() {
        val ergebnis = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Zehentstraße 39",
        )

        assertEquals(ReadBack.Unchanged, ergebnis)
    }

    @Test
    fun `ein verschobener Termin ergibt Updated`() {
        val ergebnis = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 16),
            eventEndMillis = millis(2026, 9, 10, 17),
            eventLocation = "Zehentstraße 39",
        ) as ReadBack.Updated

        assertEquals(millis(2026, 9, 10, 16), Clock.millis(ergebnis.startIso))
        assertEquals("Zehentstraße 39", ergebnis.location)
    }

    @Test
    fun `ein umgezogener Termin ergibt Updated`() {
        val ergebnis = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = "Zehentstraße 39",
            eventStartMillis = millis(2026, 9, 10, 14),
            eventEndMillis = millis(2026, 9, 10, 15),
            eventLocation = "Im Büro",
        ) as ReadBack.Updated

        assertEquals("Im Büro", ergebnis.location)
    }

    @Test
    fun `ein geloeschter Termin ergibt Gone`() {
        val ergebnis = Appointment.readBack(
            currentAt = at, currentEnd = until, currentLocation = null,
            eventStartMillis = null, eventEndMillis = null, eventLocation = null,
        )

        assertEquals(ReadBack.Gone, ergebnis)
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
        val startIso = Clock.format(eventStartMillis)
        val endIso = Clock.format(eventEndMillis)
        val same = startIso == currentAt &&
            endIso == currentEnd &&
            eventLocation == currentLocation
        return if (same) ReadBack.Unchanged else ReadBack.Updated(startIso, endIso, eventLocation)
    }
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS, fifteen tests.

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
                    repo.setStatus(placeId, Status.CALLED)
                    _state.update {
                        it.copy(hint = "Der Termin wurde im Kalender gelöscht. Status zurück auf „Angerufen“.")
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
- Consumes: `Appointment.address`, the `onOpenMap` callback from Task 10.
- Produces: nothing new.

- [ ] **Step 1: Make the address tappable**

In `BusinessDetail.kt`, in `MasterData`, replace the address rows with:

```kotlin
        Appointment.address(business.street, business.postalCode, business.city)?.let { address ->
            DataRow("Anschrift", address) { onOpenMap(address) }
        }
```

`MasterData` needs `onOpenMap: (String) -> Unit` passed through from `BusinessDetailScreen`.

This also drops the two-line address in favour of the one-liner `Appointment.address` produces, which is what goes into the calendar — one formatting rule instead of two that can drift.

- [ ] **Step 2: Show the street in the work list**

In `Components.kt`, in the business row around line 124, extend the subtitle from the city alone to street and city:

```kotlin
            val where = listOfNotNull(
                business.street?.takeIf { it.isNotBlank() },
                business.city?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifEmpty { null }
```

and use `where` where `business.city` was used. Keep the existing `maxLines` and `TextOverflow.Ellipsis` — the street makes the line longer and it must still not wrap.

- [ ] **Step 3: Build and check by hand**

Run: `./gradlew assembleDebug`

The work list shows street and city on one line, truncated cleanly on a narrow screen. Tapping the address in the detail view opens a map application; with none installed, nothing happens and the app does not crash (`runCatching` in `openMap`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt
git commit -m "Anschrift öffnet die Karten-App, Straße in der Arbeitsliste"
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
            val appointments = repo.appointmentsDue(Clock.todayStart(now) + 24L * 60 * 60 * 1000)
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
Expected: PASS, with `MigrationTest`, `AppointmentTest`, `PreferencesTest`, `CalendarStoreTest`, `RepositoryTest` and `ImporterTest` all reporting.

- [ ] **Step 2: Build the release APK**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL.

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
