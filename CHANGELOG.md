# Changelog

What changed in each release. The section for a version is written before the
release is cut — `tools/release.sh` refuses to publish without one and uses it
as the release notes on GitHub.

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
