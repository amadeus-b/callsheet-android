package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.calling.Agenda
import io.github.amadeusb.callsheet.calling.AgendaGroup
import io.github.amadeusb.callsheet.calling.AgendaSection
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.Business

/**
 * Everything coming up: overdue callbacks at the top and set apart — a missed
 * callback must not disappear silently — then today, then every later day that
 * has something on it. Callbacks and visits mixed by time, each row saying
 * which it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    sections: List<AgendaSection<Pair<AppointmentEntry, Business>>>,
    onBack: () -> Unit,
    onDial: (Business) -> Unit,
    onOpen: (Business) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Termine") },
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
        if (sections.isEmpty()) {
            Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                EmptyState("Keine Termine und keine offenen Rückrufe.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            sections.forEach { section ->
                val overdue = section.group == AgendaGroup.OVERDUE
                item(key = "header-${section.group}-${section.dayStartMillis}") {
                    GroupHeader(
                        title = Agenda.title(section),
                        subtitle = if (overdue) "Liegengeblieben. Diese zuerst." else null,
                        background = when (section.group) {
                            AgendaGroup.OVERDUE -> MaterialTheme.colorScheme.errorContainer
                            AgendaGroup.TODAY -> MaterialTheme.colorScheme.tertiaryContainer
                            AgendaGroup.DAY -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        foreground = when (section.group) {
                            AgendaGroup.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
                            AgendaGroup.TODAY -> MaterialTheme.colorScheme.onTertiaryContainer
                            AgendaGroup.DAY -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
                // Each entry sits in exactly one section, so its id is a unique key.
                items(section.items, key = { it.first.id }) { (entry, business) ->
                    Column(
                        modifier = if (overdue) {
                            Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                        } else {
                            Modifier
                        }
                    ) {
                        BusinessRow(
                            business = business,
                            onDial = { onDial(business) },
                            onOpen = { onOpen(business) },
                            // The overdue ones come from any day: they need their date.
                            label = Agenda.rowLabel(entry, withDate = overdue),
                            overdue = overdue,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item(key = "footer") { Column(Modifier.height(24.dp)) {} }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    subtitle: String?,
    background: Color,
    foreground: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
            )
        }
    }
}
