package io.github.amadeusb.callsheet

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import io.github.amadeusb.callsheet.calendar.BusyTimes
import io.github.amadeusb.callsheet.calendar.CalendarAccount
import io.github.amadeusb.callsheet.calendar.CalendarStore
import io.github.amadeusb.callsheet.calendar.EventFields
import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.calling.CallFlow
import io.github.amadeusb.callsheet.calling.ReadBack
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.calling.CallLogReader
import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.CallEntry
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.EntryKind
import io.github.amadeusb.callsheet.data.Filter
import io.github.amadeusb.callsheet.data.ImportResult
import io.github.amadeusb.callsheet.data.BusinessDraft
import io.github.amadeusb.callsheet.data.PhoneDraft
import io.github.amadeusb.callsheet.data.Repository
import io.github.amadeusb.callsheet.data.Status
import io.github.amadeusb.callsheet.data.DialTarget
import io.github.amadeusb.callsheet.data.Clock
import io.github.amadeusb.callsheet.contacts.AddressBookAccount
import io.github.amadeusb.callsheet.contacts.ContactStore
import io.github.amadeusb.callsheet.contacts.Preferences
import io.github.amadeusb.callsheet.contacts.PhoneBook
import io.github.amadeusb.callsheet.sync.FailureKind
import io.github.amadeusb.callsheet.sync.isAcceptableServerAddress
import io.github.amadeusb.callsheet.sync.SyncClient
import io.github.amadeusb.callsheet.sync.SyncEngine
import io.github.amadeusb.callsheet.sync.SyncResult
import io.github.amadeusb.callsheet.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/** Which screen is currently on show. */
sealed interface Screen {
    data object WorkList : Screen
    data class Detail(val placeId: String) : Screen
    data object Today : Screen
    data object Settings : Screen
    data object BusinessForm : Screen

    /** The form for a contact; a null [id] means a new one. */
    data class ContactForm(val placeId: String, val id: String?) : Screen
}

/** The numbers of a business the user is currently choosing between. */
data class NumberPicker(
    val placeId: String,
    val name: String,
    val targets: List<DialTarget>,
)

/**
 * The appointment being set. Lives only while the sheet is open — cancelling
 * throws it away, and nothing has been written by then.
 */
data class AppointmentDraft(
    val placeId: String,
    val startIso: String,
    val minutes: Int,
    val location: String,
    /**
     * Everything already taken on that day, for the timeline. The business's own
     * event is filtered out: it would otherwise collide with itself on every
     * change, and the conflict question would be unanswerable.
     */
    val busy: List<BusyInterval> = emptyList(),
    /** What the chosen window runs into. Empty means it is free. */
    val conflict: List<BusyInterval> = emptyList(),
    /**
     * Whether the calendar could be read at all. Without this, an empty [busy]
     * would be indistinguishable from a genuinely free day — and telling the
     * user a day is free when the app simply cannot see it is the one lie this
     * feature must not tell.
     */
    val calendarReadable: Boolean = true,
)

data class State(
    val screen: Screen = Screen.WorkList,
    /** The screens behind the current one — the most recent last. */
    val history: List<Screen> = emptyList(),
    val businesses: List<Business> = emptyList(),
    val totalInFilter: Int = 0,
    val calledToday: Int = 0,
    val filter: Filter = Filter(),
    val allIndustries: List<String> = emptyList(),
    val allCities: List<String> = emptyList(),
    val loading: Boolean = false,
    val detail: Business? = null,
    val detailCalls: List<CallEntry> = emptyList(),
    val detailContacts: List<Contact> = emptyList(),
    /** When a dial attempt is up for choosing, the numbers hang here. */
    val numberPicker: NumberPicker? = null,
    val contactDraft: ContactDraft = ContactDraft(),
    val contactError: String? = null,
    /** The log entry of the call just made, still missing its outcome. */
    val pendingCall: String? = null,
    val savesOutcome: Boolean = false,
    val noteFocus: Boolean = false,
    val statusSuggestion: Status? = null,
    val followUpSuggestion: String? = null,
    val hint: String? = null,
    val overdue: List<Business> = emptyList(),
    val dueToday: List<Business> = emptyList(),
    val blockedBusinesses: List<Business> = emptyList(),
    val lastImportResult: ImportResult? = null,
    /** Phone book: whether to store, where to, and what is on offer. */
    val phoneBookEnabled: Boolean = false,
    val phoneBookAccount: AddressBookAccount? = null,
    val addressBookAccounts: List<AddressBookAccount> = emptyList(),
    val phoneBookHint: String? = null,
    /** Calendar: whether to mirror appointments, where to, and what is on offer. */
    val calendarEnabled: Boolean = false,
    val calendar: CalendarAccount? = null,
    val calendars: List<CalendarAccount> = emptyList(),
    val appointmentDraft: AppointmentDraft? = null,
    val appointmentsToday: List<Business> = emptyList(),
    val outsideBusinessHours: Boolean = false,
    val draft: BusinessDraft = BusinessDraft(),
    val formError: String? = null,
    val saving: Boolean = false,
)

