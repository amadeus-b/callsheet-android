package io.github.amadeusb.callsheet.contacts

import android.content.Context
import io.github.amadeusb.callsheet.calling.Appointment

/**
 * The app's handful of settings. SharedPreferences on purpose: three values
 * that cost nothing even when the database is wiped.
 */
class Preferences(context: Context) {

    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Whether contacts and businesses get written to the phone book at all. */
    var phoneBookEnabled: Boolean
        get() = store.getBoolean(PHONE_BOOK_ENABLED, false)
        set(value) = store.edit().putBoolean(PHONE_BOOK_ENABLED, value).apply()

    /** The chosen address book account that gets written to. */
    var account: AddressBookAccount?
        get() {
            val name = store.getString(ACCOUNT_NAME, null) ?: return null
            val type = store.getString(ACCOUNT_TYPE, null) ?: return null
            return AddressBookAccount(name, type)
        }
        set(value) {
            store.edit()
                .putString(ACCOUNT_NAME, value?.name)
                .putString(ACCOUNT_TYPE, value?.type)
                .apply()
        }

    /** Address of the sync server. Empty means: no synchronisation at all. */
    var serverUrl: String?
        get() = store.getString(SERVER_URL, null)?.ifBlank { null }
        set(value) = store.edit().putString(SERVER_URL, value?.trim()?.trimEnd('/')).apply()

    var serverToken: String?
        get() = store.getString(SERVER_TOKEN, null)?.ifBlank { null }
        set(value) = store.edit().putString(SERVER_TOKEN, value?.trim()).apply()

    /** How far the server has been read. A number, never a timestamp. */
    var watermark: Int
        get() = store.getInt(WATERMARK, 0)
        set(value) = store.edit().putInt(WATERMARK, value).apply()

    /** When the last sync went through completely. */
    var lastSyncAt: String?
        get() = store.getString(LAST_SYNC_AT, null)
        set(value) = store.edit().putString(LAST_SYNC_AT, value).apply()

    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about appointments (schema 4).
     *
     * While it still ran 1.3.x, the server delivered appointments to it — the
     * ones migration 005 carried over, and any another device created — and
     * the old app skipped them while its watermark moved past. They would never
     * come down again. Worse, an appointment deleted elsewhere would come back:
     * the migration writes its legacy row afresh, the server drops it for its
     * newer tombstone, and the tombstone never reaches this device. One fetch
     * from zero repairs both, and costs one full download.
     *
     * A flag here rather than a step in the migration, because the watermark
     * lives in these preferences and not in the database.
     */
    var refetchedForAppointments: Boolean
        get() = store.getBoolean(REFETCHED_FOR_APPOINTMENTS, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_APPOINTMENTS, value).apply()

    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about callbacks (schema 6).
     *
     * While it still ran 1.4.0, the server delivered callbacks to it — the ones
     * migration 008 carried over, and any another device created or completed —
     * and the old app stored them without `kind` and `done_at` while its
     * watermark moved past. After the update they read as visits, and nothing
     * brings the two columns down again: the rows do not change on the server.
     * Worse, saving one through „Ändern" would send `kind: 'visit'` with a newer
     * `updated_at` and turn the callback into a visit on every device.
     *
     * One fetch from zero delivers each row again at a standstill, and the
     * store fills the gaps (SyncStore.fillGaps). A flag for the reason
     * [refetchedForAppointments] gives.
     */
    var refetchedForCallbacks: Boolean
        get() = store.getBoolean(REFETCHED_FOR_CALLBACKS, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_CALLBACKS, value).apply()

    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about several addresses per business (schema 7).
     *
     * While it still ran 1.5.x, the server delivered `business_addresses` rows
     * to it — the ones migration 009 carried over, and any another device added
     * — and the old app skipped them while its watermark moved past. Its own
     * migration only brings the main address it had; a branch added elsewhere,
     * or a later change, would never come down. Contacts it stored without
     * `address_id` get the column filled at the standstill (SyncStore.fillGaps).
     * A flag for the reason [refetchedForAppointments] gives.
     */
    var refetchedForAddresses: Boolean
        get() = store.getBoolean(REFETCHED_FOR_ADDRESSES, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_ADDRESSES, value).apply()

    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt which master data was changed by hand (schema 9).
     *
     * While it still ran 1.5.0, the server delivered businesses whose
     * `edited_fields` another device had set, and the old app stored them
     * without the column while its watermark moved past. After the update
     * those rows hold NULL: an import here would overwrite the hand edits, and
     * the next save of such a business would send the NULL up as the newer
     * row. One fetch from zero brings each row again at a standstill, and the
     * store fills the gap (SyncStore.fillGaps). A flag for the reason
     * [refetchedForAppointments] gives.
     */
    var refetchedForEditedFields: Boolean
        get() = store.getBoolean(REFETCHED_FOR_EDITED_FIELDS, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_EDITED_FIELDS, value).apply()

