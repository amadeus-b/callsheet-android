# Package 1 — Fixes after the phone test of 1.5.0 — Implementation Plan (SERVER and APP)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone number may stand at several businesses; the master data of an existing business can be edited in the app and a hand edit survives every later import on every device; the app's read-back never deletes a visit.

**Architecture:** One new synchronised, nullable column `businesses.edited_fields` (server migration 011, app schema 9) holds the sorted JSON list of columns changed by hand. Pure rules — which fields changed, reading and writing the list, the form draft from a business, whether a removal asks, the hint after it — live in `data/MasterData.kt` and `calling/Appointment.kt`. The repository writes the edit and makes the import leave edited columns alone; the business form gets an editing mode. `Appointment.reconcile` reports every visit gone from the calendar as `MissingVisit` instead of deleting it. The server needs no code: it reads columns from the schema.

**Tech Stack:** Server: Node 24, `node:sqlite`, `node --test`. App: Kotlin, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `org.json`, JUnit 4 + Robolectric 4.16 (`@Config(sdk = [34])`), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-15-paket1-fixes-nach-handytest-design.md` (app repo, commit `3872dc5`); the server's pointer is `docs/superpowers/specs/2026-09-15-paket1-fixes-nach-handytest-design.md` in the server repo (commit `ee37e9b`). Read the spec before starting.

## Decisions made while planning (on top of the spec)

- **Merge of `edited_fields`: the newer row wins, no union.** The list describes its own row's values; a union would protect values nobody edited (spec, „Synchronisation of `edited_fields`"). No merge code: the existing row rule and `fillGaps` on both sides do it.
- **A refetch after the update** (`Preferences.refetchedForEditedFields`), as for schemas 4, 6 and 7 — a 1.5.0 phone stores businesses without the column while its watermark moves past them.
- **Only changed columns are written** when master data is saved. Unchanged columns keep their stored text, even where it carries whitespace the form would trim: rewriting them would make the next import see a difference that is not one.
- **Saving compares with the values as the form opened** (review finding). `showMasterData` keeps `MasterData.of(business)` in `State.masterDataBefore`; `updateMasterData(placeId, before, draft)` writes only `changedFields(before, after)`. A column another device changed while the form was open stays as that device left it and does not land in `edited_fields`.
- **`is_target` is written only when phone or industry changed.**
- **The editing mode reuses the business form's state** (`State.draft`, `formError`, `saving`) under a new screen `Screen.MasterDataForm(placeId)`.
- **„Termin entfernen" without a dialog says what it did** in the detail view's hint („Termin entfernt." / „Termin entfernt. Status zurück auf „Angerufen“."), because the dialog that would have said it is skipped.
- **The detail row „Ansprechpartner (importiert)" reads „Ansprechpartner"** once `contact_name` is in `edited_fields` (coordinator decision; `MasterData.contactNameLabel`, `Business.editedFields`).
- **Version 1.6.0** (coordinator decision). Package 2 may ship in the same release under the same heading.
- **The read-back's deletion hint now only ever concerns callbacks**, so its visit branch (status fallback) goes.

## Global Constraints

- **Two repositories, both on branch `main`.** App: `~/code/tm-services-automate/caller-app/app` (Kotlin sources under `app/src/main/java/io/github/amadeusb/callsheet/`, written `…/` below; tests under `app/src/test/java/io/github/amadeusb/callsheet/`, written `test/…` below). Server: `~/code/tm-services-automate/caller-app/server`. Every task names its repository. Server tasks and app tasks touch disjoint files and can run in parallel; only the rollout (Tasks 9 and 10) needs both.
- **Numbers:** server migration **`011-edited-fields.sql`**, app `Database.VERSION` **8 → 9**.
- **Column:** `businesses.edited_fields TEXT`, nullable on both sides. Content: a JSON array of column names, sorted, no spaces, e.g. `["email","phone"]`; `NULL` for none. Editable column names, exactly: `name`, `phone`, `industry`, `website`, `email`, `contact_name`. The list only grows.
- **New server columns are nullable** (`server/README.md`, „Migrationen"); a missing key in a payload keeps the stored value.
- **The app repo is public on GitHub.** No hostnames, no server paths, no calendar ids, no personal addresses in code, tests, docs or commit messages. Tests use made-up names, numbers and `test@example.org`.
- **UI texts, exactly:** detail button „Stammdaten bearbeiten"; form title „Stammdaten bearbeiten"; save button „Stammdaten speichern"; errors „Ohne Namen lässt sich der Betrieb nicht speichern." and „Die Telefonnummer ist unvollständig. Lass sie leer oder trag sie vollständig ein."; hints „Termin entfernt." and „Termin entfernt. Status zurück auf „Angerufen“." (status text from `Status.label`); calendar line „Im Kalender nicht mehr gefunden" with „Termin entfernen" (unchanged).
- **Tests:** app `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`; build check `./gradlew assembleDebug`; both from the app repository root. Server `node --test` (all) or `node --test test/<file>.test.js`, locally only.
- **No view model test harness exists.** Decisions are pure functions with unit tests; view model and Compose wiring are checked by `assembleDebug` and the phone test (Task 10).
- **Language:** code, identifiers, comments, docs in English; UI text German with correct umlauts and „…" quotes; server README German. Test names in English; the app fixture method stays `fun aufbau()`.
- **Timestamps:** `Clock.now()`, compared through `Clock.millis`.
- **State writes** in new view model code use `_state.update { … }`.
- **Every task leaves its project building and all tests green. Commit after every task,** staging only the files the task names — never `git add -A` or `git add .`. German commit messages in the tone of `git log`, no attribution lines. Do not push except where Task 9 says so.
- **Rollout order:** server deployed first (Task 9), then the app released (Task 10). Both are outward-facing: ask before each.
- **Questions go to the coordinator session „caller-app-34"** (SendMessage), not to the user, except where Tasks 9 and 10 say „ask the user".

---

## File Structure

| Repository | File | Change | Responsibility |
|---|---|---|---|
| server | `db/migrations/011-edited-fields.sql` | create | the column |
| server | `test/db.test.js`, `test/receive.test.js`, `test/deliver.test.js` | modify | migration 011; the column travels without code |
| server | `README.md` | modify | the column, the merge rule |
| app | `…/data/Repository.kt` | modify | `create` without the duplicate check; `updateMasterData`; the import leaves edited columns alone |
| app | `…/data/MasterData.kt` | create | `MasterValues`, `MasterData`: changed fields, the list, the draft |
| app | `…/data/Database.kt` | modify | `VERSION = 9`, `COLUMN_BUSINESSES_9` |
| app | `…/contacts/Preferences.kt`, `…/sync/SyncEngine.kt` | modify | refetch on schema 9 |
| app | `…/CallsheetViewModel.kt` | modify | `Screen.MasterDataForm`, `showMasterData`, `saveMasterData`; `reconcile`, `reconcileAppointments`, `removeAppointment(confirmed)` |
| app | `…/ui/BusinessForm.kt` | modify | editing mode |
| app | `…/ui/BusinessDetail.kt` | modify | „Stammdaten bearbeiten"; „Termin entfernen" without a dialog where nobody is invited |
| app | `…/MainActivity.kt` | modify | the new screen and callbacks |
| app | `…/calling/Appointment.kt` | modify | `Reconcile.MissingVisit`, `reconcile` without `invited`, `removalAsks`, `removedHint` |
| app | tests | modify/create | `BusinessFormTest`, `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `SyncEngineTest`, `MasterDataTest` (new), `RepositoryTest`, `AppointmentTest` |
| app | `docs/data-model.md`, `docs/usage.md`, `CHANGELOG.md` | modify | docs |

---

### Task 0: Preconditions

**Files:** none. Run the steps for the repository you are working in; an agent doing both runs both.

- [ ] **Step 1: Clean trees on `main`**

Run in the app repo and in the server repo: `git status --short && git branch --show-current`
Expected: no output from `status`, branch `main` in both. This plan and the spec are committed (`git log --oneline -3 -- docs/superpowers/` shows them). If anything is uncommitted or untracked, or the branch is another, stop and ask „caller-app-34".

- [ ] **Step 2: The numbers are free**

Run in the server repo: `ls db/migrations | tail -1`
Expected: `010-visits-via-api.sql`. If a `011-*.sql` exists, stop and ask „caller-app-34".

Run in the app repo: `grep -n 'const val VERSION' app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt`
Expected: `const val VERSION = 8`. Otherwise stop and ask.

- [ ] **Step 3: The anchors are where this plan says**

Run in the app repo:

```bash
grep -n 'Diese Nummer steht schon bei' app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt
grep -n 'data object MissingInvited' app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt
grep -n 'invited = visit && entry.inviteEmail != null' app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt
grep -n 'private fun createVersionSeven' app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt
grep -n 'refetchedForAddresses' app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt
```

Expected: one or more lines from each. Anything missing: stop and ask „caller-app-34".

- [ ] **Step 4: Both suites green before anything changes**

Run in the server repo: `node --test`
Expected: `# fail 0`.

Run in the app repo: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

---

### Task 1: Server — migration 011, the column travels

**Repository:** server.

**Files:**
- Create: `db/migrations/011-edited-fields.sql`
- Modify: `README.md` (section „Migrationen", after the paragraph „**Adressen liegen in `business_addresses`.** …")
- Test: `test/db.test.js` (append), `test/receive.test.js` (insert before `test('the synchronised tables are exported, addresses among them', …)`), `test/deliver.test.js` (append)

**Interfaces:**
- Produces: column `businesses.edited_fields` (TEXT, NULL). No change to `src/`.

- [ ] **Step 1: Write the failing tests**

Append to `test/db.test.js`:

```js
/**
 * A database in the state a running server is in when 011 arrives: every
 * earlier migration applied and recorded, and a business already stored.
 */
function databaseBefore011() {
  const path = join(mkdtempSync(join(tmpdir(), 'callsheet-')), 'test.db')
  const db = open(path)
  db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL)')
  for (const filename of MIGRATIONS.filter(name => name < '011')) {
    db.exec(readFileSync(join('db/migrations', filename), 'utf8'))
    db.prepare('INSERT INTO schema_migrations (filename, applied_at) VALUES (?, ?)').run(filename, new Date().toISOString())
  }
  db.prepare(
    `INSERT INTO businesses (place_id, name, email, closed, is_target, status, updated_at, server_seq)
     VALUES ('P1', 'Elektro Meier', 'info@example.org', 0, 1, 'new', '2026-09-07T10:00:00+02:00', 1)`
  ).run()
  db.prepare('UPDATE sync_counter SET value = 1').run()
  return db
}

test('migration 011 adds edited_fields empty and leaves the businesses as they were', () => {
  const db = databaseBefore011()

  migrate(db, 'db/migrations')

  const row = { ...db.prepare(
    "SELECT email, edited_fields, updated_at, server_seq FROM businesses WHERE place_id = 'P1'"
  ).get() }
  assert.deepEqual(row, {
    email: 'info@example.org',
    edited_fields: null,
    updated_at: '2026-09-07T10:00:00+02:00',
    server_seq: 1,
  })
  // No carry-over: the counter does not move.
  assert.equal(db.prepare('SELECT value FROM sync_counter').get().value, 1)
})

test('businesses.edited_fields accepts NULL and a list', () => {
  const db = freshDb()
  db.prepare(
    `INSERT INTO businesses (place_id, name, closed, is_target, status, updated_at, server_seq, edited_fields)
     VALUES ('P1', 'Elektro Meier', 0, 1, 'new', '2026-09-07T10:00:00+02:00', 1, NULL),
            ('P2', 'Gartenbau Merten', 0, 1, 'new', '2026-09-07T10:00:00+02:00', 2, '["email","phone"]')`
  ).run()
  const lists = db.prepare('SELECT edited_fields FROM businesses ORDER BY place_id').all().map(row => row.edited_fields)
  assert.deepEqual(lists, [null, '["email","phone"]'])
})
```

In `test/receive.test.js`, insert before `test('the synchronised tables are exported, addresses among them', …)`:

```js
function editedFields(db) {
  return db.prepare('SELECT edited_fields FROM businesses WHERE place_id = ?').get('P1').edited_fields
}

test('a business carries the fields changed by hand', () => {
  const db = freshDb()

  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["email","phone"]' }] })

  assert.equal(editedFields(db), '["email","phone"]')
})

test('a business from an app that does not know edited_fields keeps the stored list', () => {
  // A 1.5.0 phone sends no key at all. Its edit must not clear what a newer
  // phone recorded.
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["email"]' }] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, note: 'neu', updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(editedFields(db), '["email"]')
})

test('a standstill fills a missing edited_fields', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [BUSINESS] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["email"]' }] })

  assert.equal(editedFields(db), '["email"]')
})

test('the newer business replaces edited_fields, the lists are not merged', () => {
  // The list belongs to its row: the newer row's values are what was or was
  // not edited by hand.
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["email"]' }] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["phone"]', updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(editedFields(db), '["phone"]')
})

test('an older business leaves edited_fields alone', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: '["email"]' }] })

  receive(db, { ...empty, businesses: [{ ...BUSINESS, edited_fields: null, updated_at: '2026-09-07T09:00:00+02:00' }] })

  assert.equal(editedFields(db), '["email"]')
})
```

Append to `test/deliver.test.js`:

```js
test('a business comes down with the fields changed by hand', () => {
  const db = freshDb()
  receive(db, { ...empty, businesses: [{ ...business(1), edited_fields: '["email"]' }] })

  const delivered = deliver(db, 0)

  assert.equal(delivered.businesses[0].edited_fields, '["email"]')
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test test/db.test.js test/receive.test.js test/deliver.test.js`
Expected: FAIL — `no such column: edited_fields` in both db tests; in the receive and deliver tests `edited_fields` reads `undefined` where a list is expected (the column does not exist, so `write` never stores it). `an older business leaves edited_fields alone` fails the same way.

- [ ] **Step 3: Write the migration**

Create `db/migrations/011-edited-fields.sql`:

```sql
-- Master data changed by hand in the app. A JSON array of column names —
-- name, phone, industry, website, email, contact_name — sorted, e.g.
-- ["email","phone"]; NULL for none. The app's import leaves these columns as
-- they are, so a hand edit survives every later import on every device.
--
-- Synchronised like every column: the newer row wins with its list, and the
-- lists are not merged — a list describes the values of its own row. A
-- standstill fills a NULL; a payload without the key (an app from before this
-- column) keeps the stored list. receive.js and deliver.js read the columns
-- from the schema, so nothing else changes.
--
-- Nullable, as the README asks of every new column. Nothing carried over,
-- nothing marked, no sequence number moves.

ALTER TABLE businesses ADD COLUMN edited_fields TEXT;
```

- [ ] **Step 4: Document it**

In `README.md`, section „Migrationen", after the paragraph that begins „**Adressen liegen in `business_addresses`.**" and ends „gelesen noch geschrieben.", add:

```markdown
**Von Hand geänderte Stammdaten.** `businesses.edited_fields` ist eine
JSON-Liste der Stammdaten-Spalten, die in der App von Hand geändert wurden
(`name`, `phone`, `industry`, `website`, `email`, `contact_name`), sortiert,
z. B. `["email","phone"]`, oder `NULL`. Der Import in der App lässt diese
Spalten stehen. Die Liste gehört zu ihrer Zeile: Die neuere Zeile gewinnt mit
ihrer Liste, zusammengeführt wird nichts; ein Stillstand füllt ein `NULL`. Eine
App ohne die Spalte schickt den Schlüssel nicht mit und lässt die Liste damit
stehen. Migration 011 hat die Spalte leer angelegt.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `node --test test/db.test.js test/receive.test.js test/deliver.test.js`
Expected: PASS.

Run: `node --test`
Expected: `# fail 0`.

Run: `git diff --stat -- src`
Expected: no output — no server code changed.

- [ ] **Step 6: Commit**

```bash
git add db/migrations/011-edited-fields.sql README.md test/db.test.js test/receive.test.js test/deliver.test.js
git commit -m "Migration 011: edited_fields merkt sich von Hand geänderte Stammdaten"
```

---

### Task 2: App — the same number at several businesses

**Repository:** app.

