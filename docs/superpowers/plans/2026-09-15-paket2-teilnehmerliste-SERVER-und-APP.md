# Package 2 — Attendees instead of an invitation switch — Implementation Plan (SERVER and APP)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A visit carries a list of attendees instead of one invitation address; saving asks „Mail an … senden?" / „Absage an … senden?" when nothing but the list changed, and the server sends the update with or without notification accordingly — always with notification when title, time or place changed.

**Architecture:** Two synchronised, nullable columns on `appointments` (server migration 012, app schema 10): `attendees` (JSON list) and `attendees_notify` (1/0/NULL), filled together at a standstill. The server's pure `visitState.js` compares attendees as a set and decides `shouldNotify(wanted, held, pushed, flag)`; `pushCalendar.js` passes that decision to create and update. The app's pure rules live in `data/Attendees.kt` (the list) and `calling/Appointment.kt` (the question, its title, the stored notify value, the lines in the detail view); the view model asks through a dialog in the appointment sheet and saves with the answer. `invite_email` stays as a column on both sides and is read by nothing.

**Tech Stack:** Server: Node 24, `node:sqlite` (JSON functions), `node --test`. App: Kotlin, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `org.json`, JUnit 4 + Robolectric 4.16 (`@Config(sdk = [34])`), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-15-paket2-teilnehmerliste-design.md` (app repo, commit `bd0c003`, corrected in this plan's commit); the server's pointer is the same path in the server repo (commit `92e5915`). Read the spec before starting.

## Decisions made while planning (on top of the spec)

- **Coordinator decisions:** nothing preselected on a new visit, `inviteAfterContactChange` goes without replacement (its tests are deleted); dialog texts and buttons as in the spec; more than three addresses read „a, b und 2 weitere"; a column, not a table; `attendees_notify` written only when the list changes; migration 012 rewrites `infomaniak_events.pushed`, with a test that nothing is queued afterwards; `attendees` and `attendees_notify` filled as one unit at a standstill.
- **The question comes after the slot conflict.** „Trotzdem anlegen" is remembered in the draft (`forced`), so the save after the answer does not ask about the slot again.
- **An address still in the text field is added on saving** — or blocks saving with its error — rather than being lost silently.
- **Two server tasks, not three.** Changing `wantedState` breaks `pushCalendar` and `receive` until they follow, so those three files change in one task; the migration comes first on its own.
- **Review findings (worked in):** the older tests that compare whole appointment rows get the two new columns in Task 1, so Task 1 ends green; renaming a business queues its created visits with the default title (`queueRenamedVisits`), so the next list-only change saved without mail does not notify everybody for a title that changed unseen; an update keeps each attendee's answer (`state`) from the event it read, only new addresses start as `NEEDS-ACTION`; the organizer's own address is refused as an attendee; an open question clears the slot conflict shown; between server deploy and app update nobody changes invitations on an old phone.
- **Two app tasks for the switch.** Task 6 adds the new fields and rules next to the old invitation (everything compiles, all tests green); Task 7 moves the sheet and the view model over and deletes the invitation.

## Global Constraints

- **Two repositories, both on branch `main`.** App: `~/code/tm-services-automate/caller-app/app` (Kotlin sources under `app/src/main/java/io/github/amadeusb/callsheet/`, written `…/` below; tests under `app/src/test/java/io/github/amadeusb/callsheet/`, written `test/…` below). Server: `~/code/tm-services-automate/caller-app/server`. Every task names its repository. Server Tasks 1–3 and app Tasks 4–8 touch disjoint files and can run in parallel.
- **After package 1.** `docs/superpowers/plans/2026-09-15-paket1-fixes-SERVER-und-APP.md` is implemented first and taken as given: server migration `011-edited-fields.sql`, app `Database.VERSION = 9`, `Preferences.refetchedForEditedFields`, `MigrationTest.createVersionEight()`, `Reconcile.MissingVisit`, `Appointment.removalAsks`, `Appointment.removedHint`, the `## 1.6.0` section in `CHANGELOG.md`. Task 0 checks it.
- **Numbers:** server migration **`012-attendees.sql`**, app `Database.VERSION` **9 → 10**.
- **Columns:** `appointments.attendees TEXT` — JSON array of trimmed addresses, order as added, no duplicates ignoring case, NULL for none, always NULL for a callback. `appointments.attendees_notify INTEGER` — 1, 0 or NULL (NULL reads as 1), written by the app only in a save that changes the list. Both nullable, both synchronised, both app-owned.
- **`invite_email` stays** on both sides, is not emptied, and after Task 7 is neither read nor written by the app; the server stops reading it in Task 2.
- **Notify rule (server):** nobody on the list now, in the event, or in what was last sent → no notification; title, time or place differ from what Infomaniak holds → notify; otherwise (only the list, or a create) → notify unless `attendees_notify = 0`.
- **UI texts, exactly:** section „Teilnehmende"; button „Hinzufügen"; chip suffix „ (Betrieb)"; hint „Teilnehmende sehen Titel, Zeit und Ort, nicht die Notiz."; errors „Das ist keine gültige E-Mail-Adresse." and „Die eigene Adresse ist immer dabei."; dialog titles „Mail an <names> senden?", „Absage an <names> senden?", „Mail an <names> und Absage an <names> senden?"; dialog text „Ohne Mail wird der Termin trotzdem gespeichert."; buttons „Senden", „Ohne Mail speichern"; calendar line „Im Kalender · Teilnehmende: <names>"; removal „<names> bekommt eine Absage." / „<names> bekommen eine Absage."; names „a", „a, b", „a, b, c", „a, b und 2 weitere".
- **The app repo is public on GitHub.** No hostnames, server paths, calendar ids or personal addresses in the app repo. Tests use `test@example.org`, `zweite@example.org` and other `example.org` addresses.
- **Tests:** app `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`; build check `./gradlew assembleDebug`; both from the app repository root. Server `node --test` (all) or `node --test test/<file>.test.js`, locally only.
- **No view model test harness exists.** Decisions are pure functions with unit tests; the view model and Compose wiring are checked by `assembleDebug` and the phone test (Task 10).
- **Language:** code, identifiers, comments, docs in English; UI text German with correct umlauts and „…" quotes; server README German. Test names in English; the app fixture method stays `fun aufbau()`.
- **State writes** in new view model code use `_state.update { … }`.
- **Every task leaves its project building and all tests green. Commit after every task,** staging only the files the task names — never `git add -A` or `git add .`. German commit messages in the tone of `git log`, no attribution lines. Do not push except where Task 9 says so.
- **Rollout order:** server (011 and 012) deployed first (Task 9), then the app released as 1.6.0 together with package 1 (Task 10). Both outward-facing: ask before each.
- **Questions go to the coordinator session „caller-app-34"** (SendMessage), not to the user, except where Tasks 9 and 10 say „ask the user".

---

## File Structure

| Repository | File | Change | Responsibility |
|---|---|---|---|
| server | `db/migrations/012-attendees.sql` | create | columns, carry-over, `pushed` rewritten |
| server | `src/visitState.js` | modify | `attendeeList`, `sameCore`, `sameAttendees`, `shouldNotify`; state and bodies with attendees |
| server | `src/pushCalendar.js` | modify | create/update notify through `shouldNotify` |
| server | `src/receive.js` | modify | `CALENDAR_COLUMNS`; `fillGaps` pairs the two columns |
| server | `test/db.test.js`, `test/visitState.test.js`, `test/pushCalendar.test.js`, `test/receive.test.js` | modify | |
| server | `README.md` | modify | visits, attendees, known behaviour |
| app | `…/data/Attendees.kt` | create | the list: parse, format, add, compare, names |
| app | `…/data/Database.kt` | modify | `VERSION = 10`, columns, carry-over |
| app | `…/contacts/Preferences.kt`, `…/sync/SyncEngine.kt` | modify | refetch on schema 10 |
| app | `…/sync/SyncStore.kt` | modify | `fillGaps` pairs the two columns |
| app | `…/data/Models.kt` | modify | `AppointmentEntry.attendees`, `.attendeesNotify`; `inviteEmail` removed (Task 7) |
| app | `…/data/Repository.kt` | modify | read and write the columns; `invite_email` no longer (Task 7) |
| app | `…/calling/Appointment.kt` | modify | `AttendeeQuestion`, `attendeeQuestion`, `attendeeQuestionTitle`, `attendeesNotifyToStore`, `calendarLine`, `cancellationNotice`; `attendeeAddresses`; invitation removed (Task 7) |
| app | `…/CallsheetViewModel.kt` | modify | draft fields, `addAttendee`, `answerAttendeeQuestion`, `cancelAttendeeQuestion`, `saveVisit` |
| app | `…/ui/AppointmentSheet.kt` | modify | `AttendeeSection`, the question dialog |
| app | `…/MainActivity.kt` | modify | wiring |
| app | tests | modify/create | `AttendeesTest` (new), `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `SyncEngineTest`, `RepositoryTest`, `AppointmentTest` |
| app | `docs/data-model.md`, `docs/usage.md`, `CHANGELOG.md` | modify | docs |

---

### Task 0: Preconditions

**Files:** none. Run the steps for the repository you are working in.

- [ ] **Step 1: Clean trees on `main`**

Run in the app repo and in the server repo: `git status --short && git branch --show-current`
Expected: no output from `status`, branch `main`. This plan and its spec are committed. Otherwise stop and ask „caller-app-34".

- [ ] **Step 2: Package 1 is in, the numbers are free**

Run in the server repo: `ls db/migrations | tail -2`
Expected: `010-visits-via-api.sql` and `011-edited-fields.sql`. If `011` is missing, package 1 is not implemented — stop and ask. If a `012-*.sql` exists, stop and ask.

Run in the app repo:

```bash
grep -n 'const val VERSION' app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt
grep -n 'refetchedForEditedFields' app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt
grep -n 'data object MissingVisit\|fun removalAsks\|fun cancellationNotice' app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt
grep -n 'private fun createVersionEight' app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt
grep -n '^## 1.6.0' CHANGELOG.md
```

Expected: `const val VERSION = 9`, and at least one line from each other command. Anything else: stop and ask.

- [ ] **Step 3: Both suites green before anything changes**

Run in the server repo: `node --test` — expected `# fail 0`.
Run in the app repo: `./gradlew testDebugUnitTest` — expected BUILD SUCCESSFUL.

---

### Task 1: Server — migration 012

**Repository:** server.

**Files:**
- Create: `db/migrations/012-attendees.sql`
- Test: `test/db.test.js` (append; four existing row comparisons), `test/deliver.test.js` (one existing comparison), `test/receive.test.js` (one existing comparison)

**Interfaces:**
- Produces: columns `appointments.attendees`, `appointments.attendees_notify`; carried-over `attendees`; `infomaniak_events.pushed` with `attendees` instead of `invite_email`.

- [ ] **Step 1: Write the failing tests**

Append to `test/db.test.js`:

```js
/**
 * A database in the state a running server is in when 012 arrives: every
 * earlier migration applied, a business, an invited visit whose event exists,
 * a visit without an invitation whose event exists, and one whose invitation
 * is only blanks.
 */
function databaseBefore012() {
  const path = join(mkdtempSync(join(tmpdir(), 'callsheet-')), 'test.db')
  const db = open(path)
  db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL)')
  for (const filename of MIGRATIONS.filter(name => name < '012')) {
    db.exec(readFileSync(join('db/migrations', filename), 'utf8'))
    db.prepare('INSERT INTO schema_migrations (filename, applied_at) VALUES (?, ?)').run(filename, new Date().toISOString())
  }
  db.prepare(
    `INSERT INTO businesses (place_id, name, closed, is_target, status, updated_at, server_seq)
     VALUES ('P1', 'Elektro Meier', 0, 1, 'new', '2026-09-07T10:00:00+02:00', 1)`
  ).run()
  const appointment = db.prepare(
    `INSERT INTO appointments (id, place_id, starts_at, ends_at, location, note, updated_at, kind,
                               invite_email, calendar_state, event_uid, server_seq)
     VALUES (?, 'P1', '2026-09-10T14:00:00+02:00', '2026-09-10T15:00:00+02:00', 'Zehentstraße 39',
             'Besichtigung', '2026-09-07T10:00:00+02:00', 'visit', ?, 'ok', ?, ?)`
  )
  appointment.run('T1', ' test@example.org ', 'uid-1', 2)
  appointment.run('T2', null, 'uid-2', 3)
  appointment.run('T3', '   ', 'uid-3', 4)
  const pushed = invite => JSON.stringify({
    title: 'Erstgespräch KI bei Elektro Meier – Christoph Bauer',
    starts_at: '2026-09-10T14:00:00+02:00', ends_at: '2026-09-10T15:00:00+02:00',
    location: 'Zehentstraße 39', invite_email: invite,
  })
  const job = db.prepare(
    'INSERT INTO infomaniak_events (appointment_id, event_id, pushed, pending, generation) VALUES (?, ?, ?, NULL, 1)'
  )
  job.run('T1', 41, pushed('test@example.org'))
  job.run('T2', 42, pushed(null))
  db.prepare('UPDATE sync_counter SET value = 4').run()
  return db
}

test('migration 012 carries each invitation over as the only attendee and marks nothing', () => {
  const db = databaseBefore012()

  migrate(db, 'db/migrations')

  const rows = db.prepare('SELECT id, attendees, attendees_notify, invite_email, server_seq FROM appointments ORDER BY id').all()
    .map(row => ({ ...row }))
  assert.deepEqual(rows, [
    { id: 'T1', attendees: '["test@example.org"]', attendees_notify: null, invite_email: ' test@example.org ', server_seq: 2 },
    { id: 'T2', attendees: null, attendees_notify: null, invite_email: null, server_seq: 3 },
    { id: 'T3', attendees: null, attendees_notify: null, invite_email: '   ', server_seq: 4 },
  ])
  assert.equal(db.prepare('SELECT value FROM sync_counter').get().value, 4)
})

test('migration 012 rewrites what Infomaniak last received from invite_email to attendees', () => {
  const db = databaseBefore012()

  migrate(db, 'db/migrations')

  const pushed = id => JSON.parse(db.prepare('SELECT pushed FROM infomaniak_events WHERE appointment_id = ?').get(id).pushed)
  const common = {
    title: 'Erstgespräch KI bei Elektro Meier – Christoph Bauer',
    starts_at: '2026-09-10T14:00:00+02:00', ends_at: '2026-09-10T15:00:00+02:00', location: 'Zehentstraße 39',
  }
  assert.deepEqual(pushed('T1'), { ...common, attendees: ['test@example.org'] })
  assert.deepEqual(pushed('T2'), { ...common, attendees: [] })
})

test('appointments.attendees and attendees_notify accept NULL', () => {
  const db = freshDb()
  db.prepare(
    `INSERT INTO appointments (id, place_id, starts_at, updated_at, server_seq, attendees, attendees_notify)
     VALUES ('T1', 'P1', '2026-09-10T14:00:00+02:00', '2026-09-07T10:00:00+02:00', 1, NULL, NULL)`
  ).run()
  assert.deepEqual({ ...db.prepare('SELECT attendees, attendees_notify FROM appointments').get() }, { attendees: null, attendees_notify: null })
})
```

Every existing test that compares a whole appointment row gets the two new columns, or it fails once the migration adds them:

In `test/db.test.js`, replace all **four** occurrences (the tests for migrations 005, 008 — twice — and 010) of

```js
    invite_email: null,
    calendar_state: null,
```

with:

```js
    invite_email: null,
    attendees: null,
    attendees_notify: null,
    calendar_state: null,
```

