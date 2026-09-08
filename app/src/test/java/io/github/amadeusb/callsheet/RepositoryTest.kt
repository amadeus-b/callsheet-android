package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.calling.CallFlow
import io.github.amadeusb.callsheet.data.CallEntry
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.PhoneDraft
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.EntryKind
import io.github.amadeusb.callsheet.data.Filter
import io.github.amadeusb.callsheet.data.Repository
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Every name and number here is made up.
 *
 * This app's most important test lives here: `a second import leaves the work
 * untouched`. Were that to go wrong, weeks of phone calls would be gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var repo: Repository

    @Before
    fun aufbau() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("callsheet.db")
        repo = Repository(ctx)
    }

    private suspend fun import(json: String) = repo.import(json.byteInputStream())

    // ------------------------------------------------------------------ Import

    @Test
    fun `the first import creates everything as new`() = runTest {
        val e = import(FIRST_IMPORT)
        assertEquals(6, e.new)
        assertEquals(0, e.updated)
        assertEquals(6, e.total)
        assertEquals(1, e.withoutPhone)
        assertNull(e.error)

        val b = repo.business("P1")!!
        assertEquals(Status.NEW, b.status)
        assertTrue(b.updatedAt.isNotBlank())
    }

    @Test
    fun `a second import leaves the work untouched`() = runTest {
        import(FIRST_IMPORT)

        repo.setStatus("P1", Status.APPOINTMENT)
        repo.setNote("P1", "Rueckruf bei Herrn Beispiel")
        repo.setFollowUp("P1", Clock.format(System.currentTimeMillis() + 86_400_000))
        repo.logCall(
            CallEntry(
                id = UUID.randomUUID().toString(),
                placeId = "P1",
                startedAt = Clock.now(),
                durationSeconds = 187,
                outcome = Status.APPOINTMENT.key,
                note = "gutes Gespraech",
            )
        )

        val before = repo.business("P1")!!
        val previousFollowUp = before.followUpAt

        val e = import(SECOND_IMPORT)
        assertEquals(1, e.new)
        // Only P1 (name, street, categories, origin) and P5 (phone) actually
        // differ between FIRST_IMPORT and SECOND_IMPORT — P2, P3, P4 and P9
        // come back byte-for-byte identical and are not touched.
        assertEquals(2, e.updated)

        val after = repo.business("P1")!!
        // Arbeitsfelder unverändert …
        assertEquals(Status.APPOINTMENT, after.status)
        assertEquals("Rueckruf bei Herrn Beispiel", after.note)
        assertEquals(previousFollowUp, after.followUpAt)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(1, repo.calls("P1").size)
        assertEquals(187, repo.calls("P1").first().durationSeconds)
        // … Stammdaten aktualisiert.
        assertEquals("Elektro Beispiel GmbH & Co. KG", after.name)
        assertEquals("+496219900011", after.phone)
    }

    @Test
    fun `a second import does not touch a block either`() = runTest {
        import(FIRST_IMPORT)
        repo.setStatus("P2", Status.DO_NOT_CALL)
        import(SECOND_IMPORT)
        assertEquals(Status.DO_NOT_CALL, repo.business("P2")!!.status)
        assertEquals(1, repo.blockedBusinesses().size)
    }

    // ------------------------------------------------------------------ Sperre

    @Test
    fun `blocked businesses appear in no list`() = runTest {
        import(FIRST_IMPORT)
        repo.setStatus("P2", Status.DO_NOT_CALL)

        val alles = Filter(status = emptySet(), onlyTargets = false)
        assertTrue(repo.list(alles).none { it.placeId == "P2" })
        assertEquals(repo.list(alles).size, repo.count(alles))

        // Not even when searching for its own name.
        val search = Filter(status = emptySet(), onlyTargets = false, search = "Gartenpflege")
        assertTrue(repo.list(search).isEmpty())
        assertEquals(0, repo.count(search))

        // Not even when the status is asked for explicitly.
        val onlyBlocked = Filter(status = setOf(Status.DO_NOT_CALL), onlyTargets = false)
        assertEquals(0, repo.count(onlyBlocked))

        // Nicht in den Filterwerten …
        assertTrue(repo.industries().none { it == "GaLaBau" })
        assertTrue(repo.cities().none { it == "Eichstaett" })

        // … and not among the follow-ups either.
        repo.setFollowUp("P2", Clock.format(System.currentTimeMillis() - 3_600_000))
        assertTrue(repo.due().none { it.placeId == "P2" })

        // Only the unblock list shows them.
        assertEquals(listOf("P2"), repo.blockedBusinesses().map { it.placeId })
        assertNotNull(repo.business("P2"))
    }

    // ------------------------------------------------------------------ Filter

    @Test
    fun `filters take effect in SQL`() = runTest {
        import(FIRST_IMPORT)

        val alle = Filter(status = emptySet(), onlyTargets = false)
        assertEquals(6, repo.count(alle))

        // onlyTargets hides "(kein Ziel)" industries and businesses without a phone.
        assertEquals(4, repo.count(Filter(status = emptySet())))

        // Industry
        assertEquals(
            listOf("P1"),
            repo.list(Filter(industries = setOf("Elektro"), status = emptySet())).map { it.placeId },
        )

        // unassigned is additive to the industry filter
        assertEquals(
            setOf("P1", "P3"),
            repo.list(
                Filter(industries = setOf("Elektro"), unassigned = true, status = emptySet())
            ).map { it.placeId }.toSet(),
        )

        // Ort
        assertEquals(
            setOf("P1", "P4"),
            repo.list(Filter(cities = setOf("Ingolstadt"), status = emptySet(), onlyTargets = false))
                .map { it.placeId }.toSet(),
        )

        // Status
        repo.setStatus("P1", Status.DECLINED)
        assertEquals(1, repo.count(Filter(status = setOf(Status.DECLINED), onlyTargets = false)))
        assertEquals(5, repo.count(Filter(status = setOf(Status.NEW), onlyTargets = false)))

        // Suche über Name und Ort, Groß-/Kleinschreibung egal
        assertEquals(
            listOf("P1"),
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = "elektro"))
                .map { it.placeId },
        )
        assertEquals(
            setOf("P1", "P4"),
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = "INGOLSTADT"))
                .map { it.placeId }.toSet(),
        )
    }

    @Test
    fun `the search finds umlauts regardless of case`() = runTest {
        import(FIRST_IMPORT)
        suspend fun matches(term: String) =
            repo.list(Filter(status = emptySet(), onlyTargets = false, search = term))
                .map { it.placeId }
        // SQLite only lower-cases ASCII on its own; the search column covers that.
        assertEquals(listOf("P9"), matches("müller"))
        assertEquals(listOf("P9"), matches("MÜLLER"))
        assertEquals(listOf("P9"), matches("Söhne"))
        // The city is searched as well.
        assertEquals(listOf("P9"), matches("königsmoos"))
    }

    @Test
    fun `the list sorts by industry, city, name`() = runTest {
        import(FIRST_IMPORT)
        val namen = repo.list(Filter(status = emptySet(), onlyTargets = false)).map { it.industry }
        val withoutZero = namen.filterNotNull()
        assertEquals(withoutZero.sortedBy { it.lowercase() }, withoutZero)
        // Businesses without an industry come last.
        assertNull(namen.last())
    }

    // ------------------------------------------------------------ Arbeitsfelder

    @Test
    fun `writes set updated_at and report a change`() = runTest {
        import(FIRST_IMPORT)
        val before = repo.business("P1")!!.updatedAt
        val counter = repo.changes.value

        Thread.sleep(1100) // geaendert_am hat Sekundenauflösung
        repo.setNote("P1", "Notiz")

        assertTrue(repo.business("P1")!!.updatedAt > before)
        assertTrue(repo.changes.value > counter)
    }

    @Test
    fun `a follow-up can be cleared again`() = runTest {
        import(FIRST_IMPORT)
        repo.setFollowUp("P1", Clock.now())
        assertNotNull(repo.business("P1")!!.followUpAt)
        repo.setFollowUp("P1", null)
        assertNull(repo.business("P1")!!.followUpAt)
    }

    @Test
    fun `due returns overdue items first and nothing from the future`() = runTest {
        import(FIRST_IMPORT)
        val now = System.currentTimeMillis()
        repo.setFollowUp("P1", Clock.format(now - 3 * 86_400_000L)) // lange überfällig
        repo.setFollowUp("P3", Clock.format(now - 3_600_000L))      // heute fällig
        repo.setFollowUp("P4", Clock.format(now + 86_400_000L))     // morgen

        val due = repo.due(now)
        assertEquals(listOf("P1", "P3"), due.map { it.placeId })

        // Looking ahead picks up tomorrow's appointment.
        assertEquals(3, repo.due(now + 2 * 86_400_000L).size)
    }

    @Test
    fun `calledToday counts businesses in the filter since the day began`() = runTest {
        import(FIRST_IMPORT)
        val now = System.currentTimeMillis()

        // Two calls to the same business today count once.
        logCall("P1", now - 60_000)
        logCall("P1", now - 30_000)
        logCall("P3", now - 120_000)
        // Gestern zählt nicht.
        logCall("P4", Clock.todayStart(now) - 3_600_000)

        val alle = Filter(status = emptySet(), onlyTargets = false)
        assertEquals(2, repo.calledToday(alle))

        // The filter applies here too.
        assertEquals(
            1,
            repo.calledToday(Filter(industries = setOf("Elektro"), status = emptySet(), onlyTargets = false)),
        )

        // Blocked businesses do not count.
        repo.setStatus("P1", Status.DO_NOT_CALL)
        assertEquals(1, repo.calledToday(alle))
    }

    @Test
    fun `call entries are only ever appended`() = runTest {
        import(FIRST_IMPORT)
        logCall("P1", System.currentTimeMillis() - 7_200_000)
        logCall("P1", System.currentTimeMillis())
        val list = repo.calls("P1")
        assertEquals(2, list.size)
        // neueste zuerst
        assertTrue(list[0].startedAt >= list[1].startedAt)
        assertTrue(repo.calls("P3").isEmpty())
    }

    @Test
    fun `a broken file yields an error rather than an exception`() = runTest {
        val e = import("kein JSON")
        assertNotNull(e.error)
        assertEquals(0, e.new)
        assertFalse(repo.list(Filter(status = emptySet(), onlyTargets = false)).isNotEmpty())
    }

    // ------------------------------------------------------ Protokoll ergänzen

    @Test
    fun `the outcome is filled in on the call entry, time and duration stay`() = runTest {
        import(FIRST_IMPORT)
        val id = UUID.randomUUID().toString()
        val begonnen = Clock.format(System.currentTimeMillis() - 600_000)
        repo.logCall(
            CallEntry(
                id = id,
                placeId = "P1",
                startedAt = begonnen,
                durationSeconds = 95,
                outcome = null,
                note = null,
            )
        )

        repo.completeCall(id, outcome = Status.APPOINTMENT.label, note = "Termin am Freitag")

        val entries = repo.calls("P1")
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals(begonnen, entry.startedAt)
        assertEquals(95, entry.durationSeconds)
        assertEquals(Status.APPOINTMENT.label, entry.outcome)
        assertEquals("Termin am Freitag", entry.note)
        assertEquals(EntryKind.CALL, entry.kind)
    }

    @Test
    fun `an empty note does not overwrite what was documented`() = runTest {
        import(FIRST_IMPORT)
        val id = UUID.randomUUID().toString()
        repo.logCall(
            CallEntry(
                id = id,
                placeId = "P1",
                startedAt = Clock.now(),
                durationSeconds = 0,
                outcome = null,
                note = "Dauer nicht ermittelbar",
            )
        )

        repo.completeCall(id, outcome = Status.NO_ANSWER.label, note = "")

        val entry = repo.calls("P1").first()
        assertEquals("Dauer nicht ermittelbar", entry.note)
        assertEquals(Status.NO_ANSWER.label, entry.outcome)
    }

    @Test
    fun `a note without a call stays recognisable as such`() = runTest {
        import(FIRST_IMPORT)
        repo.logCall(
            CallEntry(
                id = UUID.randomUUID().toString(),
                placeId = "P1",
                startedAt = Clock.now(),
                durationSeconds = 0,
                outcome = Status.EMAIL_PROMISED.label,
                note = "Unterlagen geschickt",
                kind = EntryKind.NOTE,
            )
        )

        val entry = repo.calls("P1").first()
        assertEquals(EntryKind.NOTE, entry.kind)
        assertEquals("Unterlagen geschickt", entry.note)
    }
    @Test
    fun `the phone book version starts out unset and can be recorded`() = runTest {
        import(FIRST_IMPORT)
        repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE)),
            )
        ).getOrThrow()

        val contact = repo.contacts("P1").single()
        // Nothing has been merged yet, so no version is on record.
        assertNull(contact.contactVersion)

        repo.setContactVersion(contact.id, 5)
        assertEquals(5, repo.contacts("P1").single().contactVersion)
    }

    // -------------------------------------------------------------- Contacts

    @Test
    fun `a contact with several numbers is saved and read back`() = runTest {
        import(FIRST_IMPORT)

        val id = repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                role = "Bauleitung",
                email = "beispiel@example.org",
                note = "vormittags erreichbar",
                numbers = listOf(
                    PhoneDraft(number = "0621 9947615", kind = PhoneType.WORK),
                    PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE),
                    // Empty rows are not an error, they are simply dropped.
                    PhoneDraft(number = "   ", kind = PhoneType.HOME),
                ),
            )
        ).getOrThrow()

        val contact = repo.contacts("P1").single()
        assertEquals(id, contact.id)
        assertEquals("Frau Beispiel", contact.name)
        assertEquals("Bauleitung", contact.role)
        assertEquals(2, contact.numbers.size)
        // The form's order is preserved, numbers are stored in E.164 form.
        assertEquals("+496219947615", contact.numbers[0].number)
        assertEquals(PhoneType.WORK, contact.numbers[0].kind)
        assertEquals("+491701234567", contact.numbers[1].number)
        assertEquals(PhoneType.MOBILE, contact.numbers[1].kind)
    }

    @Test
    fun `an incomplete number is rejected rather than silently dropped`() = runTest {
        import(FIRST_IMPORT)

        val outcome = repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Herr Beispiel",
                numbers = listOf(PhoneDraft(number = "123", kind = PhoneType.MOBILE)),
            )
        )

        assertTrue(outcome.isFailure)
        assertTrue(repo.contacts("P1").isEmpty())
    }

    @Test
    fun `a contact without a name is rejected`() = runTest {
        import(FIRST_IMPORT)
        val outcome = repo.saveContact(
            ContactDraft(placeId = "P1", name = "  ")
        )
        assertTrue(outcome.isFailure)
    }

    @Test
    fun `editing replaces the numbers without leaving duplicates`() = runTest {
        import(FIRST_IMPORT)
        val id = repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(
                    PhoneDraft(number = "0621 9947615", kind = PhoneType.WORK),
                    PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE),
                ),
            )
        ).getOrThrow()

        repo.saveContact(
            ContactDraft(
                id = id,
                placeId = "P1",
                name = "Frau Beispiel-Neu",
                numbers = listOf(PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE)),
            )
        ).getOrThrow()

        val contact = repo.contacts("P1").single()
        assertEquals("Frau Beispiel-Neu", contact.name)
        assertEquals(1, contact.numbers.size)
        assertEquals("+491701234567", contact.numbers.single().number)
    }

    @Test
    fun `deleting removes the numbers too, the log stays`() = runTest {
        import(FIRST_IMPORT)
        val id = repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE)),
            )
        ).getOrThrow()
        repo.logCall(
            CallEntry(
                id = UUID.randomUUID().toString(),
                placeId = "P1",
                startedAt = Clock.now(),
                durationSeconds = 30,
                outcome = null,
                note = null,
                contact = "Frau Beispiel · Mobil",
            )
        )

        repo.deleteContact(id)

        assertTrue(repo.contacts("P1").isEmpty())
        assertEquals("Frau Beispiel · Mobil", repo.calls("P1").single().contact)
    }

    @Test
    fun `a second import leaves contacts untouched`() = runTest {
        import(FIRST_IMPORT)
        repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE)),
            )
        ).getOrThrow()

        import(SECOND_IMPORT)

        val contact = repo.contacts("P1").single()
        assertEquals("Frau Beispiel", contact.name)
        assertEquals(1, contact.numbers.size)
    }

    @Test
    fun `the list knows a contact contributes a number`() = runTest {
        import(FIRST_IMPORT)
        // P5 arrives from the import without a phone number.
        val withoutNumber = Filter(status = emptySet(), onlyTargets = false)
        assertFalse(repo.list(withoutNumber).single { it.placeId == "P5" }.hasNumber)

        repo.saveContact(
            ContactDraft(
                placeId = "P5",
                name = "Frau Beispiel",
                numbers = listOf(
                    PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE),
                    // Fax zählt nicht, dort ruft niemand an.
                    PhoneDraft(number = "0621 9947699", kind = PhoneType.FAX),
                ),
            )
        ).getOrThrow()

        val business = repo.list(withoutNumber).single { it.placeId == "P5" }
        assertEquals(1, business.additionalNumbers)
        assertTrue(business.hasNumber)
    }

    @Test
    fun `dial targets lead with the main number and leave out fax`() = runTest {
        import(FIRST_IMPORT)
        repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(
                    PhoneDraft(number = "0170 1234567", kind = PhoneType.MOBILE),
                    PhoneDraft(number = "0621 9947699", kind = PhoneType.FAX),
                ),
            )
        ).getOrThrow()

        val business = repo.business("P1")!!
        val targets = CallFlow.dialTargets(business, repo.contacts("P1"))

        assertEquals(2, targets.size)
        assertEquals(business.phone, targets.first().number)
        assertEquals("Betriebsnummer", targets.first().label)
        assertEquals("Frau Beispiel · Mobil", targets[1].label)
    }

    @Test
    fun `the same number on business and contact appears once`() = runTest {
        import(FIRST_IMPORT)
        val business = repo.business("P1")!!
        repo.saveContact(
            ContactDraft(
                placeId = "P1",
                name = "Frau Beispiel",
                numbers = listOf(
                    PhoneDraft(number = business.phone!!, kind = PhoneType.WORK)
                ),
            )
        ).getOrThrow()

        val targets = CallFlow.dialTargets(business, repo.contacts("P1"))

        assertEquals(1, targets.size)
        // Der Name sagt im Protokoll mehr als „Betriebsnummer“.
        assertEquals("Frau Beispiel · Geschäft", targets.single().label)
    }

    private suspend fun logCall(placeId: String, millis: Long) {
        repo.logCall(
            CallEntry(
                id = UUID.randomUUID().toString(),
                placeId = placeId,
                startedAt = Clock.format(millis),
                durationSeconds = 42,
                outcome = Status.CALLED.key,
                note = null,
            )
        )
    }

    private companion object {
        /** Made-up data in the field structure from docs/data-model.md. */
        const val FIRST_IMPORT = """
        [
          {
            "placeId": "P1",
            "title": "Elektro Beispiel GmbH",
            "categories": ["Elektriker"],
            "alle_kategorien": ["Elektriker"],
            "street": "Musterweg 1", "city": "Ingolstadt", "postalCode": "85049",
            "phone": "+49 621 990 0011", "phoneUnformatted": "+496219900011",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Elektro"
          },
          {
            "placeId": "P2",
            "title": "Gartenpflege Erfunden",
            "categories": ["Gartenbauer"],
            "alle_kategorien": ["Gartenbauer"],
            "street": "Beispielstrasse 7", "city": "Eichstaett", "postalCode": "85072",
            "phone": "0621 9900012", "phoneUnformatted": null,
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["dienstleistung-in"],
            "gewerk": "GaLaBau"
          },
          {
            "placeId": "P9",
            "title": "Müller & Söhne Bedachungen",
            "categories": ["Dachdecker"],
            "alle_kategorien": ["Dachdecker"],
            "street": "Erfundenweg 3", "city": "Königsmoos", "postalCode": "86669",
            "phone": "+49 621 9900016", "phoneUnformatted": "+496219900016",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Dach"
          },
          {
            "placeId": "P3",
            "title": "Ohne Zuordnung Betrieb",
            "categories": [],
            "alle_kategorien": [],
            "street": null, "city": "Gaimersheim", "postalCode": "85080",
            "phone": "+49 621 9900013", "phoneUnformatted": "+496219900013",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": null
          },
          {
            "placeId": "P4",
            "title": "Beispiel Paketshop",
            "categories": ["Kurierdienst"],
            "alle_kategorien": ["Kurierdienst"],
            "street": "Testallee 3", "city": "Ingolstadt", "postalCode": "85051",
            "phone": "+49 621 9900014", "phoneUnformatted": "+496219900014",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["logistik-in"],
            "gewerk": "Paketdienst (kein Ziel)"
          },
          {
            "placeId": "P5",
            "title": "Kfz Fantasie",
            "categories": ["Autowerkstatt"],
            "alle_kategorien": ["Autowerkstatt"],
            "street": "Radweg 9", "city": "Beilngries", "postalCode": "92339",
            "phone": null, "phoneUnformatted": null,
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["kfz-in"],
            "gewerk": "Kfz"
          }
        ]
        """

        /** Gleiche placeIds, geänderte Stammdaten, plus ein neuer Betrieb. */
        const val SECOND_IMPORT = """
        [
          {
            "placeId": "P1",
            "title": "Elektro Beispiel GmbH & Co. KG",
            "categories": ["Elektriker", "Handwerk"],
            "alle_kategorien": ["Elektriker", "Handwerk"],
            "street": "Musterweg 1a", "city": "Ingolstadt", "postalCode": "85049",
            "phone": "+49 621 990 0011", "phoneUnformatted": "+496219900011",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in", "handwerk-in-2"],
            "gewerk": "Elektro"
          },
          {
            "placeId": "P2",
            "title": "Gartenpflege Erfunden",
            "categories": ["Gartenbauer"],
            "alle_kategorien": ["Gartenbauer"],
            "street": "Beispielstrasse 7", "city": "Eichstaett", "postalCode": "85072",
            "phone": "0621 9900012", "phoneUnformatted": null,
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["dienstleistung-in"],
            "gewerk": "GaLaBau"
          },
          {
            "placeId": "P9",
            "title": "Müller & Söhne Bedachungen",
            "categories": ["Dachdecker"],
            "alle_kategorien": ["Dachdecker"],
            "street": "Erfundenweg 3", "city": "Königsmoos", "postalCode": "86669",
            "phone": "+49 621 9900016", "phoneUnformatted": "+496219900016",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": "Dach"
          },
          {
            "placeId": "P3",
            "title": "Ohne Zuordnung Betrieb",
            "categories": [], "alle_kategorien": [],
            "street": null, "city": "Gaimersheim", "postalCode": "85080",
            "phone": "+49 621 9900013", "phoneUnformatted": "+496219900013",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in"],
            "gewerk": null
          },
          {
            "placeId": "P4",
            "title": "Beispiel Paketshop",
            "categories": ["Kurierdienst"], "alle_kategorien": ["Kurierdienst"],
            "street": "Testallee 3", "city": "Ingolstadt", "postalCode": "85051",
            "phone": "+49 621 9900014", "phoneUnformatted": "+496219900014",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["logistik-in"],
            "gewerk": "Paketdienst (kein Ziel)"
          },
          {
            "placeId": "P5",
            "title": "Kfz Fantasie",
            "categories": ["Autowerkstatt"], "alle_kategorien": ["Autowerkstatt"],
            "street": "Radweg 9", "city": "Beilngries", "postalCode": "92339",
            "phone": "+49 621 9900015", "phoneUnformatted": "+496219900015",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["kfz-in"],
            "gewerk": "Kfz"
          },
          {
            "placeId": "P6",
            "title": "Neuer Betrieb Erfunden",
            "categories": ["Dachdecker"], "alle_kategorien": ["Dachdecker"],
            "street": "Ziegelgasse 2", "city": "Ingolstadt", "postalCode": "85053",
            "phone": "+49 621 9900016", "phoneUnformatted": "+496219900016",
            "permanentlyClosed": false, "temporarilyClosed": false,
            "herkunft": ["handwerk-in-2"],
            "gewerk": "Bau"
          }
        ]
        """
    }
}
