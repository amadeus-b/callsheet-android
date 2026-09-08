# Appointments and the calendar

## The problem

The point of a call is an appointment on site. The app can record that one was
agreed — `status = 'appointment'` — but not *when* it is, and not *where*.

The only date a business carries is `follow_up_at`, and that field means
something else: "ring again". An appointment means "be at this address at this
time". Putting both in one column makes them indistinguishable, and "Today"
cannot tell an overdue callback from a missed appointment.

Appointments also exist outside the app. One is entered on a laptop, another
during a call, a third gets moved the evening before. Whatever the app writes
has to survive that without producing a second copy of the same appointment.

## Approach

The device already runs a calendar, and where that calendar is a CalDAV one
kept by DAVx5, appointments reach the server without the app knowing any of it.
This is the arrangement the phone book already uses: the app writes rows into
`ContactsContract` and speaks no CardDAV itself.

Calendars work the same way, one provider along. The app writes into
`CalendarContract`, into a calendar picked in the settings, and reads back from
`CalendarContract.Instances`. It implements no protocol and holds no
credentials.

Ownership is split down the middle:

- **The app owns the appointment's connection to a business** — which business,
  at which address, arising from which call.
- **The calendar owns the time** — because that is where the appointment gets
  moved, and because a calendar the app cannot see is the one that produces
  double bookings.

## Data model

Four working fields on `businesses`, alongside `status`, `note` and
`follow_up_at`. Like those, an import never overwrites them:

```
appointment_at        TEXT     -- ISO-8601 with a time and a zone
appointment_end_at    TEXT     -- stored, not derived: the calendar may change it
appointment_location  TEXT     -- prefilled from the address, editable
calendar_event_id     INTEGER  -- the linked event, null while none exists
```

Two master data fields, filled by the import like every other imported column:

```
latitude   REAL
longitude  REAL
```

The import file carries coordinates on every business and the importer currently
drops them. They are picked up now because the migration is free today and
because an appointment's location is worth more to a map application as a
coordinate than as a string. Nothing in this design reads them yet.

`follow_up_at` keeps its meaning untouched. `appointment_at` is the new one.
Setting an appointment sets `status = 'appointment'`; clearing it puts the
status back to `called`.

### Migration

This is the first migration the app has had. `Database.VERSION` goes from 1 to
2 and `onUpgrade` gains six `ALTER TABLE ADD COLUMN` statements. Existing rows
get `null` in all six. The rule the empty `onUpgrade` already states holds:
never discard working data, only add to it.

## The calendar package

A `calendar/` package next to `contacts/`, cut the same way.

**`CalendarStore.kt`** — write an event, read it back, link an existing one.
An event is a single row in `CalendarContract.Events`, so this stays well under
the size of `PhoneBook.kt`, which has to reassemble a contact from `RawContacts`
and `Data`.

**`BusyTimes.kt`** — one query against `CalendarContract.Instances` for one day,
returning the occupied intervals with their titles.

**`Preferences.kt`** gains `calendarEnabled` and `calendarId`, next to the
existing phone book settings.

**`Settings.kt`** gains a calendar picker beside the address book picker, with
the same shape and the same pointer to DAVx5.

`READ_CALENDAR` and `WRITE_CALENDAR` are both optional. Refused, an appointment
still gets recorded — it then lives in the app alone, the way a refused call log
permission leaves the status to be set by hand.

## Setting an appointment

The picker shows the chosen day as a strip from 8 to 18, with the occupied times
shaded and labelled. No month grid: the decision being made is about one day.

Busy times come from **every visible calendar**, not only the one being written
to. A private appointment that is invisible here is exactly the one an on-site
visit gets booked over.

The location field is prefilled from street, postal code and city, and stays
editable for the times the meeting is somewhere else.

## Avoiding a second copy

Before writing, the app looks for an event in the target calendar overlapping
the chosen window. If it finds one, it asks rather than adding a second row:

> Link · Create anyway · Pick another time

**Link** stores that event's id on the business. From then on the two are the
same appointment, whatever either side does to it.

## Reading back

Opening a business that carries a `calendar_event_id` re-reads the event. This
is the rule `ContactMerge` already applies to the phone book, in the same
direction:

| In the calendar | In the app |
|---|---|
| moved | `appointment_at` follows, silently |
| location changed | `appointment_location` follows |
| deleted | link cleared, status back to `called`, shown as a notice |

Deletion is the only case that interrupts, because it is the only one that needs
a decision.

## The address

The address is imported and shown, and does nothing. Three small changes make it
work:

- The address row in the detail view becomes tappable and fires a `geo:` intent
  carrying the coordinates and the address as a query, so whichever map
  application is installed can take it. `DataRow` already accepts an `onClick`.
- The work list row shows the street as well as the city.
- `appointment_location` goes into `EVENT_LOCATION`, so navigation starts from
  the calendar entry.

## Today

"Today" gains a third group, **appointments today**, above the follow-ups. It
reads `appointment_at` from the database and issues no calendar query — the
appointments the app knows about are the ones it linked, and those are already
current from the read-back.

## Testing

- `FollowUp`'s working-day rules are already covered; the appointment times need
  the opposite guarantee — an appointment is taken exactly as entered, including
  a Saturday, and is never corrected into a working day.
- Migration from a version 1 database: working fields survive, the six columns
  arrive empty.
- An import over a business with an appointment leaves all four appointment
  fields alone and updates the coordinates.
- Overlap detection against a set of busy intervals, including back-to-back
  appointments, which do not overlap.
- Read-back: moved, relocated, deleted, and the event id pointing at nothing.

Tests run under Robolectric, as `RepositoryTest` already does. Overlap
detection and the read-back decision are kept as pure functions in
`BusyTimes.kt` so they can be tested without a provider at all — the split
`ContactMergeTest` already relies on.

## Out of scope

Sorting the work list by distance and ordering a day's appointments into a route
both need the coordinates this design imports, but they need a location
permission and a screen of their own. They are not part of this.
