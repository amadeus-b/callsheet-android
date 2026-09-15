package io.github.amadeusb.callsheet.data

import org.json.JSONArray

/** An address offered to a visit's attendees: the list afterwards, and why it was refused, if it was. */
data class AttendeeAdd(val addresses: List<String>, val error: String?)

/**
 * A visit's attendees (`appointments.attendees`): addresses, trimmed, in the
 * order added, each once ignoring case. Stored as a JSON array, none as null.
 * The server reads the same list and compares it as a set.
 */
object Attendees {

    /** Said under the field when a typed address is refused. */
    const val INVALID: String = "Das ist keine gültige E-Mail-Adresse."

    /**
     * The calendar account every visit is organised by — the address the
     * server's visitState calls ORGANIZER, and the name in the default title.
     */
    const val ORGANIZER: String = "christoph@bauer-ki.de"

    /** Said when [ORGANIZER] is offered as an attendee: it organises every visit anyway. */
    const val OWN_ADDRESS: String = "Die eigene Adresse ist immer dabei."

    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

    /** Trimmed, blanks dropped, the first spelling of each address kept. */
    fun clean(addresses: List<String>): List<String> {
        val result = ArrayList<String>(addresses.size)
        for (raw in addresses) {
            val address = raw.trim()
            if (address.isEmpty() || contains(result, address)) continue
            result.add(address)
        }
        return result
    }

    /** The stored text. Unreadable text reads as nobody. */
    fun parse(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            clean((0 until array.length()).mapNotNull { i -> if (array.isNull(i)) null else array.optString(i, "") })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** What is stored. Null for nobody. */
    fun format(addresses: List<String>): String? =
        clean(addresses).takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }

    fun contains(addresses: List<String>, address: String): Boolean =
        addresses.any { it.trim().equals(address.trim(), ignoreCase = true) }

    /** The same people, whatever the order, case or whitespace. */
    fun sameSet(a: List<String>, b: List<String>): Boolean {
        val left = clean(a)
        val right = clean(b)
        return left.size == right.size && left.all { contains(right, it) }
    }

    /** In [after] and not in [before]. */
    fun added(before: List<String>, after: List<String>): List<String> = clean(after).filterNot { contains(before, it) }

    /** In [before] and not in [after]. */
    fun removed(before: List<String>, after: List<String>): List<String> = clean(before).filterNot { contains(after, it) }

    /**
     * [typed] added to [addresses]. Blank, or already there, changes nothing
     * and is no error; the organizer's own address is refused with
     * [OWN_ADDRESS], something that is not an address with [INVALID].
     */
    fun add(addresses: List<String>, typed: String): AttendeeAdd {
        val address = typed.trim()
        if (address.isEmpty() || contains(addresses, address)) return AttendeeAdd(addresses, null)
        if (address.equals(ORGANIZER, ignoreCase = true)) return AttendeeAdd(addresses, OWN_ADDRESS)
        if (!EMAIL.matches(address)) return AttendeeAdd(addresses, INVALID)
        return AttendeeAdd(addresses + address, null)
    }

    /** „a", „a, b", „a, b, c", and past three „a, b und 2 weitere" — a dialog title stays readable. */
    fun names(addresses: List<String>): String {
        if (addresses.size <= 3) return addresses.joinToString(", ")
        return "${addresses.take(2).joinToString(", ")} und ${addresses.size - 2} weitere"
    }
}
