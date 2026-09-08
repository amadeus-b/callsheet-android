package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Filter
import io.github.amadeusb.callsheet.data.Status

private enum class OpenDialog { NONE, INDUSTRY, CITY, STATUS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkListScreen(
    businesses: List<Business>,
    totalInFilter: Int,
    calledToday: Int,
    filter: Filter,
    allIndustries: List<String>,
    allCities: List<String>,
    outsideBusinessHours: Boolean,
    loading: Boolean,
    onFilterChange: (Filter) -> Unit,
    onDial: (Business) -> Unit,
    onOpen: (Business) -> Unit,
    onToday: () -> Unit,
    onSettings: () -> Unit,
    onNewBusiness: () -> Unit,
) {
    var dialog by remember { mutableStateOf(OpenDialog.NONE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Callsheet") },
                actions = {
                    IconButton(onClick = onToday) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Heute")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        floatingActionButton = {
            // A single lead — a referral, a business card — should be
            // recordable without a detour through the import file.
            ExtendedFloatingActionButton(
                onClick = onNewBusiness,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Betrieb") },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {

            if (outsideBusinessHours) {
                HintBanner(
                    "Außerhalb der üblichen Geschäftszeiten (Mo–Fr, 8 bis 18 Uhr). " +
                        "Anrufen geht weiterhin — nur kurz nachdenken."
                )
            }

            SearchField(
                value = filter.search,
                onValue = { onFilterChange(filter.copy(search = it)) },
            )

            FilterBar(
                filter = filter,
                onFilterChange = onFilterChange,
                onDialog = { dialog = it },
            )

            CounterRow(totalInFilter = totalInFilter, calledToday = calledToday)

            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (businesses.isEmpty() && !loading) {
                EmptyState(
                    text = "Kein Betrieb passt auf diesen Filter. " +
                        "Weniger einschränken oder über das Menü oben neu importieren.",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(businesses, key = { it.placeId }) { business ->
                        BusinessRow(
                            business = business,
                            onDial = { onDial(business) },
                            onOpen = { onOpen(business) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    when (dialog) {
        OpenDialog.NONE -> Unit

        OpenDialog.INDUSTRY -> {
            val entries = listOf(UNASSIGNED) + allIndustries
            val selected = buildSet {
                addAll(filter.industries)
                if (filter.unassigned) add(UNASSIGNED)
            }
            MultiSelectDialog(
                title = "Gewerk",
                entries = entries,
                selected = selected,
                onToggle = { entry ->
                    if (entry == UNASSIGNED) {
                        onFilterChange(filter.copy(unassigned = !filter.unassigned))
                    } else {
                        val new = filter.industries.toMutableSet()
                        if (!new.remove(entry)) new.add(entry)
                        onFilterChange(filter.copy(industries = new))
                    }
                },
                onClear = {
                    onFilterChange(filter.copy(industries = emptySet(), unassigned = false))
                },
                onClose = { dialog = OpenDialog.NONE },
            )
        }

        OpenDialog.CITY -> MultiSelectDialog(
            title = "Ort",
            entries = allCities,
            selected = filter.cities,
            onToggle = { entry ->
                val new = filter.cities.toMutableSet()
                if (!new.remove(entry)) new.add(entry)
                onFilterChange(filter.copy(cities = new))
            },
            onClear = { onFilterChange(filter.copy(cities = emptySet())) },
            onClose = { dialog = OpenDialog.NONE },
        )

        OpenDialog.STATUS -> {
            // `do_not_call` is deliberately not on offer: blocked businesses never show up.
            val selectable = Status.entries.filter { it != Status.DO_NOT_CALL }
            MultiSelectDialog(
                title = "Status",
                entries = selectable.map { it.label },
                selected = filter.status.map { it.label }.toSet(),
                onToggle = { label ->
                    val status = selectable.first { it.label == label }
                    val new = filter.status.toMutableSet()
                    if (!new.remove(status)) new.add(status)
                    onFilterChange(filter.copy(status = new))
                },
                onClear = { onFilterChange(filter.copy(status = emptySet())) },
                onClose = { dialog = OpenDialog.NONE },
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    // The text is kept locally so typing stays fluid even when the query behind
    // it takes a moment.
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    val keyboard = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValue(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text("Suche in Name und Ort") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    onValue("")
                }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Suche leeren")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun FilterBar(
    filter: Filter,
    onFilterChange: (Filter) -> Unit,
    onDialog: (OpenDialog) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChipWithCount(
            label = "Gewerk",
            count = filter.industries.size + if (filter.unassigned) 1 else 0,
            onClick = { onDialog(OpenDialog.INDUSTRY) },
        )
        FilterChipWithCount(
            label = "Ort",
            count = filter.cities.size,
            onClick = { onDialog(OpenDialog.CITY) },
        )
        FilterChipWithCount(
            label = "Status",
            count = filter.status.size,
            onClick = { onDialog(OpenDialog.STATUS) },
        )
        FilterChip(
            selected = filter.onlyTargets,
            onClick = { onFilterChange(filter.copy(onlyTargets = !filter.onlyTargets)) },
            label = { Text("nur Ziele") },
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun CounterRow(totalInFilter: Int, calledToday: Int) {
    val word = if (totalInFilter == 1) "Betrieb" else "Betriebe"
    Text(
        text = "$totalInFilter $word im Filter · $calledToday heute angerufen",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
