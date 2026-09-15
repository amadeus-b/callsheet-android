# Package 2 — attendees instead of an invitation switch

Builds on `2026-09-15-visits-via-infomaniak-api-design.md` (visits through the
Infomaniak API) and on package 1
(`2026-09-15-paket1-fixes-nach-handytest-design.md`: schema 9, migration 011,
`Reconcile.MissingVisit`, `Appointment.removalAsks`). Ships with package 1 as
1.6.0.

## The problem

A visit has a switch „Einladung senden" and exactly one address,
`appointments.invite_email`. In the phone test, switching it off sent the
invitee Infomaniak's cancellation („Veranstaltung gelöscht") — correct, since
the invitation is withdrawn, but not what anyone expects from a switch. And a
visit with two people from the business, or a colleague, cannot be expressed at
all.

Calendar tools show a list of attendees instead: add, remove, and be asked
whether to send mail.

## Decisions (the user's)

1. **A list „Teilnehmende"** in the appointment sheet, for visits only. Addresses
   are added and removed; any number. Sources as today: the contact person's
   addresses, the business's email (marked „(Betrieb)"), free text. No switch.
2. **Nothing is preselected** on a new visit. The sources are offered as chips;
   a tap adds the address. Changing the contact person changes the chips only —
   the list stays as it is. (`inviteAfterContactChange` goes.)
3. **Asked on saving only when nothing but the list changed** — addresses added
   and/or removed, a new visit with attendees included:
   - only added: „Mail an a, b senden?"
   - only removed: „Absage an c senden?"
   - both: „Mail an a, b und Absage an c senden?"
   - more than three addresses in one group: „a, b und 2 weitere".
   - Buttons „Senden" and „Ohne Mail speichern". **„Ohne Mail speichern" saves
     all the same**: added or removed without mail. Tapping beside the dialog
     or Back cancels: back to the sheet, nothing saved.
4. **Title, time or place changed** — with or without a change to the list: no
   question, **everybody on the list is notified**, silently added ones too.
5. **No replies** (accepted, declined) are shown in this package.

### The limit of the API

`notifyAttendees` applies to one API call and the whole event, not to a person.
Hence, as discussed with the user:

- Somebody added without mail hears of the next change to title, time or place
  like everybody else.
- „Ohne Mail speichern" suppresses every mail of that save.
- Two saves that reach the server before it has worked the first (offline, or
  within seconds) are sent as one: the later answer decides for both.
- Removing a visit notifies everybody on the list (a cancellation), as today;
  the removal dialog names them.

## Data model

### `appointments`

```
attendees         TEXT     -- JSON array of addresses, e.g. ["a@example.org","b@example.org"]; NULL = nobody
attendees_notify  INTEGER  -- 1 | 0 | NULL: whether the last change to the list notifies
```

Both synchronised, app-owned, nullable. `invite_email` stays as a column on both
sides, is no longer read and no longer written (the pattern of the old address
columns).

- **`attendees`**: trimmed addresses in the order added, no address twice
  (ignoring case). An empty list is stored as NULL. Always NULL for a callback.
  The server leaves the organizer's own address out when it reads the list: the
  API adds the organizer itself.
- **`attendees_notify`** is written by the app **only in a save that changes the
  list**: 1 after „Senden" or where no question was asked (title, time or place
  changed too), 0 after „Ohne Mail speichern". A save that leaves the list alone
  leaves the stored value alone. Every change to the list is asked about or
  changes title, time or place, so the value on the row always belongs to the
  latest change to the list; an old 0 cannot silence a later one. NULL — rows
  from before this package — reads as 1.

### A column, not a table

A table `appointment_attendees` with a row per address would merge two devices
adding different people to the same visit. It is not chosen:

- Everything else about a visit — title, time, place — merges row-wise, newer
  wins. The list is part of what the invitee sees and is decided together with
  it; the notify decision belongs to the list as a whole.
- The server's queue works per appointment (`infomaniak_events`), and whether a
  save changed only the list is a comparison of one row with what Infomaniak
  last received. With a table it would span several rows arriving in any order,
  each with its own `updated_at`.
- One user, usually one phone: two devices changing the same visit's list before
  either syncs is rare, and then the newer list wins, as a newer time would.

### The two columns are one unit

