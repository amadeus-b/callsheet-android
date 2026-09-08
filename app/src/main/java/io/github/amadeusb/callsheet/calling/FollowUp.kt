package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out follow-up dates. The result is always an ISO-8601 timestamp with a
 * zone and a time of day — a bare date will not do (see docs/development.md).
 *
 * Every computed date falls on a workday between 8 and 18. Only
 * [fromDateAndTime] takes the user's choice as given, without correcting it.
 */
object FollowUp {

    /** Start of the working day. */
    private val DAY_START: LocalTime = LocalTime.of(8, 0)

    /** End of the working day, exclusive. */
    private val DAY_END: LocalTime = LocalTime.of(18, 0)

    /** Fallback time when a date falls outside the working day. */
    private val FALLBACK_TIME: LocalTime = LocalTime.of(9, 0)

    /**
     * The four sections of the working day. The order is fixed on purpose: a
     * switch is always "two along", which reliably jumps between morning and
     * afternoon.
     */
    enum class DaySection(val representative: LocalTime) {
        EARLY_MORNING(LocalTime.of(9, 0)),
        LATE_MORNING(LocalTime.of(11, 0)),
        EARLY_AFTERNOON(LocalTime.of(14, 0)),
        LATE_AFTERNOON(LocalTime.of(16, 30));

        /** A deterministic switch to a different section. */
        fun other(): DaySection = entries[(ordinal + 2) % entries.size]
    }

    /** Places a time of day into one of the working day's sections. */
    fun section(time: LocalTime): DaySection = when {
        time.isBefore(LocalTime.of(10, 0)) -> DaySection.EARLY_MORNING
        time.isBefore(LocalTime.of(12, 0)) -> DaySection.LATE_MORNING
        time.isBefore(LocalTime.of(15, 0)) -> DaySection.EARLY_AFTERNOON
        else -> DaySection.LATE_AFTERNOON
    }

    /**
     * Two days out. With [differentTimeOfDay] the date deliberately lands in a
     * different section of the working day than the starting point — calling
     * three times at ten o'clock gets the same result three times.
     */
    fun inTwoDays(fromMillis: Long = System.currentTimeMillis(), differentTimeOfDay: Boolean = false): String {
        val from = Clock.zdt(fromMillis)
        var target = from.plusDays(2)
        if (differentTimeOfDay) {
            val new = section(from.toLocalTime()).other().representative
            target = target.withHour(new.hour).withMinute(new.minute).withSecond(0).withNano(0)
        }
        return Clock.format(fix(target))
    }

    /** Same weekday seven days on, same time. */
    fun nextWeek(fromMillis: Long = System.currentTimeMillis()): String =
        Clock.format(fix(Clock.zdt(fromMillis).plusDays(7)))

    /**
     * One month on. java.time handles month ends correctly: 31 January becomes
     * 28 or 29 February.
     */
    fun nextMonth(fromMillis: Long = System.currentTimeMillis()): String =
        Clock.format(fix(Clock.zdt(fromMillis).plusMonths(1)))

    /** The user's own choice — taken as given, without correction. */
    fun fromDateAndTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
        val zdt = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Clock.zone)
        return Clock.format(zdt)
    }

    /** true when the moment lies in the past. Unreadable or empty → false. */
    fun isOverdue(iso: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val m = Clock.millis(iso) ?: return false
        return m < nowMillis
    }

    /**
     * Moves a date into the next workday between 8 and 18.
     * Before 8 → same day, 9 o'clock. From 18 → next day, 9 o'clock.
     * Saturday and Sunday → Monday, keeping the time.
     */
    private fun fix(input: ZonedDateTime): ZonedDateTime {
        var t = input.withSecond(0).withNano(0)
        val time = t.toLocalTime()
        if (time.isBefore(DAY_START)) {
            t = t.withHour(FALLBACK_TIME.hour).withMinute(FALLBACK_TIME.minute)
        } else if (!time.isBefore(DAY_END)) {
            t = t.plusDays(1).withHour(FALLBACK_TIME.hour).withMinute(FALLBACK_TIME.minute)
        }
        while (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) {
            t = t.plusDays(1)
        }
        return t
    }
}