In `test/deliver.test.js`, test `appointments are delivered, without their sequence number`, replace

```js
    ...APPOINTMENT, title: null, invite_email: null, calendar_state: 'pending', calendar_error: null,
```

with:

```js
    ...APPOINTMENT, title: null, invite_email: null, attendees: null, attendees_notify: null,
    calendar_state: 'pending', calendar_error: null,
```

In `test/receive.test.js`, test `an appointment is written with every column the app owns and a sequence number`, replace `    invite_email: null,` with:

```js
    invite_email: null,
    attendees: null,
    attendees_notify: null,
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test test/db.test.js test/deliver.test.js test/receive.test.js`
Expected: FAIL — `no such column: attendees` in the three new tests; the six changed comparisons fail because the rows lack `attendees` and `attendees_notify`.

- [ ] **Step 3: Write the migration**

Create `db/migrations/012-attendees.sql`:

```sql
-- A visit invites a list of people instead of one address. attendees is a JSON
-- array of addresses (NULL for nobody); attendees_notify says whether the last
-- change to that list notifies them: 1, 0 after "Ohne Mail speichern", NULL
-- for rows from before this migration, read as 1. The app writes
-- attendees_notify only in a save that changes the list. Both are the app's,
-- both nullable, as the README asks of every new column.
--
-- invite_email stays and is not emptied; nothing reads it after this.

ALTER TABLE appointments ADD COLUMN attendees TEXT;
ALTER TABLE appointments ADD COLUMN attendees_notify INTEGER;

-- The invitee becomes the only attendee. Not marked, no sequence number: the
-- app's schema 10 carries over the same value, so the rows need not travel.
UPDATE appointments
SET attendees = json_array(trim(invite_email))
WHERE trim(COALESCE(invite_email, '')) <> '';

-- What Infomaniak last received, in the new shape. Without this the next
-- comparison would find a changed list on every invited visit and send an
-- update with mail. Two statements rather than a CASE: json_set keeps a JSON
-- array only when it comes straight from json_array.
UPDATE infomaniak_events
SET pushed = json_set(json_remove(pushed, '$.invite_email'), '$.attendees',
                      json_array(trim(json_extract(pushed, '$.invite_email'))))
WHERE json_valid(pushed) AND trim(COALESCE(json_extract(pushed, '$.invite_email'), '')) <> '';

UPDATE infomaniak_events
SET pushed = json_set(json_remove(pushed, '$.invite_email'), '$.attendees', json_array())
WHERE json_valid(pushed) AND json_type(pushed, '$.attendees') IS NULL;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --test test/db.test.js test/deliver.test.js test/receive.test.js`
Expected: PASS.

Run: `node --test`
Expected: `# fail 0` — no code reads the new columns yet, and no fresh test database holds a `pushed` from before.

- [ ] **Step 5: Commit**

```bash
git add db/migrations/012-attendees.sql test/db.test.js test/deliver.test.js test/receive.test.js
git commit -m "Migration 012: Teilnehmende statt einer Einladungsadresse, pushed umgeschrieben"
```

---

### Task 2: Server — attendees in the state, notify through `shouldNotify`, receive pairs the columns

**Repository:** server.

**Files:**
- Modify: `src/visitState.js`
- Modify: `src/pushCalendar.js` (import; `create`; `update`; `work`)
- Modify: `src/receive.js` (`fillGaps`; `CALENDAR_COLUMNS` and its doc comment; `receiveBusiness` and a new `queueRenamedVisits` above it)
- Test: `test/visitState.test.js`, `test/pushCalendar.test.js`, `test/receive.test.js`

**Interfaces:**
- Consumes: the columns from Task 1.
- Produces in `src/visitState.js`: `attendeeList(value): string[]`, `sameCore(a, b): boolean`, `sameAttendees(a, b): boolean`, `sameState(a, b)` (core and attendees), `shouldNotify(wanted, held, pushed, flag): boolean`, `createBody(state, calendarId, notify = false, held = [])`, `updateBody(state, calendarId, eventId, notify, held = [])` — [held] is the event's `attendees` as read from Infomaniak, whose `state` each address keeps; `wantedState` and `eventState` return `{ title, starts_at, ends_at, location, attendees }`.

- [ ] **Step 1: Write the failing tests — `visitState`**

In `test/visitState.test.js`:

Replace the import block with:

```js
import {
  DEFAULT_VISIT_MINUTES, defaultTitle, wantedState, sameState, sameCore, sameAttendees, attendeeList, shouldNotify,
  berlinWallTime, createBody, updateBody, eventState, uidFromIcs,
} from '../src/visitState.js'
```

In `VISIT`, replace `title: null, invite_email: null,` with `title: null, attendees: null,`.

Replace the tests `the wanted state carries time, place and invitation, never the note`, `title, place and address are trimmed, and a blank title counts as none`, `empty place and address count as none`, `the same state is the same instant and address, however they are spelled` and `title, time, place and invitation each make a different state` with:

```js
test('the wanted state carries time, place and attendees, never the note', () => {
  const state = wantedState({ ...VISIT, attendees: '["test@example.org","zweite@example.org"]' }, 'Elektro Meier')
  assert.deepEqual(state, {
    title: 'Erstgespräch KI bei Elektro Meier – Christoph Bauer',
    starts_at: '2026-09-10T14:00:00+02:00',
    ends_at: '2026-09-10T15:00:00+02:00',
    location: 'Zehentstraße 39, 85055 Ingolstadt',
    attendees: ['test@example.org', 'zweite@example.org'],
  })
})

test('title, place and attendees are trimmed, and a blank title counts as none', () => {
  const state = wantedState({ ...VISIT, title: '  Angebot besprechen ', location: ' Am Pulverl 5 ', attendees: '[" test@example.org "]' }, 'Elektro Meier')
  assert.equal(state.title, 'Angebot besprechen')
  assert.equal(state.location, 'Am Pulverl 5')
  assert.deepEqual(state.attendees, ['test@example.org'])
  const blank = wantedState({ ...VISIT, title: '   ', location: '  ' }, 'Elektro Meier')
  assert.equal(blank.title, 'Erstgespräch KI bei Elektro Meier – Christoph Bauer')
  assert.equal(blank.location, null)
})

test('empty place and no attendees count as none', () => {
  assert.equal(wantedState({ ...VISIT, location: '' }, 'Elektro Meier').location, null)
  assert.deepEqual(wantedState({ ...VISIT, attendees: '[]' }, 'Elektro Meier').attendees, [])
  assert.deepEqual(wantedState({ ...VISIT, attendees: null }, 'Elektro Meier').attendees, [])
})

test('the attendee list drops blanks, duplicates, the organizer and anything that is not a list', () => {
  assert.deepEqual(
    attendeeList('["a@example.org", " A@example.org ", "", null, "Christoph@Bauer-KI.de", "b@example.org"]'),
    ['a@example.org', 'b@example.org'],
  )
  assert.deepEqual(attendeeList(['a@example.org']), ['a@example.org'])
  for (const value of [null, undefined, '', 'kaputt', '{"a":1}', 42]) {
    assert.deepEqual(attendeeList(value), [], JSON.stringify(value))
  }
})

test('the same state is the same instant and the same attendees, however they are spelled or ordered', () => {
  const a = wantedState({ ...VISIT, attendees: '["a@example.org","b@example.org"]' }, 'Elektro Meier')
  const b = { ...a, starts_at: '2026-09-10T12:00:00Z', ends_at: '2026-09-10T13:00:00.000Z', attendees: ['B@example.org', ' a@example.org'] }
  assert.equal(sameState(a, b), true)
  assert.equal(sameAttendees(a, b), true)
  assert.equal(sameCore(a, b), true)
})

test('title, time, place and attendees each make a different state', () => {
  const a = wantedState(VISIT, 'Elektro Meier')
  assert.equal(sameState(a, { ...a, title: 'anders' }), false)
  assert.equal(sameState(a, { ...a, starts_at: '2026-09-10T14:30:00+02:00' }), false)
  assert.equal(sameState(a, { ...a, ends_at: '2026-09-10T15:30:00+02:00' }), false)
  assert.equal(sameState(a, { ...a, location: null }), false)
  assert.equal(sameState(a, { ...a, attendees: ['test@example.org'] }), false)
  assert.equal(sameCore(a, { ...a, attendees: ['test@example.org'] }), true)
  assert.equal(sameState(a, null), false)
})

test('shouldNotify: title, time or place changed notifies, a list-only change follows the flag', () => {
  const held = wantedState({ ...VISIT, attendees: '["test@example.org"]' }, 'Elektro Meier')
  const moreAttendees = { ...held, attendees: ['test@example.org', 'zweite@example.org'] }
  const moved = { ...moreAttendees, starts_at: '2026-09-10T16:00:00+02:00' }
  const nobody = { ...held, attendees: [] }

  // Only the list changed.
  assert.equal(shouldNotify(moreAttendees, held, held, 0), false)
  assert.equal(shouldNotify(moreAttendees, held, held, 1), true)
  assert.equal(shouldNotify(moreAttendees, held, held, null), true)
  // Removing everybody is still a change the removed hear of — unless told not to.
  assert.equal(shouldNotify(nobody, held, held, null), true)
  assert.equal(shouldNotify(nobody, held, held, 0), false)
  // Title, time or place changed: everybody, whatever the flag.
  assert.equal(shouldNotify(moved, held, held, 0), true)
  assert.equal(shouldNotify({ ...held, title: 'Angebot' }, held, held, 0), true)
  assert.equal(shouldNotify({ ...held, location: 'Am Pulverl 5' }, held, held, 0), true)
  // A create: the flag decides.
  assert.equal(shouldNotify(held, null, null, null), true)
  assert.equal(shouldNotify(held, null, null, 0), false)
  // Nobody anywhere: nothing to notify.
  const none = wantedState(VISIT, 'Elektro Meier')
  assert.equal(shouldNotify({ ...none, starts_at: '2026-09-10T16:00:00+02:00' }, none, none, 1), false)
  assert.equal(shouldNotify(none, null, null, 1), false)
  // Somebody only in what was last sent still counts.
  assert.equal(shouldNotify({ ...none, starts_at: '2026-09-10T16:00:00+02:00' }, none, held, 1), true)
})
```

Replace the test `a create body with an invitation lists invitee and organizer and notifies` with:

```js
test('a create body with attendees lists them and the organizer, and notifies only when told', () => {
  const state = wantedState({ ...VISIT, attendees: '["test@example.org","zweite@example.org"]' }, 'Elektro Meier')
  const body = createBody(state, 1001, true)
  assert.deepEqual(body.attendees, [
    { address: 'test@example.org', className: 'Attendee', name: 'test@example.org', organizer: false, state: 'NEEDS-ACTION' },
    { address: 'zweite@example.org', className: 'Attendee', name: 'zweite@example.org', organizer: false, state: 'NEEDS-ACTION' },
    { address: 'christoph@bauer-ki.de', className: 'Attendee', name: 'Christoph Bauer', organizer: true, state: 'ACCEPTED' },
  ])
  assert.equal(body.notifyAttendees, true)
  assert.equal(body.description, '')
  assert.equal(createBody(state, 1001, false).notifyAttendees, undefined)
  assert.equal(createBody(state, 1001).notifyAttendees, undefined)
})

test('an update body keeps what each attendee answered, a new address needs action', () => {
  // The API replaces the whole event: a state not sent back would be an answer erased.
  const state = wantedState({ ...VISIT, attendees: '["test@example.org","zweite@example.org"]' }, 'Elektro Meier')
  const held = [
    { address: 'TEST@example.org', organizer: false, state: 'ACCEPTED' },
    { address: 'christoph@bauer-ki.de', organizer: true, state: 'ACCEPTED' },
  ]

  const body = updateBody(state, 1001, 42, true, held)

  assert.deepEqual(body.attendees.map(attendee => [attendee.address, attendee.state]), [
    ['test@example.org', 'ACCEPTED'],
    ['zweite@example.org', 'NEEDS-ACTION'],
    ['christoph@bauer-ki.de', 'ACCEPTED'],
  ])
  assert.deepEqual(updateBody(state, 1001, 42, true).attendees.map(attendee => attendee.state), ['NEEDS-ACTION', 'NEEDS-ACTION', 'ACCEPTED'])
})
```

Replace the tests `an event read from Infomaniak becomes a state` and `the organizer is never taken for the invitee, even without its flag` with:

```js
test('an event read from Infomaniak becomes a state', () => {
  // The shape GET /1/calendar/pim/event/{id} returned on 2026-09-15.
  const event = {
    id: 42, calendar_id: 1001, title: 'API-TEST', location: null,
    start: '2026-09-16T23:00:00+02:00', end: '2026-09-16T23:30:00+02:00',
    attendees: [
      { address: 'test@example.org', organizer: false, state: 'NEEDS-ACTION', name: 'test@example.org' },
      { address: 'zweite@example.org', organizer: false, state: 'ACCEPTED', name: 'zweite@example.org' },
      { address: 'christoph@bauer-ki.de', organizer: true, state: 'ACCEPTED', name: 'Christoph Bauer' },
    ],
  }
  assert.deepEqual(eventState(event), {
    title: 'API-TEST', starts_at: '2026-09-16T23:00:00+02:00', ends_at: '2026-09-16T23:30:00+02:00',
    location: null, attendees: ['test@example.org', 'zweite@example.org'],
  })
  assert.deepEqual(eventState({ ...event, attendees: [] }).attendees, [])
  assert.deepEqual(eventState({ ...event, attendees: undefined }).attendees, [])
})

test('the organizer is never taken for an attendee, even without its flag', () => {
  const event = {
    title: 'x', location: null, start: '2026-09-16T23:00:00+02:00', end: '2026-09-16T23:30:00+02:00',
    attendees: [
      { address: 'Christoph@Bauer-KI.de', state: 'ACCEPTED', name: 'Christoph Bauer' },
      { address: 'test@example.org', organizer: false, state: 'NEEDS-ACTION', name: 'test@example.org' },
    ],
  }
  assert.deepEqual(eventState(event).attendees, ['test@example.org'])
  assert.deepEqual(eventState({ ...event, attendees: [event.attendees[0]] }).attendees, [])
})
```

- [ ] **Step 2: Write the failing tests — `pushCalendar`**

In `test/pushCalendar.test.js`:

In `VISIT`, replace `event_uid: null, title: null, invite_email: null,` with:

```js
  event_uid: null, title: null, attendees: null, attendees_notify: null,
```

Replace the function `eventFor` with:

```js
/** Infomaniak's GET answer for a state. */
function eventFor({ title = 'Erstgespräch KI bei Elektro Meier – Christoph Bauer', start = VISIT.starts_at, end = VISIT.ends_at, location = VISIT.location, attendees = [] } = {}) {
  return {
    id: 42, title, location, start, end,
    attendees: attendees.length
      ? [...attendees.map(address => ({ address, organizer: false })), { address: 'christoph@bauer-ki.de', organizer: true }]
      : [],
  }
}
```

In the test `create stores the event id before the export is called, then the UID and ok`, replace `starts_at: VISIT.starts_at, ends_at: VISIT.ends_at, location: VISIT.location, invite_email: null,` with:

```js
    starts_at: VISIT.starts_at, ends_at: VISIT.ends_at, location: VISIT.location, attendees: [],
```

Replace the four tests `create with an invitation sends attendees and notifies`, `update puts the whole event and notifies when invited`, `update from invited to not invited still notifies` and `update without an invitation before or after does not notify` with:

