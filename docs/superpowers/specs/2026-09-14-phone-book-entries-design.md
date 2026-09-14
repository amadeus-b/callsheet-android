# Phone book entries per person

## The problem

The app writes a business into the phone book as a single entry, and what
arrives in the CardDAV address book (Infomaniak, through DAVx5) is thin and
partly wrong:

- **The name gets split.** The app writes only `StructuredName.DISPLAY_NAME`.
  Android takes „Beispiel Fliesen Ingolstadt“ for a person and splits it into
  given name „Beispiel“, middle name „Fliesen“, family name „Ingolstadt“. The
  address book shows „Beispiel Ingolstadt“.
- **Most of what the app knows is missing.** `PhoneBook` writes name, numbers,
  email, organisation/title and note. Address and website have no row type at
  all, and a business's note is always `null`. Industry, rating and the research
  run never leave the app.
- **The imported contact person goes nowhere.** `businesses.contact_name`
  („kontakt“ from the imprint) is a text field on the business. Only people saved
  by hand in `contacts` get an entry of their own.
- **The main number reads „voice“.** The app hands Android `Phone.TYPE_MAIN`;
  DAVx5 (`vcard4android`, `PhoneHandler`) turns that into `TEL;TYPE=VOICE`. The
  address book calls a main number „Hauptadresse“, which is `TEL;TYPE=MAIN` —
  and no Android phone type makes DAVx5 write that.

## How the address book is meant to look

One entry per person. The company name is on every one of them.

| The business has | Entries |
|---|---|
| no contact person | one: no given or family name, organisation = company. The address book shows the company name. |
| one contact person | one: that person's name, organisation = company. No extra company-only entry. |
| further contact persons | one more entry each, organisation = the same company |

Contact persons come from two sources, and both count:

- **Imported** — `businesses.contact_name`.
- **Saved by hand** — rows in `contacts`, in `position` order.

A hand-saved person with the same name as the imported one (compared trimmed,
case-insensitive) counts once, as the hand-saved one. That is the person the user
has worked on; the imported name adds nothing to it.

The rollout starts clean: app data and the address book are emptied and the
business file is imported again. No existing entry has to be carried over or
cleaned up. Emptying the app alone is not enough — the next sync would bring
businesses, contacts and calls back from the server — so the server database is
emptied too, backed up first. That step is done by hand and is not part of this
change.

## Entry identity

The app recognises its own entries by `RawContacts.SOURCE_ID`, as today.

| Entry | `SOURCE_ID` |
|---|---|
| the company-only entry, or the imported person | `place_id` |
| a hand-saved person | `contacts.id` |

So the `place_id` entry is always a business's first entry: while there is an
imported person it carries that person, otherwise it is the company-only entry.
Only one case removes it — the business has hand-saved persons but no (distinct)
imported one. When the last hand-saved person is deleted, it comes back.

Rejected: a separate `SOURCE_ID` for the imported person (`<place_id>#kontakt`)
with the company-only entry deleted as soon as any person exists. It has one
more state to reconcile and nothing to gain from it.

## What every entry holds

| Field | Value |
|---|---|
| Name | Person entries: the last whitespace-separated word as family name, the rest as given name. A single word goes into the family name. Company-only entry: **no `StructuredName` row at all.** |
| Organisation | company name (`businesses.name`) |
| Title | the person's role; otherwise the industry. Company-only entry: the industry. |
| Phone | the person's own numbers with their own types, then the business's number with `TYPE_CUSTOM` and label **„Hauptadresse“**. A business number that equals one of the person's own numbers (last nine digits) is not written twice. |
| Email | the person's email; otherwise the business's |
| Address | `StructuredPostal`, `TYPE_WORK`: street, postal code, city, country „Deutschland“. Left out when street, postal code and city are all empty. |
| Website | `Website`, `TYPE_WORK`, the business's website |
| Map link | a second `Website` row, `TYPE_OTHER`. Imported business: `https://www.google.com/maps/search/?api=1&query=<name>&query_place_id=<place_id>`. Hand-entered business (`manual:` prefix): `https://www.google.com/maps/search/?api=1&query=<address>`; none without an address. |
| Note | one line from the parts that exist, joined with „ · “: `Branche: Bau · Bewertung: 5,0 (2) · Herkunft: handwerk-in`. A hand-saved person's own note goes in front, on its own line. |

