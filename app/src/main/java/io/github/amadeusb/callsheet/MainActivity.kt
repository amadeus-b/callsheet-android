package io.github.amadeusb.callsheet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.amadeusb.callsheet.calling.CallFlow
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.DialTarget
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.ui.AppointmentSheet
import io.github.amadeusb.callsheet.ui.CallsheetTheme
import io.github.amadeusb.callsheet.ui.ContactScreen
import io.github.amadeusb.callsheet.ui.WorkListScreen
import io.github.amadeusb.callsheet.ui.BusinessDetailScreen
import io.github.amadeusb.callsheet.ui.SettingsScreen
import io.github.amadeusb.callsheet.ui.BusinessFormScreen
import io.github.amadeusb.callsheet.ui.TodayScreen
import io.github.amadeusb.callsheet.calendar.CalendarStore
import io.github.amadeusb.callsheet.contacts.PhoneBook
import io.github.amadeusb.callsheet.ui.NumberPickerDialog
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallsheetTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: CallsheetViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The business just dialled — only remembered until the app comes back
    // from the dialler.
    var selectedBusiness by remember { mutableStateOf<String?>(null) }

    // Call log permission: ask once, never insist.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A refusal is fine — the app then runs without automatic durations. */ }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    // Contacts permission: only once the user switches phone book storage on —
    // before that the app has no use for it.
    val contactPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { outcome ->
        val granted = outcome.values.all { it }
        vm.setPhoneBookEnabled(granted)
        if (!granted) vm.phoneBookDenied()
    }

    // Calendar permission: only once the user switches the calendar on —
    // before that the app has no use for it.
    val calendarPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { outcome ->
        vm.setCalendarEnabled(outcome.values.all { it })
    }

    var appointmentDate by remember { mutableStateOf(false) }
    var appointmentStart by remember { mutableStateOf(false) }
    var appointmentEnd by remember { mutableStateOf(false) }

    if (appointmentDate) {
        val draft = state.appointmentDraft
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Clock.millis(draft?.startIso),
        )
        DatePickerDialog(
            onDismissRequest = { appointmentDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    val startIso = draft?.startIso
                    val startMillis = Clock.millis(startIso)
                    if (draft != null && picked != null && startMillis != null) {
                        // The picker returns midnight UTC. Only the date is
                        // taken from it; the time of day stays as agreed.
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Clock.zdt(startMillis).toLocalTime()
                        vm.updateAppointmentDraft(
                            draft.copy(startIso = Clock.format(date.atTime(time).atZone(Clock.zone)))
                        )
                    }
                    appointmentDate = false
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { appointmentDate = false }) { Text("Abbrechen") }
            },
        ) { DatePicker(state = pickerState) }
    }

    // Start and end share one dialog. The end is stored as a length, so picking
    // it is picking a length — an end before the start would be a negative one,
    // and is held at the fifteen minutes the picker snaps to everywhere else.
    if (appointmentStart || appointmentEnd) {
        val draft = state.appointmentDraft
        val startMillis = Clock.millis(draft?.startIso)
        val shown = if (appointmentEnd && startMillis != null) {
            Clock.zdt(startMillis + (draft?.minutes ?: 0) * 60_000L)
        } else if (startMillis != null) {
            Clock.zdt(startMillis)
        } else {
            null
        }
        val timeState = rememberTimePickerState(
            initialHour = shown?.hour ?: 9,
            initialMinute = shown?.minute ?: 0,
            is24Hour = true,
        )
        val closing = { appointmentStart = false; appointmentEnd = false }
        AlertDialog(
            onDismissRequest = closing,
            title = { Text(if (appointmentEnd) "Ende" else "Beginn") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    if (draft != null && startMillis != null) {
                        val day = Clock.zdt(startMillis).toLocalDate()
                        val picked = day.atTime(timeState.hour, timeState.minute).atZone(Clock.zone)
                        if (appointmentEnd) {
                            val length = ((picked.toInstant().toEpochMilli() - startMillis) / 60_000L).toInt()
                            vm.updateAppointmentDraft(draft.copy(minutes = length.coerceAtLeast(15)))
                        } else {
                            // Moving the start moves the appointment, it does not
                            // stretch it: the length stays as it was.
                            vm.updateAppointmentDraft(draft.copy(startIso = Clock.format(picked)))
                        }
                    }
                    closing()
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = closing) { Text("Abbrechen") } },
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.let { vm.import(it) }
        }
    }

    fun dial(placeId: String, target: DialTarget) {
        vm.rememberDialAttempt(target)
        selectedBusiness = placeId
        // Open the record before dialling: after hanging up it is already
        // there, instead of having to be found in the list again.
        vm.openBusiness(placeId)
        context.startActivity(CallFlow.dialIntent(target.number))
    }

    // The dial button in the lists: fetch the numbers; with several the dialog
    // below asks, with exactly one it dials straight away.
    val selection = state.numberPicker
    LaunchedEffect(selection) {
        val only = selection?.targets?.singleOrNull() ?: return@LaunchedEffect
        vm.numberPickerDismissed()
        dial(selection.placeId, only)
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // Back from the dialler: evaluate the call log.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle, selectedBusiness) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val id = selectedBusiness
                if (id != null) {
                    selectedBusiness = null
                    vm.openBusiness(id)
                    vm.evaluateCall(context, id)
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    // On start and on every return to the foreground. No background service:
    // the app syncs whenever it is running anyway.
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) vm.syncNow()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    // The back gesture should show the previous screen; only on the work list —
    // the root — may it leave the app.
    BackHandler(enabled = state.screen != Screen.WorkList) { vm.back() }

    when (val screen = state.screen) {
        is Screen.WorkList -> WorkListScreen(
            businesses = state.businesses,
            totalInFilter = state.totalInFilter,
            calledToday = state.calledToday,
            filter = state.filter,
            allIndustries = state.allIndustries,
            allCities = state.allCities,
            outsideBusinessHours = state.outsideBusinessHours,
            loading = state.loading,
            onFilterChange = vm::setFilter,
            onDial = vm::queryNumbers,
            onOpen = { vm.openBusiness(it.placeId) },
            onToday = vm::showToday,
            onSettings = vm::showSettings,
            onNewBusiness = vm::showBusinessForm,
        )

        is Screen.Detail -> {
            val business = state.detail
            if (business == null) {
                LaunchedEffect(screen.placeId) { vm.openBusiness(screen.placeId) }
            } else {
                BusinessDetailScreen(
                    business = business,
                    calls = state.detailCalls,
                    contacts = state.detailContacts,
                    noteFocus = state.noteFocus,
                    statusSuggestion = state.statusSuggestion,
                    followUpSuggestion = state.followUpSuggestion,
                    hint = state.hint,
                    saving = state.savesOutcome,
                    onBack = { vm.back() },
                    onDial = { target -> dial(business.placeId, target) },
                    onOutcome = { status, note ->
                        vm.saveOutcome(business.placeId, status, note)
                    },
                    onContact = { id -> vm.showContact(business.placeId, id) },
                    onFollowUp = { vm.setFollowUp(business.placeId, it) },
                    onAppointment = { vm.openAppointment(business.placeId) },
                    onRemoveAppointment = { vm.removeAppointment(business.placeId) },
                    onOpenUrl = ::openUrl,
                    onDismissHint = vm::hintDismissed,
                )

                state.appointmentDraft?.let { draft ->
                    AppointmentSheet(
                        draft = draft,
                        onDraft = vm::updateAppointmentDraft,
                        onSave = { vm.saveAppointment() },
                        onLink = { vm.saveAppointment(linkExisting = it) },
                        onForce = { vm.saveAppointment(force = true) },
                        onPickDate = { appointmentDate = true },
                        onPickStart = { appointmentStart = true },
                        onPickEnd = { appointmentEnd = true },
                        onDismiss = vm::dismissAppointment,
                    )
                }
            }
        }

        is Screen.Today -> TodayScreen(
            appointments = state.appointmentsToday,
            overdue = state.overdue,
            dueToday = state.dueToday,
            onBack = { vm.back() },
            onDial = vm::queryNumbers,
            onOpen = { vm.openBusiness(it.placeId) },
        )

        is Screen.BusinessForm -> BusinessFormScreen(
            draft = state.draft,
            knownIndustries = state.allIndustries,
            knownCities = state.allCities,
            error = state.formError,
            saving = state.saving,
            onChange = vm::updateDraft,
            onSave = vm::saveDraft,
            onCancel = { vm.back() },
        )

        is Screen.ContactForm -> ContactScreen(
            draft = state.contactDraft,
            businessName = state.detail?.name ?: "diesem Betrieb",
            error = state.contactError,
            saving = state.saving,
            onChange = vm::updateContactDraft,
            onSave = vm::saveContact,
            onDelete = {
                screen.id?.let { vm.deleteContact(screen.placeId, it) }
            },
            onCancel = { vm.back() },
        )

        is Screen.Settings -> SettingsScreen(
            blockedBusinesses = state.blockedBusinesses,
            lastImportResult = state.lastImportResult,
            phoneBookEnabled = state.phoneBookEnabled,
            phoneBookAccount = state.phoneBookAccount,
            addressBookAccounts = state.addressBookAccounts,
            phoneBookHint = state.phoneBookHint,
            pushing = state.saving,
            syncState = syncState,
            onBack = { vm.back() },
            onUnblock = vm::unblock,
            onImport = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onPhoneBook = { on ->
                if (on && !PhoneBook.canWrite(context)) {
                    contactPermissions.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.WRITE_CONTACTS,
                        )
                    )
                } else {
                    vm.setPhoneBookEnabled(on)
                }
            },
            onAccount = vm::setAddressBookAccount,
            onLoadAccounts = vm::loadAddressBookAccounts,
            calendarEnabled = state.calendarEnabled,
            calendar = state.calendar,
            calendars = state.calendars,
            onCalendarToggle = { on ->
                if (on && !CalendarStore.canWrite(context)) {
                    calendarPermissions.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                        )
                    )
                } else {
                    vm.setCalendarEnabled(on)
                }
            },
            onPickCalendar = vm::pickCalendar,
            onLoadCalendars = vm::loadCalendars,
            onPushAll = vm::pushAllToPhoneBook,
            onOpenServerDialog = vm::openServerDialog,
            onCloseServerDialog = vm::closeServerDialog,
            onConnectServer = vm::connectServer,
            onReuploadAll = vm::reuploadAll,
        )
    }

    if (selection != null && selection.targets.size > 1) {
        NumberPickerDialog(
            selection = selection,
            onDial = { target ->
                vm.numberPickerDismissed()
                dial(selection.placeId, target)
            },
            onClose = vm::numberPickerDismissed,
        )
    }
}
