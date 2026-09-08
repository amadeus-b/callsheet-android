package io.github.amadeusb.callsheet.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Every timestamp in the app is ISO-8601 with a zone offset, e.g.
 * 2026-09-07T14:30:00+02:00. A follow-up always carries a time of day, never
 * just a date.
 */
object Clock {

    val zone: ZoneId get() = ZoneId.systemDefault()

    private val output: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun now(): String = format(System.currentTimeMillis())

    fun format(millis: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(output)

    fun format(zdt: ZonedDateTime): String = zdt.format(output)

    /** Parses an ISO-8601 timestamp. Returns null when it cannot be read. */
    fun millis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun zdt(millis: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    /** Human-readable for the UI: "07.09.2026, 14:30". */
    fun readable(iso: String?): String {
        val m = millis(iso) ?: return "—"
        return zdt(m).format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))
    }

    /** The date alone: "07.09.2026". */
    fun readableDate(iso: String?): String {
        val m = millis(iso) ?: return "—"
        return zdt(m).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    /** Call duration as "3:07 min" or "0:42 min". */
    fun duration(seconds: Int): String = "%d:%02d min".format(seconds / 60, seconds % 60)

    /** Start of today, in milliseconds. */
    fun todayStart(nowMillis: Long = System.currentTimeMillis()): Long =
        zdt(nowMillis).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Ordinary business hours: Monday to Friday, 8 to 18. Only a hint in the
     * interface, never a prohibition.
     */
    fun outsideBusinessHours(millis: Long = System.currentTimeMillis()): Boolean {
        val t = zdt(millis)
        val weekend = t.dayOfWeek.value >= 6
        val time = t.toLocalTime()
        return weekend || time.isBefore(LocalTime.of(8, 0)) || !time.isBefore(LocalTime.of(18, 0))
    }
}
