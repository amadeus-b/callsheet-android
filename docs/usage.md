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
3. Open **Einstellungen**, scroll to the bottom to **Betriebe importieren**, and
   pick the file. The app says first what an import does and does not touch;
   after reading it, it reports how many businesses are new, how many were
   updated and how many have no phone number.

Importing the same or an extended file later is safe: status, note, callbacks,
appointments and call history stay untouched, only the master data is refreshed.

## Synchronising with a server

Optional, and off until an address is entered. Under **Abgleich**, **Server
verbinden** asks for the address and the access key, then tries them at once:
either it goes through and the section shows what was last exchanged, or it says
what went wrong — a rejected key and an address that answers nothing read
differently, so the message names which of the two to fix.

There is no button to synchronise by hand. It happens every time the app comes
to the front.

**Alles erneut hochladen** sends this device's entire stock again and starts
reading the server from the beginning. It is for after a restored server backup
or a move to a different server, not for everyday use.

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
3. **Duration 0** → proposes „nicht erreicht" plus a callback in two days at a
   different time of day. Every proposal can be overridden: somebody may well
   have picked up and hung up immediately.
4. **Duration above 0** → the note field takes focus, the status is set by hand.

A call placed from the app completes every open callback of that business that
was due by the end of today — whether anybody answered or not. The calendar
entry keeps its place and gets a „✓" in front of its title.

When a business has several numbers — its own and its contacts' — dialling asks
which one. Fax numbers are left out. The log then records who was called.

## Callbacks

A callback („Rückruf") is an appointment to ring a business again. The detail
view lists them under **Wiedervorlage**: the open ones with their time, note and
contact person, an overdue one in red, each with **Ändern** and **Entfernen**.
Completed callbacks stay as a record under **Erledigte Rückrufe**, collapsed.

„in 2 Tagen", „nächste Woche", „nächster Monat" and **Datum & Uhrzeit …** open
the same sheet as an appointment on site, already set to that time: 15 minutes,
no place. Add a note — „wegen Angebot nachfragen" — and the person to ask for,
then **Rückruf speichern**. After an unanswered call the suggestion card does
the same with **Übernehmen**. A business can have several callbacks.

Saved with the calendar switched on, a callback is written into the calendar as
„Rückruf <business>", the same way an appointment is. A callback never changes
the business's status.

## The agenda

The calendar button at the top of the work list opens **Termine**: first the
overdue callbacks — set apart, from whichever day, so a missed callback does not
disappear silently — then today, with today's appointments on site (past ones
included) and the callbacks still ahead, then every later day that has something
on it. Each row says „Rückruf" or „Vor Ort", the time and the note. Completed
callbacks and appointments from earlier days are not listed.

## Appointments on site

When a call ends in a visit, the detail view's **Termin vor Ort** section is
where it goes. A business can have as many as the work needs — a site visit and
then a meeting about the quote, or two at once with different people. Each one
shows its time, note, contact person and place, with **Ändern** and
**Entfernen** of its own. Past appointments stay as a record under **Frühere
Termine**, collapsed. „Termin anlegen" is always there and opens a sheet over
the record, so the business stays readable while the conversation is still
running.

The sheet shows the day as a strip from midnight to midnight, opened around the
time being set. Everything already in your calendars is drawn in with its title,
so a slot that is taken is visible rather than discovered later. Drag the block
to move it, drag the handle at its bottom edge to change its length; both snap
to quarter hours. The chips underneath set 30, 60, 90 or 120 minutes in one tap,
and whichever length you save is what the next appointment starts at.

**Titel** is what the calendar entry is called, preset to „Erstgespräch KI bei
<Betrieb> – Christoph Bauer". Left empty, the preset is used.

The location is prefilled with the address of the chosen contact person — the
one they are assigned to, else the business's main address — and moves along
when you pick another person, until you type a place or tap one of the address
chips below the field. It is what the calendar entry carries, so it is what the
navigation reads.

