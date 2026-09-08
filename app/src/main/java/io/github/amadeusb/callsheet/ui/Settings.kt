package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.calendar.CalendarAccount
import io.github.amadeusb.callsheet.contacts.AddressBookAccount
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.data.ImportResult
import io.github.amadeusb.callsheet.SyncUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    blockedBusinesses: List<Business>,
    lastImportResult: ImportResult?,
    phoneBookEnabled: Boolean,
    phoneBookAccount: AddressBookAccount?,
    addressBookAccounts: List<AddressBookAccount>,
    phoneBookHint: String?,
    pushing: Boolean,
    syncState: SyncUiState,
    onBack: () -> Unit,
    onUnblock: (Business) -> Unit,
    onImport: () -> Unit,
    onPhoneBook: (Boolean) -> Unit,
    onAccount: (AddressBookAccount) -> Unit,
    onLoadAccounts: () -> Unit,
    calendarEnabled: Boolean,
    calendar: CalendarAccount?,
    calendars: List<CalendarAccount>,
    onCalendarToggle: (Boolean) -> Unit,
    onPickCalendar: (CalendarAccount) -> Unit,
    onLoadCalendars: () -> Unit,
    onPushAll: () -> Unit,
    onOpenServerDialog: () -> Unit,
    onCloseServerDialog: () -> Unit,
    onConnectServer: (String, String) -> Unit,
    onReuploadAll: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Business?>(null) }
    var accountPicker by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var repairOpen by remember { mutableStateOf(false) }
    var calendarPicker by remember { mutableStateOf(false) }
    var confirmReupload by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
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
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            item(key = "phone-book") {
                Section("Telefonbuch")
                PhoneBookBlock(
                    active = phoneBookEnabled,
                    account = phoneBookAccount,
                    hint = phoneBookHint,
                    pushing = pushing,
                    onToggle = onPhoneBook,
                    onPickAccount = {
                        onLoadAccounts()
                        accountPicker = true
                    },
                    onPushAll = onPushAll,
                )
            }

            item(key = "calendar") {
                Section("Kalender")
                CalendarBlock(
                    active = calendarEnabled,
                    calendar = calendar,
                    onToggle = onCalendarToggle,
                    onPick = {
                        onLoadCalendars()
                        calendarPicker = true
                    },
                )
            }

            item(key = "sync") {
                Section("Abgleich")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // A state, not two input fields. Fields that always look
                    // the same, with a Save button that is always enabled,
                    // say nothing about whether anything is connected.
                    if (syncState.url.isBlank()) {
                        Text(
                            text = "Kein Server eingetragen — die App arbeitet nur auf diesem Gerät.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = syncState.url.removePrefix("https://"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Zuletzt abgeglichen: ${Clock.readable(syncState.lastSyncAt)} · " +
                                "${syncState.pending} offen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (syncState.running || syncState.connecting) {
                        Spacer(Modifier.height(10.dp))
                        val progress = syncState.uploadProgress
                        if (progress == null) {
                            // Nothing to upload — only fetching, and how much the
                            // server holds is not known until it stops sending.
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${syncState.uploadTotal - syncState.uploadRemaining} " +
                                    "von ${syncState.uploadTotal} gesendet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    (syncState.connectError ?: syncState.error)?.let { message ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenServerDialog,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (syncState.url.isBlank()) "Server verbinden" else "Verbindung ändern")
                    }

                    // No "sync now": it runs by itself every time the app
                    // comes to the front. A button for it would only ever be
                    // pressed by somebody who does not know that.

                    // Changes go up by themselves; this is a repair tool, not a
                    // step. Out of the way, because the one case still left for
                    // it is a restored server backup — a different server now
                    // resets on its own when the address changes.
                    if (syncState.url.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(
                            onClick = { repairOpen = !repairOpen },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (repairOpen) "Wenn etwas nicht stimmt ▴"
                                else "Wenn etwas nicht stimmt ▾",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (repairOpen) {
                            Text(
                                text = "Änderungen gehen von allein hoch, bei jedem Öffnen der " +
                                    "App. Der Knopf hier ist nur für den Fall, dass auf dem " +
                                    "Server ein älteres Backup eingespielt wurde — dann hält " +
                                    "die App fälschlich alles für gesendet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { confirmReupload = true },
                                enabled = !syncState.running,
                            ) { Text("Alles erneut hochladen") }
                        }
                    }
                }
            }

            item(key = "blocked-header") {
                Section("Gesperrte Betriebe (${blockedBusinesses.size})")
                Text(
                    text = "Diese Betriebe erscheinen in keiner Liste. " +
                        "Zurücknehmen setzt den Status auf „Neu“ — nur für den Fehlgriff.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (blockedBusinesses.isEmpty()) {
                    EmptyState("Kein Betrieb gesperrt.")
                }
            }

            items(blockedBusinesses, key = { it.placeId }) { business ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = business.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val row = listOfNotNull(business.industry, business.city)
                            .joinToString(" · ")
                        if (row.isNotBlank()) {
                            Text(
                                text = row,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { confirm = business },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Zurücknehmen") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item(key = "import") {
                Section("Daten einlesen")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = "Ein erneuter Import aktualisiert nur die Stammdaten. " +
                            "Status, Notizen, Wiedervorlagen, Termine und die " +
                            "Anrufhistorie bleiben unangetastet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { confirmImport = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Datei importieren", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (lastImportResult != null) {
                item(key = "import-result") {
                    Spacer(Modifier.height(12.dp))
                    ImportSummary(lastImportResult)
                }
            }

            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (accountPicker) {
        AlertDialog(
            onDismissRequest = { accountPicker = false },
            title = { Text("In welches Adressbuch?") },
            text = {
                if (addressBookAccounts.isEmpty()) {
                    Text(
                        "Kein Adressbuch-Konto gefunden. Richte in DAVx5 ein " +
                            "Adressbuch ein und synchronisiere einmal."
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(addressBookAccounts, key = { it.type + "|" + it.name }) { account ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAccount(account)
                                        accountPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(account.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = account.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { accountPicker = false }) { Text("Abbrechen") }
            },
        )
    }

    if (calendarPicker) {
        AlertDialog(
            onDismissRequest = { calendarPicker = false },
            title = { Text("In welchen Kalender?") },
            text = {
                if (calendars.isEmpty()) {
                    Text(
                        "Kein beschreibbarer Kalender gefunden. Richte in DAVx5 " +
                            "einen Kalender ein und synchronisiere einmal."
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(calendars, key = { it.id }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPickCalendar(entry)
                                        calendarPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = entry.accountName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { calendarPicker = false }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmReupload) {
        AlertDialog(
            onDismissRequest = { confirmReupload = false },
            title = { Text("Alles erneut hochladen?") },
            text = {
                Text(
                    "Der gesamte Bestand dieses Geräts wird beim nächsten Abgleich noch " +
                        "einmal gesendet, auch was der Server schon kennt. Außerdem wird " +
                        "der gesamte Bestand des Servers noch einmal geholt. Bei vielen " +
                        "Betrieben kann das eine Weile dauern."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onReuploadAll()
                    confirmReupload = false
                }) { Text("Hochladen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReupload = false }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Betriebe importieren?") },
            text = {
                // The section above already says what an import touches. Here
                // it only has to say what the next tap does, in one line.
                Text(
                    "Name, Branche, Anschrift und Nummer bekannter Betriebe werden " +
                        "aus der Datei überschrieben. Neue kommen dazu."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    onImport()
                }) { Text("Datei auswählen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Abbrechen") }
            },
        )
    }

    if (syncState.dialogOpen) {
        var url by remember { mutableStateOf(syncState.url) }
        // Prefilled with the stored key, shown as dots. Untouched, it stays what
        // it was — nobody should retype sixty characters to correct an address.
        var token by remember { mutableStateOf(syncState.token) }
        AlertDialog(
            onDismissRequest = onCloseServerDialog,
            title = { Text("Server verbinden") },
            text = {
                Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Serveradresse") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        enabled = !syncState.connecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Zugangsschlüssel") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !syncState.connecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    syncState.connectError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onConnectServer(url, token) },
                    enabled = !syncState.connecting,
                ) { Text(if (syncState.connecting) "Verbindet …" else "Speichern und verbinden") }
            },
            dismissButton = {
                TextButton(
                    onClick = onCloseServerDialog,
                    enabled = !syncState.connecting,
                ) { Text("Abbrechen") }
            },
        )
    }

    confirm?.let { business ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sperre zurücknehmen?") },
            text = {
                Text(
                    "${business.name} erscheint danach wieder in der Arbeitsliste, " +
                        "Status „Neu“. Nur zurücknehmen, wenn die Sperre ein Versehen war — " +
                        "ein ausgesprochener Widerspruch bleibt bindend."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUnblock(business)
                    confirm = null
                }) { Text("Zurücknehmen") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * Storing things in the phone book. Written into one of the device's address
 * book accounts — usually the one DAVx5 keeps in sync. The app synchronises
 * nothing itself; it only stores what DAVx5 then uploads.
 */
/**
 * Appointments in the device calendar. Written into the calendar the user picks
 * here — usually one DAVx5 keeps in sync. The app synchronises nothing itself.
 */
@Composable
private fun CalendarBlock(
    active: Boolean,
    calendar: CalendarAccount?,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "Termine vor Ort werden zusätzlich im Kalender des Geräts " +
                "abgelegt. Damit erinnert dich das Telefon daran und das Navi " +
                "kennt die Adresse. Was du im Kalender verschiebst, übernimmt " +
                "die App beim nächsten Öffnen der Akte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Termine in den Kalender",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = active, onCheckedChange = onToggle)
        }

        if (active) {
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = calendar?.let { "Kalender: ${it.name}" } ?: "Kalender wählen …",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (calendar == null) {
                Text(
                    text = "Ohne gewählten Kalender wird nichts geschrieben. Der " +
                        "Termin bleibt trotzdem in der App.",
                    style = MaterialTheme.typography.bodySmall,
                    // A note, not a fault: nothing has gone wrong, and nothing is
                    // lost. Red would say the opposite of both.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PhoneBookBlock(
    active: Boolean,
    account: AddressBookAccount?,
    hint: String?,
    pushing: Boolean,
    onToggle: (Boolean) -> Unit,
    onPickAccount: () -> Unit,
    onPushAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "Jeder Betrieb mit Telefonnummer wird im Telefonbuch abgelegt, " +
                "dazu die erfassten Ansprechpartner. Damit zeigt das Telefon " +
                "einen Namen, auch wenn dort zuerst angerufen wird. Gesperrte " +
                "Betriebe bleiben draußen. " +
                "Was du im Telefonbuch änderst, übernimmt die App beim nächsten " +
                "Öffnen der Akte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Im Telefonbuch ablegen",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = active, onCheckedChange = onToggle)
        }

        if (active) {
            OutlinedButton(
                onClick = onPickAccount,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = account?.let { "Adressbuch: ${it.name}" } ?: "Adressbuch wählen …",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (account == null) {
                Text(
                    text = "Ohne gewähltes Adressbuch wird nichts geschrieben.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPushAll,
                    enabled = !pushing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (pushing) "Überträgt …" else "Alle Betriebe ins Telefonbuch übertragen")
                }
            }
        }

        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ImportSummary(outcome: ImportResult) {
    val error = outcome.error
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (error != null) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Letzter Import", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium)
            } else {
                NumberStatRow("Neu", outcome.new)
                NumberStatRow("Aktualisiert", outcome.updated)
                NumberStatRow("Ohne Telefonnummer", outcome.withoutPhone)
                NumberStatRow("Gesamt in der Datei", outcome.total)
            }
        }
    }
}

@Composable
private fun NumberStatRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$value", style = MaterialTheme.typography.titleSmall)
    }
}