    /**
     * Whether this device has fetched the server's stock from the start since
     * it learnt about attendees (schema 10).
     *
     * While it ran the version before, the server delivered visits whose
     * `attendees` another device had set, and the old app stored them without
     * the column while its watermark moved past. After the update those visits
     * would show nobody, and the next save would send the empty list up as the
     * newer row. One fetch from zero brings each row again at a standstill, and
     * the store fills the gap. A flag for the reason [refetchedForAppointments]
     * gives.
     */
    var refetchedForAttendees: Boolean
        get() = store.getBoolean(REFETCHED_FOR_ATTENDEES, false)
        set(value) = store.edit().putBoolean(REFETCHED_FOR_ATTENDEES, value).apply()

    /** Whether appointments get mirrored into the device calendar at all. */
    var calendarEnabled: Boolean
        get() = store.getBoolean(CALENDAR_ENABLED, false)
        set(value) = store.edit().putBoolean(CALENDAR_ENABLED, value).apply()

    /** The chosen calendar that gets written to. */
    var calendarId: Long?
        get() = store.getLong(CALENDAR_ID, NO_CALENDAR).takeIf { it != NO_CALENDAR }
        set(value) = store.edit().putLong(CALENDAR_ID, value ?: NO_CALENDAR).apply()

    /**
     * The duration a fresh appointment starts at, in minutes. It follows the
     * last appointment saved — a setting that tunes itself instead of one to go
     * looking for.
     */
    var appointmentMinutes: Int
        get() = store.getInt(APPOINTMENT_MINUTES, Appointment.DEFAULT_MINUTES)
        set(value) = store.edit().putInt(APPOINTMENT_MINUTES, value).apply()

    /** The subject line the mail dialog starts from. `{{business_name}}` is replaced before sending. */
    var mailTemplateSubject: String
        get() = store.getString(MAIL_TEMPLATE_SUBJECT, null) ?: DEFAULT_MAIL_SUBJECT
        set(value) = store.edit().putString(MAIL_TEMPLATE_SUBJECT, value).apply()

    /** The body the mail dialog starts from. `{{business_name}}` is replaced before sending. */
    var mailTemplateBody: String
        get() = store.getString(MAIL_TEMPLATE_BODY, null) ?: DEFAULT_MAIL_BODY
        set(value) = store.edit().putString(MAIL_TEMPLATE_BODY, value).apply()

    /**
     * Forgets the saved subject and body, so the mail dialog starts from
     * [DEFAULT_MAIL_SUBJECT] and [DEFAULT_MAIL_BODY] again. The settings save
     * every keystroke, an emptied field included — without this, a template
     * once touched would never show a newer default.
     */
    fun resetMailTemplate() {
        store.edit().remove(MAIL_TEMPLATE_SUBJECT).remove(MAIL_TEMPLATE_BODY).apply()
    }