**Files:**
- Modify: `…/data/Repository.kt` (`create`, the block from the comment „Calling the same number twice is the mistake …" to its closing brace, about lines 386–397)
- Modify: `docs/usage.md` (section „Entering a single business by hand", paragraph „Two things the app catches: …")
- Test: `test/BusinessFormTest.kt` (replace `the same number cannot be created twice`)

**Interfaces:**
- Produces: `Repository.create` accepts a number another business holds.

- [ ] **Step 1: Turn the test around**

In `test/BusinessFormTest.kt`, replace the whole test `the same number cannot be created twice` with:

```kotlin
    @Test
    fun `the same number can stand at a second business`() = runTest {
        repo.create(BusinessDraft(name = "Erster Eintrag", phone = "+49 621 9900096")).getOrThrow()
        // Different notation, same number: one number often serves several businesses.
        val second = repo.create(BusinessDraft(name = "Zweiter Eintrag", phone = "0621 9900096"))

        assertTrue(second.isSuccess)
        assertEquals("+496219900096", repo.business(second.getOrThrow())!!.phone)
        assertEquals(2, repo.count(Filter(status = emptySet(), onlyTargets = false)))
        // Both go into the phone book, one entry each.
        assertEquals(2, repo.businessesForPhoneBook().count { it.phone == "+496219900096" })
    }
```

`an incomplete number is rejected` stays as it is.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.BusinessFormTest"`
Expected: FAIL in `the same number can stand at a second business` (`second.isSuccess` is false); every other test passes.

- [ ] **Step 3: Remove the check**

In `…/data/Repository.kt`, `create`, delete this block completely (it sits between the incomplete-number check and `val industry = …`):

```kotlin
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

```

In the KDoc of `create`, after the paragraph „Its key carries the [MANUAL_PREFIX] …", add:

```kotlin
     * A number another business already holds is accepted: one number often
     * serves several businesses — a family firm with two trades, a shared
     * office. Only an incomplete number is refused.
     *
```

- [ ] **Step 4: Update the usage guide**

In `docs/usage.md`, replace:

```markdown
Two things the app catches: an incomplete phone number is rejected, and the same
number cannot be created twice — the message then names the business that already
holds it. That prevents calling somebody twice.
```

with:

```markdown
An incomplete phone number is rejected. A number another business already holds
is accepted — one number often serves several businesses; the phone book then
holds it once per business.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.BusinessFormTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/test/java/io/github/amadeusb/callsheet/BusinessFormTest.kt docs/usage.md
git commit -m "Dieselbe Nummer darf bei mehreren Betrieben stehen"
```

---

### Task 3: App — schema 9, the column travels, refetch

**Repository:** app.

**Files:**
- Modify: `…/data/Database.kt` (`onCreate` after `for (sql in COLUMNS_APPOINTMENTS_8) db.execSQL(sql)`; `onUpgrade` after the `if (old < 8)` block; `VERSION`; companion after `COLUMNS_APPOINTMENTS_8`)
- Modify: `…/contacts/Preferences.kt` (after `refetchedForAddresses`; constants)
- Modify: `…/sync/SyncEngine.kt` (after the `refetchedForAddresses` block in `sync`)
- Test: `test/MigrationTest.kt` (fixture after `createVersionSeven`; test after `an upgrade from version seven adds the invitation and calendar columns empty`)
- Test: `test/SyncSchemaTest.kt` (one test)
- Test: `test/SyncStoreTest.kt` (two tests, after `a standstill never overwrites a value stored here`)
- Test: `test/SyncEngineTest.kt` (one test; `a device that has fetched everything since keeps its watermark` gains a line)

**Interfaces:**
- Produces: column `businesses.edited_fields`; `Preferences.refetchedForEditedFields: Boolean`.

- [ ] **Step 1: Write the failing tests**

In `test/MigrationTest.kt`, after `createVersionSeven()`:

```kotlin
    /**
     * The version 8 schema, as 1.5.0 ships it: version 7 with a visit's title,
     * invitation and calendar state. The upgrade to 9 touches businesses only.
     */
    private fun createVersionEight() {
        createVersionSeven()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        for (column in listOf("title TEXT", "invite_email TEXT", "calendar_state TEXT", "calendar_error TEXT", "calendar_seen_title TEXT")) {
            db.execSQL("ALTER TABLE appointments ADD COLUMN $column")
        }
        db.version = 8
        db.close()
    }
```

and after the test `an upgrade from version seven adds the invitation and calendar columns empty`:

```kotlin
    // --- from version 8, the road 1.5.0 devices are on -------------------------

    @Test
    fun `an upgrade from version eight adds edited_fields empty and leaves the businesses unmarked`() {
        createVersionEight()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT name, edited_fields, dirty FROM businesses WHERE place_id = 'alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Bestandsbetrieb", c.getString(0))
            assertTrue(c.isNull(1))
            // Nothing new to tell the server.
            assertEquals(0, c.getInt(2))
        }
    }

    @Test
    fun `a fresh database and one upgraded from version eight have the same business columns`() {
        createVersionEight()
        val upgraded = Database(context).readableDatabase.let { db -> columnsOf("businesses", db).also { db.close() } }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = columnsOf("businesses", Database(context).readableDatabase)

        // PRAGMA on a missing table returns no columns on both sides.
        assertTrue("edited_fields" in fresh)
        assertEquals(fresh, upgraded)
    }
```

In `test/SyncSchemaTest.kt`, add:

```kotlin
    @Test
    fun `businesses carry the fields changed by hand`() {
        assertTrue(columns("businesses").contains("edited_fields"))
    }
```

In `test/SyncStoreTest.kt`, after `a standstill never overwrites a value stored here`:

```kotlin
    @Test
    fun `a newer business brings its hand-edited fields and replaces the list here`() {
        // The list belongs to its row: no union.
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe("UPDATE businesses SET edited_fields = '[\"email\"]' WHERE place_id = 'P1'")

        store.apply(antwort(betriebJson("P1", null, "2026-09-07T11:00:00+02:00").put("edited_fields", "[\"phone\"]")))

        assertEquals("[\"phone\"]", einzeln("SELECT edited_fields FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a standstill fills hand-edited fields a 1_5_0 phone could not store`() {
        einBetrieb("P1", "lokal", "2026-09-07T10:00:00+02:00", dirty = 0)

        store.apply(antwort(betriebJson("P1", "lokal", "2026-09-07T10:00:00+02:00").put("edited_fields", "[\"email\",\"phone\"]")))

        assertEquals("[\"email\",\"phone\"]", einzeln("SELECT edited_fields FROM businesses WHERE place_id = 'P1'"))
        // Filled from the server, so nothing to send back.
        assertEquals(0, store.pendingCount())
    }
```

In `test/SyncEngineTest.kt`, after `the first sync after the address update starts from watermark zero, and only that one`:

```kotlin
    @Test
    fun `the first sync after the edited-fields update starts from watermark zero, and only that one`() {
        prefs.watermark = 42
        prefs.refetchedForAppointments = true
        prefs.refetchedForCallbacks = true
        prefs.refetchedForAddresses = true
        prefs.refetchedForEditedFields = false
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

and in `a device that has fetched everything since keeps its watermark`, after `prefs.refetchedForAddresses = true`, add:

```kotlin
        prefs.refetchedForEditedFields = true
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: FAIL to compile — `Unresolved reference: refetchedForEditedFields`.

Until Step 4 the test sources do not compile, so the other classes cannot be run separately; that is expected.

- [ ] **Step 3: Schema 9**

In `…/data/Database.kt`, companion object, after `COLUMNS_APPOINTMENTS_8`:

```kotlin
        /**
         * Schema 9: the master data columns changed by hand, as a sorted JSON
         * array of column names (see MasterData). The import leaves those
         * columns alone. Synchronised; nullable, as every new synchronised
         * column is. Added by ALTER on both roads, for the reason
         * COLUMNS_APPOINTMENTS_6 gives.
         */
        private const val COLUMN_BUSINESSES_9 = "ALTER TABLE businesses ADD COLUMN edited_fields TEXT"
```

In `onCreate`, after `for (sql in COLUMNS_APPOINTMENTS_8) db.execSQL(sql)`:

```kotlin
        db.execSQL(COLUMN_BUSINESSES_9)
```

In `onUpgrade`, after the `if (old < 8) { … }` block:

```kotlin
        if (old < 9) {
            // Nothing to carry over and nothing to mark: nothing was edited by
            // hand before this version could say so.
            db.execSQL(COLUMN_BUSINESSES_9)
        }
```

Change `const val VERSION = 8` to:

```kotlin
        const val VERSION = 9
```

- [ ] **Step 4: The refetch**

In `…/contacts/Preferences.kt`, after the property `refetchedForAddresses`:

```kotlin
    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt which master data was changed by hand (schema 9).
     *
     * While it still ran 1.5.0, the server delivered businesses whose
     * `edited_fields` another device had set, and the old app stored them
     * without the column while its watermark moved past. After the update
     * those rows hold NULL: an import here would overwrite the hand edits, and
     * the next save of such a business would send the NULL up as the newer
     * row. One fetch from zero brings each row again at a standstill, and the
     * store fills the gap (SyncStore.fillGaps). A flag for the reason
     * [refetchedForAppointments] gives.
     */
    var refetchedForEditedFields: Boolean
        get() = store.getBoolean(REFETCHED_FOR_EDITED_FIELDS, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_EDITED_FIELDS, value).apply()
```

In its companion, after `const val REFETCHED_FOR_ADDRESSES = "refetched_for_addresses"`:

```kotlin
        const val REFETCHED_FOR_EDITED_FIELDS = "refetched_for_edited_fields"
```

In `…/sync/SyncEngine.kt`, `sync`, after the block

```kotlin
            if (!prefs.refetchedForAddresses) {
                prefs.watermark = 0
                prefs.refetchedForAddresses = true
            }
```

add:

```kotlin
            // Once more, on the first sync that runs on schema 9 — see
            // Preferences.refetchedForEditedFields. The hand-edited fields a
            // 1.5.0 app could not store come down at a standstill.
            if (!prefs.refetchedForEditedFields) {
                prefs.watermark = 0
                prefs.refetchedForEditedFields = true
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest" --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt app/src/test/java/io/github/amadeusb/callsheet/SyncEngineTest.kt
git commit -m "Schema 9: edited_fields an Betrieben, einmal alles neu holen"
```

---

### Task 4: App — the rules for master data

**Repository:** app.

**Files:**
- Create: `…/data/MasterData.kt`
- Test: `test/MasterDataTest.kt` (new)

**Interfaces:**
- Produces, in package `io.github.amadeusb.callsheet.data`:

```kotlin
data class MasterValues(
    val name: String,
    val phone: String?,
    val industry: String?,
    val website: String?,
    val email: String?,
    val contactName: String?,
) { fun byColumn(): Map<String, String?> }

object MasterData {
    val EDITABLE: List<String>                       // name, phone, industry, website, email, contact_name
    fun of(business: Business): MasterValues
    fun fromDraft(draft: BusinessDraft): MasterValues // phone normalised; null when empty or incomplete
    fun changedFields(before: MasterValues, after: MasterValues): Set<String>
    fun parse(text: String?): Set<String>
    fun format(fields: Set<String>): String?
    fun draft(business: Business): BusinessDraft      // addresses = emptyList()
    fun contactNameLabel(editedFields: Set<String>): String // „Ansprechpartner (importiert)" / „Ansprechpartner"
}
```

- [ ] **Step 1: Write the failing test**

Create `test/MasterDataTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.BusinessDraft
import io.github.amadeusb.callsheet.data.MasterData
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which master data changed by hand, and how that is written down.
 * Robolectric only for `org.json`. Every name and number here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterDataTest {

    private fun business(
        name: String = "Elektro Beispiel GmbH",
        phone: String? = "+496219900011",
        industry: String? = "Elektro",
        website: String? = "elektro-beispiel.example",
        email: String? = null,
        contactName: String? = "Erika Beispiel",
    ) = Business(
        placeId = "P1", name = name, industry = industry, categories = emptyList(), city = "Ingolstadt",
        phone = phone, website = website, email = email, contactName = contactName,
        rating = null, ratingCount = null, closed = false, isTarget = true, origin = emptyList(),
        collectedAt = null, status = Status.NEW, note = null, updatedAt = "2026-09-07T10:00:00+02:00",
    )

    @Test
    fun `the editable columns are exactly these`() {
        assertEquals(listOf("name", "phone", "industry", "website", "email", "contact_name"), MasterData.EDITABLE)
    }

    @Test
    fun `the form opened on a business and saved unchanged changes nothing`() {
        val stored = business()

        val changed = MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(MasterData.draft(stored)))

        assertTrue(changed.isEmpty())
    }

    @Test
    fun `only the fields whose value changed are named`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(email = "test@example.org", contactName = "Max Beispiel")

        assertEquals(setOf("email", "contact_name"), MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(draft)))
    }

    @Test
    fun `clearing a field counts as a change`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(website = "  ", phone = "")

        val after = MasterData.fromDraft(draft)

        assertNull(after.website)
        assertNull(after.phone)
        assertEquals(setOf("phone", "website"), MasterData.changedFields(MasterData.of(stored), after))
    }

    @Test
    fun `whitespace and another notation of the same number are no change`() {
        val stored = business()
        val draft = MasterData.draft(stored).copy(name = "  Elektro Beispiel GmbH ", phone = "0621 990 0011")

        assertTrue(MasterData.changedFields(MasterData.of(stored), MasterData.fromDraft(draft)).isEmpty())
    }

    @Test
    fun `an incomplete number reads as no number, so the caller can refuse it`() {
        assertNull(MasterData.fromDraft(BusinessDraft(name = "Kurz", phone = "0621")).phone)
    }

    @Test
    fun `the list is written sorted and read back`() {
        val text = MasterData.format(setOf("phone", "email"))

        assertEquals("[\"email\",\"phone\"]", text)
        assertEquals(setOf("email", "phone"), MasterData.parse(text))
    }

    @Test
    fun `no fields is no list`() {
        assertNull(MasterData.format(emptySet()))
        assertTrue(MasterData.parse(null).isEmpty())
        assertTrue(MasterData.parse("").isEmpty())
    }

    @Test
    fun `a broken list reads as empty, a name from a later version is kept`() {
        assertTrue(MasterData.parse("kaputt").isEmpty())
        assertEquals(setOf("email"), MasterData.parse("[\"email\", null, \"\"]"))
        assertEquals(setOf("rating"), MasterData.parse("[\"rating\"]"))
    }

    @Test
    fun `the contact name loses its imported label once it was changed by hand`() {
        assertEquals("Ansprechpartner (importiert)", MasterData.contactNameLabel(emptySet()))
        assertEquals("Ansprechpartner (importiert)", MasterData.contactNameLabel(setOf("email", "phone")))
        assertEquals("Ansprechpartner", MasterData.contactNameLabel(setOf("contact_name")))
    }

    @Test
    fun `the draft carries the business's values and no addresses`() {
        val draft = MasterData.draft(business(email = null))

        assertEquals("Elektro Beispiel GmbH", draft.name)
        assertEquals("+496219900011", draft.phone)
        assertEquals("Elektro", draft.industry)
        assertEquals("elektro-beispiel.example", draft.website)
        assertEquals("", draft.email)
        assertEquals("Erika Beispiel", draft.contactName)
        assertTrue(draft.addresses.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MasterDataTest"`
Expected: FAIL to compile — `Unresolved reference: MasterData`.

- [ ] **Step 3: Write the rules**

Create `…/data/MasterData.kt`:

```kotlin
package io.github.amadeusb.callsheet.data

import org.json.JSONArray

/**
 * A business's master data as the user edits it: trimmed, empty as null, the
 * number normalised. Two of these compare equal when saving would change
 * nothing.
 */
data class MasterValues(
    val name: String,
    val phone: String?,
    val industry: String?,
    val website: String?,
    val email: String?,
    val contactName: String?,
) {
    /** The values by column name, in [MasterData.EDITABLE] order. */
    fun byColumn(): Map<String, String?> = linkedMapOf(
        "name" to name,
        "phone" to phone,
        "industry" to industry,
        "website" to website,
        "email" to email,
        "contact_name" to contactName,
    )
}

/**
 * Master data changed by hand, and the list that remembers it
 * (`businesses.edited_fields`). The import leaves every column named there as
 * it is stored, so a hand edit survives it — on every device, since the list
 * travels with the business.
 */
object MasterData {

    /** The columns the form in editing mode changes, by their column names. */
    val EDITABLE = listOf("name", "phone", "industry", "website", "email", "contact_name")

    fun of(business: Business): MasterValues = MasterValues(
        name = business.name.trim(),
        phone = business.phone.clean(),
        industry = business.industry.clean(),
        website = business.website.clean(),
        email = business.email.clean(),
        contactName = business.contactName.clean(),
    )

    /**
     * What the form holds. The number is normalised to E.164; empty or
     * incomplete it is null — the caller tells the two apart by the draft's
     * own text, as Repository.create does.
     */
    fun fromDraft(draft: BusinessDraft): MasterValues = MasterValues(
        name = draft.name.trim(),
        phone = draft.phone.trim().let { PhoneNumbers.normalize(it, it) },
        industry = draft.industry.clean(),
        website = draft.website.clean(),
        email = draft.email.clean(),
        contactName = draft.contactName.clean(),
    )

    /** The columns whose value differs. Clearing a field is a difference. */
    fun changedFields(before: MasterValues, after: MasterValues): Set<String> {
        val old = before.byColumn()
        val new = after.byColumn()
        return EDITABLE.filter { old[it] != new[it] }.toSet()
    }

    /**
     * The stored list. Unreadable text reads as empty. A name this version does
     * not edit is kept: a later version may edit more, and this version's
     * import must not undo that.
     */
    fun parse(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(text)
            (0 until array.length())
                .mapNotNull { i -> if (array.isNull(i)) null else array.optString(i, "").ifEmpty { null } }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Sorted, so two devices holding the same set hold the same text. Null for none. */
    fun format(fields: Set<String>): String? =
        if (fields.isEmpty()) null else JSONArray(fields.sorted()).toString()

    /** The form in editing mode, filled from [business]. Addresses have their own screen. */
    fun draft(business: Business): BusinessDraft = BusinessDraft(
        name = business.name,
        phone = business.phone.orEmpty(),
        industry = business.industry.orEmpty(),
        addresses = emptyList(),
        website = business.website.orEmpty(),
        email = business.email.orEmpty(),
        contactName = business.contactName.orEmpty(),
    )

    /**
     * The detail view's label for `contact_name`. „(importiert)" says where the
     * name came from — no longer true once it was changed by hand.
     */
    fun contactNameLabel(editedFields: Set<String>): String =
        if ("contact_name" in editedFields) "Ansprechpartner" else "Ansprechpartner (importiert)"

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MasterDataTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/MasterData.kt app/src/test/java/io/github/amadeusb/callsheet/MasterDataTest.kt
git commit -m "Regeln für Stammdaten: geänderte Felder, die Liste dazu, der Entwurf"
```

---

### Task 5: App — saving master data, and the import leaves hand edits alone

**Repository:** app.

**Files:**
- Modify: `…/data/Repository.kt` (`import`: the `same` query in the known-business branch; new private `keepEdited` after `matchesStored`; new `updateMasterData` after `create`; `fromCursor`; KDoc at the top of the class)
- Modify: `…/data/Models.kt` (`Business`: new property `editedFields`)
- Test: `test/RepositoryTest.kt` (new section before `private fun count(`; imports)

**Interfaces:**
- Consumes: `MasterData`, `MasterValues` (Task 4); column `edited_fields` (Task 3).
- Produces: `Business.editedFields: Set<String>` (default `emptySet()`, filled by every repository read of a business). `suspend fun updateMasterData(placeId: String, before: MasterValues, draft: BusinessDraft): Result<Unit>` in `Repository` — [before] is the business's `MasterData.of` as the form opened. Failure messages exactly „Ohne Namen lässt sich der Betrieb nicht speichern.", „Die Telefonnummer ist unvollständig. Lass sie leer oder trag sie vollständig ein.", „Den Betrieb gibt es auf diesem Gerät nicht mehr.".

- [ ] **Step 1: Write the failing tests**

In `test/RepositoryTest.kt`, add the imports:

```kotlin
import io.github.amadeusb.callsheet.data.BusinessDraft
import io.github.amadeusb.callsheet.data.MasterData
```

Insert this section before `private fun count(sql: String): Int =`:

```kotlin
    // ------------------------------------------------------------ Master data

    /** Opens the form on [placeId] as it is stored now and saves [change] of it. */
    private suspend fun edit(placeId: String, change: (BusinessDraft) -> BusinessDraft): Result<Unit> {
        val business = repo.business(placeId)!!
        return repo.updateMasterData(placeId, MasterData.of(business), change(MasterData.draft(business)))
    }

    private fun editedFields(placeId: String): String? =
        Database.instance(ApplicationProvider.getApplicationContext<android.content.Context>()).readableDatabase
            .rawQuery("SELECT edited_fields FROM businesses WHERE place_id = ?", arrayOf(placeId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Test
    fun `editing master data writes the changed fields, stamps and marks the business, and records them`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE businesses SET updated_at = '2026-01-01T00:00:00+01:00', dirty = 0 WHERE place_id = 'P1'")

        edit("P1") { it.copy(email = "test@example.org", phone = "0621 9900099") }.getOrThrow()

        val b = repo.business("P1")!!
        assertEquals("test@example.org", b.email)
        assertEquals("+496219900099", b.phone)
        assertTrue(b.updatedAt != "2026-01-01T00:00:00+01:00")
        assertEquals(1, count("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
        assertEquals("[\"email\",\"phone\"]", editedFields("P1"))
        // The business as read carries the list too — the detail view's label reads it.
        assertEquals(setOf("email", "phone"), b.editedFields)
    }

    @Test
    fun `the recorded fields grow across edits`() = runTest {
        import(FIRST_IMPORT)

        edit("P1") { it.copy(email = "test@example.org") }.getOrThrow()
        edit("P1") { it.copy(website = "beispiel.example") }.getOrThrow()

        assertEquals("[\"email\",\"website\"]", editedFields("P1"))
    }

    @Test
    fun `a change made elsewhere while the form was open stays and is not recorded as a hand edit`() = runTest {
        import(FIRST_IMPORT)
        val opened = repo.business("P1")!!
        // Another device changes the email, the sync brings it in, the form is still open.
        execute("UPDATE businesses SET email = 'anderes-geraet@example.org' WHERE place_id = 'P1'")

        repo.updateMasterData("P1", MasterData.of(opened), MasterData.draft(opened).copy(phone = "0621 9900099")).getOrThrow()

        val b = repo.business("P1")!!
        assertEquals("anderes-geraet@example.org", b.email)
        assertEquals("+496219900099", b.phone)
        assertEquals("[\"phone\"]", editedFields("P1"))
    }

    @Test
    fun `a change to name or email leaves is_target alone`() = runTest {
        import(FIRST_IMPORT)
        // Not what the rule would say for this business: only a changed phone or industry may rewrite it.
        execute("UPDATE businesses SET is_target = 0 WHERE place_id = 'P1'")

        edit("P1") { it.copy(email = "test@example.org") }.getOrThrow()

        assertFalse(repo.business("P1")!!.isTarget)
    }

    @Test
    fun `saving master data unchanged writes nothing`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE businesses SET dirty = 0")
        val before = repo.business("P1")!!.updatedAt

        edit("P1") { it }.getOrThrow()

        assertEquals(before, repo.business("P1")!!.updatedAt)
        assertEquals(0, count("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
        assertNull(editedFields("P1"))
    }

    @Test
    fun `clearing the number records it and takes the business out of the target set`() = runTest {
        import(FIRST_IMPORT)
        assertTrue(repo.business("P1")!!.isTarget)

        edit("P1") { it.copy(phone = "") }.getOrThrow()

        val b = repo.business("P1")!!
        assertNull(b.phone)
        assertFalse(b.isTarget)
        assertEquals("[\"phone\"]", editedFields("P1"))
    }

    @Test
    fun `a new name is found by the search`() = runTest {
        import(FIRST_IMPORT)

        edit("P1") { it.copy(name = "Lichttechnik Erfunden") }.getOrThrow()

        val found = repo.list(Filter(status = emptySet(), onlyTargets = false, search = "lichttechnik"))
        assertEquals(listOf("P1"), found.map { it.placeId })
        // The cities stay in the search text.
        assertTrue(repo.list(Filter(status = emptySet(), onlyTargets = false, search = "ingolstadt")).any { it.placeId == "P1" })
    }

    @Test
    fun `master data without a name or with an incomplete number is refused, nothing written`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE businesses SET dirty = 0")

        val noName = edit("P1") { it.copy(name = "  ") }
        val shortNumber = edit("P1") { it.copy(phone = "0621") }

        assertEquals("Ohne Namen lässt sich der Betrieb nicht speichern.", noName.exceptionOrNull()!!.message)
        assertTrue(shortNumber.exceptionOrNull()!!.message!!.contains("unvollständig"))
        assertEquals("Elektro Beispiel GmbH", repo.business("P1")!!.name)
        assertEquals(0, count("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `master data may take a number another business holds`() = runTest {
        import(FIRST_IMPORT)

        val result = edit("P2") { it.copy(phone = "+49 621 990 0011") }

        assertTrue(result.isSuccess)
        assertEquals("+496219900011", repo.business("P2")!!.phone)
    }

    @Test
    fun `a re-import leaves hand-edited fields alone and updates the rest`() = runTest {
        import(FIRST_IMPORT)
        edit("P1") { it.copy(name = "Elektro von Hand", email = "test@example.org") }.getOrThrow()

        import(SECOND_IMPORT)

        val b = repo.business("P1")!!
        assertEquals("Elektro von Hand", b.name)
        assertEquals("test@example.org", b.email)
        // Not edited: taken from the file as before.
        assertEquals(listOf("Elektriker", "Handwerk"), b.categories)
        assertEquals("[\"email\",\"name\"]", editedFields("P1"))
    }

    @Test
    fun `a re-import that differs only in hand-edited fields writes nothing`() = runTest {
        import(FIRST_IMPORT)
        edit("P1") { it.copy(name = "Elektro von Hand") }.getOrThrow()
        execute("UPDATE businesses SET dirty = 0")
        execute("UPDATE business_addresses SET dirty = 0")
        val before = repo.business("P1")!!.updatedAt

        val e = import(FIRST_IMPORT)

        assertEquals(0, e.updated)
        assertEquals(before, repo.business("P1")!!.updatedAt)
        assertEquals(0, count("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a number cleared by hand keeps the business out of the target set on re-import`() = runTest {
        import(FIRST_IMPORT)
        edit("P1") { it.copy(phone = "") }.getOrThrow()

        import(FIRST_IMPORT)

        val b = repo.business("P1")!!
        assertNull(b.phone)
        assertFalse(b.isTarget)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: FAIL to compile — `Unresolved reference: updateMasterData`.

- [ ] **Step 3: Save master data**

In `…/data/Repository.kt`, after the function `create` (after its closing `}` and before `/** Appends an entry to the log. */`), add:

```kotlin
    /**
     * Saves the master data of an existing business from the form in editing
     * mode: name, number, industry, website, email, contact name. Addresses,
     * note and status are saved elsewhere.
     *
     * Validated as [create] validates: a name, and a number that is empty or
     * complete. Another business holding the number is fine.
     *
     * Only columns the user changed in the form are written: compared with
     * [before], the values as the form opened (MasterData.changedFields), not
     * with what is stored now — a column another device changed meanwhile
     * stays as that device left it and is not taken for a hand edit. Each
     * written column is added to `edited_fields`; the import leaves it alone
     * from then on. Unchanged columns keep their stored text: rewritten
     * trimmed, they would look changed to the next import. With a change,
     * `updated_at` and the mark move and `search_text` follows; `is_target`
     * only when phone or industry changed. Without one, nothing is written.
     */
    suspend fun updateMasterData(placeId: String, before: MasterValues, draft: BusinessDraft): Result<Unit> = withContext(Dispatchers.IO) {
        val after = MasterData.fromDraft(draft)
        if (after.name.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Ohne Namen lässt sich der Betrieb nicht speichern."))
        }
        if (draft.phone.isNotBlank() && after.phone == null) {
            return@withContext Result.failure(
                IllegalArgumentException("Die Telefonnummer ist unvollständig. Lass sie leer oder trag sie vollständig ein.")
            )
        }

        val db = helper.writableDatabase
        var written = false
        db.beginTransaction()
        try {
            val (stored, edited) = db.rawQuery("SELECT * FROM businesses WHERE place_id = ?", arrayOf(placeId))
                .use { c -> if (c.moveToFirst()) fromCursor(c) to MasterData.parse(c.text("edited_fields")) else null }
                ?: return@withContext Result.failure(IllegalStateException("Den Betrieb gibt es auf diesem Gerät nicht mehr."))
            val changed = MasterData.changedFields(before, after)
            if (changed.isNotEmpty()) {
                val newValues = after.byColumn()
                val values = ContentValues().apply {
                    for (column in changed) put(column, newValues[column])
                    if ("phone" in changed || "industry" in changed) {
                        // The other of the two as stored now: it may have changed elsewhere.
                        val phone = if ("phone" in changed) after.phone else stored.phone
                        val industry = if ("industry" in changed) after.industry else stored.industry
                        put("is_target", if (TargetRule.isTarget(industry, phone, stored.closed)) 1 else 0)
                    }
                    put("edited_fields", MasterData.format(edited + changed))
                    put("updated_at", Clock.now())
                    put("dirty", 1)
                }
                db.update("businesses", values, "place_id = ?", arrayOf(placeId))
                AddressRows.refreshSearchText(db, placeId)
                written = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (written) notifyChanged()
        Result.success(Unit)
    }
```

- [ ] **Step 4: The import leaves edited columns alone**

In `import`, replace:

```kotlin
                    val same = db.rawQuery(
                        "SELECT * FROM businesses WHERE place_id = ?", arrayOf(s.placeId),
                    ).use { c -> c.moveToFirst() && matchesStored(c, values) }
```

with:

```kotlin
                    // Columns changed by hand are left out first: a file that
                    // differs only there writes nothing.
                    val same = db.rawQuery(
                        "SELECT * FROM businesses WHERE place_id = ?", arrayOf(s.placeId),
                    ).use { c ->
                        c.moveToFirst() && run {
                            keepEdited(c, values)
                            matchesStored(c, values)
                        }
                    }
```

After the function `matchesStored`, add:

```kotlin
    /**
     * Takes the columns changed by hand (`edited_fields`, see [MasterData]) out
     * of [values], so the import neither compares nor writes them. `is_target`
     * then follows the stored number and industry where those were edited, and
     * the file's values of the rest — a number cleared by hand keeps the
     * business out of the target set.
     */
    private fun keepEdited(c: Cursor, values: ContentValues) {
        val edited = MasterData.parse(c.text("edited_fields"))
        if (edited.isEmpty()) return
        for (column in edited) values.remove(column)
        if ("phone" in edited || "industry" in edited) {
            val industry = if ("industry" in edited) c.text("industry") else values.getAsString("industry")
            val phone = if ("phone" in edited) c.text("phone") else values.getAsString("phone")
            val closed = (values.getAsInteger("closed") ?: 0) == 1
            values.put("is_target", if (TargetRule.isTarget(industry, phone, closed)) 1 else 0)
        }
    }
```

In `…/data/Models.kt`, `data class Business`, after `val additionalNumbers: Int = 0,`:

```kotlin
    /** The master data columns changed by hand (`edited_fields`, see MasterData). */
    val editedFields: Set<String> = emptySet(),
```

In `…/data/Repository.kt`, `fromCursor`, after `additionalNumbers = c.int("additional_numbers") ?: 0,`:

```kotlin
        editedFields = MasterData.parse(c.text("edited_fields")),
```

In the class KDoc at the top of the file, replace rule 2:

```kotlin
 * 2. Re-importing only refreshes imported master data. Status, note, appointments
 *    and call history are never touched.
```

with:

```kotlin
 * 2. Re-importing only refreshes imported master data, and none that was
 *    changed by hand (`edited_fields`). Status, note, appointments and call
 *    history are never touched.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest"`
Expected: PASS, the existing import tests included (`a second import leaves the work untouched`, `a re-import of the same addresses writes nothing`).

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt
git commit -m "Stammdaten speichern, der Import lässt von Hand Geändertes stehen"
```

---

### Task 6: App — „Stammdaten bearbeiten" in the detail view and the form

**Repository:** app.

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`Screen`; `back()`; new functions after `saveDraft`)
- Modify: `…/ui/BusinessForm.kt` (`BusinessFormScreen`)
- Modify: `…/ui/BusinessDetail.kt` (`BusinessDetailScreen` parameters; the `master-data` item; `MasterData`, its contact name label included)
- Modify: `…/MainActivity.kt` (`Screen.Detail` branch; new branch after `Screen.BusinessForm`)

**Interfaces:**
- Consumes: `Repository.updateMasterData`, `Business.editedFields` (Task 5), `MasterData.draft`, `MasterData.contactNameLabel` (Task 4).
- Produces: `Screen.MasterDataForm(placeId: String)`; `CallsheetViewModel.showMasterData(placeId: String)`, `saveMasterData(placeId: String)`; `BusinessFormScreen(…, editing: Boolean = false)`; `BusinessDetailScreen(…, onEditMasterData: () -> Unit, …)`.

No unit test: view model and Compose wiring (Global Constraints). The rules behind it are tested in Tasks 4 and 5; this task is checked by `assembleDebug` and Task 10.

- [ ] **Step 1: The screen and the view model**

In `…/CallsheetViewModel.kt`, in `sealed interface Screen`, after `data object BusinessForm : Screen`:

```kotlin

    /** The business form in editing mode, for an existing business's master data. */
    data class MasterDataForm(val placeId: String) : Screen
```

In `back()`, in the `when (previous)`, after `is Screen.BusinessForm -> Unit`:

```kotlin
            is Screen.MasterDataForm -> Unit
```

In `State`, after `val formError: String? = null,`:

```kotlin
    /** The master data as the editing form opened: what saving compares with (Repository.updateMasterData). */
    val masterDataBefore: MasterValues? = null,
```

Add the imports `import io.github.amadeusb.callsheet.data.MasterData` and `import io.github.amadeusb.callsheet.data.MasterValues` to the file's imports.

After the function `saveDraft()`, add:

```kotlin
    /** Opens the business form in editing mode, filled from the business on show. */
    fun showMasterData(placeId: String) {
        val business = _state.value.detail?.takeIf { it.placeId == placeId } ?: return
        val screen = Screen.MasterDataForm(placeId)
        val history = historyFor(screen)
        _state.update {
            it.copy(
                screen = screen,
                history = history,
                draft = MasterData.draft(business),
                formError = null,
                masterDataBefore = MasterData.of(business),
            )
        }
    }

    /**
     * Saves the master data and returns to the record. The phone book follows,
     * the way it follows a saved contact: a changed number, name or email
     * belongs in the entries at once.
     */
    fun saveMasterData(placeId: String) {
        if (_state.value.saving) return
        val draft = _state.value.draft
        val before = _state.value.masterDataBefore ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, formError = null) }
            repo.updateMasterData(placeId, before, draft).fold(
                onSuccess = {
                    // Off the form first, and only then no longer saving: a second
                    // tap on „Stammdaten speichern" must not find the form still there.
                    // back() reloads the detail view.
                    back()
                    val industries = repo.industries()
                    _state.update {
                        it.copy(saving = false, draft = BusinessDraft(), masterDataBefore = null, allIndustries = industries)
                    }
                    repo.business(placeId)?.let { store.persistBusiness(it) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(saving = false, formError = error.message ?: "Die Stammdaten ließen sich nicht speichern.")
                    }
                },
            )
        }
    }
