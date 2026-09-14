# Callbacks as appointments

## The problem

A follow-up („Wiedervorlage") is one timestamp on the business row,
`follow_up_at`. A business holds at most one; it has no note, no contact person
and no calendar event. It is visible in two places only: the detail view of the
business, and the **Heute** screen behind the calendar button in the top bar.

An appointment on site is a row of its own in `appointments`: several per
business, each with a time, a length, a place, a note, a contact person and a
calendar event linked by its UID, synchronised with tombstones.

Two things follow from that, and both came up in use:

- **The calendar button shows today only.** Appointments made for tomorrow or
  next week are nowhere in that screen — only in the business's detail view and
  in the device calendar. That looked like a bug in 1.4.0; it is the design
  since 1.2.0.
- **A callback cannot be planned like an appointment.** It never reaches the
  device calendar, carries no note („wegen Angebot nachfragen") and no person to
  ask for, and a second one at the same business replaces the first.

The wish: handle follow-ups like regular appointments.

## Decisions

- A follow-up becomes an **appointment of the kind „Rückruf"** (callback). The
  existing kind is „Vor Ort" (visit).
- **A call completes it.** A call to the business placed from the app
  completes every open callback of that business due by the end of today — as
  soon as the app logs the call on returning from the dialler. Until
  then a callback whose time has passed is **overdue**. A visit is never
  overdue; once its time has passed it simply lies in the past, as today.
- **Completed shows in the calendar as a mark in the title**: „✓ Rückruf Elektro
  Meier". The event stays as a record. CalDAV events (`VEVENT`) have no
  completed state — `STATUS` knows tentative, confirmed and cancelled only.
  `STATUS:COMPLETED` exists on tasks (`VTODO`), which Android has no system
  provider for; DAVx5 syncs them only through a separate tasks app with its own
  API. Not worth a dependency on every phone.
- **The detail view keeps its two sections.** „Termin vor Ort" lists visits,
  „Wiedervorlage" lists callbacks. Merging them into one list was considered and
  dropped: after a call the eye goes to „Wiedervorlage", where the suggestion
  sits, and one glance should tell whether a callback is set.
- **Callbacks are created in the same sheet as appointments**, not with one tap.
- **The calendar button opens an agenda**: overdue callbacks, today, then every
  following day.

## Approach

Two columns on `appointments`: `kind` and `done_at`. Everything the table
already has — sync with newer-wins, standstill and tombstones, the link to the
calendar event by UID, read-back, **Ändern** and **Entfernen** — is what a
callback needs. No new table, no new merge rule.

Two alternatives were rejected:

- **A table of its own, `callbacks`.** Clean on paper, but sync, calendar link,
  read-back and tombstones would exist twice, and so would their bugs — after
  1.4.0 spent most of its effort getting exactly those right across devices.
- **Keep `follow_up_at` and mirror it into the calendar.** Still one per
  business, still no note or contact person. It does not make a follow-up an
  appointment.

## Coordination with work in progress

At the time of writing another piece of work is uncommitted in both
repositories: several e-mail addresses per contact and sending mail from the app.
It takes app `Database.VERSION` 5 and server migrations `006` and `007`.

This spec therefore names **no fixed numbers**. It uses *the next app schema
version* and *the next server migration*, fixed when implementation starts from a
tree that contains that work. Implementation does not start on top of
uncommitted changes of another session.

## Data model

### App — next `Database.VERSION`

Two columns on `appointments`:

```
kind      TEXT                            -- 'visit' | 'callback'; null reads as 'visit'; travels
done_at   TEXT                            -- ISO-8601 with zone; callbacks only; travels
```

`AppointmentEntry` gains `kind: AppointmentKind` (`VISIT`, `CALLBACK`) and
`doneAt: String?`. A null or unknown `kind` read from the database or the
server is treated as `VISIT`; the app writes `'visit'` or `'callback'` on every
save.

`kind` is nullable on purpose, on both sides. The server's README asks for
nullable new columns: `receive.js` fills a gap only where `NULL` stands, and a
`NOT NULL DEFAULT 'visit'` column would carry the default instead of a gap. And
a row the server stores with `kind` NULL — an insert from a 1.4.0 device — would
break a `NOT NULL` column in the app on arrival.

`follow_up_at` stays on `businesses`, emptied and no longer read or written.
Dropping it would mean rebuilding the table, the same reason migration 005 on
the server gives for the appointment columns.

### Server — next migration

```sql
ALTER TABLE appointments ADD COLUMN kind TEXT;
ALTER TABLE appointments ADD COLUMN done_at TEXT;
```

`receive.js` takes its columns from the schema, so no list needs changing. The
receive tests gain a row with `kind` and `done_at` to prove both arrive and
travel back. One change to `write` is needed for older apps — see
[Old devices](#old-devices).

### Carry-over of existing follow-ups

Done on both sides, the way 005 carried appointments over:

- **Id** `'followup-' || place_id` on both sides, so the two rows meet as one
  instead of every follow-up existing twice after the first sync.
- `kind = 'callback'`, `starts_at = follow_up_at`, `ends_at` null (see
  [Length](#length)), no location, note, contact or UID, `done_at` null.
- `updated_at` from the business. Where the business was synchronised both sides
  hold the same timestamp and the rows arrive at a standstill.
- **App:** `dirty` copied from the business. A follow-up set but not yet
  uploaded is then uploaded as a callback; one the server already has is not
  sent again.
- **Server:** each row gets its own sequence number above the counter, and the
  counter moves on, so every device fetches it like any other row.
- Afterwards `follow_up_at` is emptied on both sides — not a change to the
  business: no new `updated_at`, no `dirty`, no sequence number. Emptied on both
  sides, or the next standstill fills it straight back.
- **No calendar event is created for carried-over callbacks.** Each device would
  create its own and a shared calendar would hold one per phone. A carried-over
  callback gets its event the first time it is saved through **Ändern**.

### Length

A callback lasts **15 minutes** by default (`Appointment.CALLBACK_MINUTES`), a
visit keeps `preferences.appointmentMinutes`. Where `ends_at` is missing the
fallback depends on the kind: 15 for a callback, `DEFAULT_MINUTES` for a visit.

## Detail view

### „Termin vor Ort"

Lists visits only. Otherwise unchanged.

### „Wiedervorlage"

- **Open callbacks**, earliest first. Each row: date and time, „überfällig" when
  its start has passed, note, contact person, **Ändern** and **Entfernen**.
- „Keine Wiedervorlage gesetzt." when there is none open.
- **„Erledigte Rückrufe (n)"**, collapsed, the way „Frühere Termine" is. Each
  row shows when it was due and when it was completed, and **Entfernen**, which
  asks first — it is a record.
- The suggestion card after an unanswered call stays: „Vorschlag: <time>",
  **Übernehmen**.
- The buttons stay: **in 2 Tagen**, **nächste Woche**, **nächster Monat**,
  **Datum & Uhrzeit …**.

Every one of those — **Übernehmen**, the three quick choices, and the date and
time picker once a time is picked — **opens the appointment sheet** as a callback
with that start. Nothing is saved before **Rückruf speichern**. The
`FollowUp` rules (working days, 8 to 18, a different time of day after an
unanswered call) are unchanged; they now compute a draft's start instead of a
stored value.

The old „Entfernen" of the single follow-up goes; each callback has its own.

## The sheet

`AppointmentDraft` gains `kind`. The kind comes from the section the sheet was
opened from; the sheet shows no choice.

For a callback:

- the title reads „Rückruf", the button **Rückruf speichern**
- the location field is hidden and nothing is stored as location
- the length starts at 15 minutes
- busy times, the overlap warning, **Trotzdem anlegen**, **Andere Zeit** and
  **Verknüpfen** work exactly as for a visit

## Completing

`evaluateCall` — where the app logs a call placed from it, on returning from
the dialler, whatever the duration, „nicht erreicht" included — completes every
callback of that business with `done_at` null and a start before the end of
today:
`done_at = now`, new `updated_at`, `dirty`. One transaction,
`Repository.completeCallbacks(placeId, untilMillis, now)`, returning the
completed ids.

- Not `saveOutcome`: the save bar only appears when status or note changed. A
  business already at „Nicht erreicht" rung again without an answer would
  change neither, nothing would be saved, and its callback would stay overdue
  for ever.
- A note saved without a call completes nothing.
- A callback for a later day stays open.
- The suggestion for the next callback appears as it does today and is taken
  through the sheet.
- Completing is not undone from the app. A completed callback can only be
  removed.

## Status

Callbacks never touch the business status. `Appointment.statusAfterSave` applies
to visits only; `statusAfterRemoval` looks at the remaining **visits** only, so a
callback still ahead does not keep a business at „Termin".

## Calendar

### Title

`Appointment.eventTitle(kind, businessName, note, done)`:

| kind | done | title |
|---|---|---|
| visit | — | „Ortstermin Elektro Meier – Angebot" (unchanged) |
| callback | no | „Rückruf Elektro Meier – wegen Angebot nachfragen" |
| callback | yes | „✓ Rückruf Elektro Meier – wegen Angebot nachfragen" |

Without a note the part from the dash on is left out, as today. The description
stays contact person and number.

### On completing

For each completed callback with a linked event, where the calendar is switched
on and writable: locate the event (by id, else by UID) and update it with the
done title. A calendar that cannot be read or written leaves the event as it is;
the callback is completed regardless.

### On other devices

The completed row arrives through the sync. `followCalendar` today compares only
the slot (start, end, location), so the title would never follow. It gains one
rule: **for a written callback whose event title differs from
`eventTitle(...)` for the row, the event is updated.** With a shared calendar the
completing device has usually done that already and the titles match; nothing is
written then.

### Read-back

Unchanged and slot-based: a callback moved in the calendar moves in the app, a
callback whose event was deleted in the calendar is removed in the app — with no
status change, since callbacks have none. The ✓ in a title is not read back;
completing happens in the app only.

## The agenda behind the calendar button

`Screen.Today` and `TodayScreen` become `Screen.Agenda` and `AgendaScreen`. The
top bar title and the button's content description read **„Termine"**. Icon and
place stay.

`Repository.agenda()` returns every appointment with its business, businesses
blocked from contact excluded, where

- the kind is callback and `done_at` is null, **or**
- the kind is visit and the start is not before the start of today.

`Agenda.sections(entries, nowMillis)` — a pure function, tested without Android —
groups them:

1. **„Überfällig (n)"** — open callbacks starting before now, earliest first.
   Set apart in the error colours, as today. Rows show date and time.
2. **„Heute (n)"** — today's visits, past ones included, and today's open
   callbacks starting from now on. Rows show the time.
3. **One section per following day that has entries**, headed with weekday and
   date, „Dienstag, 15.09.". Rows show the time. No upper limit.

Within a section: by start, kinds mixed. Each row shows the kind as a label
(„Rückruf" / „Vor Ort"), the business, the note, and the dial button as today;
a tap opens the business.

Empty agenda: „Keine Termine und keine offenen Rückrufe."

It loads when opened, when returned to, and after every successful sync — the
moments `loadToday` covers now. `Repository.due` and the follow-up lists in the
state (`overdue`, `dueToday`) go.

## Old devices

Rollout order as for 1.4.0: **server first, then every device.**

- A device on 1.4.0 against the updated server shows callbacks as visits — its
  schema has no `kind`, so the column is dropped on arrival. When it edits one,
  the row it sends carries no `kind` and no `done_at` key at all. Today `write`
  in `receive.js` fills every column the payload does not carry with NULL
  (`row[c] ?? null`): the callback would lose `done_at`, and `kind` would be
  NULL — read as a visit.

  **`write` therefore distinguishes a missing key from an explicit null.** A
  column whose key is absent from the payload keeps the stored value on update
  and takes the column default on insert; an explicit `null` still clears it.
  An app always sends every column of its own schema (`Rows.kt`), so a missing
  key only ever means an older schema, for any table — which is exactly the
  case where the stored value is the better answer. Server tests: a row
  without `kind`/`done_at` keeps a callback a completed callback; a row with an
  explicit `done_at: null` clears it; an insert without `kind` becomes a visit.
- A follow-up set on a 1.4.0 device lands in `follow_up_at`, which nothing reads
  any more. It is lost to the updated devices. The CHANGELOG says so.
- An updated app against a server without the migration: the server ignores
  `kind` and `done_at`, and a callback comes back from a pull as a visit. Same
  answer as 1.4.0 — update the server first — and the CHANGELOG says so.

## Testing

App, unit tests:

- `MigrationTest` — follow-ups carried over with the fixed id, kind, start,
  `updated_at` and `dirty` from the business; `follow_up_at` emptied;
  businesses without a follow-up get nothing; existing appointments become
  visits.
- `RepositoryTest` — `completeCallbacks` completes due open callbacks of that
  business only, leaves later ones and visits alone, marks them dirty;
  `agenda()` filters as specified.
- `AppointmentTest` — titles for all three cases; default length by kind;
  `statusAfterSave` and `statusAfterRemoval` ignore callbacks.
- `AgendaTest` (new) — overdue, today and day sections; a callback earlier today
  is overdue, a visit earlier today is under today; completed callbacks and past
  visits absent; order within a section.
- `SyncSchemaTest`, `SyncStoreTest` — `kind` and `done_at` travel both ways; an
  unknown kind reads as visit.
- `CallFlowTest` / `FollowUpTest` — the suggestion is still computed as before.

Server: the migration on a database holding follow-ups and appointments; receive
and pull of `kind` and `done_at`; the missing-key rule in `write` as listed under
[Old devices](#old-devices).

On a device: create a callback through a quick choice, see its event; call the
business, save the outcome, see the ✓ in the calendar and the callback under
„Erledigte Rückrufe"; see tomorrow's appointments in the agenda.

## Documentation and release

- `CHANGELOG.md` — section for the next version: callbacks as appointments,
  the agenda, completing by calling, the ✓ in the calendar, **server first, then
  every device**, follow-ups set on an old device are lost.
- `docs/usage.md` — „Follow-ups" and the „Heute" description rewritten.
- `docs/data-model.md` — `kind`, `done_at`, carry-over, `follow_up_at` unused.
- `docs/architecture.md` — the agenda screen and completing on saving a call.

## Out of scope

- Tasks (`VTODO`) and any tasks app.
- Reading the ✓ back from the calendar, or completing from the calendar.
- Reminders on the calendar event beyond what the calendar sets by default.
- Undoing a completion.
- A choice of kind inside the sheet.
