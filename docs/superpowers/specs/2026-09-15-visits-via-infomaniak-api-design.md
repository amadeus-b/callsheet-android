# Visits through the Infomaniak API, with invitations

## The problem

An appointment on site („Vor Ort", kind `visit`) should be able to invite the
person it is with: a real calendar invitation from `christoph@bauer-ki.de`, with
an update when the visit moves and a cancellation when it is removed.

Today the app writes a visit into the device calendar and DAVx⁵ uploads it to
Infomaniak over CalDAV. That path cannot invite:

- **CalDAV does not schedule.** An event with an attendee, created in an Android
  calendar app and uploaded by DAVx⁵ to Infomaniak, arrives with the attendee
  and sends no mail (tested 2026-09-15).
- **The Infomaniak API does.** `POST https://api.infomaniak.com/1/calendar/pim/event`
  with `attendees` and `notifyAttendees: true` sends an invitation from the
  account's login address (`christoph@bauer-ki.de`, SPF/DKIM/DMARC pass,
  `METHOD:REQUEST`). `PUT` with `notifyAttendees: true` sends an update with the
  same UID and a higher `SEQUENCE`; `DELETE …?calendar_id=…&notifyAttendees=true`
  sends a cancellation. All three tested 2026-09-15 against `test@example.org`.

Two facts about the API shape the design:

- **Infomaniak ignores a UID we send** and assigns its own UUID. The create
  response does not contain it.
- **`GET /1/calendar/pim/event/{id}/export`** returns the event as iCalendar,
  `UID:` included. There is no import endpoint.

## Decisions

- **Callbacks stay as they are.** The app writes them into the device calendar,
  DAVx⁵ uploads them.
- **Every visit goes through the API**, with or without an invitation. One
  writer per kind: the app no longer writes visits into the calendar.
- **The server calls the API, not the app.** The token stays on the server; two
  devices cannot invite twice; a failure is retried in one place.
- **Through a queue on the server**, worked after the sync response — not inside
  `/sync`. A slow or unreachable Infomaniak must not stall the sync of calls and
  contacts.
- **An invitation is a choice per visit**: a switch „Einladung senden" and an
  address, preset to the contact person's first email.
- **Saving works offline.** The visit is stored and shown as pending; the server
  creates the event and sends the invitation when the row reaches it.
- **The invitee sees title, time and place** — never the note, never a phone
  number. A change to the note sends no mail.
- **The title is editable** in the sheet, preset to
  `Erstgespräch KI bei <Firma> – Christoph Bauer`.
- **The place** is preset to the contact person's address, else the business's
  first address. Several addresses per business are a separate spec
  (`2026-09-15-business-addresses-design.md`); until it lands, the preset is the
  business address as today.
- **No carry-over.** Every visit in the calendar today is a test and is deleted
  before rollout.

## Data model

### `appointments` — new synchronised columns

All nullable, as `server/README.md` asks of every new column.

| Column | Written by | Meaning |
|---|---|---|
| `title` | app | The event's title. Null means the default title. |
| `invite_email` | app | The invitee's address. Null means no invitation. |
| `calendar_state` | server | `pending`, `ok` or `error`. Null for callbacks. |
| `calendar_error` | server | The text behind `error`, shown in the app. |

`event_uid` exists. For a visit the server sets it from the export; for a
callback the app sets it, as today.

App: database version 7 → 8, `ALTER TABLE appointments ADD COLUMN` for the four.
Server: migration `010-visits-via-api.sql`.

### Server-owned columns

`calendar_state`, `calendar_error` — and `event_uid` on a visit — are the
server's. Whatever a payload says for them is ignored, as `server_seq` is today.

When the server writes them, the row gets a new `server_seq` and keeps its
`updated_at`. `updated_at` is a working date, not a lever; bumping it would make
the server's bookkeeping win over a real edit made on a phone at the same time.

For the same reason the app takes these columns from the server **whatever
`updated_at` says**: a row edited on a phone is newer than the server's copy,
and without this rule the next merge would write the phone's `event_uid = null`
over the UID the server just fetched. `Rows` names them as server-owned;
`SyncStore.apply` writes them even where it otherwise keeps the local row. The
app never sends `calendar_state` or `calendar_error`; it may send a visit's
`event_uid` like any column, and the server ignores it.

On the server, `receive.js` adds them to `SERVER_OWNED` for visits. `event_uid`
is server-owned only where the row's kind is `visit` (or null): a callback's
UID still comes from the app.

### `infomaniak_events` — server only, not synchronised

