package io.github.amadeusb.callsheet

import androidx.test.core.app.ApplicationProvider
import io.github.amadeusb.callsheet.data.CallEntry
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.Database
import io.github.amadeusb.callsheet.data.EntryKind
import io.github.amadeusb.callsheet.data.PhoneDraft
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Repository
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirtyTest {

    private lateinit var repo: Repository
    private lateinit var ctx: android.content.Context

    @Before
    fun aufbau() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("callsheet.db")
        repo = Repository(ctx)
    }

    private fun zahl(sql: String): Int =
        Database(ctx).readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private suspend fun einBetrieb(): String =
        repo.create(io.github.amadeusb.callsheet.data.BusinessDraft(
            name = "Elektro Meier", industry = "Elektro", city = "Ingolstadt", phone = "08411 12345",
        )).getOrThrow()

    @Test
    fun `setting a status marks the row`() = runTest {
        val id = einBetrieb()
        repo.setStatus(id, Status.CALLED)
        assertEquals(1, zahl("SELECT dirty FROM businesses WHERE place_id = '$id'"))
    }

    @Test
    fun `a logged call is marked`() = runTest {
        val id = einBetrieb()
        repo.logCall(CallEntry(
            id = "A1", placeId = id, startedAt = Clock.now(), durationSeconds = 60,
            outcome = null, note = null, kind = EntryKind.CALL, contact = null,
        ))
        assertEquals(1, zahl("SELECT dirty FROM calls WHERE id = 'A1'"))
    }

    @Test
    fun `completing a call refreshes its updated_at and marks it`() = runTest {
        val id = einBetrieb()
        repo.logCall(CallEntry(
            id = "A1", placeId = id, startedAt = "2026-09-07T10:00:00+02:00", durationSeconds = 60,
            outcome = null, note = null, kind = EntryKind.CALL, contact = null,
        ))
        repo.completeCall("A1", "appointment", "Termin am Freitag")
        val db = Database(ctx).readableDatabase
        db.rawQuery("SELECT updated_at, dirty FROM calls WHERE id = 'A1'", null).use {
            it.moveToFirst()
            assertEquals(1, it.getInt(1))
            org.junit.Assert.assertNotEquals("2026-09-07T10:00:00+02:00", it.getString(0))
        }
    }

    @Test
    fun `a saved contact marks contact and numbers`() = runTest {
        val id = einBetrieb()
        val kontakt = repo.saveContact(ContactDraft(
            placeId = id, name = "Frau Meier",
            numbers = listOf(PhoneDraft(number = "0176 12345678", kind = PhoneType.MOBILE)),
        )).getOrThrow()
        assertEquals(1, zahl("SELECT dirty FROM contacts WHERE id = '$kontakt'"))
        assertEquals(1, zahl("SELECT dirty FROM contact_numbers WHERE contact_id = '$kontakt'"))
    }

    @Test
    fun `a merge with the phone book is not a content change`() = runTest {
        val id = einBetrieb()
        val kontakt = repo.saveContact(ContactDraft(placeId = id, name = "Frau Meier")).getOrThrow()
        Database(ctx).writableDatabase.execSQL("UPDATE contacts SET dirty = 0")
        repo.setContactVersion(kontakt, 7)
        assertEquals(0, zahl("SELECT dirty FROM contacts WHERE id = '$kontakt'"))
    }

    @Test
    fun `deleting a contact leaves tombstones for it and its numbers`() = runTest {
        val id = einBetrieb()
        val kontakt = repo.saveContact(ContactDraft(
            placeId = id, name = "Frau Meier",
            numbers = listOf(PhoneDraft(number = "0176 12345678", kind = PhoneType.MOBILE)),
        )).getOrThrow()
        repo.deleteContact(kontakt)
        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contacts' AND row_id = '$kontakt'"))
        assertEquals(1, zahl("SELECT COUNT(*) FROM deletions WHERE table_name = 'contact_numbers'"))
    }

    @Test
    fun `an import marks what it wrote`() = runTest {
        repo.import(IMPORT.byteInputStream())
        assertEquals(1, zahl("SELECT dirty FROM businesses WHERE place_id = 'P1'"))
    }

    private companion object {
        const val IMPORT = """[{"placeId":"P1","title":"Elektro Meier","gewerk":"Elektro","city":"Ingolstadt","phone":"08411 12345"}]"""
    }
}