A standstill (the same `updated_at`) fills gaps column by column, on the server
and in the app. For these two it must not: a list filled without its decision,
or a decision without its list, would pair values from different saves. So:
`attendees_notify` is filled only in the same fill as `attendees`, and then
taken from the incoming row whatever the local value.

### Migration

Server `012-attendees.sql`, app database version 9 → 10.

- `ALTER TABLE appointments ADD COLUMN attendees TEXT` and `attendees_notify
  INTEGER`.
- Every appointment with a non-blank `invite_email` gets
  `attendees = ["<invite_email trimmed>"]`. `attendees_notify` stays NULL.
- Server only: every `infomaniak_events.pushed` — what Infomaniak last received
  — is rewritten from `invite_email` to `attendees` (a one-element list, or an
  empty one). Otherwise the next comparison would see a changed list on every
  invited visit and send an update with mail.
- Nothing is marked, no `server_seq` moves: server and app carry over the same
  value, and the rows need not travel.
- App: `Preferences.refetchedForAttendees` fetches from watermark 0 once, as
  schemas 4, 6, 7 and 9 did. A phone still on the previous version stores visits
  created elsewhere with attendees, but without the column. When 1.6.0 ships
  packages 1 and 2 together, both flags are reset in the same first sync: one
  download.

## Server

### What a visit shows (`visitState.js`)

- `wantedState` carries `attendees` (the parsed list) instead of `invite_email`.
- `eventState` reads `attendees` as every attendee that is not the organizer —
  by flag or by address, as today.
- `sameState` compares title, time and place as today (`sameCore`) and the
  attendees as a set: trimmed, ignoring case, order irrelevant
  (`sameAttendees`).
- The create and update bodies list every attendee plus the organizer; with an
  empty list, no attendees at all.

### When to notify

A pure function `shouldNotify(wanted, held, pushed, flag)`:

- Nobody on the list now, in the event (`held`), or in what was last sent
  (`pushed`): no notification.
- Title, time or place differ from what Infomaniak holds: notify.
- Otherwise — only the list differs, or the event is being created:
  notify unless `attendees_notify` is 0.

`create` passes `held = null`: a new visit with attendees notifies unless the
answer was „Ohne Mail speichern". `update` reads the event first, as today, and
sends nothing when it already matches.

### Receiving

- `CALENDAR_COLUMNS` in `receive.js`: `attendees` instead of `invite_email`.
  `attendees_notify` alone queues nothing.
- `fillGaps`: `attendees_notify` only together with `attendees` (see above).

## App

### Rules (pure, tested)

`data/Attendees.kt`:

- `parse(text)`, `format(list)` — the JSON; blank and duplicate addresses
  dropped; an empty list is null.
- `contains`, `sameSet`, `added(before, after)`, `removed(before, after)` —
  ignoring case and whitespace.
- `add(list, typed)` — a typed or picked address: trimmed; blank changes
  nothing; not an address → „Das ist keine gültige E-Mail-Adresse."; already
  there → unchanged.
- `names(list)` — „a", „a, b", „a, b, c", „a, b und 2 weitere".

`calling/Appointment.kt`:

- `attendeeAddresses(contact, businessEmail)` — today's `inviteAddresses`,
  renamed: the contact person's addresses, then the business's.
- `attendeeQuestion(before, after, businessName)` — null, or the added and
  removed addresses: only for a visit, only when the list changed, and for an
  existing visit only when title, time and place did not.
- `attendeeQuestionTitle(question)` — the texts above.
- `attendeesNotifyToStore(before, after, question, send)` — null when the list
  did not change; the answer when asked; 1 otherwise.
- `calendarLine` — „Im Kalender · Teilnehmende: a, b" instead of
  „Eingeladen: …".
- `cancellationNotice` — „a bekommt eine Absage." / „a, b bekommen eine
  Absage."; `removalAsks` (package 1) keeps working through it.

Gone: `inviteError`, `inviteToStore`, `inviteSuggestion`,
`inviteAfterContactChange`, `INVALID_INVITE` (its text moves to `Attendees`).

### Sheet

Under „Ansprechpartner", for a visit:

