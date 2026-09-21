package io.github.amadeusb.callsheet.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
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

                SpellCheckedField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "Betreff",
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                SpellCheckedField(
                    value = body,
                    onValueChange = { body = it },
                    label = "Text",
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

/**
 * A text field built on the platform's [EditText] instead of Compose's own.
 *
 * Only for the sake of the spell checker: Compose text fields do not draw the
 * red underline of the system spell checker, and do not offer its
 * corrections on a tap. An [EditText] does both, with whichever spell checker
 * is chosen in the device's settings.
 */
@Composable
private fun SpellCheckedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // The listener is created once with the view; this keeps it calling the
    // current callback, not the one from the first composition.
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.TOP or Gravity.START
                    // Without NO_SUGGESTIONS and not a password: the two
                    // conditions under which the spell checker runs at all.
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                        (if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    if (singleLine) maxLines = 1 else setMinLines(minLines)
                    setText(value)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            currentOnValueChange(s?.toString().orEmpty())
                        }
                    })
                    setOnFocusChangeListener { _, hasFocus -> focused = hasFocus }
                }
            },
            update = { field ->
                field.setTextColor(colors.onSurface.toArgb())
                field.isEnabled = enabled
                field.alpha = if (enabled) 1f else 0.6f
                // Only a change from outside is written back: rewriting what
                // was just typed would move the cursor to the start.
                if (field.text.toString() != value) field.setText(value)
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) colors.primary else colors.outline,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
