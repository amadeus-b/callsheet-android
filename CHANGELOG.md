# Changelog

What changed in each release. The section for a version is written before the
release is cut — `tools/release.sh` refuses to publish without one and uses it
as the release notes on GitHub.

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
