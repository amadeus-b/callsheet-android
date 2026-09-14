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
            ?.takeIf { name -> contacts.none { samePerson(it.name, name) } }

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

    // Typed by hand or pasted from an imprint, a name easily picks up a second space.
    private fun samePerson(a: String, b: String): Boolean =
        a.collapseSpaces().equals(b.collapseSpaces(), ignoreCase = true)

    private fun String.collapseSpaces(): String = trim().split(Regex("\\s+")).joinToString(" ")
}
