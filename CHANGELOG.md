# Changelog

What changed in each release. The section for a version is written before the
release is cut — `tools/release.sh` refuses to publish without one and uses it
as the release notes on GitHub.

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
