# Wave 1 — Several addresses per business — Implementation Plan (SERVER and APP)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A business holds any number of addresses (the existing one carried over as `main-<place_id>`), a contact person can be assigned to one of them, and the phone book, the map links, the search, the city filter and the visit's place all follow.

**Architecture:** A new synchronised table `business_addresses` on server (migration 009) and app (schema 7), plus `contacts.address_id`. The rules — which row is the main address, which address a person stands for, the search text, a visit's preset place — are pure functions in `data/Addresses.kt` and `calling/Appointment.kt`. The repository writes addresses (import, hand entry, the new address screen), the sync store applies them and keeps `search_text` in step, the phone book writes one postal row per address. The old address columns on `businesses` stay, are no longer written and, after the last task, no longer read.

**Tech Stack:** Server: Node 24, `node:sqlite`, `node --test`. App: Kotlin, Jetpack Compose + Material 3, `SQLiteOpenHelper`, `ContactsContract`, JUnit 4 + Robolectric 4.16 (`@Config(sdk = [34])`), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-15-business-addresses-design.md` (app repo, written in commit `295bd07`, updated after the plan review in commit `51edce3` — the address screen and `removed_main_addresses` are part of it since); the server's pointer is `docs/superpowers/specs/2026-09-15-business-addresses-design.md` in the server repo (commit `dbd4b79`). Read the spec before starting.

## Decisions made while planning (on top of the spec)

- **Existing businesses get their own address screen.** The business form only creates businesses. In the detail view, „Adressen bearbeiten" (or „Adresse hinzufügen" when there is none) opens a screen holding only the address list; it shares the list component with the business form. Master data is not editable there.
- **City filter, suggestions, sorting, list row.** The city filter matches when **any** address of the business is in the chosen cities (like the search). The city suggestions come from all addresses. Sorting and the second line of a list row use the city of the **main** address (`Business.city` is filled from it).
- **The hand-deleted main address is remembered locally** in a local-only table `removed_main_addresses`. `deletions` cannot answer „was `main-<place_id>` deleted by hand?": it is the outgoing queue and is emptied once the server acknowledges a tombstone, and incoming tombstones are removed from it too. Without the table, a re-import after the next sync would bring the address back. An incoming tombstone for a `main-` row fills the table as well.
- **A refetch after the update.** A phone still on 1.5.x skips the `business_addresses` rows the server delivers while its watermark moves past them. `Preferences.refetchedForAddresses` makes the first sync on schema 7 start from watermark 0, as schemas 4 and 6 did.
- **A chip counts as chosen by hand.** In the appointment sheet, a place picked by chip stays when the contact person changes, the same as typed text.

## Global Constraints

- **Two repositories, both on branch `main`.** App: `~/code/tm-services-automate/caller-app/app` (Kotlin sources under `app/src/main/java/io/github/amadeusb/callsheet/`, written `…/` below; tests under `app/src/test/java/io/github/amadeusb/callsheet/`, written `test/…` below). Server: `~/code/tm-services-automate/caller-app/server`. Every step says which repository it runs in.
- **Numbers:** server migration **`009-business-addresses.sql`**, app `Database.VERSION` **6 → 7**. The visits through the Infomaniak API come **later**, on server migration **`010`** and app database version **8** (spec commit `295bd07` in the app repo, pointer commit `dbd4b79` in the server repo) — this plan comes first and does not touch that work.
- **Start on clean trees.** All three plans are committed before any work starts. Nothing of another session's uncommitted work goes into any commit of this plan. Stage only the files each step names; never `git add -A` or `git add .`.

- **Ids:** the carried-over and imported address is `main-<place_id>` (`Addresses.MAIN_PREFIX = "main-"`), byte-identical on server and app. Every other address gets a UUID.
- **Carry-over condition, identical on both sides:** `COALESCE(street, '') <> '' OR COALESCE(postal_code, '') <> '' OR COALESCE(city, '') <> ''`; the row takes the business's `updated_at`, `position = 0`, `label` NULL.
- **New server columns are nullable** (`server/README.md`, „Migrationen"). `business_addresses.position` is nullable on both sides; NULL reads as last.
- **Old columns** `businesses.street`, `postal_code`, `city`, `latitude`, `longitude` stay on both sides, are **not emptied**, and are no longer written.
- **Label suggestions, exactly:** „Hauptsitz", „Filiale", „Lager", „Baustelle". **Phone book:** a labelled address is `StructuredPostal.TYPE_CUSTOM` with `LABEL = <label>`, an unlabelled one `TYPE_WORK`; country always `Deutschland`.
- **UI texts, exactly:** section „Adressen"; field labels „Bezeichnung", „Straße und Hausnummer", „PLZ", „Ort"; buttons „Als Hauptadresse", „Entfernen", „Adresse hinzufügen", „Adressen bearbeiten", „Adressen speichern"; detail heading per row = label, else „Anschrift"; contact form section „Standort" with „Keiner"; route text „Route zum Betrieb · <address>".
- **Tests:** app `./gradlew testDebugUnitTest` (all) or `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.<Class>"`; build check `./gradlew assembleDebug`; both from the app repository root. Server `node --test` (all) or `node --test test/<file>.test.js`, locally only.
- **Tests use made-up names, numbers and addresses only** — never data from the real business file.
- **Language:** code, identifiers, comments, docs in English; UI text German with correct umlauts and „…" quotes; server README stays German. Test names in English; the app fixture method stays `fun aufbau()`.
- **Timestamps:** ISO-8601 with offset via `Clock.now()`/`Clock.format`, compared through `Clock.millis`.
- **State writes** in new view model code use `_state.update { … }`.
- **Every task leaves both projects building and all tests green. Commit after every task,** listing files explicitly. German commit messages in the tone of `git log`, no attribution lines.
- **Rollout order:** server deployed first (Task 15), then the app released (Task 16). Both are outward-facing: ask the user before each.

### Interface used by the visits plan

The visits plan builds on the rule „the place follows the contact person only while it was not chosen by hand". It lives in `…/calling/Appointment.kt` (Task 12) with exactly this signature — do not rename it or change its parameters:

```kotlin
fun placeAfterContactChange(
    previous: AppointmentDraft,
    incoming: AppointmentDraft,
    presetFor: (contactId: String?) -> String?,
): String
```

- Returns the place for [incoming]. `presetFor` gives a contact person's preset place (null for none; `null` as argument means no person).
- `AppointmentDraft` is the class in `…/CallsheetViewModel.kt` (package `io.github.amadeusb.callsheet`); Task 12 adds `locationEdited: Boolean = false` to it.
- The place moves along — `presetFor(incoming.contactId) ?: incoming.location` — only when the contact person changed (`incoming.contactId != previous.contactId`), the place did not change in the same update, and it was not chosen by hand: `!previous.locationEdited && previous.location == presetFor(previous.contactId).orEmpty()`. In every other case, and always for a callback, it returns `incoming.location`.
- `locationEdited` keeps the spec's rule that a place picked by chip counts as chosen by hand, even when the chip holds the preset address.

---

## File Structure

| Repository | File | Change | Responsibility |
|---|---|---|---|
| server | `db/migrations/009-business-addresses.sql` | create | table, `contacts.address_id`, carry-over, counter |
| server | `src/receive.js` | modify | `business_addresses` in `KEYS`, deletable, received |
| server | `test/db.test.js`, `test/receive.test.js`, `test/deliver.test.js`, `test/server.test.js` | modify | migration 009, the table travels |
| server | `README.md` | modify | the table; old columns no longer read |
| app | `…/data/Models.kt` | modify | `BusinessAddress`, `AddressDraft`, `Contact.addressId`, `ContactDraft.addressId`, `BusinessDraft.addresses`; Task 13 trims `Business` |
| app | `…/data/Addresses.kt` | create | pure rules: main address, assignment, one line, search text, reordering |
| app | `…/data/AddressRows.kt` | create | database helpers shared by repository and sync store: search text, removed main addresses |
| app | `…/data/Database.kt` | modify | `VERSION = 7`, table, column, local table, carry-over |
| app | `…/data/Repository.kt` | modify | import, lists and filter, `addresses`, `saveAddresses`, `create`, contact assignment |
| app | `…/calling/Appointment.kt` | modify | `address` delegates; `presetLocation` (Task 3), `placeAfterContactChange` (Task 12) |
| app | `…/sync/Rows.kt`, `…/sync/SyncStore.kt`, `…/sync/SyncEngine.kt`, `…/contacts/Preferences.kt` | modify | the table travels, search text after applying, refetch |
| app | `…/contacts/PhoneBook.kt`, `…/contacts/PhoneBookEntries.kt`, `…/contacts/ContactStore.kt`, `…/contacts/ContactMerge.kt` | modify | one postal row per address; assignment survives a read-back |
| app | `…/CallsheetViewModel.kt`, `…/MainActivity.kt` | modify | address screen, detail addresses, contact assignment, sheet place |
| app | `…/ui/Addresses.kt` | create | `AddressList`, `AddressScreen` |
| app | `…/ui/BusinessDetail.kt`, `…/ui/BusinessForm.kt`, `…/ui/ContactScreen.kt`, `…/ui/AppointmentSheet.kt` | modify | addresses in detail, form, contact form, sheet |
| app | tests | modify/create | `AddressesTest` (new), `AppointmentTest`, `MigrationTest`, `SyncSchemaTest`, `SyncStoreTest`, `SyncEngineTest`, `RepositoryTest`, `BusinessFormTest`, `DirtyTest`, `ContactMergeTest`, `PhoneBookEntriesTest`, `PhoneBookRowsTest`, `GeoUriTest` |
| app | `docs/data-model.md`, `docs/usage.md`, `CHANGELOG.md` | modify | docs |

---

### Task 0: Preconditions

**Files:** none.

- [ ] **Step 1: Both trees clean**

Run in the app repo and in the server repo: `git status --short`
Expected: no output in either. The plans are committed (`git log --oneline -- docs/superpowers/plans/` shows them). If anything is uncommitted or untracked, stop and ask the coordinator session „caller-app-34".

- [ ] **Step 2: The numbers are free**

Run in the server repo: `ls db/migrations`
Expected: the last file is `008-callbacks.sql`. If a `009-*.sql` exists already, stop and ask the user.

Run in the app repo: `grep -n 'const val VERSION' app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt`
Expected: `const val VERSION = 6`. Otherwise stop and ask.

- [ ] **Step 3: Both suites green before anything changes**

Run in the server repo: `node --test`
Expected: all pass.

Run in the app repo: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

---

### Task 1: Server — migration 009

**Repository:** server.

**Files:**
- Create: `db/migrations/009-business-addresses.sql`
- Test: `test/db.test.js`

**Interfaces:**
- Produces: table `business_addresses (id, place_id, label, street, postal_code, city, latitude, longitude, position, updated_at, server_seq)`, column `contacts.address_id`. Carried-over rows `main-<place_id>`.

- [ ] **Step 1: Write the failing tests**

In `test/db.test.js`, add `'business_addresses'` to the list in `migrate creates all tables`:

```js
  for (const t of ['businesses', 'calls', 'contacts', 'contact_numbers', 'appointments', 'business_addresses', 'deletions', 'sync_counter', 'schema_migrations']) {
```

Append at the end of the file:

```js
/**
 * A database in the state a running server is in when 009 arrives: every
 * earlier migration applied and recorded, a counter that has moved, one
 * business with a full imported address, one with only a city, one without an
 * address as NULL and one as empty strings, one with coordinates only, and a
 * contact person.
 */
function databaseBefore009() {
  const path = join(mkdtempSync(join(tmpdir(), 'callsheet-')), 'test.db')
  const db = open(path)
  db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL)')
  for (const filename of MIGRATIONS.filter(name => name < '009')) {
    db.exec(readFileSync(join('db/migrations', filename), 'utf8'))
    db.prepare('INSERT INTO schema_migrations (filename, applied_at) VALUES (?, ?)').run(filename, new Date().toISOString())
  }
  db.prepare('UPDATE sync_counter SET value = 7').run()
  const business = db.prepare(
    `INSERT INTO businesses (place_id, name, closed, is_target, status, updated_at, server_seq,
                             street, postal_code, city, latitude, longitude)
     VALUES (?, ?, 0, 1, 'new', ?, ?, ?, ?, ?, ?, ?)`
  )
  // Inserted out of order: the sequence numbers follow place_id, not insertion.
  business.run('P2', 'Gartenbau Merten', '2026-09-07T11:00:00+02:00', 4, null, null, 'Gaimersheim', null, null)
  business.run('P1', 'Elektro Meier', '2026-09-07T10:00:00+02:00', 3, 'Musterweg 1', '85049', 'Ingolstadt', 48.7651, 11.4237)
  // No address at all: once as NULL, once with every field ''.
  business.run('P3', 'Malerei Huber', '2026-09-07T12:00:00+02:00', 5, null, null, null, null, null)
  business.run('P4', 'Dach Schmid', '2026-09-07T13:00:00+02:00', 6, '', '', '', null, null)
  // Coordinates without street, postal code or city are no address.
  business.run('P5', 'Fliesen Koch', '2026-09-07T14:00:00+02:00', 7, null, null, null, 48.7, 11.4)
  db.prepare(
    `INSERT INTO contacts (id, place_id, name, position, updated_at, server_seq)
     VALUES ('K1', 'P1', 'Erika Beispiel', 0, '2026-09-07T10:00:00+02:00', 2)`
  ).run()
  return db
}

test('migration 009 carries each address over as the main address', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  const rows = db.prepare('SELECT * FROM business_addresses ORDER BY id').all().map(row => ({ ...row }))
  assert.deepEqual(rows.map(row => row.id), ['main-P1', 'main-P2'])
  const { server_seq, ...first } = rows[0]
  assert.deepEqual(first, {
    id: 'main-P1',
    place_id: 'P1',
    label: null,
    street: 'Musterweg 1',
    postal_code: '85049',
    city: 'Ingolstadt',
    latitude: 48.7651,
    longitude: 11.4237,
    position: 0,
    // The business's timestamp: the app's own carried-over row arrives as a standstill.
    updated_at: '2026-09-07T10:00:00+02:00',
  })
  const { server_seq: _, ...second } = rows[1]
  assert.deepEqual(second, {
    id: 'main-P2',
    place_id: 'P2',
    label: null,
    street: null,
    postal_code: null,
    city: 'Gaimersheim',
    latitude: null,
    longitude: null,
    position: 0,
    updated_at: '2026-09-07T11:00:00+02:00',
  })
})

test('migration 009 writes no row for a business whose fields are all empty strings', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  assert.equal(db.prepare("SELECT COUNT(*) n FROM business_addresses WHERE place_id IN ('P3', 'P4')").get().n, 0)
})

test('migration 009 writes no row for a business with coordinates only', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  assert.equal(db.prepare("SELECT COUNT(*) n FROM business_addresses WHERE place_id = 'P5'").get().n, 0)
})

test('migration 009 hands out unique sequence numbers and moves the counter past them', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  const sequences = db.prepare('SELECT server_seq FROM business_addresses ORDER BY id').all().map(row => row.server_seq)
  assert.deepEqual(sequences, [8, 9])
  assert.equal(db.prepare('SELECT value FROM sync_counter').get().value, 9)
})

test('migration 009 keeps the old address columns filled and leaves the businesses unmarked', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  const business = { ...db.prepare(
    'SELECT street, postal_code, city, latitude, longitude, updated_at, server_seq FROM businesses WHERE place_id = ?'
  ).get('P1') }
  assert.deepEqual(business, {
    street: 'Musterweg 1',
    postal_code: '85049',
    city: 'Ingolstadt',
    latitude: 48.7651,
    longitude: 11.4237,
    updated_at: '2026-09-07T10:00:00+02:00',
    server_seq: 3,
  })
  const sequences = db.prepare('SELECT server_seq FROM businesses ORDER BY place_id').all().map(row => row.server_seq)
  assert.deepEqual(sequences, [3, 4, 5, 6, 7])
})

test('migration 009 gives contacts an empty address_id and leaves them unmarked', () => {
  const db = databaseBefore009()

  migrate(db, 'db/migrations')

  const contact = { ...db.prepare("SELECT address_id, updated_at, server_seq FROM contacts WHERE id = 'K1'").get() }
  assert.deepEqual(contact, { address_id: null, updated_at: '2026-09-07T10:00:00+02:00', server_seq: 2 })
})

test('business_addresses.position and contacts.address_id accept NULL', () => {
  // Nullable, as the README asks of every new column: receive.js fills only gaps.
  const db = freshDb()
  db.prepare(
    `INSERT INTO business_addresses (id, place_id, label, street, postal_code, city, latitude, longitude, position, updated_at, server_seq)
     VALUES ('A1', 'P1', NULL, NULL, NULL, 'Ingolstadt', NULL, NULL, NULL, '2026-09-07T10:00:00+02:00', 1)`
  ).run()
  db.prepare(
    `INSERT INTO contacts (id, place_id, name, role, email, note, position, updated_at, server_seq, address_id)
     VALUES ('K1', 'P1', 'Erika Beispiel', NULL, NULL, NULL, 0, '2026-09-07T10:00:00+02:00', 2, NULL)`
  ).run()
  assert.equal(db.prepare('SELECT position FROM business_addresses WHERE id = ?').get('A1').position, null)
  assert.equal(db.prepare('SELECT address_id FROM contacts WHERE id = ?').get('K1').address_id, null)
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test test/db.test.js`
Expected: FAIL — `missing table: business_addresses` in `migrate creates all tables`, `no such table: business_addresses` in the 009 tests and the NULL test, `no such column: address_id` in the contact test.

- [ ] **Step 3: Write the migration**

Create `db/migrations/009-business-addresses.sql`:

```sql
-- Several addresses per business. A business held one address, in columns on
-- its own row; businesses have a head office, a branch, a yard, and a contact
-- person often sits at one of them. A row per address is the shape that holds
-- that, synchronised like contact_emails: newer wins, a standstill fills gaps,
-- a tombstone beats an older row.
--
-- position is nullable, as this README asks of every new column: a NOT NULL
-- DEFAULT would stand where a gap belongs, and receive.js fills only gaps. The
-- app always writes it and reads a NULL as last.

CREATE TABLE business_addresses (
    id           TEXT PRIMARY KEY,
    place_id     TEXT NOT NULL,
    label        TEXT,
    street       TEXT,
    postal_code  TEXT,
    city         TEXT,
    latitude     REAL,
    longitude    REAL,
    position     INTEGER,
    updated_at   TEXT NOT NULL,
    server_seq   INTEGER NOT NULL
);

CREATE INDEX idx_business_addresses_seq ON business_addresses(server_seq);

-- The address a contact person sits at: the id of one of the business's
-- addresses, or NULL for none. A dangling id reads as none on the app; the
-- server does not check the reference.
ALTER TABLE contacts ADD COLUMN address_id TEXT;

-- The carry-over. The id is fixed rather than a fresh UUID: the app's own
-- migration writes the same 'main-' || place_id, so the two rows meet as one,
-- as 'legacy-' in 005 and 'followup-' in 008. updated_at comes from the
-- business for the same reason — where the business was already synchronised,
-- both sides hold the same timestamp and the rows arrive at a standstill.
-- Street, postal code, city and coordinates are copied as they are (no
-- trimming, no NULLIF), for the same reason: both sides must hold the same row.
--
-- Coordinates alone are no address: they came with an imported one.
--
-- Each row gets its own sequence number above the counter, so every device
-- fetches it like any other row.
INSERT INTO business_addresses (id, place_id, label, street, postal_code, city, latitude, longitude,
                                position, updated_at, server_seq)
    SELECT 'main-' || place_id, place_id, NULL, street, postal_code, city, latitude, longitude,
           0, updated_at,
           (SELECT value FROM sync_counter) + ROW_NUMBER() OVER (ORDER BY place_id)
    FROM businesses
    WHERE COALESCE(street, '') <> '' OR COALESCE(postal_code, '') <> '' OR COALESCE(city, '') <> '';

-- The table is new, so everything in it came from the INSERT above.
UPDATE sync_counter SET value = value + (SELECT COUNT(*) FROM business_addresses);

-- The old columns on businesses stay and are not emptied: nothing new reads
-- them, and an app still on the old version keeps showing the address it had.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --test test/db.test.js`
Expected: PASS, all tests in the file.

