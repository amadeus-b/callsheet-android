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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.BusinessDraft

/**
 * Entering a single business by hand — the referral over the phone, the business
 * card from a trade fair. Importing stays the usual route; this is the one-off.
 *
 * The screen holds no state: every keystroke reports the complete new draft
 * upwards. Only the presentation itself (scroll position) stays here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessFormScreen(
    draft: BusinessDraft,
    knownIndustries: List<String>,
    knownCities: List<String>,
    error: String?,
    saving: Boolean,
    onChange: (BusinessDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val nameMissing = draft.name.isBlank()
    val scroll = rememberScrollState()

    // The save button sits at the bottom, the error message at the top. Without
    // this jump, pressing save appears to do nothing at all.
    LaunchedEffect(error) {
        if (error != null) scroll.animateScrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Neuer Betrieb") },
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
            // The save button is pinned to the bottom: one place, always the
            // same, big enough for a thumb while standing.
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
                            Text("Betrieb speichern", style = MaterialTheme.typography.titleMedium)
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
            if (error != null) {
                // The text arrives ready-made from the data layer.
                ErrorCard(error)
            }

            // ---- Business ---------------------------------------------------
            Section("Betrieb")

            Field(
                value = draft.name,
                onValue = { onChange(draft.copy(name = it)) },
                label = "Name des Betriebs",
                isError = nameMissing,
                hint = if (nameMissing) "Ohne Namen lässt sich der Betrieb nicht speichern."
                else null,
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.phone,
                onValue = { onChange(draft.copy(phone = it)) },
                label = "Telefon",
                hint = "Ohne Nummer wird der Betrieb zwar gespeichert, erscheint aber " +
                    "nicht in der Standard-Arbeitsliste — die zeigt nur Betriebe mit Nummer.",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.industry,
                onValue = { onChange(draft.copy(industry = it)) },
                label = "Gewerk",
                hint = "Begründet, warum dieser Betrieb angerufen wird. " +
                    "Möglichst dieselbe Schreibweise wie bei den importierten Betrieben.",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = knownIndustries,
                onPick = { onChange(draft.copy(industry = it)) },
            )

            Field(
                value = draft.city,
                onValue = { onChange(draft.copy(city = it)) },
                label = "Ort",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = knownCities,
                onPick = { onChange(draft.copy(city = it)) },
            )

            Field(
                value = draft.street,
                onValue = { onChange(draft.copy(street = it)) },
                label = "Straße und Hausnummer",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.postalCode,
                onValue = { onChange(draft.copy(postalCode = it)) },
                label = "PLZ",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )

            // ---- Contact ----------------------------------------------------
            Section("Kontakt")

            Field(
                value = draft.contactName,
                onValue = { onChange(draft.copy(contactName = it)) },
                label = "Ansprechpartner",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.website,
                onValue = { onChange(draft.copy(website = it)) },
                label = "Webseite",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )

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

            // ---- Origin and note --------------------------------------------
            Section("Herkunft und Notiz")

            Field(
                value = draft.origin,
                onValue = { onChange(draft.copy(origin = it)) },
                label = "Herkunft",
                placeholder = "z. B. Empfehlung von Firma Weber, Visitenkarte Messe Bau 2026",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )

            Field(
                value = draft.note,
                onValue = { onChange(draft.copy(note = it)) },
                label = "Notiz",
                placeholder = "Was sonst noch wichtig ist",
                singleLine = false,
                minHeight = 120,
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Known values as tappable chips. They only fill the field in; free text stays
 * possible throughout. An empty list shows nothing.
 */
@Composable
private fun Suggestions(
    values: List<String>,
    onPick: (String) -> Unit,
) {
    if (values.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.distinct().forEach { value ->
            AssistChip(
                onClick = { onPick(value) },
                label = { Text(value) },
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
    }
}
