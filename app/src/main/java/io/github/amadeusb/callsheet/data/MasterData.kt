package io.github.amadeusb.callsheet.data

import org.json.JSONArray

/**
 * A business's master data as the user edits it: trimmed, empty as null, the
 * number normalised. Two of these compare equal when saving would change
 * nothing.
 */
data class MasterValues(
    val name: String,
    val phone: String?,
    val industry: String?,
    val website: String?,
    val email: String?,
    val contactName: String?,
) {
    /** The values by column name, in [MasterData.EDITABLE] order. */
    fun byColumn(): Map<String, String?> = linkedMapOf(
        "name" to name,
        "phone" to phone,
        "industry" to industry,
        "website" to website,
        "email" to email,
        "contact_name" to contactName,
    )
}

/**
 * Master data changed by hand, and the list that remembers it
 * (`businesses.edited_fields`). The import leaves every column named there as
 * it is stored, so a hand edit survives it — on every device, since the list
 * travels with the business.
 */
object MasterData {

    /** The columns the form in editing mode changes, by their column names. */
    val EDITABLE = listOf("name", "phone", "industry", "website", "email", "contact_name")

    fun of(business: Business): MasterValues = MasterValues(
        name = business.name.trim(),
        phone = business.phone.clean(),
        industry = business.industry.clean(),
        website = business.website.clean(),
        email = business.email.clean(),
        contactName = business.contactName.clean(),
    )

    /**
     * What the form holds. The number is normalised to E.164; empty or
     * incomplete it is null — the caller tells the two apart by the draft's
     * own text, as Repository.create does.
     */
    fun fromDraft(draft: BusinessDraft): MasterValues = MasterValues(
        name = draft.name.trim(),
        phone = draft.phone.trim().let { PhoneNumbers.normalize(it, it) },
        industry = draft.industry.clean(),
        website = draft.website.clean(),
        email = draft.email.clean(),
        contactName = draft.contactName.clean(),
    )

    /** The columns whose value differs. Clearing a field is a difference. */
    fun changedFields(before: MasterValues, after: MasterValues): Set<String> {
        val old = before.byColumn()
        val new = after.byColumn()
        return EDITABLE.filter { old[it] != new[it] }.toSet()
    }

    /**
     * The stored list. Unreadable text reads as empty. A name this version does
     * not edit is kept: a later version may edit more, and this version's
     * import must not undo that.
     */
    fun parse(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(text)
            (0 until array.length())
                .mapNotNull { i -> if (array.isNull(i)) null else array.optString(i, "").ifEmpty { null } }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Sorted, so two devices holding the same set hold the same text. Null for none. */
    fun format(fields: Set<String>): String? =
        if (fields.isEmpty()) null else JSONArray(fields.sorted()).toString()

    /** The form in editing mode, filled from [business]. Addresses have their own screen. */
    fun draft(business: Business): BusinessDraft = BusinessDraft(
        name = business.name,
        phone = business.phone.orEmpty(),
        industry = business.industry.orEmpty(),
        addresses = emptyList(),
        website = business.website.orEmpty(),
        email = business.email.orEmpty(),
        contactName = business.contactName.orEmpty(),
    )

    /**
     * The detail view's label for `contact_name`. „(importiert)" says where the
     * name came from — no longer true once it was changed by hand.
     */
    fun contactNameLabel(editedFields: Set<String>): String =
        if ("contact_name" in editedFields) "Ansprechpartner" else "Ansprechpartner (importiert)"

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }
}
