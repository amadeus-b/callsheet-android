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
import io.github.amadeusb.callsheet.data.Business

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    overdue: List<Business>,
    dueToday: List<Business>,
    onBack: () -> Unit,
    onDial: (Business) -> Unit,
    onOpen: (Business) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heute") },
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
        if (overdue.isEmpty() && dueToday.isEmpty()) {
            Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                EmptyState("Keine Wiedervorlage offen. Nichts, was heute noch drängt.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            if (overdue.isNotEmpty()) {
                item(key = "header-overdue") {
                    GroupHeader(
                        title = "Überfällig (${overdue.size})",
                        subtitle = "Aus den Vortagen liegengeblieben. Diese zuerst.",
                        background = MaterialTheme.colorScheme.errorContainer,
                        foreground = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                items(overdue, key = { "u-" + it.placeId }) { business ->
                    Column(
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                        )
                    ) {
                        BusinessRow(
                            business = business,
                            onDial = { onDial(business) },
                            onOpen = { onOpen(business) },
                            showFollowUp = true,
                            overdue = true,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item(key = "header-today") {
                GroupHeader(
                    title = "Heute fällig (${dueToday.size})",
                    subtitle = null,
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (dueToday.isEmpty()) {
                item(key = "today-empty") {
                    EmptyState("Für heute steht nichts mehr an.")
                }
            }
            items(dueToday, key = { "h-" + it.placeId }) { business ->
                BusinessRow(
                    business = business,
                    onDial = { onDial(business) },
                    onOpen = { onOpen(business) },
                    showFollowUp = true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