- **Teilnehmende** — each address as a chip with a remove icon.
- Chips for the offered addresses not on the list yet („… (Betrieb)" for the
  business's); a tap adds.
- A text field with **Hinzufügen** (and the keyboard's Done). An invalid address
  shows the error under the field and stays in it.
- „Teilnehmende sehen Titel, Zeit und Ort, nicht die Notiz."

Saving a visit: an address still in the field is added first — or blocks saving
with the error. Then the slot conflict, as today. Then the question: when
`attendeeQuestion` gives one, the dialog opens and nothing is saved yet. „Senden"
and „Ohne Mail speichern" save with the answer; a slot already accepted with
„Trotzdem anlegen" stays accepted.

### Detail view

- „Im Kalender · Teilnehmende: a, b".
- Removing a visit with attendees: „a, b bekommen eine Absage. …" in the dialog;
  „Termin entfernen" under a missing visit asks exactly when there are attendees
  (package 1's `removalAsks`).

## Known behaviour at Infomaniak (for the README)

- Removing somebody from the list with mail: they get Infomaniak's cancellation,
  „Veranstaltung gelöscht", although the visit stays. That is how a calendar
  says „you are no longer invited".
- Removing the visit: everybody on the list gets „Veranstaltung gelöscht".
- Adding somebody with mail to an existing visit: Infomaniak sends the update to
  everybody on the list, not only to the new person — `notifyAttendees` is per
  call. To be checked on the phone and written down.

## Testing

### Server (`node:test`)

- Migration 012: columns; `invite_email` carried into `attendees`; blank
  `invite_email` gives NULL; `pushed` rewritten to `attendees`; after 012 a
  newer upload of an invited visit from the new app, changing only its note,
  queues no job.
- `visitState`: attendees parsed, trimmed, deduplicated, organizer dropped;
  `sameState` as a set; bodies list every attendee plus organizer; `eventState`
  reads all non-organizer attendees; `shouldNotify` table.
- `pushCalendar`: create with attendees notifies, with `attendees_notify = 0`
  does not; a list-only change with 0 → PUT with `notifyAttendees: false`; with 1
  or NULL → true; list and time changed with 0 → true; no attendees before or
  after → false.
- `receive`: a changed list queues an update; a changed `attendees_notify` alone
  queues nothing; a standstill fills `attendees` and `attendees_notify`
  together, and never `attendees_notify` alone.

### App (JUnit / Robolectric)

- `AttendeesTest` — parse, format, add, sameSet, added/removed, names.
- `AppointmentTest` — `attendeeQuestion` (new visit, list only, list with time /
  place / title, callback, unchanged list), question titles, notify value,
  calendar line, cancellation notice; the invitation tests removed.
- `RepositoryTest` — attendees and the notify value stored and read; a callback
  stores none; a save without a notify value keeps the stored one.
- `MigrationTest` — 9 → 10 carries `invite_email` over, marks nothing.
- `SyncSchemaTest`, `SyncStoreTest` (the pair at a standstill), `SyncEngineTest`
  (refetch).

### On the phone, once

With `test@example.org` and a second address of the tester's:

1. After the update, a visit invited in 1.5.0 shows „Teilnehmende:
   test@example.org"; no mail arrived through the update.
2. A new visit, add one address, save → „Mail an … senden?" → „Senden": the
   invitation arrives.
3. Add the second address → question → „Ohne Mail speichern": no mail to anyone.
4. Move the visit: both addresses get the update.
5. Remove the first address → „Absage an … senden?" → „Senden": note what
   arrives (expected „Veranstaltung gelöscht") and whether the second address
   hears anything.
6. Change time and list at once: no question, mail to everybody on the list.
7. Remove the visit: the dialog names the attendees; cancellations arrive.
8. Cancel the question with Back: the sheet stays open, nothing saved.

## Documentation

- `server/README.md` — the section on visits and invitations: attendees,
  `attendees_notify`, the known behaviour above.
- `server/docs/superpowers/specs/` — a pointer to this spec.
- `app/docs/data-model.md` — the two columns, `invite_email` no longer used,
  the migration.
- `app/docs/usage.md` — „Teilnehmende" instead of „Einladung senden".
- `app/CHANGELOG.md` — added to the 1.6.0 section.

## Rollout

Server (migrations 011 and 012) before the app. Every phone is updated the same
day: a phone on 1.5.0 still shows the switch, its changes to the invitation are
ignored by the server, and a visit it creates invites nobody.
