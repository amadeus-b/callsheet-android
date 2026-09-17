# Changelog

What changed in each release. The section for a version is written before the
release is cut — `tools/release.sh` refuses to publish without one and uses it
as the release notes on GitHub.

## 1.7.0

- **Distance and driving time.** The list shows „Branche · Ort · 23 km · 21 min"
  for the main address, the detail view a line under every address. The
  values come from the server, computed once for every address with
  coordinates; an address without them shows nothing.
- **Changing an address clears its values**, as it clears the coordinates.
  An address added later has none.
- The first sync after the update fetches everything once more, so the values
  arrive on this phone.

## 1.6.0

- **New mail template.** The mail dialog starts from a revised text that
  links to the booking page and closes with „Mit bestem Gruß". The salutation
  reads „Guten Tag [Name],"; replace the placeholder before sending — the
  server refuses a mail that still contains it, and the dialog stays open
  with its message.
- **Standard wiederherstellen.** The mail template in the settings can be
  reset to the default of the installed version, after a confirmation. A
  template edited before keeps its text until then.
- **Mails go out as text and HTML.** The server adds the HTML part; the text
  is unchanged. Nothing to do in the app.
- **A visit deleted in the calendar is noticed, even if this phone never saw
  it there.** The detail view says „Im Kalender nicht mehr gefunden" once the
  entry has been missing for 30 minutes, and only if this phone has since
  received a visit confirmed later — proof that DAVx5 delivers the calendar
  here. A phone without the shared calendar never says it. **Termin
  entfernen** always asks first in this case.
- **Server abgleichen in the main view.** With a sync server set up, the top
  bar syncs with the server, like **Jetzt abgleichen** in the settings. It does
  not update the device calendar; DAVx5 still does that.
- The database moves to version 11 with two columns that stay on the phone.
  They start empty, so after the update no visit is reported missing until it
  has been confirmed again and found missing as described above.
- **Update the sync server first.**

## 1.5.1

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
  without attendees, after asking with them.
- **Teilnehmende instead of „Einladung senden".** A visit invites any number of
  people: the contact person's addresses and the business's own are one tap
  away, any other can be typed. Nothing is preselected.
- **Asked before mail goes out.** Saving a change to nothing but the list asks
  „Mail an … senden?" or „Absage an … senden?"; **Ohne Mail speichern** saves
  without mail. Moving the visit or changing its title or place notifies
  everybody on the list without asking.
- A visit invited in 1.5.0 keeps its invitee as the only attendee; no mail goes
  out through the update.
- **Update the sync server first, then every phone, and only then import
  again.** The first sync after the update fetches everything once. A phone
  still on 1.5.0 overwrites hand edits when it imports.

## 1.5.0

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
- **Visits go into the calendar through the sync server**, no longer through
  the phone's calendar. Each visit has a **Titel**, preset to „Erstgespräch KI
  bei <Betrieb> – Christoph Bauer".
- **Einladung senden**: a real calendar invitation from christoph@bauer-ki.de,
  with an update when the visit moves and a cancellation when it is removed.
  The invitee sees title, time and place, never the note. The address is one
  of the contact person's or the business's own — preset to the business's
  where there is no contact person, as with most imported businesses.
- Under each visit the detail view says whether it is in the calendar yet, who
  is invited, or what went wrong.
- An invited visit whose entry is gone from the calendar is not removed by
  itself: the detail view says „Im Kalender nicht mehr gefunden" and offers
  **Termin entfernen**.
- **Update the sync server first, with its Infomaniak token, then every phone.**
  Visits already in the calendar are test entries: delete them in the app and
  in the calendar before updating — required, or the server puts a second entry
  beside each of them.

## 1.4.0

- **Several appointments per business.** A site visit and then a meeting about
  the quote, or two at once with different people: each appointment has its own
  time, place, note and contact person, its own calendar entry, and its own
  **Ändern** and **Entfernen**. Past ones stay as a record under **Frühere
  Termine**.
- **Termine heute** lists appointments, not businesses. Two at one business are
  two rows, each with its note.
- **A shared calendar knows which entry belongs to which appointment on every
  phone.** Entries are linked by their calendar UID instead of one phone's entry
  number, so a second phone no longer writes a copy of an entry DAVx5 already
  brought over, and an appointment changed on one phone moves its entry on the
  others.
- **Removing an appointment** only puts the status back to „Angerufen" when no
  other appointment is still ahead. Removing a past one asks first.
- **Update the sync server first.** An older server ignores appointments; the
  app then keeps them as „offen" until it is updated rather than losing them.
- The first sync after the update fetches everything from the server once.
- **One phone book entry per contact person**, including the one from the
  imprint, each with the company name. A business without anybody gets a single
  entry under its company name — no longer split like a person's name.
- Every entry carries the address, the website, a map link and a note with
  industry, rating and research run. The main number is labelled
  „Hauptadresse“.
- „Branche“ instead of „Gewerk“ throughout the app.

## 1.3.1

- **The address opens the map on the point**, not on a search for the street.
  The coordinates from the import file were being stored and read by nothing;
  they now put the pin where the business is and give it a name. Businesses
  entered by hand, and anything imported before the columns existed, still fall
  back to searching for the address.
- **A contact card carries the route too**, labelled as the business's address —
  a contact has none of its own, and the app should not appear to hold a private
  one.
