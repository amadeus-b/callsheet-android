# Callbacks as appointments — Implementation Plan (server and app)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A follow-up („Wiedervorlage") becomes an appointment of the kind „Rückruf": several per business, with note, contact person and calendar event, completed by a call, marked „✓" in the calendar, and listed with every coming appointment in an agenda behind the calendar button.

**Architecture:** Two nullable columns on `appointments`, `kind` (`'visit'` | `'callback'`, NULL reads as visit) and `done_at`, on server and app. Existing `follow_up_at` values are carried over on both sides under the fixed id `followup-<place_id>`. The server's `write` keeps stored values for columns a payload does not carry at all, so a 1.4.0 device cannot strip them. Every rule — titles, lengths, status, overdue, the agenda's grouping — is a pure function (`calling/Appointment.kt`, new `calling/Agenda.kt`); the view model only carries them out.

**Tech Stack:** Server: Node 24, `node:sqlite`, `node --test`. App: Kotlin, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `CalendarContract`, JUnit 4 + Robolectric, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-14-callbacks-as-appointments-design.md`

## Global Constraints

- **Two repositories, both on branch `main`.** App: `~/code/tm-services-automate/caller-app/app` (Kotlin sources under `app/src/main/java/io/github/amadeusb/callsheet/`, written `…/` below; tests under `app/src/test/java/io/github/amadeusb/callsheet/`, written `test/…` below). Server: `~/code/tm-services-automate/caller-app/server`. Never push `master`. Every step says which repository it runs in.
- **Numbers used in this plan:** app `Database.VERSION` goes **5 → 6**, the server migration is **`008-callbacks.sql`**. They assume the e-mail work (app version 5, server migrations 006 and 007) is committed before Task 0. Task 0 checks this; if the numbers differ, use the next free ones consistently everywhere the plan says 6 or 008.
- **Start on clean trees.** Nothing of another session's uncommitted work goes into any commit of this plan.
- **Tests:** app `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`; build check `./gradlew assembleDebug`. Server `node --test` (all) or `node --test test/<file>.test.js`.
- **Robolectric has no calendar provider.** Nothing that talks to `CalendarContract` is unit-tested; decisions live in `Appointment` and `Agenda` and are tested there.
- **Language:** code, identifiers, comments, docs in English; UI text German with correct umlauts and „…" quotes. Test names in English; the app fixture method stays `fun aufbau()`.
- **Timestamps:** ISO-8601 with offset via `Clock.format`, compared through `Clock.millis`. In tests build instants from ISO strings with `+02:00`.
- **Kind values:** `AppointmentKind.VISIT` = `"visit"`, label „Vor Ort"; `AppointmentKind.CALLBACK` = `"callback"`, label „Rückruf". NULL or unknown reads as `VISIT`. The app writes the key on every save.
- **Local-only columns stay local:** `dirty`, `contact_version`, `calendar_event_id`, `calendar_seen_*`. `kind`, `done_at` and `event_uid` travel.
- **A callback lasts 15 minutes by default** (`Appointment.CALLBACK_MINUTES`); duration chips for callbacks: 15, 30.
- **Callbacks never touch the business status.**
- **Every task leaves both projects building and all tests green.**
- **State writes** in new view model code use `_state.update { … }`.
- **Commit after every task,** listing files explicitly. German commit messages in the tone of `git log`, no attribution lines.
- **Rollout order:** server deployed first (Task 13), then the app released (Task 14). Both are outward-facing: ask the user before each.

---

## File Structure

| Repository | File | Change |
|---|---|---|
| server | `src/receive.js` | `write` keeps stored values for columns absent from the payload |
| server | `db/migrations/008-callbacks.sql` | new: `kind`, `done_at`, carry-over of `follow_up_at`, counter |
| server | `test/receive.test.js`, `test/db.test.js` | missing-key rule, callbacks travel, migration 008 |
| server | `README.md` | the missing-key rule, `kind` NULL = visit |
| app | `…/data/Models.kt` | `AppointmentKind`; `AppointmentEntry.kind`, `.doneAt`; Task 11 removes `Business.followUpAt` |
| app | `…/data/Database.kt` | `VERSION = 6`, the two columns on create and upgrade, carry-over |
| app | `…/data/Repository.kt` | read/write `kind`, `done_at`; `completeCallbacks`; `agenda`; Task 11 removes `due`, `appointmentsDue`, `setFollowUp` |
| app | `…/data/Clock.kt` | `nextDayStart` |
| app | `…/calling/Appointment.kt` | callback length and durations, kind-aware title, status, `isOverdue`, `splitCallbacks`; Task 11 removes `rowLabel` |
| app | `…/calling/Agenda.kt` | new: `AgendaGroup`, `AgendaSection`, `Agenda.sections`, `title`, `rowLabel` |
| app | `…/CallsheetViewModel.kt` | draft kind, `openCallback`, kind-aware save/remove/read-back, completing on call, ✓ following, agenda state |
| app | `…/ui/AppointmentSheet.kt` | kind-aware title, durations, location, button |
| app | `…/ui/BusinessDetail.kt` | `CallbacksBlock` replaces `FollowUpBlock`; visits only in `AppointmentsBlock`; removal dialog by kind |
| app | `…/ui/Agenda.kt` | new `AgendaScreen`; `…/ui/Today.kt` deleted |
| app | `…/ui/Components.kt`, `…/ui/WorkList.kt`, `…/MainActivity.kt` | row label, button, wiring |
| app | tests | `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `RepositoryTest`, `AppointmentTest`, new `AgendaTest`, `GeoUriTest`, `PhoneBookEntriesTest` |
| app | `docs/usage.md`, `docs/architecture.md`, `docs/data-model.md`, `README.md`, `CHANGELOG.md` | docs |

---

### Task 0: Preconditions

**Files:** none. Spec and plan are already committed.

- [ ] **Step 1: Both trees clean**

Run (app): `git -C ~/code/tm-services-automate/caller-app/app status --porcelain`
Expected: empty.

Run (server): `git -C ~/code/tm-services-automate/caller-app/server status --porcelain`
Expected: empty.

Anything else — above all the uncommitted e-mail work (`MailClient.kt`, `MailDialog.kt`, `sendMail.js`, migrations 006/007) — **stop and ask the user** to have it committed first. Do not stash another session's work.

- [ ] **Step 2: Check the numbers**

Run (app): `grep -n "const val VERSION" app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt`
Expected: `const val VERSION = 5`

Run (server): `ls ~/code/tm-services-automate/caller-app/server/db/migrations/`
Expected: highest file starts with `007-`.

If either differs, the plan's 6 becomes current + 1 and 008 becomes highest + 1, everywhere.

- [ ] **Step 3: Everything green before starting**

Run (app): `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

Run (server): `node --test`
Expected: all tests pass.

A red suite here is not this plan's to fix: stop and report.

---

### Task 1: Server — a missing key is not an empty value

**Repository:** server.

**Files:**
- Modify: `src/receive.js` (function `write`)
- Modify: `README.md` (section `## Migrationen`)
- Test: `test/receive.test.js`

**Interfaces:**
- Produces: `write(db, table, row, sequence)` — same signature. A column whose key is absent from `row` keeps the stored value on update and gets the column default on insert; an explicit `null` still writes NULL.

- [ ] **Step 1: Write the failing tests**

Append to `test/receive.test.js`, above `test('isNewer compares timestamps', …)`:

```js
test('a column the payload does not carry keeps its stored value', () => {
  // A device on an older schema sends no key at all for a column it never had.
  // That is not "empty", it is "not mine to say" — writing NULL there would
  // undo what a newer device stored.
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...BUSINESS, latitude: 48.8059466 }] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, note: 'neu', updated_at: '2026-09-07T11:00:00+02:00' }] })

  const row = db.prepare('SELECT note, latitude FROM businesses WHERE place_id = ?').get('P1')
  assert.equal(row.note, 'neu')
  assert.equal(row.latitude, 48.8059466)
})

test('an explicit null still clears a column', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...BUSINESS, note: 'alt' }] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, note: null, updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(db.prepare('SELECT note FROM businesses WHERE place_id = ?').get('P1').note, null)
})

test('a new row without a key takes the column default', () => {
  const db = freshDb()
  const { closed, ...withoutClosed } = BUSINESS

  const { rejected } = receive(db, { ...empty, businesses: [withoutClosed] })

  assert.deepEqual(rejected, [])
  assert.equal(db.prepare('SELECT closed FROM businesses WHERE place_id = ?').get('P1').closed, 0)
})
```

- [ ] **Step 2: Run them to see them fail**

Run: `node --test test/receive.test.js`
Expected: FAIL — `latitude` is `null` in the first; in the third the row is rejected with a `NOT NULL constraint failed: businesses.closed`.

- [ ] **Step 3: Implement**

Replace `function write(db, table, row, sequence) { … }` in `src/receive.js` with:

```js
/**
 * Writes a whole row under a new sequence number.
 *
 * A key the payload does not carry at all is not a NULL. An app always sends
 * every column of its own schema, so a missing key means an older schema — a
 * device that never had the column. Writing NULL there would strip what a
 * newer device stored: a 1.4.0 phone editing a callback would turn it back
 * into a visit and forget it was completed. So such a column keeps the stored
 * value, and a new row gets the column's default. An explicit `null` is an
 * answer and is written.
 *
 * `INSERT OR REPLACE` deletes and re-inserts, so "keeps" has to be spelled
 * out: the stored value is read and written back.
 */
function write(db, table, row, sequence) {
  const key = KEYS[table]
  const existing = db.prepare(`SELECT * FROM ${table} WHERE ${key} = ?`).get(row[key])
  const columns = writableColumns(db, table)
    .filter(column => Object.hasOwn(row, column) || existing !== undefined)
  const values = columns.map(column => (Object.hasOwn(row, column) ? row[column] : existing[column]))
  columns.push('server_seq')
  values.push(sequence)
  db.prepare(
    `INSERT OR REPLACE INTO ${table} (${columns.join(', ')}) VALUES (${columns.map(() => '?').join(', ')})`
  ).run(...values)
}
```

- [ ] **Step 4: Run the whole suite**

Run: `node --test`
Expected: all pass. Should an existing test fail because it relied on an absent key clearing a column, stop and report it — do not change that test's meaning.

- [ ] **Step 5: README**

