# Changelog

What changed in each release. The section for a version is written before the
release is cut — `tools/release.sh` refuses to publish without one and uses it
as the release notes on GitHub.

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