Run: `node --test`
Expected: all pass. Nothing else changes yet: `receive.js` does not know the table, and `contacts.address_id` is read from the schema.

- [ ] **Step 5: Commit**

```bash
git add db/migrations/009-business-addresses.sql test/db.test.js
git commit -m "Migration 009: Adressen als eigene Tabelle, Anschrift wird main-<place_id>"
```

---

### Task 2: Server — addresses travel

**Repository:** server.

**Files:**
- Modify: `src/receive.js` (`KEYS` at the top; doc comment of `receiveDeletable`; `DELETABLE_TABLES`; the loop in `receive`)
- Modify: `README.md` (section „Migrationen", after the paragraph „**Termine haben eine Art.** …")
- Test: `test/receive.test.js` (test „the synchronised tables are exported, appointments among them"; new tests inserted before `isNewer compares timestamps`)
- Test: `test/deliver.test.js` (new tests appended at the end)
- Test: `test/server.test.js` (test „the response names the tables this server synchronises"; one new test after „an appointment sent up comes back down")

**Interfaces:**
- Consumes: the table and `contacts.address_id` from Task 1.
- Produces: `SYNCED_TABLES` = `['businesses', 'calls', 'contacts', 'contact_numbers', 'contact_emails', 'appointments', 'business_addresses']`; the `/sync` response names it in `tables` and carries `business_addresses`; tombstones with `table_name: 'business_addresses'` are accepted. A contact's tombstone does **not** cascade to addresses.

- [ ] **Step 1: Write the failing tests**

In `test/receive.test.js`, replace the test `the synchronised tables are exported, appointments among them`:

```js
test('the synchronised tables are exported, addresses among them', () => {
  assert.deepEqual(SYNCED_TABLES,
    ['businesses', 'calls', 'contacts', 'contact_numbers', 'contact_emails', 'appointments', 'business_addresses'])
})
```

In `test/server.test.js`, change the expectation in `the response names the tables this server synchronises`:

```js
  assert.deepEqual(response.tables,
    ['businesses', 'calls', 'contacts', 'contact_numbers', 'contact_emails', 'appointments', 'business_addresses'])
```

In `test/receive.test.js`, insert before `test('isNewer compares timestamps', …)`:

```js
const ADDRESS = {
  id: 'A2', place_id: 'P1', label: 'Filiale',
  street: 'Hafenstraße 5', postal_code: '85001', city: 'Hafenstadt',
  latitude: null, longitude: null, position: 1,
  updated_at: '2026-09-07T10:00:00+02:00',
}

const CONTACT = {
  id: 'K1', place_id: 'P1', name: 'Erika Beispiel', role: null, email: null, note: null, position: 0,
  updated_at: '2026-09-07T10:00:00+02:00', address_id: 'A2',
}

function storedAddress(db, id = 'A2') {
  const row = db.prepare('SELECT * FROM business_addresses WHERE id = ?').get(id)
  return row ? { ...row } : undefined
}

test('an address is written with every column and a sequence number', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  const { server_seq, ...row } = storedAddress(db)
  assert.deepEqual(row, ADDRESS)
  assert.equal(server_seq, 1)
})

test('a newer address wins', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  receive(db, { ...empty, business_addresses: [{ ...ADDRESS, street: 'Hafenstraße 7', updated_at: '2026-09-07T11:00:00+02:00' }] })
  assert.equal(storedAddress(db).street, 'Hafenstraße 7')
})

test('an older address loses and gets no new sequence number', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  receive(db, { ...empty, business_addresses: [{ ...ADDRESS, label: 'Lager', updated_at: '2026-09-07T09:00:00+02:00' }] })
  const row = storedAddress(db)
  assert.equal(row.label, 'Filiale')
  assert.equal(row.server_seq, 1)
})

test('a standstill fills an address\'s gaps', () => {
  // The carried-over main-<place_id> meets the app's own copy at a standstill;
  // a label typed on one side before the first sync fills the other's gap and
  // overwrites nothing.
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [{ ...ADDRESS, label: null }] })
  receive(db, { ...empty, business_addresses: [{ ...ADDRESS, label: 'Hauptsitz', street: 'anders' }] })
  const row = storedAddress(db)
  assert.equal(row.label, 'Hauptsitz')
  assert.equal(row.street, 'Hafenstraße 5')
  assert.ok(row.server_seq > 1, 'the filled row must be fetchable by the other devices')
})

test('an address without a position is stored', () => {
  const db = freshDb()
  const { rejected } = receive(db, { ...empty, business_addresses: [{ ...ADDRESS, position: null }] })
  assert.deepEqual(rejected, [])
  assert.equal(storedAddress(db).position, null)
})

test('a newer tombstone deletes the address', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  receive(db, { ...empty, deleted: [{ table_name: 'business_addresses', row_id: 'A2', deleted_at: '2026-09-07T11:00:00+02:00' }] })
  assert.equal(storedAddress(db), undefined)
  assert.equal(db.prepare("SELECT COUNT(*) n FROM deletions WHERE table_name = 'business_addresses' AND row_id = 'A2'").get().n, 1)
})

test('an address does not come back after its tombstone', () => {
  // A main address removed by hand stays removed, even when a device that has
  // not heard of it yet uploads its carried-over copy.
  const db = freshDb()
  receive(db, { ...empty, deleted: [{ table_name: 'business_addresses', row_id: 'A2', deleted_at: '2026-09-07T11:00:00+02:00' }] })
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  assert.equal(storedAddress(db), undefined)
})

test('an address newer than its tombstone stays', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [{ ...ADDRESS, updated_at: '2026-09-07T12:00:00+02:00' }] })
  receive(db, { ...empty, deleted: [{ table_name: 'business_addresses', row_id: 'A2', deleted_at: '2026-09-07T11:00:00+02:00' }] })
  assert.ok(storedAddress(db))
})

test('a contact\'s tombstone leaves the business\'s addresses standing', () => {
  // The cascade in receiveTombstone takes a contact's numbers and emails. An
  // address belongs to the business, not to the person assigned to it.
  const db = freshDb()
  receive(db, { ...empty, contacts: [CONTACT], business_addresses: [ADDRESS] })
  receive(db, { ...empty, deleted: [{ table_name: 'contacts', row_id: 'K1', deleted_at: '2026-09-07T11:00:00+02:00' }] })
  assert.equal(db.prepare('SELECT COUNT(*) n FROM contacts WHERE id = ?').get('K1').n, 0)
  assert.ok(storedAddress(db))
})

test('a contact\'s address_id travels', () => {
  const db = freshDb()
  receive(db, { ...empty, contacts: [CONTACT] })

  const row = deliver(db, 0).contacts.find(contact => contact.id === 'K1')

  assert.equal(row.address_id, 'A2')
})

test('an older app changing a contact keeps its address_id', () => {
  // Its schema has no address_id, so its row carries no such key.
  const db = freshDb()
  receive(db, { ...empty, contacts: [CONTACT] })
  const { address_id, ...older } = CONTACT

  receive(db, { ...empty, contacts: [{ ...older, role: 'Inhaberin', updated_at: '2026-09-07T11:00:00+02:00' }] })

  const row = db.prepare('SELECT role, address_id FROM contacts WHERE id = ?').get('K1')
  assert.equal(row.role, 'Inhaberin')
  assert.equal(row.address_id, 'A2')
})

test('an explicit null clears a contact\'s address_id', () => {
  const db = freshDb()
  receive(db, { ...empty, contacts: [CONTACT] })

  receive(db, { ...empty, contacts: [{ ...CONTACT, address_id: null, updated_at: '2026-09-07T11:00:00+02:00' }] })

  assert.equal(db.prepare('SELECT address_id FROM contacts WHERE id = ?').get('K1').address_id, null)
})
```

Append at the end of `test/deliver.test.js`:

```js
const ADDRESS = {
  id: 'main-P1', place_id: 'P1', label: null,
  street: 'Musterweg 1', postal_code: '85049', city: 'Ingolstadt',
  latitude: 48.7651, longitude: 11.4237, position: 0,
  updated_at: '2026-09-07T10:00:00+02:00',
}

test('addresses are delivered, without their sequence number', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  const a = deliver(db, 0)
  assert.equal(a.business_addresses.length, 1)
  assert.deepEqual({ ...a.business_addresses[0] }, ADDRESS)
})

test('an address tombstone is delivered', () => {
  const db = freshDb()
  receive(db, { ...empty, business_addresses: [ADDRESS] })
  const first = deliver(db, 0)
  receive(db, { ...empty, deleted: [{ table_name: 'business_addresses', row_id: 'main-P1', deleted_at: '2026-09-07T11:00:00+02:00' }] })

  const second = deliver(db, first.watermark)

  assert.equal(second.business_addresses.length, 0)
  assert.equal(second.deleted.length, 1)
  assert.equal(second.deleted[0].table_name, 'business_addresses')
  assert.equal(second.deleted[0].row_id, 'main-P1')
})
```

In `test/server.test.js`, add after `an appointment sent up comes back down`:

```js
test('an address sent up comes back down', async () => {
  const { base, server } = await setup()
  const address = {
    id: 'A2', place_id: 'P1', label: 'Filiale',
    street: 'Hafenstraße 5', postal_code: '85001', city: 'Hafenstadt',
    latitude: null, longitude: null, position: 1,
    updated_at: '2026-09-07T10:00:00+02:00',
  }
  const response = await (await sync(base, { ...empty, business_addresses: [address] })).json()
  assert.equal(response.business_addresses.length, 1)
  assert.equal(response.business_addresses[0].label, 'Filiale')
  server.close()
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test test/receive.test.js test/deliver.test.js test/server.test.js`
Expected: FAIL —
- `the synchronised tables are exported, addresses among them` and `the response names the tables …`: `business_addresses` missing from the list;
- the address tests in `receive.test.js`: `storedAddress(db)` is `undefined` (the payload key is ignored), the tombstone tests find no row in `deletions` (the table name is not deletable), `a contact's tombstone leaves the business's addresses standing` finds no address;
- `addresses are delivered …`: `a.business_addresses` is `undefined`;
- `an address sent up comes back down`: `response.business_addresses` is `undefined`.

The three `address_id` tests (`travels`, `older app … keeps`, `explicit null clears`) already pass: the column comes from the schema (Task 1) and a row without the key keeps the stored value. They stay as guards. `an address does not come back after its tombstone` passes too, for the wrong reason — nothing is stored yet; it guards from Step 3 on.

- [ ] **Step 3: Receive the table**

In `src/receive.js`, replace `KEYS`:

```js
const KEYS = {
  businesses: 'place_id', calls: 'id', contacts: 'id', contact_numbers: 'id',
  contact_emails: 'id', appointments: 'id', business_addresses: 'id',
}
```

Replace the doc comment above `receiveDeletable` (currently „A row that can be deleted: contacts, their numbers, appointments. …"):

```js
/**
 * A row that can be deleted: contacts, their numbers and emails, appointments,
 * a business's addresses. The tombstone check comes first — a row no newer
 * than its tombstone stays gone. Only numbers and emails also answer to their
 * parent's tombstone; an address belongs to the business, which is never
 * deleted.
 */
```

Replace `DELETABLE_TABLES`:

```js
const DELETABLE_TABLES = new Set(['contacts', 'contact_numbers', 'contact_emails', 'appointments', 'business_addresses'])
```

In `receive`, add after the `appointments` line inside the `try`:

```js
    for (const row of payload.business_addresses ?? []) attempt('business_addresses', row.id, () => receiveDeletable(db, 'business_addresses', row))
```

`deliver.js` and `server.js` stay as they are: both follow `SYNCED_TABLES`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --test test/receive.test.js test/deliver.test.js test/server.test.js`
Expected: PASS.

Run: `node --test`
Expected: all pass (including `an empty block carries a list for every synchronised table`, which reads `SYNCED_TABLES`).

- [ ] **Step 5: README**

In `README.md`, section `## Migrationen`, after the paragraph beginning `**Termine haben eine Art.**`, add:

```markdown
**Adressen liegen in `business_addresses`.** Ein Betrieb hat beliebig viele
Adressen, jede mit optionaler Bezeichnung (`label`, z. B. „Filiale") und
`position`; die erste nach `position` ist die Hauptadresse, `NULL` gilt als
letzte. Die Tabelle wird abgeglichen wie `contact_emails`, Grabsteine
eingeschlossen; der Grabstein eines Ansprechpartners löscht keine Adressen.
`contacts.address_id` nennt die Adresse, an der ein Ansprechpartner sitzt, oder
ist `NULL`; der Server prüft den Verweis nicht. Migration 009 hat jede
vorhandene Anschrift unverändert als `main-<place_id>` mit `position = 0`
übernommen. `businesses.street`, `postal_code`, `city`, `latitude` und
`longitude` sind seitdem stillgelegt: Sie bleiben gefüllt, damit eine App mit
altem Stand ihre Anschrift weiter zeigt, werden aber von neuen Apps weder
gelesen noch geschrieben.
```

- [ ] **Step 6: Commit**

```bash
git add src/receive.js test/receive.test.js test/deliver.test.js test/server.test.js README.md
git commit -m "Adressen der Betriebe werden empfangen und ausgeliefert, samt Grabsteinen; address_id reist mit dem Ansprechpartner"
```

---

### Task 3: App — the rules for addresses

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt` (new `BusinessAddress`, `AddressDraft`; `Contact.addressId`, `ContactDraft.addressId`)
- Create: `…/data/Addresses.kt`
- Modify: `…/calling/Appointment.kt` (`address` delegates; `presetLocation`)
- Create: `test/AddressesTest.kt`
- Test: `test/AppointmentTest.kt`

**Interfaces:**
- Produces:
  - `data class BusinessAddress(id: String, placeId: String, label: String?, street: String?, postalCode: String?, city: String?, latitude: Double? = null, longitude: Double? = null, position: Int? = null)` with `val oneLine: String?`
  - `data class AddressDraft(id: String? = null, label: String = "", street: String = "", postalCode: String = "", city: String = "")` with `val isBlank: Boolean`
  - `Contact.addressId: String? = null`, `ContactDraft.addressId: String? = null`
  - `object Addresses`: `MAIN_PREFIX = "main-"`, `LABEL_SUGGESTIONS: List<String>`, `mainId(placeId: String): String`, `oneLine(street: String?, postalCode: String?, city: String?): String?`, `ordered(addresses: List<BusinessAddress>): List<BusinessAddress>`, `main(addresses): BusinessAddress?`, `assigned(addressId: String?, addresses): BusinessAddress?`, `forContact(addressId: String?, addresses): BusinessAddress?`, `name(address: BusinessAddress): String`, `searchText(name: String, cities: List<String?>): String`, `drafts(addresses): List<AddressDraft>`, `makeMain(drafts: List<AddressDraft>, index: Int): List<AddressDraft>`
  - `Appointment.presetLocation(contactId: String?, contacts: List<Contact>, addresses: List<BusinessAddress>): String`
  - (`Appointment.placeAfterContactChange` follows in Task 12, where `AppointmentDraft.locationEdited` is added.)

- [ ] **Step 1: Write the failing tests**

Create `test/AddressesTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.data.AddressDraft
import io.github.amadeusb.callsheet.data.Addresses
import io.github.amadeusb.callsheet.data.BusinessAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which address counts for what. Every address here is made up. */
class AddressesTest {

    private fun address(
        id: String,
        position: Int?,
        city: String? = "Musterstadt",
        street: String? = null,
        postalCode: String? = null,
        label: String? = null,
    ) = BusinessAddress(
        id = id, placeId = "P1", label = label,
        street = street, postalCode = postalCode, city = city, position = position,
    )

    @Test
    fun `the main address is the first by position, a missing position last`() {
        val unplaced = address("A3", null)
        val branch = address("A2", 1)
        val head = address("main-P1", 0)

        assertEquals(head, Addresses.main(listOf(unplaced, branch, head)))
        assertEquals(listOf(head, branch, unplaced), Addresses.ordered(listOf(unplaced, branch, head)))
        assertNull(Addresses.main(emptyList()))
    }

    @Test
    fun `a contact person stands for their assigned address, else the main address`() {
        val head = address("main-P1", 0)
        val branch = address("A2", 1)

        assertEquals(branch, Addresses.forContact("A2", listOf(head, branch)))
        assertEquals(head, Addresses.forContact(null, listOf(head, branch)))
    }

    @Test
    fun `an assignment to a row that is not here reads as none`() {
        val head = address("main-P1", 0)

        assertNull(Addresses.assigned("gone", listOf(head)))
        assertEquals(head, Addresses.forContact("gone", listOf(head)))
    }

    @Test
    fun `the search text holds the name and every city, lower-cased`() {
        assertEquals(
            "müller & söhne ingolstadt königsmoos",
            Addresses.searchText("Müller & Söhne", listOf("Ingolstadt", null, " ", "Königsmoos")),
        )
        assertEquals("müller & söhne", Addresses.searchText("Müller & Söhne", emptyList()))
    }

    @Test
    fun `a choice shows the label, else the address`() {
        assertEquals("Filiale", Addresses.name(address("A2", 1, label = "Filiale")))
        assertEquals(
            "Hafenstraße 5, 85001 Hafenstadt",
            Addresses.name(address("A2", 1, street = "Hafenstraße 5", postalCode = "85001", city = "Hafenstadt", label = " ")),
        )
    }

    @Test
    fun `making a row the main address moves it to the top and keeps the rest in order`() {
        val a = AddressDraft(id = "a", city = "Eins")
        val b = AddressDraft(id = "b", city = "Zwei")
        val c = AddressDraft(id = "c", city = "Drei")

        assertEquals(listOf(c, a, b), Addresses.makeMain(listOf(a, b, c), 2))
        assertEquals(listOf(a, b, c), Addresses.makeMain(listOf(a, b, c), 0))
        assertEquals(listOf(a, b, c), Addresses.makeMain(listOf(a, b, c), 5))
    }

    @Test
    fun `a draft with only a label is blank`() {
        assertTrue(AddressDraft(label = "Lager", street = " ").isBlank)
        assertFalse(AddressDraft(postalCode = "85001").isBlank)
    }

    @Test
    fun `rows become drafts in their order, missing values as empty text`() {
        val drafts = Addresses.drafts(listOf(address("A2", 1, label = "Filiale"), address("main-P1", 0, city = null)))

        assertEquals(
            listOf(AddressDraft(id = "main-P1"), AddressDraft(id = "A2", label = "Filiale", city = "Musterstadt")),
            drafts,
        )
    }

    @Test
    fun `the main id carries the place id`() {
        assertEquals("main-P1", Addresses.mainId("P1"))
    }
}
```

In `test/AppointmentTest.kt`, add the import `io.github.amadeusb.callsheet.data.BusinessAddress` and, after the `// --- address ---` tests, add:

```kotlin
    // --- place of a visit ---------------------------------------------------

    private val head = BusinessAddress("main-P1", "P1", null, "Musterweg 1", "85000", "Musterstadt", position = 0)
    private val branch = BusinessAddress("A2", "P1", "Filiale", "Hafenstraße 5", "85001", "Hafenstadt", position = 1)

    private fun person(id: String, addressId: String?) = Contact(
        id = id, placeId = "P1", name = "Erika Beispiel", role = null, email = null, note = null,
        numbers = emptyList(), updatedAt = "2026-09-15T10:00:00+02:00", addressId = addressId,
    )

    @Test
    fun `a visit starts at the chosen person's assigned address`() {
        assertEquals(
            "Hafenstraße 5, 85001 Hafenstadt",
            Appointment.presetLocation("k1", listOf(person("k1", "A2")), listOf(head, branch)),
        )
    }

    @Test
    fun `without a person, an assignment or its row, a visit starts at the main address`() {
        val addresses = listOf(branch, head)
        val main = "Musterweg 1, 85000 Musterstadt"

        assertEquals(main, Appointment.presetLocation(null, emptyList(), addresses))
        assertEquals(main, Appointment.presetLocation("k1", listOf(person("k1", null)), addresses))
        assertEquals(main, Appointment.presetLocation("k1", listOf(person("k1", "gone")), addresses))
    }

    @Test
    fun `without any address the place stays empty`() {
        assertEquals("", Appointment.presetLocation("k1", listOf(person("k1", "A2")), emptyList()))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AddressesTest" --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL — compilation errors `Unresolved reference: BusinessAddress`, `Addresses`, `presetLocation`.

- [ ] **Step 3: The models**

In `…/data/Models.kt`, directly after the `Business` class, add:

```kotlin
/**
 * One of a business's addresses — head office, branch, yard. The first by
 * [position] is the main address: the preset wherever nothing more specific
 * applies.
 *
 * The address a business had before schema 7 became `main-<place_id>`, the same
 * id the server's migration 009 writes; the import keeps that row up to date.
 * Every other address has a UUID.
 */
data class BusinessAddress(
    val id: String,
    val placeId: String,
    /** „Hauptsitz", „Filiale" … Free text, optional. */
    val label: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    /** From the import. Cleared when street, postal code or city are changed by hand. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Null reads as last. */
    val position: Int? = null,
) {
    /** Street, postal code and city on one line. Null when nothing is known. */
    val oneLine: String?
        get() = Addresses.oneLine(street, postalCode, city)
}

/** One address row in a form — text as typed. */
data class AddressDraft(
    /** The row being edited; null for a new one. */
    val id: String? = null,
    val label: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
) {
    /** Street, postal code and city all empty: not saved, label or not. */
    val isBlank: Boolean
        get() = street.isBlank() && postalCode.isBlank() && city.isBlank()
}
```

In `data class Contact`, after `emails`, add:

```kotlin
    /**
     * The business address this person sits at, or null for none. A row that is
     * not here (deleted elsewhere, not synchronised yet) reads as none — see
     * [Addresses.assigned]. Never cleared on reading: the row may still arrive.
     */
    val addressId: String? = null,
```

In `data class ContactDraft`, after `emails`, add:

```kotlin
    /** The chosen address; null is „Keiner". */
    val addressId: String? = null,
```

- [ ] **Step 4: The rules**

Create `…/data/Addresses.kt`:

```kotlin
package io.github.amadeusb.callsheet.data

/**
 * The rules around a business's addresses, in one place: which one is the main
 * address, which one a contact person stands for, how an address reads on one
 * line, what the search reads. Without Android access, so the rules stay
 * testable.
 */
object Addresses {

    /**
     * The id prefix of the address carried over from the business's own columns
     * (schema 7, server migration 009), and the one the import keeps up to date.
     */
    const val MAIN_PREFIX = "main-"

    /** Offered as chips under „Bezeichnung"; free text stays possible. */
    val LABEL_SUGGESTIONS = listOf("Hauptsitz", "Filiale", "Lager", "Baustelle")

    fun mainId(placeId: String): String = MAIN_PREFIX + placeId

    /** Street, postal code and city on one line. Null when nothing is known. */
    fun oneLine(street: String?, postalCode: String?, city: String?): String? {
        val town = listOfNotNull(
            postalCode?.trim()?.ifEmpty { null },
            city?.trim()?.ifEmpty { null },
        ).joinToString(" ").ifEmpty { null }
        return listOfNotNull(street?.trim()?.ifEmpty { null }, town)
            .joinToString(", ")
            .ifEmpty { null }
    }

    /** As the business holds them: by position, a missing position last. */
    fun ordered(addresses: List<BusinessAddress>): List<BusinessAddress> =
        addresses.sortedWith(compareBy<BusinessAddress> { it.position == null }.thenBy { it.position ?: 0 })

    fun main(addresses: List<BusinessAddress>): BusinessAddress? = ordered(addresses).firstOrNull()

    /**
     * The address [addressId] names. Null for no assignment, and for a row that
     * is not here — deleted on another device, or not synchronised yet.
     */
    fun assigned(addressId: String?, addresses: List<BusinessAddress>): BusinessAddress? =
        addressId?.let { id -> addresses.firstOrNull { it.id == id } }

    /** Where a contact person is: the address they are assigned to, else the main address. */
    fun forContact(addressId: String?, addresses: List<BusinessAddress>): BusinessAddress? =
        assigned(addressId, addresses) ?: main(addresses)

    /** How a choice shows an address: its label, else the address itself. */
    fun name(address: BusinessAddress): String =
        address.label?.trim()?.ifEmpty { null } ?: address.oneLine ?: "Adresse"

    /**
     * The business's name and the cities of all its addresses, lower-cased.
     * SQLite only lower-cases ASCII; without this "müller" would not find
     * "Müller".
     */
    fun searchText(name: String, cities: List<String?>): String =
        (listOf(name) + cities.mapNotNull { it?.trim()?.ifEmpty { null } }).joinToString(" ").lowercase()

    /** The rows as a form edits them, main address first. */
    fun drafts(addresses: List<BusinessAddress>): List<AddressDraft> =
        ordered(addresses).map {
            AddressDraft(
                id = it.id,
                label = it.label.orEmpty(),
                street = it.street.orEmpty(),
                postalCode = it.postalCode.orEmpty(),
                city = it.city.orEmpty(),
            )
        }

    /** „Als Hauptadresse": the row at [index] moves to the top, the others keep their order. */
    fun makeMain(drafts: List<AddressDraft>, index: Int): List<AddressDraft> {
        if (index !in drafts.indices) return drafts
        return listOf(drafts[index]) + drafts.filterIndexed { i, _ -> i != index }
    }
}
```

In `…/calling/Appointment.kt`, add the imports `io.github.amadeusb.callsheet.data.Addresses` and `io.github.amadeusb.callsheet.data.BusinessAddress`, and replace the body of `address`:

```kotlin
    /** Street, postal code and city on one line. Null when nothing is known. */
    fun address(street: String?, postalCode: String?, city: String?): String? =
        Addresses.oneLine(street, postalCode, city)

    /**
     * The place a visit starts at: the address of [contactId]'s person — the one
     * they are assigned to, else the business's main address. Empty without any
     * address.
     */
    fun presetLocation(contactId: String?, contacts: List<Contact>, addresses: List<BusinessAddress>): String {
        val person = contacts.firstOrNull { it.id == contactId }
        return Addresses.forContact(person?.addressId, addresses)?.oneLine.orEmpty()
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AddressesTest" --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt \
        app/src/main/java/io/github/amadeusb/callsheet/data/Addresses.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AddressesTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Regeln für mehrere Adressen: Hauptadresse, Zuordnung, Suchtext, Ort eines Termins"
```

---

### Task 4: App — schema 7

**Repository:** app.

**Files:**
- Modify: `…/data/Database.kt`
- Test: `test/MigrationTest.kt`, `test/SyncSchemaTest.kt`

**Interfaces:**
- Produces: table `business_addresses (id, place_id, label, street, postal_code, city, latitude, longitude, position, updated_at, dirty)`, column `contacts.address_id`, local table `removed_main_addresses (place_id)`. Carried-over rows `main-<place_id>`, marked dirty.

- [ ] **Step 1: Write the failing tests**

In `test/SyncSchemaTest.kt`, add `"business_addresses"` to the list in `every synchronised table carries a dirty flag`:

```kotlin
        for (table in listOf("businesses", "calls", "contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses")) {
```

and add:

```kotlin
    @Test
    fun `business addresses, a contact's address and the removed main addresses have their columns`() {
        assertEquals(
            setOf("id", "place_id", "label", "street", "postal_code", "city", "latitude", "longitude", "position", "updated_at", "dirty"),
            columns("business_addresses"),
        )
        assertTrue(columns("contacts").contains("address_id"))
        assertEquals(setOf("place_id"), columns("removed_main_addresses"))
    }
```

In `test/MigrationTest.kt`, after `createVersionFour()`, add the fixture:

```kotlin
    /**
     * The version 6 schema, as 1.5.0 ships it: version 4 plus contact emails (5)
     * and the appointment kind (6). alt-1 carries a full imported address with
     * coordinates and is synchronised, alt-2 only a city and is not uploaded
     * yet, alt-3 an empty street — no address. alt-1 has a contact person.
     */
    private fun createVersionSix() {
        createVersionFour()
        val db = context.openOrCreateDatabase("callsheet.db", 0, null)
        db.execSQL(
            "CREATE TABLE contact_emails (id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, email TEXT NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0, updated_at TEXT, dirty INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("ALTER TABLE appointments ADD COLUMN kind TEXT")
        db.execSQL("ALTER TABLE appointments ADD COLUMN done_at TEXT")
        db.execSQL("UPDATE businesses SET follow_up_at = NULL")
        db.execSQL(
            "UPDATE businesses SET street = 'Musterstraße 39', postal_code = '85055', city = 'Ingolstadt', " +
                "latitude = 48.7651, longitude = 11.4237, dirty = 0 WHERE place_id = 'alt-1'"
        )
        db.execSQL("UPDATE businesses SET city = 'Gaimersheim', dirty = 1 WHERE place_id = 'alt-2'")
        db.execSQL("UPDATE businesses SET street = '', dirty = 0 WHERE place_id = 'alt-3'")
        db.execSQL(
            "INSERT INTO contacts (id, place_id, name, position, updated_at, dirty) " +
                "VALUES ('k-1', 'alt-1', 'Erika Beispiel', 0, '2026-09-07T12:00:00+02:00', 0)"
        )
        db.version = 6
        db.close()
    }
```

Before `// --- and a database that never had to migrate at all ---`, add:

```kotlin
    // --- from version 6, the road 1.5.0 devices are on ----------------------

    @Test
    fun `an upgrade from version six carries each address over as the main address`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery(
            "SELECT id, place_id, label, street, postal_code, city, latitude, longitude, position, updated_at, dirty " +
                "FROM business_addresses ORDER BY id",
            null,
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("main-alt-1", c.getString(0))
            assertEquals("alt-1", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals("Musterstraße 39", c.getString(3))
            assertEquals("85055", c.getString(4))
            assertEquals("Ingolstadt", c.getString(5))
            assertEquals(48.7651, c.getDouble(6), 0.0)
            assertEquals(11.4237, c.getDouble(7), 0.0)
            assertEquals(0, c.getInt(8))
            // The business's timestamp, so the server's carried-over row meets this one as a standstill.
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(9))
            // Sent up: a server that has the row takes it as a standstill.
            assertEquals(1, c.getInt(10))
            assertTrue(c.moveToNext())
            assertEquals("main-alt-2", c.getString(0))
            assertTrue(c.isNull(3))
            assertEquals("Gaimersheim", c.getString(5))
            assertEquals("2026-09-08T09:00:00+02:00", c.getString(9))
        }
    }

    @Test
    fun `an upgrade from version six keeps the old address columns and leaves the businesses unmarked`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT street, city, dirty, updated_at FROM businesses WHERE place_id = 'alt-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Musterstraße 39", c.getString(0))
            assertEquals("Ingolstadt", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals("2026-09-07T12:00:00+02:00", c.getString(3))
        }
    }

    @Test
    fun `an upgrade from version six gives contacts an empty address and leaves them unmarked`() {
        createVersionSix()

        val db = Database(context).readableDatabase

        db.rawQuery("SELECT address_id, dirty FROM contacts WHERE id = 'k-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(0, c.getInt(1))
        }
    }

    @Test
    fun `a fresh database and an upgraded one have the same address columns`() {
        createVersionSix()
        val upgraded = Database(context).readableDatabase.let { db ->
            (columnsOf("business_addresses", db) + columnsOf("contacts", db).map { "contacts.$it" } +
                columnsOf("removed_main_addresses", db).map { "removed.$it" }).also { db.close() }
        }
        Database.resetSharedInstanceForTesting()
        context.deleteDatabase("callsheet.db")

        val fresh = Database(context).readableDatabase.let { db ->
            columnsOf("business_addresses", db) + columnsOf("contacts", db).map { "contacts.$it" } +
                columnsOf("removed_main_addresses", db).map { "removed.$it" }
        }

        // PRAGMA on a missing table returns no columns on both sides.
        assertTrue("position" in fresh)
        assertTrue("contacts.address_id" in fresh)
        assertTrue("removed.place_id" in fresh)
        assertEquals(fresh, upgraded)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: FAIL — `no such table: business_addresses` (the database is still version 6, so `createVersionSix` is not upgraded at all), and the column set assertions fail.

- [ ] **Step 3: The schema**

In `…/data/Database.kt`, companion object: `const val VERSION = 7`, and add after `COLUMNS_APPOINTMENTS_6`:

```kotlin
        /**
         * Schema 7: every address of a business — the one it had carried over
         * as `main-<place_id>`. Synchronised like contact_emails: a row per
         * address, deleted through tombstones.
         *
         * `position` is nullable, as on the server, where a NOT NULL column would
         * stand where a gap belongs. The app writes it always and reads a NULL
         * as last.
         */
        private const val TABLE_ADDRESSES = """
            CREATE TABLE business_addresses (
                id          TEXT PRIMARY KEY,
                place_id    TEXT NOT NULL,
                label       TEXT,
                street      TEXT,
                postal_code TEXT,
                city        TEXT,
                latitude    REAL,
                longitude   REAL,
                position    INTEGER,
                updated_at  TEXT NOT NULL,
                dirty       INTEGER NOT NULL DEFAULT 0
            )
        """

        private val INDEXES_ADDRESSES = listOf(
            "CREATE INDEX idx_business_addresses_place_id ON business_addresses(place_id)",
            "CREATE INDEX idx_business_addresses_dirty ON business_addresses(dirty)",
        )

        /**
         * Schema 7: the address a contact person sits at. Added by ALTER on both
         * roads, like COLUMNS_APPOINTMENTS_6. Nullable: null is no assignment.
         */
        private const val COLUMN_CONTACTS_7 = "ALTER TABLE contacts ADD COLUMN address_id TEXT"

        /**
         * Local only, never synchronised: the businesses whose `main-` address
         * was removed — by hand on this device, or by a tombstone from the
         * server. Only the import reads it, so a re-import does not bring the
         * address back.
         *
         * `deletions` cannot say it. It is the outgoing queue: a tombstone
         * leaves it once the server has it, and an incoming one is not kept.
         */
        private const val TABLE_REMOVED_MAIN_ADDRESSES = """
            CREATE TABLE removed_main_addresses (
                place_id TEXT PRIMARY KEY
            )
        """
```

In `onCreate`, right after `for (sql in COLUMNS_APPOINTMENTS_6) db.execSQL(sql)`, add:

```kotlin
        db.execSQL(COLUMN_CONTACTS_7)
        db.execSQL(TABLE_ADDRESSES)
        for (sql in INDEXES_ADDRESSES) db.execSQL(sql)
        db.execSQL(TABLE_REMOVED_MAIN_ADDRESSES)
```

In `onUpgrade`, after the `if (old < 6) { … }` block, add:

```kotlin
        if (old < 7) {
            db.execSQL(COLUMN_CONTACTS_7)
            db.execSQL(TABLE_ADDRESSES)
            for (sql in INDEXES_ADDRESSES) db.execSQL(sql)
            db.execSQL(TABLE_REMOVED_MAIN_ADDRESSES)
            // The business's address becomes its main address. The id is fixed,
            // not a fresh UUID: the server's migration 009 writes the same
            // 'main-' || place_id under the same condition, so the two meet as
            // one row. updated_at comes from the business for the same reason.
            //
            // Marked dirty, as schemas 4 and 6 marked their rows: an address the
            // server does not have yet reaches it, and one it has arrives there
            // as a standstill and changes nothing.
            //
            // The old columns stay and are not emptied: nothing new reads them,
            // and a device still on the old version keeps its address.
            db.execSQL(
                """
                INSERT INTO business_addresses (id, place_id, street, postal_code, city, latitude, longitude, position, updated_at, dirty)
                SELECT 'main-' || place_id, place_id, street, postal_code, city, latitude, longitude, 0, updated_at, 1
                FROM businesses
                WHERE COALESCE(street, '') <> '' OR COALESCE(postal_code, '') <> '' OR COALESCE(city, '') <> ''
                """.trimIndent()
            )
        }
```

Also extend the comment above `TABLE_DELETIONS` to: `Contacts, their numbers and emails, appointments and business addresses are the only rows the app deletes; …`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.MigrationTest" --tests "io.github.amadeusb.callsheet.SyncSchemaTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Database.kt \
        app/src/test/java/io/github/amadeusb/callsheet/MigrationTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/SyncSchemaTest.kt
git commit -m "Schema 7: Adressen als eigene Tabelle, Anschrift wird main-<place_id>, Ansprechpartner bekommen address_id"
```

---

### Task 5: App — addresses travel

**Repository:** app.

**Files:**
- Create: `…/data/AddressRows.kt`
- Modify: `…/sync/Rows.kt` (`TABLES`)
- Modify: `…/sync/SyncStore.kt` (`apply`, `applyTombstone`, `TOMBSTONE_TABLES`)
- Modify: `…/contacts/Preferences.kt` (`refetchedForAddresses`)
- Modify: `…/sync/SyncEngine.kt` (refetch)
- Test: `test/SyncStoreTest.kt`, `test/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `Addresses.MAIN_PREFIX`, `Addresses.searchText` (Task 3); the tables (Task 4).
- Produces:
  - `object AddressRows { fun refreshSearchText(db: SQLiteDatabase, placeId: String); fun rememberMainRemoved(db: SQLiteDatabase, placeId: String); fun forgetMainRemoved(db: SQLiteDatabase, placeId: String); fun mainRemoved(db: SQLiteDatabase, placeId: String): Boolean }`
  - `Rows.TABLES` ends with `"business_addresses"`
  - `Preferences.refetchedForAddresses: Boolean`

- [ ] **Step 1: Write the failing tests**

In `test/SyncStoreTest.kt`, add the import `org.junit.Assert.assertNull`, and next to the other helpers:

```kotlin
    private fun adresseJson(id: String, placeId: String, city: String?, zeit: String, position: Int = 0) = JSONObject().apply {
        put("id", id); put("place_id", placeId); put("label", JSONObject.NULL)
        put("street", JSONObject.NULL); put("postal_code", JSONObject.NULL)
        put("city", city ?: JSONObject.NULL)
        put("latitude", JSONObject.NULL); put("longitude", JSONObject.NULL)
        put("position", position); put("updated_at", zeit)
    }

    private fun grabstein(table: String, id: String, zeit: String) = JSONArray(listOf(JSONObject().apply {
        put("table_name", table); put("row_id", id); put("deleted_at", zeit)
    }))

    private fun einzeln(sql: String): String? =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
```

Add the tests:

```kotlin
    @Test
    fun `incoming business addresses are written unmarked, and the search text holds their cities`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)

        store.apply(antwort().put("business_addresses", JSONArray(listOf(
            adresseJson("main-P1", "P1", "Ingolstadt", "2026-09-07T10:00:00+02:00"),
            adresseJson("A2", "P1", "Eichstätt", "2026-09-07T10:00:00+02:00", position = 1),
        ))))

        assertEquals("Eichstätt", einzeln("SELECT city FROM business_addresses WHERE id = 'A2'"))
        assertEquals(0, zahl("SELECT SUM(dirty) FROM business_addresses"))
        assertEquals("elektro meier ingolstadt eichstätt", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
        // Derived, not a change: the business stays unmarked.
        assertEquals(0, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a remote tombstone removes a business address, and the search text drops its city`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 0)"
        )
        schreibe("UPDATE businesses SET search_text = 'elektro meier eichstätt' WHERE place_id = 'P1'")

        store.apply(antwort().put("deleted", grabstein("business_addresses", "A2", "2026-09-08T10:00:00+02:00")))

        assertNull(einzeln("SELECT id FROM business_addresses WHERE id = 'A2'"))
        assertEquals("elektro meier", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `a tombstone for a main address is remembered for the import, and an incoming main address forgets it`() {
        store.apply(antwort().put("deleted", grabstein("business_addresses", "main-P1", "2026-09-08T10:00:00+02:00")))
        assertEquals("P1", einzeln("SELECT place_id FROM removed_main_addresses"))

        store.apply(antwort().put("business_addresses", JSONArray(listOf(
            adresseJson("main-P1", "P1", "Ingolstadt", "2026-09-09T10:00:00+02:00"),
        ))))
        assertNull(einzeln("SELECT place_id FROM removed_main_addresses"))
    }

    @Test
    fun `a tombstone older than the main address here is not remembered`() {
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('main-P1', 'P1', 'Ingolstadt', 0, '2026-09-09T10:00:00+02:00', 0)"
        )

        store.apply(antwort().put("deleted", grabstein("business_addresses", "main-P1", "2026-09-08T10:00:00+02:00")))

        assertEquals("main-P1", einzeln("SELECT id FROM business_addresses"))
        assertNull(einzeln("SELECT place_id FROM removed_main_addresses"))
    }

    @Test
    fun `an incoming business keeps the search text of all its addresses`() {
        einBetrieb("P1", null, "2026-09-07T10:00:00+02:00", dirty = 0)
        schreibe(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 0)"
        )

        store.apply(antwort(betriebJson("P1", "neu", "2026-09-08T10:00:00+02:00").put("search_text", "elektro meier")))

        assertEquals("elektro meier eichstätt", einzeln("SELECT search_text FROM businesses WHERE place_id = 'P1'"))
    }

    @Test
    fun `business addresses and a contact's address go up`() {
        schreibe(
            "INSERT INTO business_addresses (id, place_id, label, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Filiale', 'Eichstätt', 1, '2026-09-07T10:00:00+02:00', 1)"
        )
        schreibe(
            "INSERT INTO contacts (id, place_id, name, position, updated_at, address_id, dirty) " +
                "VALUES ('K1', 'P1', 'Frau Meier', 0, '2026-09-07T10:00:00+02:00', 'A2', 1)"
        )

        val payload = store.pending(500)

        assertEquals("Filiale", payload.getJSONArray("business_addresses").getJSONObject(0).getString("label"))
        assertEquals("A2", payload.getJSONArray("contacts").getJSONObject(0).getString("address_id"))
    }

    @Test
    fun `an incoming contact's address comes down`() {
        store.apply(antwort().put("contacts", JSONArray(listOf(JSONObject().apply {
            put("id", "K1"); put("place_id", "P1"); put("name", "Frau Meier"); put("position", 0)
            put("updated_at", "2026-09-07T10:00:00+02:00"); put("address_id", "A2")
        }))))

        assertEquals("A2", einzeln("SELECT address_id FROM contacts WHERE id = 'K1'"))
    }
```

In `test/SyncEngineTest.kt`, add `prefs.refetchedForAddresses = true` to `a device that has fetched everything since keeps its watermark` (after `prefs.refetchedForCallbacks = true`), and add:

```kotlin
    @Test
    fun `the first sync after the address update starts from watermark zero, and only that one`() {
        prefs.watermark = 42
        prefs.refetchedForAppointments = true
        prefs.refetchedForCallbacks = true
        prefs.refetchedForAddresses = false
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

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: FAIL — compilation error `Unresolved reference: refetchedForAddresses`.

- [ ] **Step 3: The shared database helpers**

Create `…/data/AddressRows.kt`:

```kotlin
package io.github.amadeusb.callsheet.data

import android.database.sqlite.SQLiteDatabase

/**
 * The database side of addresses that both [Repository] and
 * [io.github.amadeusb.callsheet.sync.SyncStore] need. Each call runs inside the
 * caller's transaction.
 */
object AddressRows {

    /**
     * Brings a business's `search_text` up to its name and the cities of all its
     * addresses ([Addresses.searchText]). Derived, not a change: no
     * `updated_at`, no mark, and no write when the text is already right.
     */
    fun refreshSearchText(db: SQLiteDatabase, placeId: String) {
        val name = db.rawQuery("SELECT name FROM businesses WHERE place_id = ?", arrayOf(placeId))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        val cities = ArrayList<String?>()
        db.rawQuery(
            "SELECT city FROM business_addresses WHERE place_id = ? ORDER BY position IS NULL, position, id",
            arrayOf(placeId),
        ).use { c ->
            while (c.moveToNext()) cities.add(if (c.isNull(0)) null else c.getString(0))
        }
        val text = Addresses.searchText(name, cities)
        db.execSQL(
            "UPDATE businesses SET search_text = ? WHERE place_id = ? AND (search_text IS NULL OR search_text <> ?)",
            arrayOf(text, placeId, text),
        )
    }

    /** The business's `main-` address was removed — see Database's `removed_main_addresses`. */
    fun rememberMainRemoved(db: SQLiteDatabase, placeId: String) {
        db.execSQL("INSERT OR IGNORE INTO removed_main_addresses (place_id) VALUES (?)", arrayOf(placeId))
    }

    /** The business's `main-` address is back. */
    fun forgetMainRemoved(db: SQLiteDatabase, placeId: String) {
        db.delete("removed_main_addresses", "place_id = ?", arrayOf(placeId))
    }

    fun mainRemoved(db: SQLiteDatabase, placeId: String): Boolean =
        db.rawQuery("SELECT COUNT(*) FROM removed_main_addresses WHERE place_id = ?", arrayOf(placeId))
            .use { it.moveToFirst() && it.getInt(0) > 0 }
}
```

- [ ] **Step 4: The table travels**

In `…/sync/Rows.kt`:

```kotlin
    val TABLES = listOf(
        "businesses", "calls", "contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses",
    )
```

In `…/sync/SyncStore.kt`, add the imports `io.github.amadeusb.callsheet.data.AddressRows` and `io.github.amadeusb.callsheet.data.Addresses`. Replace `apply` with:

```kotlin
    /**
     * Applies what the server sent. Never marks anything as dirty.
     *
     * A business's `search_text` is derived from its name and the cities of all
     * its addresses. Whatever this call writes of a business or writes or
     * removes of an address, the text of that business is brought up to date
     * at the end, in the same transaction.
     */
    fun apply(response: JSONObject): AppliedAppointments {
        val db = helper.writableDatabase
        // Fetched once per call rather than once per row — the schema does
        // not change mid-sync, and a first sync can carry a few thousand rows.
        val columnsByTable = Rows.TABLES.associateWith { columns(db, it) }
        val written = ArrayList<String>()
        val removed = ArrayList<RemovedAppointment>()
        val searchStale = HashSet<String>()
        db.beginTransaction()
        try {
            val deletions = response.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until deletions.length()) {
                applyTombstone(db, deletions.getJSONObject(i), searchStale)?.let { removed.add(it) }
            }
            for (table in Rows.TABLES) {
                val rows = response.optJSONArray(table) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (!applyRow(db, table, row, columnsByTable.getValue(table))) continue
                    when (table) {
                        "appointments" -> written.add(row.getString("id"))
                        "businesses" -> searchStale.add(row.getString("place_id"))
                        "business_addresses" -> {
                            if (!row.isNull("place_id")) searchStale.add(row.getString("place_id"))
                            // Here again: a re-import updates it rather than leaving it out.
                            val id = row.getString("id")
                            if (id.startsWith(Addresses.MAIN_PREFIX)) {
                                AddressRows.forgetMainRemoved(db, id.removePrefix(Addresses.MAIN_PREFIX))
                            }
                        }
                    }
                }
            }
            for (placeId in searchStale) AddressRows.refreshSearchText(db, placeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return AppliedAppointments(written, removed)
    }
```

Change `applyTombstone`'s signature to `private fun applyTombstone(db: SQLiteDatabase, stone: JSONObject, searchStale: MutableSet<String>): RemovedAppointment?`, and replace everything from `val key = Rows.key(table)` down to `db.delete(table, "$key = ?", arrayOf(id))` with:

```kotlin
        val key = Rows.key(table)
        val localAt = db.rawQuery("SELECT updated_at FROM $table WHERE $key = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        val stoneWins = localAt == null || !Merge.isNewer(localAt, at)

        // A main address removed on another device stays removed for this
        // device's import too — see Database's removed_main_addresses. Only when
        // the tombstone wins: a row here that is newer was added back since.
        if (table == "business_addresses" && id.startsWith(Addresses.MAIN_PREFIX) && stoneWins) {
            AddressRows.rememberMainRemoved(db, id.removePrefix(Addresses.MAIN_PREFIX))
        }
        if (localAt == null || !stoneWins) return null

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
        if (table == "business_addresses") {
            db.rawQuery("SELECT place_id FROM business_addresses WHERE id = ?", arrayOf(id))
                .use { if (it.moveToFirst()) searchStale.add(it.getString(0)) }
        }
        db.delete(table, "$key = ?", arrayOf(id))
```

(The contacts cascade and `return link` after it stay as they are.)

Replace `TOMBSTONE_TABLES`:

```kotlin
        /**
         * The only tables the app ever deletes rows from. `businesses` has no
         * `id` column (its key is `place_id`), and the app never deletes a
         * business or a call — a tombstone naming either must never reach SQL.
         */
        val TOMBSTONE_TABLES = setOf("contacts", "contact_numbers", "contact_emails", "appointments", "business_addresses")
```

- [ ] **Step 5: The refetch**

In `…/contacts/Preferences.kt`, after `refetchedForCallbacks`:

```kotlin
    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about several addresses per business (schema 7).
     *
     * While it still ran 1.5.x, the server delivered `business_addresses` rows
     * to it — the ones migration 009 carried over, and any another device added
     * — and the old app skipped them while its watermark moved past. Its own
     * migration only brings the main address it had; a branch added elsewhere,
     * or a later change, would never come down. Contacts it stored without
     * `address_id` get the column filled at the standstill (SyncStore.fillGaps).
     * A flag for the reason [refetchedForAppointments] gives.
     */
    var refetchedForAddresses: Boolean
        get() = store.getBoolean(REFETCHED_FOR_ADDRESSES, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_ADDRESSES, value).apply()
```

and in the companion: `const val REFETCHED_FOR_ADDRESSES = "refetched_for_addresses"`.

In `…/sync/SyncEngine.kt`, after the `refetchedForCallbacks` block:

```kotlin
            // Once more, on the first sync that runs on schema 7 — see
            // Preferences.refetchedForAddresses. The addresses a 1.5.x app
            // skipped come down, and contacts get their address_id filled.
            if (!prefs.refetchedForAddresses) {
                prefs.watermark = 0
                prefs.refetchedForAddresses = true
            }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.SyncStoreTest" --tests "io.github.amadeusb.callsheet.SyncEngineTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/AddressRows.kt \
        app/src/main/java/io/github/amadeusb/callsheet/sync/Rows.kt \
        app/src/main/java/io/github/amadeusb/callsheet/sync/SyncStore.kt \
        app/src/main/java/io/github/amadeusb/callsheet/sync/SyncEngine.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/Preferences.kt \
        app/src/test/java/io/github/amadeusb/callsheet/SyncStoreTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/SyncEngineTest.kt
git commit -m "Adressen werden abgeglichen, der Suchtext folgt, nach dem Update einmal alles neu holen"
```

---

### Task 6: App — import and lists read addresses

**Repository:** app.

**Files:**
- Modify: `…/data/Repository.kt` (`import`, `importedValues`, new `importAddress`, `addresses`, `create`, `list`, `business`, `cities`, `withBusinesses`, `blockedBusinesses`, `businessesForPhoneBook`, `condition`, `fromCursor`, new `addressFromCursor`, companion `MAIN_CITY_SUBQUERY`; `searchText` goes)
- Test: `test/RepositoryTest.kt`, `test/BusinessFormTest.kt`

**Interfaces:**
- Consumes: `AddressRows.refreshSearchText`, `AddressRows.mainRemoved` (Task 5); `Addresses.mainId`, `Addresses.searchText` (Task 3).
- Produces:
  - `suspend fun Repository.addresses(placeId: String): List<BusinessAddress>` — main address first
  - `Business.city` = the city of the main address (query column `main_city`), on every business query
  - The import writes `main-<place_id>` and no longer writes `businesses.street/postal_code/city/latitude/longitude`
  - `Repository.create` writes the draft's street/postal code/city as an address row at position 0 (Task 10 replaces this with the list)

- [ ] **Step 1: Write the failing tests**

In `test/RepositoryTest.kt`, add the import `io.github.amadeusb.callsheet.data.BusinessAddress` (unused until Task 7 is fine) and, before `private companion object`, add:

```kotlin
    // --------------------------------------------------------------- Addresses

    @Test
    fun `the import writes the address as the main address of a new business`() = runTest {
        import(FIRST_IMPORT)

        val address = repo.addresses("P1").single()
        assertEquals("main-P1", address.id)
        assertNull(address.label)
        assertEquals("Musterweg 1", address.street)
        assertEquals("85049", address.postalCode)
        assertEquals("Ingolstadt", address.city)
        assertEquals(0, address.position)
        assertEquals(1, count("SELECT dirty FROM business_addresses WHERE id = 'main-P1'"))
        // The business's own columns are no longer written.
        assertEquals(0, count("SELECT COUNT(*) FROM businesses WHERE street IS NOT NULL OR city IS NOT NULL"))
    }

    @Test
    fun `a re-import updates the main address when it changed, keeping label and position`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE business_addresses SET label = 'Hauptsitz', position = 3, dirty = 0 WHERE id = 'main-P1'")

        import(SECOND_IMPORT)

        val address = repo.addresses("P1").single()
        assertEquals("Musterweg 1a", address.street)
        assertEquals("Hauptsitz", address.label)
        assertEquals(3, address.position)
        assertEquals(1, count("SELECT dirty FROM business_addresses WHERE id = 'main-P1'"))
    }

    @Test
    fun `a re-import of the same addresses writes nothing`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE business_addresses SET dirty = 0")

        val e = import(FIRST_IMPORT)

        assertEquals(0, e.updated)
        assertEquals(0, count("SELECT COUNT(*) FROM business_addresses WHERE dirty = 1"))
    }

    @Test
    fun `an address changed by the import alone counts as an update`() = runTest {
        import("""[{"placeId":"t-1","title":"Adresse Erfunden","phone":"+49 841 111","city":"Ingolstadt"}]""")

        val e = import("""[{"placeId":"t-1","title":"Adresse Erfunden","phone":"+49 841 111","city":"Eichstätt"}]""")

        assertEquals(1, e.updated)
        assertEquals("Eichstätt", repo.addresses("t-1").single().city)
    }

    @Test
    fun `a re-import leaves the other addresses of the business alone`() = runTest {
        import(FIRST_IMPORT)
        execute(
            "INSERT INTO business_addresses (id, place_id, label, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Lager', 'Eichstaett', 1, '2026-09-01T10:00:00+02:00', 0)"
        )

        import(SECOND_IMPORT)

        val branch = repo.addresses("P1").single { it.id == "A2" }
        assertEquals("Lager", branch.label)
        assertEquals("Eichstaett", branch.city)
        assertEquals(0, count("SELECT dirty FROM business_addresses WHERE id = 'A2'"))
    }

    @Test
    fun `a missing main address is created after the existing ones`() = runTest {
        import(FIRST_IMPORT)
        execute("DELETE FROM business_addresses WHERE id = 'main-P1'")
        execute(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Eichstaett', 0, '2026-09-01T10:00:00+02:00', 0)"
        )

        import(SECOND_IMPORT)

        val addresses = repo.addresses("P1")
        assertEquals(listOf("A2", "main-P1"), addresses.map { it.id })
        assertEquals(1, addresses.last().position)
    }

    @Test
    fun `an imported business without an address gets no address row`() = runTest {
        import("""[{"placeId":"t-9","title":"Ohne Adresse","phone":"+49 841 111"}]""")

        assertTrue(repo.addresses("t-9").isEmpty())
    }

    @Test
    fun `the import's search text holds the cities of every address`() = runTest {
        import(FIRST_IMPORT)
        execute(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P1', 'Königsmoos', 1, '2026-09-01T10:00:00+02:00', 0)"
        )

        import(SECOND_IMPORT)

        assertEquals(
            setOf("P1", "P9"),
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = "königsmoos")).map { it.placeId }.toSet(),
        )
    }

    @Test
    fun `the city filter matches any address, the list shows the main address's city`() = runTest {
        import(FIRST_IMPORT)
        execute(
            "INSERT INTO business_addresses (id, place_id, city, position, updated_at, dirty) " +
                "VALUES ('A2', 'P2', 'Ingolstadt', 1, '2026-09-01T10:00:00+02:00', 0)"
        )

        val inIngolstadt = repo.list(Filter(cities = setOf("Ingolstadt"), status = emptySet(), onlyTargets = false))

        assertEquals(setOf("P1", "P2", "P4"), inIngolstadt.map { it.placeId }.toSet())
        assertEquals("Eichstaett", inIngolstadt.single { it.placeId == "P2" }.city)
        assertEquals("Eichstaett", repo.business("P2")!!.city)
        assertTrue(repo.cities().containsAll(listOf("Eichstaett", "Ingolstadt")))
    }
