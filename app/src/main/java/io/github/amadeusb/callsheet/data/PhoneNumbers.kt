package io.github.amadeusb.callsheet.data

/**
 * Phone numbers out of the research data arrive in varying shapes:
 * "+49 30 1234567", "0301234567", with and without spaces, with brackets.
 *
 * What gets stored is always the normalised E.164 form, otherwise matching
 * against the call log finds nothing — Android logs the number as dialled.
 */
object PhoneNumbers {

    /** German country code without the leading plus. */
    private const val DE = "49"

    /** Minimum length of a usable number without the country code. */
    private const val MIN_SUBSCRIBER_DIGITS = 5

    /**
     * Normalises to E.164 (+49…). `phoneUnformatted` is preferred, `phone`
     * serves as the fallback.
     *
     * @return the normalised number, or null when neither is usable.
     */
    fun normalize(phone: String?, phoneUnformatted: String?): String? =
        single(phoneUnformatted) ?: single(phone)

    /**
     * The tolerant form for matching against the call log: the last 8 digits.
     * "+493012345678" and "03012345678" reduce to the same value.
     *
     * @return the last 8 digits, or null when the number is too short.
     */
    fun comparableForm(number: String?): String? {
        val digits = number?.filter { it.isDigit() } ?: return null
        if (digits.length < 8) return null
        return digits.takeLast(8)
    }

    /** Normalises a single raw value. */
    private fun single(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val hasPlus = text.startsWith("+")
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // Already international: take it as it stands, other countries included.
        if (hasPlus) return check("+" + withoutTrunkPrefix(digits))

        // "0049…" — the international prefix written the national way.
        if (digits.startsWith("00")) {
            val rest = digits.drop(2)
            if (rest.isEmpty()) return null
            return check("+" + withoutTrunkPrefix(rest))
        }

        // "030…" — national notation with the trunk prefix.
        if (digits.startsWith("0")) {
            val rest = digits.trimStart('0')
            if (rest.length < MIN_SUBSCRIBER_DIGITS) return null
            return check("+$DE$rest")
        }

        // "30…" — written without the trunk prefix; read as a German number.
        if (digits.length < MIN_SUBSCRIBER_DIGITS) return null
        return check("+$DE$digits")
    }

    /**
     * Strips the bracketed trunk prefix behind the German country code:
     * "49 (0)30…" becomes "4930…".
     */
    private fun withoutTrunkPrefix(digits: String): String =
        if (digits.startsWith(DE + "0")) DE + digits.drop(3).trimStart('0') else digits

    /** Discards results that are obviously unusable. */
    private fun check(candidate: String): String? {
        val digits = candidate.drop(1)
        // Country code (1–3 digits) plus the subscriber part.
        if (digits.length < MIN_SUBSCRIBER_DIGITS + 1) return null
        if (digits.length > 15) return null
        if (digits.all { it == '0' }) return null
        return candidate
    }
}
