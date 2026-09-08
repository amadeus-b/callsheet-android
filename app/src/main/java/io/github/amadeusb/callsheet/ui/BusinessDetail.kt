package io.github.amadeusb.callsheet.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.CallEntry
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.EntryKind
import io.github.amadeusb.callsheet.data.PhoneType
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.DialTarget
import io.github.amadeusb.callsheet.data.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessDetailScreen(
    business: Business,
    calls: List<CallEntry>,
    contacts: List<Contact>,
    noteFocus: Boolean,
    statusSuggestion: Status?,
    followUpSuggestion: String?,
    hint: String?,
    saving: Boolean,
    onBack: () -> Unit,
    onDial: (DialTarget) -> Unit,
    onOutcome: (Status, String) -> Unit,
    onContact: (String?) -> Unit,
    onFollowUp: (String?) -> Unit,
    onAppointment: () -> Unit,
    onRemoveAppointment: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismissHint: () -> Unit,
) {
    var blockConfirm by remember { mutableStateOf(false) }
    var removeAppointment by remember { mutableStateOf(false) }

    // Status and note are only taken over by the save button. Until then the
    // draft lives here; it resets as soon as the saved state catches up or a
    // different business is opened.
    var statusDraft by remember(business.placeId, business.status) {
        mutableStateOf(business.status)
    }
    var noteDraft by remember(business.placeId, business.note) {
        mutableStateOf(business.note.orEmpty())
    }
    val pendingChange =
        statusDraft != business.status || noteDraft != business.note.orEmpty()

    // After blocking, the draft is done — it must not be written again when
    // leaving the screen and thereby undo the block.
    var blocked by remember(business.placeId) { mutableStateOf(false) }

    // Otherwise: never lose what was typed when leaving the screen.
    val save = rememberUpdatedState(onOutcome)
    val draft = rememberUpdatedState(statusDraft to noteDraft)
    val saved = rememberUpdatedState(business.status to business.note.orEmpty())
    val done = rememberUpdatedState(blocked)
    DisposableEffect(business.placeId) {
        onDispose {
            val (status, note) = draft.value
            if (!done.value && draft.value != saved.value) save.value(status, note)
        }
    }
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        business.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            // The bottom holds only what would otherwise be missed: unsaved
            // changes. Dialling happens above, on the number it refers to.
            if (pendingChange) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp),
                    ) {
                        SaveBar(
                            status = statusDraft,
                            saving = saving,
                            onDiscard = {
                                statusDraft = business.status
                                noteDraft = business.note.orEmpty()
                            },
                            onSave = { onOutcome(statusDraft, noteDraft) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            if (hint != null) {
                item(key = "hint") {
                    HintCard(hint, onDismissHint)
                }
            }

            item(key = "master-data") {
                MasterData(
                    business = business,
                    onOpenUrl = onOpenUrl,
                    onDial = onDial,
                )
            }

            item(key = "note") {
                Section("Notiz")
                NoteField(
                    text = noteDraft,
                    requestFocus = noteFocus,
                    onChange = { noteDraft = it },
                )
            }

            item(key = "status") {
                Section("Status")
                if (statusSuggestion != null && statusSuggestion != business.status) {
                    Text(
                        text = "Vorschlag nach dem letzten Anruf: " +
                            "${statusSuggestion.label}. Der Vorschlag wird nicht " +
                            "automatisch gesetzt — tippe ihn an oder wähle etwas anderes " +
                            "und speichere unten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                StatusButtons(
                    current = statusDraft,
                    suggestion = statusSuggestion,
                    onStatus = { statusDraft = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "contacts") {
                Section("Ansprechpartner (${contacts.size})")
                if (contacts.isEmpty()) {
                    EmptyState("Noch kein Ansprechpartner erfasst.")
                }
            }
            items(contacts, key = { it.id }) { contact ->
                ContactCard(
                    contact = contact,
                    onEdit = { onContact(contact.id) },
                    onDial = onDial,
                    onOpenUrl = onOpenUrl,
                )
            }
            item(key = "contact-new") {
                OutlinedButton(
                    onClick = { onContact(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ansprechpartner hinzufügen")
                }
            }

            item(key = "appointment") {
                Section("Termin vor Ort")
                AppointmentBlock(
                    business = business,
                    onSet = onAppointment,
                    onRemove = { removeAppointment = true },
                    onOpenUrl = onOpenUrl,
                )
            }

            item(key = "follow-up") {
                Section("Wiedervorlage")
                FollowUpBlock(
                    set = business.followUpAt,
                    suggestion = followUpSuggestion,
                    onFollowUp = onFollowUp,
                    onPickDate = { dateOpen = true },
                )
            }

            item(key = "do_not_call") {
                Section("Kein Kontakt mehr")
                DoNotCallButton(
                    alreadyBlocked = business.status == Status.DO_NOT_CALL,
                    onAsk = { blockConfirm = true },
                )
            }

            item(key = "history-header") {
                Section("Protokoll (${calls.size})")
                if (calls.isEmpty()) {
                    EmptyState("Noch nichts dokumentiert.")
                }
            }
            items(calls, key = { it.id }) { call ->
                CallRow(call)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item(key = "origin") {
                Section("Herkunft der Daten")
                Origin(business)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (blockConfirm) {
        AlertDialog(
            onDismissRequest = { blockConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Betrieb sperren?") },
            text = {
                Text(
                    "${business.name} erscheint danach in keiner Liste mehr — nicht in der " +
                        "Arbeitsliste, nicht in der Suche, nicht in „Heute“, auch nicht bei " +
                        "zurückgesetzten Filtern.\n\n" +
                        "Zurücknehmen lässt sich das nur in den Einstellungen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockConfirm = false
                    blocked = true
                    // The block is saved like any other outcome and therefore
                    // lands in the log along with the note.
                    onOutcome(Status.DO_NOT_CALL, noteDraft)
                }) {
                    Text("Sperren", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { blockConfirm = false }) { Text("Abbrechen") }
            },
        )
    }

    if (removeAppointment) {
        AlertDialog(
            onDismissRequest = { removeAppointment = false },
            title = { Text("Termin entfernen?") },
            text = {
                Text(
                    "Der Termin wird auch aus dem Kalender gelöscht. " +
                        "Der Status fällt zurück auf „Angerufen“."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveAppointment()
                    removeAppointment = false
                }) { Text("Entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { removeAppointment = false }) { Text("Abbrechen") }
            },
        )
    }

    if (dateOpen) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Clock.millis(business.followUpAt)
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        val millis = state.selectedDateMillis
                        if (millis != null) {
                            // The date picker hands back midnight UTC — read it
                            // in UTC, otherwise the date slips by a day.
                            val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            selectedDate = Triple(d.year, d.monthValue, d.dayOfMonth)
                            dateOpen = false
                            timeOpen = true
                        }
                    },
                ) { Text("Weiter zur Uhrzeit") }
            },
            dismissButton = {
                TextButton(onClick = { dateOpen = false }) { Text("Abbrechen") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (timeOpen) {
        val prefill = Clock.millis(business.followUpAt)?.let { Clock.zdt(it) }
        val state = rememberTimePickerState(
            initialHour = prefill?.hour ?: 10,
            initialMinute = prefill?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            title = { Text("Uhrzeit") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { TimePicker(state = state) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = selectedDate
                    timeOpen = false
                    if (d != null) {
                        onFollowUp(
                            FollowUp.fromDateAndTime(
                                year = d.first,
                                month = d.second,
                                day = d.third,
                                hour = state.hour,
                                minute = state.minute,
                            )
                        )
                    }
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { timeOpen = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun HintCard(hint: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Hinweis schließen")
            }
        }
    }
}

@Composable
private fun MasterData(
    business: Business,
    onOpenUrl: (String) -> Unit,
    onDial: (DialTarget) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = business.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        val subtitle = listOfNotNull(
            business.industry?.takeIf { it.isNotBlank() },
            if (business.isTarget) "Ziel" else "kein Ziel",
            if (business.closed) "dauerhaft geschlossen" else null,
        ).joinToString(" · ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        DataRow("Ansprechpartner (importiert)", business.contactName)

        val mainNumber = business.phone?.takeIf { it.isNotBlank() }
        if (mainNumber == null) {
            DataRow("Telefon", null)
        } else {
            PhoneRow(
                label = "Telefon (Betrieb)",
                number = mainNumber,
                onDial = { onDial(DialTarget(mainNumber, "Betriebsnummer")) },
            )
        }

        val address = listOfNotNull(
            business.street?.takeIf { it.isNotBlank() },
            listOfNotNull(business.postalCode, business.city).joinToString(" ").takeIf { it.isNotBlank() },
        ).joinToString("\n")
        DataRow("Anschrift", address.takeIf { it.isNotBlank() })

        business.website?.takeIf { it.isNotBlank() }?.let { site ->
            DataRow("Webseite", site) {
                onOpenUrl(if (site.startsWith("http")) site else "https://$site")
            }
        }
        business.email?.takeIf { it.isNotBlank() }?.let { mail ->
            // Only shown and made tappable. Email is a follow-up channel after
             // a call, never a first contact — hence no bulk mail feature.
            DataRow("E-Mail", mail) { onOpenUrl("mailto:$mail") }
        }

        val rating = business.rating?.let { value ->
            val count = business.ratingCount
            if (count != null) "%.1f aus %d Bewertungen".format(value, count)
            else "%.1f".format(value)
        }
        DataRow("Bewertung", rating)
        DataRow("Zuletzt geändert", Clock.readable(business.updatedAt))
    }
}

@Composable
private fun NoteField(
    text: String,
    requestFocus: Boolean,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
                // Field not attached yet — then without focus, but no crash.
            }
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 120.dp)
            .focusRequester(focus),
        label = { Text("Was wurde besprochen?") },
        minLines = 3,
    )
}

/**
 * Appears as soon as status or note differ from what is saved. Both are saved
 * together — and land together in the log.
 */
@Composable
private fun SaveBar(
    status: Status,
    saving: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Text(
            text = "Nicht gespeichert: ${status.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDiscard,
                enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Verwerfen") }
            Button(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.weight(2f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (saving) "Speichert …" else "Ergebnis speichern") }
        }
    }
}

/**
 * An address as a map application takes it. `geo:0,0?q=` rather than
 * coordinates: the query lets the map do the geocoding and land on the door
 * number, which the business's own coordinates do not always do.
 */
internal fun geoUri(address: String): String = "geo:0,0?q=" + Uri.encode(address)

/**
 * The appointment on site. Sits above the follow-up because the two are the
 * answers to one question — when does this go on? — and takes effect straight
 * away, the way the follow-up does.
 */
@Composable
private fun AppointmentBlock(
    business: Business,
    onSet: () -> Unit,
    onRemove: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val set = business.appointmentAt
        if (set == null) {
            // A status of "Termin" without a time is the hole this section
            // exists to close. Say so, and offer the way out — never force it.
            val statusOnly = business.status == Status.APPOINTMENT
            Text(
                text = if (statusOnly) {
                    "Status „Termin“, aber kein Zeitpunkt gesetzt."
                } else {
                    "Kein Termin vereinbart."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusOnly) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSet,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Termin anlegen") }
        } else {
            Text(
                text = Appointment.readableRange(set, business.appointmentEndAt),
                style = MaterialTheme.typography.titleMedium,
            )
            business.appointmentLocation?.takeIf { it.isNotBlank() }?.let { where ->
                Text(
                    text = where,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onOpenUrl(geoUri(where)) }
                        .padding(vertical = 4.dp),
                )
            }
            if (business.calendarEventId != null) {
                Text(
                    text = "Im Kalender abgelegt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSet, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Ändern")
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Entfernen")
                }
            }
        }
    }
}

@Composable
private fun FollowUpBlock(
    set: String?,
    suggestion: String?,
    onFollowUp: (String?) -> Unit,
    onPickDate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = if (set != null) "Gesetzt auf ${Clock.readable(set)}"
            else "Keine Wiedervorlage gesetzt.",
            style = MaterialTheme.typography.bodyLarge,
            color = if (set != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (suggestion != null && suggestion != set) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Vorschlag: ${Clock.readable(suggestion)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onFollowUp(suggestion) }) { Text("Übernehmen") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton("in 2 Tagen", Modifier.weight(1f)) {
                onFollowUp(FollowUp.inTwoDays())
            }
            QuickButton("nächste Woche", Modifier.weight(1f)) {
                onFollowUp(FollowUp.nextWeek())
            }
            QuickButton("nächster Monat", Modifier.weight(1f)) {
                onFollowUp(FollowUp.nextMonth())
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPickDate,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text("Datum & Uhrzeit …") }
            if (set != null) {
                OutlinedButton(
                    onClick = { onFollowUp(null) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Entfernen") }
            }
        }
    }
}

@Composable
private fun QuickButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

@Composable
private fun DoNotCallButton(alreadyBlocked: Boolean, onAsk: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (alreadyBlocked) {
            Text(
                text = "Dieser Betrieb ist gesperrt und erscheint in keiner Liste. " +
                    "Zurücknehmen geht in den Einstellungen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "Sagt jemand am Telefon, er wolle nicht mehr angerufen werden, " +
                    "gehört das hierher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAsk,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Nie wieder anrufen (sperren)")
            }
        }
    }
}

/**
 * A dialable number with its small dial button beside it. The button always sits
 * to the right of the number it dials — no detour through a picker.
 */
@Composable
private fun PhoneRow(
    label: String,
    number: String,
    onDial: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = number, style = MaterialTheme.typography.bodyLarge)
        }
        if (onDial != null) {
            Spacer(Modifier.width(12.dp))
            DialButton(
                hasPhone = true,
                onDial = onDial,
                size = 48,
            )
        }
    }
}

/**
 * A contact on the record: numbers are tappable and dial straight away, the
 * email opens the mail app. The pencil leads into the form.
 */
@Composable
private fun ContactCard(
    contact: Contact,
    onEdit: () -> Unit,
    onDial: (DialTarget) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                    contact.role?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Ansprechpartner bearbeiten")
                }
            }

            contact.numbers.forEach { number ->
                PhoneRow(
                    label = number.kind.label,
                    number = number.number,
                    // Fax numbers are there to have them — nobody calls those.
                    onDial = if (number.kind == PhoneType.FAX) null else {
                        {
                            onDial(
                                DialTarget(
                                    number.number,
                                    "${contact.name} · ${number.kind.label}",
                                )
                            )
                        }
                    },
                )
            }

            contact.email?.takeIf { it.isNotBlank() }?.let { mail ->
                Text(
                    text = mail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onOpenUrl("mailto:$mail") }
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                )
            }

            contact.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CallRow(call: CallEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Clock.readable(call.startedAt),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (call.kind == EntryKind.NOTE) "notiert"
                else Clock.duration(call.durationSeconds),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        call.contact?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        call.outcome?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        call.note?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Origin(business: Business) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Wenn am Telefon jemand fragt, woher die Nummer stammt, " +
                "steht die Antwort hier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        DataRow(
            label = "Erhoben aus",
            value = business.origin.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: "nicht dokumentiert",
        )
        DataRow(
            label = "Erhoben am",
            value = business.collectedAt?.let { Clock.readableDate(it) } ?: "nicht dokumentiert",
        )
        DataRow(
            label = "Auswahl begründet über Gewerk",
            value = business.industry ?: "keine Zuordnung",
        )
        if (business.categories.isNotEmpty()) {
            DataRow(
                label = "Alle Kategorien der Quelle",
                value = business.categories.joinToString(", "),
            )
        }
    }
}
