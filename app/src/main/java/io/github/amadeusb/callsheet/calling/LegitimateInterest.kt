package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.MANUAL_PREFIX

/**
 * What to say when someone on the phone asks about the legitimate interest.
 *
 * The number's source is only spelled out for imported businesses: a
 * hand-entered one came from a referral or a business card, and the origin row
 * above the answer says which.
 */
object LegitimateInterest {

    fun answer(placeId: String, industry: String?): String {
        val who = industry?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "als Betrieb im Bereich $it" }
            ?: "als Betrieb"
        val source = if (placeId.startsWith(MANUAL_PREFIX)) ""
        else "Ihre Nummer stammt aus Ihrem öffentlichen Eintrag im Netz. "

        return "„Gute Frage, die stell ich mir als Datenschutzbeauftragter auch immer. " +
            "Ich rufe Sie an, weil Sie $who hier in der Region jeden Tag Termine, " +
            "Anrufe und Belege abwickeln. Genau diese Abläufe automatisiere ich für " +
            "inhabergeführte Betriebe rund um Ingolstadt. $source" +
            "Wenn das für Sie kein Thema ist, sagen Sie's einfach. Dann trage ich Sie " +
            "sofort aus und Sie hören nichts mehr von mir.“"
    }
}