```sql
CREATE TABLE infomaniak_events (
    appointment_id  TEXT PRIMARY KEY,
    event_id        INTEGER,          -- Infomaniak's id, null until created
    pushed          TEXT,             -- JSON: what Infomaniak last received
    pending         TEXT,             -- 'create' | 'update' | 'delete' | NULL
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);
```

`pushed` holds `{title, starts_at, ends_at, location, invite_email}` as last sent.
It is what tells the server whether a change has to go to Infomaniak at all.

Not a sync table: it is not in `KEYS`, not delivered, and has no `server_seq`.

## Server

### Configuration

- `INFOMANIAK_TOKEN` — API token with scopes `workspace:calendar user_info`,
  created as `christoph@bauer-ki.de`.
- `INFOMANIAK_CALENDAR_ID` — the calendar visits go to (`<calendar-id>`,
  „Christoph Bauer").

Both in `.env`, passed through `docker-compose.yml`, documented in
`.env.example`. Without a token the server starts, logs a warning, and leaves
visits `pending`; the sync is unaffected.

### On sync (`receive`)

Inside the existing transaction, no outside calls:

1. For every visit row that **wins** the merge (new, newer, or a standstill that
   fills a gap in `title`, `starts_at`, `ends_at`, `location` or
   `invite_email`), compute the wanted state
   `{title ?? defaultTitle, starts_at, ends_at, location, invite_email}`.
   `defaultTitle` needs the business name, read from `businesses`:
   `Erstgespräch KI bei <Firma> – Christoph Bauer`, and
   `Erstgespräch KI – Christoph Bauer` where the name is empty. App and server
   build it byte for byte the same. Title and location are trimmed before they
   are compared or sent, so whitespace from the web calendar never triggers an
   update.
2. No `infomaniak_events` row → insert one with `pending = 'create'`.
   A row whose `pushed` differs from the wanted state → `pending = 'update'`
   (unless it is `create`, which stays `create`).
   Equal → nothing.
3. A tombstone for a visit that has an `infomaniak_events` row with an
   `event_id` → `pending = 'delete'`. Without an `event_id` (never created) the
   row is simply removed.
4. Wherever something became pending, the appointment gets
   `calendar_state = 'pending'`, `calendar_error = null` and a new `server_seq`.

A change to `note` or `contact_id` alone leaves the wanted state equal and
queues nothing. Callbacks never queue.

### After the response (`pushCalendar`)

A single worker, `pushCalendar(db, client)`, in its own module. It runs after
every `/sync` response is written, once at start, and every 10 minutes while any
row is pending. One run at a time: a trigger during a run sets a flag, and the
run repeats once before it ends.

The Infomaniak calls live in a small client module (`infomaniak.js`:
`createEvent`, `getEvent`, `exportEvent`, `updateEvent`, `deleteEvent`) so the
worker is tested against a fake.

**create**

1. `POST /1/calendar/pim/event` with title, local start and end, location,
   `calendar_id`, `timezone_start`/`timezone_end = Europe/Berlin`,
   `freebusy: busy`, `type: event`, `fullday: false`, and — with an invitation —
   `attendees` (invitee `NEEDS-ACTION`, organizer `christoph@bauer-ki.de`
   `ACCEPTED`, `organizer: true`) and `notifyAttendees: true`.
2. Store `event_id` **at once**, in its own write. From here a retry updates,
   it never creates a second event.
3. `GET …/{event_id}/export`, read `UID:`.
4. Write `event_uid`, `calendar_state = 'ok'`, `pushed`, `pending = null`,
   `attempts = 0`; new `server_seq` on the appointment.

A row with `pending = 'create'` and an `event_id` already set (the worker died
between 2 and 4) continues at 3.

The one window left open is between the `POST` returning and step 2 committing.
A crash there leaves an event on Infomaniak the server does not know, and the
retry creates a second one — with an invitation, a second mail. It is a
milliseconds-wide window on a process that rarely dies; it is accepted and
named here rather than engineered around.

**update**

1. `GET …/{event_id}`. If the event already holds the wanted state — the visit
   was changed in the Infomaniak web calendar and the app took that over — write
   only `pushed`, `calendar_state = 'ok'`, no `PUT`, no mail.
2. Otherwise `PUT …/{event_id}` with the full event (the API replaces, it does
   not patch) and `notifyAttendees: true` when an invitation exists **before or
   after** the change. Removing or replacing the address therefore notifies the
   old attendee; whether Infomaniak sends them a cancellation is checked on the
   phone.
3. Write `pushed`, `calendar_state = 'ok'`, `pending = null`, `attempts = 0`,
   and refresh `event_uid` from the export if it is still null.

**delete**

`DELETE …/{event_id}?calendar_id=…&notifyAttendees=true`. Success or 404: the
`infomaniak_events` row is removed. The appointment is already a tombstone;
nothing is delivered.

### Times

`starts_at` and `ends_at` are ISO instants with an offset. The API wants local
wall time in `Europe/Berlin` as `YYYY-MM-DD HH:MM:SS`. Converted with
`Intl.DateTimeFormat` for `Europe/Berlin`, not by string surgery. A visit without
`ends_at` gets the app's default visit length.

### Errors

| What happens | Result |
|---|---|
| Network error, timeout, 5xx, 429 | `pending` stays, `attempts + 1`, `last_error` set. The appointment stays `pending`. Retried on the next run. |
| 401 / 403 | `calendar_state = 'error'`, `calendar_error = 'Kalender-Zugang ungültig'`. `pending` stays: a fixed token picks it up on the next run. |
| Other 4xx | `calendar_state = 'error'`, `calendar_error` = Infomaniak's description. `pending = null`; the next change to the visit queues it again. |
| 404 on update | `calendar_state = 'error'`, `calendar_error = 'Im Kalender gelöscht'`, `pending = null`. **Not** recreated: a customer must not be invited to a visit that was deleted. |
| 404 on delete | Done. |

Every error is logged with the appointment id, never with the token.

## App

### Sheet (`AppointmentSheet`) for a visit

- **Titel** — a text field, preset to
  `Erstgespräch KI bei <Firma> – Christoph Bauer`. Left empty, the preset is
  saved. Stored in `title`.
- **Ort** — preset as described under Decisions.
- **Einladung senden** — a switch. On, it shows the addresses from the contact
  person's `contact_emails` (first preselected) and a free field; without a
  contact person only the free field. A malformed address blocks saving with a
  hint. Off, `invite_email = null`.
- Below the switch: „Der Eingeladene sieht Titel, Zeit und Ort, nicht die Notiz."

Callbacks show none of the three.

### Saving a visit

- No `CalendarStore.insert` or `update`, and no `Adopt`: an event made elsewhere
  has no Infomaniak id the server knows, and the server could not keep it.
  `Appointment.plan` returns no calendar step for a visit; conflicts are still
  shown from the busy times.
- The row is written and marked; `calendar_state` is left to the server.
- Sync now, and once more about 5 seconds later, so `event_uid` and
  `calendar_state` arrive without another tap.

### Removing a visit

Tombstone, as today; the server deletes the event. With `invite_email` set the
app asks first: „<Adresse> bekommt eine Absage. Entfernen?"

### Business detail

Under each visit:

- `pending` — „Wird im Kalender angelegt …" (no `event_uid` yet) or
  „Wird im Kalender aktualisiert …".
- `ok` — „Im Kalender", and „Eingeladen: <Adresse>" with an invitation.
- `error` — the hint in the error colour with `calendar_error`.
- `pending` is shown only where a sync server is set up; without one the row
  could never leave `pending`.
- null (a visit saved by an app before this version, or a server without the
  feature) — nothing.

### Reading back

The read-back stays for visits, **reading only**:

- The event is found by `event_uid` once DAVx⁵ has brought it down.
- **No `event_uid` yet: nothing is looked up.** A visit the server has not
  created is not „never seen", it is not there yet.
- „Event wins" (changed in the calendar) takes over start, end, location — and
  now **title**. The row is marked and synced; the server finds Infomaniak
  already holding that state and sends nothing.
- **First sight takes nothing.** With no `calendar_seen_*` on this device, an
  event that differs from the row is not taken over and not remembered: this
  device's DAVx⁵ may still hold the state from before a change made elsewhere,
  and taking it would send the invitee an update with the old time. Only an
  event equal to the row is remembered as seen.
- „Row wins, the event is updated" does not exist for visits. The server does
  that.
- Deleted in the calendar, visit ahead, **no invitation**: the row is deleted
  with a tombstone, as today. The server's `DELETE` then gets a 404.
- Deleted in the calendar, visit ahead, **with an invitation**: nothing is
  deleted automatically — a missing event may just as well be a calendar
  deselected in DAVx⁵ or an account set up again, and a deletion would send the
  customer a cancellation. The business detail shows „Im Kalender nicht mehr
  gefunden" with **Termin entfernen** (which asks, as removing an invited visit
  always does). Nothing is remembered: if the event comes back, the hint goes.