```js
const INVITED = { ...VISIT, attendees: '["test@example.org"]', attendees_notify: 1 }

test('create with attendees lists them and notifies', async () => {
  const db = freshDb()
  queued(db, INVITED)
  const client = fakeClient()

  await pushCalendar(db, client)

  const body = client.calls[0][1]
  assert.equal(body.attendees[0].address, 'test@example.org')
  assert.equal(body.notifyAttendees, true)
})

test('create with attendees saved without mail lists them and notifies nobody', async () => {
  const db = freshDb()
  queued(db, { ...INVITED, attendees_notify: 0 })
  const client = fakeClient()

  await pushCalendar(db, client)

  const body = client.calls[0][1]
  assert.equal(body.attendees[0].address, 'test@example.org')
  assert.equal(body.notifyAttendees, undefined)
})

test('update puts the whole event and notifies everybody when the time changed', async () => {
  const db = freshDb()
  await created(db, fakeClient(), INVITED)
  receive(db, { appointments: [later({ attendees: INVITED.attendees, attendees_notify: 1, starts_at: '2026-09-10T16:00:00+02:00', ends_at: '2026-09-10T17:00:00+02:00' })] })
  const client = fakeClient({ event: eventFor({ attendees: ['test@example.org'] }) })

  await pushCalendar(db, client)

  assert.deepEqual(names(client), ['getEvent', 'updateEvent'])
  const [, id, body] = client.calls[1]
  assert.equal(id, 42)
  assert.equal(body.id, 42)
  assert.equal(body.start, '2026-09-10 16:00:00')
  assert.equal(body.notifyAttendees, true)
  assert.equal(appointment(db).calendar_state, 'ok')
})

test('a change to the list alone, saved without mail, updates without notifying', async () => {
  const db = freshDb()
  await created(db, fakeClient(), INVITED)
  receive(db, { appointments: [later({ attendees: '["test@example.org","zweite@example.org"]', attendees_notify: 0 })] })
  const client = fakeClient({ event: eventFor({ attendees: ['test@example.org'] }) })

  await pushCalendar(db, client)

  assert.deepEqual(names(client), ['getEvent', 'updateEvent'])
  const body = client.calls[1][2]
  assert.deepEqual(body.attendees.map(attendee => attendee.address), ['test@example.org', 'zweite@example.org', 'christoph@bauer-ki.de'])
  assert.equal(body.notifyAttendees, false)
})

test('a change to the list alone, saved with mail, notifies', async () => {
  const db = freshDb()
  await created(db, fakeClient(), INVITED)
  receive(db, { appointments: [later({ attendees: '["test@example.org","zweite@example.org"]', attendees_notify: 1 })] })
  const client = fakeClient({ event: eventFor({ attendees: ['test@example.org'] }) })

  await pushCalendar(db, client)

  assert.equal(client.calls[1][2].notifyAttendees, true)
})

test('list and time changed together notify, even when the list was saved without mail', async () => {
  const db = freshDb()
  await created(db, fakeClient(), INVITED)
  receive(db, { appointments: [later({
    attendees: '["test@example.org","zweite@example.org"]', attendees_notify: 0,
    starts_at: '2026-09-10T16:00:00+02:00', ends_at: '2026-09-10T17:00:00+02:00',
  })] })
  const client = fakeClient({ event: eventFor({ attendees: ['test@example.org'] }) })

  await pushCalendar(db, client)

  assert.equal(client.calls[1][2].notifyAttendees, true)
})

test('removing everybody from a visit from before this version notifies them', async () => {
  // attendees_notify NULL, as every row migration 012 carried over.
  const db = freshDb()
  await created(db, fakeClient(), { ...INVITED, attendees_notify: null })
  receive(db, { appointments: [later({ attendees: null, attendees_notify: null })] })
  const client = fakeClient({ event: eventFor({ attendees: ['test@example.org'] }) })

  await pushCalendar(db, client)

  const body = client.calls[1][2]
  assert.deepEqual(body.attendees, [])
  assert.equal(body.notifyAttendees, true)
})

test('update without attendees before or after does not notify', async () => {
  const db = freshDb()
  await created(db, fakeClient())
  receive(db, { appointments: [later({ location: 'Am Pulverl 5, 85051 Ingolstadt' })] })
  const client = fakeClient({ event: eventFor() })

  await pushCalendar(db, client)

  assert.equal(client.calls[1][2].notifyAttendees, false)
})

test('an update keeps the answers attendees gave in the calendar', async () => {
  const db = freshDb()
  await created(db, fakeClient(), INVITED)
  receive(db, { appointments: [later({ attendees: '["test@example.org","zweite@example.org"]', attendees_notify: 1 })] })
  const event = eventFor({ attendees: ['test@example.org'] })
  event.attendees[0].state = 'ACCEPTED'
  const client = fakeClient({ event })

  await pushCalendar(db, client)

  assert.deepEqual(client.calls[1][2].attendees.map(attendee => attendee.state), ['ACCEPTED', 'NEEDS-ACTION', 'ACCEPTED'])
})
```

- [ ] **Step 3: Write the failing tests — `receive`**

In `test/receive.test.js`:

In the function `pushed`, replace `location: stored.location, invite_email: stored.invite_email,` with:

```js
      location: stored.location, attendees: stored.attendees ? JSON.parse(stored.attendees) : [],
```

In the `for (const [what, changes] of [ … ])` list, replace the entry `['invitation', { invite_email: '…' }],` (the address in it is from the 1.5.0 tests) with:

```js
  ['attendees', { attendees: '["test@example.org"]' }],
```

After the test `a newer visit with only a changed note queues nothing`, add:

```js
test('a newer visit with only attendees_notify changed queues nothing', () => {
  const db = freshDb()
  const invited = { ...APPOINTMENT, attendees: '["test@example.org"]', attendees_notify: 1 }
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [invited] })
  pushed(db)

  receive(db, { ...empty, appointments: [later(invited, { attendees_notify: 0 })] })

  assert.equal(job(db).pending, null)
})

test('a newer visit with the same attendees in another order and case queues nothing', () => {
  const db = freshDb()
  const invited = { ...APPOINTMENT, attendees: '["a@example.org","b@example.org"]' }
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [invited] })
  pushed(db)

  receive(db, { ...empty, appointments: [later(invited, { attendees: '["B@example.org"," a@example.org"]' })] })

  assert.equal(job(db).pending, null)
})

test('a standstill fills attendees and attendees_notify together, the decision over a stored one', () => {
  // All attendees removed without mail here (NULL, 0); the same save elsewhere carried the list.
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [{ ...APPOINTMENT, attendees: null, attendees_notify: 0 }] })

  receive(db, { ...empty, appointments: [{ ...APPOINTMENT, attendees: '["test@example.org"]', attendees_notify: 1 }] })

  const row = storedAppointment(db)
  assert.equal(row.attendees, '["test@example.org"]')
  assert.equal(row.attendees_notify, 1)
})

test('a standstill never fills attendees_notify without attendees', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [{ ...APPOINTMENT, attendees: '["test@example.org"]' }] })

  receive(db, { ...empty, appointments: [{ ...APPOINTMENT, attendees: '["zweite@example.org"]', attendees_notify: 0 }] })

  const row = storedAppointment(db)
  assert.equal(row.attendees, '["test@example.org"]')
  assert.equal(row.attendees_notify, null)
})

test('renaming a business queues its created visits that carry the default title, and only those', () => {
  // The default title names the business: renamed, the event would keep the old
  // title, and the next list-only change saved without mail would find it
  // different and notify everybody.
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [APPOINTMENT, { ...APPOINTMENT, id: 'T2', title: 'Angebot besprechen' }, CALLBACK] })
  pushed(db)
  pushed(db, 'T2', 43)

  receive(db, { ...empty, businesses: [{ ...BUSINESS, name: 'Elektro Meier & Sohn', updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(job(db).pending, 'update')
  assert.equal(storedAppointment(db).calendar_state, 'pending')
  assert.equal(job(db, 'T2').pending, null)
  assert.equal(job(db, 'R1'), undefined)
})

test('renaming a business creates no event for a visit the calendar never had', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [APPOINTMENT] })
  db.prepare('DELETE FROM infomaniak_events').run()

  receive(db, { ...empty, businesses: [{ ...BUSINESS, name: 'Elektro Meier & Sohn', updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(job(db), undefined)
})

test('a newer business with the same name queues nothing', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS], appointments: [APPOINTMENT] })
  pushed(db)

  receive(db, { ...empty, businesses: [{ ...BUSINESS, note: 'neu', updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(job(db).pending, null)
})
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `node --test test/visitState.test.js test/pushCalendar.test.js test/receive.test.js`
Expected: FAIL — `visitState.test.js` with `does not provide an export named 'attendeeList'` (the whole file fails to load); in `pushCalendar.test.js` the attendee tests (no attendees in the bodies, `notifyAttendees` false or undefined where true is expected); in `receive.test.js` the `attendees` update test (nothing queued) and `a standstill fills attendees and attendees_notify together, …` (`attendees_notify` stays 0) and `a standstill never fills attendees_notify without attendees` (it is filled with 0), and `renaming a business queues its created visits …` (nothing queued); in `pushCalendar.test.js` also `an update keeps the answers …`.

- [ ] **Step 5: `visitState.js`**

In `src/visitState.js`:

Replace the KDoc and body of `wantedState`:

```js
/**
 * Everything of a visit the invitee can see, and nothing else. The note is not
 * here on purpose: a change to it must not reach Infomaniak, let alone send a
 * mail.
 *
 * Trimmed, so whitespace typed on a phone or added in the web calendar never
 * makes a state differ. The app always sends `ends_at`; the default length is
 * only a fallback for a row without one.
 */
export function wantedState(appointment, businessName) {
  return {
    title: text(appointment.title) ?? defaultTitle(businessName),
    starts_at: appointment.starts_at,
    ends_at: appointment.ends_at
      || new Date(Date.parse(appointment.starts_at) + DEFAULT_VISIT_MINUTES * 60_000).toISOString(),
    location: text(appointment.location),
    invite_email: text(appointment.invite_email),
  }
}
```

with:

```js
/**
 * A visit's attendees as a list: parsed from the column's JSON text (an array
 * is taken as it is), trimmed, blanks dropped, each address once ignoring
 * case, and never the organizer — the bodies add that entry themselves.
 * Anything that is not a JSON array reads as nobody.
 */
export function attendeeList(value) {
  let list = value
  if (typeof value === 'string') {
    try {
      list = JSON.parse(value)
    } catch {
      return []
    }
  }
  if (!Array.isArray(list)) return []
  const seen = new Set()
  const result = []
  for (const entry of list) {
    const trimmed = text(entry)
    if (!trimmed) continue
    const key = trimmed.toLowerCase()
    if (key === ORGANIZER.address || seen.has(key)) continue
    seen.add(key)
    result.push(trimmed)
  }
  return result
}

/**
 * Everything of a visit the attendees can see, and nothing else. The note is
 * not here on purpose: a change to it must not reach Infomaniak, let alone
 * send a mail.
 *
 * Trimmed, so whitespace typed on a phone or added in the web calendar never
 * makes a state differ. The app always sends `ends_at`; the default length is
 * only a fallback for a row without one. `invite_email` is read no more: since
 * migration 012 the attendees are the list.
 */
export function wantedState(appointment, businessName) {
  return {
    title: text(appointment.title) ?? defaultTitle(businessName),
    starts_at: appointment.starts_at,
    ends_at: appointment.ends_at
      || new Date(Date.parse(appointment.starts_at) + DEFAULT_VISIT_MINUTES * 60_000).toISOString(),
    location: text(appointment.location),
    attendees: attendeeList(appointment.attendees),
  }
}
```

Replace `sameState` (its KDoc and body):

```js
/**
 * Do two states show the invitee the same thing? Times as instants — the app
 * writes `+02:00`, Infomaniak answers `+02:00`, `toISOString` writes `Z` —
 * and addresses without regard to case.
 */
export function sameState(a, b) {
  if (!a || !b) return false
  return a.title === b.title
    && sameMoment(a.starts_at, b.starts_at)
    && sameMoment(a.ends_at, b.ends_at)
    && (a.location || null) === (b.location || null)
    && address(a.invite_email) === address(b.invite_email)
}
```

with:

```js
/**
 * Title, time and place the same? Times as instants — the app writes
 * `+02:00`, Infomaniak answers `+02:00`, `toISOString` writes `Z`.
 */
export function sameCore(a, b) {
  if (!a || !b) return false
  return a.title === b.title
    && sameMoment(a.starts_at, b.starts_at)
    && sameMoment(a.ends_at, b.ends_at)
    && (a.location || null) === (b.location || null)
}

/** The same attendees? As a set: order, case and whitespace do not count. */
export function sameAttendees(a, b) {
  const set = state => new Set(attendeeList(state?.attendees).map(entry => entry.toLowerCase()))
  const left = set(a)
  const right = set(b)
  return left.size === right.size && [...left].every(entry => right.has(entry))
}

/** Do two states show the attendees the same thing? */
export function sameState(a, b) {
  return sameCore(a, b) && sameAttendees(a, b)
}

/**
 * Whether sending [wanted] notifies the attendees. `notifyAttendees` is one flag
 * per API call, for the whole event — not per person.
 *
 * - Nobody on the list now, in the event Infomaniak holds ([held]) or in what
 *   was last sent ([pushed]): nobody to notify.
 * - Title, time or place differ from the event: everybody is notified — the
 *   user's rule, whatever was answered about the list.
 * - Otherwise only the list changed, or the event is being created ([held]
 *   null): notified unless the app saved the list without mail
 *   (`attendees_notify` = 0; NULL, from before migration 012, reads as 1).
 */
export function shouldNotify(wanted, held, pushed, flag) {
  const anybody = [wanted, held, pushed].some(state => attendeeList(state?.attendees).length > 0)
  if (!anybody) return false
  if (held && !sameCore(held, wanted)) return true
  return flag !== 0
}
```

Replace the function `attendees`:

```js
function attendees(inviteEmail) {
  if (!inviteEmail) return []
  return [
    { address: inviteEmail, className: 'Attendee', name: inviteEmail, organizer: false, state: 'NEEDS-ACTION' },
    { address: ORGANIZER.address, className: 'Attendee', name: ORGANIZER.name, organizer: true, state: 'ACCEPTED' },
  ]
}
```

with:

```js
/**
 * The attendees for a body. [held] is what Infomaniak holds: an address found
 * there keeps its answer (`state`), because a PUT replaces the whole event and
 * would otherwise reset every acceptance. A new address needs action.
 */