```

- [ ] **Step 2: The form's editing mode**

In `…/ui/BusinessForm.kt`:

Replace the KDoc above `@OptIn(ExperimentalMaterial3Api::class)` of `BusinessFormScreen`:

```kotlin
/**
 * Entering a single business by hand — the referral over the phone, the business
 * card from a trade fair. Importing stays the usual route; this is the one-off.
 *
 * The screen holds no state: every keystroke reports the complete new draft
 * upwards. Only the presentation itself (scroll position) stays here.
 */
```

with:

```kotlin
/**
 * Entering a single business by hand — the referral over the phone, the business
 * card from a trade fair. Importing stays the usual route; this is the one-off.
 *
 * With [editing] the same form edits an existing business's master data: name,
 * number, industry, contact name, website, email. Addresses have their own
 * screen, note and status stay in the detail view, and the origin is recorded
 * once, when a business is created — so those sections are left out.
 *
 * The screen holds no state: every keystroke reports the complete new draft
 * upwards. Only the presentation itself (scroll position) stays here.
 */
```

Add a parameter after `onCancel: () -> Unit,`:

```kotlin
    editing: Boolean = false,
```

Replace `title = { Text("Neuer Betrieb") },` with:

```kotlin
                title = { Text(if (editing) "Stammdaten bearbeiten" else "Neuer Betrieb") },