```

In `test/BusinessFormTest.kt`, add:

```kotlin
    @Test
    fun `a hand-entered address becomes the business's first address row`() = runTest {
        val id = repo.create(
            BusinessDraft(name = "Dachdecker Erfunden", street = "Ziegelgasse 2", postalCode = "85053", city = "Ingolstadt")
        ).getOrThrow()

        val address = repo.addresses(id).single()
        assertEquals("Ziegelgasse 2", address.street)
        assertEquals("85053", address.postalCode)
        assertEquals("Ingolstadt", address.city)
        assertEquals(0, address.position)
        assertEquals("Ingolstadt", repo.business(id)!!.city)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.BusinessFormTest"`
Expected: FAIL — compilation error `Unresolved reference: addresses`.

- [ ] **Step 3: Reading addresses, and the main city on every business**

In `…/data/Repository.kt` (package `data`, so `Addresses`, `AddressRows` and `BusinessAddress` need no import):

Add to the companion object:

```kotlin
        /**
         * The city of the business's main address — what the list shows and
         * sorts by. The business's own `city` column is no longer written.
         */
        const val MAIN_CITY_SUBQUERY =
            "(SELECT ba.city FROM business_addresses ba WHERE ba.place_id = b.place_id " +
                "ORDER BY ba.position IS NULL, ba.position, ba.id LIMIT 1) AS main_city"
```

Change every query that reads a `Business` to select `b.*` with `MAIN_CITY_SUBQUERY` from `businesses b`:

```kotlin
    // list
        val sql = "SELECT b.*, $NUMBERS_SUBQUERY, $MAIN_CITY_SUBQUERY FROM businesses b WHERE $where " +
            "ORDER BY b.industry IS NULL, b.industry COLLATE NOCASE, main_city COLLATE NOCASE, b.name COLLATE NOCASE"

    // business
            .rawQuery("SELECT b.*, $MAIN_CITY_SUBQUERY FROM businesses b WHERE b.place_id = ?", arrayOf(placeId))

    // withBusinesses
                    "SELECT b.*, $NUMBERS_SUBQUERY, $MAIN_CITY_SUBQUERY FROM businesses b WHERE b.place_id = ?",

    // blockedBusinesses
        val sql = "SELECT b.*, $MAIN_CITY_SUBQUERY FROM businesses b WHERE b.status = ? ORDER BY b.name COLLATE NOCASE"

    // businessesForPhoneBook
        val sql = "SELECT b.*, $MAIN_CITY_SUBQUERY FROM businesses b " +
            "WHERE b.status <> ? AND b.phone IS NOT NULL AND b.phone <> '' " +
            "ORDER BY b.name COLLATE NOCASE"
```

In `fromCursor`, replace `city = c.text("city"),` with:

```kotlin
        // The main address's city, see MAIN_CITY_SUBQUERY.
        city = c.text("main_city"),
```

Replace `cities()`:

```kotlin
    /** Every city of any address, for the filter and the suggestions. Blocked businesses excluded. */
    suspend fun cities(): List<String> = withContext(Dispatchers.IO) {
        val sql = "SELECT DISTINCT a.city FROM business_addresses a JOIN businesses b ON b.place_id = a.place_id " +
            "WHERE b.status <> ? AND a.city IS NOT NULL AND a.city <> '' " +
            "ORDER BY a.city COLLATE NOCASE"
        helper.readableDatabase.rawQuery(sql, arrayOf(Status.DO_NOT_CALL.key)).use { c ->
            val list = ArrayList<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }
    }
```

In `condition`, replace the cities block:

```kotlin
        if (filter.cities.isNotEmpty()) {
            // Any of the business's addresses, the way the search reads every city.
            parts.add(
                "EXISTS (SELECT 1 FROM business_addresses ba WHERE ba.place_id = ${b}place_id " +
                    "AND ba.city IN (${placeholder(filter.cities.size)}))"
            )
            args.addAll(filter.cities)
        }
```

Add, next to `appointments(placeId)`, in a new section `// ------------------------------------------------------------- Addresses`:

```kotlin
    /** A business's addresses, main address first. */
    suspend fun addresses(placeId: String): List<BusinessAddress> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM business_addresses WHERE place_id = ? ORDER BY position IS NULL, position, id",
            arrayOf(placeId),
        ).use { c ->
            val list = ArrayList<BusinessAddress>(c.count)
            while (c.moveToNext()) list.add(addressFromCursor(c))
            list
        }
    }
```

and in the `// Cursor` section:

```kotlin
    private fun addressFromCursor(c: Cursor): BusinessAddress = BusinessAddress(
        id = c.text("id") ?: "",
        placeId = c.text("place_id") ?: "",
        label = c.text("label"),
        street = c.text("street"),
        postalCode = c.text("postal_code"),
        city = c.text("city"),
        latitude = c.decimal("latitude"),
        longitude = c.decimal("longitude"),
        position = c.int("position"),
    )
```

- [ ] **Step 4: The import writes the main address**

Remove `private fun searchText(name: String, city: String?)`.

Replace `importedValues` with (search text and address columns gone):

```kotlin
    /**
     * The business's imported master data. The address goes into
     * `business_addresses` ([importAddress]); the business's own address columns
     * are no longer written, and `search_text` follows the addresses
     * (AddressRows.refreshSearchText).
     */
    private fun importedValues(s: ImportedBusiness): ContentValues = ContentValues().apply {
        put("name", s.name)
        put("industry", s.industry)
        put("categories", toJson(s.categories))
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
```

Replace the `for (s in imported) { … }` loop in `import` with:

```kotlin
            for (s in imported) {
                val values = importedValues(s)
                val businessChanged = if (known.contains(s.placeId)) {
                    // Imported master data only. status, note and
                    // updated_at are deliberately absent from [importedValues].
                    val same = db.rawQuery(
                        "SELECT * FROM businesses WHERE place_id = ?", arrayOf(s.placeId),
                    ).use { c -> c.moveToFirst() && matchesStored(c, values) }
                    if (!same) {
                        values.put("updated_at", now)
                        values.put("dirty", 1)
                        db.update("businesses", values, "place_id = ?", arrayOf(s.placeId))
                    }
                    // Identical master data: nothing to write. A row that was
                    // already synced must not be marked and sent up again for
                    // no reason — and a stale `updated_at` here would make the
                    // server's own, real change look older than it is.
                    !same
                } else {
                    values.put("place_id", s.placeId)
                    values.put("status", Status.NEW.key)
                    values.put("updated_at", now)
                    values.put("dirty", 1)
                    db.insert("businesses", null, values)
                    new++
                    false
                }
                val addressChanged = importAddress(db, s, now)
                if (known.contains(s.placeId) && (businessChanged || addressChanged)) updated++
                // search_text reads the name and the cities: only a business that
                // is new, renamed, or whose main address was written needs it.
                // An unchanged re-import of thousands of rows runs no query for it.
                if (!known.contains(s.placeId) || businessChanged || addressChanged) {
                    AddressRows.refreshSearchText(db, s.placeId)
                }
            }
```

Add after `matchesStored`:

```kotlin
    /**
     * Writes the imported address into the business's main address
     * `main-<place_id>`. Returns whether anything was written.
     *
     * - An address with every field empty writes nothing.
     * - The row is there: street, postal code, city and coordinates are compared
     *   and, where they differ, written, stamped and marked. Label and position
     *   stay — a label typed by hand, or another row made the main address,
     *   survive a re-import.
     * - The row is not there: removed by hand (AddressRows.mainRemoved), it stays
     *   removed; otherwise it is created after the last existing address.
     *
     * The business's other addresses are never touched.
     */
    private fun importAddress(db: android.database.sqlite.SQLiteDatabase, s: ImportedBusiness, now: String): Boolean {
        if (s.street == null && s.postalCode == null && s.city == null) return false
        val id = Addresses.mainId(s.placeId)
        val stored = db.rawQuery("SELECT * FROM business_addresses WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) addressFromCursor(c) else null }
        val values = ContentValues().apply {
            put("street", s.street)
            put("postal_code", s.postalCode)
            put("city", s.city)
            put("latitude", s.latitude)
            put("longitude", s.longitude)
            put("updated_at", now)
            put("dirty", 1)
        }
        if (stored != null) {
            val same = stored.street == s.street && stored.postalCode == s.postalCode && stored.city == s.city &&
                stored.latitude == s.latitude && stored.longitude == s.longitude
            if (same) return false
            db.update("business_addresses", values, "id = ?", arrayOf(id))
            return true
        }
        if (AddressRows.mainRemoved(db, s.placeId)) return false
        val next = db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM business_addresses WHERE place_id = ?",
            arrayOf(s.placeId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        values.put("id", id)
        values.put("place_id", s.placeId)
        values.put("position", next)
        db.insert("business_addresses", null, values)
        return true
    }
```

- [ ] **Step 5: A hand-entered business writes an address row**

In `create`, replace everything from `val industry = new.industry.trim().ifEmpty { null }` to `Result.success(placeId)` with:

```kotlin
        val industry = new.industry.trim().ifEmpty { null }
        val street = new.street.trim().ifEmpty { null }
        val postalCode = new.postalCode.trim().ifEmpty { null }
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
            put("search_text", Addresses.searchText(name, listOf(city)))
            put("industry", industry)
            put("categories", JSONArray(emptyList<String>()).toString())
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
            put("dirty", 1)
        }

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insert("businesses", null, values)
            if (street != null || postalCode != null || city != null) {
                db.insert(
                    "business_addresses", null,
                    ContentValues().apply {
                        put("id", java.util.UUID.randomUUID().toString())
                        put("place_id", placeId)
                        put("street", street)
                        put("postal_code", postalCode)
                        put("city", city)
                        put("position", 0)
                        put("updated_at", now)
                        put("dirty", 1)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        Result.success(placeId)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.BusinessFormTest"`
Expected: PASS — including the existing `a second import leaves the work untouched` (still 2 updated: P1's name and street, P5's phone), `filters take effect in SQL`, `the search finds umlauts regardless of case` and `the list sorts by industry, city, name`.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/BusinessFormTest.kt
git commit -m "Import schreibt die Hauptadresse, Ortsfilter und Liste lesen die Adressen"
```

---

### Task 7: App — saving addresses, assigning a person

**Repository:** app.

**Files:**
- Modify: `…/data/Repository.kt` (new `saveAddresses`; `contacts` reads, `saveContact` writes `address_id`)
- Modify: `…/contacts/ContactMerge.kt` (`merge` keeps `addressId`)
- Test: `test/RepositoryTest.kt`, `test/ContactMergeTest.kt`

**Interfaces:**
- Consumes: `addresses`, `addressFromCursor` (Task 6); `AddressRows` (Task 5); `Addresses` (Task 3).
- Produces:
  - `suspend fun Repository.saveAddresses(placeId: String, drafts: List<AddressDraft>)`
  - `Contact.addressId` read from and `ContactDraft.addressId` written to `contacts.address_id`

- [ ] **Step 1: Write the failing tests**

In `test/RepositoryTest.kt`, add the imports `io.github.amadeusb.callsheet.data.AddressDraft` and `io.github.amadeusb.callsheet.data.Addresses`, and in the `Addresses` section add:

```kotlin
    /** P1 with its imported main address and a branch, nothing marked. */
    private suspend fun twoAddresses(): List<BusinessAddress> {
        import(FIRST_IMPORT)
        repo.saveAddresses(
            "P1",
            listOf(
                AddressDraft(id = "main-P1", street = "Musterweg 1", postalCode = "85049", city = "Ingolstadt"),
                AddressDraft(label = "Filiale", street = "Hafenstraße 5", postalCode = "85001", city = "Hafenstadt"),
            ),
        )
        execute("UPDATE business_addresses SET dirty = 0")
        return repo.addresses("P1")
    }

    @Test
    fun `saved addresses keep the order given, blank rows are left out, unchanged rows stay unmarked`() = runTest {
        import(FIRST_IMPORT)
        execute("UPDATE business_addresses SET dirty = 0")

        repo.saveAddresses(
            "P1",
            listOf(
                AddressDraft(label = "Lager", street = " "),
                AddressDraft(id = "main-P1", street = "Musterweg 1", postalCode = "85049", city = "Ingolstadt"),
                AddressDraft(label = "Filiale", city = "Hafenstadt"),
            ),
        )

        val addresses = repo.addresses("P1")
        assertEquals(2, addresses.size)
        assertEquals("main-P1", addresses[0].id)
        assertEquals(0, addresses[0].position)
        assertEquals(0, count("SELECT dirty FROM business_addresses WHERE id = 'main-P1'"))
        assertEquals("Filiale", addresses[1].label)
        assertEquals(1, addresses[1].position)
        assertEquals(1, count("SELECT dirty FROM business_addresses WHERE label = 'Filiale'"))
    }

    @Test
    fun `a contact's address is saved and read back`() = runTest {
        val (_, branch) = twoAddresses()

        val id = repo.saveContact(ContactDraft(placeId = "P1", name = "Erika Beispiel", addressId = branch.id)).getOrThrow()

        assertEquals(branch.id, repo.contacts("P1").single { it.id == id }.addressId)
    }

    @Test
    fun `removing an address clears its contacts' assignment, marks them and leaves a tombstone`() = runTest {
        val (head, branch) = twoAddresses()
        val id = repo.saveContact(ContactDraft(placeId = "P1", name = "Erika Beispiel", addressId = branch.id)).getOrThrow()
        execute("UPDATE contacts SET dirty = 0")

        repo.saveAddresses("P1", Addresses.drafts(listOf(head)))

        assertEquals(listOf("main-P1"), repo.addresses("P1").map { it.id })
        assertNull(repo.contacts("P1").single { it.id == id }.addressId)
        assertEquals(1, count("SELECT dirty FROM contacts WHERE id = '$id'"))
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM deletions WHERE table_name = 'business_addresses' AND row_id = '${branch.id}'"),
        )
    }

    @Test
    fun `removing the main address makes the next one the main address`() = runTest {
        val (_, branch) = twoAddresses()

        repo.saveAddresses("P1", Addresses.drafts(listOf(branch)))

        val left = repo.addresses("P1").single()
        assertEquals(branch.id, left.id)
        assertEquals(0, left.position)
        assertEquals(1, count("SELECT dirty FROM business_addresses WHERE id = '${branch.id}'"))
    }

    @Test
    fun `making a row the main address renumbers and marks only the moved rows`() = runTest {
        import(FIRST_IMPORT)
        repo.saveAddresses(
            "P1",
            listOf(
                AddressDraft(id = "main-P1", street = "Musterweg 1", postalCode = "85049", city = "Ingolstadt"),
                AddressDraft(label = "Filiale", city = "Hafenstadt"),
                AddressDraft(label = "Lager", city = "Königsmoos"),
            ),
        )
        execute("UPDATE business_addresses SET dirty = 0")
        val before = repo.addresses("P1")

        repo.saveAddresses("P1", Addresses.makeMain(Addresses.drafts(before), 1))

        assertEquals(listOf(before[1].id, before[0].id, before[2].id), repo.addresses("P1").map { it.id })
        assertEquals(2, count("SELECT COUNT(*) FROM business_addresses WHERE dirty = 1"))
        assertEquals(0, count("SELECT dirty FROM business_addresses WHERE id = '${before[2].id}'"))
    }

    @Test
    fun `changing street, postal code or city clears the coordinates, a new label does not`() = runTest {
        import(
            """[{"placeId":"t-2","title":"Koordinaten Erfunden","phone":"+49 841 222",
                "street":"Musterweg 1","city":"Ingolstadt","location":{"lat":48.7651,"lng":11.4237}}]"""
        )
        val imported = repo.addresses("t-2").single()
        assertEquals(48.7651, imported.latitude!!, 0.0)

        repo.saveAddresses("t-2", listOf(AddressDraft(id = imported.id, label = "Hauptsitz", street = "Musterweg 1", city = "Ingolstadt")))
        assertEquals(48.7651, repo.addresses("t-2").single().latitude!!, 0.0)

        repo.saveAddresses("t-2", listOf(AddressDraft(id = imported.id, label = "Hauptsitz", street = "Musterweg 2", city = "Ingolstadt")))
        assertNull(repo.addresses("t-2").single().latitude)
        assertNull(repo.addresses("t-2").single().longitude)
    }

    @Test
    fun `a main address removed by hand does not come back with a re-import`() = runTest {
        import(FIRST_IMPORT)
        repo.saveAddresses("P1", listOf(AddressDraft(label = "Filiale", city = "Hafenstadt")))
        // The tombstone leaves the outgoing queue once the server has it.
        execute("DELETE FROM deletions")

        import(SECOND_IMPORT)

        assertEquals(listOf("Hafenstadt"), repo.addresses("P1").map { it.city })
    }

    @Test
    fun `the search text follows saved addresses`() = runTest {
        import(FIRST_IMPORT)

        repo.saveAddresses(
            "P1",
            listOf(
                AddressDraft(id = "main-P1", street = "Musterweg 1", postalCode = "85049", city = "Ingolstadt"),
                AddressDraft(city = "Hafenstadt"),
            ),
        )

        assertEquals(
            listOf("P1"),
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = "hafenstadt")).map { it.placeId },
        )
    }