function attendees(list, held = []) {
  if (list.length === 0) return []
  const answered = entry => held.find(attendee =>
    !attendee.organizer && address(attendee.address) === entry.toLowerCase())?.state
  return [
    ...list.map(entry => ({
      address: entry, className: 'Attendee', name: entry, organizer: false, state: answered(entry) ?? 'NEEDS-ACTION',
    })),
    { address: ORGANIZER.address, className: 'Attendee', name: ORGANIZER.name, organizer: true, state: 'ACCEPTED' },
  ]
}
```

Replace the signature line and doc comment of `updateBody`

```js
/** The body of `PUT /event/{id}`. The API replaces, it does not patch: the whole event goes. */
export function updateBody(state, calendarId, eventId, notify) {
  return {
    ...createBody(state, calendarId),
```

with:

```js
/**
 * The body of `PUT /event/{id}`. The API replaces, it does not patch: the whole
 * event goes — the attendees' answers from [held] included.
 */
export function updateBody(state, calendarId, eventId, notify, held = []) {
  return {
    ...createBody(state, calendarId, false, held),
```

In `createBody`, replace the signature line and the two lines around `attendees`:

```js
/** The body of `POST /event`: the fields tested on 2026-09-15, no more. */
export function createBody(state, calendarId) {
```

with:

```js
/**
 * The body of `POST /event`: the fields tested on 2026-09-15, no more.
 * [notify] comes from [shouldNotify]; without it Infomaniak sends no mail.
 * [held] only for an update, see [attendees].
 */
export function createBody(state, calendarId, notify = false, held = []) {
```

and

```js
    attendees: attendees(state.invite_email),
  }
  if (state.invite_email) body.notifyAttendees = true
  return body
```

with:

```js
    attendees: attendees(state.attendees, held),
  }
  if (notify) body.notifyAttendees = true
  return body
```

Replace `eventState` (its KDoc and body):

```js
/**
 * What Infomaniak holds, from `GET /event/{id}`, in the shape of [wantedState],
 * trimmed the same way.
 *
 * The invitee is an attendee that is not the organizer — by flag and by
 * address: an organizer entry that comes back without `organizer: true` must
 * not look like an invitation to christoph@bauer-ki.de.
 */
export function eventState(event) {
  const invitee = (event.attendees ?? []).find(attendee =>
    !attendee.organizer && address(attendee.address) !== ORGANIZER.address)
  return {
    title: text(event.title),
    starts_at: event.start,
    ends_at: event.end,
    location: text(event.location),
    invite_email: invitee?.address ?? null,
  }
}
```

with:

```js
/**
 * What Infomaniak holds, from `GET /event/{id}`, in the shape of [wantedState],
 * trimmed the same way.
 *
 * The attendees are every attendee that is not the organizer — by flag and by
 * address: an organizer entry that comes back without `organizer: true` must
 * not look like an invitation to christoph@bauer-ki.de.
 */
export function eventState(event) {
  return {
    title: text(event.title),
    starts_at: event.start,
    ends_at: event.end,
    location: text(event.location),
    attendees: attendeeList((event.attendees ?? [])
      .filter(attendee => !attendee.organizer && address(attendee.address) !== ORGANIZER.address)
      .map(attendee => attendee.address)),
  }
}
```

- [ ] **Step 6: `pushCalendar.js`**

In `src/pushCalendar.js`:

Replace `import { createBody, eventState, sameState, uidFromIcs, updateBody } from './visitState.js'` with:

```js
import { createBody, eventState, sameState, shouldNotify, uidFromIcs, updateBody } from './visitState.js'
```

Replace

```js
async function create(db, client, job, state) {
  const id = job.appointment_id
  const eventId = await client.createEvent(createBody(state, client.calendarId))
```

with:

```js
async function create(db, client, job, appointment, state) {
  const id = job.appointment_id
  // Nobody held yet: the attendees are notified unless the list was saved without mail.
  const notify = shouldNotify(state, null, null, appointment.attendees_notify)
  const eventId = await client.createEvent(createBody(state, client.calendarId, notify))
```

Replace

```js
  const current = eventState(await client.getEvent(job.event_id))
```

with:

```js
  const event = await client.getEvent(job.event_id)
  const current = eventState(event)
```

Replace

```js
  if (!sameState(current, state)) {
    const before = JSON.parse(job.pushed ?? 'null')?.invite_email || current.invite_email
    // Removing or replacing the address notifies too: the old invitee has to hear of it.
    const notify = Boolean(state.invite_email || before)
    await client.updateEvent(job.event_id, updateBody(state, client.calendarId, job.event_id, notify))
  }
```

with:

```js
  if (!sameState(current, state)) {
    // Removed attendees count too: they are in the event or in what was last sent.
    const notify = shouldNotify(state, current, JSON.parse(job.pushed ?? 'null'), appointment.attendees_notify)
    // The answers attendees gave stay: see visitState's attendees.
    await client.updateEvent(job.event_id, updateBody(state, client.calendarId, job.event_id, notify, event?.attendees ?? []))
  }
```

In `work`, replace `if (job.pending === 'create' && job.event_id === null) return create(db, client, job, state)` with:

```js
  if (job.pending === 'create' && job.event_id === null) return create(db, client, job, appointment, state)
```

- [ ] **Step 7: `receive.js`**

In `src/receive.js`, in `fillGaps`, replace

```js
  const gaps = writableColumns(db, table)
    .filter(column => existing[column] === null && (row[column] ?? null) !== null)
  if (gaps.length === 0) return gaps
```

with:

```js
  let gaps = writableColumns(db, table)
    .filter(column => existing[column] === null && (row[column] ?? null) !== null)
  if (table === 'appointments') gaps = pairAttendees(gaps, row)
  if (gaps.length === 0) return gaps
```

and add, directly above the KDoc of `fillGaps`:

```js
/**
 * A visit's `attendees` and `attendees_notify` are one decision of one save:
 * whether a change to that list notifies. A standstill must not pair a list
 * with another save's decision, so `attendees_notify` is filled only in the
 * same fill as `attendees` — and then taken from the incoming row, whatever is
 * stored.
 */
function pairAttendees(gaps, row) {
  const rest = gaps.filter(column => column !== 'attendees_notify')
  return rest.includes('attendees') && Object.hasOwn(row, 'attendees_notify') ? [...rest, 'attendees_notify'] : rest
}
```

Replace

```js
 * The columns after whose change a visit is looked at again: what the invitee
 * sees, and the kind, which decides whether there is an event at all. A
 * standstill that fills only a note or a contact queues nothing — a visit
 * stored before migration 010 must not be created in the calendar because a
 * phone uploaded it again.
 */
const CALENDAR_COLUMNS = new Set(['title', 'starts_at', 'ends_at', 'location', 'invite_email', 'kind'])
```

with:

```js
 * The columns after whose change a visit is looked at again: what the
 * attendees see, and the kind, which decides whether there is an event at all.
 * A standstill that fills only a note or a contact queues nothing — a visit
 * stored before migration 010 must not be created in the calendar because a
 * phone uploaded it again. `attendees_notify` alone queues nothing: it only
 * says how a change to the list goes out. `invite_email` is read no more.
 */
const CALENDAR_COLUMNS = new Set(['title', 'starts_at', 'ends_at', 'location', 'attendees', 'kind'])
```

In `receiveBusiness`, replace its last lines

```js
  write(db, 'businesses', blocked ? { ...row, status: 'do_not_call' } : row, nextSequence(db))
}
```

with:

```js
  write(db, 'businesses', blocked ? { ...row, status: 'do_not_call' } : row, nextSequence(db))
  if (existing && Object.hasOwn(row, 'name') && row.name !== existing.name) queueRenamedVisits(db, row.place_id)
}
```

and add directly above `function receiveBusiness(db, row) {`:

```js
/**
 * A visit without a title of its own shows the business's name in its default
 * title (visitState's defaultTitle). Renaming the business changes that title
 * without touching the visit, so its event would keep the old one — and the
 * next change to the attendees alone, saved without mail, would find the title
 * different and notify everybody. A rename therefore queues those visits like
 * any change of title, and the attendees hear of it.
 *
 * Only visits the calendar already has (a queue row): one stored before
 * migration 010 must not be created because its business was renamed.
 * queueVisit finds nothing to send where the trimmed name did not change.
 */
function queueRenamedVisits(db, placeId) {
  const visits = db.prepare(
    `SELECT a.id FROM appointments a JOIN infomaniak_events e ON e.appointment_id = a.id
     WHERE a.place_id = ? AND (a.kind IS NULL OR a.kind = 'visit') AND trim(COALESCE(a.title, '')) = ''`
  ).all(placeId)
  for (const { id } of visits) queueVisit(db, id)
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `node --test test/visitState.test.js test/pushCalendar.test.js test/receive.test.js`
Expected: PASS.

Run: `node --test`
Expected: `# fail 0`.

Run: `grep -rn "\.invite_email\|invite_email:" src`
Expected: no output. (`invite_email` still appears in comments that say it is read no more.)

- [ ] **Step 9: Commit**

```bash
git add src/visitState.js src/pushCalendar.js src/receive.js test/visitState.test.js test/pushCalendar.test.js test/receive.test.js
git commit -m "Teilnehmende statt Einladungsadresse: Vergleich als Menge, Benachrichtigung nach attendees_notify"
```

---

### Task 3: Server — no mail after migration 012, README

**Repository:** server.

**Files:**
- Test: `test/db.test.js` (import; one test after `migration 012 rewrites what Infomaniak last received from invite_email to attendees`)
- Modify: `README.md` (section „Migrationen": the paragraphs „**Besuchstermine gehen über die Infomaniak-API in den Kalender.**", „`infomaniak_events` ist die Warteschlange …" and „Was beim Eingeladenen ankommt …")

**Interfaces:** none new.

- [ ] **Step 1: Write the test**

In `test/db.test.js`, add the import after `import { open, migrate } from '../src/db.js'`:

```js
import { receive } from '../src/receive.js'
```

After the test `migration 012 rewrites what Infomaniak last received from invite_email to attendees`, add:

```js
test('after migration 012 an invited visit uploaded again by the new app queues no job and no mail', () => {
  const db = databaseBefore012()
  migrate(db, 'db/migrations')

  // What an app on schema 10 sends after changing only the note: the carried-over
  // attendees, no notify decision, and the old column it still holds.
  receive(db, { appointments: [{
    id: 'T1', place_id: 'P1', kind: 'visit', done_at: null,
    starts_at: '2026-09-10T14:00:00+02:00', ends_at: '2026-09-10T15:00:00+02:00',
    location: 'Zehentstraße 39', note: 'Chef heißt Huber', contact_id: null, title: null,
    invite_email: ' test@example.org ', attendees: '["test@example.org"]', attendees_notify: null,
    event_uid: 'uid-1', updated_at: '2026-09-07T11:00:00+02:00',
  }] })

  const job = { ...db.prepare("SELECT pending, generation FROM infomaniak_events WHERE appointment_id = 'T1'").get() }
  assert.deepEqual(job, { pending: null, generation: 1 })
  assert.equal(db.prepare("SELECT calendar_state FROM appointments WHERE id = 'T1'").get().calendar_state, 'ok')
})
```

- [ ] **Step 2: Run it**

Run: `node --test test/db.test.js`
Expected: PASS. (It passes because Task 1 rewrote `pushed` and Task 2 compares attendees; run it once with the first `UPDATE infomaniak_events` statement of Task 1's migration removed to see it fail with `pending: 'update'`, then restore the file — `git diff db/migrations` must show nothing afterwards.)

- [ ] **Step 3: README**

In `README.md`, section „Migrationen", replace the paragraph that begins „**Besuchstermine gehen über die Infomaniak-API in den Kalender.**" and ends „… wird verworfen. Schreibt der Server sie, bekommt die Zeile eine neue `server_seq`, `updated_at` bleibt." with:

```markdown
**Besuchstermine gehen über die Infomaniak-API in den Kalender.** Rückrufe
schreibt weiter die App in den Gerätekalender (DAVx⁵). Einen Besuch (`kind`
`'visit'` oder `NULL`) legt der Server über die API an, ändert und löscht ihn —
nur so verschickt Infomaniak echte Einladungen. `appointments.title`,
`attendees` und `attendees_notify` kommen von der App: `title` `NULL` heißt
Standardtitel, `attendees` ist eine JSON-Liste der Teilnehmenden (`NULL` für
niemanden), `attendees_notify` sagt, ob die letzte Änderung dieser Liste
benachrichtigt (1, 0 nach „Ohne Mail speichern", `NULL` gilt als 1). Die App
schreibt `attendees_notify` nur, wenn sie die Liste ändert; beim Stillstand wird
es nur zusammen mit `attendees` aufgefüllt. `invite_email` ist seit Migration 012
stillgelegt: Migration 012 hat jede Einladung als einzigen Eintrag nach
`attendees` übernommen und `infomaniak_events.pushed` entsprechend
umgeschrieben. `calendar_state` (`pending`, `ok`, `error`), `calendar_error` und
bei Besuchen `event_uid` gehören dem Server: Was eine App dafür schickt, wird
verworfen. Schreibt der Server sie, bekommt die Zeile eine neue `server_seq`,
`updated_at` bleibt.
```

In the paragraph that begins „`infomaniak_events` ist die Warteschlange dazu", replace the sentence

```markdown
`receive.js` stellt nur ein, wenn sich Titel, Zeit, Ort, Einladung oder
Art geändert haben — eine geänderte Notiz verschickt keine Mail.
```

with:

```markdown
`receive.js` stellt nur ein, wenn sich Titel, Zeit, Ort, Teilnehmende oder
Art geändert haben — eine geänderte Notiz verschickt keine Mail. Teilnehmende
werden als Menge verglichen (getrimmt, ohne Groß-/Kleinschreibung, Reihenfolge
egal); der Organisator zählt nicht mit.
```

Replace the paragraph that begins „Was beim Eingeladenen ankommt (am Telefon geprüft 2026-09-15):" and ends „… solange etwas offen ist." with:

```markdown
Wann benachrichtigt wird: `notifyAttendees` gilt pro API-Aufruf für das ganze
Ereignis, nicht pro Person. Haben sich Titel, Zeit oder Ort gegenüber dem
Stand bei Infomaniak geändert, werden alle Teilnehmenden benachrichtigt, auch
still eingetragene. Hat sich nur die Liste geändert (oder wird das Ereignis
angelegt), wird benachrichtigt, außer `attendees_notify` ist 0. Steht niemand
auf der Liste — jetzt, bei Infomaniak oder im zuletzt Gesendeten —, wird nicht
benachrichtigt. Zwei Speichervorgänge, die vor dem nächsten Lauf ankommen,
gehen als ein Aufruf raus; es gilt die zuletzt gespeicherte Entscheidung.
Wurde der Besuch im Web-Kalender verschoben und hat die App das noch nicht
übernommen, weicht die Zeit vom Stand bei Infomaniak ab: Eine reine
Listenänderung mit „Ohne Mail speichern" schickt dann trotzdem Mail an alle.
Wird ein Betrieb umbenannt, stellt der Server seine angelegten Besuche mit
Standardtitel ein; die Teilnehmenden bekommen die Aktualisierung. Bei jeder
Aktualisierung behält jede Adresse ihre Antwort aus dem Kalender (`state`), nur
neue Adressen stehen auf `NEEDS-ACTION`.

Was bei Teilnehmenden ankommt (am Telefon geprüft 2026-09-15 mit einer
Adresse): Ändern mit Benachrichtigung schickt „Veranstaltung aktualisiert".
Wer mit Benachrichtigung von der Liste genommen wird, bekommt „Veranstaltung
gelöscht" (METHOD:CANCEL) — so sagt ein Kalender „nicht mehr eingeladen", der
Termin selbst bleibt. Wird der Besuch gelöscht, bekommen alle Teilnehmenden
„Veranstaltung gelöscht". Ob beim Hinzufügen einer weiteren Person auch die
schon Eingetragenen eine Aktualisierung bekommen, wird beim Handy-Test von
Paket 2 geprüft und hier nachgetragen. Abgearbeitet wird nach jeder
Sync-Antwort, beim Start und alle zehn Minuten, solange etwas offen ist.
```

- [ ] **Step 4: Run everything**

Run: `node --test`
Expected: `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add test/db.test.js README.md
git commit -m "Nach Migration 012 keine Mail; README: Teilnehmende und wann benachrichtigt wird"
```

---

### Task 4: App — the rules for the attendee list

**Repository:** app.

**Files:**
- Create: `…/data/Attendees.kt`
- Test: `test/AttendeesTest.kt` (new)

**Interfaces:**
- Produces, package `io.github.amadeusb.callsheet.data`:

```kotlin
data class AttendeeAdd(val addresses: List<String>, val error: String?)

object Attendees {
    const val INVALID: String                     // „Das ist keine gültige E-Mail-Adresse."
    const val ORGANIZER: String                   // the calendar account's address
    const val OWN_ADDRESS: String                 // „Die eigene Adresse ist immer dabei."
    fun clean(addresses: List<String>): List<String>
    fun parse(text: String?): List<String>
    fun format(addresses: List<String>): String?  // null for none
    fun contains(addresses: List<String>, address: String): Boolean
    fun sameSet(a: List<String>, b: List<String>): Boolean
    fun added(before: List<String>, after: List<String>): List<String>
    fun removed(before: List<String>, after: List<String>): List<String>
    fun add(addresses: List<String>, typed: String): AttendeeAdd
    fun names(addresses: List<String>): String
}
```

- [ ] **Step 1: Write the failing test**

Create `test/AttendeesTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.AttendeeAdd
import io.github.amadeusb.callsheet.data.Attendees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A visit's attendees as a list. Robolectric only for `org.json`. Every address is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttendeesTest {

    @Test
    fun `the list is written as JSON and read back, none is null`() {
        val text = Attendees.format(listOf("test@example.org", "zweite@example.org"))

        assertEquals("[\"test@example.org\",\"zweite@example.org\"]", text)
        assertEquals(listOf("test@example.org", "zweite@example.org"), Attendees.parse(text))
        assertNull(Attendees.format(emptyList()))
        assertNull(Attendees.format(listOf("  ")))
    }

    @Test
    fun `reading drops blanks and duplicates, keeps the order and survives garbage`() {
        assertEquals(
            listOf("a@example.org", "b@example.org"),
            Attendees.parse("[\" a@example.org \", \"\", null, \"A@EXAMPLE.org\", \"b@example.org\"]"),
        )
        assertTrue(Attendees.parse(null).isEmpty())
        assertTrue(Attendees.parse("").isEmpty())
        assertTrue(Attendees.parse("kaputt").isEmpty())
    }

    @Test
    fun `an address is added trimmed, once, and only when it looks like one`() {
        val list = listOf("test@example.org")

        assertEquals(AttendeeAdd(listOf("test@example.org", "zweite@example.org"), null), Attendees.add(list, " zweite@example.org "))
        assertEquals(AttendeeAdd(list, null), Attendees.add(list, "TEST@example.org"))
        assertEquals(AttendeeAdd(list, null), Attendees.add(list, "   "))
        assertEquals(AttendeeAdd(list, Attendees.INVALID), Attendees.add(list, "zweite@example"))
        assertEquals(AttendeeAdd(list, Attendees.INVALID), Attendees.add(list, "zweite example@org.de"))
        assertEquals("Das ist keine gültige E-Mail-Adresse.", Attendees.INVALID)
        // The calendar account organises every visit; it is never an attendee.
        assertEquals(AttendeeAdd(list, Attendees.OWN_ADDRESS), Attendees.add(list, " Christoph@Bauer-KI.de "))
        assertEquals("Die eigene Adresse ist immer dabei.", Attendees.OWN_ADDRESS)
    }

    @Test
    fun `two lists are the same set whatever the order, case and whitespace`() {
        assertTrue(Attendees.sameSet(listOf("a@example.org", "b@example.org"), listOf(" B@example.org", "a@example.org")))
        assertFalse(Attendees.sameSet(listOf("a@example.org"), listOf("a@example.org", "b@example.org")))
        assertTrue(Attendees.sameSet(emptyList(), emptyList()))
        assertTrue(Attendees.contains(listOf("a@example.org"), " A@example.org "))
    }

    @Test
    fun `added and removed are told apart, ignoring case`() {
        val before = listOf("a@example.org", "b@example.org")
        val after = listOf("B@example.org", "c@example.org")

        assertEquals(listOf("c@example.org"), Attendees.added(before, after))
        assertEquals(listOf("a@example.org"), Attendees.removed(before, after))
    }

    @Test
    fun `names are listed up to three, after that shortened`() {
        assertEquals("a@example.org", Attendees.names(listOf("a@example.org")))
        assertEquals("a@example.org, b@example.org", Attendees.names(listOf("a@example.org", "b@example.org")))
        assertEquals(
            "a@example.org, b@example.org, c@example.org",
            Attendees.names(listOf("a@example.org", "b@example.org", "c@example.org")),
        )
        assertEquals(
            "a@example.org, b@example.org und 2 weitere",
            Attendees.names(listOf("a@example.org", "b@example.org", "c@example.org", "d@example.org")),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AttendeesTest"`
Expected: FAIL to compile — `Unresolved reference: Attendees`.

- [ ] **Step 3: Write the rules**

Create `…/data/Attendees.kt`:

```kotlin
package io.github.amadeusb.callsheet.data

import org.json.JSONArray

/** An address offered to a visit's attendees: the list afterwards, and why it was refused, if it was. */
data class AttendeeAdd(val addresses: List<String>, val error: String?)

/**
 * A visit's attendees (`appointments.attendees`): addresses, trimmed, in the
 * order added, each once ignoring case. Stored as a JSON array, none as null.
 * The server reads the same list and compares it as a set.
 */
object Attendees {

    /** Said under the field when a typed address is refused. */
    const val INVALID: String = "Das ist keine gültige E-Mail-Adresse."

    /**
     * The calendar account every visit is organised by — the address the
     * server's visitState calls ORGANIZER, and the name in the default title.
     */
    const val ORGANIZER: String = "christoph@bauer-ki.de"

    /** Said when [ORGANIZER] is offered as an attendee: it organises every visit anyway. */
    const val OWN_ADDRESS: String = "Die eigene Adresse ist immer dabei."

    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

    /** Trimmed, blanks dropped, the first spelling of each address kept. */
    fun clean(addresses: List<String>): List<String> {
        val result = ArrayList<String>(addresses.size)
        for (raw in addresses) {
            val address = raw.trim()
            if (address.isEmpty() || contains(result, address)) continue
            result.add(address)
        }
        return result
    }

    /** The stored text. Unreadable text reads as nobody. */
    fun parse(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            clean((0 until array.length()).mapNotNull { i -> if (array.isNull(i)) null else array.optString(i, "") })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** What is stored. Null for nobody. */
    fun format(addresses: List<String>): String? =
        clean(addresses).takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }

    fun contains(addresses: List<String>, address: String): Boolean =
        addresses.any { it.trim().equals(address.trim(), ignoreCase = true) }

    /** The same people, whatever the order, case or whitespace. */
    fun sameSet(a: List<String>, b: List<String>): Boolean {
        val left = clean(a)
        val right = clean(b)
        return left.size == right.size && left.all { contains(right, it) }
    }

    /** In [after] and not in [before]. */
    fun added(before: List<String>, after: List<String>): List<String> = clean(after).filterNot { contains(before, it) }

    /** In [before] and not in [after]. */
    fun removed(before: List<String>, after: List<String>): List<String> = clean(before).filterNot { contains(after, it) }

    /**
     * [typed] added to [addresses]. Blank, or already there, changes nothing
     * and is no error; the organizer's own address is refused with
     * [OWN_ADDRESS], something that is not an address with [INVALID].
     */
    fun add(addresses: List<String>, typed: String): AttendeeAdd {
        val address = typed.trim()
        if (address.isEmpty() || contains(addresses, address)) return AttendeeAdd(addresses, null)
        if (address.equals(ORGANIZER, ignoreCase = true)) return AttendeeAdd(addresses, OWN_ADDRESS)
        if (!EMAIL.matches(address)) return AttendeeAdd(addresses, INVALID)
        return AttendeeAdd(addresses + address, null)
    }

    /** „a", „a, b", „a, b, c", and past three „a, b und 2 weitere" — a dialog title stays readable. */
    fun names(addresses: List<String>): String {
        if (addresses.size <= 3) return addresses.joinToString(", ")
        return "${addresses.take(2).joinToString(", ")} und ${addresses.size - 2} weitere"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AttendeesTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Attendees.kt app/src/test/java/io/github/amadeusb/callsheet/AttendeesTest.kt
git commit -m "Regeln für die Teilnehmerliste: lesen, schreiben, hinzufügen, vergleichen, Namen"
```

---

### Task 5: App — schema 10, the columns travel as one unit, refetch

**Repository:** app.

**Files:**
- Modify: `…/data/Database.kt` (`onCreate` after `db.execSQL(COLUMN_BUSINESSES_9)`; `onUpgrade` after the `if (old < 9)` block; `VERSION`; companion after `COLUMN_BUSINESSES_9`; a private function after `onDowngrade`)
- Modify: `…/sync/SyncStore.kt` (`fillGaps`)
- Modify: `…/contacts/Preferences.kt` (after `refetchedForEditedFields`; constants)
- Modify: `…/sync/SyncEngine.kt` (after the `refetchedForEditedFields` block)
- Test: `test/MigrationTest.kt`, `test/SyncSchemaTest.kt`, `test/SyncStoreTest.kt`, `test/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `Attendees.format` (Task 4).
- Produces: columns `appointments.attendees`, `appointments.attendees_notify`; `Preferences.refetchedForAttendees: Boolean`.

- [ ] **Step 1: Write the failing tests**

In `test/MigrationTest.kt`, after `createVersionEight()`:

```kotlin
    /**
     * The version 9 schema, as package 1 builds it: version 8 with a business's
     * hand-edited fields. alt-1's visit invites somebody, as a 1.5.0 phone saved
     * it — with the whitespace the server keeps in `invite_email` too.
     */
    private fun createVersionNine() {
        createVersionEight()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL("ALTER TABLE businesses ADD COLUMN edited_fields TEXT")
        db.execSQL("UPDATE appointments SET invite_email = ' test@example.org ' WHERE id = 'legacy-alt-1'")
        db.version = 9
        db.close()
    }
```

and after the test `an upgrade from version eight adds edited_fields empty and leaves the businesses unmarked`:

```kotlin
    // --- from version 9, the road package 1 devices are on --------------------

    @Test
    fun `an upgrade from version nine carries the invitation over as the only attendee and marks nothing`() {
        createVersionNine()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT attendees, attendees_notify, invite_email, dirty FROM appointments WHERE id = 'legacy-alt-1'", null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[\"test@example.org\"]", c.getString(0))
            assertTrue(c.isNull(1))
            // Kept, as the old address columns were.
            assertEquals(" test@example.org ", c.getString(2))
            assertEquals(0, c.getInt(3))
        }
        db.rawQuery("SELECT COUNT(*) FROM appointments WHERE attendees IS NOT NULL", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
```

In `test/SyncSchemaTest.kt`, add:

```kotlin
    @Test
    fun `appointments carry their attendees and whether changing them notifies`() {
        val appointments = columns("appointments")
        assertTrue(appointments.contains("attendees"))
        assertTrue(appointments.contains("attendees_notify"))
    }
```

In `test/SyncStoreTest.kt`, after the two tests package 1 added for `edited_fields`:

```kotlin
    @Test
    fun `a standstill fills a visit's attendees and their notify decision together`() {
        // Everybody removed here without mail; the same save elsewhere still carried the list.
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe("UPDATE appointments SET attendees_notify = 0 WHERE id = 'A1'")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00").put("kind", "visit")
            .put("attendees", "[\"test@example.org\"]").put("attendees_notify", 1)

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(listOf("[\"test@example.org\"]", "1"), zeile("SELECT attendees, attendees_notify FROM appointments WHERE id = 'A1'"))
    }

    @Test
    fun `a standstill never fills the notify decision without the attendees`() {
        einBesuch("A1", "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe("UPDATE appointments SET attendees = '[\"test@example.org\"]' WHERE id = 'A1'")
        val incoming = terminJson("A1", "2026-09-07T10:00:00+02:00").put("kind", "visit")
            .put("attendees", "[\"zweite@example.org\"]").put("attendees_notify", 0)

        store.apply(leereAntwort().put("appointments", JSONArray(listOf(incoming))))

        assertEquals(listOf("[\"test@example.org\"]", null), zeile("SELECT attendees, attendees_notify FROM appointments WHERE id = 'A1'"))
    }
```

In `test/SyncEngineTest.kt`, after `the first sync after the edited-fields update starts from watermark zero, and only that one`:

```kotlin
    @Test
    fun `the first sync after the attendees update starts from watermark zero, and only that one`() {
        prefs.watermark = 42
        prefs.refetchedForAppointments = true
        prefs.refetchedForCallbacks = true
        prefs.refetchedForAddresses = true
        prefs.refetchedForEditedFields = true
        prefs.refetchedForAttendees = false
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
```

and in `a device that has fetched everything since keeps its watermark`, after `prefs.refetchedForEditedFields = true`, add:

```kotlin
        prefs.refetchedForAttendees = true
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: FAIL to compile — `Unresolved reference: refetchedForAttendees`. The other classes cannot run until Step 5; that is expected.

- [ ] **Step 3: Schema 10**

In `…/data/Database.kt`, companion object, after `COLUMN_BUSINESSES_9`:

```kotlin
        /**
         * Schema 10: a visit's attendees (a JSON array, see Attendees) and
         * whether the last change to them notifies (1/0/NULL, NULL read as 1).
         * Both synchronised and nullable; a standstill fills them together
         * (SyncStore.fillGaps). Added by ALTER on both roads, for the reason
         * COLUMNS_APPOINTMENTS_6 gives. `invite_email` stays and is read by
         * nothing after the switch.
         */
        private val COLUMNS_APPOINTMENTS_10 = listOf(
            "ALTER TABLE appointments ADD COLUMN attendees TEXT",
            "ALTER TABLE appointments ADD COLUMN attendees_notify INTEGER",
        )
```

In `onCreate`, after `db.execSQL(COLUMN_BUSINESSES_9)`:

```kotlin
        for (sql in COLUMNS_APPOINTMENTS_10) db.execSQL(sql)
```

In `onUpgrade`, after the `if (old < 9) { … }` block:

```kotlin
        if (old < 10) {
            for (sql in COLUMNS_APPOINTMENTS_10) db.execSQL(sql)
            carryInvitationsOver(db)
        }
```

After the function `onDowngrade` (before `companion object`):

```kotlin
    /**
     * Schema 10: a visit's single invitee becomes its only attendee — the value
     * the server's migration 012 writes, so the rows need not travel and are
     * not marked. Read first and written after: no update runs while the
     * cursor is open.
     */
    private fun carryInvitationsOver(db: SQLiteDatabase) {
        val invited = db.rawQuery(
            "SELECT id, invite_email FROM appointments WHERE TRIM(COALESCE(invite_email, '')) <> ''", null,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toList() }
        for ((id, email) in invited) {
            db.execSQL("UPDATE appointments SET attendees = ? WHERE id = ?", arrayOf(Attendees.format(listOf(email)), id))
        }
    }
```

Change `const val VERSION = 9` to:

```kotlin
        const val VERSION = 10
```

- [ ] **Step 4: The pair at a standstill**

In `…/sync/SyncStore.kt`, `fillGaps`, replace

```kotlin
        val gaps = Rows.toValues(row, tableColumns)
        for (name in gaps.keySet().toList()) {
            if (!local.isNull(name) || gaps.get(name) == null) gaps.remove(name)
        }
        if (gaps.size() == 0) return false
```

with:

```kotlin
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
```

In the KDoc of `fillGaps`, after the paragraph that ends „… and the row stays unmarked.", add:

```kotlin
     *
     * One exception to column by column: `attendees_notify` only together with
     * `attendees`, see the comment in the body.
```

- [ ] **Step 5: The refetch**

In `…/contacts/Preferences.kt`, after the property `refetchedForEditedFields`:

```kotlin
    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about attendees (schema 10).
     *
     * While it ran the version before, the server delivered visits whose
     * `attendees` another device had set, and the old app stored them without
     * the column while its watermark moved past. After the update those visits
     * would show nobody, and the next save would send the empty list up as the
     * newer row. One fetch from zero brings each row again at a standstill, and
     * the store fills the gap. A flag for the reason [refetchedForAppointments]
     * gives.
     */
    var refetchedForAttendees: Boolean
        get() = store.getBoolean(REFETCHED_FOR_ATTENDEES, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_ATTENDEES, value).apply()
```

In its companion, after `const val REFETCHED_FOR_EDITED_FIELDS = "refetched_for_edited_fields"`:

```kotlin
        const val REFETCHED_FOR_ATTENDEES = "refetched_for_attendees"
```

In `…/sync/SyncEngine.kt`, `sync`, after the block

```kotlin
            if (!prefs.refetchedForEditedFields) {
                prefs.watermark = 0
                prefs.refetchedForEditedFields = true
            }
```

add:

```kotlin
            // Once more, on the first sync that runs on schema 10 — see
            // Preferences.refetchedForAttendees. Shipped with schema 9, both
            // flags reset the watermark in this same sync: one download.
            if (!prefs.refetchedForAttendees) {
                prefs.watermark = 0
                prefs.refetchedForAttendees = true
            }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest" --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncStore.kt app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncEngineTest.kt
git commit -m "Schema 10: Teilnehmende und attendees_notify, beim Stillstand nur gemeinsam, einmal alles neu holen"
```

---

### Task 6: App — attendees on the entry, the rules for the question

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt` (`AppointmentEntry`, after `inviteEmail`)
- Modify: `…/data/Repository.kt` (`saveAppointment`; `appointmentFromCursor`)
- Modify: `…/calling/Appointment.kt` (import; `AttendeeQuestion` after `CalendarLine`; new functions after `removedHint`; `calendarLine`; `cancellationNotice`)
- Test: `test/RepositoryTest.kt` (two tests after `switching the invitation off clears the address`), `test/AppointmentTest.kt`

**Interfaces:**
- Consumes: `Attendees` (Task 4), the columns (Task 5).
- Produces:
  - `AppointmentEntry.attendees: List<String> = emptyList()`, `AppointmentEntry.attendeesNotify: Boolean? = null`.
  - In `calling/Appointment.kt`: `data class AttendeeQuestion(val added: List<String>, val removed: List<String>)`; `Appointment.attendeeQuestion(before: AppointmentEntry?, after: AppointmentEntry, businessName: String): AttendeeQuestion?`; `Appointment.attendeeQuestionTitle(question: AttendeeQuestion): String`; `Appointment.attendeesNotifyToStore(before: AppointmentEntry?, after: AppointmentEntry, question: AttendeeQuestion?, send: Boolean?): Boolean?`.
  - `calendarLine` and `cancellationNotice` read `attendees`. The invitation (`inviteEmail`, `invite*` functions) still exists until Task 7.

- [ ] **Step 1: Write the failing tests**

In `test/RepositoryTest.kt`, after the test `switching the invitation off clears the address`:

```kotlin
    @Test
    fun `a visit stores its attendees and the notify decision, a callback neither`() = runTest {
        repo.saveAppointment(
            visit("A-1", "t-1", "2026-09-16T09:00:00+02:00")
                .copy(attendees = listOf("test@example.org", "zweite@example.org"), attendeesNotify = false)
        )
        repo.saveAppointment(
            callback("R-1", "t-1", "2026-09-15T09:00:00+02:00").copy(attendees = listOf("test@example.org"), attendeesNotify = true)
        )

        val visit = repo.appointment("A-1")!!
        assertEquals(listOf("test@example.org", "zweite@example.org"), visit.attendees)
        assertEquals(false, visit.attendeesNotify)
        assertTrue(repo.appointment("R-1")!!.attendees.isEmpty())
        assertNull(repo.appointment("R-1")!!.attendeesNotify)
    }

    @Test
    fun `saving without a notify decision keeps the stored one, nobody is stored as none`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00").copy(attendees = listOf("test@example.org"), attendeesNotify = false))

        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00").copy(attendees = emptyList(), attendeesNotify = null))

        val visit = repo.appointment("A-1")!!
        assertTrue(visit.attendees.isEmpty())
        assertEquals(false, visit.attendeesNotify)
        assertEquals(1, count("SELECT COUNT(*) FROM appointments WHERE id = 'A-1' AND attendees IS NULL"))
    }
```

In `test/AppointmentTest.kt`:

Add the import `import io.github.amadeusb.callsheet.calling.AttendeeQuestion`.

In the test `a visit says where it stands on its way into the calendar`, replace

```kotlin
        assertEquals(
            CalendarLine("Im Kalender · Eingeladen: test@example.org"),
            Appointment.calendarLine(
                visit.copy(eventUid = "abc", calendarState = CalendarState.OK, inviteEmail = "test@example.org"), syncConfigured = true,
            ),
        )
```

with:

```kotlin
        assertEquals(
            CalendarLine("Im Kalender · Teilnehmende: test@example.org, zweite@example.org"),
            Appointment.calendarLine(
                visit.copy(eventUid = "abc", calendarState = CalendarState.OK, attendees = listOf("test@example.org", "zweite@example.org")),
                syncConfigured = true,
            ),
        )
```

In the tests `an invited visit missing from the calendar offers its removal instead of a state` and `removing a visit missing from the calendar asks only when someone gets a cancellation`, replace every `inviteEmail = "test@example.org"` with `attendees = listOf("test@example.org")`.

Replace the test `removing an invited visit says who gets a cancellation` with:

```kotlin
    @Test
    fun `removing a visit with attendees says who gets a cancellation`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertEquals("test@example.org bekommt eine Absage.", Appointment.cancellationNotice(visit.copy(attendees = listOf("test@example.org"))))
        assertEquals(
            "test@example.org, zweite@example.org bekommen eine Absage.",
            Appointment.cancellationNotice(visit.copy(attendees = listOf("test@example.org", "zweite@example.org"))),
        )
        assertNull(Appointment.cancellationNotice(visit))
        assertNull(Appointment.cancellationNotice(visit.copy(kind = AppointmentKind.CALLBACK, attendees = listOf("test@example.org"))))
    }
```

At the end of the class, before its closing `}`, add:

```kotlin
    // --- attendees: the question on saving ------------------------------------

    private val storedVisit = entry("A-1", "2026-09-10T14:00:00+02:00", "2026-09-10T15:00:00+02:00")
        .copy(location = "Zehentstraße 39", attendees = listOf("test@example.org"))
    private val withSecond = storedVisit.copy(attendees = listOf("test@example.org", "zweite@example.org"))

    @Test
    fun `a new visit with attendees asks about mail to them, one without asks nothing`() {
        assertEquals(AttendeeQuestion(listOf("test@example.org"), emptyList()), Appointment.attendeeQuestion(null, storedVisit, "Elektro Meier"))
        assertNull(Appointment.attendeeQuestion(null, storedVisit.copy(attendees = emptyList()), "Elektro Meier"))
    }

    @Test
    fun `a change to the attendees alone asks about the added and the removed`() {
        val after = storedVisit.copy(attendees = listOf("zweite@example.org"), note = "Angebot", contactId = "K-1")

        assertEquals(
            AttendeeQuestion(listOf("zweite@example.org"), listOf("test@example.org")),
            Appointment.attendeeQuestion(storedVisit, after, "Elektro Meier"),
        )
    }

    @Test
    fun `the same attendees in another spelling or order are no change`() {
        val before = storedVisit.copy(attendees = listOf("a@example.org", "b@example.org"))

        assertNull(Appointment.attendeeQuestion(before, before.copy(attendees = listOf(" B@example.org", "a@example.org")), "Elektro Meier"))
        assertNull(Appointment.attendeeQuestion(storedVisit, storedVisit.copy(note = "Angebot"), "Elektro Meier"))
    }

    @Test
    fun `with title, time or place changed nothing is asked, everybody is notified`() {
        val name = "Elektro Meier"
        assertNull(Appointment.attendeeQuestion(storedVisit, withSecond.copy(startsAt = "2026-09-10T16:00:00+02:00"), name))
        assertNull(Appointment.attendeeQuestion(storedVisit, withSecond.copy(endsAt = "2026-09-10T16:00:00+02:00"), name))
        assertNull(Appointment.attendeeQuestion(storedVisit, withSecond.copy(location = "Am Pulverl 5"), name))
        assertNull(Appointment.attendeeQuestion(storedVisit, withSecond.copy(title = "Angebot besprechen"), name))
        // The preset title written out, and the same instant with another offset, are no change.
        val onlySecond = AttendeeQuestion(listOf("zweite@example.org"), emptyList())
        assertEquals(onlySecond, Appointment.attendeeQuestion(storedVisit, withSecond.copy(title = "Erstgespräch KI bei Elektro Meier – Christoph Bauer"), name))
        assertEquals(onlySecond, Appointment.attendeeQuestion(storedVisit, withSecond.copy(startsAt = "2026-09-10T12:00:00Z"), name))
    }

    @Test
    fun `a callback never asks`() {
        assertNull(Appointment.attendeeQuestion(null, storedVisit.copy(kind = AppointmentKind.CALLBACK), "Elektro Meier"))
    }

    @Test
    fun `the question names the added and the removed, shortened past three`() {
        assertEquals("Mail an a@example.org senden?", Appointment.attendeeQuestionTitle(AttendeeQuestion(listOf("a@example.org"), emptyList())))
        assertEquals("Absage an c@example.org senden?", Appointment.attendeeQuestionTitle(AttendeeQuestion(emptyList(), listOf("c@example.org"))))
        assertEquals(
            "Mail an a@example.org, b@example.org und Absage an c@example.org senden?",
            Appointment.attendeeQuestionTitle(AttendeeQuestion(listOf("a@example.org", "b@example.org"), listOf("c@example.org"))),
        )
        assertEquals(
            "Mail an a@example.org, b@example.org und 2 weitere senden?",
            Appointment.attendeeQuestionTitle(
                AttendeeQuestion(listOf("a@example.org", "b@example.org", "c@example.org", "d@example.org"), emptyList())
            ),
        )
    }

    @Test
    fun `the notify decision is stored only when the attendees changed`() {
        val question = AttendeeQuestion(listOf("zweite@example.org"), emptyList())

        assertNull(Appointment.attendeesNotifyToStore(storedVisit, storedVisit.copy(note = "Angebot"), null, null))
        assertEquals(false, Appointment.attendeesNotifyToStore(storedVisit, withSecond, question, send = false))
        assertEquals(true, Appointment.attendeesNotifyToStore(storedVisit, withSecond, question, send = true))
        // Changed together with the time: not asked, everybody hears of it.
        assertEquals(true, Appointment.attendeesNotifyToStore(storedVisit, withSecond.copy(startsAt = "2026-09-10T16:00:00+02:00"), null, null))
        assertNull(Appointment.attendeesNotifyToStore(null, storedVisit.copy(kind = AppointmentKind.CALLBACK), null, null))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile — `Unresolved reference: attendees`, `AttendeeQuestion`, `attendeeQuestion`.

- [ ] **Step 3: The entry and the repository**

In `…/data/Models.kt`, `AppointmentEntry`, after

```kotlin
    /** Who a visit invites. Null means no invitation. Always null for a callback. */
    val inviteEmail: String? = null,
```

add:

```kotlin
    /** Who a visit invites, in the order added (see Attendees). Empty for nobody; always empty for a callback. */
    val attendees: List<String> = emptyList(),
    /**
     * Whether the last change to [attendees] notifies them (`attendees_notify`):
     * set only in a save that changes the list (Appointment.attendeesNotifyToStore).
     * Null when saving leaves the stored value as it is; read as stored.
     */
    val attendeesNotify: Boolean? = null,
```

In `…/data/Repository.kt`, `saveAppointment`, after the line `put("invite_email", if (entry.kind == AppointmentKind.CALLBACK) null else entry.inviteEmail)`:

```kotlin
            // Nobody is invited to a phone call. An empty list is stored as none.
            put("attendees", if (entry.kind == AppointmentKind.CALLBACK) null else Attendees.format(entry.attendees))
            // Only in a save that changed the list; otherwise the decision stored
            // for the last change stays. See Appointment.attendeesNotifyToStore.
            val notify = entry.attendeesNotify
            if (entry.kind == AppointmentKind.VISIT && notify != null) put("attendees_notify", if (notify) 1 else 0)
```

In `appointmentFromCursor`, after `inviteEmail = c.text("invite_email"),`:

```kotlin
        attendees = Attendees.parse(c.text("attendees")),
        attendeesNotify = c.int("attendees_notify")?.let { it == 1 },
```

- [ ] **Step 4: The rules**

In `…/calling/Appointment.kt`:

Add the import `import io.github.amadeusb.callsheet.data.Attendees`.

After `data class CalendarLine(…)`:

```kotlin

/**
 * What saving a visit asks before any mail goes out: the addresses added to its
 * attendees, and the ones removed. See Appointment.attendeeQuestion.
 */
data class AttendeeQuestion(val added: List<String>, val removed: List<String>)
```

Replace in `calendarLine`

```kotlin
                listOfNotNull("Im Kalender", entry.inviteEmail?.let { "Eingeladen: $it" }).joinToString(" · ")
```

with:

```kotlin
                listOfNotNull(
                    "Im Kalender",
                    entry.attendees.takeIf { it.isNotEmpty() }?.let { "Teilnehmende: ${Attendees.names(it)}" },
                ).joinToString(" · ")
```

Replace

```kotlin
    /** What removing a visit sends: a cancellation to the invitee. Null without one. */
    fun cancellationNotice(entry: AppointmentEntry): String? =
        entry.inviteEmail?.takeIf { entry.kind == AppointmentKind.VISIT }?.let { "$it bekommt eine Absage." }
```

with:

```kotlin
    /** What removing a visit sends: a cancellation to every attendee. Null without any. */
    fun cancellationNotice(entry: AppointmentEntry): String? {
        if (entry.kind != AppointmentKind.VISIT || entry.attendees.isEmpty()) return null
        val verb = if (entry.attendees.size == 1) "bekommt" else "bekommen"
        return "${Attendees.names(entry.attendees)} $verb eine Absage."
    }
```

After the function `removedHint` (package 1), add:

```kotlin
    /**
     * What saving [after] asks before any mail goes out, or null to save without
     * asking. Only for a visit whose attendees changed (added or removed,
     * ignoring case and order) — and for an existing visit ([before]) only when
     * title, time and place did not: with those changed, everybody on the list
     * is notified without a question, the user's rule. A new visit with
     * attendees always asks.
     */
    fun attendeeQuestion(before: AppointmentEntry?, after: AppointmentEntry, businessName: String): AttendeeQuestion? {
        if (after.kind != AppointmentKind.VISIT) return null
        val old = before?.attendees.orEmpty()
        val added = Attendees.added(old, after.attendees)
        val removed = Attendees.removed(old, after.attendees)
        if (added.isEmpty() && removed.isEmpty()) return null
        if (before != null && !sameForAttendees(before, after, businessName)) return null
        return AttendeeQuestion(added, removed)
    }

    /**
     * Title, time and place as the attendees see them: the title with its
     * default, times as instants, places trimmed — the server's sameCore.
     */
    private fun sameForAttendees(a: AppointmentEntry, b: AppointmentEntry, businessName: String): Boolean =
        visitTitle(a.title, businessName) == visitTitle(b.title, businessName) &&
            Clock.millis(a.startsAt) == Clock.millis(b.startsAt) &&
            Clock.millis(a.endsAt) == Clock.millis(b.endsAt) &&
            a.location?.trim().orEmpty() == b.location?.trim().orEmpty()

    /** „Mail an a, b senden?", „Absage an c senden?", or both in one sentence. */
    fun attendeeQuestionTitle(question: AttendeeQuestion): String {
        val mail = question.added.takeIf { it.isNotEmpty() }?.let { "Mail an ${Attendees.names(it)}" }
        val cancellation = question.removed.takeIf { it.isNotEmpty() }?.let { "Absage an ${Attendees.names(it)}" }
        return listOfNotNull(mail, cancellation).joinToString(" und ") + " senden?"
    }

    /**
     * The `attendees_notify` a save writes: null when the attendees did not
     * change, so the decision stored for the last change stays; the answer
     * [send] where [question] was asked; true where it was not — title, time or
     * place changed too, and everybody is notified. Null for a callback.
     */
    fun attendeesNotifyToStore(
        before: AppointmentEntry?,
        after: AppointmentEntry,
        question: AttendeeQuestion?,
        send: Boolean?,
    ): Boolean? {
        if (after.kind != AppointmentKind.VISIT) return null
        if (Attendees.sameSet(before?.attendees.orEmpty(), after.attendees)) return null
        return if (question != null) send ?: true else true
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest" --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: PASS.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Teilnehmende am Termin, Regeln für die Rückfrage, Anzeige und Absage-Hinweis"
```

---

### Task 7: App — the sheet asks, the invitation goes

**Repository:** app.

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`AppointmentDraft`; imports; `openSheet`; `updateAppointmentDraft`; `withInvite` removed; new functions after `dismissAppointment`; `saveVisit`)
- Modify: `…/ui/AppointmentSheet.kt` (imports; parameters; the dialog; `InviteSection` → `AttendeeSection`)
- Modify: `…/MainActivity.kt` (`AppointmentSheet` call)
- Modify: `…/calling/Appointment.kt` (invitation functions removed; `inviteAddresses` → `attendeeAddresses`)
- Modify: `…/data/Models.kt` (`inviteEmail` removed), `…/data/Repository.kt` (`invite_email` no longer written or read)
- Test: `test/AppointmentTest.kt` (invitation tests removed, one renamed), `test/RepositoryTest.kt` (two invitation tests removed)

**Interfaces:**
- Consumes: Tasks 4–6.
- Produces: `AppointmentDraft.attendees`, `.attendeeInput`, `.attendeeError`, `.attendeeQuestion`, `.sendToAttendees`, `.forced`; `CallsheetViewModel.addAttendee(typed: String)`, `answerAttendeeQuestion(send: Boolean)`, `cancelAttendeeQuestion()`; `AppointmentSheet(…, onAddAttendee: (String) -> Unit, onAnswerAttendees: (Boolean) -> Unit, onCancelAttendees: () -> Unit, …)`; `Appointment.attendeeAddresses(contact: Contact?, businessEmail: String?): List<String>`.

No new unit test: view model and Compose (Global Constraints). The rules are tested in Tasks 4 and 6; removed tests are listed below.

- [ ] **Step 1: The draft**

In `…/CallsheetViewModel.kt`, `AppointmentDraft`, replace

```kotlin
    /** „Einladung senden". Only for a visit. */
    val invite: Boolean = false,
    /** The invitee's address as typed or picked. */
    val inviteEmail: String = "",
    /** Why the address blocks saving; null while nothing is wrong. */
    val inviteError: String? = null,
)
```

with:

```kotlin
    /** A visit's attendees, in the order added. Nothing is preselected. Only for a visit. */
    val attendees: List<String> = emptyList(),
    /** An address typed but not added yet. Saving adds it first. */
    val attendeeInput: String = "",
    /** Why an address was refused; null while nothing is wrong. */
    val attendeeError: String? = null,
    /** The question on saving, while its dialog is open (Appointment.attendeeQuestion). */
    val attendeeQuestion: AttendeeQuestion? = null,
    /** The answer to [attendeeQuestion]: true „Senden", false „Ohne Mail speichern", null not asked yet. */
    val sendToAttendees: Boolean? = null,
    /** „Trotzdem anlegen" was chosen before the question came: the save after the answer keeps it. */
    val forced: Boolean = false,
)
```

Add the imports:

```kotlin
import io.github.amadeusb.callsheet.calling.AttendeeQuestion
import io.github.amadeusb.callsheet.data.Attendees
```

- [ ] **Step 2: Opening and changing the sheet**

In `openSheet`, replace

```kotlin
                        invite = existing?.inviteEmail != null,
                        inviteEmail = existing?.inviteEmail.orEmpty(),
```

with:

```kotlin
                        attendees = existing?.attendees.orEmpty(),
```

In `updateAppointmentDraft`, replace

```kotlin
        // Place and invitation both follow a new contact person, each only
        // where it was not chosen by hand.
        val draft = withInvite(previous, withPlace(previous, incoming))
```

with:

```kotlin
        // The place follows a new contact person where it was not chosen by
        // hand. The attendees never do: only the chips on offer change.
        val draft = withPlace(previous, incoming)
```

and in the same function replace

```kotlin
                    inviteError = null,
```

with:

```kotlin
                    attendeeError = null,
                    // A change after the question makes the answer stale: the next save asks again.
                    attendeeQuestion = null,
                    sendToAttendees = null,
                    forced = false,
```

Delete the whole function `withInvite` together with its KDoc (from `/**` above `private fun withInvite(` down to its closing `}`).

After the function `dismissAppointment()`, add:

```kotlin
    /**
     * Adds a picked or typed address to the visit's attendees. The field is
     * cleared once its own text went in; a refused address stays in it with
     * the reason underneath (Attendees.add).
     */
    fun addAttendee(typed: String) {
        _state.update { state ->
            val draft = state.appointmentDraft ?: return@update state
            val result = Attendees.add(draft.attendees, typed)
            val fromField = typed.trim() == draft.attendeeInput.trim()
            state.copy(
                appointmentDraft = draft.copy(
                    attendees = result.addresses,
                    attendeeInput = if (fromField && result.error == null) "" else draft.attendeeInput,
                    attendeeError = result.error,
                    attendeeQuestion = null,
                    sendToAttendees = null,
                )
            )
        }
    }

    /** The answer to the question on saving: „Senden" or „Ohne Mail speichern". Saves with it. */
    fun answerAttendeeQuestion(send: Boolean) {
        val draft = _state.value.appointmentDraft ?: return
        _state.update { it.copy(appointmentDraft = draft.copy(attendeeQuestion = null, sendToAttendees = send)) }
        saveAppointment(force = draft.forced)
    }

    /** The question dismissed — beside the dialog, or Back: back to the sheet, nothing saved. */
    fun cancelAttendeeQuestion() {
        _state.update { state ->
            val draft = state.appointmentDraft ?: return@update state
            state.copy(appointmentDraft = draft.copy(attendeeQuestion = null, sendToAttendees = null, forced = false))
        }
    }
```

- [ ] **Step 3: Saving a visit asks**

In `saveVisit`, replace

```kotlin
        Appointment.inviteError(draft.invite, draft.inviteEmail)?.let { error ->
            _state.update { it.copy(appointmentDraft = draft.copy(inviteError = error)) }
            return
        }
        val plan = Appointment.planVisit(startMillis, endMillis, draft.busy, force)
        if (plan is SavePlan.Conflict) {
            _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
            return
        }
```

with:

```kotlin
        // An address still in the field goes in first — or blocks saving.
        val typed = Attendees.add(draft.attendees, draft.attendeeInput)
        if (typed.error != null) {
            _state.update { it.copy(appointmentDraft = draft.copy(attendeeError = typed.error)) }
            return
        }
        val current = draft.copy(attendees = typed.addresses, attendeeInput = "")
        val plan = Appointment.planVisit(startMillis, endMillis, current.busy, force)
        if (plan is SavePlan.Conflict) {
            _state.update { it.copy(appointmentDraft = current.copy(conflict = plan.with)) }
            return
        }
```

In the `AppointmentEntry(…)` built in `saveVisit`, replace

```kotlin
            inviteEmail = Appointment.inviteToStore(draft.invite, draft.inviteEmail),
```

with:

```kotlin
            attendees = current.attendees,
```

and replace

```kotlin
        // The length a visit starts at follows the last visit.
        preferences.appointmentMinutes = draft.minutes
        repo.saveAppointment(entry)
```

with:

```kotlin
        // Nothing but the list changed: ask before any mail goes out. The answer
        // comes back through answerAttendeeQuestion, which saves again.
        val question = Appointment.attendeeQuestion(existing, entry, business.name)
        if (question != null && current.sendToAttendees == null) {
            // The slot, if it collided, was accepted: its notice goes while the dialog is open.
            _state.update {
                it.copy(appointmentDraft = current.copy(attendeeQuestion = question, forced = force, conflict = emptyList()))
            }
            return
        }
        // The length a visit starts at follows the last visit.
        preferences.appointmentMinutes = draft.minutes
        repo.saveAppointment(
            entry.copy(attendeesNotify = Appointment.attendeesNotifyToStore(existing, entry, question, current.sendToAttendees))
        )
```

In the KDoc of `saveVisit`, replace

```kotlin
     * and removes the event through the Infomaniak API and sends the
     * invitation. Here only the row is written — offline too — then synced at
```

with:

```kotlin
     * and removes the event through the Infomaniak API and notifies the
     * attendees. Here only the row is written — offline too — then synced at
```

and replace

```kotlin
     * state come down without another tap. A taken slot is still asked about;
     * nothing is linked.
```

with:

```kotlin
     * state come down without another tap. A taken slot is still asked about;
     * nothing is linked. A change to nothing but the attendees is asked about
     * too, after the slot (Appointment.attendeeQuestion).
```

- [ ] **Step 4: The sheet**

In `…/ui/AppointmentSheet.kt`:

Remove the import `import androidx.compose.material3.Switch` and add:

```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import io.github.amadeusb.callsheet.data.Attendees
```

In the parameters of `AppointmentSheet`, replace the KDoc line `/** The business's own address, offered for the invitation after the contact person's. */` with `/** The business's own address, offered for the attendees after the contact person's. */`, and after `onForce: () -> Unit,` add:

```kotlin
    onAddAttendee: (String) -> Unit,
    onAnswerAttendees: (Boolean) -> Unit,
    onCancelAttendees: () -> Unit,
```

After the line `val callback = draft.kind == AppointmentKind.CALLBACK` inside `ModalBottomSheet { … }`, add:

```kotlin
        // Asked on saving when nothing but the list changed. Beside the dialog,
        // or Back, cancels: the sheet stays, nothing is saved.
        draft.attendeeQuestion?.let { question ->
            AlertDialog(
                onDismissRequest = onCancelAttendees,
                title = { Text(Appointment.attendeeQuestionTitle(question)) },
                text = { Text("Ohne Mail wird der Termin trotzdem gespeichert.") },
                confirmButton = { TextButton(onClick = { onAnswerAttendees(true) }) { Text("Senden") } },
                dismissButton = { TextButton(onClick = { onAnswerAttendees(false) }) { Text("Ohne Mail speichern") } },
            )
        }
```

Replace

```kotlin
                if (!callback) {
                    InviteSection(
                        draft = draft,
                        contact = contacts.firstOrNull { it.id == draft.contactId },
                        businessEmail = businessEmail,
                        onDraft = onDraft,
                    )
                }
```

with:

```kotlin
                if (!callback) {
                    AttendeeSection(
                        draft = draft,
                        contact = contacts.firstOrNull { it.id == draft.contactId },
                        businessEmail = businessEmail,
                        onDraft = onDraft,
                        onAdd = onAddAttendee,
                    )
                }
```

Replace the whole composable `InviteSection` with its KDoc (from `/**\n * „Einladung senden": …` down to the closing `}` of `private fun InviteSection`) with:

```kotlin
/**
 * „Teilnehmende": the addresses the visit invites, each with a remove icon;
 * the contact person's addresses and the business's own offered as chips; and
 * a field for any other. Nothing is preselected, and a new contact person only
 * changes the chips. What the attendees get to see is said right here, so the
 * note stays a private one. Whether saving sends mail is asked on saving.
 */
@Composable
private fun AttendeeSection(
    draft: AppointmentDraft,
    contact: Contact?,
    businessEmail: String?,
    onDraft: (AppointmentDraft) -> Unit,
    onAdd: (String) -> Unit,
) {
    SectionLabel("Teilnehmende")
    if (draft.attendees.isNotEmpty()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            draft.attendees.forEach { address ->
                InputChip(
                    selected = true,
                    onClick = { onDraft(draft.copy(attendees = draft.attendees - address)) },
                    label = { Text(address) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "$address entfernen") },
                )
            }
        }
    }
    val personal = contact?.emails.orEmpty().map { it.email }
    val offered = Appointment.attendeeAddresses(contact, businessEmail).filterNot { Attendees.contains(draft.attendees, it) }
    if (offered.isNotEmpty()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            offered.forEach { address ->
                AssistChip(
                    onClick = { onAdd(address) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    // Only the address is stored; the label says whose it is.
                    label = { Text(if (address in personal) address else "$address (Betrieb)") },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft.attendeeInput,
            onValueChange = { onDraft(draft.copy(attendeeInput = it)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = draft.attendeeError != null,
            placeholder = { Text("name@betrieb.de") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd(draft.attendeeInput) }),
        )
        TextButton(onClick = { onAdd(draft.attendeeInput) }) { Text("Hinzufügen") }
    }
    draft.attendeeError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Text(
        text = "Teilnehmende sehen Titel, Zeit und Ort, nicht die Notiz.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
```

In `…/MainActivity.kt`, in the `AppointmentSheet(…)` call, after `onForce = { vm.saveAppointment(force = true) },`:

```kotlin
                        onAddAttendee = vm::addAttendee,
                        onAnswerAttendees = vm::answerAttendeeQuestion,
                        onCancelAttendees = vm::cancelAttendeeQuestion,
```

- [ ] **Step 5: The invitation goes**

In `…/calling/Appointment.kt`, delete completely, each with its KDoc:
- `const val INVALID_INVITE: String = "Das ist keine gültige E-Mail-Adresse."` (with `/** Said under the address when it blocks saving. */`)
- `private val EMAIL = Regex(…)`
- `fun inviteError(…)`
- `fun inviteToStore(…)`
- `fun inviteSuggestion(…)`
- `fun inviteAfterContactChange(…)`

Replace

```kotlin
    /**
     * The addresses a visit's invitation offers: the contact person's in their
     * order, then the business's own — imported businesses rarely have a
     * contact person. The business's is trimmed, and left out when blank or
     * already among the person's (ignoring case).
     */
    fun inviteAddresses(contact: Contact?, businessEmail: String?): List<String> {
```

with:

```kotlin
    /**
     * The addresses offered for a visit's attendees: the contact person's in
     * their order, then the business's own — imported businesses rarely have a
     * contact person. The business's is trimmed, and left out when blank or
     * already among the person's (ignoring case).
     */
    fun attendeeAddresses(contact: Contact?, businessEmail: String?): List<String> {
```

In `…/data/Models.kt`, `AppointmentEntry`, delete

```kotlin
    /** Who a visit invites. Null means no invitation. Always null for a callback. */
    val inviteEmail: String? = null,
```

In `…/data/Repository.kt`, `saveAppointment`, replace

```kotlin
            // A callback has neither: its title is built from its note, and
            // nobody is invited to a phone call. A null title is the default,
            // a null address no invitation — both written, so switching the
            // invitation off clears it.
            put("title", if (entry.kind == AppointmentKind.CALLBACK) null else entry.title)
            put("invite_email", if (entry.kind == AppointmentKind.CALLBACK) null else entry.inviteEmail)
```

with:

```kotlin
            // A callback has no title of its own: it is built from its note. A
            // null title is the default. `invite_email` is written no more:
            // attendees took its place in schema 10.
            put("title", if (entry.kind == AppointmentKind.CALLBACK) null else entry.title)
```

In `appointmentFromCursor`, delete `inviteEmail = c.text("invite_email"),`.

- [ ] **Step 6: The invitation's tests go**

In `test/AppointmentTest.kt`, delete these tests completely:
- `an invitation needs an address that looks like one`
- `switched off, no address is stored`
- `the invitation is preset to the contact person's first address`
- `without a contact person's address the invitation is preset to the business's`
- `switching the contact person to nobody brings the business's address, a typed one stays`
- `another contact person brings their first address where the previous one's was preselected`
- `a typed or picked address stays when the contact person changes`
- `the invitation's address follows nothing while it is off, the person stays, or it is a callback`

and the fixtures between them: `private val emails = mapOf(…)`, `private val emailsFor = …`, `private val invited = AppointmentDraft(…)`.

Replace the test `the business's address is offered after the contact person's, once and only when there is one` with:

```kotlin
    @Test
    fun `the business's address is offered after the contact person's, once and only when there is one`() {
        assertEquals(
            listOf("a@meier.de", "b@meier.de", "info@meier.de"),
            Appointment.attendeeAddresses(personWithEmails("a@meier.de", "b@meier.de"), " info@meier.de "),
        )
        assertEquals(listOf("Info@Meier.de"), Appointment.attendeeAddresses(personWithEmails("Info@Meier.de"), " info@meier.de"))
        assertEquals(listOf("a@meier.de"), Appointment.attendeeAddresses(personWithEmails("a@meier.de"), "   "))
        assertEquals(emptyList<String>(), Appointment.attendeeAddresses(null, ""))
        assertEquals(emptyList<String>(), Appointment.attendeeAddresses(null, null))
    }
```

In `test/RepositoryTest.kt`, delete the tests `a visit keeps its title and invitation, a callback stores neither` and `switching the invitation off clears the address`, and add in their place:

```kotlin
    @Test
    fun `a visit keeps its title, a callback stores none`() = runTest {
        repo.saveAppointment(visit("A-1", "t-1", "2026-09-16T09:00:00+02:00").copy(title = "Erstgespräch"))
        repo.saveAppointment(callback("R-1", "t-1", "2026-09-15T09:00:00+02:00").copy(title = "Erstgespräch"))

        val visit = repo.appointment("A-1")!!
        assertEquals("Erstgespräch", visit.title)
        assertTrue(visit.dirty)
        assertNull(repo.appointment("R-1")!!.title)
    }
```

- [ ] **Step 7: Build and test**

Run: `grep -rn "inviteEmail\|inviteError\|inviteToStore\|inviteSuggestion\|inviteAfterContactChange\|INVALID_INVITE\|withInvite\|InviteSection\|inviteAddresses" app/src`
Expected: no output.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. An unused-import warning is not a failure; an unresolved reference is — fix it where the compiler points.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Termin-Formular: Liste „Teilnehmende“ mit Rückfrage beim Speichern, Einladungsschalter entfernt"
```

---

### Task 8: App — documentation and changelog

**Repository:** app.

**Files:**
- Modify: `docs/data-model.md` (`appointments` block; the „Schema 8" paragraph; section „Synchronisation")
- Modify: `docs/usage.md` (section „Appointments on site")
- Modify: `CHANGELOG.md` (section `## 1.6.0`)
- Modify: `README.md` (section „What it does")

- [ ] **Step 1: Data model**

In `docs/data-model.md`, in the `appointments` code block, replace

```
invite_email            TEXT              -- a visit's invitee; NULL = no invitation (schema 8)
```

with:

```
invite_email            TEXT              -- no longer used since schema 10, not emptied; see attendees
attendees               TEXT              -- a visit's attendees, JSON array of addresses; NULL = nobody (schema 10)
attendees_notify        INTEGER           -- 1 | 0 | NULL (read as 1): whether the last change to attendees notifies (schema 10)
```

In the paragraph that begins „Schema 8: a visit reaches the calendar through the server", replace

```markdown
moves and removes its event through the Infomaniak API and sends the
invitation to `invite_email`. The app never writes a visit's event. The
```

with:

```markdown
moves and removes its event through the Infomaniak API and notifies its
attendees. The app never writes a visit's event. The
```

and after that paragraph add:

```markdown
Schema 10: a visit invites a list, `attendees`, instead of one address. The app
writes `attendees_notify` only in a save that changes the list: 1 after
„Senden" or when title, time or place changed as well, 0 after „Ohne Mail
speichern"; a save that leaves the list alone leaves it alone. The server
notifies everybody when title, time or place changed, and on a change to the
list alone only unless `attendees_notify` is 0. At a standstill the two columns
are filled together or `attendees_notify` not at all. The migration carried a
non-blank `invite_email` over as the only attendee, as the server's migration
012 does, and marked nothing.
```

In section „Synchronisation", replace

```markdown
Schemas 7 and 9 do it once each, for the addresses
and for `edited_fields` a 1.5.x app could not store.
```

with:

```markdown
Schemas 7, 9 and 10 do it once each, for the
addresses, for `edited_fields` and for the attendees an older app could not store.
```

(If package 1 wrapped that sentence differently, replace the sentence „Schemas 7 and 9 do it once each, …" wherever its line breaks fall.)

- [ ] **Step 2: Usage**

In `docs/usage.md`, section „Appointments on site", replace the paragraph

```markdown
**Einladung senden** invites somebody: pick one of the contact person's
addresses, the business's own (marked „Betrieb") or type one. Without a contact
person's address the business's is preselected; picking another person brings
their first address along unless you chose one yourself. The invitation comes from christoph@bauer-ki.de;
moving the visit sends an update, removing it a cancellation. The invitee sees
title, time and place — never the note. Changing only the note sends nothing.
```

with:

```markdown
**Teilnehmende** lists who is invited. Tap one of the contact person's addresses
or the business's own (marked „Betrieb") to add it, or type any address and tap
**Hinzufügen**; the cross on an address takes it off. Nothing is preselected.
The invitation comes from christoph@bauer-ki.de. Attendees see title, time and
place — never the note. Changing only the note sends nothing.

When saving changes nothing but the list, the app asks: „Mail an … senden?",
„Absage an … senden?", or both. **Senden** sends the invitation or
cancellation; **Ohne Mail speichern** saves all the same, and nobody gets a
mail. Beside the dialog or Back returns to the sheet without saving. When title,
time or place change, nothing is asked and everybody on the list gets the
update — also somebody added without mail before. A person taken off with mail
gets „Veranstaltung gelöscht" from the calendar, although the visit stays.
```

Replace „„Im Kalender · Eingeladen: <Adresse>"" with „„Im Kalender · Teilnehmende: <Adressen>"".

In the paragraph package 1 wrote about a visit missing from the calendar, replace the words „without an invitation it removes the visit at once and says so, with an invitation it asks first, as the invitee gets a cancellation" (spread over several lines) with „without attendees it removes the visit at once and says so, with attendees it asks first, as they get a cancellation".

Replace

```markdown
**Entfernen** asks first, for a past appointment too: it removes a piece of the
record. With an invitation it names who gets the cancellation.
```

with:

```markdown
**Entfernen** asks first, for a past appointment too: it removes a piece of the
record. With attendees it names who gets the cancellation.
```

In `README.md`, replace

```markdown
  the calendar through the sync server, with an invitation if wanted; callbacks
```

with:

```markdown
  the calendar through the sync server, with the attendees invited; callbacks
```

- [ ] **Step 3: Changelog**

In `CHANGELOG.md`, section `## 1.6.0`, in package 1's bullet about a missing visit replace the words „at once without an invitation, after asking with one" (spread over two lines) with „at once without attendees, after asking with them". Then add before the bullet that begins „**Update the sync server first, then every phone, and only then import":

```markdown
- **Teilnehmende instead of „Einladung senden".** A visit invites any number of
  people: the contact person's addresses and the business's own are one tap
  away, any other can be typed. Nothing is preselected.
- **Asked before mail goes out.** Saving a change to nothing but the list asks
  „Mail an … senden?" or „Absage an … senden?"; **Ohne Mail speichern** saves
  without mail. Moving the visit or changing its title or place notifies
  everybody on the list without asking.
- A visit invited in 1.5.0 keeps its invitee as the only attendee; no mail goes
  out through the update.
```

- [ ] **Step 4: Check and commit**

Run: `grep -n "Einladung senden\|Eingeladen:\|without an invitation\|with an invitation" docs/*.md README.md`
Expected: no output.

Run: `sed -n '/^## 1.6.0/,/^## 1.5.0/p' CHANGELOG.md | grep -n "Einladung senden\|without an invitation\|with an invitation"`
Expected: no output. The `## 1.5.0` section stays as it was released.

```bash
git add docs/data-model.md docs/usage.md CHANGELOG.md README.md
git commit -m "Doku und CHANGELOG: Teilnehmende statt Einladungsschalter"
```

---

## Rollout

Server before app, 1.6.0 with packages 1 and 2 together. **Between the server deploy and the app update, nobody changes invitations or attendees on a phone still on 1.5.0** — the server ignores them there, and a visit created on such a phone invites nobody. Every phone is updated the same day. If package 1's Tasks 9 and 10 have not run yet, run them together with Tasks 9 and 10 here: one deploy, one phone test, one release.

### Task 9: Server — deploy

**Repository:** server, and the production host. **Outward-facing: ask the user before Step 1's push and before Step 4, and wait for a yes each time.**

Every remote command is one non-interactive `ssh <server> '…'` call. `<server>`, `<deploy-dir>` and `<callsheet-host>` are the ones from earlier deploys; ask „caller-app-34" if you do not have them.

- [ ] **Step 1: Push**

Run: `node --test` — `# fail 0`. Then ask the user, and on a yes: `git push origin main`.

- [ ] **Step 2: See what the host will pull**

```bash
ssh <server> 'cd <deploy-dir>/repo && git fetch && git diff HEAD origin/main --stat'
```

Expected: `db/migrations/012-attendees.sql`, `src/visitState.js`, `src/pushCalendar.js`, `src/receive.js`, tests, README, docs — and package 1's `011-edited-fields.sql` if it is not deployed yet. Anything else under `src/` or another migration: stop and ask the user.

- [ ] **Step 3: Count before, and back up**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT COUNT(*) AS invited FROM appointments WHERE TRIM(COALESCE(invite_email, \x27\x27)) <> \x27\x27\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS pending FROM infomaniak_events WHERE pending IS NOT NULL\").get());
"'
ssh <server> 'sudo <deploy-dir>/repo/scripts/backup.sh'
```

Expected: two numbers; the backup exits 0. If `pending` is not 0, wait ten minutes and count again: a job in flight during the deploy would run on the new code with an old `pushed`.

- [ ] **Step 4: Pull and restart**

```bash
ssh <server> 'cd <deploy-dir>/repo && git pull && ENVIRONMENT=prod ./scripts/up.sh'
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml ps'
```

Expected: `up.sh` exits 0; `callsheet` is `healthy` within 90 seconds.

- [ ] **Step 5: Check the migration on the live file**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT filename FROM schema_migrations ORDER BY filename DESC LIMIT 1\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS with_attendees FROM appointments WHERE attendees IS NOT NULL\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS old_pushed FROM infomaniak_events WHERE json_type(pushed, \x27$.invite_email\x27) IS NOT NULL\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS pending FROM infomaniak_events WHERE pending IS NOT NULL\").get());
"'
```

Expected: `012-attendees.sql`; `with_attendees` equals Step 3's `invited`; `old_pushed` 0; `pending` 0 — nothing was queued by the migration.

- [ ] **Step 6: From outside**

```bash
curl -si https://<callsheet-host>/health | head -1
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<callsheet-host>/sync
```

Expected: `HTTP/2 200`, then `401`.

---

### Task 10: App — on the phone, then release

**Repository:** app. **The phone test needs the user's phone; the release is outward-facing: ask before Step 4.**

- [ ] **Step 1: Everything green, server ready**

Run: `./gradlew assembleDebug testDebugUnitTest` — BUILD SUCCESSFUL; `git status --porcelain` in both repos shows nothing. Ask „caller-app-34" (or the user) whether Task 9 is done; do not install before it is. Remind the user: until every phone runs the new app, no invitations or attendees are changed on an old one.

- [ ] **Step 2: Install**

Run: `~/android-sdk/platform-tools/adb devices -l` — one device, else ask the user to connect it and wait.
Run: `./gradlew installDebug` — `Installed on 1 device`.

- [ ] **Step 3: Walk through it with the user**

With `test@example.org` replaced by an address the tester can read, and a second one. The user reports each result:

1. A visit invited in 1.5.0: the detail view shows „Im Kalender · Teilnehmende: <address>". No mail arrived through the update.
2. **Termin anlegen** for a visit: under „Teilnehmende" nothing is selected; the contact person's addresses and „… (Betrieb)" are chips. Switch the contact person: the chips change, the list does not.
3. Add the first address, save → „Mail an … senden?" → **Senden**: the invitation arrives; the line reads „Teilnehmende: …".
4. **Ändern**, add the second address, save → question → **Ohne Mail speichern**: no mail to either address; the event in the web calendar lists both.
5. **Ändern**, move the visit by an hour, save: no question; both addresses get the update.
6. **Ändern**, take the first address off, save → „Absage an … senden?" → **Senden**: note what the first address gets (expected „Veranstaltung gelöscht") and whether the second hears anything. Report it for the server README.
7. **Ändern**, add an address and change the place in one go: no question; everybody on the list gets the update.
8. **Ändern**, add an address, save, and at the question press Back: the sheet stays open, nothing saved (the detail view still shows the old list after closing the sheet).
9. Type „test@example" into the field, save: „Das ist keine gültige E-Mail-Adresse." under the field, nothing saved.
10. **Entfernen** on a visit with two attendees: the dialog says „… bekommen eine Absage."; both get „Veranstaltung gelöscht".
11. Package 1's check with attendees: a visit missing from the calendar with attendees asks before „Termin entfernen", one without does not.
12. The second address accepts the invitation in its mail program. Then move the visit in the app: in the web calendar the second address is still „accepted".
13. Rename the business (package 1, **Stammdaten bearbeiten**) of a visit with the default title: the attendees get the update with the new title.
14. Type the calendar account's own address as an attendee: „Die eigene Adresse ist immer dabei."

Anything that does not match: stop, find the cause (superpowers:systematic-debugging), fix it test-first in a task of its own, and repeat this step. Send the observations for items 6 and 7 to „caller-app-34" for the server README.

- [ ] **Step 4: Release 1.6.0**

Only after Task 9 is done, packages 1 and 2 are both through their phone tests, and the user says yes. Confirm with „caller-app-34" that 1.6.0 ships both packages, then run `tools/release.sh minor`.
Expected: the script builds, tests, tags and publishes 1.6.0 with its CHANGELOG section as release notes. Every phone is updated the same day.
