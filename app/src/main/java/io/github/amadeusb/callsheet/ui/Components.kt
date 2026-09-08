package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.NumberPicker
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.DialTarget
import io.github.amadeusb.callsheet.data.Clock

/** Entry in the industry picker for businesses without an industry. */
const val UNASSIGNED = "ohne Zuordnung"

/**
 * The app's main handle. Deliberately large (64 dp), deliberately always the
 * same colour, deliberately always in the same place. Without a phone number it
 * is visible but dead — that way it is obvious straight away why this is the end
 * of the road.
 */
@Composable
fun DialButton(
    hasPhone: Boolean,
    onDial: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 64,
) {
    val active = hasPhone
    val background = if (active) dialColor else MaterialTheme.colorScheme.surfaceVariant
    val foreground =
        if (active) onDialColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Surface(
        modifier = modifier
            .size(size.dp)
            .semantics {
                contentDescription = if (active) "Anrufen" else "Keine Telefonnummer hinterlegt"
            },
        shape = RoundedCornerShape(percent = 30),
        color = background,
        onClick = onDial,
        enabled = active,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (active) Icons.Filled.Phone else Icons.Filled.Phone,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size((size * 0.45).dp),
            )
        }
    }
}

/**
 * One row of the work list. Tapping the surface opens the business, the button
 * on the right dials. The two targets sit far enough apart not to be confused
 * while walking.
 */
@Composable
fun BusinessRow(
    business: Business,
    onDial: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showFollowUp: Boolean = false,
    overdue: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = business.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val secondLine = listOfNotNull(
                business.industry?.takeIf { it.isNotBlank() },
                business.city?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (secondLine.isNotBlank()) {
                Text(
                    text = secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            business.contactName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showFollowUp && business.followUpAt != null) {
                Text(
                    text = "Wiedervorlage " + Clock.readable(business.followUpAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        DialButton(
            hasPhone = business.hasNumber,
            onDial = onDial,
        )
    }
}

/**
 * The picker for industry, city and status. Multiple selection, the whole row is
 * tappable, no miniature checkboxes to aim at.
 */
@Composable
fun MultiSelectDialog(
    title: String,
    entries: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    // Duplicate entries would break the list's stable key.
    val list = remember(entries) { entries.distinct() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            if (list.isEmpty()) {
                Text("Keine Einträge vorhanden.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(entry) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entry in selected,
                                onCheckedChange = { onToggle(entry) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fertig") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Alle abwählen") } },
    )
}

/** A chip in the filter bar: shows whether and how much is being filtered. */
@Composable
fun FilterChipWithCount(
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = count > 0,
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        label = {
            Text(if (count > 0) "$label ($count)" else label)
        },
        leadingIcon = if (count > 0) {
            { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(),
    )
}

/**
 * The status buttons of the detail view. `do_not_call` is deliberately missing —
 * it gets its own set-apart button with a confirmation there.
 */
@Composable
fun StatusButtons(
    current: Status,
    suggestion: Status?,
    onStatus: (Status) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = Status.entries.filter { it != Status.DO_NOT_CALL }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selectable.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { status ->
                    StatusButton(
                        status = status,
                        set = status == current,
                        suggested = status == suggestion && status != current,
                        onClick = { onStatus(status) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusButton(
    status: Status,
    set: Boolean,
    suggested: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background: Color
    val text: Color
    when {
        set -> {
            background = MaterialTheme.colorScheme.primary
            text = MaterialTheme.colorScheme.onPrimary
        }
        suggested -> {
            background = MaterialTheme.colorScheme.tertiaryContainer
            text = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            text = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(14.dp),
        color = background,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = text,
                    maxLines = 2,
                )
                if (suggested) {
                    Text(
                        text = "Vorschlag",
                        style = MaterialTheme.typography.labelMedium,
                        color = text,
                    )
                }
            }
        }
    }
}

/** A labelled row of master data. Tappable when onClick is set. */
@Composable
fun DataRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (value.isNullOrBlank()) return
    val base = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 8.dp, horizontal = 16.dp)
    Column(modifier = base) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Heading of a section in the detail view. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}

/** A quiet empty state. No illustration, no exclamation mark — just a sentence. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An unobtrusive hint banner, e.g. outside business hours. */
@Composable
fun HintBanner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    textColor: Color? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color ?: MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor ?: MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * Picking which number to dial — the business's main number and its contacts'
 * numbers. Only appears when there is more than one.
 */
@Composable
fun NumberPickerDialog(
    selection: NumberPicker,
    onDial: (DialTarget) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Wen anrufen?") },
        text = {
            Column {
                Text(
                    text = selection.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(selection.targets, key = { it.number }) { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDial(target) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(target.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = target.number,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.Filled.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Abbrechen") }
        },
    )
}

/** An error from the data layer, unmissable at the top. The wording arrives ready-made. */
@Composable
fun ErrorCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** One input field of a form. Same width, spacing and height everywhere. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    keyboard: KeyboardOptions,
    placeholder: String? = null,
    hint: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 60,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        supportingText = hint?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        isError = isError,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = keyboard,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = minHeight.dp),
    )
}
