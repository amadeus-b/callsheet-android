package io.github.amadeusb.callsheet.calling

import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where an entry sits in the agenda. */
enum class AgendaGroup {
    /** Open callbacks whose start has passed — from earlier days or from today. */
    OVERDUE,

    /** Today's visits, past ones included, and today's callbacks still ahead. */
    TODAY,

    /** One later day. */
    DAY,
}

/** One heading in the agenda and what stands under it. [dayStartMillis] is null for [AgendaGroup.OVERDUE]. */
data class AgendaSection<T>(val group: AgendaGroup, val dayStartMillis: Long?, val items: List<T>)

/**
 * The agenda behind the calendar button: what is overdue, what is on today,
 * and every later day that has something on it.
 *
 * Generic over what the screen lists — an entry with its business — so the
 * grouping is tested on bare entries, without a business in sight.
 */
object Agenda {

    private val dayTitle: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Groups [items] for the agenda, by start, kinds mixed.
     *
     * Left out: completed callbacks, and visits from before today — a visit a
     * fortnight ago happened or it did not; either way it is not on the agenda.
     * An open callback is never left out, however old: it is work not done.
     * Items whose start cannot be read are left out as well.
     */
    fun <T> sections(items: List<T>, entryOf: (T) -> AppointmentEntry, nowMillis: Long): List<AgendaSection<T>> {
        val todayStart = Clock.todayStart(nowMillis)
        val tomorrowStart = Clock.nextDayStart(nowMillis)
        val overdue = ArrayList<T>()
        val today = ArrayList<T>()
        val later = LinkedHashMap<Long, MutableList<T>>()

        items
            .mapNotNull { item -> Clock.millis(entryOf(item).startsAt)?.let { it to item } }
            .sortedBy { it.first }
            .forEach { (start, item) ->
                val entry = entryOf(item)
                val callback = entry.kind == AppointmentKind.CALLBACK
                when {
                    callback && entry.doneAt != null -> Unit
                    callback && start < nowMillis -> overdue.add(item)
                    !callback && start < todayStart -> Unit
                    start < tomorrowStart -> today.add(item)
                    else -> later.getOrPut(Clock.todayStart(start)) { ArrayList() }.add(item)
                }
            }

        return buildList {
            if (overdue.isNotEmpty()) add(AgendaSection(AgendaGroup.OVERDUE, null, overdue))
            if (today.isNotEmpty()) add(AgendaSection(AgendaGroup.TODAY, todayStart, today))
            later.forEach { (day, list) -> add(AgendaSection(AgendaGroup.DAY, day, list)) }
        }
    }

    /** „Überfällig (2)", „Heute (3)", „Dienstag, 15.09.". */
    fun title(section: AgendaSection<*>): String = when (section.group) {
        AgendaGroup.OVERDUE -> "Überfällig (${section.items.size})"
        AgendaGroup.TODAY -> "Heute (${section.items.size})"
        AgendaGroup.DAY -> Clock.zdt(section.dayStartMillis ?: 0L).format(dayTitle)
    }

    /**
     * „Rückruf · 09:00 – 09:15 · wegen Angebot". [withDate] adds the date —
     * for the overdue ones, which come from any day.
     */
    fun rowLabel(entry: AppointmentEntry, withDate: Boolean): String {
        val slot = if (withDate) Appointment.readableRange(entry.startsAt, entry.endsAt) else timeRange(entry.startsAt, entry.endsAt)
        val note = entry.note?.trim()?.ifEmpty { null }
        return listOfNotNull(entry.kind.label, slot, note).joinToString(" · ")
    }

    private fun timeRange(startIso: String?, endIso: String?): String {
        val start = Clock.millis(startIso) ?: return "—"
        val head = Clock.zdt(start).format(time)
        val end = Clock.millis(endIso) ?: return head
        return "$head – ${Clock.zdt(end).format(time)}"
    }
}
