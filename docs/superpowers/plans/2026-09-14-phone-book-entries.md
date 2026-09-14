# Phone Book Entries per Person Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone book holds one entry per contact person (imported or saved by hand) with the company name on each, a company-only entry when there is nobody, and address, website, map link, note and a main number labelled „Hauptadresse“ on every entry.

**Architecture:** A new pure object `PhoneBookEntries` computes a business's target entries as `ContactFields`. `PhoneBook` learns to write names as given/family name, postal addresses, websites and labelled numbers; its row building is split out as `dataRows` so it can be tested. `ContactStore.persistBusiness` becomes the single place that writes a business's entries and removes the `place_id` entry when it is no longer part of the set. `ContactMerge` stops taking the business's number and email over as a person's own.

**Tech Stack:** Kotlin, Android `ContactsContract`, JUnit 4 + Robolectric 4.16 (`@Config(sdk = [34])`), Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-14-phone-book-entries-design.md`

## Global Constraints

- All paths below are relative to the app repository root `caller-app/app/`. Source root: `app/src/main/java/io/github/amadeusb/callsheet/`, test root: `app/src/test/java/io/github/amadeusb/callsheet/`.
- minSdk 30: no API that needs a newer level. In particular `URLEncoder.encode(String, Charset)` (API 33) is off limits — use `URLEncoder.encode(s, "UTF-8")`.
- Label of the business's main number: exactly `Hauptadresse`, written as `Phone.TYPE_CUSTOM` with `Phone.LABEL`.
- Country on every address: exactly `Deutschland`.
- Note format: `Branche: <industry> · Bewertung: <rating, one decimal, German comma> (<count>) · Herkunft: <runs joined with ", ">`; a hand-saved person's own note goes in front on its own line.
- Map link, imported business: `https://www.google.com/maps/search/?api=1&query=<name>&query_place_id=<place_id>`; hand-entered business (`manual:` prefix): `https://www.google.com/maps/search/?api=1&query=<street, postal code city>`, none without an address.
- User-facing German says „Branche“, never „Gewerk“. The import file field `gewerk` stays as it is.
- Tests use made-up names, numbers and addresses only — never data from the real business file.
- Code comments and KDoc in English, matching the surrounding code.
- Commit messages in German, short, no attribution lines.
- The working tree holds changes that are not part of this plan (`ui/BusinessDetail.kt`, `calling/LegitimateInterest.kt`, `LegitimateInterestTest.kt`, the multiple-appointments spec and plan). **Never stage them.** Always `git add` explicit paths; Task 5 shows how to stage a single line of `BusinessDetail.kt`.
- Run tests with `./gradlew testDebugUnitTest` from the app repository root (JDK from `~/jdk`, already on `PATH`).

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `contacts/PhoneBook.kt` | modify | Data types of an entry (`ContactFields`, `PersonName`, `PostalAddress`, `PhoneBookWebsite`), `dataRows`, writing/reading/deleting through the contacts provider |
| `contacts/ContactMerge.kt` | modify | `PhoneBookNumber` gains `label`; shared `sameNumber`; read-back rules for business number and email |
| `contacts/PhoneBookEntries.kt` | create | Pure rules: which entries a business makes and what each holds |
| `contacts/ContactStore.kt` | modify | `persistBusiness` writes the whole set; `persistContact` goes; `readBack` gets the business |
| `CallsheetViewModel.kt` | modify | Call sites: save/delete contact, push all, detail read-back |
| `ui/Settings.kt` | modify | Phone book explanation text |
| `ui/WorkList.kt`, `ui/BusinessForm.kt`, `ui/BusinessDetail.kt` | modify | „Gewerk“ → „Branche“ |
| `docs/data-model.md`, `docs/usage.md` | modify | Phone book description |
| `PhoneBookRowsTest.kt` | create | Row shape of an entry |
| `PhoneBookEntriesTest.kt` | create | Target set and field rules |
| `ContactMergeTest.kt` | modify | New read-back rules |

---

### Task 1: Entry data types and row building in `PhoneBook`

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBook.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt:10` (`PhoneBookNumber`)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactStore.kt:28-70` (constructor calls only)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/PhoneBookRowsTest.kt`

**Interfaces:**
- Produces:
  - `data class PhoneBookNumber(val number: String, val kind: PhoneType, val label: String? = null)`
  - `data class PersonName(val given: String?, val family: String)` with `val display: String` and `companion fun of(name: String): PersonName?`
  - `data class PostalAddress(val street: String?, val postalCode: String?, val city: String?, val country: String)`
  - `enum class WebsiteKind { WORK, OTHER }`, `data class PhoneBookWebsite(val url: String, val kind: WebsiteKind)`
  - `data class ContactFields(sourceId: String, name: PersonName?, organization: String?, role: String?, email: String?, note: String?, numbers: List<PhoneBookNumber>, address: PostalAddress? = null, websites: List<PhoneBookWebsite> = emptyList())`
  - `const val MAIN_NUMBER_LABEL = "Hauptadresse"`
  - `fun businessNumber(number: String): List<PhoneBookNumber>` — now labelled
  - `internal fun PhoneBook.dataRows(fields: ContactFields): List<ContentValues>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/amadeusb/callsheet/PhoneBookRowsTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import io.github.amadeusb.callsheet.contacts.ContactFields
