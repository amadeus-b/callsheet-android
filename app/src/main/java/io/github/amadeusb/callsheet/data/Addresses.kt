package io.github.amadeusb.callsheet.data

/**
 * The rules around a business's addresses, in one place: which one is the main
 * address, which one a contact person stands for, how an address reads on one
 * line, what the search reads. Without Android access, so the rules stay
 * testable.
 */
object Addresses {

    /**
     * The id prefix of the address carried over from the business's own columns
     * (schema 7, server migration 009), and the one the import keeps up to date.
     */
    const val MAIN_PREFIX = "main-"

    /** Offered as chips under „Bezeichnung"; free text stays possible. */
    val LABEL_SUGGESTIONS = listOf("Hauptsitz", "Filiale", "Lager", "Baustelle")

    fun mainId(placeId: String): String = MAIN_PREFIX + placeId

    /** Street, postal code and city on one line. Null when nothing is known. */
    fun oneLine(street: String?, postalCode: String?, city: String?): String? {
        val town = listOfNotNull(
            postalCode?.trim()?.ifEmpty { null },
            city?.trim()?.ifEmpty { null },
        ).joinToString(" ").ifEmpty { null }
        return listOfNotNull(street?.trim()?.ifEmpty { null }, town)
            .joinToString(", ")
            .ifEmpty { null }
    }

    /** As the business holds them: by position, a missing position last. */
    fun ordered(addresses: List<BusinessAddress>): List<BusinessAddress> =
        addresses.sortedWith(compareBy<BusinessAddress> { it.position == null }.thenBy { it.position ?: 0 })

    fun main(addresses: List<BusinessAddress>): BusinessAddress? = ordered(addresses).firstOrNull()

    /**
     * The address [addressId] names. Null for no assignment, and for a row that
     * is not here — deleted on another device, or not synchronised yet.
     */
    fun assigned(addressId: String?, addresses: List<BusinessAddress>): BusinessAddress? =
        addressId?.let { id -> addresses.firstOrNull { it.id == id } }

    /** Where a contact person is: the address they are assigned to, else the main address. */
    fun forContact(addressId: String?, addresses: List<BusinessAddress>): BusinessAddress? =
        assigned(addressId, addresses) ?: main(addresses)

    /** How a choice shows an address: its label, else the address itself. */
    fun name(address: BusinessAddress): String =
        address.label?.trim()?.ifEmpty { null } ?: address.oneLine ?: "Adresse"

    /**
     * The business's name and the cities of all its addresses, lower-cased.
     * SQLite only lower-cases ASCII; without this "müller" would not find
     * "Müller".
     */
    fun searchText(name: String, cities: List<String?>): String =
        (listOf(name) + cities.mapNotNull { it?.trim()?.ifEmpty { null } }).joinToString(" ").lowercase()

    /** The rows as a form edits them, main address first. */
    fun drafts(addresses: List<BusinessAddress>): List<AddressDraft> =
        ordered(addresses).map {
            AddressDraft(
                id = it.id,
                label = it.label.orEmpty(),
                street = it.street.orEmpty(),
                postalCode = it.postalCode.orEmpty(),
                city = it.city.orEmpty(),
            )
        }

    /**
     * Distance and driving time from the office on one line, „23 km · 21 min".
     * Null unless both are known.
     */
    fun driveLine(meters: Int?, seconds: Int?): String? {
        if (meters == null || seconds == null) return null
        val km = Math.round(meters / 1000.0)
        val distance = if (meters < 1000) "< 1 km" else "$km km"
        val minutes = maxOf(1L, Math.round(seconds / 60.0))
        val time = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
        return "$distance · $time"
    }

    /** „Als Hauptadresse": the row at [index] moves to the top, the others keep their order. */
    fun makeMain(drafts: List<AddressDraft>, index: Int): List<AddressDraft> {
        if (index !in drafts.indices) return drafts
        return listOf(drafts[index]) + drafts.filterIndexed { i, _ -> i != index }
    }
}
