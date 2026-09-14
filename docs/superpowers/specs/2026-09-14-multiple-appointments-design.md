# Several appointments per business

## The problem

A business holds exactly one appointment. The four columns `appointment_at`,
`appointment_end_at`, `appointment_location` and `calendar_event_id` sit on the
`businesses` row itself, and the detail view turns **Termin anlegen** into
**Ändern** and **Entfernen** as soon as one is set. Saving again moves the one
linked calendar event; it never creates a second.

That does not match the work. Appointments at one business come in two shapes,
and both happen:

- **One after another** — a site visit, then a meeting about the quote. Only
  one is ahead, and the earlier ones are worth keeping as a record.
- **Side by side** — two appointments ahead at once, with different people or at
  different sites of the same business.

Today the only way to hold a second one is to remove the first — which deletes
its calendar event and puts the status back to `called`.

## Approach

A table of its own, `appointments`, synchronised like `contacts` and
`contact_numbers`: a row per appointment, keyed by a UUID, deletable through
tombstones. Every rule the sync already applies to those rows — the newer
version wins, a standstill fills gaps, a tombstone beats an older row — is the
rule an appointment needs. No new merge logic.

Two alternatives were rejected:

- **A JSON array in a column on `businesses`.** Barely touches the sync, but the
  whole business row wins or loses as one: a device adding appointment 2 while
  another moves appointment 1 loses one of the two. And the device-local
  `calendar_event_id` cannot be separated from what travels.
- **Entries in `calls` with `kind = 'appointment'`.** The log is append-only on
  purpose: time and duration stay as recorded, and there are no tombstones. An
  appointment gets moved and deleted; it would have to break the log's rules.

