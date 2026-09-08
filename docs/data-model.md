# Data model

## The import file

The app expects a JSON array of businesses, as produced by researching publicly
accessible directories and imprints.

Its field names are German because they come from the source file — they are
data, not identifiers, and the importer reads them verbatim.

The file holds the names and addresses of sole traders. It belongs neither in
this repository nor in the APK; the app reads it at runtime through the file
picker.

### Fields

| Field | Meaning |
|---|---|
| `placeId` | Stable key from the map service. **Primary key, the basis of every duplicate check.** |
| `title` | Company name |
| `gewerk` | Pre-classified industry, roughly two dozen values. May be null. |
| `categoryName` | The source's own category — unreliable, use only as a fallback |
| `categories`, `alle_kategorien` | Every category ever seen. The source returns different ones depending on the search context, so it collects them all. |
| `street`, `city`, `postalCode`, `state`, `countryCode` | Address |
| `phone`, `phoneUnformatted` | Phone. Not present on every business. |
| `emails` | Array — from the imprint, often empty |
| `kontakt`, `kontakt_quelle` | Contact person, often empty |
| `impressum`, `impressum_datei` | Pointer to the full imprint text |
| `herkunft` | Array of research runs. **The basis of the selection rationale, take it over without fail.** |
| `totalScore`, `reviewsCount` | Rating from the map service |
| `location` | `{lat, lng}` |
| `permanentlyClosed`, `temporarilyClosed` | Closed businesses |
| `kontakt_versucht` | Metadata from the imprint crawler, **not** "has been called" |
| `social`, `weitere_mails`, `quelle_mail` | Rarely present, irrelevant to the app |

### Industries and the target set

The industries lean towards trades and mid-sized businesses: vehicle repair,
facility management, electrical, plumbing and heating, landscaping,
construction, metalwork, painting and others.

Industries carrying the suffix **`(kein Ziel)`** — "no target" — are explicitly
out of scope, for instance `Paketdienst (kein Ziel)` or
`Immobilien (kein Ziel)`. What matters is the **suffix**, not a fixed list, so
the rule also catches values added later. It lives in exactly one place
(`data/TargetRule.kt`) so that import and manual entry cannot drift apart.

A business belongs to the callable target set when it carries no `(kein Ziel)`
industry, has a phone number and is not closed.

Businesses **without** an industry are not excluded; they stay reachable through
their own filter and can be reviewed.

## Tables

Two core tables, laid out the way the server holds them, so a row travels
between the two without translation.

### `businesses`

Master data, overwritten by every import:

```
place_id        TEXT PRIMARY KEY
name            TEXT NOT NULL
industry        TEXT            -- may be null
categories      TEXT            -- JSON array as text
street, postal_code, city  TEXT
phone           TEXT            -- normalised, E.164 (+49…)
website, email  TEXT
contact_name    TEXT
rating          REAL
rating_count    INTEGER
closed          INTEGER         -- 0/1
is_target       INTEGER         -- 0/1, computed on import
origin          TEXT            -- JSON array as text
collected_at    TEXT            -- ISO-8601
```

Working fields, **never overwritten by an import**:

```
status          TEXT NOT NULL DEFAULT 'new'
note            TEXT
follow_up_at    TEXT            -- ISO-8601 with a time, not just a date
updated_at      TEXT NOT NULL   -- ISO-8601
dirty           INTEGER NOT NULL DEFAULT 0   -- 1 while a change is waiting to sync
```

`is_target` = 1 when the industry does **not** end in `(kein Ziel)` **and** a
phone number is present **and** the business is not closed.

There is also a `search_text` column: SQLite only lower-cases ASCII on its own;
without it, searching for „müller" would not find „Müller".

### `status` — allowed values