/** What the settings screen shows about synchronisation. */
data class SyncUiState(
    val url: String = "",
    val token: String = "",
    val lastSyncAt: String? = null,
    val pending: Int = 0,
    val running: Boolean = false,
    val error: String? = null,
)

class CallsheetViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = Repository(application)
    private val preferences = Preferences(application)
    private val store = ContactStore(application, repo, preferences)
    private val syncStore = SyncStore(application)
    private val syncEngine = SyncEngine(syncStore, preferences)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _syncState = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    /** What was dialled last — the basis for the log entry that follows. */
    private data class DialAttempt(val number: String, val label: String, val from: Long)

    private var activeCall: DialAttempt? = null

    /** The running list query — dropped on every keystroke in the search field. */
    private var listQuery: Job? = null

    init {
        refreshList()
        refreshSyncState()
        _state.value = _state.value.copy(
            phoneBookEnabled = preferences.phoneBookEnabled,
            phoneBookAccount = preferences.account,
            calendarEnabled = preferences.calendarEnabled,
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(
                allIndustries = repo.industries(),
                allCities = repo.cities(),
                outsideBusinessHours = Clock.outsideBusinessHours(),
            )
        }
    }

    // --- Synchronisation ----------------------------------------------------

    /**
     * Runs a sync when a server is configured. Errors stay in the settings screen:
     * in the middle of a call, a network hiccup is not worth a message.
     */
    fun syncNow(quiet: Boolean = true) {
        val url = preferences.serverUrl ?: return
        val token = preferences.serverToken ?: return
        if (_syncState.value.running) return

        viewModelScope.launch {
            _syncState.value = _syncState.value.copy(running = true)
            val result = withContext(Dispatchers.IO) { syncEngine.sync(SyncClient(url, token)) }
            // NETWORK and RATE_LIMITED stay silent on an automatic run — both are
            // common and self-healing, and showing them here would make the
            // settings screen look broken most of the time; every other kind,
            // including SERVER and TOO_LARGE, is shown even when quiet, because
            // none of them gets better on its own and "shown" only ever means one
            // line in the settings — it interrupts nothing.
            val error = when (result) {
                is SyncResult.Failed ->
                    if (quiet && (result.kind == FailureKind.NETWORK || result.kind == FailureKind.RATE_LIMITED)) {
                        null
                    } else {
                        result.message
                    }
                else -> null
            }
            _syncState.value = _syncState.value.copy(running = false, error = error)
            refreshSyncState()
            if (result is SyncResult.Ok) {
                refreshList()
                loadToday()
            }
        }
    }

    fun setServer(url: String, token: String) {
        val trimmed = url.ifBlank { null }
        if (trimmed != null && !isAcceptableServerAddress(trimmed)) {
            _syncState.value = _syncState.value.copy(
                error = "Nur eine Adresse, die mit https:// beginnt, wird angenommen — Android blockiert unverschlüsselte Verbindungen.",
            )
            return
        }
        // Compared against the normalised value already stored — Preferences
        // trims and drops a trailing slash on the way in — so re-saving the
        // same address unchanged does not look like a change.
        val addressChanged = trimmed?.trim()?.trimEnd('/') != preferences.serverUrl
        preferences.serverUrl = trimmed
        preferences.serverToken = token.ifBlank { null }
        if (addressChanged) {
            // A different server is a different watermark — otherwise the app
            // would believe it had already read a stock it has never seen —
            // and a different server has never seen this device's data
            // either, so everything must go up again.
            viewModelScope.launch {
                withContext(Dispatchers.IO) { syncEngine.resetForFullResync() }
                refreshSyncState()
            }
        } else {
            refreshSyncState()
        }
    }

    /**
     * Marks the entire local stock as unsent and resets the watermark to
     * zero. For the settings screen, behind a confirmation — after restoring
     * an older server backup, or before pointing the app at a server that
     * has never seen this device's data. In the restore case the watermark
     * reset matters just as much as the marking: the server's own counter
     * now sits below this device's, and without the reset the app would
     * never fetch what the server writes in between. The jump in "offen"
     * afterwards is the honest signal that it worked.
     */
    fun reuploadAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { syncEngine.resetForFullResync() }
            refreshSyncState()
        }
    }

    private fun refreshSyncState() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) { syncStore.pendingCount() }
            _syncState.value = _syncState.value.copy(
                url = preferences.serverUrl.orEmpty(),
                token = preferences.serverToken.orEmpty(),
                lastSyncAt = preferences.lastSyncAt,
                pending = pending,
            )
        }
    }

    // --- Navigation -------------------------------------------------------

    /**
     * What the history looks like after jumping to [new]: the current screen is
     * remembered so the back button leads there. The work list is the root —
     * jumping to it leaves nothing behind you.
     */
    private fun historyFor(new: Screen): List<Screen> {
        val current = _state.value.screen
        return when {
            new == Screen.WorkList -> emptyList()
            new == current -> _state.value.history
            else -> _state.value.history + current
        }
    }

    /**
     * One step back. Returns false when nothing lies behind the current screen —
     * the back button may then close the app.
     */
    fun back(): Boolean {
        val history = _state.value.history
        val previous = history.lastOrNull()
            ?: if (_state.value.screen == Screen.WorkList) return false else Screen.WorkList
        _state.value = _state.value.copy(screen = previous, history = history.dropLast(1))
        // The screen may have gone stale in the meantime: load it afresh.
        when (previous) {
            is Screen.WorkList -> refreshList()
            is Screen.Detail -> loadDetail(previous.placeId)
            is Screen.Today -> loadToday()
            is Screen.Settings -> loadBlocked()
            is Screen.BusinessForm -> Unit
            is Screen.ContactForm -> Unit
        }
        return true
    }

    fun showWorkList() {
        _state.value = _state.value.copy(screen = Screen.WorkList, history = emptyList())
        refreshList()
    }

    /**
     * Opens the detail view. With [replaceCurrent] it takes the place of the
     * current screen instead of pushing it onto the history.
     */
    fun openBusiness(placeId: String, replaceCurrent: Boolean = false) {
        val screen = Screen.Detail(placeId)
        // Suggestions, hint and pending call belong to the previous business —
        // they must not travel along when switching.
        val switching = _state.value.detail?.placeId != placeId
        _state.value = _state.value.copy(
            screen = screen,
            history = if (replaceCurrent) _state.value.history else historyFor(screen),
            detail = if (switching) null else _state.value.detail,
            detailCalls = if (switching) emptyList() else _state.value.detailCalls,
            detailContacts = if (switching) emptyList() else _state.value.detailContacts,
            statusSuggestion = if (switching) null else _state.value.statusSuggestion,
            followUpSuggestion = if (switching) null else _state.value.followUpSuggestion,
            hint = if (switching) null else _state.value.hint,
            noteFocus = if (switching) false else _state.value.noteFocus,
            pendingCall = if (switching) null else _state.value.pendingCall,
        )
        loadDetail(placeId)
        // No loop: syncAppointment only ever calls loadDetail, never back here.
        syncAppointment(placeId)
    }

    fun showToday() {
        _state.value = _state.value.copy(screen = Screen.Today, history = historyFor(Screen.Today))
        loadToday()
    }

    private fun loadToday() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val due = repo.due(Clock.todayStart(now) + 24L * 60 * 60 * 1000)
            val (late, today) = due.partition { FollowUp.isOverdue(it.followUpAt, now) }
            val appointments = repo.appointmentsDue(
                fromMillis = Clock.todayStart(now),
                toMillis = Clock.todayStart(now) + 24L * 60 * 60 * 1000,
            )
            _state.value = _state.value.copy(
                overdue = late,
                dueToday = today,
                appointmentsToday = appointments,
            )
        }
    }

    /** Opens an empty entry form. Any earlier draft is discarded. */
    fun showBusinessForm() {
        _state.value = _state.value.copy(
            screen = Screen.BusinessForm,
            history = historyFor(Screen.BusinessForm),
            draft = BusinessDraft(),
            formError = null,
        )
    }

    fun updateDraft(draft: BusinessDraft) {
        _state.value = _state.value.copy(draft = draft, formError = null)
    }

    /**
     * Creates the entered business and, on success, jumps straight into its
     * detail view — from there the user can dial immediately.
     */
    fun saveDraft() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, formError = null)
            val outcome = repo.create(_state.value.draft)
            outcome.fold(
                onSuccess = { placeId ->
                    _state.value = _state.value.copy(
                        saving = false,
                        draft = BusinessDraft(),
                        allIndustries = repo.industries(),
                        allCities = repo.cities(),
                    )
                    // A hand-entered business is one the user knows: its number
                    // belongs in the phone book right away, without waiting for
                    // a contact or a first call.
                    repo.business(placeId)?.let { store.persistBusiness(it) }
                    // The form is done: back leads to the work list, not the draft.
                    openBusiness(placeId, replaceCurrent = true)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        formError = error.message ?: "Der Betrieb ließ sich nicht anlegen.",
                    )
                },
            )
        }
    }

    /**
     * Opens the form for a contact. Without an [id] a new one is created,
     * otherwise the existing one is loaded for editing.
     */
    fun showContact(placeId: String, id: String? = null) {
        val existing = _state.value.detailContacts.firstOrNull { it.id == id }
        val draft = if (existing == null) {
            ContactDraft(placeId = placeId)
        } else {
            ContactDraft(
                id = existing.id,
                placeId = existing.placeId,
                name = existing.name,
                role = existing.role.orEmpty(),
                email = existing.email.orEmpty(),
                note = existing.note.orEmpty(),
                numbers = existing.numbers
                    .map { PhoneDraft(id = it.id, number = it.number, kind = it.kind) }
                    .ifEmpty { listOf(PhoneDraft()) },
            )
        }
        val screen = Screen.ContactForm(placeId, id)
        _state.value = _state.value.copy(
            screen = screen,
            history = historyFor(screen),
            contactDraft = draft,
            contactError = null,
        )
    }

    fun updateContactDraft(draft: ContactDraft) {
        _state.value = _state.value.copy(contactDraft = draft, contactError = null)
    }

    /** Saves the contact and returns to the record on success. */
    fun saveContact() {
        if (_state.value.saving) return
        val draft = _state.value.contactDraft
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, contactError = null)
            repo.saveContact(draft).fold(
                onSuccess = { id ->
                    _state.value = _state.value.copy(saving = false)
                    val business = repo.business(draft.placeId)
                    repo.contacts(draft.placeId).firstOrNull { it.id == id }?.let {
                        store.persistContact(it, business)
                    }
                    // The business itself belongs there too, otherwise a call
                    // back from the switchboard stays nameless.
                    business?.let { store.persistBusiness(it) }
                    back()
                    loadDetail(draft.placeId)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        contactError = error.message
                            ?: "Der Ansprechpartner ließ sich nicht speichern.",
                    )
                },
            )
        }
    }

    fun deleteContact(placeId: String, id: String) {
        viewModelScope.launch {
            repo.deleteContact(id)
            store.deleteContact(id)
            if (_state.value.screen is Screen.ContactForm) back()
            loadDetail(placeId)
        }
    }

    fun showSettings() {
        _state.value = _state.value.copy(
            screen = Screen.Settings,
            history = historyFor(Screen.Settings),
        )
        loadBlocked()
    }

    private fun loadBlocked() {
        viewModelScope.launch {
            _state.value = _state.value.copy(blockedBusinesses = repo.blockedBusinesses())
        }
    }

    // --- Phone book -------------------------------------------------------

    /**
     * Switches storing in the phone book on or off. It is only switched on when
     * the permission is there — the UI takes care of that beforehand.
     */
    fun setPhoneBookEnabled(active: Boolean) {
        preferences.phoneBookEnabled = active
        _state.value = _state.value.copy(
            phoneBookEnabled = active,
            phoneBookHint = null,
        )
        if (active) loadAddressBookAccounts()
    }

    /** Without the permission it stays off — with a word on why. */
    fun phoneBookDenied() {
        _state.value = _state.value.copy(
            phoneBookEnabled = false,
            phoneBookHint = "Ohne Zugriff auf die Kontakte kann die App nichts im " +
                "Telefonbuch ablegen. Du kannst die Berechtigung in den " +
                "Android-Einstellungen der App nachträglich erteilen.",
        )
    }

    // ------------------------------------------------------------------ Calendar

    /** Loads the device's calendars for the picker. */
    fun loadCalendars() {
        viewModelScope.launch {
            val found = CalendarStore.calendars(getApplication())
            val chosen = preferences.calendarId?.let { id -> found.firstOrNull { it.id == id } }
            _state.update { it.copy(calendars = found, calendar = chosen) }
        }
    }

    fun setCalendarEnabled(enabled: Boolean) {
        preferences.calendarEnabled = enabled
        _state.update { it.copy(calendarEnabled = enabled) }
        if (enabled) loadCalendars()
    }

    fun pickCalendar(calendar: CalendarAccount) {
        preferences.calendarId = calendar.id
        _state.update { it.copy(calendar = calendar) }
    }

    // --------------------------------------------------------------- Appointment

    /** Opens the sheet, prefilled from the business and the last duration used. */
    fun openAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            val start = business.appointmentAt ?: FollowUp.inTwoDays()
            val minutes = if (business.appointmentAt != null) {
                Appointment.minutesBetween(business.appointmentAt, business.appointmentEndAt)
            } else {
                preferences.appointmentMinutes
            }
            val location = business.appointmentLocation
                ?: Appointment.address(business.street, business.postalCode, business.city)
                ?: ""
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        calendarReadable = CalendarStore.canRead(getApplication()),
                    )
                )
            }
            Clock.millis(start)?.let { loadBusy(it, business.calendarEventId) }
        }
    }

    /**
     * Reads the busy times for the day containing [millis].
     *
     * [ownEventId] drops out of the result. An appointment being changed is
     * already in the calendar, so leaving it in would make every save collide
     * with itself — and the conflict question would offer to link an appointment
     * to itself.
     */
    private fun loadBusy(millis: Long, ownEventId: Long?) {
        viewModelScope.launch {
            val dayStart = Clock.todayStart(millis)
            val busy = BusyTimes.forDay(getApplication(), dayStart)
                .filter { it.eventId == null || it.eventId != ownEventId }
            _state.update { state ->
                val draft = state.appointmentDraft ?: return@update state
                state.copy(appointmentDraft = draft.copy(busy = busy, conflict = emptyList()))
            }
        }
    }

    fun updateAppointmentDraft(draft: AppointmentDraft) {
        val previous = _state.value.appointmentDraft
        val previousDay = Clock.todayStart(Clock.millis(previous?.startIso) ?: 0L)
        val newDay = Clock.todayStart(Clock.millis(draft.startIso) ?: return)
        val dayChanged = previous == null || previousDay != newDay

        // On a new day the old day's busy times are dropped straight away.
        // Keeping them until the reload returns would place yesterday's
        // appointments against today's midnight — foreign blocks standing at
        // times nobody is busy.
        _state.update {
            it.copy(
                appointmentDraft = draft.copy(
                    conflict = emptyList(),
                    busy = if (dayChanged) emptyList() else draft.busy,
                )
            )
        }

        if (dayChanged) {
            viewModelScope.launch {
                val start = Clock.millis(draft.startIso) ?: return@launch
                loadBusy(start, repo.business(draft.placeId)?.calendarEventId)
            }
        }
    }

    fun dismissAppointment() {
        _state.update { it.copy(appointmentDraft = null) }
    }

    /** Writes the appointment: the four columns, the status, and the calendar. */
    fun saveAppointment(linkExisting: Long? = null, force: Boolean = false) {
        val draft = _state.value.appointmentDraft ?: return
        viewModelScope.launch {
            val startMillis = Clock.millis(draft.startIso) ?: return@launch
            val endIsoFromDraft = Appointment.endOf(draft.startIso, draft.minutes)
            val endMillis = Clock.millis(endIsoFromDraft) ?: return@launch
            val business = repo.business(draft.placeId)
            val location = draft.location.trim().ifEmpty { null }

            val plan = Appointment.plan(
                startMillis = startMillis,
                endMillis = endMillis,
                busy = draft.busy,
                ownEventId = business?.calendarEventId,
                linkExisting = linkExisting,
                force = force,
                calendarEnabled = preferences.calendarEnabled,
            )

            if (plan is SavePlan.Conflict) {
                _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
                return@launch
            }

            val fields = EventFields(
                title = "Ortstermin ${business?.name ?: ""}".trim(),
                startMillis = startMillis,
                endMillis = endMillis,
                location = location,
                description = business?.phone,
            )

            // Adopting takes the calendar's values, so the columns written below
            // differ per plan. Every other case writes the draft.
            var atIso = draft.startIso
            var endIso = endIsoFromDraft
            var place = location

            val eventId: Long? = when (plan) {
                is SavePlan.Adopt -> {
                    val event = CalendarStore.read(getApplication(), plan.eventId)
                    if (event != null) {
                        atIso = Clock.format(event.startMillis)
                        endIso = Clock.format(event.endMillis)
                        place = event.location ?: location
                        plan.eventId
                    } else {
                        // Gone between listing the day and pressing save. Linking
                        // to an id that no longer resolves would leave a business
                        // pointing at nothing; keep the draft and no link.
                        null
                    }
                }

                // A failed update usually means the event is gone — deleted in
                // the calendar between opening the sheet and saving it. Falling
                // back to a new one is what the user asked for; reporting "could
                // not be written" and pointing at the permission would be a lie.
                is SavePlan.Update -> plan.eventId.takeIf {
                    CalendarStore.update(getApplication(), it, fields)
                } ?: preferences.calendarId?.let { CalendarStore.insert(getApplication(), it, fields) }

                SavePlan.Create ->
                    preferences.calendarId?.let { CalendarStore.insert(getApplication(), it, fields) }

                SavePlan.LocalOnly -> null
                is SavePlan.Conflict -> null // already returned above
            }

            preferences.appointmentMinutes = draft.minutes
            repo.setAppointment(draft.placeId, atIso, endIso, place, eventId)
            repo.setStatus(draft.placeId, Status.APPOINTMENT)
            _state.update {
                it.copy(
                    appointmentDraft = null,
                    hint = if (preferences.calendarEnabled && plan !is SavePlan.LocalOnly && eventId == null) {
                        "Termin gespeichert. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    } else {
                        null
                    },
                )
            }
            loadDetail(draft.placeId)
        }
    }

    /**
     * Brings a linked appointment back in line with the calendar.
     *
     * The calendar wins: that is where an appointment gets moved, on a laptop or
     * in the car. The same rule the phone book already follows for names and
     * numbers.
     *
     * A deleted event is the only case that speaks up, because it is the only
     * one that needs a decision.
     */
    private fun syncAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            val eventId = business.calendarEventId ?: return@launch
            if (!CalendarStore.canRead(getApplication())) return@launch

            val event = CalendarStore.read(getApplication(), eventId)
            val decision = Appointment.readBack(
                currentAt = business.appointmentAt,
                currentEnd = business.appointmentEndAt,
                currentLocation = business.appointmentLocation,
                eventStartMillis = event?.startMillis,
                eventEndMillis = event?.endMillis,
                eventLocation = event?.location,
            )

            when (decision) {
                is ReadBack.Unchanged -> Unit

                is ReadBack.Updated -> {
                    repo.setAppointment(
                        placeId, decision.startIso, decision.endIso, decision.location, eventId,
                    )
                    loadDetail(placeId)
                }

                is ReadBack.Gone -> {
                    repo.setAppointment(placeId, null, null, null, null)
                    // Only the status this appointment set gets taken back. A
                    // business that has since been declined or blocked keeps
                    // that — deleting an entry in the calendar is not
                    // permission to undo a decision made on the phone.
                    val reset = business.status == Status.APPOINTMENT
                    if (reset) repo.setStatus(placeId, Status.CALLED)
                    _state.update {
                        it.copy(
                            hint = if (reset) {
                                "Der Termin wurde im Kalender gelöscht. Status zurück auf „Angerufen“."
                            } else {
                                "Der Termin wurde im Kalender gelöscht. Der Status bleibt, wie er ist."
                            }
                        )
                    }
                    loadDetail(placeId)
                }
            }
        }
    }

    /**
     * Removes the appointment and its calendar event.
     *
     * The status only falls back when it is still `appointment`. A business set
     * to `declined` or `do_not_call` in the meantime keeps that — those are
     * decisions the user made, and removing an appointment is not permission to
     * undo them.
     */
    fun removeAppointment(placeId: String) {
        viewModelScope.launch {
            val business = repo.business(placeId) ?: return@launch
            business.calendarEventId?.let { CalendarStore.delete(getApplication(), it) }
            repo.setAppointment(placeId, null, null, null, null)
            if (business.status == Status.APPOINTMENT) repo.setStatus(placeId, Status.CALLED)
            loadDetail(placeId)
        }
    }

    fun setAddressBookAccount(account: AddressBookAccount?) {
        preferences.account = account
        _state.value = _state.value.copy(phoneBookAccount = account)
    }

    /** Reads the device's address books for the picker in the settings. */
    fun loadAddressBookAccounts() {
        viewModelScope.launch {
            val accounts = PhoneBook.accounts(getApplication())
            _state.value = _state.value.copy(
                addressBookAccounts = accounts,
                phoneBookHint = if (accounts.isEmpty()) {
                    "Auf dem Gerät ist kein Adressbuch-Konto mit Kontakten zu finden. " +
                        "Richte in DAVx5 ein Adressbuch ein und synchronisiere " +
                        "einmal — danach steht es hier zur Auswahl."
                } else {
                    null
                },
            )
        }
    }

    /**
     * Stores every contact and every business that belongs in the phone book —
     * for the first run after switching the feature on.
     */
    fun pushAllToPhoneBook() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, phoneBookHint = null)
            var people = 0
            var businesses = 0
            for (business in repo.businessesForPhoneBook()) {
                val contacts = repo.contacts(business.placeId)
                contacts.forEach { store.persistContact(it, business) }
                people += contacts.size
                store.persistBusiness(business)
                businesses++
            }
            _state.value = _state.value.copy(
                saving = false,
                phoneBookHint = "Übertragen: $businesses Betriebe und $people " +
                    "Ansprechpartner. DAVx5 lädt sie beim nächsten Abgleich hoch.",
            )
        }
    }

    // --- Work list --------------------------------------------------------

    fun setFilter(filter: Filter) {
        // The search field reports every keystroke. Only that gets debounced;
        // toggling a filter chip should feel immediate.
        val onlySearchChanged = filter.copy(search = "") == _state.value.filter.copy(search = "")
        _state.value = _state.value.copy(filter = filter)
        refreshList(if (onlySearchChanged) 250L else 0L)
    }

    private fun refreshList(delayMillis: Long = 0L) {
        listQuery?.cancel()
        listQuery = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            _state.value = _state.value.copy(loading = true)
            val f = _state.value.filter
            val list = repo.list(f)
            _state.value = _state.value.copy(
                businesses = list,
                totalInFilter = repo.count(f),
                calledToday = repo.calledToday(f),
                loading = false,
            )
        }
    }

    private fun loadDetail(placeId: String) {
        viewModelScope.launch {
            val contacts = repo.contacts(placeId)
            _state.value = _state.value.copy(
                detail = repo.business(placeId),
                detailCalls = repo.calls(placeId),
                detailContacts = contacts,
            )
            // Whatever was changed in the phone book wins — afterwards the
            // record is level with the address book again.
            if (store.readBack(contacts)) {
                _state.value = _state.value.copy(
                    detailContacts = repo.contacts(placeId),
                )
            }
        }
    }

    // --- Call flow --------------------------------------------------------

    /**
     * The dial button in a list: gather the business's numbers and offer them.
     * A single number is dialled without asking — the UI can tell from the
     * length of the list.
     */
    fun queryNumbers(business: Business) {
        viewModelScope.launch {
            val targets = CallFlow.dialTargets(business, repo.contacts(business.placeId))
            if (targets.isEmpty()) return@launch
            _state.value = _state.value.copy(
                numberPicker = NumberPicker(business.placeId, business.name, targets),
            )
        }
    }

    fun numberPickerDismissed() {
        _state.value = _state.value.copy(numberPicker = null)
    }

    /** Remembers number, label and time before the dial intent starts. */
    fun rememberDialAttempt(target: DialTarget) {
        activeCall = DialAttempt(target.number, target.label, System.currentTimeMillis())
    }

    /**
     * After coming back from the dialler: read the duration out of the call log,
     * record the call and offer a suggestion — never apply one automatically.
     */
    fun evaluateCall(context: Context, placeId: String) {
        val attempt = activeCall ?: return
        val (number, label, from) = attempt
        activeCall = null
        viewModelScope.launch {
            val duration = CallLogReader.findDuration(context, number, from)
            val suggestion = CallFlow.suggestion(duration)
            val entry = UUID.randomUUID().toString()
            repo.logCall(
                CallEntry(
                    id = entry,
                    placeId = placeId,
                    startedAt = Clock.format(from),
                    durationSeconds = duration ?: 0,
                    outcome = null,
                    note = if (duration == null) "Dauer nicht ermittelbar" else null,
                    kind = EntryKind.CALL,
                    contact = label,
                )
            )
            // Whoever was called can call back: the business belongs in the
            // phone book now, so the number has a name attached to it.
            repo.business(placeId)?.let { store.persistBusiness(it) }
            _state.value = _state.value.copy(
                // Outcome and note land in exactly this entry when saved.
                pendingCall = entry,
                statusSuggestion = suggestion.status,
                followUpSuggestion = suggestion.followUpIso,
                hint = suggestion.hint,
                noteFocus = duration != null && duration > 0,
            )
            loadDetail(placeId)
        }
    }

    fun hintDismissed() {
        _state.value = _state.value.copy(hint = null, noteFocus = false)
    }

    // --- Working fields ---------------------------------------------------

    /**
     * Saves status and note in one go and records both in the log.
     *
     * When a call has just happened, its entry gains the outcome and note. With
     * no call, an entry of kind [EntryKind.NOTE] is created instead, so the
     * record stays without gaps.
     */
    fun saveOutcome(placeId: String, status: Status, note: String) {
        val before = _state.value.detail
        val newStatus = before?.placeId != placeId || before.status != status
        val noteChanged = before?.placeId != placeId || (before.note ?: "") != note
        if (!newStatus && !noteChanged) return
        if (_state.value.savesOutcome) return

        viewModelScope.launch {
            _state.value = _state.value.copy(savesOutcome = true)
            if (newStatus) repo.setStatus(placeId, status)
            if (noteChanged) repo.setNote(placeId, note)

            val open = _state.value.pendingCall
            if (open != null) {
                repo.completeCall(open, outcome = status.label, note = note)
                // The call just wrapped up is the moment the most valuable
                // change happens — worth a sync attempt on its own.
                syncNow()
            } else {
                repo.logCall(
                    CallEntry(
                        id = UUID.randomUUID().toString(),
                        placeId = placeId,
                        startedAt = Clock.now(),
                        durationSeconds = 0,
                        outcome = status.label,
                        note = note.ifBlank { null },
                        kind = EntryKind.NOTE,
                    )
                )
            }

            _state.value = _state.value.copy(
                pendingCall = null,
                statusSuggestion = null,
                noteFocus = false,
                savesOutcome = false,
            )
            if (status == Status.DO_NOT_CALL) showWorkList() else loadDetail(placeId)
        }
    }

    fun setFollowUp(placeId: String, iso: String?) {
        viewModelScope.launch {
            repo.setFollowUp(placeId, iso)
            _state.value = _state.value.copy(followUpSuggestion = null)
            loadDetail(placeId)
        }
    }

    fun unblock(business: Business) {
        viewModelScope.launch {
            repo.setStatus(business.placeId, Status.NEW)
            _state.value = _state.value.copy(blockedBusinesses = repo.blockedBusinesses())
        }
    }

    // --- Import -----------------------------------------------------------

    fun import(input: InputStream) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val outcome = repo.import(input)
            _state.value = _state.value.copy(
                lastImportResult = outcome,
                allIndustries = repo.industries(),
                allCities = repo.cities(),
                loading = false,
            )
            refreshList()
        }
    }
}