    private companion object {
        const val PHONE_BOOK_ENABLED = "phone_book_enabled"
        const val ACCOUNT_NAME = "phone_book_account_name"
        const val ACCOUNT_TYPE = "phone_book_account_type"
        const val SERVER_URL = "server_url"
        const val SERVER_TOKEN = "server_token"
        const val WATERMARK = "sync_watermark"
        const val LAST_SYNC_AT = "last_sync_at"
        const val REFETCHED_FOR_APPOINTMENTS = "refetched_for_appointments"
        const val REFETCHED_FOR_CALLBACKS = "refetched_for_callbacks"
        const val REFETCHED_FOR_ADDRESSES = "refetched_for_addresses"
        const val REFETCHED_FOR_EDITED_FIELDS = "refetched_for_edited_fields"
        const val REFETCHED_FOR_ATTENDEES = "refetched_for_attendees"
        const val CALENDAR_ENABLED = "calendar_enabled"
        const val CALENDAR_ID = "calendar_id"
        const val APPOINTMENT_MINUTES = "appointment_minutes"
        const val NO_CALENDAR = -1L
        const val MAIL_TEMPLATE_SUBJECT = "mail_template_subject"
        const val MAIL_TEMPLATE_BODY = "mail_template_body"

        /**
         * The signature every mail goes out under, verbatim as it is kept for the
         * sender domain bauer-ki.de. Grußformel included: it belongs to the
         * signature, it is never part of the body text itself.
         *
         * The block after the contact line is the legal one. A UG's business mail
         * counts as a Geschäftsbrief under § 35a GmbHG, so firm with its legal
         * form, seat, register court, HRB and Geschäftsführer have to be in it.
         * No `-- ` separator in front: mail clients grey out everything behind it
         * and drop it when replying, Grußformel and all.
         */
        const val MAIL_SIGNATURE = "Mit bestem Gruß\n" +
            "Christoph Bauer\n\n" +
            "KI-Automatisierung für Betriebe in Ingolstadt & Region 10\n" +
            "Tel. 0174 5228788 · christoph@bauer-ki.de · bauer-ki.de\n\n" +
            "TM Services UG (haftungsbeschränkt) · Im Ebenfeld 8c · 94536 Eppenschlag\n" +
            "Sitz: Eppenschlag · Registergericht: Amtsgericht Passau · HRB 12759\n" +
            "Geschäftsführer: Christoph Bauer · USt-IdNr.: DE452785562"

        const val DEFAULT_MAIL_SUBJECT = "Wie besprochen: KI-Automatisierung für {{business_name}}"
        /**
         * `[Name]` is left for the salutation, to be filled in by hand before
         * sending; the server refuses a mail that still contains it.
         */
        const val DEFAULT_MAIL_BODY = "Guten Tag [Name],\n\n" +
            "vielen Dank für das freundliche Telefonat. Wie besprochen hier ein paar Infos.\n\n" +
            "Ich helfe Betrieben in Ingolstadt und Umgebung dabei, wiederkehrende Büroarbeit " +
            "zu automatisieren - aufbauend auf den Programmen, die Sie ohnehin schon nutzen, " +
            "oder ergänzend mit neuen Lösungen, um Optimierungen bestmöglich umzusetzen.\n\n" +
            "- Anfragen und E-Mails werden erkannt, einsortiert und als fertiger Entwurf " +
            "vorbereitet oder nach Freigabe direkt versendet.\n" +
            "- Dokumente landen per Texterkennung automatisch in der richtigen Ablage und " +
            "sind durchsuchbar.\n" +
            "- Auswertungen und kleine Werkzeuge, zugeschnitten auf Ihre Abläufe.\n\n" +
            "KI kann heutzutage viele Dinge vollautomatisch übernehmen. Sie bestimmen, wie viel " +
            "sie eigenständig darf und welche Schritte nur nach menschlicher Freigabe " +
            "ausgeführt werden. Als ausgebildeter Datenschutzbeauftragter (BDSG/DSGVO) sorge " +
            "ich dafür, dass die KI nur das tut, was Sie freigegeben haben - und nur mit den " +
            "Daten, die sie dafür braucht.\n\n" +
            "Der nächste Schritt kostet Sie nichts: eine Analyse bei Ihnen vor Ort.\n\n" +
            "Buchen Sie gerne unverbindlich Ihren Termin: https://bauer-ki.de/termin/\n\n" +
            "Für Rückfragen erreichen Sie mich gerne per E-Mail oder Telefon.\n\n" +
            MAIL_SIGNATURE
    }
}