- One section for importing, at the foot of the settings, instead of two doing
  the same thing in different places.
- The synchronisation section looks like every other section instead of a card
  of its own, and the notes about a missing calendar or address book are no
  longer red: nothing has gone wrong and nothing is lost.
- **An upload shows how far it has come.** Determinate while there is something
  to send, indeterminate while only fetching — the server does not say in
  advance how much it holds, and a bar built on a number nobody has would be an
  invention.
- **Jetzt abgleichen** is back next to the connection, now that there is a bar
  to watch. **Alles erneut hochladen** stands beside it with the difference
  spelled out: the first exchanges what changed, the second queues the whole
  stock again and is for a restored server backup.

## 1.3.0

- **Date, start and end sit on one line in the appointment sheet**, each one a
  button into its own picker. The row of coming days it replaces was quick for
  the day after tomorrow and no use for anything else: a date three weeks out
  took the overflow button anyway, and the time of day could only be reached by
  dragging. Moving the start moves the appointment; it does not stretch it.
- **The sheet uses the full height of the screen.** It was capped, which left the
  day strip about two hours tall — a strip that short reads as an empty day
  rather than as a strip. The note about a missing calendar permission moved
  into the section heading, where it no longer costs three lines of the strip it
  is talking about.
- A proposed appointment time starts on a quarter hour, like every time the
  picker produces when dragged.
- **Every business with a number goes into the phone book**, not only the ones
  already called or hand-entered. Research material is exactly the stock most
  likely to ring first, and it used to come up as a bare number. Blocked
  businesses stay out — they always should have, and the filter that has now
  gone was the only thing keeping them out.
- **The server connection is set up in a dialog that tries it.** Two fields with
  a Save button looked the same whether the details were right or a digit was
  wrong. Now the attempt happens on saving: it goes through, or it says which of
  the two fields to fix. The section then shows the server and the last
  exchange instead of the fields.
- **The button to synchronise by hand is gone.** It happens every time the app
  comes to the front, and a button for it was only ever pressed by somebody who
  did not know that.
- **Importing moved to the foot of the settings**, behind a note saying what an
  import overwrites and what it never touches. It is a rare thing to do, and its
  circular arrow in the title bar had come to look like "synchronise".
- Appointments now reach the server: it gained the columns in a migration of its
  own, and it takes the columns of a table from the schema instead of a list
  kept beside it. A row that arrived before a new column existed gets that
  column filled in on the next upload, rather than being skipped as "not newer".

## 1.2.1

- The appointment sheet uses the full height of the screen. It was capped, which
  left the day strip about two hours tall — a strip that short reads as an empty
  day rather than as a strip.
- The note about a missing calendar permission moved into the section heading.
  It still has to be said, so that an empty strip is not mistaken for a free day,
  but it no longer costs three lines of the strip it is talking about.
- A proposed appointment time starts on a quarter hour, like every time the
  picker produces when dragged.

## 1.2.0

- **Appointment on site.** A visit now has a time, a length and an address of
  its own, instead of living in a status flag that said „Termin" and nothing
  more. Set in the detail view through a sheet that shows the day as a strip:
  what is already in your calendars is drawn in with its titles, so a slot that
  is taken is visible before it is booked over.
- The appointment can be **mirrored into the device's calendar** — into the
  calendar chosen in the settings, typically one DAVx5 keeps in sync. Off until
  switched on; without it, or without the permission, the appointment still
  lives in the app.
- **The calendar wins.** Move the entry on a laptop and the app takes the new
  time over the next time the record is opened. Delete it there and the
  appointment is cleared, with the status falling back to „Angerufen".
- **An appointment that already exists can be linked** rather than duplicated.
  The existing entry is left exactly as it was; the app only records that the
  two are the same thing.
- **"Heute"** shows the day's appointments above the follow-ups.
- **The address opens a map application**, in the appointment and in the master
  data.
- Appointments synchronise with the server like every other working field. The
  link to the calendar entry does not — it names an event on one device.
- Coordinates from the import file are stored. Nothing reads them yet.
- The database moves to version 3. Existing data is carried over, from 1.0.2 and
  from 1.1.0 alike.

## 1.1.0

- **Synchronisation with your own server.** The app can now keep its work in
  step with a small server of its own: status, notes, follow-ups, the call log
  and contacts travel in both directions. A lost phone no longer costs the
  work that was only on it.
- Synchronisation is **off until a server address is entered**. Without one the
  app behaves exactly as before — offline, on the device, without an account.
- The settings carry the server address, the access key, a button to sync now,
  the time of the last successful sync and the number of changes still waiting.
- "Alles erneut hochladen" sends the whole stock again — for a server that was
  restored from a backup, or a new one that has never seen this device.
- The server is a separate project and is not part of this repository.

## 1.0.2

- A business entered by hand is written to the phone book as soon as it is
  saved, provided it has a number. It no longer has to wait for a contact or a
  first call.
- "Alle bekannten Kontakte übertragen" now picks up hand-entered businesses
  with a number as well. The imported stock stays out: it is research material,
  not an address book.

## 1.0.1

- Replaced the launcher icon.

## 1.0.0

First release. Imports a business database from JSON, hands out one business at
a time to call, and records what came of it — offline, on the device, without an
account and without a server.