import io.github.amadeusb.callsheet.contacts.MAIN_NUMBER_LABEL
import io.github.amadeusb.callsheet.contacts.PersonName
import io.github.amadeusb.callsheet.contacts.PhoneBook
import io.github.amadeusb.callsheet.contacts.PhoneBookNumber
import io.github.amadeusb.callsheet.contacts.PhoneBookWebsite
import io.github.amadeusb.callsheet.contacts.PostalAddress
import io.github.amadeusb.callsheet.contacts.WebsiteKind
import io.github.amadeusb.callsheet.contacts.businessNumber
import io.github.amadeusb.callsheet.data.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shape of a phone book entry as rows. Every name, number and address here
 * is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBookRowsTest {

    private fun fields(
        name: PersonName? = PersonName("Max", "Mustermann"),
        numbers: List<PhoneBookNumber> = listOf(PhoneBookNumber("+491701234567", PhoneType.MOBILE)),
        address: PostalAddress? = PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland"),
        websites: List<PhoneBookWebsite> = listOf(
            PhoneBookWebsite("https://example.org/", WebsiteKind.WORK),
            PhoneBookWebsite("https://www.google.com/maps/search/?api=1&query=x", WebsiteKind.OTHER),
        ),
    ) = ContactFields(
        sourceId = "P1",
        name = name,
        organization = "Muster Fliesen",
        role = "Bau",
        email = "info@example.org",
        note = "Branche: Bau",
        numbers = numbers,
        address = address,
        websites = websites,
    )

    private fun List<ContentValues>.of(mimeType: String) =
        filter { it.getAsString(Data.MIMETYPE) == mimeType }

    @Test
    fun `a person is written with given and family name`() {
        val name = PhoneBook.dataRows(fields()).of(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Max", name.getAsString(StructuredName.GIVEN_NAME))
        assertEquals("Mustermann", name.getAsString(StructuredName.FAMILY_NAME))
        assertEquals("Max Mustermann", name.getAsString(StructuredName.DISPLAY_NAME))
    }

    @Test
    fun `a company-only entry has no name row, so the organisation shows`() {
        val rows = PhoneBook.dataRows(fields(name = null))
        assertTrue(rows.of(StructuredName.CONTENT_ITEM_TYPE).isEmpty())
        assertEquals(
            "Muster Fliesen",
            rows.of(Organization.CONTENT_ITEM_TYPE).single().getAsString(Organization.COMPANY),
        )
    }

    @Test
    fun `the main number is written with its label`() {
        val rows = PhoneBook.dataRows(fields(numbers = businessNumber("+498412345678")))
        val phone = rows.of(Phone.CONTENT_ITEM_TYPE).single()
        assertEquals(Phone.TYPE_CUSTOM, phone.getAsInteger(Phone.TYPE))
        assertEquals(MAIN_NUMBER_LABEL, phone.getAsString(Phone.LABEL))
        assertEquals("Hauptadresse", MAIN_NUMBER_LABEL)
    }

    @Test
    fun `a number without a label keeps its own type`() {
        val phone = PhoneBook.dataRows(fields()).of(Phone.CONTENT_ITEM_TYPE).single()
        assertEquals(Phone.TYPE_MOBILE, phone.getAsInteger(Phone.TYPE))
        assertNull(phone.getAsString(Phone.LABEL))
    }

    @Test
    fun `address, websites, email and note are written`() {
        val rows = PhoneBook.dataRows(fields())

        val postal = rows.of(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("Musterweg 1", postal.getAsString(StructuredPostal.STREET))
        assertEquals("85000", postal.getAsString(StructuredPostal.POSTCODE))
        assertEquals("Musterstadt", postal.getAsString(StructuredPostal.CITY))
        assertEquals("Deutschland", postal.getAsString(StructuredPostal.COUNTRY))
        assertEquals(StructuredPostal.TYPE_WORK, postal.getAsInteger(StructuredPostal.TYPE))

        val sites = rows.of(Website.CONTENT_ITEM_TYPE)
        assertEquals(listOf(Website.TYPE_WORK, Website.TYPE_OTHER), sites.map { it.getAsInteger(Website.TYPE) })
        assertEquals("https://example.org/", sites[0].getAsString(Website.URL))

        assertEquals("info@example.org", rows.of(Email.CONTENT_ITEM_TYPE).single().getAsString(Email.ADDRESS))
        assertEquals("Branche: Bau", rows.of(Note.CONTENT_ITEM_TYPE).single().getAsString(Note.NOTE))
    }

    @Test
    fun `no address means no postal row`() {
        val rows = PhoneBook.dataRows(fields(address = null, websites = emptyList()))
        assertTrue(rows.of(StructuredPostal.CONTENT_ITEM_TYPE).isEmpty())
        assertTrue(rows.of(Website.CONTENT_ITEM_TYPE).isEmpty())
    }

    @Test
    fun `a name splits at the last word`() {
        assertEquals(PersonName("Max", "Mustermann"), PersonName.of("Max Mustermann"))
        assertEquals(PersonName("Anna Maria", "Beispiel"), PersonName.of("  Anna   Maria Beispiel "))
        assertEquals(PersonName(null, "Aleks"), PersonName.of("Aleks"))
        assertNull(PersonName.of("   "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*PhoneBookRowsTest'`
Expected: FAIL — compilation errors: `Unresolved reference: dataRows`, `PersonName`, `MAIN_NUMBER_LABEL` …

- [ ] **Step 3: Add `label` to `PhoneBookNumber`**

In `contacts/ContactMerge.kt` replace

```kotlin
/** A number as the phone book holds it. */
data class PhoneBookNumber(val number: String, val kind: PhoneType)
```

with

```kotlin
/**
 * A number as the phone book holds it.
 *
 * [label] replaces the type with a text of its own — the business's main number
 * carries one, because DAVx5 has no phone type it would upload as `TYPE=MAIN`.
 * Reading back ignores it.
 */
data class PhoneBookNumber(val number: String, val kind: PhoneType, val label: String? = null)
```

- [ ] **Step 4: Replace the data types at the top of `PhoneBook.kt`**

Add the imports

```kotlin
import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
```

and replace the existing `ContactFields` block

```kotlin
/** A contact as the app writes it into the phone book. */
data class ContactFields(
    /** The app's stable id — it stays the same across updates. */
    val sourceId: String,
    val name: String,
    val organization: String?,
    val role: String?,
    val email: String?,
    val note: String?,
    val numbers: List<PhoneBookNumber>,
)
```

with

```kotlin
/** The label the address book shows a business's main number under. */
const val MAIN_NUMBER_LABEL = "Hauptadresse"

/** A person's name as the phone book stores it. */
data class PersonName(val given: String?, val family: String) {

    /** Given and family name as one line, the way the app shows the person. */
    val display: String
        get() = listOfNotNull(given, family).joinToString(" ")

    companion object {
        /**
         * The last word becomes the family name, the rest the given name. A
         * single word is all family name. Null for a blank name.
         */
        fun of(name: String): PersonName? {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            if (words.size == 1) return PersonName(null, words.single())
            return PersonName(words.dropLast(1).joinToString(" "), words.last())
        }
    }
}

/** A business address as it goes into the phone book. */
data class PostalAddress(
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
)

enum class WebsiteKind { WORK, OTHER }

/** A link on an entry: the business's website, or the map. */
data class PhoneBookWebsite(val url: String, val kind: WebsiteKind)

/** A contact as the app writes it into the phone book. */
data class ContactFields(
    /** The app's stable id — it stays the same across updates. */
    val sourceId: String,
    /**
     * Null for a company-only entry. It gets no name row at all, so Android
     * and DAVx5 both fall back to the organisation.
     */
    val name: PersonName?,
    val organization: String?,
    val role: String?,
    val email: String?,
    val note: String?,
    val numbers: List<PhoneBookNumber>,
    val address: PostalAddress? = null,
    val websites: List<PhoneBookWebsite> = emptyList(),
)
```

- [ ] **Step 5: Extend `OWN_TYPES`, add `dataRows`, use it in `write`**

In `object PhoneBook`, replace

```kotlin
    private val OWN_TYPES = arrayOf(
        StructuredName.CONTENT_ITEM_TYPE,
        Phone.CONTENT_ITEM_TYPE,
        Email.CONTENT_ITEM_TYPE,
        Organization.CONTENT_ITEM_TYPE,
        Note.CONTENT_ITEM_TYPE,
    )
```

with

```kotlin
    private val OWN_TYPES = arrayOf(
        StructuredName.CONTENT_ITEM_TYPE,
        Phone.CONTENT_ITEM_TYPE,
        Email.CONTENT_ITEM_TYPE,
        Organization.CONTENT_ITEM_TYPE,
        Note.CONTENT_ITEM_TYPE,
        StructuredPostal.CONTENT_ITEM_TYPE,
        Website.CONTENT_ITEM_TYPE,
    )
```

In `write`, replace everything from

```kotlin
            ops.add(
                row()
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, fields.name)
                    .build()
            )
```

down to and including the note block

```kotlin
            fields.note?.takeIf { it.isNotBlank() }?.let { note ->
                ops.add(
                    row()
                        .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                        .withValue(Note.NOTE, note)
                        .build()
                )
            }
```

with

```kotlin
            dataRows(fields).forEach { values -> ops.add(row().withValues(values).build()) }
```

Then add this function to `object PhoneBook`, directly after `write`:

```kotlin
    /**
     * The data rows of an entry, without the raw contact id — [write] adds that.
     * Kept apart so the shape of an entry can be tested without a contacts
     * provider.
     */
    internal fun dataRows(fields: ContactFields): List<ContentValues> {
        val rows = ArrayList<ContentValues>()
        fun row(mimeType: String, fill: ContentValues.() -> Unit) {
            rows += ContentValues().apply {
                put(Data.MIMETYPE, mimeType)
                fill()
            }
        }

        fields.name?.let { name ->
            // Given and family name set explicitly: with only a display name
            // Android splits a company like a person.
            row(StructuredName.CONTENT_ITEM_TYPE) {
                put(StructuredName.DISPLAY_NAME, name.display)
                put(StructuredName.GIVEN_NAME, name.given)
                put(StructuredName.FAMILY_NAME, name.family)
            }
        }
        fields.numbers.forEach { number ->
            row(Phone.CONTENT_ITEM_TYPE) {
                put(Phone.NUMBER, number.number)
                if (number.label != null) {
                    put(Phone.TYPE, Phone.TYPE_CUSTOM)
                    put(Phone.LABEL, number.label)
                } else {
                    put(Phone.TYPE, ContactMerge.toAndroidType(number.kind))
                }
            }
        }
        fields.email?.takeIf { it.isNotBlank() }?.let { mail ->
            row(Email.CONTENT_ITEM_TYPE) {
                put(Email.ADDRESS, mail)
                put(Email.TYPE, Email.TYPE_WORK)
            }
        }
        if (!fields.organization.isNullOrBlank() || !fields.role.isNullOrBlank()) {
            row(Organization.CONTENT_ITEM_TYPE) {
                put(Organization.COMPANY, fields.organization)
                put(Organization.TITLE, fields.role)
                put(Organization.TYPE, Organization.TYPE_WORK)
            }
        }
        fields.address?.let { address ->
            row(StructuredPostal.CONTENT_ITEM_TYPE) {
                put(StructuredPostal.STREET, address.street)
                put(StructuredPostal.POSTCODE, address.postalCode)
                put(StructuredPostal.CITY, address.city)
                put(StructuredPostal.COUNTRY, address.country)
                put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
            }
        }
        fields.websites.forEach { site ->
            row(Website.CONTENT_ITEM_TYPE) {
                put(Website.URL, site.url)
                put(
                    Website.TYPE,
                    when (site.kind) {
                        WebsiteKind.WORK -> Website.TYPE_WORK
                        WebsiteKind.OTHER -> Website.TYPE_OTHER
                    },
                )
            }
        }
        fields.note?.takeIf { it.isNotBlank() }?.let { note ->
            row(Note.CONTENT_ITEM_TYPE) { put(Note.NOTE, note) }
        }
        return rows
    }
```

At the bottom of `PhoneBook.kt`, replace

```kotlin
/** A business's main number goes into the phone book as the switchboard. */
fun businessNumber(number: String): List<PhoneBookNumber> =
    listOf(PhoneBookNumber(number, PhoneType.MAIN))
```

with

```kotlin
/** A business's main number goes into the phone book under [MAIN_NUMBER_LABEL]. */
fun businessNumber(number: String): List<PhoneBookNumber> =
    listOf(PhoneBookNumber(number, PhoneType.MAIN, label = MAIN_NUMBER_LABEL))
```

- [ ] **Step 6: Keep `ContactStore` compiling**

In `contacts/ContactStore.kt`, in `persistContact` replace `name = contact.name,` with `name = PersonName.of(contact.name),`; in `persistBusiness` replace `name = business.name,` with `name = null,`. (Both functions are rewritten in Task 4.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*PhoneBookRowsTest' --tests '*ContactMergeTest'`
Expected: PASS, all tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBook.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactStore.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PhoneBookRowsTest.kt
git commit -m "Telefonbuch: Vor- und Nachname, Anschrift, Website und Label für die Hauptnummer"
```

---

### Task 2: `PhoneBookEntries` — which entries a business makes

**Files:**
- Create: `app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBookEntries.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt` (move the number comparison out of the object)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt`

**Interfaces:**
- Consumes (Task 1): `ContactFields`, `PersonName.of`, `PostalAddress`, `PhoneBookWebsite`, `WebsiteKind`, `PhoneBookNumber`, `businessNumber`, `List<PhoneNumber>.toPhoneBookNumbers()`; `MANUAL_PREFIX` from `data/Models.kt`.
- Produces:
  - `object PhoneBookEntries { fun forBusiness(business: Business, contacts: List<Contact>): List<ContactFields> }`
  - `internal fun sameNumber(a: String, b: String): Boolean` (top level in `ContactMerge.kt`)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt`:

```kotlin
package io.github.amadeusb.callsheet

import io.github.amadeusb.callsheet.contacts.MAIN_NUMBER_LABEL
import io.github.amadeusb.callsheet.contacts.PersonName
import io.github.amadeusb.callsheet.contacts.PhoneBookEntries
import io.github.amadeusb.callsheet.contacts.PhoneBookWebsite
import io.github.amadeusb.callsheet.contacts.PostalAddress
import io.github.amadeusb.callsheet.contacts.WebsiteKind
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.PhoneNumber
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One entry per person, the company on every one. Every name, number and
 * address here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBookEntriesTest {

    private fun business(
        placeId: String = "P1",
        phone: String? = "+498412345678",
        contactName: String? = null,
        industry: String? = "Bau",
        rating: Double? = 4.5,
        ratingCount: Int? = 12,
        origin: List<String> = listOf("lauf-a"),
        street: String? = "Musterweg 1",
        postalCode: String? = "85000",
        city: String? = "Musterstadt",
        website: String? = "https://example.org/",
        email: String? = "info@example.org",
    ) = Business(
        placeId = placeId, name = "Muster Fliesen", industry = industry, categories = emptyList(),
        street = street, postalCode = postalCode, city = city,
        phone = phone, website = website, email = email, contactName = contactName,
        rating = rating, ratingCount = ratingCount, closed = false, isTarget = true,
        origin = origin, collectedAt = null, status = Status.NEW, note = null,
        followUpAt = null, updatedAt = "2026-09-14T12:00:00+02:00",
    )

    private fun contact(
        id: String = "c1",
        name: String = "Erika Beispiel",
        role: String? = null,
        email: String? = null,
        note: String? = null,
        numbers: List<PhoneNumber> = listOf(PhoneNumber("n1", "+491701234567", PhoneType.MOBILE)),
    ) = Contact(
        id = id, placeId = "P1", name = name, role = role, email = email, note = note,
        numbers = numbers, updatedAt = "2026-09-14T12:00:00+02:00",
    )

    // ---- target set ----

    @Test
    fun `nobody at all makes one company-only entry`() {
        val entry = PhoneBookEntries.forBusiness(business(), emptyList()).single()
        assertEquals("P1", entry.sourceId)
        assertNull(entry.name)
        assertEquals("Muster Fliesen", entry.organization)
        assertEquals("Bau", entry.role)
    }

    @Test
    fun `the imported person takes the place_id entry`() {
        val entry = PhoneBookEntries.forBusiness(business(contactName = "Max Mustermann"), emptyList()).single()
        assertEquals("P1", entry.sourceId)
        assertEquals(PersonName("Max", "Mustermann"), entry.name)
        assertEquals("Muster Fliesen", entry.organization)
    }

    @Test
    fun `imported and hand-saved people get one entry each`() {
        val entries = PhoneBookEntries.forBusiness(business(contactName = "Max Mustermann"), listOf(contact()))
        assertEquals(listOf("P1", "c1"), entries.map { it.sourceId })
        assertEquals(PersonName("Erika", "Beispiel"), entries[1].name)
        assertEquals("Muster Fliesen", entries[1].organization)
    }

    @Test
    fun `hand-saved people only leave no place_id entry`() {
        val entries = PhoneBookEntries.forBusiness(business(), listOf(contact(), contact(id = "c2", name = "Tom Test")))
        assertEquals(listOf("c1", "c2"), entries.map { it.sourceId })
    }

    @Test
    fun `an imported name that was also saved by hand counts once`() {
        val entries = PhoneBookEntries.forBusiness(
            business(contactName = "  erika BEISPIEL "),
            listOf(contact(name = "Erika Beispiel")),
        )
        assertEquals(listOf("c1"), entries.map { it.sourceId })
    }

    @Test
    fun `without a main number only hand-saved people are written, with their own numbers`() {
        assertTrue(PhoneBookEntries.forBusiness(business(phone = null, contactName = "Max Mustermann"), emptyList()).isEmpty())

        val entry = PhoneBookEntries.forBusiness(business(phone = null), listOf(contact())).single()
        assertEquals(listOf("+491701234567"), entry.numbers.map { it.number })
    }

    // ---- fields ----

    @Test
    fun `a person carries their own numbers and the main number with its label`() {
        val entry = PhoneBookEntries.forBusiness(business(), listOf(contact())).single()
        assertEquals(listOf("+491701234567", "+498412345678"), entry.numbers.map { it.number })
        assertNull(entry.numbers[0].label)
        assertEquals(MAIN_NUMBER_LABEL, entry.numbers[1].label)
    }

    @Test
    fun `the main number is not written twice`() {
        val same = listOf(PhoneNumber("n1", "0841 2345678", PhoneType.WORK))
        val entry = PhoneBookEntries.forBusiness(business(), listOf(contact(numbers = same))).single()
        assertEquals(1, entry.numbers.size)
        assertNull(entry.numbers.single().label)
    }

    @Test
    fun `role and email fall back to the business`() {
        val plain = PhoneBookEntries.forBusiness(business(), listOf(contact())).single()
        assertEquals("Bau", plain.role)
        assertEquals("info@example.org", plain.email)

        val own = PhoneBookEntries.forBusiness(
            business(), listOf(contact(role = "Bauleitung", email = "erika@example.org")),
        ).single()
        assertEquals("Bauleitung", own.role)
        assertEquals("erika@example.org", own.email)
    }

    @Test
    fun `the note lists industry, rating and origin`() {
        val entry = PhoneBookEntries.forBusiness(business(origin = listOf("lauf-a", "lauf-b")), emptyList()).single()
        assertEquals("Branche: Bau · Bewertung: 4,5 (12) · Herkunft: lauf-a, lauf-b", entry.note)
    }

    @Test
    fun `the note leaves out what is missing and puts a person's own note in front`() {
        val sparse = PhoneBookEntries.forBusiness(business(rating = null, ratingCount = null), emptyList()).single()
        assertEquals("Branche: Bau · Herkunft: lauf-a", sparse.note)

        val empty = PhoneBookEntries.forBusiness(
            business(industry = null, rating = null, origin = emptyList()), emptyList(),
        ).single()
        assertNull(empty.note)

        val person = PhoneBookEntries.forBusiness(business(rating = 5.0, ratingCount = 2), listOf(contact(note = "vormittags"))).single()
        assertEquals("vormittags\nBranche: Bau · Bewertung: 5,0 (2) · Herkunft: lauf-a", person.note)
    }

    @Test
    fun `the address is left out when street, postal code and city are empty`() {
        val full = PhoneBookEntries.forBusiness(business(), emptyList()).single()
        assertEquals(PostalAddress("Musterweg 1", "85000", "Musterstadt", "Deutschland"), full.address)

        val none = PhoneBookEntries.forBusiness(business(street = null, postalCode = " ", city = null), emptyList()).single()
        assertNull(none.address)
    }

    @Test
    fun `an imported business links to its place on the map`() {
        val entry = PhoneBookEntries.forBusiness(business(), emptyList()).single()
        assertEquals(
            listOf(
                PhoneBookWebsite("https://example.org/", WebsiteKind.WORK),
                PhoneBookWebsite(
                    "https://www.google.com/maps/search/?api=1&query=Muster+Fliesen&query_place_id=P1",
                    WebsiteKind.OTHER,
                ),
            ),
            entry.websites,
        )
    }

    @Test
    fun `a hand-entered business links to its address, or not at all`() {
        val withAddress = PhoneBookEntries.forBusiness(business(placeId = "manual:x", website = null), emptyList()).single()
        assertEquals(
            listOf(
                PhoneBookWebsite(
                    "https://www.google.com/maps/search/?api=1&query=Musterweg+1%2C+85000+Musterstadt",
                    WebsiteKind.OTHER,
                ),
            ),
            withAddress.websites,
        )

        val without = PhoneBookEntries.forBusiness(
            business(placeId = "manual:x", website = null, street = null, postalCode = null, city = null),
            emptyList(),
        ).single()
        assertTrue(without.websites.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*PhoneBookEntriesTest'`
Expected: FAIL — `Unresolved reference: PhoneBookEntries`.

- [ ] **Step 3: Share the number comparison**

In `contacts/ContactMerge.kt`, delete the private member at the end of `object ContactMerge`:

```kotlin
    /** Notations like „030 12…“ and „+4930 12…“ must not count as a change. */
    private fun String.forComparison(): String = filter { it.isDigit() }.takeLast(9)
```

and add at the very end of the file, outside the object:

```kotlin
/** Notations like „030 12…“ and „+4930 12…“ must not count as a change. */
internal fun String.forComparison(): String = filter { it.isDigit() }.takeLast(9)

/** Whether two notations are the same number. */
internal fun sameNumber(a: String, b: String): Boolean = a.forComparison() == b.forComparison()
```

- [ ] **Step 4: Write `PhoneBookEntries`**

Create `app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBookEntries.kt`:

```kotlin
package io.github.amadeusb.callsheet.contacts

import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.MANUAL_PREFIX
import java.net.URLEncoder
import java.util.Locale

/**
 * Which phone book entries a business makes, and what each one holds.
 *
 * One entry per person, the company name on every one. The imported contact
 * person and a business with nobody at all share the `place_id` entry; a person
 * saved by hand has one under their own id. Without Android access, so the
 * rules stay testable.
 */
object PhoneBookEntries {

    private const val COUNTRY = "Deutschland"
    private const val MAP_SEARCH = "https://www.google.com/maps/search/?api=1&query="

    fun forBusiness(business: Business, contacts: List<Contact>): List<ContactFields> {
        val mainNumber = business.phone.clean()
        // Saved by hand under the same name, the imported person is already
        // there — as the one the user has worked on.
        val imported = business.contactName.clean()
            ?.takeIf { name -> contacts.none { it.name.trim().equals(name, ignoreCase = true) } }

        val entries = ArrayList<ContactFields>()
        if (mainNumber != null && (imported != null || contacts.isEmpty())) {
            entries += entry(
                business = business,
                sourceId = business.placeId,
                name = imported?.let { PersonName.of(it) },
                role = null,
                email = null,
                ownNote = null,
                ownNumbers = emptyList(),
            )
        }
        contacts.forEach { contact ->
            entries += entry(
                business = business,
                sourceId = contact.id,
                name = PersonName.of(contact.name),
                role = contact.role,
                email = contact.email,
                ownNote = contact.note,
                ownNumbers = contact.numbers.toPhoneBookNumbers(),
            )
        }
        return entries
    }

    private fun entry(
        business: Business,
        sourceId: String,
        name: PersonName?,
        role: String?,
        email: String?,
        ownNote: String?,
        ownNumbers: List<PhoneBookNumber>,
    ) = ContactFields(
        sourceId = sourceId,
        name = name,
        organization = business.name,
        role = role.clean() ?: business.industry.clean(),
        email = email.clean() ?: business.email.clean(),
        note = note(business, ownNote),
        numbers = numbers(ownNumbers, business.phone.clean()),
        address = address(business),
        websites = websites(business),
    )

    /** The person's own numbers, then the main number unless they already hold it. */
    private fun numbers(own: List<PhoneBookNumber>, mainNumber: String?): List<PhoneBookNumber> {
        if (mainNumber == null || own.any { sameNumber(it.number, mainNumber) }) return own
        return own + businessNumber(mainNumber)
    }

    private fun note(business: Business, ownNote: String?): String? {
        val facts = listOfNotNull(
            business.industry.clean()?.let { "Branche: $it" },
            business.rating?.let { rating ->
                val count = business.ratingCount?.let { " ($it)" }.orEmpty()
                "Bewertung: " + String.format(Locale.GERMANY, "%.1f", rating) + count
            },
            business.origin.takeIf { it.isNotEmpty() }?.let { "Herkunft: " + it.joinToString(", ") },
        ).joinToString(" · ")
        return listOfNotNull(ownNote.clean(), facts.clean()).joinToString("\n").clean()
    }

    private fun address(business: Business): PostalAddress? {
        val street = business.street.clean()
        val postalCode = business.postalCode.clean()
        val city = business.city.clean()
        if (street == null && postalCode == null && city == null) return null
        return PostalAddress(street, postalCode, city, COUNTRY)
    }

    private fun websites(business: Business): List<PhoneBookWebsite> = listOfNotNull(
        business.website.clean()?.let { PhoneBookWebsite(it, WebsiteKind.WORK) },
        mapLink(business)?.let { PhoneBookWebsite(it, WebsiteKind.OTHER) },
    )

    /**
     * An imported business is found by its place id. A hand-entered one has
     * none, so the map searches for its address — and without one there is no
     * link.
     */
    private fun mapLink(business: Business): String? {
        if (!business.placeId.startsWith(MANUAL_PREFIX)) {
            return MAP_SEARCH + encode(business.name) + "&query_place_id=" + encode(business.placeId)
        }
        val town = listOfNotNull(business.postalCode.clean(), business.city.clean()).joinToString(" ")
        val address = listOfNotNull(business.street.clean(), town.clean()).joinToString(", ")
        return address.clean()?.let { MAP_SEARCH + encode(it) }
    }

    // The Charset overload needs API 33; minSdk is 30.
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*PhoneBookEntriesTest' --tests '*ContactMergeTest' --tests '*PhoneBookRowsTest'`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/PhoneBookEntries.kt \
        app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt \
        app/src/test/java/io/github/amadeusb/callsheet/PhoneBookEntriesTest.kt
git commit -m "Telefonbuch: ein Eintrag je Ansprechpartner, sonst einer für den Betrieb"
```

---

### Task 3: Read-back ignores the business's number and email

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt` (`merge`)
- Test: `app/src/test/java/io/github/amadeusb/callsheet/ContactMergeTest.kt`

**Interfaces:**
- Consumes (Task 2): `sameNumber(a, b)`, `String.forComparison()`
- Produces: `fun ContactMerge.merge(existing: Contact, fromPhoneBook: PhoneBookContact, businessPhone: String? = null, businessEmail: String? = null): ContactDraft?`

- [ ] **Step 1: Write the failing tests**

Append to `class ContactMergeTest` in `app/src/test/java/io/github/amadeusb/callsheet/ContactMergeTest.kt`, before the closing brace:

```kotlin
    @Test
    fun `the business's main number is not taken over as the person's`() {
        val phoneBook = fromPhoneBook(
            numbers = listOf(
                PhoneBookNumber("+491701234567", PhoneType.MOBILE),
                PhoneBookNumber("+49 841 2345678", PhoneType.OTHER),
            ),
        )
        assertNull(ContactMerge.merge(contact(), phoneBook, businessPhone = "+498412345678"))
    }

    @Test
    fun `a person who holds the main number in the app keeps it`() {
        val own = listOf(
            PhoneNumber("n1", "+491701234567", PhoneType.MOBILE),
            PhoneNumber("n2", "+498412345678", PhoneType.WORK),
        )
        val phoneBook = fromPhoneBook(
            numbers = listOf(
                PhoneBookNumber("+491701234567", PhoneType.MOBILE),
                PhoneBookNumber("+498412345678", PhoneType.WORK),
            ),
        )
        assertNull(ContactMerge.merge(contact(numbers = own), phoneBook, businessPhone = "+498412345678"))
    }

    @Test
    fun `the business's email is not taken over by a person without one`() {
        val phoneBook = fromPhoneBook(email = "info@example.org")
        assertNull(
            ContactMerge.merge(contact(email = null), phoneBook, businessEmail = "info@example.org")
        )
    }

    @Test
    fun `an email of the person's own is still taken over`() {
        val phoneBook = fromPhoneBook(email = "neu@example.org")
        val draft = ContactMerge.merge(contact(email = null), phoneBook, businessEmail = "info@example.org")!!
        assertEquals("neu@example.org", draft.email)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*ContactMergeTest'`
Expected: FAIL — compilation error `Cannot find a parameter with this name: businessPhone`.

- [ ] **Step 3: Implement**

In `object ContactMerge`, replace the signature and the first three lines of `merge`

```kotlin
    fun merge(
        existing: Contact,
        fromPhoneBook: PhoneBookContact,
    ): ContactDraft? {
        val name = fromPhoneBook.name.trim().ifEmpty { existing.name }
        val email = fromPhoneBook.email?.trim().orEmpty()
        val numbers = fromPhoneBook.numbers.map { PhoneDraft(number = it.number, kind = it.kind) }
```

with

```kotlin
    fun merge(
        existing: Contact,
        fromPhoneBook: PhoneBookContact,
        businessPhone: String? = null,
        businessEmail: String? = null,
    ): ContactDraft? {
        val name = fromPhoneBook.name.trim().ifEmpty { existing.name }

        // Every entry of a business carries its email when the person has none
        // of their own — that one is not the person's to take over.
        val readEmail = fromPhoneBook.email?.trim().orEmpty()
        val businessMail = businessEmail?.trim().orEmpty()
        val email = if (existing.email.isNullOrBlank() && businessMail.isNotEmpty() &&
            readEmail.equals(businessMail, ignoreCase = true)
        ) "" else readEmail

        // Likewise the main number, unless the person holds it in the app too.
        val main = businessPhone?.trim()?.takeIf { it.isNotEmpty() }
        val holdsMain = main != null && existing.numbers.any { sameNumber(it.number, main) }
        val numbers = fromPhoneBook.numbers
            .filter { main == null || holdsMain || !sameNumber(it.number, main) }
            .map { PhoneDraft(number = it.number, kind = it.kind) }
```

Also extend the KDoc of `merge` by one paragraph, after „An empty name in the phone book is ignored; it would leave the app's entry unusable.“:

```kotlin
     *
     * The business's main number and email sit on every entry of the business;
     * [businessPhone] and [businessEmail] keep them from being taken over as
     * the person's own.
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*ContactMergeTest'`
Expected: PASS, all tests including the existing ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactMerge.kt \
        app/src/test/java/io/github/amadeusb/callsheet/ContactMergeTest.kt
git commit -m "Zurücklesen übernimmt Hauptnummer und Firmen-E-Mail nicht als eigene"
```

---

### Task 4: `ContactStore` writes the whole set; call sites follow

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactStore.kt`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt` (`saveContact` ~559, `deleteContact` ~588, `pushAllToPhoneBook` ~931, `loadDetail` ~978)
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/Settings.kt:611-616`

**Interfaces:**
- Consumes: `PhoneBookEntries.forBusiness` (Task 2), `ContactMerge.merge(..., businessPhone, businessEmail)` (Task 3), `PhoneBook.write/delete`, `Repository.contacts(placeId)`, `Repository.setContactVersion(id: String, version: Int?)`.
- Produces:
  - `suspend fun ContactStore.persistBusiness(business: Business): Int` — number of entries written, 0 when inactive
  - `suspend fun ContactStore.readBack(business: Business?, contacts: List<Contact>): Boolean`
  - `ContactStore.persistContact` no longer exists.

No new unit test: `ContactStore` only wires `PhoneBookEntries` to the contacts provider, which Robolectric does not model; the rules are covered by Tasks 1–3 and the wiring by Task 7.

- [ ] **Step 1: Rewrite `ContactStore`**

In `contacts/ContactStore.kt`, replace `persistContact` and `persistBusiness` (from `/** Writes a contact into the phone book and records the version it left. */` down to the closing brace of `persistBusiness`) with:

```kotlin
    /**
     * Writes every entry a business makes (see [PhoneBookEntries]) and removes
     * the `place_id` entry once it is no longer one of them — people saved by
     * hand have taken its place.
     *
     * @return how many entries were written.
     */
    suspend fun persistBusiness(business: Business): Int {
        val account = preferences.account ?: return 0
        if (!active) return 0
        val entries = PhoneBookEntries.forBusiness(business, repo.contacts(business.placeId))
        for (entry in entries) {
            val version = PhoneBook.write(context, account, entry)
            // Only people saved by hand are read back, so only they need it.
            if (entry.sourceId != business.placeId) repo.setContactVersion(entry.sourceId, version)
        }
        if (entries.none { it.sourceId == business.placeId }) {
            PhoneBook.delete(context, account, business.placeId)
        }
        return entries.size
    }
```

Replace the signature and merge call of `readBack`:

```kotlin
    suspend fun readBack(contacts: List<Contact>): Boolean {
```

becomes

```kotlin
    suspend fun readBack(business: Business?, contacts: List<Contact>): Boolean {
```

and

```kotlin
            val draft = ContactMerge.merge(contact, fromPhoneBook)
```

becomes

```kotlin
            val draft = ContactMerge.merge(contact, fromPhoneBook, business?.phone, business?.email)
```

Update the class KDoc sentence „Changes made there win the next time the record is opened; new people are still created in the app.“ to:

```kotlin
 * Changes made there to people saved by hand win the next time the record is
 * opened; everything else is written anew by the app. New people are still
 * created in the app.
```

- [ ] **Step 2: `saveContact` in the view model**

Replace

```kotlin
                    val business = repo.business(draft.placeId)
                    repo.contacts(draft.placeId).firstOrNull { it.id == id }?.let {
                        store.persistContact(it, business)
                    }
                    // The business itself belongs there too, otherwise a call
                    // back from the switchboard stays nameless.
                    business?.let { store.persistBusiness(it) }
```

with

```kotlin
                    // The whole business follows: the person's own entry, and
                    // the company entry that makes way for them.
                    repo.business(draft.placeId)?.let { store.persistBusiness(it) }
```

Then check whether `id` is still used in that `onSuccess = { id ->` lambda; if not, rename it to `_`.

- [ ] **Step 3: `deleteContact` in the view model**

Replace

```kotlin
            repo.deleteContact(id)
            store.deleteContact(id)
```

with

```kotlin
            repo.deleteContact(id)
            store.deleteContact(id)
            // With the last person gone, the company entry comes back.
            repo.business(placeId)?.let { store.persistBusiness(it) }
```

- [ ] **Step 4: `pushAllToPhoneBook` in the view model**

Replace

```kotlin
            var people = 0
            var businesses = 0
            for (business in repo.businessesForPhoneBook()) {
                val contacts = repo.contacts(business.placeId)
                contacts.forEach { store.persistContact(it, business) }
                people += contacts.size
                store.persistBusiness(business)
                businesses++
            }
            _state.value = _state.value.copy(
                saving = false,
                phoneBookHint = "Übertragen: $businesses Betriebe und $people " +
                    "Ansprechpartner. DAVx5 lädt sie beim nächsten Abgleich hoch.",
            )
```

with

```kotlin
            var businesses = 0
            var entries = 0
            for (business in repo.businessesForPhoneBook()) {
                entries += store.persistBusiness(business)
                businesses++
            }
            _state.value = _state.value.copy(
                saving = false,
                phoneBookHint = "Übertragen: $businesses Betriebe in $entries " +
                    "Einträgen. DAVx5 lädt sie beim nächsten Abgleich hoch.",
            )
```

- [ ] **Step 5: `loadDetail` in the view model**

Replace

```kotlin
            val contacts = repo.contacts(placeId)
            _state.value = _state.value.copy(
                detail = repo.business(placeId),
                detailCalls = repo.calls(placeId),
                detailContacts = contacts,
            )
            // Whatever was changed in the phone book wins — afterwards the
            // record is level with the address book again.
            if (store.readBack(contacts)) {
```

with

```kotlin
            val contacts = repo.contacts(placeId)
            val business = repo.business(placeId)
            _state.value = _state.value.copy(
                detail = business,
                detailCalls = repo.calls(placeId),
                detailContacts = contacts,
            )
            // Whatever was changed in the phone book wins — afterwards the
            // record is level with the address book again.
            if (store.readBack(business, contacts)) {
```

- [ ] **Step 6: Settings text**

In `ui/Settings.kt` replace

```kotlin
            text = "Jeder Betrieb mit Telefonnummer wird im Telefonbuch abgelegt, " +
                "dazu die erfassten Ansprechpartner. Damit zeigt das Telefon " +
                "einen Namen, auch wenn dort zuerst angerufen wird. Gesperrte " +
                "Betriebe bleiben draußen. " +
                "Was du im Telefonbuch änderst, übernimmt die App beim nächsten " +
                "Öffnen der Akte.",
```

with

```kotlin
            text = "Jeder Betrieb mit Telefonnummer wird im Telefonbuch abgelegt: " +
                "ein Eintrag je Ansprechpartner, sonst einer für den Betrieb. " +
                "Damit zeigt das Telefon einen Namen, auch wenn dort zuerst " +
                "angerufen wird. Gesperrte Betriebe bleiben draußen. " +
                "Was du bei selbst erfassten Ansprechpartnern im Telefonbuch " +
                "änderst, übernimmt die App beim nächsten Öffnen der Akte; " +
                "alles andere schreibt sie beim nächsten Übertragen neu.",
```

- [ ] **Step 7: Verify nothing still uses the old API**

Run: `grep -rn "persistContact\|readBack(contacts)" app/src/main`
Expected: no output.

- [ ] **Step 8: Run the full test suite and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/amadeusb/callsheet/contacts/ContactStore.kt \
        app/src/main/java/io/github/amadeusb/callsheet/CallsheetViewModel.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/Settings.kt
git commit -m "Telefonbuch: Betrieb schreibt alle Einträge, Firmeneintrag weicht Ansprechpartnern"
```

---

### Task 5: „Gewerk“ becomes „Branche“

**Files:**
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/WorkList.kt:164,266`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessForm.kt:173`
- Modify: `app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt:993`

- [ ] **Step 1: Rename the four labels**

- `ui/WorkList.kt`: `title = "Gewerk",` → `title = "Branche",` and `label = "Gewerk",` → `label = "Branche",`
- `ui/BusinessForm.kt`: `label = "Gewerk",` → `label = "Branche",`
- `ui/BusinessDetail.kt`: `label = "Auswahl begründet über Gewerk",` → `label = "Auswahl begründet über Branche",`

- [ ] **Step 2: Verify**

Run: `grep -rn '"[^"]*Gewerk[^"]*"' app/src/main`
Expected: no output.

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit — without the foreign changes in `BusinessDetail.kt`**

`BusinessDetail.kt` carries uncommitted work that is not part of this plan. Stage only the renamed line by building the staged version from `HEAD`:

```bash
F=app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt
git show HEAD:$F | sed 's/Auswahl begründet über Gewerk/Auswahl begründet über Branche/' > /tmp/BusinessDetail.kt
git update-index --cacheinfo 100644,$(git hash-object -w /tmp/BusinessDetail.kt),$F
git add app/src/main/java/io/github/amadeusb/callsheet/ui/WorkList.kt \
        app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessForm.kt
git diff --cached --stat
```

Expected: three files, one or two changed lines each. Then:

```bash
git commit -m "Branche statt Gewerk in der Oberfläche"
git diff --stat app/src/main/java/io/github/amadeusb/callsheet/ui/BusinessDetail.kt
```

Expected after the commit: `BusinessDetail.kt` still shows the foreign uncommitted changes (about 22 insertions), and no longer the rename.

---

### Task 6: Documentation

**Files:**
- Modify: `docs/data-model.md` (section „Phone book“)
- Modify: `docs/usage.md` (section „Contacts“)

- [ ] **Step 1: `docs/data-model.md`**

Replace the three bullets

```markdown
- The app recognises its own entries by `RawContacts.SOURCE_ID`: it holds the
  contact's id, or the business's `place_id`. Other people's contacts are never
  touched.
- Every business that has a number goes in, together with the contacts saved by
  hand. Blocked businesses stay out. Writing happens when a contact is saved,
  after a call, and on demand from the settings.
- The other direction: opening a record reads the app's own entries back. If
  `RawContacts.VERSION` has moved on, the phone book wins for name, email and
  numbers. Role and note stay as they are in the app.
```

with

```markdown
- **One entry per person, the company name on every one.** People are the
  imported `contact_name` and the contacts saved by hand; a hand-saved person
  with the imported name counts once. A business with nobody gets one entry
  without a personal name, so the company name shows.
- The app recognises its own entries by `RawContacts.SOURCE_ID`. The `place_id`
  entry holds the imported person, or the company when there is nobody; it is
  removed while only hand-saved people exist. A hand-saved person's entry holds
  the contact's id. Other people's contacts are never touched.
- Every entry carries the person's own numbers and the business's main number
  under the label „Hauptadresse“ (DAVx5 has no phone type it uploads as
  `TYPE=MAIN`), the email, the business address, the website, a map link and a
  note with industry, rating and research run. Rules in
  `contacts/PhoneBookEntries.kt`.
- Every business that has a number goes in. Blocked businesses stay out.
  Writing happens when a contact is saved or deleted, after a call, and on
  demand from the settings.
- The other direction: opening a record reads the entries of hand-saved people
  back. If `RawContacts.VERSION` has moved on, the phone book wins for name,
  email and numbers — except the business's main number and email, which are on
  every entry and not the person's. Role and note stay as they are in the app.
  The `place_id` entry is never read back.
```

- [ ] **Step 2: `docs/usage.md`**

Replace the two paragraphs under `## Contacts`

```markdown
Every business can carry contacts with a role, an email address and several
numbers. They go into the device's phone book, into the address book account
chosen in the settings — so a call back has a name attached.

If such a contact is edited in the phone book, the app takes over name, email and
numbers the next time the record is opened. Role and note stay as they are in the
app. Contacts that did not originate in the app are never touched.
```

with

```markdown
Every business can carry contacts with a role, an email address and several
numbers. The business goes into the device's phone book, into the address book
account chosen in the settings — so a call back has a name attached. There is one
entry per contact person, including the one from the imprint, each with the
company name, the main number („Hauptadresse“), address, website, a map link and
a note. A business without any contact person gets a single entry under its
company name.

If a contact saved in the app is edited in the phone book, the app takes over
name, email and numbers the next time the record is opened. Role and note stay as
they are in the app. Everything else is written anew the next time the business
is transferred. Contacts that did not originate in the app are never touched.
```

- [ ] **Step 3: Commit**

```bash
git add docs/data-model.md docs/usage.md
git commit -m "Doku: Telefonbuch mit einem Eintrag je Ansprechpartner"
```

CHANGELOG: this repository writes the section for a version right before the release. When that section is written, add:

```markdown
- **One phone book entry per contact person**, including the one from the
  imprint, each with the company name. A business without anybody gets a single
  entry under its company name — no longer split like a person's name.
- Every entry carries the address, the website, a map link and a note with
  industry, rating and research run. The main number is labelled
  „Hauptadresse“.
- „Branche“ instead of „Gewerk“ throughout the app.
```

---

### Task 7: Manual acceptance on the phone (with the user)

Not automatable: it needs the phone, DAVx5 and the Infomaniak web interface. An agent stops here and hands over.

- [ ] **Step 1:** Build and install: `./gradlew assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or the release build the user normally installs).
- [ ] **Step 2:** The user empties the „Firmen“ address book in Infomaniak and the app data. The server database is emptied only on the user's explicit go, after a backup — otherwise the next sync restores the old stock.
- [ ] **Step 3:** Import the business file, run **Alle Betriebe ins Telefonbuch übertragen**, let DAVx5 synchronise.
- [ ] **Step 4:** In Infomaniak check:
  - a business without a contact person: Vorname/Name empty, Unternehmen = company, address with country Deutschland, website, map link, note;
  - a business with an imported contact person: Vorname/Name of the person, Unternehmen = company, no second company-only entry;
  - after adding a contact by hand in the app to that business: a second entry with the same company;
  - the main number: shown as „Hauptadresse“? If Infomaniak shows the raw label or nothing, switch `businessNumber` in `PhoneBook.kt` to `PhoneBookNumber(number, PhoneType.WORK)` (label null), adjust the test `the main number is written with its label` and the docs, and repeat Step 3.
- [ ] **Step 5:** On the phone: call from / to a business number shows a name; open a record with a hand-saved person, edit that person's email in the phone book, reopen the record — the new email is taken over, the main number does not appear among the person's numbers.
