package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.Contact

/** One address the mail can go out to, and who it belongs to. */
private data class MailRecipient(val address: String, val label: String)

/**
 * The mail addresses on record for a business: its own address, plus every
 * address of every contact. Deduplicated by address — the same address
 * offered twice would let it be picked, and then sent to, twice.
 */
private fun recipientsFor(business: Business, contacts: List<Contact>): List<MailRecipient> =
    buildList {
        business.email?.trim()?.takeIf { it.isNotEmpty() }?.let { add(MailRecipient(it, "Firma")) }
        for (contact in contacts) {
            for (email in contact.emails) {
                email.email.trim().takeIf { it.isNotEmpty() }?.let { add(MailRecipient(it, contact.name)) }
            }
        }
    }.distinctBy { it.address }

/**
 * Sends a mail to any number of the business's and its contacts' addresses,
 * through the server's `/send-mail` endpoint.
 *
 * Subject and body start out from the templates in the settings, with
 * `{{business_name}}` already replaced — from there they are free text.
 * [sending] shows a spinner in the send button instead of disabling the whole
 * dialog: the recipients and the text stay visible and, on a failure, editable
 * for another attempt. [error] is shown inside the dialog and never closes it —
 * only a send that went through does that.
 */
@Composable
fun MailDialog(
    business: Business,
    contacts: List<Contact>,
    subjectTemplate: String,
    bodyTemplate: String,
    sending: Boolean,
    error: String?,
    onSend: (to: List<String>, subject: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val recipients = remember(business, contacts) { recipientsFor(business, contacts) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var subject by remember { mutableStateOf(subjectTemplate.replace("{{business_name}}", business.name)) }
    var body by remember { mutableStateOf(bodyTemplate.replace("{{business_name}}", business.name)) }

    val canSend = selected.isNotEmpty() && subject.isNotBlank() && body.isNotBlank() && !sending

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Mail senden") },
        text = {
            Column {
                if (recipients.isEmpty()) {
                    Text("Für diesen Betrieb ist keine E-Mail-Adresse hinterlegt.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(recipients, key = { it.address }) { recipient ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !sending) {
                                        selected = if (recipient.address in selected) {
                                            selected - recipient.address
                                        } else {
                                            selected + recipient.address
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = recipient.address in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + recipient.address else selected - recipient.address
                                    },
                                    enabled = !sending,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(recipient.address, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = recipient.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.heightIn(min = 8.dp))
                }

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Betreff") },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Text") },
                    minLines = 6,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(selected.toList(), subject, body) },
                enabled = canSend,
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (sending) "Sendet …" else "Senden")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Abbrechen") }
        },
    )
}