```

Replace `Text("Betrieb speichern", style = MaterialTheme.typography.titleMedium)` with:

```kotlin
                            Text(
                                if (editing) "Stammdaten speichern" else "Betrieb speichern",
                                style = MaterialTheme.typography.titleMedium,
                            )
```

Wrap the addresses section — from `// ---- Addresses ----…` through the closing `)` of `AddressList(…)` — in `if (!editing) { … }`:

```kotlin
            // ---- Addresses --------------------------------------------------
            // Not when editing: an existing business's addresses have their own screen.
            if (!editing) {
                Section("Adressen")

                AddressList(
                    drafts = draft.addresses,
                    knownCities = knownCities,
                    onChange = { onChange(draft.copy(addresses = it)) },
                )
            }
```

Wrap the origin and note section — from `// ---- Origin and note ----…` through the closing `)` of the „Notiz" `Field(…)` — the same way:

```kotlin
            // ---- Origin and note --------------------------------------------
            // Not when editing: the note lives in the detail view, the origin is recorded once.
            if (!editing) {
                Section("Herkunft und Notiz")

                Field(
                    value = draft.origin,
                    onValue = { onChange(draft.copy(origin = it)) },
                    label = "Herkunft",
                    placeholder = "z. B. Empfehlung von Firma Weber, Visitenkarte Messe Bau 2026",
                    keyboard = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )

                Field(
                    value = draft.note,
                    onValue = { onChange(draft.copy(note = it)) },
                    label = "Notiz",
                    placeholder = "Was sonst noch wichtig ist",
                    singleLine = false,
                    minHeight = 120,
                    keyboard = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
```