- After a sync (`SyncStore.apply` reporting written and removed rows) no
  calendar write happens for visits.

`calendar_event_id` and `calendar_seen_*` stay as they are — local, used to find
and compare the event. `calendar_seen_title` joins them, so a title changed in
the calendar is recognised the same way a moved time is.

### Callbacks

Unchanged: written into the device calendar, `Rückruf <Firma> – <Notiz>`, the
tick when done.

### Settings

Nothing new. The calendar switch now governs callbacks and reading; visits reach
the calendar through the server either way.

## Testing

### Server (`node:test`)

- **Migration 010** — the four columns and `infomaniak_events` exist; existing
  rows keep their values.
- **`receive`**
  - a new visit queues `create`, a new callback queues nothing;
  - a newer visit with a changed time, place, title or invitation queues
    `update`; one with only a changed note queues nothing;
  - an older visit queues nothing;
  - a tombstone for a created visit queues `delete`; for a never-created one
    removes the row;
  - `calendar_state`, `calendar_error` and a visit's `event_uid` in the payload
    are ignored; a callback's `event_uid` is taken;
  - queuing sets `calendar_state = 'pending'`, a new `server_seq`, and leaves
    `updated_at` alone.
- **`pushCalendar`** against a fake client
  - create: `event_id` stored before the export is called; `event_uid`, `ok`,
    `pushed` afterwards;
  - a row with `create` and an `event_id` does not `POST` again;
  - `notifyAttendees` and `attendees` only with an invitation;
  - update with Infomaniak already equal: no `PUT`;
  - update from invited to not invited: `notifyAttendees: true`;
  - delete: 200 and 404 both remove the row;
  - 5xx keeps `pending` and counts; 401 sets the access error and keeps
    `pending`; other 4xx sets `error` and clears `pending`; 404 on update sets
    „Im Kalender gelöscht";
  - a second trigger during a run does not start a parallel run, and the run
    repeats once.
