package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.PhoneDraft
import io.github.amadeusb.callsheet.data.PhoneType

/**
 * The form for a contact: name, role, email and any number of phone numbers —
 * each with its kind, the way the Contacts app does it.
 *
 * The screen holds no state beyond the delete confirmation: every keystroke
 * reports the complete new draft upwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(
    draft: ContactDraft,
    businessName: String,
    error: String?,
    saving: Boolean,
    onChange: (ContactDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val nameMissing = draft.name.isBlank()
    val scroll = rememberScrollState()
    var deleteConfirm by remember { mutableStateOf(false) }

    // The save button sits at the bottom, the error message at the top.
    LaunchedEffect(error) {
        if (error != null) scroll.animateScrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (draft.id == null) "Neuer Ansprechpartner" else "Ansprechpartner")
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                ) {
                    Button(
                        onClick = onSave,
                        enabled = !nameMissing && !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Speichern …", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Ansprechpartner speichern", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            if (error != null) ErrorCard(error)

            Section("Person")
            Text(
                text = "Gehört zu $businessName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Field(
                value = draft.name,
                onValue = { onChange(draft.copy(name = it)) },
                label = "Name",
                isError = nameMissing,
                hint = if (nameMissing) "Ohne Namen lässt sich der Eintrag nicht speichern."
                else null,
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.role,
                onValue = { onChange(draft.copy(role = it)) },
                label = "Rolle",
                placeholder = "z. B. Inhaber, Bauleitung, Büro",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            roleSuggestions { onChange(draft.copy(role = it)) }

            Field(
                value = draft.email,
                onValue = { onChange(draft.copy(email = it)) },
                label = "E-Mail",
                hint = "Folgekanal nach einem Telefonat, kein Erstkontakt.",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            Section("Telefonnummern")
            draft.numbers.forEachIndexed { index, row ->
                PhoneRow(
                    row = row,
                    removable = draft.numbers.size > 1,
                    onChange = { new ->
                        onChange(
                            draft.copy(
                                numbers = draft.numbers.toMutableList().also { it[index] = new }
                            )
                        )
                    },
                    onRemove = {
                        onChange(
                            draft.copy(
                                numbers = draft.numbers.filterIndexed { i, _ -> i != index }
                            )
                        )
                    },
                )
            }

            OutlinedButton(
                onClick = { onChange(draft.copy(numbers = draft.numbers + PhoneDraft())) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Weitere Nummer")
            }

            Section("Notiz")
            Field(
                value = draft.note,
                onValue = { onChange(draft.copy(note = it)) },
                label = "Notiz zur Person",
                placeholder = "z. B. am besten vormittags erreichbar",
                singleLine = false,
                minHeight = 100,
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )

            if (draft.id != null) {
                Section("Eintrag entfernen")
                OutlinedButton(
                    onClick = { deleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ansprechpartner löschen")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Ansprechpartner löschen?") },
            text = {
                Text(
                    "${draft.name.ifBlank { "Der Eintrag" }} wird mit allen Nummern " +
                        "entfernt. Die dokumentierten Anrufe bleiben im Protokoll stehen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    onDelete()
                }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("Abbrechen") }
            },
        )
    }
}

/** One number row: pick the kind, type the number, remove the row again. */
@Composable
private fun PhoneRow(
    row: PhoneDraft,
    removable: Boolean,
    onChange: (PhoneDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneType.entries.forEach { kind ->
                FilterChip(
                    selected = kind == row.kind,
                    onClick = { onChange(row.copy(kind = kind)) },
                    label = { Text(kind.label) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = row.number,
                onValue = { onChange(row.copy(number = it)) },
                label = numberLabel(row.kind),
                placeholder = "030 1234567",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.weight(1f),
            )
            if (removable) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(48.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Nummer entfernen")
                }
            }
        }
    }
}

private fun numberLabel(kind: PhoneType): String = "Nummer (${kind.label})"

/** Common roles as tappable chips — free text stays possible. */
@Composable
private fun roleSuggestions(onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Inhaber", "Geschäftsführung", "Bauleitung", "Büro", "Einkauf").forEach { role ->
            AssistChip(
                onClick = { onPick(role) },
                label = { Text(role) },
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
    }
}