**Notiz** says what the appointment is for — „Besichtigung", „Angebot". It stays
in the app. **Ansprechpartner** picks who to ask for on site.

**Einladung senden** invites somebody: pick one of the contact person's
addresses or type one; picking another person brings their first address along
unless you chose one yourself. The invitation comes from christoph@bauer-ki.de;
moving the visit sends an update, removing it a cancellation. The invitee sees
title, time and place — never the note. Changing only the note sends nothing.

**When the slot is already taken** the app says what is there and asks, instead
of writing a second entry on top:

- **Verknüpfen** — for a callback only: this is that appointment. The existing
  entry is left exactly as it is, and the callback is linked to it; its time
  wins. An entry another appointment already holds is not offered.
- **Trotzdem anlegen** — two things at once, deliberately.
- **Andere Zeit** — back to the strip.

Saving writes the appointment and sets the status to „Termin" if it is still
ahead — offline too. The sync server then puts it into the Infomaniak calendar,
whatever the calendar switch in the settings says; DAVx5 brings the entry onto
the phones. Under the appointment the detail view says where it stands:
„Wird im Kalender angelegt …", „Im Kalender · Eingeladen: <Adresse>", or what
went wrong, in red. Without a sync server a visit does not reach the calendar.
Without calendar permission the strip says so rather than pretending the day is
free.

**Moving it in the calendar is enough.** Shift the entry in the web calendar or
on a laptop, and the app takes the new time, place and title over the next time
the record is opened, without asking — once the server has put the latest
version into the calendar, and once this phone has seen the entry before: an
entry seen for the first time is only taken note of when it matches. Delete the
entry in the calendar and the appointment is removed — said out loud, because
the status falls back to „Angerufen" once no other appointment is ahead. Not so
with an invitation: the detail view says „Im Kalender nicht mehr gefunden" and
offers **Termin entfernen**, which asks first, as the invitee would get a
cancellation. If the entry turns up again, the note goes. A past entry that a
calendar clears out on its own leaves the appointment in place.

**Entfernen** asks first, for a past appointment too: it removes a piece of the
record. With an invitation it names who gets the cancellation.

Tapping an address, in the appointment or in the master data, hands it to a map
application.

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

## Addresses

A business can have several addresses — head office, branch, yard. **Adressen
bearbeiten** in the detail view opens them; the first is the main address, and
**Als Hauptadresse** moves another one to the top. A label is optional, with
„Hauptsitz", „Filiale", „Lager" and „Baustelle" one tap away. The import keeps
the imported address up to date and leaves the others alone; an imported address
removed by hand stays removed.

Once a business has two addresses, a contact person can be assigned to one under
**Standort**. Their phone book entry then carries only that address, their route
leads there, and a visit with them starts there.

## Contacts

Every business can carry contacts with a role, an email address and several
numbers. The business goes into the device's phone book, into the address book
account chosen in the settings — so a call back has a name attached. There is one
entry per contact person, including the one from the imprint, each with the
company name, the main number („Hauptadresse“), the addresses — only their own
for a person assigned to one, each under its label —, website, a map link and a
note. A business without any contact person gets a single entry under its
company name.

If a contact saved in the app is edited in the phone book, the app takes over
name, email and numbers the next time the record is opened. Role and note stay as
they are in the app. Everything else is written anew the next time the business
is transferred. Contacts that did not originate in the app are never touched.

## Blocking a business

If somebody says on the phone „rufen Sie hier nie wieder an", that is an
objection. The **Sperre** button in the detail view enforces it: the business
appears in no list afterwards — not in the work list, not in the search, not in
„Termine", not even with the filters cleared.

A mistake can be taken back in the **settings**, which list every blocked
business.

## Provenance of the data

If somebody on the phone asks where the number came from, the answer sits further
down in the detail view: which research run the data came from and when it was
collected.
