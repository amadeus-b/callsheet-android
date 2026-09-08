package io.github.amadeusb.callsheet.data

/**
 * Whether a business belongs to the callable target set.
 *
 * Deliberately in one place: import and manual entry have to apply the same
 * rule, otherwise the work list treats the two origins differently.
 *
 * Industries carrying the `(kein Ziel)` suffix are excluded by the data itself;
 * the rule keys on the suffix rather than a fixed list.
 */
object TargetRule {

    /** Industries carrying this suffix are explicitly out of scope. */
    const val NO_TARGET_SUFFIX = "(kein Ziel)"

    fun isExcludedIndustry(industry: String?): Boolean =
        industry != null && industry.trimEnd().endsWith(NO_TARGET_SUFFIX)

    /**
     * A null industry does not exclude anyone — those businesses should stay
     * available for review.
     */
    fun isTarget(industry: String?, phone: String?, closed: Boolean): Boolean =
        !isExcludedIndustry(industry) && phone != null && !closed
}
