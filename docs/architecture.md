# Architecture

## Stance

The app gets used for hours at a stretch, often standing up, often on the move,
with the phone at an ear or on speaker. Every interaction counts: large targets,
few paths, no menu anything has to be hunted for in.

Three screens, no more.

## Screens

### Work list

The starting screen. Businesses one below the other; per row the company name in
large type, industry and city small underneath, the contact person if there is
one, and on the right a **large dial button** — the main handle of the whole app.

Above it a filter bar: industry (multiple selection), city, status, plus a
search field over name and city.

On opening it shows `is_target = 1` and status `new`, sorted by industry and
city, so alike businesses sit together. That is not a detail: whoever calls fifty
electricians in a row gets better with every call; whoever jumps between trades
starts over each time.

Businesses with status `do_not_call` **never** appear, whatever the filter says.

A counter shows how many businesses the current filter covers and how many of
them have been called today. Bottom right, a button opens the form for entering a
single business.

### Business

The detail view, reached by tapping a row:

- all master data, address, website tappable
- contacts with their numbers, each individually dialable
- a **note field**, writable directly
- a **follow-up** with quick choices („in 2 Tagen", „nächste Woche",
  „nächster Monat") and a free pick of date and time
- **status buttons** in a row, big enough for a thumb
- this business's **call history** with date, duration and note

Further down sits the provenance of the data — which research run, collected
when. That gets needed when somebody on the phone asks where the number came
from.

### Today

Follow-ups that are due, sorted by time — **including the overdue ones** from
previous days, at the top and set apart visually. Without them, missed call-backs
disappear silently, and those are exactly the ones that cost business.

## Call flow

The most important path through the app:

1. The dial button is tapped.
2. The app remembers the time and the number and fires an **`ACTION_DIAL`**
   intent. Deliberately `DIAL`, not `CALL`: the dialler shows the number ready to
   go and the user presses call himself. The app therefore never needs the right
   to place calls on its own.
3. The user has the conversation and returns to the app.
4. On `onResume` the app asks the call log (`CallLog.Calls`) for the most recent
   **outgoing** entry to that number after the remembered time and reads the
   duration. The entry sometimes appears with a delay, hence three attempts a
   second apart (see [Development](development.md)).
5. Result:
   - **Duration 0** → propose `no_answer`, plus a follow-up in two days at a
     **different time of day** than this attempt. Calling three times at ten
     o'clock gets the same result three times.
   - **Duration > 0** → the note field takes focus, the status choice is open.
6. An entry is written to `calls` and the business's `updated_at` is refreshed.

Every proposal can be overridden — even a duration of 0 can mean somebody picked
up and hung up straight away.

## Code layout

```
io.github.amadeusb.callsheet
├── data/       database, models, import, phone numbers, target rule
├── calling/    dialling, reading the call log, follow-up dates
├── contacts/   merging with the Android contacts
├── ui/         Compose screens and building blocks
└── CallsheetViewModel
```

The split is drawn so that the logic stays testable without an Android UI:
import, target rule, number normalisation, follow-up computation and contact
merging carry no Compose dependency.

## Technical decisions

- **Kotlin and Jetpack Compose**, `minSdk` 30.
- **SQLite directly** through `SQLiteOpenHelper` rather than Room. With a handful
  of tables Room saves little code and brings annotation processing along.
- **Filtering in the database, not in memory.** Loading everything and filtering
  in Kotlin turns noticeably sluggish at a few thousand businesses.
- **`org.json` from the framework** instead of a JSON library.
- **No library that does not pay for itself** — no dependency injection, no
  network stack, no analytics, no crash reporting.
- **German-language user interface.** The domain is German law and German
  callers; the UI strings and the values coming out of the import file stay
  German, everything else is English.
- Timestamps are ISO-8601 with a zone throughout; `updated_at` is written on
  every change to a working field.

## Ground rules

A handful of behaviours are wired into the app rather than left to discipline.
They are listed here because they explain code that would otherwise look
overcautious.

- **A block is enforced in the query, not the UI.** A business with status
  `do_not_call` is excluded in `Repository.condition()`, which every list goes
  through; only `blockedBusinesses()` returns them, so a mistake can be undone in
  the settings. A UI-level solution gets worked around eventually.
- **Selection stays traceable.** `industry` and `origin` are taken over by the
  import and shown in the detail view, reachable during a call rather than three
  menus deep. Hand-entered businesses get a free-text origin instead.
- **The log is append-only.** Entries in `calls` gain their outcome afterwards
  and are otherwise never modified or deleted.
- **Email is a follow-up channel.** An address is displayed and tappable; the
  status `email_promised` covers the case where somebody asked for material.
- **Outside business hours the work list shows a hint.** It blocks nothing; when
  calls happen is the caller's decision, and whether the caller ID is withheld
  is a SIM setting the app cannot see.

## Outlook: synchronisation

No server is built, but one is prepared for. The data model is laid out so it
could sit unchanged on a server: `updated_at` on every record, `calls` as a
pure append table with device-generated UUIDs.

That would allow a sync with last-write-wins at record level — calls are only
ever appended on both sides and deduplicated by UUID, so they can never collide.
The accepted trade-off is that the older change loses when the same business is
edited on both sides between two syncs; field-level resolution would be cleaner
but triples the logic.

The `INTERNET` permission in the manifest is reserved for that case and unused
today.