```

In `test/ContactMergeTest.kt`, add:

```kotlin
    @Test
    fun `a merged draft keeps the person's address`() {
        val draft = ContactMerge.merge(contact().copy(addressId = "A2"), fromPhoneBook(name = "Frau Anders"))!!

        assertEquals("A2", draft.addressId)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.ContactMergeTest"`
Expected: FAIL — compilation error `Unresolved reference: saveAddresses`.

- [ ] **Step 3: Saving addresses**

In `…/data/Repository.kt`, after `addresses(placeId)`, add:

```kotlin
    /**
     * Writes a business's addresses as the form holds them: in this order, the
     * first one the main address. Blank rows ([AddressDraft.isBlank]) are left
     * out, label or not.
     *
     * Only rows that changed are stamped and marked; a moved position counts.
     * Changing street, postal code or city clears the coordinates — they
     * belonged to the old address. A row no longer in [drafts] is removed with a
     * tombstone, the contacts assigned to it lose the assignment and are marked,
     * and a removed `main-` row is remembered so a re-import leaves it out.
     */
    suspend fun saveAddresses(placeId: String, drafts: List<AddressDraft>) = withContext(Dispatchers.IO) {
        val rows = drafts.filterNot { it.isBlank }
        val db = helper.writableDatabase
        val now = Clock.now()
        db.beginTransaction()
        try {
            val before = db.rawQuery("SELECT * FROM business_addresses WHERE place_id = ?", arrayOf(placeId))
                .use { c -> generateSequence { if (c.moveToNext()) addressFromCursor(c) else null }.associateBy { it.id } }
            val kept = HashSet<String>()
            rows.forEachIndexed { index, draft ->
                val label = draft.label.trim().ifEmpty { null }
                val street = draft.street.trim().ifEmpty { null }
                val postalCode = draft.postalCode.trim().ifEmpty { null }
                val city = draft.city.trim().ifEmpty { null }
                val stored = draft.id?.let { before[it] }
                if (stored == null) {
                    // New — or deleted by a sync while the form was open, and
                    // then a new row too: its old id carries a tombstone.
                    db.insert(
                        "business_addresses", null,
                        ContentValues().apply {
                            put("id", java.util.UUID.randomUUID().toString())
                            put("place_id", placeId)
                            put("label", label)
                            put("street", street)
                            put("postal_code", postalCode)
                            put("city", city)
                            put("position", index)
                            put("updated_at", now)
                            put("dirty", 1)
                        },
                    )
                    return@forEachIndexed
                }
                kept.add(stored.id)
                val moved = stored.street != street || stored.postalCode != postalCode || stored.city != city
                if (!moved && stored.label == label && stored.position == index) return@forEachIndexed
                db.update(
                    "business_addresses",
                    ContentValues().apply {
                        put("label", label)
                        put("street", street)
                        put("postal_code", postalCode)
                        put("city", city)
                        if (moved) {
                            putNull("latitude")
                            putNull("longitude")
                        }
                        put("position", index)
                        put("updated_at", now)
                        put("dirty", 1)
                    },
                    "id = ?", arrayOf(stored.id),
                )
            }
            for (gone in before.keys - kept) {
                tombstone(db, "business_addresses", gone, now)
                db.delete("business_addresses", "id = ?", arrayOf(gone))
                if (gone == Addresses.mainId(placeId)) AddressRows.rememberMainRemoved(db, placeId)
                // Here, on the device that removes it. A device that learns of
                // the removal by sync reads the dangling id as no assignment.
                db.update(
                    "contacts",
                    ContentValues().apply {
                        putNull("address_id")
                        put("updated_at", now)
                        put("dirty", 1)
                    },
                    "address_id = ?", arrayOf(gone),
                )
            }
            AddressRows.refreshSearchText(db, placeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }
```

- [ ] **Step 4: The assignment on the contact**

In `contacts(placeId)`, change the last query and the `Contact` it builds:

```kotlin
            db.rawQuery(
                "SELECT id, place_id, name, role, email, note, updated_at, contact_version, address_id " +
                    "FROM contacts " +
                    "WHERE place_id = ? ORDER BY position, name COLLATE NOCASE",
                arrayOf(placeId),
            ).use { c ->
```

and add to the `Contact(…)` there:

```kotlin
                            addressId = if (c.isNull(8)) null else c.getString(8),
```

In `saveContact`, add to the contact's `ContentValues` (after `put("note", …)`):

```kotlin
                    put("address_id", draft.addressId)
```

In `…/contacts/ContactMerge.kt`, add to the returned `ContactDraft(…)` in `merge` (after `note = …`):

```kotlin
            // Not in the phone book at all: what the app holds stays.
            addressId = existing.addressId,
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.RepositoryTest" --tests "io.github.amadeusb.callsheet.ContactMergeTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt \
        app/src/test/java/io/github/amadeusb/callsheet/RepositoryTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/ContactMergeTest.kt
git commit -m "Adressen speichern, umsortieren und entfernen; Ansprechpartner einer Adresse zuordnen"
```

---

### Task 8: App — the phone book carries the addresses

**Repository:** app.

**Files:**
- Modify: `…/contacts/PhoneBook.kt` (`PostalAddress.label`, `ContactFields.addresses`, `dataRows`)
- Modify: `…/contacts/PhoneBookEntries.kt` (`forBusiness` takes the addresses)
- Modify: `…/contacts/ContactStore.kt` (`persistBusiness` loads them)
- Test: `test/PhoneBookEntriesTest.kt`, `test/PhoneBookRowsTest.kt`

**Interfaces:**
- Consumes: `Repository.addresses` (Task 6), `Addresses.ordered/assigned/main` (Task 3).
- Produces:
  - `data class PostalAddress(street: String?, postalCode: String?, city: String?, country: String, label: String? = null)`
  - `ContactFields.addresses: List<PostalAddress> = emptyList()` (replaces `address`)
  - `fun PhoneBookEntries.forBusiness(business: Business, contacts: List<Contact>, addresses: List<BusinessAddress>): List<ContactFields>`

- [ ] **Step 1: Write the failing tests**

In `test/PhoneBookRowsTest.kt`, replace the `address` parameter of `fields` with:

```kotlin
        addresses: List<PostalAddress> = listOf(PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland")),
```

and `address = address,` with `addresses = addresses,`. In `no address means no postal row`, use `fields(addresses = emptyList(), websites = emptyList())`. Add:

```kotlin
    @Test
    fun `every address is a postal row, a labelled one under its label`() {
        val rows = PhoneBook.dataRows(
            fields(
                addresses = listOf(
                    PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland"),
                    PostalAddress("Hafenstraße 5", "85001", "Hafenstadt", "Deutschland", label = "Filiale"),
                ),
            )
        )

        val postal = rows.of(StructuredPostal.CONTENT_ITEM_TYPE)
        assertEquals(2, postal.size)
        assertEquals(StructuredPostal.TYPE_WORK, postal[0].getAsInteger(StructuredPostal.TYPE))
        assertNull(postal[0].getAsString(StructuredPostal.LABEL))
        assertEquals("Hafenstraße 5", postal[1].getAsString(StructuredPostal.STREET))
        assertEquals(StructuredPostal.TYPE_CUSTOM, postal[1].getAsInteger(StructuredPostal.TYPE))
        assertEquals("Filiale", postal[1].getAsString(StructuredPostal.LABEL))
    }
```

In `test/PhoneBookEntriesTest.kt`:

1. Add the import `io.github.amadeusb.callsheet.data.BusinessAddress`.
2. In the `business(…)` fixture, remove the parameters `street`, `postalCode`, `city` and pass `street = null, postalCode = null, city = "Musterstadt"` to `Business(…)`.
3. Add below the fixtures:

```kotlin
    private val mainAddress = BusinessAddress("main-P1", "P1", null, "Musterweg 1", "85000", "Musterstadt", position = 0)
    private val branchAddress = BusinessAddress("A2", "P1", "Filiale", "Hafenstraße 5", "85001", "Hafenstadt", position = 1)

    private fun entries(
        business: Business,
        contacts: List<Contact>,
        addresses: List<BusinessAddress> = listOf(mainAddress),
    ) = PhoneBookEntries.forBusiness(business, contacts, addresses)
```

4. Replace every `PhoneBookEntries.forBusiness(` in the file with `entries(`.
5. Replace `the address is left out when street, postal code and city are empty` with:

```kotlin
    @Test
    fun `an address row with street, postal code and city empty is left out`() {
        val full = entries(business(), emptyList()).single()
        assertEquals(listOf(PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland")), full.addresses)

        val blank = BusinessAddress("A3", "P1", "Lager", null, " ", null, position = 1)
        val none = entries(business(), emptyList(), listOf(blank)).single()
        assertTrue(none.addresses.isEmpty())
    }
```

6. In `a hand-entered business links to its address, or not at all`, change the second call to:

```kotlin
        val without = entries(business(placeId = "manual:x", website = null), emptyList(), emptyList()).single()
```

7. Add:

```kotlin
    // ---- addresses ----

    @Test
    fun `an assigned person carries only their address`() {
        val entry = entries(business(), listOf(contact().copy(addressId = "A2")), listOf(mainAddress, branchAddress)).single()
        assertEquals(
            listOf(PostalAddress("Hafenstraße 5", "85001", "Hafenstadt", "Deutschland", label = "Filiale")),
            entry.addresses,
        )
    }

    @Test
    fun `a person without an assignment, or assigned to a missing row, carries every address, main first`() {
        val unassigned = entries(business(), listOf(contact()), listOf(branchAddress, mainAddress)).single()
        assertEquals(listOf("Musterweg 1", "Hafenstraße 5"), unassigned.addresses.map { it.street })

        val dangling = entries(business(), listOf(contact().copy(addressId = "gone")), listOf(branchAddress, mainAddress)).single()
        assertEquals(listOf("Musterweg 1", "Hafenstraße 5"), dangling.addresses.map { it.street })
    }

    @Test
    fun `the company entry carries every address`() {
        val entry = entries(business(), emptyList(), listOf(mainAddress, branchAddress)).single()
        assertEquals(listOf(null, "Filiale"), entry.addresses.map { it.label })
    }

    @Test
    fun `a hand-entered business's map link follows the person's address`() {
        val entry = entries(
            business(placeId = "manual:x", website = null),
            listOf(contact().copy(addressId = "A2")),
            listOf(mainAddress, branchAddress),
        ).single()
        assertEquals(
            listOf(PhoneBookWebsite("https://www.google.com/maps/search/?api=1&query=Hafenstra%C3%9Fe+5%2C+85001+Hafenstadt", WebsiteKind.OTHER)),
            entry.websites,
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.PhoneBookEntriesTest" --tests "io.github.amadeusb.callsheet.PhoneBookRowsTest"`
Expected: FAIL — compilation errors (`No parameter with name 'addresses'`, `Too many arguments for forBusiness`).

- [ ] **Step 3: One postal row per address**

In `…/contacts/PhoneBook.kt`:

```kotlin
/** A business address as it goes into the phone book. */
data class PostalAddress(
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    /** „Filiale" … Written as a custom type; null keeps the work type. */
    val label: String? = null,
)
```

In `ContactFields`, replace `val address: PostalAddress? = null,` with:

```kotlin
    /** Main address first. Empty for none. */
    val addresses: List<PostalAddress> = emptyList(),
```

In `dataRows`, replace the `fields.address?.let { … }` block with:

```kotlin
        fields.addresses.forEach { address ->
            row(StructuredPostal.CONTENT_ITEM_TYPE) {
                put(StructuredPostal.STREET, address.street)
                put(StructuredPostal.POSTCODE, address.postalCode)
                put(StructuredPostal.CITY, address.city)
                put(StructuredPostal.COUNTRY, address.country)
                if (address.label != null) {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                    put(StructuredPostal.LABEL, address.label)
                } else {
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
                }
            }
        }
```

- [ ] **Step 4: Which addresses an entry carries**

In `…/contacts/PhoneBookEntries.kt`, add the imports `io.github.amadeusb.callsheet.data.Addresses` and `io.github.amadeusb.callsheet.data.BusinessAddress`. Replace `forBusiness`, `entry`, `address` and `mapLink` with:

```kotlin
    /**
     * [addresses] are the business's. A person assigned to one of them carries
     * only that one; a person without an assignment, or assigned to a row that
     * is not here, and the company's own entry carry all, main address first.
     */
    fun forBusiness(business: Business, contacts: List<Contact>, addresses: List<BusinessAddress>): List<ContactFields> {
        val mainNumber = business.phone.clean()
        val all = Addresses.ordered(addresses)
        // Saved by hand under the same name, the imported person is already
        // there — as the one the user has worked on.
        val imported = business.contactName.clean()
            ?.takeIf { name -> contacts.none { samePerson(it.name, name) } }

        val entries = ArrayList<ContactFields>()
        if (mainNumber != null && (imported != null || contacts.isEmpty())) {
            entries += entry(
                business = business,
                sourceId = business.placeId,
                name = imported?.let { PersonName.of(it) },
                role = null,
                email = null,
                ownNote = null,
                ownNumbers = emptyList(),
                addresses = all,
                place = all.firstOrNull(),
            )
        }
        contacts.forEach { contact ->
            val assigned = Addresses.assigned(contact.addressId, addresses)
            entries += entry(
                business = business,
                sourceId = contact.id,
                name = PersonName.of(contact.name),
                role = contact.role,
                email = contact.email,
                ownNote = contact.note,
                ownNumbers = contact.numbers.toPhoneBookNumbers(),
                addresses = assigned?.let { listOf(it) } ?: all,
                place = assigned ?: all.firstOrNull(),
            )
        }
        return entries
    }

    private fun entry(
        business: Business,
        sourceId: String,
        name: PersonName?,
        role: String?,
        email: String?,
        ownNote: String?,
        ownNumbers: List<PhoneBookNumber>,
        addresses: List<BusinessAddress>,
        /** Where the map link of a hand-entered business leads. */
        place: BusinessAddress?,
    ) = ContactFields(
        sourceId = sourceId,
        name = name,
        organization = business.name,
        role = role.clean() ?: business.industry.clean(),
        email = email.clean() ?: business.email.clean(),
        note = note(business, ownNote),
        numbers = numbers(ownNumbers, business.phone.clean()),
        addresses = addresses.mapNotNull { postal(it) },
        websites = websites(business, place),
    )
```

```kotlin
    private fun postal(address: BusinessAddress): PostalAddress? {
        val street = address.street.clean()
        val postalCode = address.postalCode.clean()
        val city = address.city.clean()
        if (street == null && postalCode == null && city == null) return null
        return PostalAddress(street, postalCode, city, COUNTRY, label = address.label.clean())
    }

    private fun websites(business: Business, place: BusinessAddress?): List<PhoneBookWebsite> = listOfNotNull(
        business.website.clean()?.let { PhoneBookWebsite(it, WebsiteKind.WORK) },
        mapLink(business, place)?.let { PhoneBookWebsite(it, WebsiteKind.OTHER) },
    )

    /**
     * An imported business is found by its place id. A hand-entered one has
     * none, so the map searches for [place] — the person's address, else the
     * main address — and without one there is no link.
     */
    private fun mapLink(business: Business, place: BusinessAddress?): String? {
        if (!business.placeId.startsWith(MANUAL_PREFIX)) {
            return MAP_SEARCH + encode(business.name) + "&query_place_id=" + encode(business.placeId)
        }
        return place?.oneLine?.let { MAP_SEARCH + encode(it) }
    }
```

(The old `websites(business)` goes; `numbers`, `note`, `encode`, `clean`, `samePerson`, `collapseSpaces` stay.)

In `…/contacts/ContactStore.kt`, in `persistBusiness`, replace the `val entries = …` line with:

```kotlin
            val entries = PhoneBookEntries.forBusiness(
                business, repo.contacts(business.placeId), repo.addresses(business.placeId),
            )
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.PhoneBookEntriesTest" --tests "io.github.amadeusb.callsheet.PhoneBookRowsTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBook.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBookEntries.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactStore.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PhoneBookRowsTest.kt
git commit -m "Telefonbuch: jede Adresse als eigene Anschrift, zugeordnete Ansprechpartner nur mit ihrer"
```

---

### Task 9: App — addresses in the detail view, and the address screen

**Repository:** app.

**Files:**
- Create: `…/ui/Addresses.kt` (`AddressList`, `AddressScreen`)
- Modify: `…/ui/BusinessForm.kt` (`Suggestions` becomes `internal`)
- Modify: `…/ui/BusinessDetail.kt` (`BusinessDetailScreen`, `MasterData`, `ContactCard`, `geoUri`)
- Modify: `…/CallsheetViewModel.kt` (`Screen.AddressForm`, `State.detailAddresses`, `State.addressDrafts`, `back`, `openBusiness`, `loadDetail`, `showAddresses`, `updateAddressDrafts`, `saveAddresses`)
- Modify: `…/MainActivity.kt`
- Test: `test/GeoUriTest.kt`

**Interfaces:**
- Consumes: `Repository.addresses`, `Repository.saveAddresses` (Tasks 6, 7); `Addresses` (Task 3); `ContactStore.persistBusiness` (Task 8).
- Produces:
  - `internal fun geoUri(name: String, address: BusinessAddress): String?` (replaces `geoUri(business, address)`; `geoUri(address: String)` stays)
  - `@Composable fun AddressList(drafts: List<AddressDraft>, knownCities: List<String>, onChange: (List<AddressDraft>) -> Unit)` — used again in Task 10
  - `State.detailAddresses: List<BusinessAddress>` — used in Tasks 11 and 12
  - `CallsheetViewModel.showAddresses(placeId: String)`, `updateAddressDrafts(drafts: List<AddressDraft>)`, `saveAddresses(placeId: String)`

- [ ] **Step 1: Write the failing test**

Replace `test/GeoUriTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import android.net.Uri
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.ui.geoUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uri.encode is an Android call, hence Robolectric. Every address here is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoUriTest {

    private fun address(
        lat: Double? = null,
        lng: Double? = null,
        street: String? = "Musterstraße 39",
        postalCode: String? = "85055",
        city: String? = "Ingolstadt",
    ) = BusinessAddress(
        id = "main-P1", placeId = "P1", label = null,
        street = street, postalCode = postalCode, city = city,
        latitude = lat, longitude = lng, position = 0,
    )

    @Test
    fun `coordinates put the map on the point and label the pin`() {
        val uri = geoUri("Gartenbau Merten", address(48.7654321, 11.4234567))!!

        assertTrue(uri, uri.startsWith("geo:48.7654321,11.4234567?q="))
        assertTrue(uri, uri.contains("Gartenbau"))
    }

    @Test
    fun `without coordinates the address is searched instead`() {
        val uri = geoUri("Gartenbau Merten", address())!!

        assertEquals("geo:0,0?q=" + Uri.encode("Musterstraße 39, 85055 Ingolstadt"), uri)
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        // A latitude without a longitude would land the map on the equator.
        val uri = geoUri("Gartenbau Merten", address(lat = 48.7654321))!!

        assertTrue(uri, uri.startsWith("geo:0,0?q="))
    }

    @Test
    fun `no coordinates and no address means no link at all`() {
        assertNull(geoUri("Gartenbau Merten", address(street = null, postalCode = " ", city = null)))
    }

    @Test
    fun `the address form escapes what it is given`() {
        assertEquals("geo:0,0?q=Musterstra%C3%9Fe%2039", geoUri("Musterstraße 39"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.GeoUriTest"`
Expected: FAIL — compilation error, `geoUri` has no overload taking `(String, BusinessAddress)`.

- [ ] **Step 3: The map link takes an address**

In `…/ui/BusinessDetail.kt`, add the imports `io.github.amadeusb.callsheet.data.Addresses` and `io.github.amadeusb.callsheet.data.BusinessAddress`, and replace `geoUri(business: Business, address: String?)` and its KDoc with:

```kotlin
/**
 * The same, for one of a business's addresses.
 *
 * `geo:lat,lng?q=lat,lng(Name)` puts the map on the point itself and labels the
 * pin with [name]. The address form leaves the map application to geocode a
 * string, which lands on the street rather than the yard often enough to
 * matter when the yard is behind it.
 *
 * Falls back to the address when the row has no coordinates — hand-entered
 * addresses have none, nor has one whose street was changed by hand.
 */
internal fun geoUri(name: String, address: BusinessAddress): String? {
    val lat = address.latitude
    val lng = address.longitude
    if (lat != null && lng != null) {
        val label = Uri.encode("$lat,$lng($name)")
        return "geo:$lat,$lng?q=$label"
    }
    return address.oneLine?.let { geoUri(it) }
}
```

- [ ] **Step 4: Every address in the master data, the route by the person's address**

In `BusinessDetailScreen`, add the parameters `addresses: List<BusinessAddress>,` (after `appointments`) and `onEditAddresses: () -> Unit,` (after `onContact`). Change the `master-data` item to:

```kotlin
            item(key = "master-data") {
                MasterData(
                    business = business,
                    addresses = addresses,
                    onOpenUrl = onOpenUrl,
                    onDial = onDial,
                    onEditAddresses = onEditAddresses,
                )
            }
```

and add `addresses = addresses,` to the `ContactCard(…)` call.

In `MasterData`, add the parameters `addresses: List<BusinessAddress>,` and `onEditAddresses: () -> Unit,`, and replace the `Appointment.address(business.street, …)?.let { … }` block (with the comment above it) with:

```kotlin
        // Every address its own row, main address first. The line is the same
        // one that goes into a calendar event, so the two cannot drift apart.
        Addresses.ordered(addresses).forEach { address ->
            address.oneLine?.let { line ->
                DataRow(address.label?.trim()?.ifEmpty { null } ?: "Anschrift", line) {
                    geoUri(business.name, address)?.let(onOpenUrl)
                }
            }
        }
        TextButton(
            onClick = onEditAddresses,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(if (addresses.isEmpty()) "Adresse hinzufügen" else "Adressen bearbeiten")
        }
```

In `ContactCard`, add the parameter `addresses: List<BusinessAddress>,` after `business`, and replace the route block (from `val address = Appointment.address(…)` to the end of its `?.let { … }`) with:

```kotlin
            // A contact has no address of its own — this is one of the
            // business's: the one the person is assigned to, else the main
            // address. The wording has to say so.
            val place = Addresses.forContact(contact.addressId, addresses)
            val line = place?.oneLine
            place?.let { geoUri(business.name, it) }?.let { uri ->
                Text(
                    text = "Route zum Betrieb" + (line?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onOpenUrl(uri) }
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                )
            }
```

Update the KDoc of the `business` parameter of `ContactCard` to: `/** The business this contact belongs to. */`.

- [ ] **Step 5: The address list and screen**

In `…/ui/BusinessForm.kt`, change `private fun Suggestions(` to `internal fun Suggestions(`.

Create `…/ui/Addresses.kt`:

```kotlin
package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.AddressDraft
import io.github.amadeusb.callsheet.data.Addresses

/**
 * A business's addresses as rows of a form, the first one the main address.
 *
 * Holds no state: every change reports the whole new list upwards. Blank rows
 * are dropped when saving, not here — a row still being filled in must not
 * vanish under the thumb.
 */
@Composable
fun AddressList(
    drafts: List<AddressDraft>,
    knownCities: List<String>,
    onChange: (List<AddressDraft>) -> Unit,
) {
    fun replace(index: Int, row: AddressDraft) = onChange(drafts.toMutableList().also { it[index] = row })

    Column(modifier = Modifier.fillMaxWidth()) {
        drafts.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Text(
                text = if (index == 0) "Hauptadresse" else "Weitere Adresse",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Field(
                value = row.label,
                onValue = { replace(index, row.copy(label = it)) },
                label = "Bezeichnung",
                placeholder = "optional",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = Addresses.LABEL_SUGGESTIONS,
                onPick = { replace(index, row.copy(label = it)) },
            )
            Field(
                value = row.street,
                onValue = { replace(index, row.copy(street = it)) },
                label = "Straße und Hausnummer",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Field(
                value = row.postalCode,
                onValue = { replace(index, row.copy(postalCode = it)) },
                label = "PLZ",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            Field(
                value = row.city,
                onValue = { replace(index, row.copy(city = it)) },
                label = "Ort",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = knownCities,
                onPick = { replace(index, row.copy(city = it)) },
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                if (index > 0) {
                    TextButton(onClick = { onChange(Addresses.makeMain(drafts, index)) }) {
                        Text("Als Hauptadresse")
                    }
                }
                TextButton(onClick = { onChange(drafts.filterIndexed { i, _ -> i != index }) }) {
                    Text("Entfernen")
                }
            }
        }
        OutlinedButton(
            onClick = { onChange(drafts + AddressDraft()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Adresse hinzufügen")
        }
    }
}

/**
 * The addresses of an existing business — imported or entered by hand. Only
 * the addresses: the master data stays as the import or the entry form left it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressScreen(
    businessName: String,
    drafts: List<AddressDraft>,
    knownCities: List<String>,
    saving: Boolean,
    onChange: (List<AddressDraft>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adressen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                ) {
                    Button(
                        onClick = onSave,
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Speichern …", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Adressen speichern", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Adressen")
            Text(
                text = "Gehört zu $businessName. Die erste Adresse ist die Hauptadresse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AddressList(drafts = drafts, knownCities = knownCities, onChange = onChange)
            Spacer(Modifier.height(24.dp))
        }
    }
}
```

- [ ] **Step 6: The view model**

In `…/CallsheetViewModel.kt`, add the imports `io.github.amadeusb.callsheet.data.AddressDraft`, `io.github.amadeusb.callsheet.data.Addresses`, `io.github.amadeusb.callsheet.data.BusinessAddress` (and `kotlinx.coroutines.flow.update` if it is not imported yet).

In `sealed interface Screen`, after `ContactForm`:

```kotlin
    /** The addresses of a business, for editing. */
    data class AddressForm(val placeId: String) : Screen
```

In `State`, after `detailAppointments`:

```kotlin
    /** The business's addresses, main address first. */
    val detailAddresses: List<BusinessAddress> = emptyList(),
```

and after `contactError`:

```kotlin
    /** The address screen's rows while it is open. */
    val addressDrafts: List<AddressDraft> = emptyList(),
```

In `back()`, add `is Screen.AddressForm -> Unit` to the `when`.

In `openBusiness`, add to the `copy(…)`:

```kotlin
            detailAddresses = if (switching) emptyList() else _state.value.detailAddresses,
```

In `loadDetail`, add to the first `copy(…)`:

```kotlin
                detailAddresses = repo.addresses(placeId),
```

After `deleteContact`, add:

```kotlin
    // --- Addresses ----------------------------------------------------------

    /** Opens a business's addresses for editing; with none yet, one empty row. */
    fun showAddresses(placeId: String) {
        viewModelScope.launch {
            val drafts = Addresses.drafts(repo.addresses(placeId)).ifEmpty { listOf(AddressDraft()) }
            val screen = Screen.AddressForm(placeId)
            val history = historyFor(screen)
            _state.update { it.copy(screen = screen, history = history, addressDrafts = drafts) }
        }
    }

    fun updateAddressDrafts(drafts: List<AddressDraft>) {
        _state.update { it.copy(addressDrafts = drafts) }
    }

    /** Saves the addresses and returns to the record. */
    fun saveAddresses(placeId: String) {
        if (_state.value.saving) return
        val drafts = _state.value.addressDrafts
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            repo.saveAddresses(placeId, drafts)
            _state.update { it.copy(saving = false, allCities = repo.cities()) }
            // Every entry of the business carries addresses, and a removed one
            // may have taken a person's assignment with it.
            repo.business(placeId)?.let { store.persistBusiness(it) }
            back()
            loadDetail(placeId)
        }
    }
```

- [ ] **Step 7: Wire it up**

In `…/MainActivity.kt`, add the import `io.github.amadeusb.callsheet.ui.AddressScreen`. In the `BusinessDetailScreen(…)` call, add:

```kotlin
                    addresses = state.detailAddresses,
```

and

```kotlin
                    onEditAddresses = { vm.showAddresses(business.placeId) },
```

After the `is Screen.ContactForm -> …` branch, add:

```kotlin
        is Screen.AddressForm -> AddressScreen(
            businessName = state.detail?.name ?: "diesem Betrieb",
            drafts = state.addressDrafts,
            knownCities = state.allCities,
            saving = state.saving,
            onChange = vm::updateAddressDrafts,
            onSave = { vm.saveAddresses(screen.placeId) },
            onCancel = { vm.back() },
        )
```

- [ ] **Step 8: Run the tests and the build**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.GeoUriTest"`
Expected: PASS.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/Addresses.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessForm.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt \
        app/src/test/java/io/github/amadeusb/callsheet/GeoUriTest.kt
git commit -m "Detailansicht zeigt jede Adresse, eigener Screen zum Bearbeiten, Route nach Standort"
```

---

### Task 10: App — the business form takes several addresses

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt` (`BusinessDraft`)
- Modify: `…/data/Repository.kt` (`create`)
- Modify: `…/ui/BusinessForm.kt`
- Test: `test/BusinessFormTest.kt`, `test/DirtyTest.kt`

**Interfaces:**
- Consumes: `AddressList` (Task 9), `Addresses.searchText` (Task 3).
- Produces: `BusinessDraft.addresses: List<AddressDraft> = listOf(AddressDraft())` replacing `street`, `postalCode`, `city`.

- [ ] **Step 1: Write the failing test**

In `test/BusinessFormTest.kt`, add the import `io.github.amadeusb.callsheet.data.AddressDraft`. Replace the test `a hand-entered address becomes the business's first address row` (from Task 6) with:

```kotlin
    @Test
    fun `a hand-entered business keeps every address in the order entered, blank rows left out`() = runTest {
        val id = repo.create(
            BusinessDraft(
                name = "Dachdecker Erfunden",
                addresses = listOf(
                    AddressDraft(street = "Ziegelgasse 2", postalCode = "85053", city = "Ingolstadt"),
                    AddressDraft(label = "Lager"),
                    AddressDraft(label = "Filiale", city = "Hafenstadt"),
                ),
            )
        ).getOrThrow()

        val addresses = repo.addresses(id)
        assertEquals(listOf("Ingolstadt", "Hafenstadt"), addresses.map { it.city })
        assertEquals(listOf(0, 1), addresses.map { it.position })
        assertEquals("Filiale", addresses[1].label)
        assertEquals("Ingolstadt", repo.business(id)!!.city)
        assertEquals(
            listOf(id),
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = "hafenstadt")).map { it.placeId },
        )
    }
```

Find every other draft that still uses the old fields:

Run: `grep -rn 'BusinessDraft(' -A4 app/src/test | grep -n 'city =\|street =\|postalCode ='`
Expected: `BusinessFormTest.kt` (`city = "Ingolstadt"`, `city = "Königsmoos"`, `city = "Kösching"`) and `DirtyTest.kt` (`city = "Ingolstadt"`).

Change each `city = "<Ort>"` there to `addresses = listOf(AddressDraft(city = "<Ort>"))`, keeping the value. In `DirtyTest.kt` write it fully qualified, as the draft already is: `addresses = listOf(io.github.amadeusb.callsheet.data.AddressDraft(city = "Ingolstadt"))`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.BusinessFormTest" --tests "io.github.amadeusb.callsheet.DirtyTest"`
Expected: FAIL — compilation error `No parameter with name 'addresses'`.

- [ ] **Step 3: The draft**

In `…/data/Models.kt`, in `BusinessDraft`, replace `street`, `postalCode` and `city` with:

```kotlin
    /** The first row is the main address. A new business starts with one empty row. */
    val addresses: List<AddressDraft> = listOf(AddressDraft()),
```

- [ ] **Step 4: Creating writes every address**

In `…/data/Repository.kt`, `create`: remove the three lines `val street = …`, `val postalCode = …`, `val city = …`, and add in their place:

```kotlin
        val addressRows = new.addresses.filterNot { it.isBlank }
```

Change the search text line to:

```kotlin
            put("search_text", Addresses.searchText(name, addressRows.map { it.city }))
```

Replace the `if (street != null || postalCode != null || city != null) { … }` block inside the transaction with:

```kotlin
            addressRows.forEachIndexed { index, row ->
                db.insert(
                    "business_addresses", null,
                    ContentValues().apply {
                        put("id", java.util.UUID.randomUUID().toString())
                        put("place_id", placeId)
                        put("label", row.label.trim().ifEmpty { null })
                        put("street", row.street.trim().ifEmpty { null })
                        put("postal_code", row.postalCode.trim().ifEmpty { null })
                        put("city", row.city.trim().ifEmpty { null })
                        put("position", index)
                        put("updated_at", now)
                        put("dirty", 1)
                    },
                )
            }
```

- [ ] **Step 5: The form**

In `…/ui/BusinessForm.kt`, delete the `Field`s for „Ort", „Straße und Hausnummer" and „PLZ" and the `Suggestions` for `knownCities` between them. In their place, directly after the industry `Suggestions`, add:

```kotlin
            // ---- Addresses --------------------------------------------------
            Section("Adressen")

            AddressList(
                drafts = draft.addresses,
                knownCities = knownCities,
                onChange = { onChange(draft.copy(addresses = it)) },
            )
```

- [ ] **Step 6: Run the tests and the build**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.BusinessFormTest" --tests "io.github.amadeusb.callsheet.DirtyTest"`
Expected: PASS.

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt \
        app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessForm.kt \
        app/src/test/java/io/github/amadeusb/callsheet/BusinessFormTest.kt \
        app/src/test/java/io/github/amadeusb/callsheet/DirtyTest.kt
git commit -m "Neuer Betrieb: beliebig viele Adressen statt einer"
```

---

### Task 11: App — „Standort" in the contact form

**Repository:** app.

**Files:**
- Modify: `…/ui/ContactScreen.kt`
- Modify: `…/CallsheetViewModel.kt` (`showContact`)
- Modify: `…/MainActivity.kt`

**Interfaces:**
- Consumes: `State.detailAddresses` (Task 9), `ContactDraft.addressId` saved by `Repository.saveContact` (Task 7), `Addresses.assigned/ordered/name` (Task 3).
- Produces: `ContactScreen(…, addresses: List<BusinessAddress>, …)`.

The rules behind this are tested in `AddressesTest` and `RepositoryTest` (`a contact's address is saved and read back`); this task is checked by the build and on the phone (Task 16).

- [ ] **Step 1: The choice**

In `…/ui/ContactScreen.kt`, add the imports `io.github.amadeusb.callsheet.data.Addresses` and `io.github.amadeusb.callsheet.data.BusinessAddress`. Add the parameter `addresses: List<BusinessAddress>,` after `businessName`. After `roleSuggestions { onChange(draft.copy(role = it)) }`, add:

```kotlin
            // Only with a choice to make: with one address, everybody is there.
            if (addresses.size >= 2) {
                Section("Standort")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        // A row that is gone reads as none.
                        selected = Addresses.assigned(draft.addressId, addresses) == null,
                        onClick = { onChange(draft.copy(addressId = null)) },
                        label = { Text("Keiner") },
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                    Addresses.ordered(addresses).forEach { address ->
                        FilterChip(
                            selected = draft.addressId == address.id,
                            onClick = { onChange(draft.copy(addressId = address.id)) },
                            label = { Text(Addresses.name(address)) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
            }
```

- [ ] **Step 2: The draft carries the assignment**

In `…/CallsheetViewModel.kt`, `showContact`, add to the `ContactDraft(…)` built from `existing`:

```kotlin
                addressId = existing.addressId,
```

- [ ] **Step 3: Wire it up**

In `…/MainActivity.kt`, add to the `ContactScreen(…)` call:

```kotlin
            addresses = state.detailAddresses,
```

- [ ] **Step 4: Build and tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/ui/ContactScreen.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt
git commit -m "Ansprechpartner: Standort wählen, sobald ein Betrieb zwei Adressen hat"
```

---

### Task 12: App — the visit's place follows the person

**Repository:** app.

**Files:**
- Modify: `…/CallsheetViewModel.kt` (`AppointmentDraft.locationEdited`, `openSheet`, `updateAppointmentDraft`, new `withPlace`)
- Modify: `…/calling/Appointment.kt` (new `placeAfterContactChange`)
- Modify: `…/ui/AppointmentSheet.kt`
- Modify: `…/MainActivity.kt`
- Test: `test/AppointmentTest.kt`

**Interfaces:**
- Consumes: `Appointment.presetLocation` (Task 3, tested in `AppointmentTest`); `Repository.contacts`, `Repository.addresses` (Tasks 6, 7); `State.detailContacts`, `State.detailAddresses` (Task 9).
- Produces: `AppointmentDraft.locationEdited: Boolean = false`; `Appointment.placeAfterContactChange(previous: AppointmentDraft, incoming: AppointmentDraft, presetFor: (contactId: String?) -> String?): String` — the exact signature from „Interface used by the visits plan" in the Global Constraints; `AppointmentSheet(…, addresses: List<BusinessAddress>, …)`.

- [ ] **Step 1: Write the failing tests**

In `test/AppointmentTest.kt` (package `io.github.amadeusb.callsheet`, so `AppointmentDraft` needs no import; `AppointmentKind` is imported already), after the `// --- place of a visit ---` tests from Task 3, add:

```kotlin
    // --- the place after the contact person changed ---------------------------

    private val headLine = "Musterweg 1, 85000 Musterstadt"
    private val branchLine = "Hafenstraße 5, 85001 Hafenstadt"

    /** k1 sits at the main address, k2 at the branch; nobody means the main address. */
    private val presets: (String?) -> String? = { contactId -> if (contactId == "k2") branchLine else headLine }

    private fun sheet(contactId: String?, location: String, edited: Boolean = false, kind: AppointmentKind = AppointmentKind.VISIT) =
        AppointmentDraft(
            placeId = "P1", startIso = "2026-09-17T10:00:00+02:00", minutes = 60,
            location = location, kind = kind, contactId = contactId, locationEdited = edited,
        )

    @Test
    fun `a place not chosen by hand moves along to the new person's address`() {
        assertEquals(branchLine, Appointment.placeAfterContactChange(sheet("k1", headLine), sheet("k2", headLine), presets))
        assertEquals(headLine, Appointment.placeAfterContactChange(sheet("k2", branchLine), sheet(null, branchLine), presets))
    }

    @Test
    fun `a place chosen by hand stays when the person changes`() {
        // Typed: differs from the previous person's address.
        assertEquals("Baustelle Nord", Appointment.placeAfterContactChange(sheet("k1", "Baustelle Nord", edited = true), sheet("k2", "Baustelle Nord", edited = true), presets))
        // Picked by chip, even the chip of the previous person's own address.
        assertEquals(headLine, Appointment.placeAfterContactChange(sheet("k1", headLine, edited = true), sheet("k2", headLine, edited = true), presets))
    }

    @Test
    fun `a place that is not the previous person's address stays, flag or not`() {
        assertEquals("Baustelle Nord", Appointment.placeAfterContactChange(sheet("k1", "Baustelle Nord"), sheet("k2", "Baustelle Nord"), presets))
    }

    @Test
    fun `without a change of person, or without a preset, the incoming place is kept`() {
        assertEquals("Musterweg 1a", Appointment.placeAfterContactChange(sheet("k1", headLine), sheet("k1", "Musterweg 1a"), presets))
        assertEquals("", Appointment.placeAfterContactChange(sheet("k1", ""), sheet("k2", ""), { null }))
    }

    @Test
    fun `a callback's place never moves`() {
        assertEquals(
            "",
            Appointment.placeAfterContactChange(
                sheet("k1", "", kind = AppointmentKind.CALLBACK),
                sheet("k2", "", kind = AppointmentKind.CALLBACK),
                presets,
            ),
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: FAIL — compilation errors `No parameter with name 'locationEdited'` and `Unresolved reference: placeAfterContactChange`.

- [ ] **Step 3: The draft knows whether the place was chosen, and the rule**

In `…/CallsheetViewModel.kt`, in `data class AppointmentDraft`, directly after `val location: String,`:

```kotlin
    /**
     * The place was chosen in this sheet — typed, or picked by chip. Until then
     * a new contact person moves it along to their address
     * (Appointment.placeAfterContactChange).
     */
    val locationEdited: Boolean = false,
```

In `…/calling/Appointment.kt`, add the import `io.github.amadeusb.callsheet.AppointmentDraft`, and after `presetLocation` add:

```kotlin
    /**
     * The place for [incoming] after the sheet changed from [previous]. It
     * follows the contact person only while it was not chosen by hand: when the
     * person changed, the place did not change in the same update, and the
     * previous place was still the previous person's preset and not marked as
     * chosen ([AppointmentDraft.locationEdited] — a chip counts as chosen, even
     * the chip of that very address). Then it is the new person's preset, or,
     * without one, stays as it was. A callback has no place and never moves.
     *
     * [presetFor] gives a person's preset place, null for none; a null id is no
     * person. The visits plan builds on this signature — keep it.
     */
    fun placeAfterContactChange(
        previous: AppointmentDraft,
        incoming: AppointmentDraft,
        presetFor: (contactId: String?) -> String?,
    ): String {
        if (incoming.kind == AppointmentKind.CALLBACK) return incoming.location
        if (incoming.contactId == previous.contactId) return incoming.location
        if (incoming.location != previous.location) return incoming.location
        val handEdited = previous.locationEdited || previous.location != presetFor(previous.contactId).orEmpty()
        if (handEdited) return incoming.location
        return presetFor(incoming.contactId) ?: incoming.location
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.amadeusb.callsheet.AppointmentTest"`
Expected: PASS.

- [ ] **Step 5: The preset, from the same lists the sheet shows**

The sheet is only opened from the detail view (`MainActivity`: `onCallback`/`onAppointment` of `BusinessDetailScreen`), and it shows `state.detailContacts` and `state.detailAddresses`. `openSheet` reads both from the repository and writes them into the state in the same update that sets the draft; `withPlace` (Step 6) then reads exactly those lists. Preset, chips and moving along never see different rows.

In `openSheet`, replace the `val location = when { … }` block (the six lines from `val location = when {` down to its closing `}`, whose `else` branch calls `Appointment.address(business.street, business.postalCode, business.city).orEmpty()`) with:

```kotlin
            val contacts = repo.contacts(placeId)
            val addresses = repo.addresses(placeId)
            val preset = Appointment.presetLocation(existing?.contactId, contacts, addresses)
            val location = when {
                existing != null -> existing.location.orEmpty()
                // A phone call has no place.
                sheetKind == AppointmentKind.CALLBACK -> ""
                else -> preset
            }
```

In the `_state.update { it.copy(appointmentDraft = AppointmentDraft(…)) }` there, add after `location = location,`:

```kotlin
                        // A stored place other than the person's address was chosen before.
                        locationEdited = existing != null && location != preset,
```

and, after the closing `)` of `AppointmentDraft(…)`, inside the same `it.copy(…)`:

```kotlin
                    // The lists the preset was taken from: the sheet's chips and
                    // withPlace read these, so all three agree.
                    detailContacts = if (it.detail?.placeId == placeId) contacts else it.detailContacts,
                    detailAddresses = if (it.detail?.placeId == placeId) addresses else it.detailAddresses,
```

Update the KDoc of `openAppointment`: replace „the business's address" with „the address of its contact person, else the business's main address".

- [ ] **Step 6: Moving along**

In `fun updateAppointmentDraft`, replace exactly its first two lines

```kotlin
    fun updateAppointmentDraft(draft: AppointmentDraft) {
        val previous = _state.value.appointmentDraft
```

with these three — `val previous` is declared once, here, and nowhere else in the function:

```kotlin
    fun updateAppointmentDraft(incoming: AppointmentDraft) {
        val previous = _state.value.appointmentDraft
        val draft = withPlace(previous, incoming)
```

The rest of the function stays as it is, reading `draft` and `previous` (from `val previousDay = …` on). Add after the function:

```kotlin
    /**
     * [incoming] with its place as the sheet shows it. A place changed in this
     * update counts as chosen by hand; a new contact person moves a place not
     * chosen along to their address (Appointment.placeAfterContactChange).
     * The presets come from the same lists openSheet took its preset from.
     */
    private fun withPlace(previous: AppointmentDraft?, incoming: AppointmentDraft): AppointmentDraft {
        if (previous == null) return incoming
        val state = _state.value
        val location = Appointment.placeAfterContactChange(previous, incoming) { contactId ->
            Appointment.presetLocation(contactId, state.detailContacts, state.detailAddresses).ifEmpty { null }
        }
        return incoming.copy(
            location = location,
            locationEdited = incoming.locationEdited || incoming.location != previous.location,
        )
    }
```

- [ ] **Step 7: The chips**

In `…/ui/AppointmentSheet.kt`, add the imports `io.github.amadeusb.callsheet.data.Addresses` and `io.github.amadeusb.callsheet.data.BusinessAddress`, the parameter `addresses: List<BusinessAddress>,` after `contacts`, and inside `if (!callback) { … }`, after the `OutlinedTextField` for the place:

```kotlin
                    // Every address of the business, one tap each. Free text stays possible.
                    val places = Addresses.ordered(addresses).mapNotNull { address -> address.oneLine?.let { address to it } }
                    if (places.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            places.forEach { (address, line) ->
                                FilterChip(
                                    selected = draft.location.trim() == line,
                                    // Chosen by hand even when it is the preset: it stays when the person changes.
                                    onClick = { onDraft(draft.copy(location = line, locationEdited = true)) },
                                    label = { Text(Addresses.name(address)) },
                                )
                            }
                        }
                    }
```

Update the sheet's KDoc sentence about the place: `A callback has no place: the location field is left out, and the durations are a phone call's. A visit's place starts at the contact person's address and offers every address of the business as a chip.`

- [ ] **Step 8: Wire it up**

In `…/MainActivity.kt`, add to the `AppointmentSheet(…)` call, after `contacts = state.detailContacts,`:

```kotlin
                        addresses = state.detailAddresses,
```

- [ ] **Step 9: Build and tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/calling/Appointment.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/AppointmentSheet.kt \
        app/src/main/java/io/github/amadeusb/callsheet/MainActivity.kt \
        app/src/test/java/io/github/amadeusb/callsheet/AppointmentTest.kt
git commit -m "Termin vor Ort beginnt an der Adresse des Ansprechpartners, Adressen als Chips"
```

---

### Task 13: App — the business's own address columns are read by nothing

**Repository:** app.

**Files:**
- Modify: `…/data/Models.kt` (`Business`)
- Modify: `…/data/Repository.kt` (`fromCursor`)
- Test: `test/PhoneBookEntriesTest.kt`

**Interfaces:**
- Produces: `Business` without `street`, `postalCode`, `latitude`, `longitude`. `Business.city` is the main address's city. `ImportedBusiness` keeps all of them — the importer still reads one address.

- [ ] **Step 1: Nothing reads them any more**

Run: `grep -rnE 'business\.(street|postalCode|latitude|longitude)' app/src/main`
Expected: no output. If there is any, it is a spot Tasks 8, 9 or 12 missed — switch it to the addresses before going on.

- [ ] **Step 2: Remove the fields**

In `…/data/Models.kt`, `Business`: delete `val street: String?,`, `val postalCode: String?,`, and the block

```kotlin
    /** From the import. Nothing reads them yet. */
    val latitude: Double? = null,
    val longitude: Double? = null,
```

and give `city` a KDoc:

```kotlin
    /** The city of the main address, for the lists. The addresses themselves: Repository.addresses. */
    val city: String?,
```

In `…/data/Repository.kt`, `fromCursor`: delete the lines `street = …`, `postalCode = …`, `latitude = …`, `longitude = …`.

In `test/PhoneBookEntriesTest.kt`, fixture `business(…)`: delete `street = null, postalCode = null,` from the `Business(…)` call.

- [ ] **Step 3: Build and tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. A compilation error names a remaining reader — switch it to the addresses, never add the field back.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/data/Models.kt \
        app/src/main/java/io/github/amadeusb/callsheet/data/Repository.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt
git commit -m "Business ohne eigene Anschrift: gelesen wird nur noch business_addresses"
```

---

### Task 14: App — documentation and changelog

**Repository:** app.

**Files:**
- Modify: `docs/data-model.md`, `docs/usage.md`, `CHANGELOG.md`

- [ ] **Step 1: `docs/data-model.md`**

In the `businesses` master data block, replace the lines for `street, postal_code, city`, `latitude` and `longitude` with:

```
street, postal_code, city  TEXT  -- no longer used since schema 7, not emptied; see `business_addresses`
latitude        REAL            -- no longer used since schema 7
longitude       REAL            -- no longer used since schema 7
```

Replace the `search_text` paragraph with:

```markdown
There is also a `search_text` column: the name and the cities of all the
business's addresses, lower-cased. SQLite only lower-cases ASCII on its own;
without it, searching for „müller" would not find „Müller". It is recomputed
wherever an address is saved, imported, or arrives from the server.
```

After the `appointments` section (before `### \`status\` — allowed values`), add:

````markdown
### `business_addresses`

Every address of a business — head office, branch, yard. Synchronised like
`contact_emails`: a row per address, deleted through tombstones.

```
id           TEXT PRIMARY KEY  -- UUID; carried over and imported: 'main-<place_id>'
place_id     TEXT NOT NULL
label        TEXT              -- „Hauptsitz", „Filiale" …, optional
street       TEXT
postal_code  TEXT
city         TEXT
latitude     REAL              -- from the import; cleared when the address is changed by hand
longitude    REAL
position     INTEGER           -- 0 is the main address; NULL reads as last
updated_at   TEXT NOT NULL
dirty        INTEGER NOT NULL DEFAULT 0
```

Schema 7 carried each business's address over into `main-<place_id>` — the same
id and condition the server's migration 009 uses, with the business's
`updated_at`, marked dirty. The business's own address columns stay as they
were and are no longer written or read.

The import keeps `main-<place_id>` up to date (street, postal code, city,
coordinates) and leaves its label, its position and every other address alone.
A `main-` address removed by hand is recorded in the local-only table
`removed_main_addresses (place_id)` — here or by a tombstone from the server —
and the import does not bring it back. `deletions` cannot answer that: it is
the outgoing queue.

The list's city is the main address's; the city filter matches any address.
````

In the `contacts` block, after `note             TEXT`, add:

```
address_id       TEXT               -- one of the business's addresses, or null
```

and after the `contact_numbers` block's closing fence, before „The imported `businesses.contact_name` …", add:

```markdown
`address_id` names the address a person sits at. A row that is not there reads
as no assignment and is not cleared on reading — it may still arrive. Removing
an address on this device clears the assignment of its contacts.
```

In `### \`deletions\``, change the sentence to: „Contacts, their numbers and emails, appointments and business addresses are the only rows the app ever deletes."

- [ ] **Step 2: `docs/usage.md`**

Replace the paragraph beginning „The location is prefilled from the business's address" with:

```markdown
The location is prefilled with the address of the chosen contact person — the
one they are assigned to, else the business's main address — and moves along
when you pick another person, until you type a place or tap one of the address
chips below the field. It is what the calendar entry carries, so it is what the
navigation reads.
```

Change „Tapping the address, in the appointment or in the master data," to „Tapping an address, in the appointment or in the master data,".

Before `## Contacts`, add:

```markdown
## Addresses

A business can have several addresses — head office, branch, yard. **Adressen
bearbeiten** in the detail view opens them; the first is the main address, and
**Als Hauptadresse** moves another one to the top. A label is optional, with
„Hauptsitz", „Filiale", „Lager" and „Baustelle" one tap away. The import keeps
the imported address up to date and leaves the others alone; an imported address
removed by hand stays removed.

Once a business has two addresses, a contact person can be assigned to one under
**Standort**. Their phone book entry then carries only that address, their route
leads there, and a visit with them starts there.
```

In `## Contacts`, change „company name, the main number („Hauptadresse“), address, website, a map link and a note." to „company name, the main number („Hauptadresse“), the addresses — only their own for a person assigned to one, each under its label —, website, a map link and a note."

- [ ] **Step 3: `CHANGELOG.md`**

Run: `git tag --list 'v1.5.0'`

If there is no output, 1.5.0 is not released yet: add the bullets below at the end of the `## 1.5.0` section. Otherwise add a new section `## 1.6.0` above `## 1.5.0` holding them.

```markdown
- **Several addresses per business.** Head office, branch, yard: **Adressen
  bearbeiten** in the detail view, each with an optional label; the first is the
  main address. The form for a new business takes several too.
- **A contact person can sit at one of them** (**Standort**). Their phone book
  entry carries only that address, the route on their card leads there, and a
  visit with them starts there. The appointment sheet offers every address as a
  chip.
- The city filter and the search find a business by any of its addresses.
- A re-import keeps the imported address up to date and leaves the others alone.
- **Update the sync server first, then every phone.** The first sync after the
  update fetches everything once. A phone still on an older version keeps the
  address it had and does not see addresses added elsewhere.
```

- [ ] **Step 4: Commit**

```bash
git add docs/data-model.md docs/usage.md CHANGELOG.md
git commit -m "Doku und CHANGELOG: mehrere Adressen pro Firma"
```

---

### Task 15: Server — deploy

**Repository:** server, and the production host. **Outward-facing: ask the user before Step 1's push and before Step 4, and wait for a yes each time.**

Every remote command is one non-interactive `ssh <server> '…'` call. Never open an interactive shell on the host.

- [ ] **Step 1: Push**

Run: `node --test` — all pass. Then ask the user, and on a yes: `git push origin main`.

- [ ] **Step 2: See what the host will pull**

```bash
ssh <server> 'cd <deploy-dir>/repo && git fetch && git diff HEAD origin/main --stat'
```

Expected: `db/migrations/009-business-addresses.sql`, `src/receive.js`, tests, README, the spec pointer. If migration `008-callbacks.sql` or anything of the visits work is in the diff too, stop and ask the user — it has not been deployed yet and may need its own steps.

- [ ] **Step 3: Count before, and back up**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT COUNT(*) AS with_address FROM businesses WHERE COALESCE(street, \x27\x27) <> \x27\x27 OR COALESCE(postal_code, \x27\x27) <> \x27\x27 OR COALESCE(city, \x27\x27) <> \x27\x27\").get());
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

- [ ] **Step 5: Check the migration on the live file**

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=commonjs -e "
  const { DatabaseSync } = require(\"node:sqlite\");
  const db = new DatabaseSync(\"/data/callsheet.db\");
  console.log(db.prepare(\"SELECT filename FROM schema_migrations ORDER BY filename DESC LIMIT 1\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS addresses FROM business_addresses\").get());
  console.log(db.prepare(\"SELECT COUNT(*) AS not_main FROM business_addresses WHERE id NOT LIKE \x27main-%\x27\").get());
  console.log(db.prepare(\"SELECT value AS counter FROM sync_counter\").get());
"'
```

Expected:
- `009-business-addresses.sql`
- `addresses` equals `with_address` from Step 3, and `not_main` is 0 — more only if a phone on the new app synced in between
- `counter` equals Step 3's `counter` plus `addresses`, or more if a phone synced in between

- [ ] **Step 6: From outside**

```bash
curl -si https://callsheet.tm-services.de/health | head -1
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://callsheet.tm-services.de/sync
```

Expected: `HTTP/2 200`, then `401`.

---

### Task 16: App — on the phone, then release

**Repository:** app. **The device test needs the user's phone; the release is outward-facing: ask before Step 4.**

- [ ] **Step 1: Everything green**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. `git status --porcelain` in both repos shows nothing.

Before any phone gets the new app, check that production already synchronises the table. The request is read-only: an empty payload, and a `since` beyond every sequence number, so nothing is written and no rows come back. It runs inside the container, which holds the token — the token never leaves the host.

```bash
ssh <server> 'docker compose -f <deploy-dir>/repo/docker-compose.yml -f <deploy-dir>/repo/docker-compose.prod.yml exec -T callsheet node --input-type=module -e "
  const port = process.env.CALLSHEET_PORT ?? 8080;
  const response = await fetch(\"http://127.0.0.1:\" + port + \"/sync\", {
    method: \"POST\",
    headers: { authorization: \"Bearer \" + process.env.CALLSHEET_TOKEN, \"content-type\": \"application/json\" },
    body: JSON.stringify({ since: Number.MAX_SAFE_INTEGER }),
  });
  const body = await response.json();
  console.log(response.status, body.tables.includes(\"business_addresses\"), JSON.stringify(body.tables));
"'
```

Expected: `200 true ["businesses","calls","contacts","contact_numbers","contact_emails","appointments","business_addresses"]`. Anything else — `false`, another status, an error — means Task 15 is not done on production: stop, do not install or release, and ask the user.

- [ ] **Step 2: Install on a connected phone**

Run: `~/android-sdk/platform-tools/adb devices -l`
Expected: one device. If none: ask the user to connect the phone with USB debugging on, and wait.

Run: `./gradlew installDebug`
Expected: `Installed on 1 device`.

- [ ] **Step 3: Walk through it with the user**

Ask the user to check, on a phone with the phone book, the calendar and sync switched on, and report back:

1. After the update, a business's address shows as before under „Anschrift". **Adressen bearbeiten** → **Adresse hinzufügen**: label „Filiale", another street and city → **Adressen speichern**. Both rows show; tapping each opens the map at that place.
2. Open a contact person of that business: **Standort** offers „Keiner", the main address and „Filiale". Choose „Filiale", save. The card's route reads „Route zum Betrieb · <branch address>".
3. In the Infomaniak address book (after DAVx5 has synced): the assigned person shows only the branch address; another person of the business shows both. **Note whether the label „Filiale" appears** — the spec asks for it to be checked by hand.
4. **Termin anlegen** with the assigned person: the place is the branch. Switch to the other person: the place follows to the main address. Type a place, switch the person again: the typed place stays. Tap the „Filiale" chip: the place is the branch.
5. Re-import the business file: the main address is unchanged or updated, the branch is still there.
6. The city filter with the branch's city lists the business; the work list row shows the main address's city.

Anything that does not match: stop, find the cause (superpowers:systematic-debugging), fix it test-first, and repeat this step.

- [ ] **Step 4: Release**

Only after Task 15 is done and the user says yes. Ask the user which version to release — `1.5.0` if `git tag --list v1.5.0` is empty (the callbacks work has not been released yet and goes out with this), otherwise `1.6.0` — and run `tools/release.sh minor` accordingly.
Expected: the script builds, tests, tags and publishes the version with its CHANGELOG section as release notes.
