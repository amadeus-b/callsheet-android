# Package 1 — fixes after the phone test of 1.5.0

Three changes the phone test of 1.5.0 asked for. They are independent of each
other and ship together.

1. A phone number may stand at more than one business.
2. The master data of an existing business can be edited, and a hand edit wins
   over a later import.
3. The read-back never deletes a visit.

## 1. The same number at several businesses

### The problem

`Repository.create` refuses a business whose number another business already
holds („Diese Nummer steht schon bei …"). In the field, one number often serves
several businesses: a family firm with two trades, a shared office, a
switchboard. The only way around the refusal was to leave the number out.

### Decision

- The check goes. A hand-entered business may carry a number another business
  holds, in any notation.
- The completeness check stays: an incomplete number is still refused
  („Die Telefonnummer ist unvollständig. …").
- The phone book then holds the number in several entries, one per business.
  That is accepted.
- The same holds for editing master data (section 2): no duplicate check there
  either.

## 2. Editing the master data of a business

### The problem

The business form only creates businesses. A wrong name, a changed number, a
missing email address on an imported or hand-entered business cannot be put
right in the app. And whatever could be put right would be undone by the next
import: `Repository.import` rewrites every imported master data column that
differs from the file.

### Decisions

- **„Stammdaten bearbeiten"** in the detail view opens the existing business
  form in an editing mode.
- **Editable:** name, phone, industry, website, email, and the contact name
  (`contact_name`, the text field „Ansprechpartner"). Addresses keep their own
  screen („Adressen bearbeiten"); note and status stay where they are. The
  editing mode shows neither the address list nor „Herkunft und Notiz".
- **Validation as when creating:** the name is required; the number is empty or
  complete. No duplicate check (section 1).
- **Saving** writes only what the user changed in the form: compared with the
  values as the form opened, not with what is stored now. A column another
  device changed while the form was open stays as that device left it and is
  not taken for a hand edit. With a change:
  - the changed columns, `updated_at` new, `dirty = 1`;
  - `search_text` recomputed — name and the cities of all addresses
    (`Addresses.searchText`, through `AddressRows.refreshSearchText`);
  - `is_target` recomputed with `TargetRule.isTarget(industry, phone, closed)`,
    only when phone or industry changed;
  - the business's phone book entries rewritten, through
    `ContactStore.persistBusiness` — the path a saved contact or saved addresses
    take today.
- Values are compared after trimming, empty as null, the phone number
  normalised: opening the form and saving it unchanged writes nothing.

### A hand edit wins over the import

A new synchronised column on `businesses`:

```
edited_fields  TEXT  -- JSON array of column names changed by hand, e.g. ["email","phone"]; nullable
```

- **What goes in:** the name of every editable column whose value changed in
  that save. Clearing a field counts. The list only grows: a later save adds to
  it and never removes a name. It is written sorted, so two devices holding the
  same set hold the same text. No change, no list: `NULL`.
- **Column names**, exactly: `name`, `phone`, `industry`, `website`, `email`,
  `contact_name`.
- **The import** leaves every column named in the list as it is stored. The
  other imported master data is compared and written as today; a business whose
  remaining columns are identical is not written at all — the existing
  „identical → nothing written" rule, applied after the edited columns are left
  out.
- **`is_target` on import** follows the stored values of an edited `phone` or
  `industry` and the imported values of the rest, so a number cleared by hand
  keeps the business out of the target set.
- Names in the list the app does not know are kept and read as edited too: a
  later version may make more columns editable, and this version's import must
  not undo those edits.
- Hand-entered businesses (`manual:` prefix) get the list too. No import reaches
  them, so it changes nothing there.

### Synchronisation of `edited_fields`

The column travels with the business. Another device may import the file, and
it must know what was changed by hand.

**Merge: the newer row wins, the list included — no union.** The list describes
the values of its own row. Where device A changed the email and device B, later
and without A's change, changed the phone, B's row wins: the email is B's value,
which was not edited by hand, and B's list `["phone"]` says exactly that. A
union `["email","phone"]` would protect an email nobody edited, and the import
could never update it again. A standstill fills a `NULL` list from the other side
(`fillGaps`, on server and app), as with every column.

**Refetch after the update.** A phone still on 1.5.0 receives businesses whose
list another phone has set, stores them without the column and moves its
watermark past them. After the update those rows hold `NULL`: its import would
overwrite the hand edit, and its next save of the business would send
`edited_fields: null` as the newer row and clear the list everywhere.
`Preferences.refetchedForEditedFields` makes the first sync on schema 9 start
from watermark 0 once, as schemas 4, 6 and 7 did. The rows come down at a
standstill and the store fills the gap.

What is accepted:

- A business changed on a phone still on 1.5.0 and not synchronised before its
  update goes up with `edited_fields: null` and a newer `updated_at` — it wins,
  and a list set elsewhere in the meantime is gone. A device stays on the old
  version for a day, not a season.
- A phone still on 1.5.0 knows nothing of the list: an import there overwrites
  hand edits, which then travel as the newer row. Update every phone before
  importing again.
- An app on 1.5.0 sends no `edited_fields` key at all. The server keeps the
  stored value for a missing key (`server/README.md`, „Ein fehlendes Feld ist
  kein leeres"), so editing a business there does not clear the list.

### Migration

Server `011-edited-fields.sql`: `ALTER TABLE businesses ADD COLUMN edited_fields
TEXT`. Nothing carried over, nothing marked, no sequence number moves.
`receive.js` reads the columns from the schema and `deliver.js` delivers
`SELECT *`: nothing else changes on the server, and a test says so.

App database version 8 → 9: the same `ALTER TABLE`, on create and on upgrade.
Nothing marked.

### Interface

- Detail view, below the master data rows: **Stammdaten bearbeiten**.
- The detail row for `contact_name` reads „Ansprechpartner (importiert)" while the
  name is the imported one, and „Ansprechpartner" once `contact_name` is in
  `edited_fields`.
- The form in editing mode: title „Stammdaten bearbeiten", sections „Betrieb"
  (Name, Telefon, Branche) and „Kontakt" (Ansprechpartner, Webseite, E-Mail),
  save button „Stammdaten speichern". Back leads to the detail view, which shows
  the saved values.
- An error — no name, an incomplete number — shows at the top of the form, as
  when creating.

## 3. The read-back never deletes a visit

### The problem

When the detail view opens, the app compares each appointment with its event in
the device calendar (`Appointment.reconcile`). A visit without an invitation
whose event this device has seen before and no longer finds, while the visit is
still ahead, is `DeletedInCalendar`: the app deletes it with a tombstone, and the
server then deletes the event at Infomaniak. In the phone test, deselecting the
calendar in DAVx⁵ made every such visit look deleted — they would have gone from
the server and from Infomaniak. An invited visit was already safe
(`MissingInvited`).

### Decisions

- **Every visit** (`kind` visit) gone from the calendar while still ahead gets
  what an invited visit gets today: nothing is deleted, nothing is stored. The
  outcome is renamed `Reconcile.MissingVisit`; `reconcile` loses its `invited`
  parameter.
- The detail view shows „Im Kalender nicht mehr gefunden" under the visit, with
  **Termin entfernen**, as today for an invited visit. If the event turns up
  again, the line goes.
- **Termin entfernen** under that line:
  - **without an invitation** removes the visit at once, without a dialog —
    nobody gets a cancellation. What the removal did is said in a hint:
    „Termin entfernt." or, where the status falls back, „Termin entfernt. Status
    zurück auf „Angerufen“."
  - **with an invitation** opens the removal dialog as today: „<Adresse> bekommt
    eine Absage. …".
- The ordinary **Entfernen** button of a visit keeps its dialog.
- A past visit gone from the calendar only loses its link (`Unlink`), as today.
- **Callbacks are unchanged:** gone from the calendar while ahead, a callback is
  still `DeletedInCalendar` and deleted, with the hint „Der Rückruf wurde im
  Kalender gelöscht. …".

## Testing

### Server (`node:test`)

- Migration 011: `businesses.edited_fields` exists and is NULL on existing rows;
  no `server_seq` and no counter moves; the column accepts NULL.
- `receive` / `deliver`: `edited_fields` is written from a payload and delivered;
  a payload without the key keeps the stored list; a standstill fills a NULL
  list; a newer row replaces the list rather than merging it.

### App (JUnit / Robolectric)

- `BusinessFormTest` — a second business with the same number, in another
  notation, is created; an incomplete number is still refused.
- `MigrationTest` — 8 → 9 adds `edited_fields` empty and marks nothing.
- `SyncSchemaTest` — a fresh database has the column.
- `SyncStoreTest` — the list comes down with a newer row, replacing the local
  one; a standstill fills a NULL list.
- `SyncEngineTest` — the first sync on schema 9 starts from watermark 0, once.
- `MasterDataTest` — changed fields: only real changes, clearing counts,
  whitespace and phone notation do not; the list is parsed and written sorted,
  garbage reads as empty; the draft from a business; the contact name's label
  with and without `contact_name` in the list.
- `RepositoryTest`
  - editing writes the changed columns, stamps and marks the business, and
    records the changed fields; the list grows across saves;
  - an unchanged save writes nothing;
  - clearing the number records `phone` and takes the business out of the target
    set;
  - a new name is found by the search;
  - no name or an incomplete number: refused, nothing written;
  - a re-import leaves edited columns alone and updates the rest; a re-import
    that differs only in edited columns writes nothing and counts no update;
    `is_target` follows a number cleared by hand.
- `AppointmentTest` — a visit gone from the calendar while ahead is
  `MissingVisit` with and without an invitation; past: `Unlink`; a callback is
  still `DeletedInCalendar`; the calendar line offers the removal for a visit
  without an invitation too; removal asks only with an invitation; the hint
  after an unasked removal.

There is no view model test harness. The view model only carries out the
decisions above; its wiring is covered by `assembleDebug` and the phone test.

### On the phone, once

1. Create a business with the number of an existing one: it is saved.
2. Open an imported business, **Stammdaten bearbeiten**: change the email, clear
   the website, save. The detail view shows the new values; the phone book entry
   carries the new email. Change the contact name: the row reads
   „Ansprechpartner", no longer „(importiert)".
3. Re-import the business file: email and website stay as edited; another
   business's changed data from the file is taken.
4. On a second phone, after sync: the same values; its re-import keeps them too.
5. A visit without an invitation, seen on this phone once. Deselect the calendar
   in DAVx⁵, open the business: nothing is removed, „Im Kalender nicht mehr
   gefunden" with „Termin entfernen". Select the calendar again, let DAVx⁵ sync,
   open the business: the line is gone. Deselect again, tap „Termin entfernen":
   no dialog, the visit goes, the hint says so; the event at Infomaniak is gone.
6. The same with an invitation: the dialog names the invitee.

## Documentation

- `server/README.md` — `businesses.edited_fields`.
- `server/docs/superpowers/specs/` — a pointer to this spec.
- `app/docs/data-model.md` — the column; the import leaves edited columns alone;
  the refetch on schema 9; in „Calendar", the read-back deletes only callbacks.
- `app/docs/usage.md` — editing master data; the same number at several
  businesses; a visit gone from the calendar.
- `app/CHANGELOG.md` — a section for the release.

## Rollout

Server first (migration 011), then the app. Every phone is updated before the
business file is imported again.