- [ ] **Step 3: The button in the detail view**

In `…/ui/BusinessDetail.kt`:

In the parameters of `BusinessDetailScreen`, after `onEditAddresses: () -> Unit,`:

```kotlin
    onEditMasterData: () -> Unit,
```

In the item `master-data`, the call `MasterData(…)` gains, after `onEditAddresses = onEditAddresses,`:

```kotlin
                    onEditMasterData = onEditMasterData,
```

In the private composable `MasterData`, replace

```kotlin
        DataRow("Ansprechpartner (importiert)", business.contactName)
```

with:

```kotlin
        // „(importiert)" only while the name is the imported one — see MasterData.contactNameLabel.
        DataRow(io.github.amadeusb.callsheet.data.MasterData.contactNameLabel(business.editedFields), business.contactName)
```

The name is written out in full because this file's own composable is called `MasterData` too.

The private composable `MasterData` gains a parameter after `onEditAddresses: () -> Unit,`:

```kotlin
    onEditMasterData: () -> Unit,
```

and at the end of its `Column`, after `DataRow("Zuletzt geändert", Clock.readable(business.updatedAt))`:

```kotlin
        TextButton(
            onClick = onEditMasterData,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("Stammdaten bearbeiten")
        }
```

- [ ] **Step 4: Wire it in `MainActivity`**

In `…/MainActivity.kt`, in the `BusinessDetailScreen(…)` call, after `onEditAddresses = { vm.showAddresses(business.placeId) },`:

```kotlin
                    onEditMasterData = { vm.showMasterData(business.placeId) },
```

After the branch `is Screen.BusinessForm -> BusinessFormScreen(…)` (after its closing `)`), add:

```kotlin

        is Screen.MasterDataForm -> BusinessFormScreen(
            draft = state.draft,
            knownIndustries = state.allIndustries,
            knownCities = state.allCities,
            error = state.formError,
            saving = state.saving,
            onChange = vm::updateDraft,
            onSave = { vm.saveMasterData(screen.placeId) },
            onCancel = { vm.back() },
            editing = true,
        )
```

- [ ] **Step 5: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. A `when` that is not exhaustive (`Screen.MasterDataForm` missing) fails the build here — add the branch where the compiler points.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessForm.kt app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Stammdaten bearbeiten: Knopf in der Detailansicht, Formular im Bearbeitungsmodus"
```

---

### Task 7: App — the read-back never deletes a visit

**Repository:** app.

**Files:**
- Modify: `…/calling/Appointment.kt` (`Reconcile.DeletedInCalendar`, `Reconcile.MissingInvited`; KDoc of `CalendarLine`; KDoc of `calendarLine`; after `cancellationNotice`; `reconcile`)
- Modify: `…/CallsheetViewModel.kt` (`State.detailMissingInCalendar` KDoc; `reconcile`; `reconcileAppointments`; `removeAppointment`)
- Modify: `…/ui/BusinessDetail.kt` (`BusinessDetailScreen` parameters; the `appointment` item; `AppointmentsBlock`; `AppointmentItem`)
- Modify: `…/MainActivity.kt` (`BusinessDetailScreen` call)
- Test: `test/AppointmentTest.kt`

**Interfaces:**
- Produces: `Reconcile.MissingVisit` (replaces `MissingInvited`); `Appointment.reconcile(row, seen, event, nowMillis, visit = false)` — the `invited` parameter is gone; `Appointment.removalAsks(entry: AppointmentEntry, missing: Boolean): Boolean`; `Appointment.removedHint(fallback: Status?): String`; `CallsheetViewModel.removeAppointment(appointmentId: String, confirmed: Boolean = true)`; `BusinessDetailScreen(…, onRemoveAppointmentUnasked: (String) -> Unit, …)`.

- [ ] **Step 1: Write the failing tests**

In `test/AppointmentTest.kt`, replace the three tests `an invited visit gone from the calendar is reported, never deleted`, `a visit without an invitation gone from the calendar is deleted as before` and `the visit rules leave a callback alone` with:

```kotlin
    @Test
    fun `a visit gone from the calendar is reported, never deleted`() {
        // A calendar deselected in DAVx5 or a new account looks the same. A
        // deletion would remove the visit on the server and at Infomaniak, and
        // send an invitee a cancellation. Whether someone is invited no longer
        // matters.
        assertEquals(
            Reconcile.MissingVisit,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore, visit = true),
        )
        assertEquals(
            Reconcile.Unlink,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayAfter, visit = true),
        )
        assertEquals(
            Reconcile.NotYetHere,
            Appointment.reconcile(row = planned, seen = null, event = null, nowMillis = dayBefore, visit = true),
        )
    }

    @Test
    fun `the visit rules leave a callback alone`() {
        assertEquals(Reconcile.TakeEvent(planned), Appointment.reconcile(row = later, seen = null, event = planned, nowMillis = dayBefore))
        assertEquals(
            Reconcile.DeletedInCalendar,
            Appointment.reconcile(row = planned, seen = planned, event = null, nowMillis = dayBefore),
        )
    }
```

After the test `an invited visit missing from the calendar offers its removal instead of a state`, add:

```kotlin
    @Test
    fun `a visit without an invitation missing from the calendar offers its removal too`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00").copy(eventUid = "abc", calendarState = CalendarState.OK)

        assertEquals(
            CalendarLine("Im Kalender nicht mehr gefunden", error = true, offersRemoval = true),
            Appointment.calendarLine(visit, syncConfigured = true, missing = true),
        )
    }