All devices are updated together. An app on 1.3.x that keeps syncing does not
need to be supported, only not to break anything. Rollout order: **server
first, then every device.** Neither order is free: each side has to cope with a
partner that does not know the table yet (see
[A server that does not know the table](#a-server-that-does-not-know-the-table)
and [An app that does not know the table](#an-app-that-does-not-know-the-table)).

## Data model

### App — `Database.VERSION` 3 → 4

```
appointments
id                      TEXT PRIMARY KEY           -- UUID; carried-over appointments: 'legacy-<place_id>'
place_id                TEXT NOT NULL
starts_at               TEXT NOT NULL              -- ISO-8601 with a time and a zone
ends_at                 TEXT
location                TEXT                       -- one line, as it goes into the calendar event
note                    TEXT                       -- "Besichtigung", "Angebot" …
contact_id              TEXT                       -- one of the business's contacts, or null
updated_at              TEXT NOT NULL
event_uid               TEXT                       -- iCalendar UID of the linked event, or null
calendar_event_id       INTEGER                    -- local: the event's _ID on this device
calendar_seen_starts_at TEXT                       -- local: what this device last saw in the event
calendar_seen_ends_at   TEXT
calendar_seen_location  TEXT
dirty                   INTEGER NOT NULL DEFAULT 0
```

Indexes on `place_id`, `starts_at`, `event_uid` and `dirty`.

### Server — migration `005-appointments-table.sql`

The same table without `dirty` and the four `calendar_` columns, with
`server_seq INTEGER NOT NULL` and an index on it.

### What stays where

- **The status stays on the business.** `appointment` still means "an
  appointment was agreed"; the rules for when it is set and taken back are
  below.
- **`contact_id` is a reference and nothing more.** A deleted contact leaves the
  appointment showing no contact person. Deleting a contact does not touch its
  appointments, and the sync does not check the reference.
- **`event_uid` travels, the `calendar_` columns do not.** The UID is the same
  event on every device carrying the shared calendar; an `_ID`, and what one
  device last saw in its copy of the event, are not (see
  [The calendar](#the-calendar)).

### Carrying over the existing appointments

Both sides do the same, each in its own migration:

1. Every business with a non-empty `appointment_at` becomes one row:
   `id = 'legacy-' || place_id`, `starts_at`, `ends_at` and `location` from the
   old columns, `note`, `contact_id` and `event_uid` null, `updated_at` from
   the business.
2. The old columns `appointment_at`, `appointment_end_at`,
   `appointment_location` — and in the app `calendar_event_id` — are set to
   NULL. The columns themselves stay: dropping them would mean rebuilding
   `businesses`, and no code of version 4 reads or writes them.

Why these details matter:

- **The fixed id.** If each side made up its own UUID, every existing
  appointment would exist twice after the first sync. `legacy-<place_id>` is the
  same on both sides without either asking the other.
- **`updated_at` from the business.** Where the business had already been
  synchronised, both sides hold the same timestamp. The row arrives as a
  standstill and changes nothing.
- **The app keeps its `calendar_event_id`**, copied into the new row, and — where
  there is one — fills the `calendar_seen_` columns from the old columns: the
  read-back has kept the two in step so far. The existing calendar event stays
  linked. Its UID is not known yet, so `event_uid` stays null; the first
  read-back that finds the event stores its `UID_2445` there and marks the row
  as changed, with a new `updated_at`, so the other devices learn it. (A standstill
  would not do: the app, unlike the server, fills no gaps.)
- **The app marks every carried-over row `dirty = 1`.** An appointment that had
  not been uploaded yet still reaches the server; one the server already has
  arrives as a standstill.
- **Both sides empty the old columns.** Emptied on one side only, `fillGaps`
  would copy the old values back on the next standstill. Neither side marks the
  business as changed for it.
- **The server hands out sequence numbers.** Each carried-over row gets a unique
  `server_seq` from `sync_counter` (current value plus `ROW_NUMBER()`), and the
  counter moves on by the number of rows. A phone restored from the server
  receives them like any other row.

A fresh install creates version 4 directly and has nothing to carry over. Its
`businesses` table still has the four old columns and
`idx_businesses_appointment`, so a fresh and an upgraded database keep the same
columns — which `MigrationTest` already checks.

### While devices still run 1.3.x

Until the last device is updated, the old columns are not quite dead. A 1.3.x
app that changes a business sends `appointment_at` with it, and the server
writes it back into its `businesses` row; updated devices receive it and store
it in their unused columns. That is harmless — nothing reads them — but it
means an appointment set on a 1.3.x device in that window becomes an
`appointments` row only when that device is updated and its own migration
carries it over.

## Synchronisation

### Server

- `receive.js`
  - `KEYS` gains `appointments: 'id'`.
  - `receiveContact(db, table, row)` becomes `receiveDeletable` — it now serves
    three tables, and the contact-number parent check stays inside it, limited
    to `contact_numbers`.
  - `payload.appointments` is received after `contact_numbers`.
  - `DELETABLE_TABLES` gains `appointments`.
  - The table names are exported, taken from `KEYS` — not a second list kept
    beside it, for the same reason `writableColumns` reads the schema.
- `deliver.js` — `appointments` joins `TABLES`, `OUTPUT_KEYS` and the empty
  response it starts from (`{ businesses: [], …, appointments: [], deleted: [] }`);
  without the last one, the first appointment in a block throws. The shared cut
  by `server_seq` stays exactly as it is.
- `server.js` — assembles the response from `deliver()` and `rejected`, and now
  adds the tables this server knows:
  `tables: ['businesses', 'calls', 'contacts', 'contact_numbers', 'appointments']`.

### App

- `Rows.TABLES` gains `appointments`. `Rows.key` needs no change — it already
  returns `id` for every table but `businesses`. `pending`, `markAllDirty`,
  `pendingCount` and `apply` pick it up from there. `Rows.LOCAL_ONLY` already
  holds `calendar_event_id` and applies to every table; it gains
  `calendar_seen_starts_at`, `calendar_seen_ends_at` and
  `calendar_seen_location`. `event_uid` is not in it.
- `SyncStore.apply` reports the appointments it wrote and removed, with their
  local links, so the caller can bring the calendar along (see
  [After a sync](#after-a-sync)).
- `SyncStore.TOMBSTONE_TABLES` gains `appointments`. The comments that speak of
  "the four synchronised tables" (`SyncStore.markAllDirty`,
  `SyncEngine.sentCount`, `TOMBSTONE_TABLES`) are brought up to date.
- `Repository.deleteAppointment(id)` deletes the row and leaves a tombstone, the
  way `deleteContact` does.

### A server that does not know the table

An older server ignores `payload.appointments` without a word — nothing lands in
`rejected`. `clearPending` would clear the marks, and the appointments would
never reach the server, with nothing on either side saying so.

So `clearPending` clears marks and tombstones only for the tables named in the
response's `tables`. A response without the field is an older server and counts
as the four tables it has always had: appointments stay marked and keep counting
as pending until the server is updated. Rolling out in the wrong order then
costs a delay, not data.

Rows left marked this way must not keep the sync loop turning. `SyncEngine`
continues while a full block went up and rows are still marked; with 500
appointments stuck against an older server that would be true every round, up
to `MAX_ROUNDS`. So `sentCount` and the pending count used for that decision
only count the tables the response named.

### An app that does not know the table

The recommended order has a hole of its own. Migration 005 gives every
carried-over appointment a fresh `server_seq`, so the server delivers them to
every device on its next sync — a 1.3.x app included. Its `apply` only knows
`Rows.TABLES` and skips them, and its watermark moves past them anyway. The same
happens to appointments an updated device creates in the meantime, and to their
tombstones, which the old `applyTombstone` skips as well.

After the update, those rows never come down again. The migration 3 → 4 only
carries over what stands in the device's own columns. Worse, a carried-over
appointment deleted elsewhere in the meantime comes back: the migration writes
`legacy-<place_id>` afresh and marks it dirty, the server drops it without a
word because its tombstone is newer, the app clears the mark — and the
appointment lives on on this one device.

So the first start after the upgrade to version 4 sets the watermark back to 0,
once. The watermark lives in `Preferences`, not in the database, so the
migration cannot do it itself; it is a flag in `Preferences`, checked before the
next sync. Fetching everything again is safe: `apply` lets the newer version win
and drops what a tombstone covers, so rows the device already holds change
nothing. It costs one full download per device.

## The calendar

The calendar is shared. Every device carries the same CalDAV calendar
(Infomaniak), synchronised by DAVx⁵, so an event one device writes shows up on
the others once DAVx⁵ has synchronised there — with a different local `_ID` on each, and at
its own pace, independent of this app's sync.

Two things follow. A link held as a local `_ID` means nothing on the next
device: it would create a second event for the same appointment, and see the
first one as a conflict with itself. And the calendar a device reads may be
behind the rows it holds, or ahead of them.

### The link: the event's UID

An event is linked through its iCalendar UID, which DAVx⁵ keeps in
`Events.UID_2445` and which is the same on every device.

- An event the app creates gets `UID_2445 = appointments.id`. The app reads the
  event back right after inserting it: if `UID_2445` holds that value, the row
  takes `event_uid = id`; if it came back empty, the link stays local
  (`calendar_event_id`) and `event_uid` stays null.
- An adopted event keeps its own UID; the row takes `event_uid` from its
  `UID_2445`. An event entered on the phone and not uploaded yet may have none —
  then the link stays local (`calendar_event_id`) until a read-back finds a UID
  and stores it. DAVx⁵ writes the UID it generates back into `UID_2445` when it
  uploads, so that happens after its next sync.
- `calendar_event_id` stays as a local shortcut, used while the event behind it
  still exists and is not deleted. Otherwise the event is looked up by
  `UID_2445 = event_uid` among the calendars the app reads, and the id found is
  stored.
- **The event behind the shortcut carries a different UID** than the row's
  `event_uid`, or the row has none yet (a carried-over appointment, an adopted
  event uploaded since): the app takes the event's UID into `event_uid` and
  marks the row as changed, so the other devices look for that one. It is the
  same event — the app already relies on an `_ID` not being handed to another
  event — and taking it for deleted would delete the appointment.

"Linked on this device" means: an event was found that way.

#### Why the UID holds, and what happens if it does not

The UID is not checked on devices beforehand; the design tolerates it going
wrong instead.

That it holds is DAVx⁵'s doing, not Android's. DAVx⁵'s source says it does: on
upload, a `UID_2445` that is already set is used as the event's UID, and one is
generated — and written back — only where it is missing
(`AndroidEventHandler.provideUid`, `CalendarSyncManager` in davx5-ose); the file
name on the server is derived from that UID. On download, the UID goes into
`UID_2445` again. And CalDAV treats the UID as the identity of an event: a
server that changed it when an event is moved in its web calendar would break
the standard.

Should it go wrong anyway, nothing is lost:

| What goes wrong | What the app does |
|---|---|
| Android does not take `UID_2445` from the app | The read after inserting finds it empty; the link stays local. |
| DAVx⁵ or the server replaces the UID | The device holding the event finds a different UID behind its shortcut and takes it over; the others find the event by it after the next sync. |
| Another device cannot find the event by UID | "Never seen" on reading back, `LocalOnly` on saving: no second event, no deleted appointment. The device that holds the event writes the change into it after its next sync. |

The worst case is an event that follows a change a sync later, not a lost
appointment or a duplicate event.

### Saving

`AppointmentDraft` gains `appointmentId` (null for a new appointment), `note`
and `contactId`.

`Appointment.plan` keeps its shape, with `ownEventId` now the event linked to
the appointment being edited on this device. So a linked appointment is
updated, and a new one gets an event of its own. One case is added: the
appointment has an `event_uid`, but no event is found on this device. That is
`LocalOnly` — creating one would put a second event into the shared calendar
the moment DAVx⁵ catches up on this device. The sheet says the event will
follow.

- **Busy times** leave out only the event of the appointment being edited,
  recognised by its UID. The business's other appointments stay in the strip as
  busy — two appointments at the same time are a real conflict.
- **Linking an existing event** (`Adopt`) is offered only for an event whose UID
  is no appointment's `event_uid`. `event_uid` travels, so this holds across
  devices. Otherwise two appointments would share an event, and changing or
  removing one would take the other along. Such an event still shows as a
  conflict; only **Verknüpfen** is not offered for it. Two more cases get no
  **Verknüpfen**:
  - **An appointment that already has an event.** Its old event would stay in
    the calendar, and a device whose shortcut still points there would keep
    writing into it and take its UID back — two events for one appointment, and
    a UID going back and forth between devices.
  - **A recurring event**, or a changed occurrence of one. `Events.DTSTART` holds
    the start of the series, not of the occurrence shown in the strip; linking
    it would move the appointment to the first occurrence.
- **The event** is titled `Ortstermin <Firma> – <Notiz>`, or
  `Ortstermin <Firma>` without a note. With a contact person, the description is
  their name and first number; without one, the business's phone number, as
  today.
- After writing or adopting, the row remembers locally what the event now holds:
  `calendar_seen_starts_at`, `calendar_seen_ends_at`, `calendar_seen_location`.
- **Saving never clears `event_uid`.** A draft opened before a read-back took
  over a UID carries none; saving it must not write that emptiness over the
  row, or the lost link would travel to every device.
- **An update that fails** because the event is gone — deleted in the calendar
  while the sheet was open — creates a new event with a **fresh** UID, not the
  appointment's id: DAVx⁵ may still be deleting the old `<id>.ics`. If the event
  is still there and the update failed for another reason, the local link is
  dropped and a hint says why once the sheet has closed. It never simply inserts a second event.
- **An appointment deleted while its sheet was open** — by a sync — is not
  saved back into existence. The sheet closes with a hint.

### Reading back

The read-back runs on opening a business, once per appointment with an
`event_uid` or a local link. It compares three things: the row (**R**), the
event (**E**) and what this device last saw in the event (**S**). Times count
as equal within a minute, locations after trimming, as today.

**The event is found.**

| S | E = S? | R = S? | What happened | Result |
|---|---|---|---|---|
| none | — | — | first sight on this device | E = R: nothing. Otherwise **R wins**: the event is updated. |
| set | yes | yes | nothing | nothing |
| set | **no** | yes | moved or relocated in the calendar | **E wins**: the row takes it, as today |
| set | yes | **no** | changed on another device, synchronised | **R wins**: the event is updated |
| set | no | no | changed on both sides | E = R: nothing. Otherwise **R wins**. |

Every result ends with S set to what the event now holds — written only where
it differs, so opening a business that is in step writes nothing. "The event is
updated" happens only where the app may write the calendar at all (calendar
switched on in the settings, write permission granted); otherwise the result
waits, and S stays as it was.

Where both sides changed, the row wins: it is what every device shows, and the
calendar gives the app no modification time to compare. On first sight, too,
the row wins — this device's calendar may simply not have caught up.

When two devices both update the event with the same values — one because the
row changed, the other because its calendar was behind — they write the same
thing twice, which is harmless.

**The event is not found.**

- **Never seen on this device** (S empty): nothing. The event may not have
  reached this device's calendar yet.
- **Seen before, appointment still ahead** (`ends_at` in the future): deleted in
  the calendar. The appointment is deleted with a tombstone, and the hint says
  so, as today.
- **Seen before, appointment already past:** the appointment stays as a record
  and only loses its local link and S. Many calendars clear out old events on
  their own, and that must not erase the history. Only local columns change, so
  the row is not marked as changed; `event_uid` stays.

An event DAVx⁵ deleted and wrote back with a new `_ID` — after a
full resynchronisation, say — is found again by its UID and is not taken for
deleted.

**Without read permission the app does not look.** Nothing is read back, saving
leaves the calendar alone, and removing an appointment removes only the row.
That is not a failure either: someone who never switched the calendar on is
told nothing about it. Only with the calendar switched on does a hint say that
the permission is missing.

**Not found is not the same as not readable.** Only a lookup that ran and came
back empty counts as "not found". If the calendar provider throws, or the app
may not read the calendar, the appointment is left exactly as it is — no
reconciling, no unlinking, no tombstone. A deletion that travels to every
device must never rest on a hiccup on one of them. For the same reason the
lookup ignores whether a calendar is shown: a calendar hidden in the calendar
app still holds its events.

### After a sync

Nobody may open the business for days, so a sync does not leave the calendar to
the next read-back. `SyncStore.apply` reports which appointments it wrote and
which it removed, each with its local link. After the sync, where the app may
write the calendar:

- **A written appointment** that is linked on this device goes through the
  read-back above, but only its "R wins" results are carried out. Anything
  that would change the row or delete the appointment waits for the next
  opening, where a hint can be shown.
- **A removed appointment** has its linked event deleted. With a shared
  calendar the device that removed it has usually done so already; deleting an
  event that is gone changes nothing.

## Status

An appointment is **ahead** while its `ends_at` — or `starts_at`, where it has
no end — lies in the future, and **past** once it does not. The same definition
serves the status, the read-back and the split in the detail view.

- **Saving an appointment that is still ahead** sets `status = 'appointment'`,
  as today. Entering a past appointment after the fact leaves the status alone.
- **Removing an appointment** — with the button or by deleting it in the
  calendar — puts the status back to `called` only when both hold:
  - the status is still `appointment`, and
  - **no appointment ahead** is left for the business.

  A business set to `declined` or `do_not_call` keeps that, as today.
- **Entfernen** deletes the calendar event for a past appointment too, but asks
  first. Unlike today, it removes a piece of history.

The rules live as pure functions in `Appointment` — whether a status falls
back, which of row and event wins, whether a missing event means nothing,
delete or unlink, whether an event may be adopted, when saving stays
`LocalOnly` — so they are tested without a content provider.

## Interface

### Business detail

The appointment section lists:

1. **Appointments ahead**, earliest first. Each row: time range, note, contact
   person, location (tappable for the map), "Im Kalender abgelegt." when linked
   on this device, and **Ändern** and **Entfernen**. **Entfernen** on a device
   where the event is not found removes the row; the device that has the event
   deletes it after its next sync.
2. **Frühere Termine (n)**, collapsed, latest first, with the same buttons.
   **Entfernen** asks first.
3. **Termin anlegen**, always.

### Appointment sheet

Below the location:

- **Notiz** — one line, optional.
- **Ansprechpartner** — a choice among the business's contacts, defaulting to
  "Keiner". Absent when the business has no contacts.

Editing fills everything from the appointment. A new appointment starts as today:
in two days snapped to the quarter hour, the last duration used, the business's
address as location.

### Today

**Termine heute** shows one row per appointment, not per business. Two
appointments at one business today are two rows.

`Repository.appointmentsDue` returns pairs of appointment and business, still
leaving out `do_not_call`. `BusinessRow` takes the appointment as a parameter
instead of `showAppointment` and shows `<time range> · <note>`.

## Testing

### Server (`node:test`)

- Migration 005: carries appointments over with `legacy-` ids and unique
  `server_seq`, empties the old columns, advances `sync_counter`.
- `receive`: an appointment is written; a newer version wins, an older one does
  not; a standstill fills gaps; a tombstone beats an older row and deletes it.
- `deliver`: appointments arrive in the shared cut, also as the first row of a
  block.
- `server.test.js`: the response carries `tables`.

### App (JUnit/Robolectric, beside the existing tests)

- `MigrationTest`: 3 → 4 carries appointments over with their
  `calendar_event_id` and filled `calendar_seen_` columns, marks them dirty,
  empties the old columns; a fresh database still has the same columns.
- `SyncSchemaTest`: `appointments` is synchronised; `event_uid` travels,
  `calendar_event_id` and the `calendar_seen_` columns neither go up nor come
  down.
- `SyncStoreTest`: `apply` writes appointments and honours their tombstones,
  and reports what it wrote and removed with the local links; `clearPending`
  leaves appointments marked when `tables` is missing or does not name them.
- `SyncEngineTest`: the first sync after the upgrade starts from watermark 0,
  and only that one; appointments left marked by an older server do not keep
  the loop running.
- `RepositoryTest`: create, update, delete with tombstone; `appointmentsDue`
  with two appointments at one business.
- `AppointmentTest`: the status rule; every row of the read-back table,
  including first sight; a missing event never seen, seen and ahead, seen and
  past; `LocalOnly` for an appointment whose event is not on this device; no
  adopting of an event whose UID another appointment holds, of a recurring
  event, or for an appointment that already has an event; busy times leave out
  the edited appointment's event by UID; an empty `UID_2445` after inserting
  keeps the link local; a different UID behind the shortcut is taken over, not
  taken for deleted.

### On the phone

Once, after implementation, on one phone with the Infomaniak calendar in
DAVx⁵:

1. Create an appointment in the app.
2. Move it in the Infomaniak web calendar.
3. Let DAVx⁵ synchronise, open the business: the appointment shows the new time,
   and the calendar holds one event for it, not two.

## Documentation

- `app/docs/data-model.md` — the new table; the old columns marked as no longer
  used.
- `app/CHANGELOG.md` — a section for 1.4.0.
- `server/docs/superpowers/specs/` — a pointer to this spec, since migration 005
  and the `tables` field are server work.

## Out of scope

- Recurring appointments.
- An overview of appointments across all businesses beyond **Heute**.
- Reminders beyond those the calendar itself sends.
- Calendars other than the shared one. A device that writes into a calendar
  only it carries still works — its events are found by UID on that device
  alone — but other devices then save changes without touching it, and the
  event follows only after that device's next sync.