Why no `StructuredName` on the company-only entry: DAVx5 fills the required `FN`
from the organisation when there is no display name
(`ContactWriter.addFormattedName`) and writes no `N`. Android does the same for
its display name. Both then show the company.

Why a label rather than a type for the main number: DAVx5 writes a labelled
number as `itemN.TEL` with `itemN.X-ABLabel:Hauptadresse`. Whether Infomaniak
shows that label as its „Hauptadresse“ type is not known yet; the manual check
below settles it. If it does not, the business number switches to `TYPE_WORK`
(„Geschäftlich“) — a one-line change in one place.

Rows the app does not manage (birthdays, groups, photos added elsewhere) stay
untouched, as today. `StructuredPostal` and `Website` join the managed row types,
so an address or website added by hand to one of the app's entries is replaced
on the next write.

## When entries are written

`ContactStore.persistBusiness(business)` becomes the one place that works out a
business's entries: it computes the target set, writes each entry, records the
version of every hand-saved person's entry (`setContactVersion`) and deletes the
`place_id` entry when it is not part of the set.

It runs where business or contact entries are written today, plus once more:

- after a call;
- after a business is created by hand;
- after a contact is saved;
- **after a contact is deleted** — new, so the company-only entry comes back
  when the last person goes;
- for every business from **Alle Betriebe mit Nummer ins Telefonbuch**.

`persistContact` and the separate loop over contacts in `pushAllToPhoneBook`
fold into it. Deleting a contact still removes that contact's own entry first.

Unchanged: nothing is written while the feature is off or no address book is
picked. A business set to „Nicht anrufen“ is left out of **Alle Betriebe mit
Nummer ins Telefonbuch** and its existing entries stay where they are.

Hand-saved persons of a business without a main number are still written, with
their own numbers only. The company-only entry and the imported person need a
main number, as the company entry does today.

## Reading back

Only hand-saved persons are read back, as today: name, email and numbers, when
`RawContacts.VERSION` has moved. Changes to the `place_id` entry are never read
back; the app wins on the next write.

Three rules are new, all in `ContactMerge` so they stay testable without Android:

- **The business number is dropped** from the numbers read back — unless the
  person holds the same number in the app as well.
- **The business email is not taken over** when the person has no email of their
  own and the phone book holds exactly the business's.
- **The name is the display name**, as today. Android and DAVx5 build it from
  given and family name, so „Max Mustermann“ comes back as „Max Mustermann“.

## Naming: „Branche“ instead of „Gewerk“

The app is not about trades only. „Gewerk“ goes wherever a user reads it:

- the filter in the work list (`WorkList.kt`, two places),
- the form for a hand-entered business (`BusinessForm.kt`),
- „Auswahl begründet über Gewerk“ in the detail view (`BusinessDetail.kt`),
- the note written into the phone book.

The import file's field stays `gewerk`: it is data from the research export and
the importer reads it verbatim.

## Errors

- Missing permission or no address book: nothing happens, as today. Working in
  the app never depends on the phone book.
- One business failing during **Alle Betriebe mit Nummer ins Telefonbuch** does
  not stop the run; the next business is written.
- A business name without a letter („.“) is written as it is. The user corrects
  such names in the source.

## Testing

Robolectric, with made-up names and numbers only.

- **Target set:** no person; imported person only; imported plus hand-saved;
  hand-saved only; imported name equal to a hand-saved one; business without a
  main number.
- **Fields:** name split (two words, three words, one word); no `StructuredName`
  on the company-only entry; address left out when empty; map link for an
  imported and a hand-entered business; note with missing rating, missing
  industry, and a person's own note in front; label „Hauptadresse“; business
  number not written twice.
- **Reading back:** business number dropped; a person's own identical number
  kept; business email not taken over; an unchanged entry yields no draft.
- **Deleting the last hand-saved person** brings the `place_id` entry back.

Manual check on the phone, not automatable:

1. Install the new version.
2. Empty the app, the server database (backed up first) and the „Firmen“
   address book.
3. Import the business file, run **Alle Betriebe mit Nummer ins Telefonbuch**,
   let DAVx5 synchronise.
4. In Infomaniak, check one business without a contact person, one with an
   imported person, and one with an added hand-saved person: name, organisation,
   address, website, map link, note, and whether the main number shows as
   „Hauptadresse“.
