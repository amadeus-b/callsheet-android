package io.github.amadeusb.callsheet.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.AddressDraft
import io.github.amadeusb.callsheet.data.Addresses

/**
 * A business's addresses as rows of a form, the first one the main address.
 *
 * Holds no state: every change reports the whole new list upwards. Blank rows
 * are dropped when saving, not here — a row still being filled in must not
 * vanish under the thumb.
 */
@Composable
fun AddressList(
    drafts: List<AddressDraft>,
    knownCities: List<String>,
    onChange: (List<AddressDraft>) -> Unit,
) {
    fun replace(index: Int, row: AddressDraft) = onChange(drafts.toMutableList().also { it[index] = row })

    Column(modifier = Modifier.fillMaxWidth()) {
        drafts.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Text(
                text = if (index == 0) "Hauptadresse" else "Weitere Adresse",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Field(
                value = row.label,
                onValue = { replace(index, row.copy(label = it)) },
                label = "Bezeichnung",
                placeholder = "optional",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = Addresses.LABEL_SUGGESTIONS,
                onPick = { replace(index, row.copy(label = it)) },
            )
            Field(
                value = row.street,
                onValue = { replace(index, row.copy(street = it)) },
                label = "Straße und Hausnummer",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Field(
                value = row.postalCode,
                onValue = { replace(index, row.copy(postalCode = it)) },
                label = "PLZ",
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            Field(
                value = row.city,
                onValue = { replace(index, row.copy(city = it)) },
                label = "Ort",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Suggestions(
                values = knownCities,
                onPick = { replace(index, row.copy(city = it)) },
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                if (index > 0) {
                    TextButton(onClick = { onChange(Addresses.makeMain(drafts, index)) }) {
                        Text("Als Hauptadresse")
                    }
                }
                TextButton(onClick = { onChange(drafts.filterIndexed { i, _ -> i != index }) }) {
                    Text("Entfernen")
                }
            }
        }
        OutlinedButton(
            onClick = { onChange(drafts + AddressDraft()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Adresse hinzufügen")
        }
    }
}

/**
 * The addresses of an existing business — imported or entered by hand. Only
 * the addresses: the master data stays as the import or the entry form left it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressScreen(
    businessName: String,
    drafts: List<AddressDraft>,
    knownCities: List<String>,
    saving: Boolean,
    onChange: (List<AddressDraft>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adressen") },
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
                        enabled = !saving,
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
                            Text("Adressen speichern", style = MaterialTheme.typography.titleMedium)
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
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Adressen")
            Text(
                text = "Gehört zu $businessName. Die erste Adresse ist die Hauptadresse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AddressList(drafts = drafts, knownCities = knownCities, onChange = onChange)
            Spacer(Modifier.height(24.dp))
        }
    }
}
