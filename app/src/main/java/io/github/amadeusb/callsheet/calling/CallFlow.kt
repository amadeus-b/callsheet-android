package io.github.amadeusb.callsheet.calling

import android.content.Intent
import android.net.Uri
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.DialTarget
import io.github.amadeusb.callsheet.data.Clock

/** What the app proposes after a call. Only ever offered, never applied. */
data class Suggestion(
    val status: Status?,
    val followUpIso: String?,
    val hint: String,
)

/** The path from the dial button back into recording the outcome. */
object CallFlow {

    /**
     * Builds the ACTION_DIAL intent. Deliberately DIAL rather than CALL: the
     * dialler shows the number ready to go and the user presses call himself.
     * That way the app never needs CALL_PHONE.
     */
    fun dialIntent(number: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Every number dialable for this business: the main number from the master
     * data first, then the contacts' numbers in their own order. Fax numbers
     * stay out — nobody calls those.
     *
     * Duplicates appear once. When the same number sits on both the business
     * and a contact, the contact wins, because a name says more in the log than
     * „Betriebsnummer“ does.
     */
    fun dialTargets(business: Business, contacts: List<Contact>): List<DialTarget> {
        val fromContacts = contacts.flatMap { contact ->
            contact.numbers
                .filter { it.kind != PhoneType.FAX }
                .map { DialTarget(it.number, "${contact.name} · ${it.kind.label}") }
        }
        val main = business.phone
            ?.takeIf { it.isNotBlank() && fromContacts.none { target -> target.number == it } }
            ?.let { listOf(DialTarget(it, "Betriebsnummer")) }
            .orEmpty()
        return (main + fromContacts).distinctBy { it.number }
    }

    /**
     * What to propose after the call. A null [duration] means the call log could
     * not be read. Every proposal has to be overridable — even a duration of 0
     * can mean somebody picked up and hung up straight away.
     */
    fun suggestion(duration: Int?): Suggestion = when {
        duration == null -> Suggestion(
            status = null,
            followUpIso = null,
            hint = "Die Gesprächsdauer konnte nicht aus dem Anrufprotokoll gelesen werden. Bitte Status selbst setzen.",
        )
        duration == 0 -> Suggestion(
            status = Status.NO_ANSWER,
            followUpIso = FollowUp.inTwoDays(differentTimeOfDay = true),
            hint = "Keine Verbindung. Vorschlag: nicht erreicht, Wiedervorlage in zwei Tagen zu einer anderen Tageszeit.",
        )
        else -> Suggestion(
            status = null,
            followUpIso = null,
            hint = "Gespräch geführt (${Clock.duration(duration)}). Notiz erfassen und Status wählen.",
        )
    }
}