In `README.md`, at the end of section `## Migrationen` (after the paragraph ending „…bis zur nächsten echten Bearbeitung der Zeile falsch."), add:

```markdown
**Ein fehlendes Feld ist kein leeres.** Schickt eine App eine Spalte gar nicht
mit, stammt sie von einem älteren Schema. `write` behält dann den gespeicherten
Wert (bei einer neuen Zeile gilt der Vorgabewert der Spalte). Nur ein
ausdrückliches `null` leert eine Spalte. Sonst würde ein Telefon mit altem
Stand bei jeder Änderung Spalten löschen, die es nicht kennt.
```

- [ ] **Step 6: Commit**

```bash
git add src/receive.js test/receive.test.js README.md
git commit -m "Fehlende Felder behalten den gespeicherten Wert, nur null leert"
```

---

### Task 2: Server — migration 008, callbacks travel

**Repository:** server.

**Files:**
- Create: `db/migrations/008-callbacks.sql`
- Modify: `README.md`
- Test: `test/db.test.js`, `test/receive.test.js`

**Interfaces:**
- Consumes: Task 1's `write`.
- Produces: `appointments.kind TEXT` (NULL = visit), `appointments.done_at TEXT`; rows `followup-<place_id>` with `kind = 'callback'`; `businesses.follow_up_at` emptied.

- [ ] **Step 1: Write the failing migration tests**

Append to `test/db.test.js`:

```js
/**
 * A database in the state a running server is in when 008 arrives: every
 * earlier migration applied and recorded, a counter that has moved,
 * businesses with follow-ups and one appointment on site.
 */
function databaseBefore008() {
  const path = join(mkdtempSync(join(tmpdir(), 'callsheet-')), 'test.db')
  const db = open(path)
  db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL)')
  for (const filename of MIGRATIONS.filter(name => name < '008')) {
    db.exec(readFileSync(join('db/migrations', filename), 'utf8'))
    db.prepare('INSERT INTO schema_migrations (filename, applied_at) VALUES (?, ?)').run(filename, new Date().toISOString())
  }
  db.prepare('UPDATE sync_counter SET value = 7').run()
  const business = db.prepare(
    `INSERT INTO businesses (place_id, name, closed, is_target, status, updated_at, server_seq, follow_up_at)
     VALUES (?, ?, 0, 1, ?, ?, ?, ?)`
  )
  business.run('P2', 'Gartenbau Merten', 'no_answer', '2026-09-07T11:00:00+02:00', 4, '2026-09-16T14:00:00+02:00')
  business.run('P1', 'Elektro Meier', 'called', '2026-09-07T10:00:00+02:00', 3, '2026-09-15T09:00:00+02:00')
  business.run('P3', 'Malerei Huber', 'new', '2026-09-07T12:00:00+02:00', 5, null)
  db.prepare(
    `INSERT INTO appointments (id, place_id, starts_at, ends_at, location, note, contact_id, event_uid, updated_at, server_seq)
     VALUES ('T1', 'P3', '2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', NULL, 'Besichtigung', NULL, 'T1', '2026-09-07T12:00:00+02:00', 6)`
  ).run()
  return db
}

test('migration 008 carries each follow-up over as a callback of its own', () => {
  const db = databaseBefore008()

  migrate(db, 'db/migrations')

  const rows = db.prepare("SELECT * FROM appointments WHERE kind = 'callback' ORDER BY id").all().map(row => ({ ...row }))
  assert.deepEqual(rows.map(row => row.id), ['followup-P1', 'followup-P2'])
  const { server_seq, ...first } = rows[0]
  assert.deepEqual(first, {
    id: 'followup-P1',
    place_id: 'P1',
    starts_at: '2026-09-15T09:00:00+02:00',
    ends_at: null,
    location: null,
    note: null,
    contact_id: null,
    event_uid: null,
    // The business's timestamp: the app's own carried-over row arrives as a standstill.
    updated_at: '2026-09-07T10:00:00+02:00',
    kind: 'callback',
    done_at: null,
  })
})

test('migration 008 hands out unique sequence numbers and moves the counter past them', () => {
  const db = databaseBefore008()

  migrate(db, 'db/migrations')

  const sequences = db.prepare("SELECT server_seq FROM appointments WHERE kind = 'callback' ORDER BY server_seq")
    .all().map(row => row.server_seq)
  assert.deepEqual(sequences, [8, 9])
  assert.equal(db.prepare('SELECT value FROM sync_counter').get().value, 9)
})

test('migration 008 empties follow_up_at and leaves the businesses unmarked', () => {
  const db = databaseBefore008()

  migrate(db, 'db/migrations')

  const businesses = db.prepare('SELECT follow_up_at, updated_at, server_seq FROM businesses ORDER BY place_id')
    .all().map(row => ({ ...row }))
  for (const business of businesses) assert.equal(business.follow_up_at, null)
  assert.deepEqual(businesses.map(row => row.server_seq), [3, 4, 5])
  assert.equal(businesses[0].updated_at, '2026-09-07T10:00:00+02:00')
})

test('migration 008 leaves appointments on site as they were, kind NULL', () => {
  const db = databaseBefore008()

  migrate(db, 'db/migrations')

  const row = db.prepare("SELECT kind, done_at, server_seq FROM appointments WHERE id = 'T1'").get()
  assert.equal(row.kind, null)
  assert.equal(row.done_at, null)
  assert.equal(row.server_seq, 6)
})
```

- [ ] **Step 2: Run them to see them fail**

Run: `node --test test/db.test.js`
Expected: FAIL — `no such column: kind`.

- [ ] **Step 3: Write the migration**

Create `db/migrations/008-callbacks.sql`:

```sql
-- Follow-ups become appointments of the kind 'callback'. A business held one
-- follow-up, a bare timestamp in businesses.follow_up_at; as an appointment a
-- callback gets a note, a contact person, a calendar event, and company.
--
-- Both columns nullable, as this README asks of every new column: a NOT NULL
-- DEFAULT would stand where a gap belongs, and receive.js fills only gaps. A
-- NULL kind is a visit — every appointment before this migration, and every
-- one a 1.4.0 app still creates.

ALTER TABLE appointments ADD COLUMN kind TEXT;
ALTER TABLE appointments ADD COLUMN done_at TEXT;

-- The carry-over, the way 005 carried appointments over. The id is fixed: the
-- app's own migration writes the same 'followup-' || place_id, so the two rows
-- meet as one. updated_at comes from the business, so where it was
-- synchronised the rows arrive at a standstill.
--
-- Each row gets its own sequence number above the counter, so every device
-- fetches it like any other row.
INSERT INTO appointments (id, place_id, starts_at, ends_at, location, note, contact_id, event_uid,
                          updated_at, kind, done_at, server_seq)
    SELECT 'followup-' || place_id, place_id, follow_up_at, NULL, NULL, NULL, NULL, NULL,
           updated_at, 'callback', NULL,
           (SELECT value FROM sync_counter) + ROW_NUMBER() OVER (ORDER BY place_id)
    FROM businesses
    WHERE follow_up_at IS NOT NULL AND follow_up_at <> '';

-- Every callback in the table came from the INSERT above.
UPDATE sync_counter SET value = value + (SELECT COUNT(*) FROM appointments WHERE kind = 'callback');

-- Emptied, not dropped, for the reason 005 gives. Emptied here as well as in
-- the app, or the next standstill fills it straight back. Not a change to the
-- business: no new sequence number, no new updated_at.
UPDATE businesses SET follow_up_at = NULL WHERE follow_up_at IS NOT NULL;
```

- [ ] **Step 4: Run the migration tests**

Run: `node --test test/db.test.js`
Expected: PASS.

- [ ] **Step 5: Receive tests for callbacks**

In `test/receive.test.js`, extend the `APPOINTMENT` fixture — an updated app sends both keys:

```js
const APPOINTMENT = {
  id: 'T1', place_id: 'P1',
  starts_at: '2026-09-10T14:00:00+02:00', ends_at: '2026-09-10T15:00:00+02:00',
  location: 'Zehentstraße 39, 85055 Ingolstadt', note: 'Besichtigung', contact_id: 'K1',
  event_uid: 'T1', updated_at: '2026-09-07T10:00:00+02:00',
  kind: 'visit', done_at: null,
}
```

Add `import { deliver } from '../src/deliver.js'` below the existing imports, and append above `test('isNewer compares timestamps', …)`:

```js
const CALLBACK = {
  ...APPOINTMENT, id: 'R1', location: null, note: 'wegen Angebot nachfragen',
  starts_at: '2026-09-15T09:00:00+02:00', ends_at: '2026-09-15T09:15:00+02:00',
  event_uid: 'R1', kind: 'callback', done_at: null,
}

test('a callback travels with its kind and its completion', () => {
  const db = freshDb()
  receive(db, { ...empty, appointments: [{ ...CALLBACK, done_at: '2026-09-15T09:05:00+02:00' }] })

  const row = deliver(db, 0).appointments.find(appointment => appointment.id === 'R1')

  assert.equal(row.kind, 'callback')
  assert.equal(row.done_at, '2026-09-15T09:05:00+02:00')
})

test('a 1.4.0 app changing a callback leaves it a completed callback', () => {
  // Its schema has neither column, so its row carries neither key.
  const db = freshDb()
  receive(db, { ...empty, appointments: [{ ...CALLBACK, done_at: '2026-09-15T09:05:00+02:00' }] })
  const { kind, done_at, ...older } = CALLBACK

  receive(db, { ...empty, appointments: [{ ...older, note: 'geändert', updated_at: '2026-09-07T11:00:00+02:00' }] })

  const row = storedAppointment(db, 'R1')
  assert.equal(row.note, 'geändert')
  assert.equal(row.kind, 'callback')
  assert.equal(row.done_at, '2026-09-15T09:05:00+02:00')
})

test('an appointment a 1.4.0 app creates is stored without a kind', () => {
  const db = freshDb()
  const { kind, done_at, ...older } = APPOINTMENT

  receive(db, { ...empty, appointments: [older] })

  assert.equal(storedAppointment(db).kind, null)
})

test('a standstill fills a missing kind', () => {
  const db = freshDb()
  const { kind, done_at, ...older } = CALLBACK
  receive(db, { ...empty, appointments: [older] })

  receive(db, { ...empty, appointments: [CALLBACK] })

  assert.equal(storedAppointment(db, 'R1').kind, 'callback')
})
```

- [ ] **Step 6: Run the whole suite**

Run: `node --test`
Expected: all pass.

- [ ] **Step 7: README**

In `README.md`, below the paragraph added in Task 1, add:

```markdown
**Termine haben eine Art.** `appointments.kind` ist `'visit'` (Termin vor Ort)
oder `'callback'` (Rückruf), `NULL` gilt als Termin vor Ort: alle Termine vor
Migration 008 und alle, die eine App mit Stand 1.4.0 anlegt. `done_at` ist der
Zeitpunkt, zu dem ein Rückruf erledigt wurde. Migration 008 hat jede
Wiedervorlage aus `businesses.follow_up_at` als Rückruf `followup-<place_id>`
übernommen und die Spalte geleert.
```

- [ ] **Step 8: Commit**

```bash
git add db/migrations/008-callbacks.sql test/db.test.js test/receive.test.js README.md
git commit -m "Migration 008: Wiedervorlagen werden Rückrufe, Termine bekommen Art und Erledigt"
```

---
### Task 3: App — schema 6, the kind on every appointment, carry-over

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt`
- Modify: `…/data/Database.kt`
- Modify: `…/data/Repository.kt` (`saveAppointment`, `appointmentFromCursor`)
- Test: `test/…/MigrationTest.kt`, `test/…/SyncSchemaTest.kt`, `test/…/SyncStoreTest.kt`, `test/…/RepositoryTest.kt`

**Interfaces:**
- Produces:
  - `enum class AppointmentKind(val key: String, val label: String) { VISIT("visit", "Vor Ort"), CALLBACK("callback", "Rückruf") }` with `companion fun fromKey(s: String?): AppointmentKind`
  - `AppointmentEntry.kind: AppointmentKind = AppointmentKind.VISIT`, `AppointmentEntry.doneAt: String? = null` (last two parameters)
  - `Database.VERSION == 6`; columns `appointments.kind TEXT`, `appointments.done_at TEXT`
  - `Repository.saveAppointment(entry)` writes `kind` always and `done_at` only when non-null (never clears it)

- [ ] **Step 1: Write the failing schema and migration tests**

In `test/…/SyncSchemaTest.kt` add:

```kotlin
    @Test
    fun `appointments carry a kind and a completion`() {
        val appointments = columns("appointments")
        assertTrue(appointments.contains("kind"))
        assertTrue(appointments.contains("done_at"))
    }
```

In `test/…/MigrationTest.kt`, add below `createVersionThree()`:

```kotlin
    /**
     * The version 4 schema, as it shipped in 1.4.0: version 3 with its
     * appointment carried over into the appointments table, and the contact
     * columns the version 5 migration reads. alt-1 keeps the follow-up it had
     * since version 1 and is synchronised; alt-2 has one that has not been
     * uploaded yet; alt-3 has none.
     */
    private fun createVersionFour() {
        createVersionThree()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        val contactColumns = columnsOf("contacts", db)
        for (column in listOf("role TEXT", "email TEXT", "note TEXT", "contact_version INTEGER")) {
            if (column.substringBefore(' ') !in contactColumns) db.execSQL("ALTER TABLE contacts ADD COLUMN $column")
        }
        db.execSQL(
            """
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
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO appointments (id, place_id, starts_at, ends_at, updated_at, dirty) " +
                "VALUES ('legacy-alt-1', 'alt-1', '2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', " +
                "'2026-09-07T12:00:00+02:00', 0)"
        )
        db.execSQL(
            "UPDATE businesses SET appointment_at = NULL, appointment_end_at = NULL, " +
                "appointment_location = NULL, calendar_event_id = NULL"
        )
        db.execSQL("UPDATE businesses SET follow_up_at = '2026-09-15T09:00:00+02:00', dirty = 1 WHERE place_id = 'alt-2'")
        db.version = 4
        db.close()
    }
```

Add before `// --- and a database that never had to migrate at all`:

```kotlin
    // --- from version 4, the road 1.4.0 devices are on ----------------------

    @Test
    fun `an upgrade from version four carries each follow-up over as a callback`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, starts_at, ends_at, location, note, contact_id, event_uid, updated_at, " +
                "kind, done_at, calendar_event_id, dirty FROM appointments WHERE kind = 'callback' ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("followup-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertEquals("2026-09-10T09:00:00+02:00", c.getString(2))
            for (i in 3..7) assertTrue("column $i not empty", c.isNull(i))
            // The business's timestamp, so the server's carried-over row meets this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(8))
            assertEquals("callback", c.getString(9))
            assertTrue(c.isNull(10))
            // No calendar event: each device would create its own.
            assertTrue(c.isNull(11))
            // Synchronised business, nothing to send.
            assertEquals(0, c.getInt(12))
            assertTrue(c.moveToNext())
            assertEquals("followup-alt-2", c.getString(0))
            assertEquals("2026-09-15T09:00:00+02:00", c.getString(2))
            assertEquals("2026-09-08T09:00:00+02:00", c.getString(8))
            // Not uploaded yet: goes up as a callback.
            assertEquals(1, c.getInt(12))
        }
    }

    @Test
    fun `an upgrade from version four empties follow_up_at without marking the business`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT place_id, follow_up_at, dirty, updated_at FROM businesses ORDER BY place_id", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("alt-1", c.getString(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(3))
            while (c.moveToNext()) assertTrue("follow_up_at left on ${c.getString(0)}", c.isNull(1))
        }
    }

    @Test
    fun `an upgrade from version four leaves appointments on site without a kind`() {
        createVersionFour()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT kind, done_at, dirty FROM appointments WHERE id = 'legacy-alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
    }
```

In the existing test `a fresh database and an upgraded one have the same appointments table`, below `assertTrue("event_uid" in fresh)` add:

```kotlin
        assertTrue("kind" in fresh)
        assertTrue("done_at" in fresh)
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: FAIL — no column `kind`.

If `createVersionFour` itself throws while upgrading through version 5 (the e-mail migration reading a contacts column the fixture lacks), add that column to the `listOf(...)` in `createVersionFour` — the fixture must describe 1.4.0 as shipped, and 1.4.0 contacts had `role`, `email`, `note`, `contact_version`.

- [ ] **Step 3: The model**

In `…/data/Models.kt`, above the KDoc of `data class AppointmentEntry`, add:

```kotlin
/**
 * What an appointment is. Stored as [key]. A null or unknown key reads as
 * [VISIT]: every appointment before schema 6 is one, and so is every one a
 * 1.4.0 device still creates.
 */
enum class AppointmentKind(val key: String, val label: String) {
    VISIT("visit", "Vor Ort"),
    CALLBACK("callback", "Rückruf");

    companion object {
        fun fromKey(s: String?): AppointmentKind =
            entries.firstOrNull { it.key == s } ?: VISIT
    }
}
```

Change the KDoc first paragraph of `AppointmentEntry` to:

```kotlin
/**
 * An appointment: on site, or a callback. A business can have any number of
 * them — one after another, or side by side.
```

and append two parameters after `seenLocation`:

```kotlin
    val seenLocation: String? = null,
    val kind: AppointmentKind = AppointmentKind.VISIT,
    /** When a callback was completed. Null while open, and always for a visit. */
    val doneAt: String? = null,
)
```

- [ ] **Step 4: The schema**

In `…/data/Database.kt`:

Change `const val VERSION = 5` to `const val VERSION = 6`.

In `companion object`, below `INDEXES_APPOINTMENTS`, add:

```kotlin
        /**
         * Schema 6: the kind of an appointment and when a callback was
         * completed. Added by ALTER on both roads — onCreate and the upgrade —
         * so an upgrade from before schema 4, which creates TABLE_APPOINTMENTS
         * first, does not meet a column that is already there.
         *
         * Both nullable: a NULL kind is a visit, and a row the server stores
         * without a kind must not break a NOT NULL column here on arrival.
         */
        private val COLUMNS_APPOINTMENTS_6 = listOf(
            "ALTER TABLE appointments ADD COLUMN kind TEXT",
            "ALTER TABLE appointments ADD COLUMN done_at TEXT",
        )
```

In `onCreate`, directly after `for (sql in INDEXES_APPOINTMENTS) db.execSQL(sql)`, add:

```kotlin
        for (sql in COLUMNS_APPOINTMENTS_6) db.execSQL(sql)
```

At the end of `onUpgrade`, after the `if (old < 5) { … }` block, add:

```kotlin
        if (old < 6) {
            for (sql in COLUMNS_APPOINTMENTS_6) db.execSQL(sql)
            // Every follow-up becomes a callback. The id is fixed, not a fresh
            // UUID: the server's migration 008 writes the same
            // 'followup-' || place_id, so the two meet as one row. updated_at
            // comes from the business for the same reason.
            //
            // dirty is the business's: a follow-up not uploaded yet goes up as
            // a callback, one the server already has is not sent again. No
            // calendar link — every device would create its own event for the
            // same callback; it gets one when it is next saved.
            db.execSQL(
                """
                INSERT INTO appointments (id, place_id, starts_at, updated_at, kind, dirty)
                SELECT 'followup-' || place_id, place_id, follow_up_at, updated_at, 'callback', dirty
                FROM businesses
                WHERE follow_up_at IS NOT NULL AND follow_up_at <> ''
                """.trimIndent()
            )
            // Emptied on both sides, or the next standstill fills it back. Not a
            // change to the business: no updated_at, no mark.
            db.execSQL("UPDATE businesses SET follow_up_at = NULL WHERE follow_up_at IS NOT NULL")
        }
```

In the `CREATE TABLE businesses` of `onCreate`, change the line `follow_up_at    TEXT,` to:

```kotlin
                -- The one follow-up a business held up to schema 5. Emptied by
                -- the migration to 6 — callbacks are appointments since.
                follow_up_at    TEXT,
```

- [ ] **Step 5: Run the migration and schema tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: PASS.

- [ ] **Step 6: Write the failing repository and sync tests**

In `test/…/RepositoryTest.kt`, below the helper `visit(...)`, add:

```kotlin
    private fun callback(id: String, placeId: String, startsAt: String, doneAt: String? = null) =
        AppointmentEntry(
            id = id, placeId = placeId, startsAt = startsAt, endsAt = null,
            location = null, note = null, contactId = null,
            kind = AppointmentKind.CALLBACK, doneAt = doneAt,
        )
```

add `import io.github.amadeusb.callsheet.data.AppointmentKind`, and these tests below `a business's appointments come back earliest first`:

```kotlin
    @Test
    fun `a callback is stored and read back with its kind`() = runTest {
        repo.saveAppointment(callback("R-1", "t-1", "2026-09-15T09:00:00+02:00"))
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00"))

        assertEquals(AppointmentKind.CALLBACK, repo.appointment("R-1")!!.kind)
        assertNull(repo.appointment("R-1")!!.doneAt)
        assertEquals(AppointmentKind.VISIT, repo.appointment("A-1")!!.kind)
        assertEquals(1, count("SELECT COUNT(*) FROM appointments WHERE id = 'A-1' AND kind = 'visit'"))
    }

    @Test
    fun `a row without a kind reads as a visit`() = runTest {
        execute(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, dirty) " +
                "VALUES ('A-9', 't-1', '2026-09-15T09:00:00+02:00', '2026-09-07T10:00:00+02:00', 0)"
        )

        assertEquals(AppointmentKind.VISIT, repo.appointment("A-9")!!.kind)
    }

    @Test
    fun `saving an entry without a completion keeps the completion already stored`() = runTest {
        repo.saveAppointment(callback("R-1", "t-1", "2026-09-15T09:00:00+02:00"))
        execute("UPDATE appointments SET done_at = '2026-09-15T09:05:00+02:00' WHERE id = 'R-1'")

        // A sheet opened before the call completed it carries none.
        repo.saveAppointment(callback("R-1", "t-1", "2026-09-15T10:00:00+02:00"))

        val stored = repo.appointment("R-1")!!
        assertEquals("2026-09-15T09:05:00+02:00", stored.doneAt)
        assertEquals("2026-09-15T10:00:00+02:00", stored.startsAt)
    }
```

In `test/…/SyncStoreTest.kt` add below `an older incoming appointment is not reported as written`:

```kotlin
    @Test
    fun `a callback's kind and completion go up`() {
        schreibe(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, kind, done_at, dirty) VALUES " +
                "('R1', 'P1', '2026-09-15T09:00:00+02:00', '2026-09-07T10:00:00+02:00', 'callback', " +
                "'2026-09-15T09:05:00+02:00', 1)"
        )

        val row = store.pending(500).getJSONArray("appointments").getJSONObject(0)

        assertEquals("callback", row.getString("kind"))
        assertEquals("2026-09-15T09:05:00+02:00", row.getString("done_at"))
    }

    @Test
    fun `an incoming appointment without a kind keeps the kind stored here`() {
        // A server before migration 008 knows neither column and sends neither key.
        schreibe(
            "INSERT INTO appointments (id, place_id, starts_at, updated_at, kind, done_at, dirty) VALUES " +
                "('T1', 'P1', '2026-09-15T09:00:00+02:00', '2026-09-07T10:00:00+02:00', 'callback', " +
                "'2026-09-15T09:05:00+02:00', 0)"
        )

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(terminJson("T1", "2026-09-07T11:00:00+02:00")))))

        Database(ctx).readableDatabase.rawQuery("SELECT kind, done_at, note FROM appointments WHERE id = 'T1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("callback", c.getString(0))
            assertEquals("2026-09-15T09:05:00+02:00", c.getString(1))
            assertEquals("Angebot", c.getString(2))
        }
    }
```

- [ ] **Step 7: Run them to see the repository tests fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.SyncStoreTest"`
Expected: `RepositoryTest` FAILS — kind reads as `VISIT` for `R-1`, `doneAt` not read. The two `SyncStoreTest` tests may already pass (the sync layer reads columns from the schema); that is the point of that layer.

- [ ] **Step 8: Repository reads and writes the columns**

In `…/data/Repository.kt`, in `appointmentFromCursor`, after `seenLocation = c.text("calendar_seen_location"),` add:

```kotlin
        kind = AppointmentKind.fromKey(c.text("kind")),
        doneAt = c.text("done_at"),
```

In `saveAppointment`, extend the KDoc with:

```kotlin
     * The same holds for [AppointmentEntry.doneAt]: a sheet opened before a
     * call completed the callback carries none, and saving it must not reopen
     * the callback. Nothing in the app reopens one.
```

and in its `ContentValues`, after `if (entry.eventUid != null) put("event_uid", entry.eventUid)`, add:

```kotlin
            put("kind", entry.kind.key)
            if (entry.doneAt != null) put("done_at", entry.doneAt)
```

- [ ] **Step 9: Run everything**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Schema 6: Termine haben eine Art, Wiedervorlagen werden Rückrufe"
```

---
### Task 4: App — the rules for a callback

**Repository:** app.

**Files:**
- Modify: `…/calling/Appointment.kt`
- Modify: `…/CallsheetViewModel.kt` (call sites of the changed signatures only)
- Test: `test/…/AppointmentTest.kt`

**Interfaces:**
- Consumes: `AppointmentKind`, `AppointmentEntry.kind`, `.doneAt` (Task 3).
- Produces (all in `object Appointment`):
  - `const val CALLBACK_MINUTES: Int = 15`
  - `val CALLBACK_DURATIONS: List<Int> = listOf(15, 30)`
  - `fun defaultMinutes(kind: AppointmentKind): Int`
  - `fun durations(kind: AppointmentKind): List<Int>`
  - `fun minutesBetween(startIso: String?, endIso: String?, fallback: Int = DEFAULT_MINUTES): Int`
  - `fun eventTitle(kind: AppointmentKind, businessName: String, note: String?, done: Boolean = false): String`
  - `fun statusAfterSave(kind: AppointmentKind, startsAt: String, endsAt: String?, nowMillis: Long): Status?`
  - `fun statusAfterRemoval(status: Status, remaining: List<AppointmentEntry>, nowMillis: Long): Status?` — same signature, counts visits only
  - `fun isOverdue(entry: AppointmentEntry, nowMillis: Long): Boolean`
  - `fun splitCallbacks(entries: List<AppointmentEntry>): Pair<List<AppointmentEntry>, List<AppointmentEntry>>` — (open earliest first, completed latest completion first), callbacks only

- [ ] **Step 1: Write the failing tests**

In `test/…/AppointmentTest.kt` add `import io.github.amadeusb.callsheet.data.AppointmentKind`.

Replace the test `the event title carries the note when there is one` with:

```kotlin
    @Test
    fun `the event title carries the note when there is one`() {
        assertEquals("Ortstermin Elektro Meier – Angebot", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", "Angebot"))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", " "))
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", null))
    }

    @Test
    fun `a callback's title says so, and a completed one carries a tick`() {
        assertEquals(
            "Rückruf Elektro Meier – wegen Angebot nachfragen",
            Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", "wegen Angebot nachfragen"),
        )
        assertEquals("Rückruf Elektro Meier", Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", null))
        assertEquals(
            "✓ Rückruf Elektro Meier",
            Appointment.eventTitle(AppointmentKind.CALLBACK, "Elektro Meier", null, done = true),
        )
    }

    @Test
    fun `a visit never carries a tick`() {
        assertEquals("Ortstermin Elektro Meier", Appointment.eventTitle(AppointmentKind.VISIT, "Elektro Meier", null, done = true))
    }
```

Below `minutesBetween falls back to the default without an end` add:

```kotlin
    @Test
    fun `a callback falls back to its own length`() {
        assertEquals(15, Appointment.minutesBetween("2026-09-10T14:00:00+02:00", null, Appointment.defaultMinutes(AppointmentKind.CALLBACK)))
        assertEquals(Appointment.DEFAULT_MINUTES, Appointment.defaultMinutes(AppointmentKind.VISIT))
    }

    @Test
    fun `a callback is offered short durations`() {
        assertEquals(listOf(15, 30), Appointment.durations(AppointmentKind.CALLBACK))
        assertEquals(Appointment.DURATIONS, Appointment.durations(AppointmentKind.VISIT))
    }
```

Replace the two status-after-save tests with:

```kotlin
    @Test
    fun `saving an appointment still ahead sets the status`() {
        assertEquals(
            Status.APPOINTMENT,
            Appointment.statusAfterSave(AppointmentKind.VISIT, "2026-09-11T09:00:00+02:00", "2026-09-11T10:00:00+02:00", noon),
        )
    }

    @Test
    fun `entering a past appointment after the fact leaves the status alone`() {
        assertNull(Appointment.statusAfterSave(AppointmentKind.VISIT, "2026-09-01T09:00:00+02:00", "2026-09-01T10:00:00+02:00", noon))
    }

    @Test
    fun `saving a callback never sets the status`() {
        assertNull(Appointment.statusAfterSave(AppointmentKind.CALLBACK, "2026-09-11T09:00:00+02:00", "2026-09-11T09:15:00+02:00", noon))
    }
```

Below `removing never touches a status other than appointment` add:

```kotlin
    @Test
    fun `a callback still ahead does not keep the status at appointment`() {
        val onlyACallback = listOf(
            entry("R-1", "2026-09-12T09:00:00+02:00", "2026-09-12T09:15:00+02:00").copy(kind = AppointmentKind.CALLBACK),
        )

        assertEquals(Status.CALLED, Appointment.statusAfterRemoval(Status.APPOINTMENT, onlyACallback, noon))
    }

    // --- callbacks: overdue, open and completed ---------------------------------

    private fun callback(id: String, startsAt: String, doneAt: String? = null) =
        entry(id, startsAt).copy(kind = AppointmentKind.CALLBACK, doneAt = doneAt)

    @Test
    fun `an open callback whose start has passed is overdue`() {
        assertTrue(Appointment.isOverdue(callback("R-1", "2026-09-10T11:00:00+02:00"), noon))
        assertTrue(Appointment.isOverdue(callback("R-2", "2026-09-01T09:00:00+02:00"), noon))
    }

    @Test
    fun `a callback ahead, a completed one and a visit are never overdue`() {
        assertFalse(Appointment.isOverdue(callback("R-1", "2026-09-10T13:00:00+02:00"), noon))
        assertFalse(Appointment.isOverdue(callback("R-2", "2026-09-10T11:00:00+02:00", doneAt = "2026-09-10T11:05:00+02:00"), noon))
        assertFalse(Appointment.isOverdue(entry("A-1", "2026-09-01T09:00:00+02:00"), noon))
    }

    @Test
    fun `callbacks split into open earliest first and completed latest first, visits left out`() {
        val all = listOf(
            callback("open-late", "2026-09-20T09:00:00+02:00"),
            callback("done-early", "2026-09-01T09:00:00+02:00", doneAt = "2026-09-01T09:10:00+02:00"),
            entry("visit", "2026-09-11T09:00:00+02:00"),
            callback("open-early", "2026-09-05T09:00:00+02:00"),
            callback("done-late", "2026-09-08T09:00:00+02:00", doneAt = "2026-09-08T09:10:00+02:00"),
        )

        val (open, done) = Appointment.splitCallbacks(all)

        assertEquals(listOf("open-early", "open-late"), open.map { it.id })
        assertEquals(listOf("done-late", "done-early"), done.map { it.id })
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: compilation FAILS — `eventTitle` and `statusAfterSave` take other arguments, `defaultMinutes`, `durations`, `isOverdue`, `splitCallbacks` do not exist.

- [ ] **Step 3: Implement**

In `…/calling/Appointment.kt` add `import io.github.amadeusb.callsheet.data.AppointmentKind`.

Below `val DURATIONS: List<Int> = listOf(30, 60, 90, 120)` add:

```kotlin
    /** The length a fresh callback starts at. A phone call, not a visit. */
    const val CALLBACK_MINUTES: Int = 15

    /** The durations offered as chips for a callback. */
    val CALLBACK_DURATIONS: List<Int> = listOf(15, 30)

    /** The length an appointment of [kind] falls back to where none is known. */
    fun defaultMinutes(kind: AppointmentKind): Int = when (kind) {
        AppointmentKind.VISIT -> DEFAULT_MINUTES
        AppointmentKind.CALLBACK -> CALLBACK_MINUTES
    }

    /** The chips the sheet offers for [kind]. */
    fun durations(kind: AppointmentKind): List<Int> = when (kind) {
        AppointmentKind.VISIT -> DURATIONS
        AppointmentKind.CALLBACK -> CALLBACK_DURATIONS
    }
```

Replace `minutesBetween` with:

```kotlin
    /**
     * How long an appointment runs. Falls back to [fallback] when either end is
     * missing or unreadable, so the picker always has a length to show — a
     * callback's own, where the caller passes it.
     */
    fun minutesBetween(startIso: String?, endIso: String?, fallback: Int = DEFAULT_MINUTES): Int {
        val start = Clock.millis(startIso) ?: return fallback
        val end = Clock.millis(endIso) ?: return fallback
        val minutes = ((end - start) / 60_000L).toInt()
        return if (minutes > 0) minutes else fallback
    }
```

Replace `statusAfterSave` and `statusAfterRemoval` with:

```kotlin
    /**
     * The status to set after saving, or null to leave it. A visit still ahead
     * means one was agreed; a past one entered after the fact says nothing
     * about where the business stands now. A callback is the app's own
     * reminder to ring again and never says anything about the status.
     */
    fun statusAfterSave(kind: AppointmentKind, startsAt: String, endsAt: String?, nowMillis: Long): Status? =
        if (kind == AppointmentKind.VISIT && isAhead(startsAt, endsAt, nowMillis)) Status.APPOINTMENT else null

    /**
     * The status after a visit went away — removed, or deleted in the calendar —
     * or null to leave it. [remaining] are the business's appointments without
     * that one; only visits among them count.
     *
     * Only the status a visit set is taken back, and only once none is left
     * ahead. `declined` and `do_not_call` are decisions made on the phone; a
     * removed appointment is not permission to undo them. Callers only ask when
     * the one that went away was a visit.
     */
    fun statusAfterRemoval(status: Status, remaining: List<AppointmentEntry>, nowMillis: Long): Status? {
        if (status != Status.APPOINTMENT) return null
        val visitAhead = remaining.any { it.kind == AppointmentKind.VISIT && isAhead(it.startsAt, it.endsAt, nowMillis) }
        return if (visitAhead) null else Status.CALLED
    }

    /**
     * An open callback whose start has passed. It stays open work until a call
     * completes it. A visit is never overdue: once its time is past it
     * happened, or it did not.
     */
    fun isOverdue(entry: AppointmentEntry, nowMillis: Long): Boolean {
        if (entry.kind != AppointmentKind.CALLBACK || entry.doneAt != null) return false
        val start = Clock.millis(entry.startsAt) ?: return false
        return start < nowMillis
    }

    /**
     * A business's callbacks as the detail view lists them: open ones earliest
     * first, completed ones latest completion first. Visits are left out.
     */
    fun splitCallbacks(entries: List<AppointmentEntry>): Pair<List<AppointmentEntry>, List<AppointmentEntry>> {
        val (done, open) = entries
            .filter { it.kind == AppointmentKind.CALLBACK }
            .partition { it.doneAt != null }
        return open.sortedBy { Clock.millis(it.startsAt) ?: Long.MAX_VALUE } to
            done.sortedByDescending { Clock.millis(it.doneAt) ?: 0L }
    }
```

Replace `eventTitle` with:

```kotlin
    /**
     * „Ortstermin Elektro Meier – Angebot", „Rückruf Elektro Meier – wegen
     * Angebot nachfragen", and „✓ Rückruf Elektro Meier" once a callback is
     * completed. Without a note the part from the dash on is left out.
     *
     * The tick is the only way a calendar can show a completed callback: an
     * event (VEVENT) has no completed state, only a task (VTODO) has.
     */
    fun eventTitle(kind: AppointmentKind, businessName: String, note: String?, done: Boolean = false): String {
        val head = when (kind) {
            AppointmentKind.VISIT -> "Ortstermin $businessName"
            AppointmentKind.CALLBACK -> "${if (done) "✓ " else ""}Rückruf $businessName"
        }.trim()
        val tail = note?.trim()?.ifEmpty { null } ?: return head
        return "$head – $tail"
    }
```

- [ ] **Step 4: Adjust the call sites in the view model**

In `…/CallsheetViewModel.kt`, function `eventFieldsFor`, replace

```kotlin
        val end = Clock.millis(entry.endsAt) ?: (start + Appointment.DEFAULT_MINUTES * 60_000L)
```

with

```kotlin
        val end = Clock.millis(entry.endsAt) ?: (start + Appointment.defaultMinutes(entry.kind) * 60_000L)
```

and

```kotlin
            title = Appointment.eventTitle(business.name, entry.note),
```

with

```kotlin
            title = Appointment.eventTitle(entry.kind, business.name, entry.note, done = entry.doneAt != null),
```

In `saveAppointment`, replace

```kotlin
            Appointment.statusAfterSave(entry.startsAt, entry.endsAt, System.currentTimeMillis())
```

with

```kotlin
            Appointment.statusAfterSave(entry.kind, entry.startsAt, entry.endsAt, System.currentTimeMillis())
```

- [ ] **Step 5: Run everything**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Regeln für Rückrufe: Länge, Titel mit Haken, kein Status, überfällig"
```

---
### Task 5: App — the agenda's grouping

**Repository:** app.

**Files:**
- Create: `…/calling/Agenda.kt`
- Modify: `…/data/Clock.kt`
- Test: `test/…/AgendaTest.kt` (new, plain JUnit, no Robolectric)

**Interfaces:**
- Consumes: `AppointmentKind`, `AppointmentEntry` (Task 3), `Appointment.readableRange` (existing).
- Produces:
  - `fun Clock.nextDayStart(millis: Long): Long` — start of the day after the one containing `millis`
  - `enum class AgendaGroup { OVERDUE, TODAY, DAY }`
  - `data class AgendaSection<T>(val group: AgendaGroup, val dayStartMillis: Long?, val items: List<T>)`
  - `object Agenda` with `fun <T> sections(items: List<T>, entryOf: (T) -> AppointmentEntry, nowMillis: Long): List<AgendaSection<T>>`, `fun title(section: AgendaSection<*>): String`, `fun rowLabel(entry: AppointmentEntry, withDate: Boolean): String`

- [ ] **Step 1: Write the failing tests**

Create `test/…/AgendaTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.calling.Agenda
import io.github.amadeusb.callsheet.calling.AgendaGroup
import io.github.amadeusb.callsheet.calling.AgendaSection
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {

    private fun instant(iso: String): Long = Clock.millis(iso)!!

    /** Thursday, 10 September 2026, noon. */
    private val noon = instant("2026-09-10T12:00:00+02:00")

    private fun visit(id: String, startsAt: String, endsAt: String? = null, note: String? = null) = AppointmentEntry(
        id = id, placeId = "P1", startsAt = startsAt, endsAt = endsAt,
        location = null, note = note, contactId = null,
    )

    private fun callback(id: String, startsAt: String, endsAt: String? = null, doneAt: String? = null, note: String? = null) =
        visit(id, startsAt, endsAt, note).copy(kind = AppointmentKind.CALLBACK, doneAt = doneAt)

    private fun sections(vararg entries: AppointmentEntry): List<AgendaSection<AppointmentEntry>> =
        Agenda.sections(entries.toList(), { it }, noon)

    @Test
    fun `nothing listed, no sections`() {
        assertTrue(sections().isEmpty())
    }

    @Test
    fun `open callbacks from earlier days and from earlier today are overdue, earliest first`() {
        val result = sections(
            callback("this-morning", "2026-09-10T09:00:00+02:00"),
            callback("last-week", "2026-09-03T09:00:00+02:00"),
        )

        assertEquals(AgendaGroup.OVERDUE, result.first().group)
        assertEquals(listOf("last-week", "this-morning"), result.first().items.map { it.id })
    }

    @Test
    fun `today holds today's visits, past ones included, and callbacks still ahead today`() {
        val result = sections(
            callback("this-afternoon", "2026-09-10T15:00:00+02:00"),
            visit("visit-this-morning", "2026-09-10T08:00:00+02:00", "2026-09-10T09:00:00+02:00"),
            visit("visit-tonight", "2026-09-10T18:00:00+02:00"),
        )

        val today = result.single()
        assertEquals(AgendaGroup.TODAY, today.group)
        assertEquals(instant("2026-09-10T00:00:00+02:00"), today.dayStartMillis)
        assertEquals(listOf("visit-this-morning", "this-afternoon", "visit-tonight"), today.items.map { it.id })
    }

    @Test
    fun `every later day with something on it gets a section of its own, in order`() {
        val result = sections(
            visit("tuesday", "2026-09-15T10:00:00+02:00"),
            callback("friday-late", "2026-09-11T16:00:00+02:00"),
            callback("friday-early", "2026-09-11T09:00:00+02:00"),
        )

        assertEquals(listOf(AgendaGroup.DAY, AgendaGroup.DAY), result.map { it.group })
        assertEquals(instant("2026-09-11T00:00:00+02:00"), result[0].dayStartMillis)
        assertEquals(listOf("friday-early", "friday-late"), result[0].items.map { it.id })
        assertEquals(instant("2026-09-15T00:00:00+02:00"), result[1].dayStartMillis)
    }

    @Test
    fun `completed callbacks and visits from earlier days are not listed`() {
        val result = sections(
            callback("done", "2026-09-10T09:00:00+02:00", doneAt = "2026-09-10T09:05:00+02:00"),
            callback("done-tomorrow", "2026-09-11T09:00:00+02:00", doneAt = "2026-09-10T11:00:00+02:00"),
            visit("yesterday", "2026-09-09T10:00:00+02:00"),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sections come in the order overdue, today, later days`() {
        val result = sections(
            visit("tomorrow", "2026-09-11T10:00:00+02:00"),
            visit("today", "2026-09-10T14:00:00+02:00"),
            callback("overdue", "2026-09-09T10:00:00+02:00"),
        )

        assertEquals(listOf(AgendaGroup.OVERDUE, AgendaGroup.TODAY, AgendaGroup.DAY), result.map { it.group })
    }

    @Test
    fun `titles count, and a later day is named with weekday and date`() {
        val result = sections(
            callback("o1", "2026-09-09T10:00:00+02:00"),
            callback("o2", "2026-09-08T10:00:00+02:00"),
            visit("t1", "2026-09-10T14:00:00+02:00"),
            visit("d1", "2026-09-15T10:00:00+02:00"),
        )

        assertEquals(listOf("Überfällig (2)", "Heute (1)", "Dienstag, 15.09."), result.map { Agenda.title(it) })
    }

    @Test
    fun `a row names the kind, the time and the note`() {
        val entry = callback("R-1", "2026-09-10T09:00:00+02:00", "2026-09-10T09:15:00+02:00", note = "wegen Angebot")

        assertEquals("Rückruf · 09:00 – 09:15 · wegen Angebot", Agenda.rowLabel(entry, withDate = false))
        assertEquals("Rückruf · Do., 10.09. · 09:00 – 09:15 · wegen Angebot", Agenda.rowLabel(entry, withDate = true))
        assertEquals("Vor Ort · 14:00", Agenda.rowLabel(visit("A-1", "2026-09-10T14:00:00+02:00"), withDate = false))
    }

    @Test
    fun `the next day starts at midnight, across a change of clocks too`() {
        assertEquals(instant("2026-09-11T00:00:00+02:00"), Clock.nextDayStart(noon))
        // 25 October 2026: the clocks go back, that day has 25 hours.
        assertEquals(instant("2026-10-26T00:00:00+01:00"), Clock.nextDayStart(instant("2026-10-25T23:30:00+01:00")))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AgendaTest"`
Expected: compilation FAILS — `Agenda` and `Clock.nextDayStart` do not exist.

- [ ] **Step 3: `Clock.nextDayStart`**

In `…/data/Clock.kt`, below `todayStart`, add:

```kotlin
    /**
     * Start of the day after the one containing [millis]. Through the calendar
     * date, not by adding 24 hours: the day the clocks change has 23 or 25.
     */
    fun nextDayStart(millis: Long): Long =
        zdt(millis).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
```

- [ ] **Step 4: `Agenda`**

Create `…/calling/Agenda.kt`:

```kotlin
package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where an entry sits in the agenda. */
enum class AgendaGroup {
    /** Open callbacks whose start has passed — from earlier days or from today. */
    OVERDUE,

    /** Today's visits, past ones included, and today's callbacks still ahead. */
    TODAY,

    /** One later day. */
    DAY,
}

/** One heading in the agenda and what stands under it. [dayStartMillis] is null for [AgendaGroup.OVERDUE]. */
data class AgendaSection<T>(val group: AgendaGroup, val dayStartMillis: Long?, val items: List<T>)

/**
 * The agenda behind the calendar button: what is overdue, what is on today,
 * and every later day that has something on it.
 *
 * Generic over what the screen lists — an entry with its business — so the
 * grouping is tested on bare entries, without a business in sight.
 */
object Agenda {

    private val dayTitle: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Groups [items] for the agenda, by start, kinds mixed.
     *
     * Left out: completed callbacks, and visits from before today — a visit a
     * fortnight ago happened or it did not; either way it is not on the agenda.
     * An open callback is never left out, however old: it is work not done.
     * Items whose start cannot be read are left out as well.
     */
    fun <T> sections(items: List<T>, entryOf: (T) -> AppointmentEntry, nowMillis: Long): List<AgendaSection<T>> {
        val todayStart = Clock.todayStart(nowMillis)
        val tomorrowStart = Clock.nextDayStart(nowMillis)
        val overdue = ArrayList<T>()
        val today = ArrayList<T>()
        val later = LinkedHashMap<Long, MutableList<T>>()

        items
            .mapNotNull { item -> Clock.millis(entryOf(item).startsAt)?.let { it to item } }
            .sortedBy { it.first }
            .forEach { (start, item) ->
                val entry = entryOf(item)
                val callback = entry.kind == AppointmentKind.CALLBACK
                when {
                    callback && entry.doneAt != null -> Unit
                    callback && start < nowMillis -> overdue.add(item)
                    !callback && start < todayStart -> Unit
                    start < tomorrowStart -> today.add(item)
                    else -> later.getOrPut(Clock.todayStart(start)) { ArrayList() }.add(item)
                }
            }

        return buildList {
            if (overdue.isNotEmpty()) add(AgendaSection(AgendaGroup.OVERDUE, null, overdue))
            if (today.isNotEmpty()) add(AgendaSection(AgendaGroup.TODAY, todayStart, today))
            later.forEach { (day, list) -> add(AgendaSection(AgendaGroup.DAY, day, list)) }
        }
    }

    /** „Überfällig (2)", „Heute (3)", „Dienstag, 15.09.". */
    fun title(section: AgendaSection<*>): String = when (section.group) {
        AgendaGroup.OVERDUE -> "Überfällig (${section.items.size})"
        AgendaGroup.TODAY -> "Heute (${section.items.size})"
        AgendaGroup.DAY -> Clock.zdt(section.dayStartMillis ?: 0L).format(dayTitle)
    }

    /**
     * „Rückruf · 09:00 – 09:15 · wegen Angebot". [withDate] adds the date —
     * for the overdue ones, which come from any day.
     */
    fun rowLabel(entry: AppointmentEntry, withDate: Boolean): String {
        val slot = if (withDate) Appointment.readableRange(entry.startsAt, entry.endsAt) else timeRange(entry.startsAt, entry.endsAt)
        val note = entry.note?.trim()?.ifEmpty { null }
        return listOfNotNull(entry.kind.label, slot, note).joinToString(" · ")
    }

    private fun timeRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val head = Clock.zdt(start).format(time)
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AgendaTest"`
Expected: PASS. Like `AppointmentTest`, these assume the build machine's zone is Europe/Berlin (`Clock.zone` is the system default).

- [ ] **Step 6: Run everything**

Run: `./gradlew testDebugUnitTest`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Agenda.kt app/src/main/java/io/github/amadeusb/callsheet/data/Clock.kt app/src/test/java/io/github/amadeusb/callsheet/AgendaTest.kt
git commit -m "Agenda: überfällige Rückrufe, heute, jeder weitere Tag"
```

---
### Task 6: App — completing callbacks and reading the agenda

**Repository:** app.

**Files:**
- Modify: `…/data/Repository.kt`
- Test: `test/…/RepositoryTest.kt`

**Interfaces:**
- Consumes: Task 3's columns and `callback(...)` test helper.
- Produces:
  - `suspend fun Repository.completeCallbacks(placeId: String, untilMillis: Long, doneAt: String): List<String>` — ids completed
  - `suspend fun Repository.agenda(todayStartMillis: Long): List<Pair<AppointmentEntry, Business>>` — open callbacks of any day and visits from `todayStartMillis` on, blocked businesses left out, earliest first
  - private `withBusinesses(db, appointments)` shared by `agenda` and `appointmentsDue`

- [ ] **Step 1: Write the failing tests**

In `test/…/RepositoryTest.kt`, below `a due appointment's business knows its contacts' numbers`, add:

```kotlin
    // ------------------------------------------------------------- Callbacks

    @Test
    fun `completeCallbacks completes the business's open callbacks due before the limit`() = runTest {
        repo.saveAppointment(callback("R-1", "t-1", "2026-09-09T09:00:00+02:00"))
        repo.saveAppointment(callback("R-2", "t-1", "2026-09-10T16:00:00+02:00"))
        repo.saveAppointment(callback("R-3", "t-1", "2026-09-11T09:00:00+02:00"))
        repo.saveAppointment(callback("R-4", "t-1", "2026-09-08T09:00:00+02:00", doneAt = "2026-09-08T09:10:00+02:00"))
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-10T10:00:00+02:00"))
        repo.saveAppointment(callback("R-9", "t-2", "2026-09-10T09:00:00+02:00"))
        execute("UPDATE appointments SET dirty = 0")

        val completed = repo.completeCallbacks(
            placeId = "t-1",
            untilMillis = Clock.millis("2026-09-11T00:00:00+02:00")!!,
            doneAt = "2026-09-10T12:00:00+02:00",
        )

        assertEquals(setOf("R-1", "R-2"), completed.toSet())
        assertEquals("2026-09-10T12:00:00+02:00", repo.appointment("R-1")!!.doneAt)
        assertEquals("2026-09-10T12:00:00+02:00", repo.appointment("R-2")!!.doneAt)
        assertNull(repo.appointment("R-3")!!.doneAt)
        // Completed before stays completed when it was.
        assertEquals("2026-09-08T09:10:00+02:00", repo.appointment("R-4")!!.doneAt)
        assertNull(repo.appointment("A-1")!!.doneAt)
        assertNull(repo.appointment("R-9")!!.doneAt)
        // Completing travels.
        assertEquals(2, count("SELECT COUNT(*) FROM appointments WHERE dirty = 1"))
    }

    @Test
    fun `completeCallbacks with nothing due changes nothing`() = runTest {
        repo.saveAppointment(callback("R-3", "t-1", "2026-09-11T09:00:00+02:00"))
        execute("UPDATE appointments SET dirty = 0")

        val completed = repo.completeCallbacks("t-1", Clock.millis("2026-09-11T00:00:00+02:00")!!, "2026-09-10T12:00:00+02:00")

        assertTrue(completed.isEmpty())
        assertEquals(0, count("SELECT COUNT(*) FROM appointments WHERE dirty = 1"))
    }

    @Test
    fun `agenda lists open callbacks of any day and visits from today on, earliest first`() = runTest {
        import(
            """[
              {"placeId":"t-4","title":"Elektro Meier","phone":"+49 841 111"},
              {"placeId":"t-5","title":"Gartenbau Merten","phone":"+49 841 222"}
            ]"""
        )
        repo.saveAppointment(callback("R-old", "t-4", "2026-09-01T09:00:00+02:00"))
        repo.saveAppointment(callback("R-done", "t-4", "2026-09-09T09:00:00+02:00", doneAt = "2026-09-09T09:05:00+02:00"))
        repo.saveAppointment(visit("A-yesterday", "t-5", "2026-09-09T10:00:00+02:00"))
        repo.saveAppointment(visit("A-today", "t-5", "2026-09-10T08:00:00+02:00"))
        repo.saveAppointment(visit("A-next-week", "t-4", "2026-09-17T10:00:00+02:00"))

        val listed = repo.agenda(Clock.millis("2026-09-10T00:00:00+02:00")!!)

        assertEquals(listOf("R-old", "A-today", "A-next-week"), listed.map { it.first.id })
        assertEquals(listOf("t-4", "t-5", "t-4"), listed.map { it.second.placeId })
    }

    @Test
    fun `a blocked business never appears in the agenda`() = runTest {
        import("""[{"placeId":"t-7","title":"Gesperrt","phone":"+49 841 111"}]""")
        repo.saveAppointment(callback("R-7", "t-7", "2026-09-01T09:00:00+02:00"))
        repo.saveAppointment(visit("A-7", "t-7", "2026-09-17T10:00:00+02:00"))
        repo.setStatus("t-7", Status.DO_NOT_CALL)

        assertTrue(repo.agenda(Clock.millis("2026-09-10T00:00:00+02:00")!!).isEmpty())
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: compilation FAILS — `completeCallbacks` and `agenda` do not exist.

- [ ] **Step 3: Implement**

In `…/data/Repository.kt`, replace the body of `appointmentsDue` from `val businesses = HashMap<String, Business?>()` to the end of the function with a call to a shared helper, and add `agenda` and the helper directly below it:

```kotlin
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
            withBusinesses(db, due)
        }

    /**
     * What the agenda lists, each with its business, earliest first: every open
     * callback, however old — it is work not done — and the visits from
     * [todayStartMillis] on. Completed callbacks and earlier visits are left
     * out, and so is everything at a blocked business. Grouping is
     * Agenda.sections' job.
     */
    suspend fun agenda(todayStartMillis: Long): List<Pair<AppointmentEntry, Business>> =
        withContext(Dispatchers.IO) {
            val db = helper.readableDatabase
            val listed = db.rawQuery(
                "SELECT a.* FROM appointments a JOIN businesses b ON b.place_id = a.place_id WHERE b.status <> ?",
                arrayOf(Status.DO_NOT_CALL.key),
            ).use { c -> allAppointments(c) }
                .mapNotNull { a -> Clock.millis(a.startsAt)?.let { it to a } }
                .filter { (start, a) ->
                    if (a.kind == AppointmentKind.CALLBACK) a.doneAt == null else start >= todayStartMillis
                }
                .sortedBy { it.first }
                .map { it.second }
            withBusinesses(db, listed)
        }

    /** Each appointment with its business, read once per business. The list query's numbers included. */
    private fun withBusinesses(
        db: android.database.sqlite.SQLiteDatabase,
        appointments: List<AppointmentEntry>,
    ): List<Pair<AppointmentEntry, Business>> {
        val businesses = HashMap<String, Business?>()
        return appointments.mapNotNull { appointment ->
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

Below `deleteAppointment`, add:

```kotlin
    /**
     * Completes the open callbacks of [placeId] that start before [untilMillis]:
     * `done_at` = [doneAt], stamped and marked for upload, in one transaction.
     * Returns the ids completed — the caller marks their calendar events.
     */
    suspend fun completeCallbacks(placeId: String, untilMillis: Long, doneAt: String): List<String> =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            val due = db.rawQuery(
                "SELECT * FROM appointments WHERE place_id = ? AND kind = ? AND done_at IS NULL",
                arrayOf(placeId, AppointmentKind.CALLBACK.key),
            ).use { c -> allAppointments(c) }
                .filter { (Clock.millis(it.startsAt) ?: Long.MAX_VALUE) < untilMillis }
                .map { it.id }
            if (due.isEmpty()) return@withContext emptyList()

            val now = Clock.now()
            db.beginTransaction()
            try {
                for (id in due) {
                    val values = ContentValues().apply {
                        put("done_at", doneAt)
                        put("updated_at", now)
                        put("dirty", 1)
                    }
                    db.update("appointments", values, "id = ?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            due
        }
```

- [ ] **Step 4: Run everything**

Run: `./gradlew testDebugUnitTest`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Rückrufe erledigen, Agenda aus der Datenbank lesen"
```

---
### Task 7: App — the sheet, saving and removing by kind

**Repository:** app.

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`AppointmentDraft`, `openAppointment`, new `openCallback`, `saveAppointment`, `removeAppointment`, `reconcileAppointments`)
- Modify: `…/ui/AppointmentSheet.kt`

**Interfaces:**
- Consumes: `Appointment.defaultMinutes`, `durations`, `statusAfterRemoval` (Task 4).
- Produces:
  - `AppointmentDraft.kind: AppointmentKind = AppointmentKind.VISIT`
  - `fun CallsheetViewModel.openCallback(placeId: String, startIso: String)` — opens the sheet for a new callback
  - `openAppointment(placeId, appointmentId)` unchanged in signature; for an existing id the kind comes from the row

No unit tests: this is view model and Compose code. The rules it calls are tested in Tasks 4–6. Verification is the build and the full suite.

- [ ] **Step 1: The draft**

In `…/CallsheetViewModel.kt` add `import io.github.amadeusb.callsheet.data.AppointmentKind`. In `data class AppointmentDraft`, below `val location: String,`, add:

```kotlin
    /** Visit or callback. Chosen by where the sheet was opened; an existing appointment keeps its own. */
    val kind: AppointmentKind = AppointmentKind.VISIT,
```

- [ ] **Step 2: Opening the sheet**

Replace the whole function `openAppointment` (KDoc included) with:

```kotlin
    /**
     * Opens the sheet for a visit. Without [appointmentId] a new visit starts as
     * before: in two days, snapped to the quarter hour, the last duration used,
     * the business's address. With one, everything comes from that appointment
     * — its kind too, so „Ändern" on a callback opens a callback.
     */
    fun openAppointment(placeId: String, appointmentId: String? = null) =
        openSheet(placeId, appointmentId, AppointmentKind.VISIT, startIso = null)

    /**
     * Opens the sheet for a new callback at [startIso] — from a quick choice,
     * the date picker or the suggestion after a call. Nothing is saved until
     * „Rückruf speichern".
     */
    fun openCallback(placeId: String, startIso: String) =
        openSheet(placeId, null, AppointmentKind.CALLBACK, startIso)

    private fun openSheet(placeId: String, appointmentId: String?, kind: AppointmentKind, startIso: String?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val business = repo.business(placeId) ?: return@launch
            val existing = appointmentId?.let { repo.appointment(it) }
            val sheetKind = existing?.kind ?: kind
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            val start = existing?.startsAt ?: startIso ?: Appointment.snapToQuarter(FollowUp.inTwoDays())
            val minutes = when {
                existing != null -> Appointment.minutesBetween(existing.startsAt, existing.endsAt, Appointment.defaultMinutes(sheetKind))
                sheetKind == AppointmentKind.CALLBACK -> Appointment.CALLBACK_MINUTES
                else -> preferences.appointmentMinutes
            }
            val location = when {
                existing != null -> existing.location.orEmpty()
                // A phone call has no place.
                sheetKind == AppointmentKind.CALLBACK -> ""
                else -> Appointment.address(business.street, business.postalCode, business.city).orEmpty()
            }
            val readable = CalendarStore.canRead(context)
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        kind = sheetKind,
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

- [ ] **Step 3: Saving**

In `saveAppointment`:

(a) Directly after the block `if (draft.appointmentId != null && existing == null) { … return@launch }`, add:

```kotlin
            val kind = existing?.kind ?: draft.kind
            val saved = if (kind == AppointmentKind.CALLBACK) "Rückruf gespeichert" else "Termin gespeichert"
```

(b) Replace the construction of `var entry = AppointmentEntry(…)` with:

```kotlin
            var entry = AppointmentEntry(
                id = existing?.id ?: UUID.randomUUID().toString(),
                placeId = draft.placeId,
                startsAt = draft.startIso,
                endsAt = endIso,
                location = if (kind == AppointmentKind.CALLBACK) null else draft.location.trim().ifEmpty { null },
                note = draft.note.trim().ifEmpty { null },
                contactId = draft.contactId,
                eventUid = existing?.eventUid,
                kind = kind,
                // Saving never completes or reopens; the repository never clears it either.
                doneAt = existing?.doneAt,
            )
```

(c) In the same function, every string literal that begins with `"Termin gespeichert` — six of them: „Ohne Zugriff…", „Der Kalender ließ sich gerade nicht lesen…", „, aber nicht verknüpft…", „Der Kalendereintrag war gelöscht…", „Der Kalendereintrag ließ sich nicht ändern…", „Der Kalendereintrag konnte nicht…" — begins with `"$saved` instead. Example:

```kotlin
                !readable || !writable -> "$saved. Ohne Zugriff auf den Kalender bleibt der Eintrag " +
```

and

```kotlin
                        calendarHint = "$saved, aber nicht verknüpft: " +
```

Check: `grep -n '"Termin gespeichert' app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt` prints nothing.

(d) Replace `preferences.appointmentMinutes = draft.minutes` with:

```kotlin
            // The length a visit starts at follows the last visit; a callback's is fixed.
            if (kind == AppointmentKind.VISIT) preferences.appointmentMinutes = draft.minutes
```

- [ ] **Step 4: Removing**

Replace the function `removeAppointment` with:

```kotlin
    /**
     * Removes an appointment and its calendar event — a past visit or a
     * completed callback too; the detail view asks first. Where the event is not
     * on this device, the row goes alone and the device holding the event
     * deletes it after its next sync. Only a visit can take the status back.
     */
    fun removeAppointment(appointmentId: String) {
        viewModelScope.launch {
            val entry = repo.appointment(appointmentId) ?: return@launch
            val business = repo.business(entry.placeId) ?: return@launch
            val lookup = lookUpEvent(entry)
            val deleted = lookup?.getOrNull()?.let { CalendarStore.delete(getApplication(), it.eventId) }
            repo.deleteAppointment(entry.id)
            if (entry.kind == AppointmentKind.VISIT) {
                Appointment.statusAfterRemoval(business.status, repo.appointments(entry.placeId), System.currentTimeMillis())
                    ?.let { repo.setStatus(entry.placeId, it) }
            }
            val removed = if (entry.kind == AppointmentKind.CALLBACK) "Rückruf entfernt" else "Termin entfernt"
            val linked = entry.eventUid != null || entry.calendarEventId != null
            val hint = when {
                !preferences.calendarEnabled || !linked -> null
                // Spec: without read permission nothing is asked — but with the
                // calendar switched on, the missing permission is said.
                lookup == null -> "$removed. Ohne Zugriff auf den Kalender bleibt der Eintrag dort " +
                    "stehen — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                lookup.isFailure -> "$removed. Der Kalender ließ sich nicht lesen — den Eintrag dort bitte selbst löschen."
                deleted == false -> "$removed. Der Kalendereintrag ließ sich nicht löschen — bitte dort selbst löschen."
                else -> null
            }
            hint?.let { text -> _state.update { it.copy(hint = text) } }
            loadDetail(entry.placeId)
            syncNow()
        }
    }
```

- [ ] **Step 5: The read-back's hint**

In `reconcileAppointments`, replace from `// Read again after the sync: it may have brought newer rows.` down to the closing brace of `if (Reconcile.DeletedInCalendar in outcomes) { … }` with:

```kotlin
                // Read again after the sync: it may have brought newer rows.
                val results = repo.appointments(placeId)
                    .filter { it.eventUid != null || it.calendarEventId != null }
                    .mapNotNull { entry -> reconcile(entry, business, now, rowWinsOnly = false)?.let { entry to it } }
                if (results.isEmpty()) return@launch

                // The sync may have taken long: the user may have moved on. The
                // status is data and falls back regardless; hint and reload only
                // for the business still on screen.
                val stillShown = { (_state.value.screen as? Screen.Detail)?.placeId == placeId }
                val deleted = results.filter { it.second == Reconcile.DeletedInCalendar }.map { it.first }
                if (deleted.isNotEmpty()) {
                    // Only a deleted visit can take the status back.
                    val fallback = if (deleted.any { it.kind == AppointmentKind.VISIT }) {
                        Appointment.statusAfterRemoval(business.status, repo.appointments(placeId), now)
                    } else {
                        null
                    }
                    fallback?.let { repo.setStatus(placeId, it) }
                    val what = if (deleted.all { it.kind == AppointmentKind.CALLBACK }) "Der Rückruf" else "Der Termin"
                    if (stillShown()) {
                        _state.update {
                            it.copy(
                                hint = if (fallback != null) {
                                    "$what wurde im Kalender gelöscht. Status zurück auf „Angerufen“."
                                } else {
                                    "$what wurde im Kalender gelöscht. Der Status bleibt, wie er ist."
                                }
                            )
                        }
                    }
                }
```

The following line `if (stillShown()) loadDetail(placeId)` stays.

- [ ] **Step 6: The sheet**

In `…/ui/AppointmentSheet.kt` add `import io.github.amadeusb.callsheet.data.AppointmentKind`.

In the KDoc of `AppointmentSheet`, change the first sentence to „Setting an appointment on site or a callback." and add at the end of the KDoc: „A callback has no place: the location field is left out, and the durations are a phone call's."

Inside `AppointmentSheet`, directly after `ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {`, add:

```kotlin
        val callback = draft.kind == AppointmentKind.CALLBACK
```

Replace the title `text = "Termin vor Ort",` with:

```kotlin
                text = if (callback) "Rückruf" else "Termin vor Ort",
```

Replace `Appointment.DURATIONS.forEach { minutes ->` with:

```kotlin
                    Appointment.durations(draft.kind).forEach { minutes ->
```

Wrap the location label and field:

```kotlin
                if (!callback) {
                    SectionLabel("Ort")
                    OutlinedTextField(
                        value = draft.location,
                        onValueChange = { onDraft(draft.copy(location = it)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        singleLine = false,
                    )
                }
```

Replace the note placeholder `placeholder = { Text("Besichtigung, Angebot …") },` with:

```kotlin
                    placeholder = { Text(if (callback) "wegen Angebot nachfragen …" else "Besichtigung, Angebot …") },
```

Replace `Text("Termin speichern")` with:

```kotlin
                Text(if (callback) "Rückruf speichern" else "Termin speichern")
```

- [ ] **Step 7: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt
git commit -m "Termin-Blatt für Rückrufe: ohne Ort, kurze Dauer, kein Status"
```

---

### Task 8: App — a call completes, the calendar gets its tick

**Repository:** app.

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`evaluateCall`, `followCalendar`, two new private functions)

**Interfaces:**
- Consumes: `Repository.completeCallbacks` (Task 6), `Clock.nextDayStart` (Task 5), `Appointment.eventTitle` (Task 4), `locateEvent`, `calendarLookup` (existing).
- Produces: private `syncEventTitle(entry, business)`, private `completeDueCallbacks(placeId): Int`.

No unit tests: calendar provider and view model. The title rule and `completeCallbacks` are tested.

- [ ] **Step 1: Writing the title into an event**

In `…/CallsheetViewModel.kt`, directly below `rememberSeen`, add:

```kotlin
    /**
     * Writes the title [entry] calls for into its event, and nothing else: the
     * event's own time, place and description go back as the calendar holds
     * them. Moving an event is the read-back's decision, not this one's.
     *
     * Only where the calendar is switched on, readable and writable. A calendar
     * that could not be asked, or an event not on this device, is left alone:
     * the device holding the event writes it after its next sync.
     */
    private suspend fun syncEventTitle(entry: AppointmentEntry, business: Business) {
        val context = getApplication<Application>()
        if (!preferences.calendarEnabled || !CalendarStore.canRead(context) || !CalendarStore.canWrite(context)) return
        if (entry.eventUid == null && entry.calendarEventId == null) return
        val located = calendarLookup { locateEvent(entry) }.getOrNull() ?: return
        val title = Appointment.eventTitle(entry.kind, business.name, entry.note, done = entry.doneAt != null)
        if (located.event.title != title) {
            CalendarStore.update(context, located.eventId, located.event.copy(title = title))
        }
    }

    /**
     * Completes the business's open callbacks due by the end of today and puts
     * the tick on their events. Called where a call from the app is logged —
     * whatever its duration: an unanswered call is a callback made too. Returns
     * how many were completed.
     */
    private suspend fun completeDueCallbacks(placeId: String): Int {
        val now = System.currentTimeMillis()
        val completed = repo.completeCallbacks(placeId, Clock.nextDayStart(now), Clock.format(now))
        if (completed.isEmpty()) return 0
        val business = repo.business(placeId) ?: return completed.size
        for (id in completed) repo.appointment(id)?.let { syncEventTitle(it, business) }
        return completed.size
    }
```

- [ ] **Step 2: Completing on the call**

In `evaluateCall`, directly after the `repo.logCall(CallEntry(…))` call and before `// Whoever was called can call back: …`, add:

```kotlin
            // The call is what completes a callback — not saving the outcome: the
            // save bar only appears when status or note changed, and a business
            // already at „Nicht erreicht" rung again without an answer changes
            // neither. Up at once, so other devices stop listing it as overdue.
            if (completeDueCallbacks(placeId) > 0) syncNow()
```

- [ ] **Step 3: The tick follows on other devices**

In `followCalendar`, replace the loop

```kotlin
                for (id in batch.written.distinct()) {
                    val entry = repo.appointment(id) ?: continue
                    if (entry.eventUid == null && entry.calendarEventId == null) continue
                    val business = repo.business(entry.placeId) ?: continue
                    reconcile(entry, business, now, rowWinsOnly = true)
                }
```

with

```kotlin
                for (id in batch.written.distinct()) {
                    val entry = repo.appointment(id) ?: continue
                    if (entry.eventUid == null && entry.calendarEventId == null) continue
                    val business = repo.business(entry.placeId) ?: continue
                    reconcile(entry, business, now, rowWinsOnly = true)
                    // The read-back compares time and place only, so a callback
                    // completed elsewhere would never get its tick here. With a
                    // shared calendar the completing device has usually written it
                    // already, the titles match, and nothing is written.
                    if (entry.kind == AppointmentKind.CALLBACK) {
                        repo.appointment(id)?.let { syncEventTitle(it, business) }
                    }
                }
```

- [ ] **Step 4: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Anruf erledigt fällige Rückrufe, Kalendereintrag bekommt den Haken"
```

---
### Task 9: App — the „Wiedervorlage" section lists callbacks

**Repository:** app.

**Files:**
- Modify: `…/ui/BusinessDetail.kt`
- Modify: `…/MainActivity.kt`
- Modify: `…/CallsheetViewModel.kt` (`setFollowUp` goes; the suggestion clears on saving a callback)

**Interfaces:**
- Consumes: `openCallback` (Task 7), `Appointment.splitCallbacks`, `isOverdue`, `statusAfterRemoval` (Task 4).
- Produces: `BusinessDetailScreen(…, onCallback: (String) -> Unit, …)` replacing `onFollowUp: (String?) -> Unit`.

- [ ] **Step 1: Screen parameters and sections**

In `…/ui/BusinessDetail.kt` add `import io.github.amadeusb.callsheet.data.AppointmentKind`.

In `BusinessDetailScreen`'s parameters replace `onFollowUp: (String?) -> Unit,` with:

```kotlin
    /** Opens the sheet for a new callback at the given start. */
    onCallback: (String) -> Unit,
```

Directly below `var removeAppointment by remember { mutableStateOf<AppointmentEntry?>(null) }` add:

```kotlin
    val visits = appointments.filter { it.kind == AppointmentKind.VISIT }
    val callbacks = appointments.filter { it.kind == AppointmentKind.CALLBACK }
```

In `item(key = "appointment")`, change `appointments = appointments,` to `appointments = visits,`.

Replace the whole `item(key = "follow-up") { … }` with:

```kotlin
            item(key = "follow-up") {
                Section("Wiedervorlage")
                CallbacksBlock(
                    placeId = business.placeId,
                    callbacks = callbacks,
                    contacts = contacts,
                    suggestion = followUpSuggestion,
                    onCallback = onCallback,
                    onEdit = { id -> onAppointment(id) },
                    onRemove = { removeAppointment = it },
                    onPickDate = { dateOpen = true },
                )
            }
```

- [ ] **Step 2: The removal dialog by kind**

Replace the whole `removeAppointment?.let { entry -> … }` block with:

```kotlin
    removeAppointment?.let { entry ->
        val now = System.currentTimeMillis()
        val callback = entry.kind == AppointmentKind.CALLBACK
        // A record is what stays as proof: a past visit, a completed callback.
        val record = if (callback) entry.doneAt != null else !Appointment.isAhead(entry.startsAt, entry.endsAt, now)
        val fallsBack = !callback && Appointment.statusAfterRemoval(
            business.status, appointments.filter { it.id != entry.id }, now,
        ) != null
        AlertDialog(
            onDismissRequest = { removeAppointment = null },
            title = {
                Text(
                    when {
                        callback && record -> "Erledigten Rückruf entfernen?"
                        callback -> "Rückruf entfernen?"
                        record -> "Früheren Termin entfernen?"
                        else -> "Termin entfernen?"
                    }
                )
            },
            text = {
                Text(
                    listOfNotNull(
                        when {
                            callback && record -> "Der Rückruf ist erledigt und bleibt sonst als Nachweis stehen. " +
                                "Er wird auch aus dem Kalender gelöscht."
                            callback -> "Der Rückruf wird auch aus dem Kalender gelöscht."
                            record -> "Der Termin ist vorbei und bleibt sonst als Nachweis stehen. " +
                                "Er wird auch aus dem Kalender gelöscht."
                            else -> "Der Termin wird auch aus dem Kalender gelöscht."
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

- [ ] **Step 3: Date and time open the sheet**

In the `if (dateOpen) { … }` block, replace

```kotlin
            initialSelectedDateMillis = Clock.millis(business.followUpAt)
                ?: System.currentTimeMillis(),
```

with

```kotlin
            initialSelectedDateMillis = System.currentTimeMillis(),
```

In the `if (timeOpen) { … }` block, replace

```kotlin
        val prefill = Clock.millis(business.followUpAt)?.let { Clock.zdt(it) }
        val state = rememberTimePickerState(
            initialHour = prefill?.hour ?: 10,
            initialMinute = prefill?.minute ?: 0,
```

with

```kotlin
        val state = rememberTimePickerState(
            initialHour = 10,
            initialMinute = 0,
```

and replace `onFollowUp(` with `onCallback(` in its confirm button (the `FollowUp.fromDateAndTime(…)` argument stays).

- [ ] **Step 4: `CallbacksBlock` replaces `FollowUpBlock`**

Delete the function `FollowUpBlock` and put in its place:

```kotlin
/**
 * The callbacks. Open ones first, earliest first, an overdue one said as such;
 * completed ones stay as a record, collapsed, the way „Frühere Termine" do.
 *
 * Every way in — the suggestion after a call, the quick choices, the picker —
 * opens the appointment sheet with that time. Nothing is saved before
 * „Rückruf speichern", and every callback has its own „Ändern" and „Entfernen".
 */
@Composable
private fun CallbacksBlock(
    placeId: String,
    callbacks: List<AppointmentEntry>,
    contacts: List<Contact>,
    suggestion: String?,
    onCallback: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (AppointmentEntry) -> Unit,
    onPickDate: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val (open, done) = Appointment.splitCallbacks(callbacks)
    var showDone by remember(placeId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (open.isEmpty()) {
            Text(
                text = "Keine Wiedervorlage gesetzt.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        open.forEach { CallbackItem(it, contacts, overdue = Appointment.isOverdue(it, now), onEdit = onEdit, onRemove = onRemove) }

        if (done.isNotEmpty()) {
            TextButton(onClick = { showDone = !showDone }) {
                Text("Erledigte Rückrufe (${done.size})")
            }
            if (showDone) done.forEach { CallbackItem(it, contacts, overdue = false, onEdit = null, onRemove = onRemove) }
        }

        // Gone once a callback at exactly that time exists — saved from this very card.
        if (suggestion != null && open.none { it.startsAt == suggestion }) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Vorschlag: ${Clock.readable(suggestion)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onCallback(suggestion) }) { Text("Übernehmen") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton("in 2 Tagen", Modifier.weight(1f)) {
                onCallback(FollowUp.inTwoDays())
            }
            QuickButton("nächste Woche", Modifier.weight(1f)) {
                onCallback(FollowUp.nextWeek())
            }
            QuickButton("nächster Monat", Modifier.weight(1f)) {
                onCallback(FollowUp.nextMonth())
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPickDate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Datum & Uhrzeit …") }
    }
}

/** One callback. [onEdit] is null for a completed one: completing is not undone, only removed. */
@Composable
private fun CallbackItem(
    entry: AppointmentEntry,
    contacts: List<Contact>,
    overdue: Boolean,
    onEdit: ((String) -> Unit)?,
    onRemove: (AppointmentEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = Appointment.readableRange(entry.startsAt, entry.endsAt),
            style = MaterialTheme.typography.titleMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (overdue) {
            Text(
                text = "überfällig",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        entry.doneAt?.let {
            Text(
                text = "Erledigt ${Clock.readable(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        if (entry.calendarEventId != null) {
            Text(
                text = "Im Kalender abgelegt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onEdit != null) {
                OutlinedButton(onClick = { onEdit(entry.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Ändern")
                }
            }
            OutlinedButton(onClick = { onRemove(entry) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Entfernen")
            }
        }
    }
}
```

In the KDoc of `AppointmentsBlock`, replace „Sits above the follow-up because the two are the answers to one question — when does this go on?" with „Visits only; callbacks have their own section below. Both answer one question — when does this go on?"

- [ ] **Step 5: Wiring**

In `…/MainActivity.kt`, in the `BusinessDetailScreen(…)` call, replace

```kotlin
                    onFollowUp = { vm.setFollowUp(business.placeId, it) },
```

with

```kotlin
                    onCallback = { start -> vm.openCallback(business.placeId, start) },
```

In `…/CallsheetViewModel.kt`, delete the function `setFollowUp`. In `saveAppointment`, replace

```kotlin
            _state.update { it.copy(appointmentDraft = null, hint = calendarHint) }
```

with

```kotlin
            _state.update {
                it.copy(
                    appointmentDraft = null,
                    hint = calendarHint,
                    // The suggestion after a call is answered once a callback is saved.
                    followUpSuggestion = if (kind == AppointmentKind.CALLBACK) null else it.followUpSuggestion,
                )
            }
```

- [ ] **Step 6: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. A compiler warning about an unused import (for example `Card` no longer being used elsewhere) is fine; an error is not.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Wiedervorlage in der Detailansicht: Rückrufe mit Ändern, Entfernen und Erledigten"
```

---
### Task 10: App — the agenda behind the calendar button

**Repository:** app.

**Files:**
- Create: `…/ui/Agenda.kt`
- Delete: `…/ui/Today.kt`
- Modify: `…/CallsheetViewModel.kt` (`Screen`, `State`, navigation, loading)
- Modify: `…/ui/Components.kt` (`BusinessRow`)
- Modify: `…/ui/WorkList.kt`, `…/MainActivity.kt`

**Interfaces:**
- Consumes: `Repository.agenda` (Task 6), `Agenda`, `AgendaSection`, `AgendaGroup` (Task 5).
- Produces:
  - `Screen.Agenda` replacing `Screen.Today`; `fun showAgenda()` replacing `showToday()`; private `loadAgenda()` replacing `loadToday()`
  - `State.agenda: List<AgendaSection<Pair<AppointmentEntry, Business>>>` replacing `overdue`, `dueToday`, `appointmentsToday`
  - `BusinessRow(business, onDial, onOpen, modifier, label: String? = null, overdue: Boolean = false)`
  - `AgendaScreen(sections, onBack, onDial, onOpen)`
  - `WorkListScreen(…, onAgenda: () -> Unit, …)` replacing `onToday`

- [ ] **Step 1: The row**

In `…/ui/Components.kt`, change `BusinessRow`'s parameters

```kotlin
    showFollowUp: Boolean = false,
    /** The appointment this row stands for, in „Termine heute". */
    appointment: AppointmentEntry? = null,
    overdue: Boolean = false,
```

to

```kotlin
    /** What this row stands for in the agenda: „Rückruf · 09:00 – 09:15 · …". */
    label: String? = null,
    /** An overdue callback: the label in the error colour. */
    overdue: Boolean = false,
```

and replace the two blocks `appointment?.let { … }` and `if (showFollowUp && business.followUpAt != null) { … }` with:

```kotlin
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
```

Remove the imports of `AppointmentEntry` and `Appointment` from `Components.kt` if nothing else in the file uses them (`grep -n "Appointment" app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt`).

- [ ] **Step 2: The screen**

Delete `…/ui/Today.kt` (`git rm app/src/main/java/io/github/amadeusb/callsheet/ui/Today.kt`) and create `…/ui/Agenda.kt`:

```kotlin
package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.calling.Agenda
import io.github.amadeusb.callsheet.calling.AgendaGroup
import io.github.amadeusb.callsheet.calling.AgendaSection
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.Business

/**
 * Everything coming up: overdue callbacks at the top and set apart — a missed
 * callback must not disappear silently — then today, then every later day that
 * has something on it. Callbacks and visits mixed by time, each row saying
 * which it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    sections: List<AgendaSection<Pair<AppointmentEntry, Business>>>,
    onBack: () -> Unit,
    onDial: (Business) -> Unit,
    onOpen: (Business) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Termine") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { inner ->
        if (sections.isEmpty()) {
            Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                EmptyState("Keine Termine und keine offenen Rückrufe.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            sections.forEach { section ->
                val overdue = section.group == AgendaGroup.OVERDUE
                item(key = "header-${section.group}-${section.dayStartMillis}") {
                    GroupHeader(
                        title = Agenda.title(section),
                        subtitle = if (overdue) "Liegengeblieben. Diese zuerst." else null,
                        background = when (section.group) {
                            AgendaGroup.OVERDUE -> MaterialTheme.colorScheme.errorContainer
                            AgendaGroup.TODAY -> MaterialTheme.colorScheme.tertiaryContainer
                            AgendaGroup.DAY -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        foreground = when (section.group) {
                            AgendaGroup.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
                            AgendaGroup.TODAY -> MaterialTheme.colorScheme.onTertiaryContainer
                            AgendaGroup.DAY -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
                // Each entry sits in exactly one section, so its id is a unique key.
                items(section.items, key = { it.first.id }) { (entry, business) ->
                    Column(
                        modifier = if (overdue) {
                            Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                        } else {
                            Modifier
                        }
                    ) {
                        BusinessRow(
                            business = business,
                            onDial = { onDial(business) },
                            onOpen = { onOpen(business) },
                            // The overdue ones come from any day: they need their date.
                            label = Agenda.rowLabel(entry, withDate = overdue),
                            overdue = overdue,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item(key = "footer") { Column(Modifier.height(24.dp)) {} }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    subtitle: String?,
    background: Color,
    foreground: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
            )
        }
    }
}
```

- [ ] **Step 3: The view model**

In `…/CallsheetViewModel.kt`:

Add imports `io.github.amadeusb.callsheet.calling.Agenda` and `io.github.amadeusb.callsheet.calling.AgendaSection`.

In `sealed interface Screen`, replace `data object Today : Screen` with:

```kotlin
    /** The agenda behind the calendar button. */
    data object Agenda : Screen
```

Because `Screen.Agenda` and `calling.Agenda` now share a simple name, refer to the screen always as `Screen.Agenda` and to the object as `Agenda` — inside `sealed interface Screen` nothing else is declared, so no clash arises there.

In `data class State`, delete

```kotlin
    val overdue: List<Business> = emptyList(),
    val dueToday: List<Business> = emptyList(),
```

and replace `val appointmentsToday: List<Pair<AppointmentEntry, Business>> = emptyList(),` with:

```kotlin
    /** What the agenda shows, grouped. */
    val agenda: List<AgendaSection<Pair<AppointmentEntry, Business>>> = emptyList(),
```

In `back()`, replace `is Screen.Today -> loadToday()` with `is Screen.Agenda -> loadAgenda()`.

Replace the functions `showToday` and `loadToday` with:

```kotlin
    fun showAgenda() {
        _state.update { it.copy(screen = Screen.Agenda, history = historyFor(Screen.Agenda)) }
        loadAgenda()
    }

    private fun loadAgenda() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val listed = repo.agenda(Clock.todayStart(now))
            _state.update { it.copy(agenda = Agenda.sections(listed, { pair -> pair.first }, now)) }
        }
    }
```

Replace every remaining `loadToday()` (after a sync in `syncNow`, and in `connectServer`) with `loadAgenda()`.

Check: `grep -n "loadToday\|showToday\|Screen.Today\|appointmentsToday\|dueToday\|\.overdue" app/src/main/java/io/github/amadeusb/callsheet/` prints nothing.

- [ ] **Step 4: Work list and wiring**

In `…/ui/WorkList.kt`, rename the parameter `onToday: () -> Unit,` to `onAgenda: () -> Unit,` and replace

```kotlin
                    IconButton(onClick = onToday) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Heute")
                    }
```

with

```kotlin
                    IconButton(onClick = onAgenda) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Termine")
                    }
```

In `…/MainActivity.kt`, replace `onToday = vm::showToday,` with `onAgenda = vm::showAgenda,`, and replace the whole branch `is Screen.Today -> TodayScreen(…)` with:

```kotlin
        is Screen.Agenda -> AgendaScreen(
            sections = state.agenda,
            onBack = { vm.back() },
            onDial = vm::queryNumbers,
            onOpen = { vm.openBusiness(it.placeId) },
        )
```

Replace the import `io.github.amadeusb.callsheet.ui.TodayScreen` with `io.github.amadeusb.callsheet.ui.AgendaScreen`.

- [ ] **Step 5: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/Agenda.kt app/src/main/java/io/github/amadeusb/callsheet/ui/Today.kt app/src/main/java/io/github/amadeusb/callsheet/ui/Components.kt app/src/main/java/io/github/amadeusb/callsheet/ui/WorkList.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
git commit -m "Termine hinter dem Kalender-Knopf: überfällig, heute und jeder weitere Tag"
```

---

### Task 11: App — the follow-up column is read by nothing

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt` (`Business.followUpAt` goes)
- Modify: `…/data/Repository.kt` (`due`, `appointmentsDue`, `setFollowUp` go; `fromCursor`; comments)
- Modify: `…/calling/Appointment.kt` (`rowLabel` goes)
- Test: `test/…/RepositoryTest.kt`, `test/…/AppointmentTest.kt`, `test/…/GeoUriTest.kt`, `test/…/PhoneBookEntriesTest.kt`

**Interfaces:**
- Produces: nothing new. After this task `grep -rn "followUpAt\|setFollowUp\|appointmentsDue\|fun due" app/src` finds nothing, and `follow_up_at` appears only in `Database.kt` and `MigrationTest.kt`.

- [ ] **Step 1: Tests first — take out what tested the old follow-up**

In `test/…/RepositoryTest.kt`:

- In `a second import leaves the work untouched`, delete the line `repo.setFollowUp("P1", Clock.format(System.currentTimeMillis() + 86_400_000))`, the line `val previousFollowUp = before.followUpAt` and the line `assertEquals(previousFollowUp, after.followUpAt)`. If `before` is then unused, delete `val before = repo.business("P1")!!` too.
- In `blocked businesses appear in no list`, replace

```kotlin
        // … and not among the follow-ups either.
        repo.setFollowUp("P2", Clock.format(System.currentTimeMillis() - 3_600_000))
        assertTrue(repo.due().none { it.placeId == "P2" })
```

with

```kotlin
        // … and not in the agenda either, not even with an overdue callback.
        repo.saveAppointment(callback("R-2", "P2", Clock.format(System.currentTimeMillis() - 3_600_000)))
        assertTrue(repo.agenda(Clock.todayStart()).none { it.second.placeId == "P2" })
```

- Delete the tests `a follow-up can be cleared again` and `due returns overdue items first and nothing from the future`.
- Delete the four `appointmentsDue` tests: `appointmentsDue returns one row per appointment, earliest first`, `a past appointment is not due today`, `a blocked business never appears in appointmentsDue`, and `a due appointment's business knows its contacts' numbers` — and add in their place, so the numbers stay covered:

```kotlin
    @Test
    fun `an agenda entry's business knows its contacts' numbers`() = runTest {
        import("""[{"placeId":"t-8","title":"Ohne Hauptnummer"}]""")
        repo.saveContact(ContactDraft(placeId = "t-8", name = "Frau Meier", numbers = listOf(PhoneDraft(number = "+49 176 12345"))))
        repo.saveAppointment(visit("A-8", "t-8", "2026-09-10T09:00:00+02:00", "2026-09-10T10:00:00+02:00"))

        // Without it the dial button in the agenda would show nothing to dial.
        assertTrue(repo.agenda(Clock.millis("2026-09-10T00:00:00+02:00")!!).single().second.hasNumber)
    }
```

In `test/…/AppointmentTest.kt`, delete the test `a row in Heute reads time range and note`.

In `test/…/GeoUriTest.kt` and `test/…/PhoneBookEntriesTest.kt`, delete `followUpAt = null,` from the `Business(…)` constructions (leave the rest of each line).

- [ ] **Step 2: Run to see the compile fail**

Run: `./gradlew testDebugUnitTest`
Expected: compilation FAILS in `GeoUriTest` and `PhoneBookEntriesTest` — `followUpAt` is still a required parameter. That is the failing state this task removes.

- [ ] **Step 3: Remove the code**

In `…/data/Models.kt`, delete `val followUpAt: String?,` from `Business`.

In `…/data/Repository.kt`:
- delete the functions `due`, `appointmentsDue` and `setFollowUp` with their KDoc;
- in `fromCursor`, delete `followUpAt = c.text("follow_up_at"),`;
- in the class KDoc, change „Status, note, follow-up and call history are never touched." to „Status, note, appointments and call history are never touched.";
- in the import's comment that lists the working columns (`// Imported master data only. status, note, follow_up_at and …`), drop `follow_up_at` from the list.

In `…/calling/Appointment.kt`, delete the function `rowLabel` with its KDoc.

- [ ] **Step 4: Nothing reads the column**

Run: `grep -rn "followUpAt\|setFollowUp\|appointmentsDue\|fun due\|rowLabel(entry: AppointmentEntry)" app/src`
Expected: only `Agenda.rowLabel(entry: AppointmentEntry, withDate: Boolean)` in `calling/Agenda.kt`.

Run: `grep -rn "follow_up_at" app/src`
Expected: only `Database.kt` and `MigrationTest.kt`.

- [ ] **Step 5: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt app/src/test/java/io/github/amadeusb/callsheet/GeoUriTest.kt app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt
git commit -m "Alte Wiedervorlage entfernt: nichts liest follow_up_at mehr"
```

---
### Task 12: App — documentation and changelog

**Repository:** app.

**Files:**
- Modify: `docs/usage.md`, `docs/architecture.md`, `docs/data-model.md`, `README.md`, `CHANGELOG.md`

- [ ] **Step 1: `docs/usage.md`**

In `## The call flow`, item 3, replace „proposes „nicht erreicht" plus a follow-up in two days at a different time of day." with „proposes „nicht erreicht" plus a callback in two days at a different time of day." Below the numbered list (before „When a business has several numbers …") add:

```markdown
A call placed from the app completes every open callback of that business that
was due by the end of today — whether anybody answered or not. The calendar
entry keeps its place and gets a „✓" in front of its title.
```

Replace the whole section `## Follow-ups` (heading and both paragraphs) with:

```markdown
## Callbacks

A callback („Rückruf") is an appointment to ring a business again. The detail
view lists them under **Wiedervorlage**: the open ones with their time, note and
contact person, an overdue one in red, each with **Ändern** and **Entfernen**.
Completed callbacks stay as a record under **Erledigte Rückrufe**, collapsed.

„in 2 Tagen", „nächste Woche", „nächster Monat" and **Datum & Uhrzeit …** open
the same sheet as an appointment on site, already set to that time: 15 minutes,
no place. Add a note — „wegen Angebot nachfragen" — and the person to ask for,
then **Rückruf speichern**. After an unanswered call the suggestion card does
the same with **Übernehmen**. A business can have several callbacks.

Saved with the calendar switched on, a callback is written into the calendar as
„Rückruf <business>", the same way an appointment is. A callback never changes
the business's status.

## The agenda

The calendar button at the top of the work list opens **Termine**: first the
overdue callbacks — set apart, from whichever day, so a missed callback does not
disappear silently — then today, with today's appointments on site (past ones
included) and the callbacks still ahead, then every later day that has something
on it. Each row says „Rückruf" or „Vor Ort", the time and the note. Completed
callbacks and appointments from earlier days are not listed.
```

In `## Blocking a business`, replace „not in the search, not in „Heute", not even" with „not in the search, not in „Termine", not even".

- [ ] **Step 2: `docs/architecture.md`**

In `### Business`, replace the bullet

```markdown
- a **follow-up** with quick choices („in 2 Tagen", „nächste Woche",
  „nächster Monat") and a free pick of date and time
```

with

```markdown
- **appointments on site** and **callbacks**, each opening the same sheet; the
  callbacks' quick choices („in 2 Tagen", „nächste Woche", „nächster Monat")
  and the free pick of date and time only preset its time
```

Replace the section `### Today` (heading and paragraph) with:

```markdown
### Agenda

Behind the calendar button: open callbacks that are overdue — from any day, at
the top and set apart visually — then today, then every later day with something
on it. Callbacks and appointments on site mixed by time. Grouping is a pure
function (`calling/Agenda.kt`), tested without Android.
```

In `## Call flow`, after the step where the call log is evaluated, add as the next step (renumber the following ones):

```markdown
N. Open callbacks of that business due by the end of today are completed
   (`done_at`), and their calendar events get a „✓" in the title. Here and not
   on saving the outcome: the save bar only appears when status or note changed.
```

where `N` is the number that step gets in the list.

- [ ] **Step 3: `docs/data-model.md`**

In the working fields of `businesses`, replace

```
follow_up_at    TEXT            -- ISO-8601 with a time, not just a date
```

with

```
follow_up_at    TEXT            -- no longer used since schema 6, emptied; callbacks are appointments
```

In the `appointments` block, replace the first line's comment and add two columns before `dirty`:

```
id                      TEXT PRIMARY KEY  -- UUID; carried over: 'legacy-<place_id>' (schema 4), 'followup-<place_id>' (schema 6)
```

```
kind                    TEXT              -- 'visit' | 'callback'; NULL reads as 'visit'
done_at                 TEXT              -- when a callback was completed; NULL while open, always for a visit
```

Replace the paragraph starting „Appointments on site, any number per business" with:

```markdown
Appointments, any number per business — on site („visit") or a callback — one
after another, or side by side. Synchronised like contacts: a UUID per row,
deleted through tombstones.
```

Below the paragraph „Schema 4 carried each business's single appointment over …" add:

```markdown
Schema 6 carried each business's follow-up over into a callback
`followup-<place_id>` — the same id the server's migration 008 writes, with the
business's `updated_at` and `dirty` — and emptied `follow_up_at` on both sides.
Carried-over callbacks have no calendar event until they are next saved: every
device would otherwise write its own into the shared calendar.

`kind` is nullable on both sides: the server fills only gaps where `NULL`
stands, and a 1.4.0 device creates appointments without it. A callback is
completed by a call to its business (`done_at`); saving never clears
`done_at`, the same way it never clears `event_uid`.
```

In `## Calendar`, add a bullet at the end of the list:

```markdown
- A callback's event is titled „Rückruf <business>", with the note after a dash.
  Completed, the title gets a leading „✓" and the event stays: CalDAV events
  have no completed state, only tasks do. After a sync, a callback completed on
  another device gets the tick here if its title still lacks it. The tick is not
  read back.
```

- [ ] **Step 4: `README.md`**

Replace the bullet `- **Status, note and follow-up** in one go after hanging up` with `- **Status and note** in one go after hanging up`.

Replace

```markdown
- **Appointments on site**, as many per business as the work needs, each with a
  time, a length, an address, a note and a contact person, mirrored into the
  device's calendar
- **"Today"** with the day's appointments above follow-ups that are due *and*
  overdue
```

with

```markdown
- **Appointments on site and callbacks**, as many per business as the work
  needs, each with a time, a length, a note and a contact person, mirrored into
  the device's calendar; a call completes the callbacks due
- **"Termine"**: overdue callbacks, then today, then every coming day
```

In `## Synchronisation`, replace „businesses, contacts, calls and follow-ups" with „businesses, contacts, calls and appointments". In the documentation table, replace „import, daily flow, blocking, follow-ups, appointments" with „import, daily flow, blocking, callbacks, appointments, agenda".

- [ ] **Step 5: `CHANGELOG.md`**

If the file has no section for the next version yet, add `## 1.5.0` above `## 1.4.0`. Under that section (below any bullets already there, for example from the e-mail work) add:

```markdown
- **A follow-up is an appointment now: a callback.** „in 2 Tagen", „nächste
  Woche", „nächster Monat" and the date picker open the appointment sheet, set
  to that time. A callback has a note, a person to ask for, its own calendar
  entry „Rückruf …", and its own **Ändern** and **Entfernen**. A business can
  have several.
- **A call completes it.** Calling the business from the app completes every
  callback that was due by the end of today, answered or not. The calendar entry
  stays and gets a „✓" in front of its title. Completed callbacks stay as a
  record under **Erledigte Rückrufe**.
- **The calendar button opens „Termine"**: overdue callbacks first, then today,
  then every coming day — not only today any more.
- Callbacks never change the status.
- **Update the sync server first, then every phone.** Existing follow-ups become
  callbacks on both sides without a calendar entry; they get one when saved
  again. A phone still on 1.4.0 shows callbacks as appointments on site, and a
  follow-up set there is lost.
```

- [ ] **Step 6: Check and commit**

Run: `grep -n -i "follow-up\|„Heute\"" docs/usage.md docs/architecture.md README.md`
Expected: no line that still describes the old follow-up or the old „Heute" screen (the word „follow-up" inside the carried-over explanation in `docs/data-model.md` is fine).

```bash
git add docs/usage.md docs/architecture.md docs/data-model.md README.md CHANGELOG.md
git commit -m "Doku und CHANGELOG: Rückrufe als Termine, Agenda"
```

---

### Task 13: Server — deploy

**Repository:** server, and the production host. **Outward-facing: ask the user before Step 3 and wait for a yes.**

Every remote command is one non-interactive `ssh <server> '…'` call. Never open an interactive shell on the host.

- [ ] **Step 1: Push**

Run: `node --test` — all pass. Then ask the user, and on a yes: `git push origin main`.

- [ ] **Step 2: See what the host will pull**

```bash
ssh <server> 'cd <deploy-dir>/repo && git fetch && git diff HEAD origin/main --stat'
```

Expected: `src/receive.js`, `db/migrations/008-callbacks.sql`, tests, README — plus whatever the e-mail work brought if it was not deployed yet. If the e-mail work is in the diff and its own deployment needs something (for example a `LETTERMINT_API_KEY` in `<deploy-dir>/.env`), stop and ask the user.

- [ ] **Step 3: Count before, and back up**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT COUNT(*) AS with_follow_up FROM businesses WHERE follow_up_at IS NOT NULL AND follow_up_at <> \x27\x27\").get());
  console.log(db.prepare(\"SELECT value AS counter FROM sync_counter\").get());
"'
ssh <server> 'sudo <deploy-dir>/repo/scripts/backup.sh'
```

Expected: two numbers; the backup script exits 0. Restoring that backup later drops every sync since and needs **Alles erneut hochladen** on every phone — it is the way back, not a free one.

- [ ] **Step 4: Pull and restart**

`src/start.js` applies pending migrations on start.

```bash
ssh <server> 'cd <deploy-dir>/repo && git pull && ENVIRONMENT=prod ./scripts/up.sh'
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml ps'
```

Expected: `up.sh` exits 0; `callsheet` is `healthy` within 90 seconds.

- [ ] **Step 5: Check the migration on the live file, straight away**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT filename FROM schema_migrations ORDER BY filename DESC LIMIT 1\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS callbacks FROM appointments WHERE kind = \x27callback\x27\").get());
  console.log(db.prepare(\"SELECT value AS counter FROM sync_counter\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS left_over FROM businesses WHERE follow_up_at IS NOT NULL\").get());
"'
```

Expected:
- `008-callbacks.sql`
- `callbacks` equals `with_follow_up` from Step 3
- `counter` equals Step 3's `counter` plus `callbacks`, or more if a phone synced in between
- `left_over` is 0 — or only businesses a phone still on 1.4.0 changed after the restart (it sends `follow_up_at` along; nothing reads it)

- [ ] **Step 6: From outside**

```bash
curl -si https://callsheet.tm-services.de/health | head -1
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://callsheet.tm-services.de/sync
```

Expected: `HTTP/2 200`, then `401`.

---

### Task 14: App — try it on a phone, then release

**Repository:** app. **The device test needs the user's phone; the release is outward-facing: ask before Step 4.**

- [ ] **Step 1: Everything green**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. `git status --porcelain` is empty.

- [ ] **Step 2: Install on a connected phone**

Run: `~/android-sdk/platform-tools/adb devices -l`
Expected: one device. If none: ask the user to connect the phone with USB debugging on, and wait.

Run: `./gradlew installDebug`
Expected: `Installed on 1 device`.

- [ ] **Step 3: Walk through it with the user**

Ask the user to check, on a phone with calendar and sync switched on, and report back:

1. After the update a follow-up set before is listed under **Wiedervorlage** as a callback, and under **Termine**.
2. **in 2 Tagen** opens the sheet titled „Rückruf", 15 minutes, no „Ort"; saving shows the callback with **Ändern** and **Entfernen**, and the calendar has „Rückruf <Betrieb>".
3. **Termine** lists tomorrow's and next week's appointments, not only today's.
4. A callback due today: call the business from the app, hang up, return. The callback is under **Erledigte Rückrufe**, and the calendar entry reads „✓ Rückruf <Betrieb>". It is no longer under **Überfällig**.
5. On a second phone (if at hand), after a sync: the same callback is completed, and its calendar entry carries the tick.
6. Removing a callback leaves the status as it was.

Anything that does not match: stop, find the cause (superpowers:systematic-debugging), fix it test-first, and repeat this step.

- [ ] **Step 4: Release**

Only after Task 13 is done and the user says yes:

Run: `tools/release.sh minor`
Expected: the script builds, tests, tags and publishes `1.5.0` with the CHANGELOG section as release notes. If `1.5.0` was already released by the e-mail work, stop and ask which version to use.
