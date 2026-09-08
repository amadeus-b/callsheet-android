package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.AppointmentDraft
import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.data.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** An hour's height. A minute is therefore HOUR_HEIGHT / 60, everywhere. */
private val HOUR_HEIGHT = 56.dp

/** How much run-up is shown above the appointment when the strip opens. */
private const val RUN_UP_HOURS = 1

/** Nobody agrees an appointment at 14:07. */
private const val SNAP_MINUTES = 15

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EE", Locale.GERMAN)
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Setting an appointment on site. A sheet rather than a screen, so the business
 * stays visible behind it — the conversation that produced the appointment is
 * usually still going.
 *
 * The layout is header, scrolling timeline, footer, rather than one long scroll:
 * the day has to scroll without taking the save button off the screen with it.
 * That only holds if the timeline is the part that gives way — hence
 * `weight(1f)` on it and a bounded height on the column around it. Stacked at
 * their natural heights the pieces come to roughly 700 dp, and the conflict
 * notice adds another hundred; on an ordinary phone that pushes
 * "Termin speichern" off the bottom exactly when it is needed most.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentSheet(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onSave: () -> Unit,
    onLink: (Long) -> Unit,
    onForce: () -> Unit,
    onPickDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The sheet takes the height it can get. The timeline is the reason
        // this screen exists, and a strip showing two hours is worth less than
        // no strip at all — it looks like the day is empty.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Termin vor Ort",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionLabel("Tag")
            DayRow(draft = draft, onDraft = onDraft, onPickDate = onPickDate)

            // The missing permission rides along in the label rather than in a
            // paragraph of its own: it still has to be said — an empty strip
            // otherwise reads as "the day is free" — but not at the price of
            // three lines taken from the strip itself.
            SectionLabel(
                if (draft.calendarReadable) "Uhrzeit"
                else "Uhrzeit · Kalender nicht freigegeben"
            )
            // weight, not a fixed height: the timeline is what gives way when
            // the sheet runs out of room, so the save button never does.
            Timeline(
                draft = draft,
                onDraft = onDraft,
                modifier = Modifier.weight(1f).heightIn(min = 320.dp),
            )

            SectionLabel("Dauer")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Appointment.DURATIONS.forEach { minutes ->
                    FilterChip(
                        selected = draft.minutes == minutes,
                        onClick = { onDraft(draft.copy(minutes = minutes)) },
                        label = { Text("$minutes") },
                    )
                }
            }

            SectionLabel("Ort")
            OutlinedTextField(
                value = draft.location,
                onValueChange = { onDraft(draft.copy(location = it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = false,
            )

            if (draft.conflict.isNotEmpty()) {
                ConflictNotice(draft = draft, onDraft = onDraft, onLink = onLink, onForce = onForce)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Termin speichern")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * The next five days, starting with the one the draft is on. Picking a day keeps
 * the time of day — the hour is usually agreed before the date.
 */
@Composable
private fun DayRow(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onPickDate: () -> Unit,
) {
    val start = Clock.millis(draft.startIso) ?: return
    val from = Clock.zdt(start)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..4).forEach { offset ->
            val day = from.plusDays(offset.toLong())
            FilterChip(
                selected = offset == 0,
                onClick = { onDraft(draft.copy(startIso = Clock.format(day))) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.format(dayFormat), style = MaterialTheme.typography.labelSmall)
                        Text(day.format(dateFormat), style = MaterialTheme.typography.labelLarge)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        FilterChip(
            selected = false,
            onClick = onPickDate,
            label = { Text("anderer", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The whole day, an hour to a fixed height, in its own scroll area.
 *
 * It runs 0 to 24 on purpose. An appointment is what the customer agreed to, so
 * a Saturday at 18:30 has to be reachable — a strip that stopped at 18 would
 * quietly forbid what [Appointment] explicitly allows.
 *
 * It opens on the appointment, not on a fixed hour: an evening appointment being
 * changed must not start with a hunt for its own block.
 */
@Composable
private fun Timeline(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = Clock.millis(draft.startIso) ?: return
    val dayStart = Clock.todayStart(start)
    val scroll = rememberScrollState()
    val openHour = (Clock.zdt(start).hour - RUN_UP_HOURS).coerceIn(0, 23)
    val openAt = with(LocalDensity.current) { (HOUR_HEIGHT * openHour).roundToPx() }

    // Unit, not openAt: the strip is positioned once when the sheet opens.
    // Re-running it on every drag would fight the user for the scroll position.
    LaunchedEffect(Unit) { scroll.scrollTo(openAt) }

    Box(modifier = modifier.fillMaxWidth().verticalScroll(scroll)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp, end = 16.dp)
                .height(HOUR_HEIGHT * 24),
        ) {
            (0 until 24).forEach { hour ->
                Box(modifier = Modifier.offset(y = HOUR_HEIGHT * hour)) {
                    Text(
                        text = "%02d".format(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = (-32).dp, y = (-6).dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }

            // Overlapping entries are inset, so a second one behind the first is
            // visible rather than hidden underneath it. The inset is taken off
            // the width as well, or the block would hang over the right edge.
            draft.busy.forEachIndexed { index, interval ->
                val overlapsEarlier = draft.busy.take(index).count {
                    interval.startMillis < it.endMillis && it.startMillis < interval.endMillis
                }.coerceAtMost(3)
                val top = ((interval.startMillis - dayStart) / 60_000L).toInt()
                val length = ((interval.endMillis - interval.startMillis) / 60_000L).toInt()
                BusyBlock(
                    interval = interval,
                    modifier = Modifier
                        .offset(
                            x = 12.dp * overlapsEarlier,
                            y = HOUR_HEIGHT / 60 * top.coerceIn(0, 24 * 60),
                        )
                        .height(HOUR_HEIGHT / 60 * length.coerceIn(15, 24 * 60))
                        .fillMaxWidth()
                        .padding(end = 12.dp * overlapsEarlier),
                )
            }

            DraftBlock(draft = draft, dayStart = dayStart, onDraft = onDraft)
        }
    }
}

/**
 * The appointment being set: drag the block to move it, drag the handle to
 * change its length.
 *
 * Both gestures accumulate. `detectDragGestures` reports a few pixels per frame,
 * so rounding each frame on its own would round to zero and nothing would ever
 * move. The accumulator adds the frames up and gives back only what it has
 * already turned into a step.
 */
@Composable
private fun DraftBlock(
    draft: AppointmentDraft,
    dayStart: Long,
    onDraft: (AppointmentDraft) -> Unit,
) {
    // The gesture outlives any single recomposition, so it must not close over
    // the draft it started with.
    val current by rememberUpdatedState(draft)
    val emit by rememberUpdatedState(onDraft)
    var carriedMinutes by remember { mutableFloatStateOf(0f) }
    var carriedLength by remember { mutableFloatStateOf(0f) }

    val start = Clock.millis(draft.startIso) ?: return
    val top = ((start - dayStart) / 60_000L).toInt()

    Box(
        modifier = Modifier
            .offset(y = HOUR_HEIGHT / 60 * top)
            .height(HOUR_HEIGHT / 60 * draft.minutes)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(Unit) {
                val perMinute = HOUR_HEIGHT.toPx() / 60f
                detectDragGestures(
                    onDragEnd = { carriedMinutes = 0f },
                    onDragCancel = { carriedMinutes = 0f },
                ) { change, amount ->
                    change.consume()
                    carriedMinutes += amount.y / perMinute
                    val steps = (carriedMinutes / SNAP_MINUTES).roundToInt()
                    if (steps != 0) {
                        carriedMinutes -= steps * SNAP_MINUTES
                        val moved = (Clock.millis(current.startIso) ?: return@detectDragGestures) +
                            steps * SNAP_MINUTES * 60_000L
                        emit(current.copy(startIso = Clock.format(moved)))
                    }
                }
            },
    ) {
        Text(
            text = "${Clock.zdt(start).format(timeFormat)} – " +
                Clock.zdt(start + draft.minutes * 60_000L).format(timeFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(48.dp)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    val perMinute = HOUR_HEIGHT.toPx() / 60f
                    detectDragGestures(
                        onDragEnd = { carriedLength = 0f },
                        onDragCancel = { carriedLength = 0f },
                    ) { change, amount ->
                        change.consume()
                        carriedLength += amount.y / perMinute
                        val steps = (carriedLength / SNAP_MINUTES).roundToInt()
                        if (steps != 0) {
                            carriedLength -= steps * SNAP_MINUTES
                            val length = current.minutes + steps * SNAP_MINUTES
                            emit(current.copy(minutes = length.coerceAtLeast(SNAP_MINUTES)))
                        }
                    }
                },
        )
    }
}

@Composable
private fun BusyBlock(interval: BusyInterval, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // fillMaxHeight, not a fixed one: a half-hour block would otherwise grow
        // a bar an hour tall sticking out of its own card.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Text(
            text = interval.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * What to do when the slot is already taken. Asking beats writing a second copy
 * of an appointment that already exists.
 */
@Composable
private fun ConflictNotice(
    draft: AppointmentDraft,
    onDraft: (AppointmentDraft) -> Unit,
    onLink: (Long) -> Unit,
    onForce: () -> Unit,
) {
    val clash = draft.conflict.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(
            text = "Um ${Clock.zdt(clash.startMillis).format(timeFormat)} steht " +
                "bereits „${clash.title}“.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            clash.eventId?.let { id ->
                Button(onClick = { onLink(id) }) { Text("Verknüpfen") }
            }
            Button(onClick = onForce) { Text("Trotzdem anlegen") }
            Button(onClick = { onDraft(draft.copy(conflict = emptyList())) }) { Text("Andere Zeit") }
        }
    }
}
