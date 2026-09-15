# Several addresses per business

## The problem

A business holds one address: `street`, `postal_code`, `city` and, for an
imported business, `latitude` and `longitude`, all on its row in `businesses`.
Contacts already hold several numbers (`contact_numbers`) and several emails
(`contact_emails`); an address cannot be added twice.

Businesses have more than one site — a head office, a branch, a yard — and a
contact person often sits at one of them. Two things need that:

- **The phone book.** The app writes one entry per contact person into the
  CardDAV address book (`2026-09-14-phone-book-entries-design.md`), each with
  the business's address. A person at the branch gets the head office.
- **Visits.** The appointment sheet presets the place with the business's
  address. A visit to the person at the branch starts at the wrong place
  (`2026-09-15-visits-via-infomaniak-api-design.md` builds on this spec).

This spec is implemented before the visits spec.

## Decisions

- **All addresses live in one new table**, `business_addresses` — the existing
  one included, carried over as the first row, the way `contact_emails` took
  over `contacts.email`. There is one place addresses are read from.
- **The first address is the main address** (`position = 0`). It is the preset
  where nothing more specific applies. Any other row can be made the main
  address.
- **A label is free text, optional**, with suggestions: „Hauptsitz", „Filiale",
  „Lager", „Baustelle".
- **A contact person may be assigned to one address** (`contacts.address_id`).
  Their phone book entry then carries only that address, and a visit with them
  starts there. Without an assignment the entry carries every address of the
  business.