```

After the test `removing an invited visit says who gets a cancellation`, add:

```kotlin
    @Test
    fun `removing a visit missing from the calendar asks only when someone gets a cancellation`() {
        val visit = entry("A-1", "2026-09-10T14:00:00+02:00")

        assertFalse(Appointment.removalAsks(visit, missing = true))
        assertTrue(Appointment.removalAsks(visit.copy(inviteEmail = "test@example.org"), missing = true))
        // The ordinary „Entfernen" always asks: it removes a piece of the record.
        assertTrue(Appointment.removalAsks(visit, missing = false))
        assertTrue(Appointment.removalAsks(visit.copy(kind = AppointmentKind.CALLBACK), missing = false))
    }

    @Test
    fun `a removal that did not ask says what it did`() {
        assertEquals("Termin entfernt.", Appointment.removedHint(null))
        assertEquals("Termin entfernt. Status zurück auf „Angerufen“.", Appointment.removedHint(Status.CALLED))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile — `Unresolved reference: MissingVisit`, `removalAsks`, `removedHint`.

- [ ] **Step 3: The rules**

In `…/calling/Appointment.kt`:

Replace

```kotlin
    /** Seen before, gone now, appointment still ahead: deleted in the calendar. */
    data object DeletedInCalendar : Reconcile

    /**
     * A visit with an invitation: seen before, gone now, still ahead. Not
     * deleted — a calendar deselected in DAVx5 or an account set up again
     * looks the same, and a deletion would send the customer a cancellation.
     * Nothing is stored; the detail view offers the removal.
     */
    data object MissingInvited : Reconcile
```

with:

```kotlin
    /** A callback: seen before, gone now, still ahead — deleted in the calendar. */
    data object DeletedInCalendar : Reconcile

    /**
     * A visit: seen before, gone now, still ahead. Never deleted — a calendar
     * deselected in DAVx5 or an account set up again looks the same, and a
     * deletion would remove the visit on every device and at Infomaniak, with a
     * cancellation where someone was invited. Nothing is stored; the detail
     * view offers the removal.
     */
    data object MissingVisit : Reconcile
```

In the KDoc of `CalendarLine`, replace `it — see Reconcile.MissingInvited.` with `it — see Reconcile.MissingVisit.`

In the KDoc of `calendarLine`, replace

```kotlin
     * pending, and nothing is said. [missing]: the read-back did not find the
     * event of this invited visit (Reconcile.MissingInvited) — said instead of
```

with:

```kotlin
     * pending, and nothing is said. [missing]: the read-back did not find the
     * event of this visit (Reconcile.MissingVisit) — said instead of
```

After the function `cancellationNotice`, add:

```kotlin
    /**
     * Whether removing [entry] asks first. The ordinary „Entfernen" always does:
     * it removes a piece of the record. „Termin entfernen" under a visit the
     * calendar no longer holds ([missing]) asks only when someone gets a
     * cancellation ([cancellationNotice]); without one nobody outside hears of
     * it.
     */
    fun removalAsks(entry: AppointmentEntry, missing: Boolean): Boolean =
        !missing || cancellationNotice(entry) != null

    /** The hint after a removal that did not ask: that it went, and the status it fell back to, if any. */
    fun removedHint(fallback: Status?): String =
        if (fallback == null) "Termin entfernt." else "Termin entfernt. Status zurück auf „${fallback.label}“."
```

In the KDoc of `reconcile`, replace

```kotlin
     * A [visit] differs in two places. On first sight it takes nothing
     * (NotYetHere) — only an event equal to the row is recorded. And gone while
     * still ahead with an invitation ([invited]) it is MissingInvited, not
     * deleted. UpdateEvent for a visit means only that the row is ahead; the
     * server writes the event, not this device.
```

with:

```kotlin
     * A [visit] differs in two places. On first sight it takes nothing
     * (NotYetHere) — only an event equal to the row is recorded. And gone while
     * still ahead it is MissingVisit, never deleted, invitation or not.
     * UpdateEvent for a visit means only that the row is ahead; the server
     * writes the event, not this device.
```

In the signature of `reconcile`, delete the line `        invited: Boolean = false,`. In its body, replace

```kotlin
                visit && invited -> Reconcile.MissingInvited
```

with:

```kotlin
                visit -> Reconcile.MissingVisit
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL to compile in `CallsheetViewModel.kt` (`MissingInvited`, `invited`) — the main sources still use the old names. Go on with Step 5; the tests run in Step 7.

- [ ] **Step 5: The view model**

In `…/CallsheetViewModel.kt`:

In `State`, replace the KDoc of `detailMissingInCalendar`:

```kotlin
    /**
     * The shown business's invited visits the last read-back did not find in
     * the calendar (Reconcile.MissingInvited). Not stored: the next opening
     * decides again, and a found event takes its id out.
     */
```

with:

```kotlin
    /**
     * The shown business's visits the last read-back did not find in the
     * calendar (Reconcile.MissingVisit). Not stored: the next opening decides
     * again, and a found event takes its id out.
     */
```

In `reconcile`, replace

```kotlin
            // First sight takes nothing, and an invited visit is never deleted — see Appointment.reconcile.
            visit = visit,
            invited = visit && entry.inviteEmail != null,
        )
```

with:

```kotlin
            // First sight takes nothing, and a visit is never deleted — see Appointment.reconcile.
            visit = visit,
        )
```

and in its `when (outcome)` replace

```kotlin
            // Nothing deleted, nothing stored: reconcileAppointments reports it.
            Reconcile.MissingInvited -> Unit
```

with:

```kotlin
            // Nothing deleted, nothing stored: reconcileAppointments reports it.
            Reconcile.MissingVisit -> Unit
```

In `reconcileAppointments`, replace the KDoc sentence

```kotlin
     * opening it. A deletion in the calendar is the one case that speaks up,
     * because it is the one that may take the status back.
```

with:

```kotlin
     * opening it. A callback deleted in the calendar is deleted here and said
     * out loud; a visit gone from the calendar is only shown as missing.
```

and replace

```kotlin
                if (stillShown()) {
                    val missing = results.filter { it.second == Reconcile.MissingInvited }.map { it.first.id }.toSet()
                    _state.update { it.copy(detailMissingInCalendar = missing) }
                }
                if (results.isEmpty()) return@launch
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

with:

```kotlin
                if (stillShown()) {
                    val missing = results.filter { it.second == Reconcile.MissingVisit }.map { it.first.id }.toSet()
                    _state.update { it.copy(detailMissingInCalendar = missing) }
                }
                if (results.isEmpty()) return@launch
                // Only a callback is deleted by the read-back — a visit gone
                // missing is MissingVisit — and a callback never changes the status.
                if (results.any { it.second == Reconcile.DeletedInCalendar } && stillShown()) {
                    _state.update { it.copy(hint = "Der Rückruf wurde im Kalender gelöscht. Der Status bleibt, wie er ist.") }
                }
```

In `removeAppointment`, replace the KDoc and the signature

```kotlin
    /**
     * Removes an appointment and its calendar event — a past visit or a
     * completed callback too; the detail view asks first. Where the event is not
     * on this device, the row goes alone and the device holding the event
     * deletes it after its next sync. Only a visit can take the status back.
     */
    fun removeAppointment(appointmentId: String) {
```

with:

```kotlin
    /**
     * Removes an appointment and its calendar event — a past visit or a
     * completed callback too. The detail view asks first, except for a visit
     * missing from the calendar that invites nobody (Appointment.removalAsks);
     * then [confirmed] is false and a hint says what happened. Where the event
     * is not on this device, the row goes alone and the device holding the
     * event deletes it after its next sync. Only a visit can take the status
     * back.
     */
    fun removeAppointment(appointmentId: String, confirmed: Boolean = true) {
```

and in its visit branch replace

```kotlin
                repo.deleteAppointment(entry.id)
                Appointment.statusAfterRemoval(business.status, repo.appointments(entry.placeId), System.currentTimeMillis())
                    ?.let { repo.setStatus(entry.placeId, it) }
                loadDetail(entry.placeId)
```

with:

```kotlin
                repo.deleteAppointment(entry.id)
                val fallback = Appointment.statusAfterRemoval(
                    business.status, repo.appointments(entry.placeId), System.currentTimeMillis(),
                )
                fallback?.let { repo.setStatus(entry.placeId, it) }
                // No dialog said it: the hint does.
                if (!confirmed) _state.update { it.copy(hint = Appointment.removedHint(fallback)) }
                loadDetail(entry.placeId)
```

- [ ] **Step 6: The detail view**

In `…/ui/BusinessDetail.kt`:

In the parameters of `BusinessDetailScreen`, after `onRemoveAppointment: (String) -> Unit,`:

```kotlin
    /** Removes an appointment without asking — see Appointment.removalAsks. */
    onRemoveAppointmentUnasked: (String) -> Unit,
```

In the item `appointment`, the call `AppointmentsBlock(…)` gains, after `onRemove = { removeAppointment = it },`:

```kotlin
                    onRemoveNow = onRemoveAppointmentUnasked,
```

In `AppointmentsBlock`, add a parameter after `onRemove: (AppointmentEntry) -> Unit,`:

```kotlin
    onRemoveNow: (String) -> Unit,
```

and in both calls of `AppointmentItem` inside it, replace `onRemove, onOpenUrl,` with `onRemove, onRemoveNow, onOpenUrl,` — the two lines read:

```kotlin
        ahead.forEach { AppointmentItem(it, contacts, onSet, onRemove, onRemoveNow, onOpenUrl, syncConfigured, it.id in missingInCalendar) }
```

```kotlin
            if (showPast) past.forEach { AppointmentItem(it, contacts, onSet, onRemove, onRemoveNow, onOpenUrl, syncConfigured, it.id in missingInCalendar) }
```

In `AppointmentItem`, add a parameter after `onRemove: (AppointmentEntry) -> Unit,`:

```kotlin
    onRemoveNow: (String) -> Unit,
```

and replace

```kotlin
            // Not found with an invitation: removing it is offered, never done
            // unasked. It goes through the dialog, which names who gets the
            // cancellation.
            if (line.offersRemoval) {
                TextButton(onClick = { onRemove(entry) }) { Text("Termin entfernen") }
            }
```

with:

```kotlin
            // Not found: removing it is offered, never done on its own. With an
            // invitation it goes through the dialog, which names who gets the
            // cancellation; without one nobody outside hears of it, and it goes
            // at once.
            if (line.offersRemoval) {
                TextButton(onClick = {
                    if (Appointment.removalAsks(entry, missing = true)) onRemove(entry) else onRemoveNow(entry.id)
                }) { Text("Termin entfernen") }
            }
```

In `…/MainActivity.kt`, in the `BusinessDetailScreen(…)` call, replace `onRemoveAppointment = vm::removeAppointment,` with:

```kotlin
                    onRemoveAppointment = { vm.removeAppointment(it) },
                    onRemoveAppointmentUnasked = { vm.removeAppointment(it, confirmed = false) },
```

- [ ] **Step 7: Build and test**

Run: `grep -rn "MissingInvited\|invited = " app/src/main app/src/test`
Expected: no output.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `AppointmentTest` passes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Besuche werden beim Abgleich nie gelöscht, auch ohne Einladung"
```

---

### Task 8: App — documentation and changelog

**Repository:** app.

**Files:**
- Modify: `docs/data-model.md` (`businesses` block; section „Synchronisation"; section „Import")
- Modify: `docs/usage.md` (section „Appointments on site"; new section after „Entering a single business by hand")
- Modify: `CHANGELOG.md`

**Interfaces:** none.

- [ ] **Step 1: Data model**

In `docs/data-model.md`, in the first code block of `### businesses` (the master data), after the line `collected_at    TEXT            -- ISO-8601`, add:

```
edited_fields   TEXT            -- since schema 9: JSON array of the columns changed by hand, e.g. ["email","phone"]
```

Replace the line `Master data, overwritten by every import:` with:

```
Master data, overwritten by every import — except the columns named in
`edited_fields`:
```

After the paragraph that begins „`is_target` = 1 when the industry …", add:

```markdown
`edited_fields` names the master data columns changed by hand in the app
(„Stammdaten bearbeiten"): `name`, `phone`, `industry`, `website`, `email`,
`contact_name`, sorted; NULL for none. Clearing a field counts. The list only
grows. The import leaves those columns as they are; `is_target` then follows the
stored number and industry where those were edited. The list travels with the
business: the newer row wins with its list, and lists are never merged — a list
describes the values of its own row.
```

In section „Synchronisation", replace

```markdown
sync on schema 6 does so once more: a 1.4.0 app stored the callbacks it pulled
without `kind` and `done_at`.
```

with:

```markdown
sync on schema 6 does so once more: a 1.4.0 app stored the callbacks it pulled
without `kind` and `done_at`. Schemas 7 and 9 do it once each, for the addresses
and for `edited_fields` a 1.5.x app could not store.
```

In section „Calendar", replace

```markdown
  saw means nothing yet. One seen before and gone is a deletion while the
  appointment is ahead — the appointment goes, and the status falls back to
  `called` if it was still `appointment` and no other appointment is ahead — and
  only a lost link once it is past.
```

with:

```markdown
  saw means nothing yet. One seen before and gone, while the appointment is
  ahead, is a deletion for a callback only — the callback goes, and the status
  stays. A visit is never removed that way: a calendar deselected in DAVx5 looks
  the same. The detail view says „Im Kalender nicht mehr gefunden" and offers
  „Termin entfernen". Once an appointment is past, only the link goes.
```

In section „Import", replace

```markdown
3. Known businesses: **master data only.** Status, note, callbacks,
```

with:

```markdown
3. Known businesses: **master data only**, and none that was changed by hand
   (`edited_fields`). Status, note, callbacks,
```

- [ ] **Step 2: Usage**

In `docs/usage.md`, section „Appointments on site", replace the text from „Delete the entry in the calendar and the appointment is removed" up to and including „If the entry turns up again, the note goes." — currently:

```markdown
Delete the
entry in the calendar and the appointment is removed — said out loud, because
the status falls back to „Angerufen" once no other appointment is ahead. Not so
with an invitation: the detail view says „Im Kalender nicht mehr gefunden" and
offers **Termin entfernen**, which asks first, as the invitee would get a
cancellation. If the entry turns up again, the note goes.
```

with:

```markdown
If the
entry is gone from the calendar, the visit is **not** removed — a calendar
switched off in DAVx5 looks exactly the same. The detail view says „Im Kalender
nicht mehr gefunden" and offers **Termin entfernen**: without an invitation it
removes the visit at once and says so, with an invitation it asks first, as the
invitee gets a cancellation. If the entry turns up again, the note goes. A
callback deleted in the calendar is removed, and the app says so.
```

After the section „Entering a single business by hand" (before `## Addresses`), add:

```markdown
## Editing master data

**Stammdaten bearbeiten** in the detail view opens the business form for an
existing business: name, phone, industry, contact name, website and email.
Addresses keep their own screen, note and status stay in the detail view. The
same rules as for a new business apply: a name is required, and a number is
empty or complete.

What you change here stays: a later import of the business file leaves every
field you changed alone — on every phone, once they have synchronised — and
updates the rest. Clearing a field counts as a change. The phone book entries
follow at once.
```

- [ ] **Step 3: Changelog**

Run: `git tag --list 'v1.*' | tail -1; grep -n '^versionName' version.properties`
Expected: `v1.5.0` and `versionName=1.5.0`. The version is **1.6.0** (decided by „caller-app-34"). If package 2 has already added a `## 1.6.0` section, add these bullets to it instead of a second heading.

In `CHANGELOG.md`, insert above `## 1.5.0`:

```markdown
## 1.6.0

- **Stammdaten bearbeiten.** Name, phone, industry, contact name, website and
  email of an existing business can be changed in the detail view.
- **A hand edit survives the import.** Re-importing the business file leaves
  every field changed by hand alone, on every phone, and updates the rest.
- **The same number at several businesses.** A business can be created with a
  number another one already has; the phone book holds it once per business.
- **A visit is never removed because its calendar entry is missing.** A
  calendar switched off in DAVx5 looked like a deleted entry and would have
  removed visits on the server and at Infomaniak. The detail view now says „Im
  Kalender nicht mehr gefunden" and offers **Termin entfernen** — at once
  without an invitation, after asking with one.
- **Update the sync server first, then every phone, and only then import
  again.** The first sync after the update fetches everything once. A phone
  still on 1.5.0 overwrites hand edits when it imports.

```

- [ ] **Step 4: Check and commit**

Run: `grep -n "Diese Nummer steht schon\|cannot be created twice" docs/*.md CHANGELOG.md`
Expected: no output.

```bash
git add docs/data-model.md docs/usage.md CHANGELOG.md
git commit -m "Doku und CHANGELOG: Stammdaten bearbeiten, doppelte Nummern, Besuche nie automatisch gelöscht"
```

---

## Rollout

Server before app. Both steps are outward-facing: each asks first.

### Task 9: Server — deploy

**Repository:** server, and the production host. **Outward-facing: ask the user before Step 1's push and before Step 4, and wait for a yes each time.**

Every remote command is one non-interactive `ssh <server> '…'` call. Never open an interactive shell on the host. `<server>` and `<deploy-dir>` are the ones from the previous deploys; ask „caller-app-34" if you do not have them.

- [ ] **Step 1: Push**

Run: `node --test` — `# fail 0`. Then ask the user, and on a yes: `git push origin main`.

- [ ] **Step 2: See what the host will pull**

```bash
ssh <server> 'cd <deploy-dir>/repo && git fetch && git diff HEAD origin/main --stat'
```

Expected: `db/migrations/011-edited-fields.sql`, `README.md`, the three test files, the spec pointer and, if not deployed yet, this plan's neighbours in `docs/`. If anything under `src/` or another migration is in the diff, stop and ask the user — it has its own deploy steps.

- [ ] **Step 3: Back up**

```bash
ssh <server> 'sudo <deploy-dir>/repo/scripts/backup.sh'
```

Expected: exit 0. Restoring that backup later drops every sync since and needs **Alles erneut hochladen** on every phone.

- [ ] **Step 4: Pull and restart**

`src/start.js` applies pending migrations on start.

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
  console.log(db.prepare(\"SELECT COUNT(*) AS with_list FROM businesses WHERE edited_fields IS NOT NULL\").get());
"'
```

Expected: `011-edited-fields.sql`; `with_list` 0 (no phone runs the new app yet).

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

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. `git status --porcelain` in both repos shows nothing.

Ask „caller-app-34" (or the user) whether Task 9 is done. Do not install before it is.

- [ ] **Step 2: Install on a connected phone**

Run: `~/android-sdk/platform-tools/adb devices -l`
Expected: one device. If none: ask the user to connect the phone with USB debugging on, and wait.

Run: `./gradlew installDebug`
Expected: `Installed on 1 device`.

- [ ] **Step 3: Walk through it with the user**

Ask the user to check, with phone book, calendar and sync switched on, and report each result:

1. **Neuer Betrieb** with the number of an existing business: it is saved; both show in the work list.
2. An imported business → **Stammdaten bearbeiten**: change the email, clear the website, save. The detail view shows the new values; after DAVx5 has synced, the phone book entry carries the new email. Change the contact name: the row reads „Ansprechpartner" instead of „Ansprechpartner (importiert)".
3. **Stammdaten bearbeiten**, clear the name: the form says „Ohne Namen lässt sich der Betrieb nicht speichern." Type „0621" as number: „Die Telefonnummer ist unvollständig. …".
4. Re-import the business file: email and website stay as edited; the import summary counts no update for that business unless something else in the file changed.
5. On a second phone with the new version, after sync: the same values; a re-import there keeps them too.
6. A visit without an invitation that this phone has seen in the calendar once. Deselect the calendar in DAVx5, open the business: the visit stays, „Im Kalender nicht mehr gefunden" with „Termin entfernen". Select the calendar again, let DAVx5 sync, open the business: the line is gone. Deselect again, tap „Termin entfernen": no dialog, the visit goes, the hint says „Termin entfernt." (or with the status); in the Infomaniak web calendar the event is gone.
7. The same with an invitation to `test@example.org`: „Termin entfernen" opens the dialog naming `test@example.org`; „Entfernen" → a cancellation arrives.
8. A callback deleted in the calendar is still removed, with „Der Rückruf wurde im Kalender gelöscht. …".

Anything that does not match: stop, find the cause (superpowers:systematic-debugging), fix it test-first in a task of its own, and repeat this step.

- [ ] **Step 4: Release**

Only after Task 9 is done and the user says yes. The version is **1.6.0**, the heading Task 8 wrote; package 2 may ship in the same release — ask „caller-app-34" whether to wait for it. Then run `tools/release.sh minor`.
Expected: the script builds, tests, tags and publishes the version with its CHANGELOG section as release notes. Every phone is updated before the business file is imported again.