- **Times** — summer time, winter time, the night of the change, a visit past
  midnight.
- **`deliver`** — rows touched by the worker come down with a new `server_seq`
  and their old `updated_at`.
- **Without a token** — the server starts, `/sync` works, visits stay `pending`.

### App (JUnit / Robolectric)

- `MigrationTest` — 7 → 8 adds the four columns and `calendar_seen_title`.
- `SyncSchemaTest` — `title` and `invite_email` go up; `calendar_state`,
  `calendar_error` come down and never go up; `calendar_seen_title` neither.
- `SyncStoreTest` — the server-owned columns are taken from a server row older
  than the local one; a visit's `event_uid` too; a callback's is not forced.
- `AppointmentTest`
  - the default title, and an empty title saved as the default;
  - `plan` for a visit has no calendar write and offers no `Adopt`;
  - the read-back takes a changed title; without `event_uid` it looks up
    nothing and deletes nothing;
  - callbacks: every existing case unchanged.
- ViewModel — saving a visit syncs twice; removing an invited visit asks first.
  The app has no view-model test harness: these decisions live in `Appointment`
  and are tested there; the view model is covered by the phone test.

### On the phone, once

With `test@example.org`, the server holding a real token:

1. Visit with invitation → invitation arrives; the event appears on the phone
   through DAVx⁵; the business shows „Im Kalender · Eingeladen: test@example.org".
2. Move it in the app → an update arrives.
3. Change only the note → no mail.
4. Move it in the Infomaniak web calendar, open the business → the app shows
   the new time; no second mail beyond the one the web calendar offered.
5. Switch the invitation off → does a cancellation reach `test@example.org`? Either
   way, write down what happened in `server/README.md`.
6. Remove it → a cancellation arrives.
7. Visit without invitation → in the calendar, no mail.
8. Airplane mode, save a visit, back online → „Wird im Kalender angelegt …",
   then „Im Kalender".

## Rollout

1. Delete the test visits in the app and their events in the calendar.
2. The user creates a server token (longer validity than the test token) and
   puts `INFOMANIAK_TOKEN` and `INFOMANIAK_CALENDAR_ID` into
   `<deploy-dir>/.env`. The agent does not handle that token.
3. Deploy the server, then release the app. An older server does not know the
   new columns and leaves them out, as the README describes; visits saved
   against it have no `calendar_state` and show nothing.

## Documentation

- `server/README.md` — the variables, `infomaniak_events`, the server-owned
  columns, the errors.
- `server/docs/superpowers/specs/` — a pointer to this spec.
- `app/docs/data-model.md` — the new columns.
- `app/CHANGELOG.md` — a section for the release.