- **The view stays per business.** Addresses are edited in the business form
  when a business is created, and for an existing business on an address
  screen opened from the detail view („Adressen bearbeiten", or „Adresse
  hinzufügen" when there is none) — the business form only creates businesses.
  Both share one list component. The assignment is edited in the contact form.

## Data model

### `business_addresses`

```sql
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
```

`position` is nullable on the server, as `server/README.md` asks of new columns;
the app writes it always and reads a null as last. On the app the table carries
`dirty` like every synchronised table and no `server_seq`.

Synchronised like `contact_emails`: newer wins, a standstill fills gaps, a
tombstone beats an older row. Server: `KEYS` gains `business_addresses: 'id'`,
and it joins the deletable tables. App: `Rows.TABLES` and
`SyncStore.TOMBSTONE_TABLES` gain it.

### `contacts.address_id`

Nullable. The id of a `business_addresses` row of the same business, or null
for no assignment. It travels with the contact.

An `address_id` naming a row that does not exist (deleted on another device,
not synchronised yet) reads as no assignment. It is not cleared on reading: the
row may still arrive.

### Migration

Server `009-business-addresses.sql`, app database version 6 → 7.

- Every business with a non-empty `street`, `postal_code` or `city` gets the row
  `id = 'main-' || place_id`, `position = 0`, with its address, coordinates and
  the business's `updated_at`.
- The id is fixed, not a fresh UUID: app and server write the same one, so after
  the first sync the address is one row, not two — as with `legacy-` in 005 and
  `followup-` in 008. Where the business was already synchronised, both sides
  hold the same `updated_at` and meet at a standstill.
- On the server each row gets its own `server_seq` above the counter, and the
  counter moves past them.
- `businesses.street`, `postal_code`, `city`, `latitude`, `longitude` stay and
  are **not emptied**. Nothing new reads them; an app still on the old version
  keeps showing the address it had. They are not written any more either — an
  address edited in the new app does not reach an old app. That is accepted: a
  device stays on the old version for a day, not a season.

The app's migration marks the new rows dirty. On a server that has run 009
they arrive at a standstill and change nothing.

## Import

`Importer` still reads one address per business. `Repository.import` writes it
into `main-<place_id>`:

- **New business** — the business row as today (the old address columns are
  left null) and `main-<place_id>` at `position = 0`.
- **Known business, address row present** — street, postal code, city and
  coordinates are compared; if they differ, they are written, `updated_at` set,
  the row marked. `label` and `position` stay: a label typed by hand, or another
  row made the main address, survive a re-import.
- **Known business, address row absent** — `main-<place_id>` deleted by hand
  (on this device, or by an incoming tombstone) means nothing is written. This
  is remembered in a local-only table `removed_main_addresses`: `deletions` is
  the outgoing queue and is emptied once the server acknowledges a tombstone, so
  it cannot answer the question after the next sync. No tombstone — the row is
  created, at the position after the last existing address.
- **Other address rows** are never touched by an import.

An imported address with every field empty writes no row.

## Search

`search_text` is the business's name and the cities of **all** its addresses,
lower-cased, as today with one city. Recomputed where an address is saved or
deleted, and where `SyncStore.apply` writes or removes a `business_addresses`
row. The import computes it from the imported city plus the business's other
addresses.

## Removing an address

- A contact assigned to it gets `address_id = null` and is marked. Done on the
  device that deletes the address; a device that learns of the deletion by sync
  treats the dangling id as no assignment (see above) and changes nothing.
- The last address can be removed. A business without an address is allowed, as
  today.
- Removing the main address makes the next row the main address:
  `position` is renumbered from 0 and the moved rows are marked.

## Interface

### Business form

The fields „Ort", „Straße und Hausnummer", „PLZ" become a list **Adressen**. Each
row:

- **Bezeichnung** — optional, with suggestion chips „Hauptsitz", „Filiale",
  „Lager", „Baustelle".
- **Straße und Hausnummer**, **PLZ**, **Ort** — „Ort" keeps its suggestions from
  the known cities.
- **Als Hauptadresse** on every row but the first: moves it to the top.
- **Entfernen** on every row.

Below the list, **Adresse hinzufügen**. A new business starts with one empty
row. A row whose street, postal code and city are all empty is not saved, label
or not.

Changing street, postal code or city of a row by hand clears its coordinates:
they belonged to the old address.

### Business detail

Every address is its own row, the main address first. The row's heading is the
label, else „Anschrift". Tapping opens the map: with coordinates where the row
has them, else with the address — `geoUri` takes the address row instead of the
business.

### Contact form and contact view

- **Standort** — a choice between „Keiner" and the business's addresses, each
  shown by its label, else its address. Shown only when the business has two or
  more addresses. Stored in `address_id`.
- „Route zum Betrieb" leads to the assigned address, else the main address.

### Appointment sheet

For a visit:

- The place is preset with the chosen contact person's assigned address, else
  the main address.
- Changing the contact person moves the place along — as long as the place was
  not edited by hand in this sheet.
- Chips with every address of the business switch the place with one tap. Free
  text stays possible.

Callbacks keep having no place.

## Phone book

`PhoneBookEntries` takes the addresses instead of the business's columns.

- **Assigned contact person** — only the assigned address.
- **Not assigned**, or the assigned row is missing — every address, main address
  first.
- **No contact person** (the entry for the company itself) — every address.
- Each address is one `StructuredPostal` row: with a label `TYPE_CUSTOM` and
  `LABEL = <label>`; without one `TYPE_WORK`, as today. Country „Deutschland" as
  today. A row with street, postal code and city all empty is left out.
- The map link for a hand-entered business uses the assigned address, else the
  main address. Imported businesses keep the Google link by `place_id`.

`StructuredPostal` is already a managed row type, so the rows are replaced on
every write. A changed, added or removed address, and a changed assignment,
rewrite the entries of the business's contact persons through the same path a
changed business takes today.

Whether Infomaniak shows the custom label is checked by hand, as „Hauptadresse"
on the number was.

## Testing

### Server (`node:test`)

- Migration 009: `main-<place_id>` with address, coordinates, `updated_at` and a
  unique `server_seq` above the counter; the counter advanced; a business with
  no address gets no row; the old columns are not emptied.
- `receive` / `deliver`: `business_addresses` is received and delivered like
  `contact_emails`, tombstones included; `contacts.address_id` travels; the
  response's `tables` names the new table.

### App (JUnit / Robolectric)

- `MigrationTest` — 6 → 7 writes `main-<place_id>` with the server's id and
  values, marks the rows dirty, skips businesses without an address; a fresh
  database has the table and `contacts.address_id`.
- `SyncSchemaTest` — the table and `address_id` go up and come down.
- `SyncStoreTest` — rows and tombstones applied; `search_text` recomputed after
  applying.
- `RepositoryTest`
  - import creates `main-…` for a new business; updates it when the address
    changed, keeping label and position; writes nothing when it is equal;
    does not recreate it after a tombstone; leaves other rows alone;
  - `search_text` holds every city;
  - removing an address clears `address_id` of its contacts and marks them;
    removing the main address renumbers;
  - „Als Hauptadresse" renumbers and marks the moved rows;
  - editing a row clears its coordinates.
- `PhoneBookEntriesTest` — assigned: one address; not assigned, or assigned to a
  missing row: all, main first; company entry: all; label as `TYPE_CUSTOM`,
  none as `TYPE_WORK`; empty rows left out; map link from the assigned or main
  address.
- `AppointmentTest` — the place preset from the assigned address, else the main
  address; moving along with the contact person only while not edited by hand.

### On the phone, once

1. Give a business a second address „Filiale" and assign one contact person to
   it.
2. In the Infomaniak address book: the assigned person shows only the branch,
   labelled; another person of the business shows both; note whether the label
   appears.
3. Open a visit with the assigned person: the place is the branch. Switch to
   the other person: the place follows. Type a place, switch again: it stays.
4. Re-import the business file: the main address is unchanged or updated, the
   branch still there.

## Documentation

- `server/README.md` — the table, and that the old address columns are no
  longer read.
- `server/docs/superpowers/specs/` — a pointer to this spec.
- `app/docs/data-model.md` — the table, `contacts.address_id`, the old columns
  marked as no longer used.
- `app/CHANGELOG.md` — a section for the release.
