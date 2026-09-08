# Usage

How the app is used day to day. Installation is covered in the
[README](../README.md).

The user interface is German throughout; the labels quoted below are what appears
on screen.

## First start

1. The app asks once for access to the **call log**. That is how it reads the
   duration after hanging up. If the permission is refused, everything still
   works — the status is then set by hand. It does not ask again.
2. Put the import file on the device; the download folder will do.
3. Pick the file through the import button. After reading it, the app reports how
   many businesses are new, how many were updated and how many have no phone
   number.

Importing the same or an extended file later is safe: status, note, follow-up and
call history stay untouched, only the master data is refreshed.

## Before the first call

Two things the app cannot enforce:

- **Do not withhold the caller ID.** The corresponding SIM setting has to be
  off; the app cannot check it.
- **Call during ordinary business hours only.** Outside them the app shows an
  unobtrusive hint. Not a prohibition, just a guard against slips late in the
  evening.

## The call flow

1. Press the dial button. The dialler opens with the number ready to go — you
   press **call** yourself. The app never calls on its own.
2. After hanging up, switch back to the app. It reads the duration from the call
   log and proposes an outcome.
3. **Duration 0** → proposes „nicht erreicht" plus a follow-up in two days at a
   different time of day. Every proposal can be overridden: somebody may well
   have picked up and hung up immediately.
4. **Duration above 0** → the note field takes focus, the status is set by hand.

When a business has several numbers — its own and its contacts' — dialling asks
which one. Fax numbers are left out. The log then records who was called.

## Follow-ups

The **Heute** screen lists everything due, sorted by time, **including the
overdue ones** from previous days. Those sit at the top and are set apart —
missed call-backs should not disappear silently.

In the detail view a follow-up can be set through the quick choices („in 2
Tagen", „nächste Woche", „nächster Monat") or by picking a date and time.

## Entering a single business by hand

The **Betrieb** button at the bottom right of the work list opens an entry form —
for the referral over the phone or the business card from a trade fair. No detour
through the import file.

Only the name is mandatory. Without a phone number the business is still saved
but does not appear in the default work list, which shows only businesses with a
number. It stays reachable through the „nur Ziele" filter.

The form offers the industries and cities already present as chips, so the
spelling matches the imported businesses. Free text remains possible.

The **Herkunft** field matters: for every business it has to stay traceable
where the contact came from. For imported businesses the research run provides
that; for a hand-entered one only this note does.

Two things the app catches: an incomplete phone number is rejected, and the same
number cannot be created twice — the message then names the business that already
holds it. That prevents calling somebody twice.

Hand-entered businesses get an id carrying the `manual:` prefix. A later import
can therefore never hit one of them and never overwrite it.

## Contacts

Every business can carry contacts with a role, an email address and several
numbers. They go into the device's phone book, into the address book account
chosen in the settings — so a call back has a name attached.

If such a contact is edited in the phone book, the app takes over name, email and
numbers the next time the record is opened. Role and note stay as they are in the
app. Contacts that did not originate in the app are never touched.

## Blocking a business

If somebody says on the phone „rufen Sie hier nie wieder an", that is an
objection. The **Sperre** button in the detail view enforces it: the business
appears in no list afterwards — not in the work list, not in the search, not in
„Heute", not even with the filters cleared.

A mistake can be taken back in the **settings**, which list every blocked
business.

## Provenance of the data

If somebody on the phone asks where the number came from, the answer sits further
down in the detail view: which research run the data came from and when it was
collected.
