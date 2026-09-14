package io.github.amadeusb.callsheet.data

/** The allowed working statuses. Order = order of the buttons in the detail view. */
enum class Status(val key: String, val label: String) {
    NEW("new", "Neu"),
    CALLED("called", "Angerufen"),
    NO_ANSWER("no_answer", "Nicht erreicht"),
    MAIL_SENT("mail_sent", "Mail gesendet"),
    APPOINTMENT("appointment", "Termin"),
    DECLINED("declined", "Abgelehnt"),
    DO_NOT_CALL("do_not_call", "Sperre");

    companion object {
        fun fromKey(s: String?): Status =
            entries.firstOrNull { it.key == s } ?: NEW
    }
}

/** A business — imported master data plus the fields worked on in the app. */
data class Business(
    val placeId: String,
    val name: String,
    val industry: String?,
    val categories: List<String>,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val contactName: String?,
    val rating: Double?,
    val ratingCount: Int?,
    val closed: Boolean,
    val isTarget: Boolean,
    val origin: List<String>,
    val collectedAt: String?,
    val status: Status,
    val note: String?,
    val updatedAt: String,
    /** From the import. Nothing reads them yet. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * How many dialable numbers hang off the contacts (fax excluded). Only the
     * list queries fill this in; everywhere else it stays 0.
     */
    val additionalNumbers: Int = 0,
) {
    /** Whether any number at all can be dialled — the main one or a contact's. */
    val hasNumber: Boolean
        get() = !phone.isNullOrBlank() || additionalNumbers > 0
}

/** Where a log entry came from. */
enum class EntryKind(val key: String) {
    /** Came out of a dial attempt — carries a call duration. */
    CALL("call"),

    /** Recorded on the record without a call taking place. */
    NOTE("note");

    companion object {
        fun fromKey(s: String?): EntryKind =
            entries.firstOrNull { it.key == s } ?: CALL
    }
}

/**
 * One entry in a business's log.
 *
 * Entries are appended and only ever gain the outcome filled in afterwards —
 * time and duration stay exactly as they were recorded.
 */
data class CallEntry(
    val id: String,
    val placeId: String,
    val startedAt: String,
    val durationSeconds: Int,
    val outcome: String?,
    val note: String?,
    val kind: EntryKind = EntryKind.CALL,
    /** Who was called — empty as long as only the main number existed. */
    val contact: String? = null,
)

/**
 * What an appointment is. Stored as [key]. A null or unknown key reads as
 * [VISIT]: every appointment before schema 6 is one, and so is every one a
 * 1.4.0 device still creates.
 */
enum class AppointmentKind(val key: String, val label: String) {
    VISIT("visit", "Vor Ort"),
    CALLBACK("callback", "Rückruf");

    companion object {
        fun fromKey(s: String?): AppointmentKind =
            entries.firstOrNull { it.key == s } ?: VISIT
    }
}

/**
 * An appointment: on site, or a callback. A business can have any number of
 * them — one after another, or side by side.
 *
 * [eventUid] names the linked calendar event on every device carrying the
 * shared calendar. [calendarEventId] and the `seen` fields describe this
 * device's calendar only and never travel.
 */
data class AppointmentEntry(
    val id: String,
    val placeId: String,
    val startsAt: String,
    val endsAt: String?,
    /** One line, as it goes into the calendar event. */
    val location: String?,
    /** „Besichtigung", „Angebot" … */
    val note: String?,
    /** One of the business's contacts. A reference and nothing more: it may point at a deleted one. */
    val contactId: String?,
    /** As stored. Saving ignores it — the repository stamps every save itself. */
    val updatedAt: String = "",
    val eventUid: String? = null,
    /** The event's `_ID` on this device — a shortcut, see Appointment.locate. */
    val calendarEventId: Long? = null,
    /** What this device last saw in the event. All null while it never saw it. */
    val seenStartsAt: String? = null,
    val seenEndsAt: String? = null,
    val seenLocation: String? = null,
    val kind: AppointmentKind = AppointmentKind.VISIT,
    /** When a callback was completed. Null while open, and always for a visit. */
    val doneAt: String? = null,
)

/**
 * The calendar events appointments already hold, by UID and by this device's
 * event id. An event in here is not offered for linking again: two
 * appointments sharing one event would change and delete each other's.
 */