| Value | Meaning |
|---|---|
| `new` | not called yet |
| `called` | spoke to somebody, no clear result |
| `no_answer` | nobody picked up |
| `email_promised` | material promised by email |
| `appointment` | appointment agreed |
| `declined` | not interested |
| `do_not_call` | **excluded from every query — see [Architecture](architecture.md#ground-rules)** |

### `calls`

A business's log. Entries are appended and only ever gain the outcome filled in
afterwards; time and duration stay as recorded.

```
id                TEXT PRIMARY KEY   -- UUID, generated on the device
place_id          TEXT NOT NULL
started_at        TEXT NOT NULL      -- ISO-8601
duration_seconds  INTEGER NOT NULL   -- 0 = never connected
outcome           TEXT               -- the status set afterwards
note              TEXT
kind              TEXT NOT NULL      -- 'call' | 'note' (no dial attempt)
contact           TEXT               -- who was called, "Frau Meier · Mobil"
updated_at        TEXT               -- ISO-8601, set once the outcome is recorded
dirty             INTEGER NOT NULL DEFAULT 0
```

### `contacts` and `contact_numbers`

These only ever come into being inside the app — an import never touches them.
The `id` is a UUID and stays stable: it is the `SOURCE_ID` of the phone book
entry and later doubles as the vCard UID.

```
contacts
id               TEXT PRIMARY KEY
place_id         TEXT NOT NULL
name             TEXT NOT NULL
role             TEXT
email            TEXT
note             TEXT
position         INTEGER NOT NULL
updated_at       TEXT NOT NULL
dirty            INTEGER NOT NULL DEFAULT 0
contact_version  INTEGER            -- RawContacts.VERSION at the last merge

contact_numbers
id          TEXT PRIMARY KEY
contact_id  TEXT NOT NULL
number      TEXT NOT NULL           -- E.164
kind        TEXT NOT NULL           -- mobile | work | main | home | fax | other
position    INTEGER NOT NULL
updated_at  TEXT
dirty       INTEGER NOT NULL DEFAULT 0
```

The imported `businesses.contact_name` field stays alongside them: it is master
data from the research and the import keeps maintaining it.

### `deletions`

A tombstone table, so a deletion made on one side does not come back with the
next sync. Contacts and their numbers are the only rows the app ever deletes.

```
table_name  TEXT NOT NULL
row_id      TEXT NOT NULL
deleted_at  TEXT NOT NULL           -- ISO-8601
PRIMARY KEY (table_name, row_id)
```

## Synchronisation

`dirty` marks a row as changed since the last successful sync; it is cleared
only once the server has confirmed the exact version that was sent, so a row
touched again while a request is in flight stays marked. Wherever a timestamp
decides which side wins — `updated_at` on a row, `deleted_at` on a tombstone —
it is compared as an instant in time, never as a string: two equivalent
timestamps written with a different offset or format must resolve the same
way.

## Phone book

So that a call back has a name attached, the app stores contacts and called
businesses in the device's phone book — through the Android contacts API, into
the address book account chosen in the settings. When that is a CardDAV address
book managed by DAVx5, the entries reach the server the same way; the app itself
speaks **no** CardDAV and needs no credentials.

- The app recognises its own entries by `RawContacts.SOURCE_ID`: it holds the
  contact's id, or the business's `place_id`. Other people's contacts are never
  touched.
- Writing happens when a contact is saved and after the first call to a business.
- The other direction: opening a record reads the app's own entries back. If
  `RawContacts.VERSION` has moved on, the phone book wins for name, email and
  numbers. Role and note stay as they are in the app.
- People newly created in the phone book are **not** pulled in — they have no
  link to a business.

## Import

Importing goes through the Android file picker (Storage Access Framework), so no
file access permission is needed and a fresh research export can be loaded
without a new build.

1. Matching happens on `place_id`.
2. New businesses are created with `status = 'new'`.
3. Known businesses: **master data only.** Status, note, follow-up and call
   history stay untouched. A fresh export must never overwrite the work.
4. Phone numbers are normalised: `phoneUnformatted` preferred, brought to E.164
   (`+49…`). No number → `is_target = 0`.
5. A summary follows: how many new, how many updated, how many without a phone.

Hand-entered businesses get an id carrying the `manual:` prefix. A later import
can therefore never hit one of them and never overwrite it.
