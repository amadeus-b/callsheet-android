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
import io.github.amadeusb.callsheet.calling.Agenda
import io.github.amadeusb.callsheet.calling.AgendaSection
import io.github.amadeusb.callsheet.calling.Appointment
import io.github.amadeusb.callsheet.calling.BusyInterval
import io.github.amadeusb.callsheet.calling.CallFlow
import io.github.amadeusb.callsheet.calling.Located
import io.github.amadeusb.callsheet.calling.Reconcile
import io.github.amadeusb.callsheet.calling.SavePlan
import io.github.amadeusb.callsheet.calling.Slot
import io.github.amadeusb.callsheet.calling.CallLogReader
import io.github.amadeusb.callsheet.calling.FollowUp
import io.github.amadeusb.callsheet.data.AddressDraft
import io.github.amadeusb.callsheet.data.Addresses
import io.github.amadeusb.callsheet.data.BusinessAddress
import io.github.amadeusb.callsheet.data.AppointmentEntry
import io.github.amadeusb.callsheet.data.AppointmentKind
import io.github.amadeusb.callsheet.data.CallEntry
import io.github.amadeusb.callsheet.data.Contact
import io.github.amadeusb.callsheet.data.ContactDraft
import io.github.amadeusb.callsheet.data.Business
import io.github.amadeusb.callsheet.data.EmailDraft
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
import io.github.amadeusb.callsheet.sync.AppliedAppointments
import io.github.amadeusb.callsheet.sync.FailureKind
import io.github.amadeusb.callsheet.sync.isAcceptableServerAddress
import io.github.amadeusb.callsheet.sync.MailClient
import io.github.amadeusb.callsheet.sync.MailResult
import io.github.amadeusb.callsheet.sync.SyncClient
import io.github.amadeusb.callsheet.sync.SyncEngine
import io.github.amadeusb.callsheet.sync.SyncGate
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
    /** The agenda behind the calendar button. */
    data object Agenda : Screen
    data object Settings : Screen
    data object BusinessForm : Screen

    /** The form for a contact; a null [id] means a new one. */
    data class ContactForm(val placeId: String, val id: String?) : Screen

    /** The addresses of a business, for editing. */
    data class AddressForm(val placeId: String) : Screen
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
    /** Visit or callback. Chosen by where the sheet was opened; an existing appointment keeps its own. */
    val kind: AppointmentKind = AppointmentKind.VISIT,
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
    val detailAppointments: List<AppointmentEntry> = emptyList(),
    /** The business's addresses, main address first. */
    val detailAddresses: List<BusinessAddress> = emptyList(),
    /** When a dial attempt is up for choosing, the numbers hang here. */
    val numberPicker: NumberPicker? = null,
    val contactDraft: ContactDraft = ContactDraft(),
    val contactError: String? = null,
    /** The address screen's rows while it is open. */
    val addressDrafts: List<AddressDraft> = emptyList(),
    /** The log entry of the call just made, still missing its outcome. */
    val pendingCall: String? = null,
    val savesOutcome: Boolean = false,
    val noteFocus: Boolean = false,
    val statusSuggestion: Status? = null,
    val followUpSuggestion: String? = null,
    val hint: String? = null,
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
    /** What the agenda shows, grouped. */
    val agenda: List<AgendaSection<Pair<AppointmentEntry, Business>>> = emptyList(),
    val outsideBusinessHours: Boolean = false,
    val draft: BusinessDraft = BusinessDraft(),
    val formError: String? = null,
    val saving: Boolean = false,
    /** The "Mail gesendet" dialog: open, mid-send, or showing the last failure. */
    val mailDialogOpen: Boolean = false,
    val mailSending: Boolean = false,
    val mailError: String? = null,
    val mailTemplateSubject: String = "",
    val mailTemplateBody: String = "",
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
    private val syncGate = SyncGate<SyncResult?>()

    /** Businesses whose read-back is under way. Touched only on the main thread. */
    private val readingBack = HashSet<String>()

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
            mailTemplateSubject = preferences.mailTemplateSubject,
            mailTemplateBody = preferences.mailTemplateBody,
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
     *
     * Never dropped: requested while a sync runs, it waits and gets a run of its
     * own (see SyncGate).
     */
    fun syncNow(quiet: Boolean = true) {
        viewModelScope.launch { syncAndWait(quiet) }
    }

    /** [syncNow], waiting for the run that covers this request. Null without a server. */
    private suspend fun syncAndWait(quiet: Boolean = true): SyncResult? = syncGate.run {
        val url = preferences.serverUrl ?: return@run null
        val token = preferences.serverToken ?: return@run null

        _syncState.value = _syncState.value.copy(running = true)
        // Filled on the sync thread, read here after it returns.
        val applied = java.util.Collections.synchronizedList(ArrayList<AppliedAppointments>())
        val result = withContext(Dispatchers.IO) {
            syncEngine.sync(SyncClient(url, token), ::reportUploadProgress) { applied.add(it) }
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
        // Whatever came down is real, even when the run did not finish.
        followCalendar(applied.toList())
        if (result is SyncResult.Ok) {
            refreshList()
            loadAgenda()
            // The UIDs taken here go up with the next run, started at once.
            if (captureMissingUids() > 0) syncNow()
        }
        result
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

            val applied = java.util.Collections.synchronizedList(ArrayList<AppliedAppointments>())
            val result = syncGate.run {
                withContext(Dispatchers.IO) {
                    syncEngine.sync(SyncClient(preferences.serverUrl!!, token), ::reportUploadProgress) { applied.add(it) }
                }
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
            followCalendar(applied.toList())
            if (result is SyncResult.Ok) {
                refreshList()
                loadAgenda()
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
            is Screen.Agenda -> loadAgenda()
            is Screen.Settings -> loadBlocked()
            is Screen.BusinessForm -> Unit
            is Screen.ContactForm -> Unit
            is Screen.AddressForm -> Unit
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
            detailAppointments = if (switching) emptyList() else _state.value.detailAppointments,
            detailAddresses = if (switching) emptyList() else _state.value.detailAddresses,
            statusSuggestion = if (switching) null else _state.value.statusSuggestion,
            followUpSuggestion = if (switching) null else _state.value.followUpSuggestion,
            hint = if (switching) null else _state.value.hint,
            noteFocus = if (switching) false else _state.value.noteFocus,
            pendingCall = if (switching) null else _state.value.pendingCall,
        )
        loadDetail(placeId)
        // No loop: reconcileAppointments only ever calls loadDetail, never back here.
        reconcileAppointments(placeId)
    }

    fun showAgenda() {
        _state.update { it.copy(screen = Screen.Agenda, history = historyFor(Screen.Agenda)) }
        loadAgenda()
    }

    private fun loadAgenda() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val listed = repo.agenda(Clock.todayStart(now))
            _state.update { it.copy(agenda = Agenda.sections(listed, { pair -> pair.first }, now)) }
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
                emails = existing.emails
                    .map { EmailDraft(id = it.id, email = it.email) }
                    .ifEmpty { listOf(EmailDraft()) },
                addressId = existing.addressId,
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
                    // The whole business follows: the person's own entry, and
                    // the company entry that makes way for them. What was just
                    // saved here is not read back over.
                    repo.business(draft.placeId)?.let { store.persistBusiness(it, savedInApp = id) }
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
            // With the last person gone, the company entry comes back.
            repo.business(placeId)?.let { store.persistBusiness(it) }
            if (_state.value.screen is Screen.ContactForm) back()
            loadDetail(placeId)
        }
    }

    // --- Addresses ----------------------------------------------------------

    /** Opens a business's addresses for editing; with none yet, one empty row. */
    fun showAddresses(placeId: String) {
        viewModelScope.launch {
            val drafts = Addresses.drafts(repo.addresses(placeId)).ifEmpty { listOf(AddressDraft()) }
            val screen = Screen.AddressForm(placeId)
            val history = historyFor(screen)
            _state.update { it.copy(screen = screen, history = history, addressDrafts = drafts) }
        }
    }

    fun updateAddressDrafts(drafts: List<AddressDraft>) {
        _state.update { it.copy(addressDrafts = drafts) }
    }

    /** Saves the addresses and returns to the record. */
    fun saveAddresses(placeId: String) {
        if (_state.value.saving) return
        val drafts = _state.value.addressDrafts
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            repo.saveAddresses(placeId, drafts)
            _state.update { it.copy(saving = false, allCities = repo.cities()) }
            // Every entry of the business carries addresses, and a removed one
            // may have taken a person's assignment with it.
            repo.business(placeId)?.let { store.persistBusiness(it) }
            back()
            loadDetail(placeId)
        }
    }

    // --- Mail ---------------------------------------------------------------

    fun setMailTemplateSubject(value: String) {
        preferences.mailTemplateSubject = value
        _state.update { it.copy(mailTemplateSubject = value) }
    }

    fun setMailTemplateBody(value: String) {
        preferences.mailTemplateBody = value
        _state.update { it.copy(mailTemplateBody = value) }
    }

    fun openMailDialog() {
        _state.update { it.copy(mailDialogOpen = true, mailError = null) }
    }

    fun closeMailDialog() {
        if (_state.value.mailSending) return
        _state.update { it.copy(mailDialogOpen = false, mailError = null) }
    }

    /**
     * Sends the mail through the server and, only on success, sets the
     * business's status to [Status.MAIL_SENT] and closes the dialog. A
     * failure leaves the dialog open with the reason shown inside it — the
     * recipients and the text stay exactly as typed, for another attempt.
     */
    fun sendMail(placeId: String, to: List<String>, subject: String, text: String) {
        if (_state.value.mailSending) return
        val url = preferences.serverUrl
        val token = preferences.serverToken
        if (url == null || token == null) {
            _state.update { it.copy(mailError = "Kein Server verbunden — in den Einstellungen einrichten.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(mailSending = true, mailError = null) }
            val result = withContext(Dispatchers.IO) {
                MailClient(url, token).send(to, subject, text)
            }
            when (result) {
                is MailResult.Ok -> {
                    repo.setStatus(placeId, Status.MAIL_SENT)
                    _state.update {
                        it.copy(mailSending = false, mailDialogOpen = false, mailError = null)
                    }
                    loadDetail(placeId)
                }
                is MailResult.Failed -> {
                    _state.update { it.copy(mailSending = false, mailError = result.message) }
                }
            }
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
        val end = Clock.millis(entry.endsAt) ?: (start + Appointment.defaultMinutes(entry.kind) * 60_000L)
        val contact = entry.contactId?.let { id -> repo.contacts(entry.placeId).firstOrNull { it.id == id } }
        return EventFields(
            title = Appointment.eventTitle(entry.kind, business.name, entry.note, done = entry.doneAt != null),
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
     * Writes the title [entry] calls for into its event, and nothing else: the
     * event's own time, place and description go back as the calendar holds
     * them. Moving an event is the read-back's decision, not this one's.
     *
     * Only where the calendar is switched on, readable and writable. A calendar
     * that could not be asked, or an event not on this device, is left alone:
     * the device holding the event writes it after its next sync.
     */
    private suspend fun syncEventTitle(entry: AppointmentEntry, business: Business) {
        val context = getApplication<Application>()
        if (!preferences.calendarEnabled || !CalendarStore.canRead(context) || !CalendarStore.canWrite(context)) return
        if (entry.eventUid == null && entry.calendarEventId == null) return
        val located = calendarLookup { locateEvent(entry) }.getOrNull() ?: return
        val title = Appointment.eventTitle(entry.kind, business.name, entry.note, done = entry.doneAt != null)
        if (located.event.title != title) {
            CalendarStore.update(context, located.eventId, located.event.copy(title = title))
        }
    }

    /**
     * Completes the business's open callbacks due by the end of today and puts
     * the tick on their events. Called where a call from the app is logged —
     * whatever its duration: an unanswered call is a callback made too. Returns
     * how many were completed.
     */
    private suspend fun completeDueCallbacks(placeId: String): Int {
        val now = System.currentTimeMillis()
        val completed = repo.completeCallbacks(placeId, Clock.nextDayStart(now), Clock.format(now))
        if (completed.isEmpty()) return 0
        val business = repo.business(placeId) ?: return completed.size
        for (id in completed) repo.appointment(id)?.let { syncEventTitle(it, business) }
        return completed.size
    }

    /**
     * Opens the sheet for a visit. Without [appointmentId] a new visit starts as
     * before: in two days, snapped to the quarter hour, the last duration used,
     * the business's address. With one, everything comes from that appointment
     * — its kind too, so „Ändern" on a callback opens a callback.
     */
    fun openAppointment(placeId: String, appointmentId: String? = null) =
        openSheet(placeId, appointmentId, AppointmentKind.VISIT, startIso = null)

    /**
     * Opens the sheet for a new callback at [startIso] — from a quick choice,
     * the date picker or the suggestion after a call. Taken as given: the
     * detail view snaps what it computes to the quarter hour and leaves the
     * picker's time alone. Nothing is saved until „Rückruf speichern".
     */
    fun openCallback(placeId: String, startIso: String) =
        openSheet(placeId, null, AppointmentKind.CALLBACK, startIso)

    private fun openSheet(placeId: String, appointmentId: String?, kind: AppointmentKind, startIso: String?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val business = repo.business(placeId) ?: return@launch
            val existing = appointmentId?.let { repo.appointment(it) }
            val sheetKind = existing?.kind ?: kind
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            val start = existing?.startsAt ?: startIso ?: Appointment.snapToQuarter(FollowUp.inTwoDays())
            val minutes = when {
                existing != null -> Appointment.minutesBetween(existing.startsAt, existing.endsAt, Appointment.defaultMinutes(sheetKind))
                sheetKind == AppointmentKind.CALLBACK -> Appointment.CALLBACK_MINUTES
                else -> preferences.appointmentMinutes
            }
            val location = when {
                existing != null -> existing.location.orEmpty()
                // A phone call has no place.
                sheetKind == AppointmentKind.CALLBACK -> ""
                else -> Appointment.address(business.street, business.postalCode, business.city).orEmpty()
            }
            val readable = CalendarStore.canRead(context)
            _state.update {
                it.copy(
                    appointmentDraft = AppointmentDraft(
                        placeId = placeId,
                        startIso = start,
                        minutes = minutes,
                        location = location,
                        kind = sheetKind,
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
            val kind = existing?.kind ?: draft.kind
            val saved = if (kind == AppointmentKind.CALLBACK) "Rückruf gespeichert" else "Termin gespeichert"
            val readable = CalendarStore.canRead(context)
            val lookup = existing?.let { lookUpEvent(it) }
            val located = lookup?.getOrNull()
            // The calendar was asked where this appointment's event is, and the
            // question failed. Saving then leaves the calendar alone: an Update is
            // impossible without the event, and a Create could put a second one
            // beside it. The same holds without read permission, where nothing
            // could be asked at all.
            val calendarUnreadable = lookup?.isFailure == true
            val writable = CalendarStore.canWrite(context)
            val calendarUsable = preferences.calendarEnabled && readable && writable && !calendarUnreadable

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
                location = if (kind == AppointmentKind.CALLBACK) null else draft.location.trim().ifEmpty { null },
                note = draft.note.trim().ifEmpty { null },
                contactId = draft.contactId,
                eventUid = existing?.eventUid,
                kind = kind,
                // Saving never completes or reopens; the repository never clears it either.
                doneAt = existing?.doneAt,
            )
            val fields = eventFieldsFor(entry, business) ?: return@launch
            // The linked event and what it holds once this is through; null when none is linked.
            var linked: Pair<Long, EventFields>? = null
            // This device's link is stale and goes — see the failed update below.
            var dropLink = false
            // The event is still there but did not take the change: keep the shortcut, and as S
            // what the event holds now (start, end, location), so the row wins next time.
            var keepShortcut: Long? = null
            var keepSeen: Triple<String?, String?, String?>? = null
            // Said only to someone who switched the calendar on: to everyone else
            // an untouched calendar is exactly what they chose.
            var calendarHint: String? = when {
                !preferences.calendarEnabled -> null
                !readable || !writable -> "$saved. Ohne Zugriff auf den Kalender bleibt der Eintrag " +
                    "unberührt — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                calendarUnreadable -> "$saved. Der Kalender ließ sich gerade nicht lesen; " +
                    "der Eintrag wird beim nächsten Öffnen abgeglichen."
                else -> null
            }

            when (plan) {
                // Adopting takes the calendar's time, place and UID, and leaves
                // the event exactly as it is — a callback's place excepted, the
                // repository stores it without one. Gone, or unreadable, between
                // listing the day and pressing save: keep the draft and no link.
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
                        calendarHint = "$saved, aber nicht verknüpft: " +
                            "der Kalendereintrag war nicht mehr zu lesen."
                    }
                }

                is SavePlan.Update -> if (CalendarStore.update(context, plan.eventId, fields)) {
                    entry = entry.copy(
                        eventUid = entry.eventUid ?: Appointment.uidToTake(null, located?.event?.uid)
                            ?.takeUnless { repo.heldByOther(entry.id, it, null) }
                    )
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
                            calendarHint = "$saved. Der Kalendereintrag war gelöscht und ließ " +
                                "sich nicht neu anlegen — prüfe den gewählten Kalender in den Einstellungen."
                        }
                    } else {
                        // Still there but not writable, or not readable: the event
                        // keeps its old time for now. This device keeps its shortcut
                        // and records as seen what the event holds now — or, where it
                        // could not be read, what it saw before. The next opening then
                        // finds the event unchanged and the row changed, and the row
                        // wins. Forgetting what it saw would make that a first sight,
                        // and the calendar would undo the edit.
                        keepShortcut = plan.eventId
                        val current = afterFailure.getOrNull()
                        keepSeen = if (current != null) {
                            Triple(Clock.format(current.startMillis), Clock.format(current.endMillis), current.location)
                        } else {
                            Triple(existing?.seenStartsAt, existing?.seenEndsAt, existing?.seenLocation)
                        }
                        // Taken now if the event had a UID the row lacks — without
                        // one, a lost shortcut could never be found again. Never one
                        // another appointment already holds.
                        current?.uid?.let { found ->
                            Appointment.uidToTake(entry.eventUid, found)
                                ?.takeUnless { repo.heldByOther(entry.id, it, null) }
                                ?.let { entry = entry.copy(eventUid = it) }
                        }
                        calendarHint = "$saved. Der Kalendereintrag ließ sich nicht ändern; " +
                            "er wird beim nächsten Öffnen abgeglichen."
                    }
                }

                SavePlan.Create -> {
                    linked = createEvent(entry.id, fields)?.also { (_, holds) -> entry = entry.copy(eventUid = holds.uid) }
                    if (linked == null) {
                        calendarHint = "$saved. Der Kalendereintrag konnte nicht " +
                            "geschrieben werden — prüfe die Berechtigung und den " +
                            "gewählten Kalender in den Einstellungen."
                    }
                }

                SavePlan.LocalOnly -> Unit
                is SavePlan.Conflict -> Unit // already returned above
            }

            // The length a visit starts at follows the last visit; a callback's is fixed.
            if (kind == AppointmentKind.VISIT) preferences.appointmentMinutes = draft.minutes
            repo.saveAppointment(entry)
            when {
                linked != null -> linked?.let { (eventId, holds) -> rememberSeen(entry.id, eventId, holds) }
                dropLink -> repo.setCalendarLink(entry.id, null, null, null, null)
                else -> keepShortcut?.let { repo.setCalendarLink(entry.id, it, keepSeen?.first, keepSeen?.second, keepSeen?.third) }
            }
            Appointment.statusAfterSave(entry.kind, entry.startsAt, entry.endsAt, System.currentTimeMillis())
                ?.let { repo.setStatus(draft.placeId, it) }
            _state.update {
                it.copy(
                    appointmentDraft = null,
                    hint = calendarHint,
                    // The suggestion after a call is answered once a callback is saved.
                    followUpSuggestion = if (kind == AppointmentKind.CALLBACK) null else it.followUpSuggestion,
                )
            }
            loadDetail(draft.placeId)
            // Up at once: until the row reaches the server, a device that gets the
            // moved event through DAVx5 first would take the calendar's time onto
            // its older row — and that row, stamped newer, would win.
            syncNow()
        }
    }

    /**
     * Brings one appointment and its event back in line — the table in
     * Appointment.reconcile — and returns what it found, or null when nothing
     * was compared.
     *
     * A calendar that could not be asked is one of those nulls. It is skipped,
     * never passed on as "not found": seen before and ahead, that would read as
     * deleted in the calendar and delete the appointment on every device.
     *
     * With [rowWinsOnly], only an event update is carried out, or an event in
     * step recorded as seen: after a sync nobody is looking, so whatever would
     * change a row or delete an appointment waits for the next opening, where a
     * hint can say so.
     */
    private suspend fun reconcile(
        entry: AppointmentEntry,
        business: Business,
        nowMillis: Long,
        rowWinsOnly: Boolean,
    ): Reconcile? {
        val context = getApplication<Application>()
        val row = Appointment.rowSlot(entry) ?: return null
        // Without read permission nothing can be compared, and nothing may be
        // concluded — the callers check too, this makes it hold for any caller.
        if (!CalendarStore.canRead(context)) return null
        val located = calendarLookup { locateEvent(entry) }.getOrElse { return null }
        val event = located?.event
        // The shortcut's event carries another UID than the row, and the row's
        // UID names a different event on this device: that one is the
        // appointment's. The copy behind the shortcut is a duplicate — written
        // by this device before the row learnt its UID — and goes. Only when no
        // other appointment points at that copy — then it is not a copy but that
        // appointment's event.
        if (!rowWinsOnly && located != null && entry.eventUid != null && event?.uid != entry.eventUid) {
            val rowUid = entry.eventUid
            val found = calendarLookup { CalendarStore.findByUid(context, rowUid) }.getOrElse { return null }
            val target = Appointment.relinkTo(rowUid, event?.uid, located.eventId, found)
            if (target != null) {
                // Linked with what the target holds as seen, so a row that differs
                // wins on the next opening instead of meeting the event for the
                // first time. Unreadable or gone: nothing is relinked or deleted.
                val targetEvent = calendarLookup { CalendarStore.read(context, target) }.getOrNull() ?: return null
                if (preferences.calendarEnabled && CalendarStore.canWrite(context) &&
                    !repo.heldByOther(entry.id, event?.uid, located.eventId)
                ) {
                    CalendarStore.delete(context, located.eventId)
                }
                rememberSeen(entry.id, target, targetEvent)
                return null
            }
        }
        val outcome = Appointment.reconcile(
            row = row,
            seen = Appointment.seenSlot(entry),
            event = event?.let { Slot(it.startMillis, it.endMillis, it.location) },
            nowMillis = nowMillis,
        )
        // After a sync nothing may change a row; recording what an event in step
        // holds is local and may.
        if (rowWinsOnly && outcome != Reconcile.UpdateEvent && outcome != Reconcile.InStep) return null

        // A row without a UID takes the one its event carries — a carried-over
        // appointment, or an event DAVx5 has uploaded since. A row that has one
        // keeps it (see Appointment.uidToTake). Never one another appointment holds.
        val uid = if (rowWinsOnly || event == null) {
            null
        } else {
            Appointment.uidToTake(entry.eventUid, event.uid)?.takeUnless { repo.heldByOther(entry.id, it, null) }
        }
        if (uid != null) repo.setEventUid(entry.id, uid)

        when (outcome) {
            Reconcile.InStep -> {
                val holds = Slot(event!!.startMillis, event.endMillis, event.location)
                // Only when something is new — a link rewritten on every opening
                // would notify every observer of the database for nothing.
                if (!Appointment.seenIsCurrent(entry, located!!.eventId, holds)) {
                    rememberSeen(entry.id, located.eventId, event)
                }
            }

            is Reconcile.TakeEvent -> {
                // Only time and place come from the calendar (a callback's place
                // stays empty, see Repository.saveAppointment), written onto the
                // row as it stands now — not onto the entry read before the
                // lookup, or a note or contact saved in between would be undone.
                val fresh = repo.appointment(entry.id) ?: return null
                // Changed since the comparison — a pull brought a newer version,
                // say: the next opening decides again.
                if (fresh.startsAt != entry.startsAt || fresh.endsAt != entry.endsAt || fresh.location != entry.location) {
                    return null
                }
                repo.saveAppointment(
                    fresh.copy(
                        startsAt = Clock.format(outcome.slot.startMillis),
                        endsAt = outcome.slot.endMillis?.let { Clock.format(it) },
                        location = outcome.slot.location,
                        eventUid = fresh.eventUid ?: uid,
                    )
                )
                rememberSeen(entry.id, located!!.eventId, event!!)
            }

            Reconcile.UpdateEvent -> {
                // Reading is allowed with the calendar switched off in the
                // settings; writing is not — the same condition followCalendar
                // checks before it writes anything.
                if (!preferences.calendarEnabled || !CalendarStore.canWrite(context)) return outcome
                val fields = eventFieldsFor(entry, business) ?: return outcome
                if (CalendarStore.update(context, located!!.eventId, fields)) {
                    rememberSeen(entry.id, located.eventId, fields)
                }
            }

            Reconcile.NotYetHere -> Unit
            Reconcile.DeletedInCalendar -> repo.deleteAppointment(entry.id)
            Reconcile.Unlink -> repo.setCalendarLink(entry.id, null, null, null, null)
        }
        return outcome
    }

    /**
     * Reads every linked appointment of a business back from the calendar, on
     * opening it. A deletion in the calendar is the one case that speaks up,
     * because it is the one that may take the status back.
     */
    private fun reconcileAppointments(placeId: String) {
        viewModelScope.launch {
            if (!CalendarStore.canRead(getApplication())) return@launch
            // Nothing linked, nothing to read back — and no sync for it.
            if (repo.appointments(placeId).none { it.eventUid != null || it.calendarEventId != null }) return@launch
            // One read-back per business at a time: opened again while one waits
            // for its sync, the second would only compare the same rows twice.
            if (!readingBack.add(placeId)) return@launch
            try {
                // Nothing is taken from the calendar onto a row before this device
                // knows the server's version of it — an older row stamped newer
                // would win over a change made elsewhere. No sync, no read-back:
                // the next opening tries again.
                if (preferences.serverUrl != null && preferences.serverToken != null) {
                    if (syncAndWait() !is SyncResult.Ok) return@launch
                }
                val business = repo.business(placeId) ?: return@launch
                val now = System.currentTimeMillis()
                // Read again after the sync: it may have brought newer rows.
                val results = repo.appointments(placeId)
                    .filter { it.eventUid != null || it.calendarEventId != null }
                    .mapNotNull { entry -> reconcile(entry, business, now, rowWinsOnly = false)?.let { entry to it } }
                if (results.isEmpty()) return@launch

                // The sync may have taken long: the user may have moved on. The
                // status is data and falls back regardless; hint and reload only
                // for the business still on screen.
                val stillShown = { (_state.value.screen as? Screen.Detail)?.placeId == placeId }
                val deleted = results.filter { it.second == Reconcile.DeletedInCalendar }.map { it.first }
                if (deleted.isNotEmpty()) {
                    // Only a deleted visit can take the status back.
                    val fallback = if (deleted.any { it.kind == AppointmentKind.VISIT }) {
                        Appointment.statusAfterRemoval(business.status, repo.appointments(placeId), now)
                    } else {
                        null
                    }
                    fallback?.let { repo.setStatus(placeId, it) }
                    val what = if (deleted.all { it.kind == AppointmentKind.CALLBACK }) "Der Rückruf" else "Der Termin"
                    if (stillShown()) {
                        _state.update {
                            it.copy(
                                hint = if (fallback != null) {
                                    "$what wurde im Kalender gelöscht. Status zurück auf „Angerufen“."
                                } else {
                                    "$what wurde im Kalender gelöscht. Der Status bleibt, wie er ist."
                                }
                            )
                        }
                    }
                }
                if (stillShown()) loadDetail(placeId)
            } finally {
                readingBack.remove(placeId)
            }
        }
    }

    /**
     * Brings the calendar along after a sync. An appointment changed on another
     * device moves its event here; a deleted one takes its event with it — with
     * a shared calendar the removing device has usually done that already, and
     * deleting an event that is gone changes nothing.
     *
     * Only where the app may write the calendar at all.
     */
    private fun followCalendar(applied: List<AppliedAppointments>) {
        if (applied.isEmpty()) return
        val context = getApplication<Application>()
        if (!preferences.calendarEnabled || !CalendarStore.canRead(context) || !CalendarStore.canWrite(context)) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            for (batch in applied) {
                for (removed in batch.removed) {
                    // A calendar that could not be asked keeps the event; the
                    // appointment is gone regardless, and nothing else is at stake.
                    calendarLookup {
                        Appointment.locate(
                            calendarEventId = removed.calendarEventId,
                            eventUid = removed.eventUid,
                            read = { CalendarStore.read(context, it) },
                            find = { CalendarStore.findByUid(context, it) },
                        )
                    }.getOrNull()?.let { CalendarStore.delete(context, it.eventId) }
                }
                for (id in batch.written.distinct()) {
                    val entry = repo.appointment(id) ?: continue
                    if (entry.eventUid == null && entry.calendarEventId == null) continue
                    val business = repo.business(entry.placeId) ?: continue
                    reconcile(entry, business, now, rowWinsOnly = true)
                    // The read-back compares time and place only, so a callback
                    // completed elsewhere would never get its tick here. With a
                    // shared calendar the completing device has usually written it
                    // already, the titles match, and nothing is written.
                    if (entry.kind == AppointmentKind.CALLBACK) {
                        repo.appointment(id)?.let { syncEventTitle(it, business) }
                    }
                }
            }
            (_state.value.screen as? Screen.Detail)?.let { loadDetail(it.placeId) }
        }
    }

    /**
     * Takes the UID from the event of every appointment linked on this device
     * without one — above all the ones carried over from 1.3.x. Until a row has
     * its UID, another device cannot find the event and would create a second
     * one when the appointment is changed there.
     *
     * Runs after a sync, never before: taking a UID stamps the row as changed,
     * and a row older than the server's would then win over it.
     *
     * Returns how many rows took a UID. A calendar that cannot be read skips the row.
     */
    private suspend fun captureMissingUids(): Int {
        val context = getApplication<Application>()
        if (!CalendarStore.canRead(context)) return 0
        var taken = 0
        for (entry in repo.linkedWithoutUid()) {
            val eventId = entry.calendarEventId ?: continue
            val uid = calendarLookup { CalendarStore.read(context, eventId) }.getOrNull()?.uid
            Appointment.uidToTake(null, uid)?.takeUnless { repo.heldByOther(entry.id, it, null) }?.let {
                repo.setEventUid(entry.id, it)
                taken++
            }
        }
        return taken
    }

    /**
     * Removes an appointment and its calendar event — a past visit or a
     * completed callback too; the detail view asks first. Where the event is not
     * on this device, the row goes alone and the device holding the event
     * deletes it after its next sync. Only a visit can take the status back.
     */
    fun removeAppointment(appointmentId: String) {
        viewModelScope.launch {
            val entry = repo.appointment(appointmentId) ?: return@launch
            val business = repo.business(entry.placeId) ?: return@launch
            val lookup = lookUpEvent(entry)
            val deleted = lookup?.getOrNull()?.let { CalendarStore.delete(getApplication(), it.eventId) }
            repo.deleteAppointment(entry.id)
            if (entry.kind == AppointmentKind.VISIT) {
                Appointment.statusAfterRemoval(business.status, repo.appointments(entry.placeId), System.currentTimeMillis())
                    ?.let { repo.setStatus(entry.placeId, it) }
            }
            val removed = if (entry.kind == AppointmentKind.CALLBACK) "Rückruf entfernt" else "Termin entfernt"
            val linked = entry.eventUid != null || entry.calendarEventId != null
            val hint = when {
                !preferences.calendarEnabled || !linked -> null
                // Spec: without read permission nothing is asked — but with the
                // calendar switched on, the missing permission is said.
                lookup == null -> "$removed. Ohne Zugriff auf den Kalender bleibt der Eintrag dort " +
                    "stehen — die Berechtigung lässt sich in den Android-Einstellungen der App erteilen."
                lookup.isFailure -> "$removed. Der Kalender ließ sich nicht lesen — den Eintrag dort bitte selbst löschen."
                deleted == false -> "$removed. Der Kalendereintrag ließ sich nicht löschen — bitte dort selbst löschen."
                else -> null
            }
            hint?.let { text -> _state.update { it.copy(hint = text) } }
            loadDetail(entry.placeId)
            syncNow()
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
            var businesses = 0
            var entries = 0
            for (business in repo.businessesForPhoneBook()) {
                // One business that fails must not stop the run: the rest still
                // belong in the phone book, and the button would stay disabled.
                try {
                    entries += store.persistBusiness(business)
                    businesses++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
            }
            _state.value = _state.value.copy(
                saving = false,
                phoneBookHint = "Übertragen: $businesses Betriebe in $entries " +
                    "Einträgen. DAVx5 lädt sie beim nächsten Abgleich hoch.",
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
            val business = repo.business(placeId)
            _state.value = _state.value.copy(
                detail = business,
                detailCalls = repo.calls(placeId),
                detailContacts = contacts,
                detailAppointments = repo.appointments(placeId),
                detailAddresses = repo.addresses(placeId),
            )
            // Whatever was changed in the phone book wins — afterwards the
            // record is level with the address book again.
            if (store.readBack(business, contacts)) {
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
            // The call is what completes a callback — not saving the outcome: the
            // save bar only appears when status or note changed, and a business
            // already at „Nicht erreicht" rung again without an answer changes
            // neither. Up at once, so other devices stop listing it as overdue.
            if (completeDueCallbacks(placeId) > 0) syncNow()
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