data class TakenEvents(val uids: Set<String>, val eventIds: Set<Long>)

/**
 * The kind of a phone number — the same categories the Contacts app uses.
 *
 * [vcard] records which TEL type the number carries inside a vCard; later
 * synchronisation over WebDAV needs exactly that mapping.
 */
enum class PhoneType(
    val key: String,
    val label: String,
    val vcard: String,
) {
    MOBILE("mobile", "Mobil", "CELL"),
    WORK("work", "Geschäft", "WORK"),
    MAIN("main", "Zentrale", "WORK"),
    HOME("home", "Privat", "HOME"),
    FAX("fax", "Fax", "FAX"),
    OTHER("other", "Weitere", "VOICE");

    companion object {
        fun fromKey(s: String?): PhoneType =
            entries.firstOrNull { it.key == s } ?: OTHER
    }
}

/** A contact's phone number, always stored in E.164 form. */
data class PhoneNumber(
    val id: String,
    val number: String,
    val kind: PhoneType,
)

/** A contact's email address — several are possible, the same way numbers are. */
data class ContactEmail(
    val id: String,
    val email: String,
    val position: Int,
)

/**
 * A business's contact person with any number of phone numbers.
 *
 * The id is a UUID and stays stable — it later doubles as the vCard UID for
 * synchronisation. Contacts are never overwritten by an import; they only ever
 * come into being inside the app.
 */
data class Contact(
    val id: String,
    val placeId: String,
    val name: String,
    val role: String?,
    val email: String?,
    val note: String?,
    val numbers: List<PhoneNumber>,
    val updatedAt: String,
    /** Version of the phone book entry at the last merge; null means there was none. */
    val contactVersion: Int? = null,
    /** The contact's email addresses, in the order they were entered. */
    val emails: List<ContactEmail> = emptyList(),
)

/** One number row in the edit form — text as typed, not yet validated. */
data class PhoneDraft(
    val id: String? = null,
    val number: String = "",
    val kind: PhoneType = PhoneType.MOBILE,
)

/** One email row in the edit form — text as typed, not yet validated. */
data class EmailDraft(
    val id: String? = null,
    val email: String = "",
)

/**
 * A contact as it is being edited. Everything is text, exactly as typed;
 * validation and normalisation happen when the repository saves it.
 */
data class ContactDraft(
    val id: String? = null,
    val placeId: String = "",
    val name: String = "",
    val role: String = "",
    val email: String = "",
    val note: String = "",
    val numbers: List<PhoneDraft> = listOf(PhoneDraft()),
    val emails: List<EmailDraft> = listOf(EmailDraft()),
)

/** A dialable number of a business — the main one or a contact's. */
data class DialTarget(
    val number: String,
    /** Who is being called, for the picker and the log: „Frau Meier · Mobil“. */
    val label: String,
)

/** Master data as it arrives from the import file — without working fields. */
data class ImportedBusiness(
    val placeId: String,
    val name: String,
    val industry: String?,
    val categories: List<String>,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val contactName: String?,
    val rating: Double?,
    val ratingCount: Int?,
    val closed: Boolean,
    val isTarget: Boolean,
    val origin: List<String>,
    val collectedAt: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Prefix for the ids of hand-entered businesses. No research export produces
 * anything like it, so a later import can never hit one of them.
 */
const val MANUAL_PREFIX = "manual:"

/** First entry in `origin` for a hand-entered business. */
const val ORIGIN_MANUAL = "selbst erfasst"

/**
 * A hand-entered business as it is being typed.
 *
 * Everything is text, exactly as entered; the repository normalises and
 * validates it on creation.
 */
data class BusinessDraft(
    val name: String = "",
    val phone: String = "",
    val industry: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val website: String = "",
    val email: String = "",
    val contactName: String = "",
    val note: String = "",
    val origin: String = "",
)

/** The work list's filter state. */
data class Filter(
    val industries: Set<String> = emptySet(),
    val unassigned: Boolean = false,
    val cities: Set<String> = emptySet(),
    val status: Set<Status> = setOf(Status.NEW),
    val onlyTargets: Boolean = true,
    val search: String = "",
)

/** The outcome of an import, for the summary shown afterwards. */
data class ImportResult(
    val new: Int,
    val updated: Int,
    val withoutPhone: Int,
    val total: Int,
    val error: String? = null,
)
