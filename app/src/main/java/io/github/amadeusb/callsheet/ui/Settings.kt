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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.contacts.AddressBookAccount
import io.github.amadeusb.callsheet.data.ImportResult

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
    onBack: () -> Unit,
    onUnblock: (Business) -> Unit,
    onImport: () -> Unit,
    onPhoneBook: (Boolean) -> Unit,
    onAccount: (AddressBookAccount) -> Unit,
    onLoadAccounts: () -> Unit,
    onPushAll: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Business?>(null) }
    var accountPicker by remember { mutableStateOf(false) }

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
            item(key = "import") {
                Section("Daten einlesen")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = "Ein erneuter Import aktualisiert nur die Stammdaten. " +
                            "Status, Notizen, Wiedervorlagen und die Anrufhistorie " +
                            "bleiben unangetastet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onImport,
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
            text = "Ansprechpartner, angerufene und selbst erfasste Betriebe werden " +
                "im Telefonbuch abgelegt. Damit zeigt das Telefon bei einem " +
                "Rückruf den Namen. " +
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
                    color = MaterialTheme.colorScheme.error,
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
                    Text(if (pushing) "Überträgt …" else "Alle bekannten Kontakte übertragen")
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
