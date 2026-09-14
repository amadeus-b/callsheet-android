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
import io.github.amadeusb.callsheet.calling.Located
import io.github.amadeusb.callsheet.calling.ReadBack
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.calling.CallLogReader
import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.AppointmentEntry
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
import kotlinx.coroutines.CancellationException
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
    /** The appointment being changed; null for a new one. */
    val appointmentId: String? = null,
    /** „Besichtigung", „Angebot" … One line, optional. */
    val note: String = "",
    /** One of the business's contacts; null is „Keiner". */
    val contactId: String? = null,
    /**
     * The appointment has an event in the shared calendar, but not in this
     * device's copy yet. Saving leaves the calendar alone; the device holding
     * the event updates it after its next sync. The sheet says so.
     */
    val eventElsewhere: Boolean = false,
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
    /**
     * The busy events that may be linked. An event another appointment
     * already holds is shown as a conflict, but not offered for „Verknüpfen".
     */
    val adoptable: Set<Long> = emptySet(),
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
    /** The connection dialog is open. */
    val dialogOpen: Boolean = false,
    /** A connection attempt from that dialog is under way. */
    val connecting: Boolean = false,
    /** Rows still waiting to go up, and how many were waiting at the start. */
    val uploadRemaining: Int = 0,
    val uploadTotal: Int = 0,
    /** Why the last attempt failed. Null once one has succeeded. */
    val connectError: String? = null,
) {
    /** A server is configured and the last exchange with it went through. */
    val connected: Boolean get() = url.isNotBlank() && connectError == null

    /**
     * How far the upload has come, or null while there is nothing to count.
     *
     * Null is not zero: it means the app cannot know the total — it is only
     * fetching, and the server does not say in advance how much it holds. The
     * interface shows an indeterminate bar for that, rather than a determinate
     * one built on a number nobody has.
     */
    val uploadProgress: Float?
        get() = if (uploadTotal > 0) (uploadTotal - uploadRemaining).toFloat() / uploadTotal else null
}

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
            val result = withContext(Dispatchers.IO) {
                syncEngine.sync(SyncClient(url, token), ::reportUploadProgress)
            }
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
            _syncState.value = _syncState.value.copy(
                running = false, error = error, uploadRemaining = 0, uploadTotal = 0,
            )
            refreshSyncState()
            if (result is SyncResult.Ok) {
                refreshList()
                loadToday()
            }
        }
    }

    fun openServerDialog() {
        _syncState.value = _syncState.value.copy(dialogOpen = true, connectError = null)
    }

    fun closeServerDialog() {
        _syncState.value = _syncState.value.copy(dialogOpen = false, connecting = false)
    }

    /**
     * Stores the connection and tries it at once.
     *
     * Saving without trying was the old behaviour, and it left the screen
     * looking the same whether the details were right or a digit was wrong —
     * there was nothing to see either way. An attempt turns the two fields into
     * an answer: it went through, or here is why it did not.
     *
     * The details are kept even when the attempt fails. A wrong address should
     * not cost the user a sixty-character key typed on a phone.
     */
    fun connectServer(url: String, token: String) {
        val trimmed = url.trim().ifBlank { null }
        val complaint = when {
            trimmed == null -> "Ohne Adresse lässt sich nichts verbinden."
            !isAcceptableServerAddress(trimmed) ->
                "Nur eine Adresse, die mit https:// beginnt, wird angenommen — Android blockiert unverschlüsselte Verbindungen."
            token.isBlank() -> "Ohne Zugangsschlüssel weist der Server jede Anfrage ab."
            else -> null
        }
        if (complaint != null) {
            _syncState.value = _syncState.value.copy(connectError = complaint, connecting = false)
            return
        }

        viewModelScope.launch {
            _syncState.value = _syncState.value.copy(connecting = true, connectError = null)
            // A different server is a different watermark — otherwise the app
            // would believe it had already read a stock it has never seen — and
            // a different server has never seen this device's data either, so
            // everything must go up again.
            val addressChanged = trimmed!!.trimEnd('/') != preferences.serverUrl
            preferences.serverUrl = trimmed
            preferences.serverToken = token
            if (addressChanged) withContext(Dispatchers.IO) { syncEngine.resetForFullResync() }

            val result = withContext(Dispatchers.IO) {
                syncEngine.sync(SyncClient(preferences.serverUrl!!, token), ::reportUploadProgress)
            }
            val failure = (result as? SyncResult.Failed)?.message
            _syncState.value = _syncState.value.copy(
                connecting = false,
                uploadRemaining = 0,
                uploadTotal = 0,
                connectError = failure,
                // Only a success closes it. A failure keeps the fields on the
                // screen, next to the reason — that is where they get fixed.
                dialogOpen = failure != null,
                error = null,
            )
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

    /** Called from the sync thread after every round. */
    private fun reportUploadProgress(remaining: Int, total: Int) {
        _syncState.value = _syncState.value.copy(uploadRemaining = remaining, uploadTotal = total)
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

    /**
     * Runs a calendar lookup and hands a failure back as a value. A lookup that
     * failed — no permission, a provider that threw — is not an event that is
     * gone, and every caller has to be able to tell the two apart: taken for
     * "gone", it deletes an appointment on every device. Cancellation still
     * propagates.
     */
    private suspend fun <T> calendarLookup(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        Result.failure(failed)
    }

    /**
     * This appointment's event on this device, or null when it is not here —
     * see Appointment.locate. Throws when the calendar could not be asked; call
     * it through [calendarLookup].
     */
    private suspend fun locateEvent(entry: AppointmentEntry): Located<EventFields>? {
        val context = getApplication<Application>()
        return Appointment.locate(
            calendarEventId = entry.calendarEventId,
            eventUid = entry.eventUid,
            read = { CalendarStore.read(context, it) },
            find = { CalendarStore.findByUid(context, it) },
        )
    }

    /**
     * Looks for this appointment's event, if this device may read the calendar
     * at all. Null without the permission: nothing was asked, so there is
     * neither a result nor a failure to report — most people who never switched
     * the calendar on are in exactly that state, and must not be told on every
     * save that their calendar "could not be read".
     *
     * Null is therefore not "not found". No caller may act on it as a deletion;
     * the read-back does not even start without the permission.
     */
    private suspend fun lookUpEvent(entry: AppointmentEntry): Result<Located<EventFields>?>? {
        if (!CalendarStore.canRead(getApplication())) return null
        return calendarLookup { locateEvent(entry) }
    }

    /** The event as the app writes it for [entry]: title, time, place, and who to ask for. */
    private suspend fun eventFieldsFor(entry: AppointmentEntry, business: Business): EventFields? {
        val start = Clock.millis(entry.startsAt) ?: return null
        val end = Clock.millis(entry.endsAt) ?: (start + Appointment.DEFAULT_MINUTES * 60_000L)
        val contact = entry.contactId?.let { id -> repo.contacts(entry.placeId).firstOrNull { it.id == id } }
        return EventFields(
            title = Appointment.eventTitle(business.name, entry.note),
            startMillis = start,
            endMillis = end,
            location = entry.location,
            description = Appointment.eventDescription(contact, business.phone),
        )
    }

    /**
     * Creates the event under [uid] and reads it back to learn which UID it
     * kept. The returned fields carry that UID, or none when the provider
     * dropped it or the read failed — the link then stays local.
     */
    private suspend fun createEvent(uid: String, fields: EventFields): Pair<Long, EventFields>? {
        val context = getApplication<Application>()
        val calendar = preferences.calendarId ?: return null
        val eventId = CalendarStore.insert(context, calendar, fields.copy(uid = uid)) ?: return null
        val kept = Appointment.uidToTake(null, calendarLookup { CalendarStore.read(context, eventId) }.getOrNull()?.uid)
        return eventId to fields.copy(uid = kept)
    }

    /** Records locally which event this device links, and what that event now holds. */
    private suspend fun rememberSeen(appointmentId: String, eventId: Long, event: EventFields) {
        repo.setCalendarLink(
            appointmentId, eventId,
            Clock.format(event.startMillis), Clock.format(event.endMillis), event.location,
        )
    }

    /**
     * Opens the sheet. Without [appointmentId] a new appointment starts as
     * before: in two days, snapped to the quarter hour, the last duration used,
     * the business's address. With one, everything comes from that appointment.
     */
    fun openAppointment(placeId: String, appointmentId: String? = null) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val business = repo.business(placeId) ?: return@launch
            val existing = appointmentId?.let { repo.appointment(it) }
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            val start = existing?.startsAt ?: Appointment.snapToQuarter(FollowUp.inTwoDays())
            val minutes = if (existing != null) {
                Appointment.minutesBetween(existing.startsAt, existing.endsAt)
            } else {
                preferences.appointmentMinutes
            }
            val location = if (existing != null) {
                existing.location.orEmpty()
            } else {
                Appointment.address(business.street, business.postalCode, business.city).orEmpty()
            }
            val readable = CalendarStore.canRead(context)
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        appointmentId = existing?.id,
                        note = existing?.note.orEmpty(),
                        contactId = existing?.contactId,
                        // Only from a lookup that ran and went through: a calendar that
                        // was not or could not be asked says nothing about where the event is.
                        eventElsewhere = preferences.calendarEnabled && lookup?.isSuccess == true &&
                            existing?.eventUid != null && located == null,
                        calendarReadable = readable,
                    )
                )
            }
            Clock.millis(start)?.let {
                loadBusy(it, existing?.eventUid, located?.eventId ?: existing?.calendarEventId)
            }
        }
    }

    /**
     * Reads the busy times for the day containing [millis].
     *
     * The event of the appointment being edited drops out — by [ownUid], or by
     * [ownEventId] where it has no UID yet. Leaving it in would make every save
     * collide with itself. Everything else stays, the business's other
     * appointments included.
     */
    private fun loadBusy(millis: Long, ownUid: String?, ownEventId: Long?) {
        viewModelScope.launch {
            val dayStart = Clock.todayStart(millis)
            val busy = Appointment.busyExcept(BusyTimes.forDay(getApplication(), dayStart), ownUid, ownEventId)
            val taken = repo.takenEvents()
            // None at all for an appointment that already has an event — see Appointment.adoptable.
            val adoptable = Appointment.adoptable(busy, taken, ownUid, ownEventId)
            _state.update { state ->
                val draft = state.appointmentDraft ?: return@update state
                state.copy(appointmentDraft = draft.copy(busy = busy, adoptable = adoptable, conflict = emptyList()))
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
                val existing = draft.appointmentId?.let { repo.appointment(it) }
                loadBusy(start, existing?.eventUid, existing?.calendarEventId)
            }
        }
    }

    fun dismissAppointment() {
        _state.update { it.copy(appointmentDraft = null) }
    }

    /** Writes the appointment, its calendar event, and — for one still ahead — the status. */
    fun saveAppointment(linkExisting: Long? = null, force: Boolean = false) {
        val draft = _state.value.appointmentDraft ?: return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val startMillis = Clock.millis(draft.startIso) ?: return@launch
            val endIso = Appointment.endOf(draft.startIso, draft.minutes)
            val endMillis = Clock.millis(endIso) ?: return@launch
            val business = repo.business(draft.placeId) ?: return@launch
            val existing = draft.appointmentId?.let { repo.appointment(it) }
            if (draft.appointmentId != null && existing == null) {
                // Deleted while the sheet was open — by a sync, or in the
                // calendar. Saving would bring it back under a new id.
                _state.update {
                    it.copy(appointmentDraft = null, hint = "Der Termin wurde inzwischen gelöscht. Nichts gespeichert.")
                }
                loadDetail(draft.placeId)
                return@launch
            }
            val readable = CalendarStore.canRead(context)
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            // The calendar was asked where this appointment's event is, and the
            // question failed. Saving then leaves the calendar alone: an Update is
            // impossible without the event, and a Create could put a second one
            // beside it. The same holds without read permission, where nothing
            // could be asked at all.
            val calendarUnreadable = lookup?.isFailure == true
            val calendarUsable = preferences.calendarEnabled && readable && !calendarUnreadable

            val plan = Appointment.plan(
                startMillis = startMillis,
                endMillis = endMillis,
                busy = draft.busy,
                ownEventId = located?.eventId,
                // Only an event no other appointment holds, whatever reached this call.
                linkExisting = linkExisting?.takeIf { it in draft.adoptable },
                force = force,
                calendarEnabled = calendarUsable,
                eventUid = existing?.eventUid,
            )

            if (plan is SavePlan.Conflict) {
                _state.update { it.copy(appointmentDraft = draft.copy(conflict = plan.with)) }
                return@launch
            }

            var entry = AppointmentEntry(
                id = existing?.id ?: UUID.randomUUID().toString(),
                placeId = draft.placeId,
                startsAt = draft.startIso,
                endsAt = endIso,
                location = draft.location.trim().ifEmpty { null },
                note = draft.note.trim().ifEmpty { null },
                contactId = draft.contactId,
                eventUid = existing?.eventUid,
            )
            val fields = eventFieldsFor(entry, business) ?: return@launch
            // The linked event and what it holds once this is through; null when none is linked.
            var linked: Pair<Long, EventFields>? = null
            // This device's link is stale and goes — see the failed update below.
            var dropLink = false
            // Said only to someone who switched the calendar on: to everyone else
            // an untouched calendar is exactly what they chose.
            var calendarHint: String? = when {
                !preferences.calendarEnabled -> null
                !readable -> "Termin gespeichert. Ohne Zugriff auf den Kalender bleibt der Eintrag " +
                    "unberührt — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                calendarUnreadable -> "Termin gespeichert. Der Kalender ließ sich gerade nicht lesen; " +
                    "der Eintrag wird beim nächsten Öffnen abgeglichen."
                else -> null
            }

            when (plan) {
                // Adopting takes the calendar's time, place and UID, and leaves
                // the event exactly as it is. Gone, or unreadable, between listing
                // the day and pressing save: keep the draft and no link.
                is SavePlan.Adopt -> {
                    calendarLookup { CalendarStore.read(context, plan.eventId) }.getOrNull()?.let { event ->
                        entry = entry.copy(
                            startsAt = Clock.format(event.startMillis),
                            endsAt = Clock.format(event.endMillis),
                            location = event.location,
                            eventUid = Appointment.uidToTake(null, event.uid),
                        )
                        linked = plan.eventId to event
                    }
                    if (linked == null) {
                        calendarHint = "Termin gespeichert, aber nicht verknüpft: " +
                            "der Kalendereintrag war nicht mehr zu lesen."
                    }
                }

                is SavePlan.Update -> if (CalendarStore.update(context, plan.eventId, fields)) {
                    entry = entry.copy(eventUid = Appointment.uidToTake(entry.eventUid, located?.event?.uid) ?: entry.eventUid)
                    linked = plan.eventId to fields
                } else {
                    val afterFailure = calendarLookup { CalendarStore.read(context, plan.eventId) }
                    if (afterFailure.isSuccess && afterFailure.getOrNull() == null) {
                        // Deleted between opening the sheet and saving. A new event
                        // is what the user asked for — under a UID of its own: the
                        // old one may still be on its way out of the shared calendar
                        // as <uid>.ics, and a second resource with that UID would
                        // collide with it.
                        // Should the provider drop the fresh UID, the row keeps the old one
                        // (saving never clears a UID): the other devices then look for the
                        // deleted event, which is what a deletion in the calendar means to
                        // them anyway, and this device keeps its local link.
                        linked = createEvent(UUID.randomUUID().toString(), fields)
                            ?.also { (_, holds) -> entry = entry.copy(eventUid = holds.uid ?: entry.eventUid) }
                        if (linked == null) {
                            dropLink = true
                            calendarHint = "Termin gespeichert. Der Kalendereintrag war gelöscht und ließ " +
                                "sich nicht neu anlegen — prüfe den gewählten Kalender in den Einstellungen."
                        }
                    } else {
                        // Still there but not writable, or not readable: the event
                        // keeps its old time for now. This device's link and what it
                        // saw go, so the next opening does not take a stale S for
                        // proof of a deletion. With a UID it finds the event again
                        // and, seeing it for the first time, lets the row win.
                        dropLink = true
                        calendarHint = "Termin gespeichert. Der Kalendereintrag ließ sich nicht ändern; " +
                            "er wird beim nächsten Öffnen abgeglichen."
                    }
                }

                SavePlan.Create -> {
                    linked = createEvent(entry.id, fields)?.also { (_, holds) -> entry = entry.copy(eventUid = holds.uid) }
                    if (linked == null) {
                        calendarHint = "Termin gespeichert. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    }
                }

                SavePlan.LocalOnly -> Unit
                is SavePlan.Conflict -> Unit // already returned above
            }

            preferences.appointmentMinutes = draft.minutes
            repo.saveAppointment(entry)
            when {
                linked != null -> linked?.let { (eventId, holds) -> rememberSeen(entry.id, eventId, holds) }
                dropLink -> repo.setCalendarLink(entry.id, null, null, null, null)
            }
            Appointment.statusAfterSave(entry.startsAt, entry.endsAt, System.currentTimeMillis())
                ?.let { repo.setStatus(draft.placeId, it) }
            _state.update { it.copy(appointmentDraft = null, hint = calendarHint) }
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

            // A calendar that could not be read is not a deleted event: skip.
            val event = try {
                CalendarStore.read(getApplication(), eventId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                return@launch
            }
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
