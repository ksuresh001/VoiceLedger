package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DefaultCategories
import com.example.data.model.NotePriority
import com.example.data.model.NoteStatus
import com.example.data.model.TimeWindow
import com.example.data.model.VoiceNote
import com.example.data.model.Workspace
import com.example.data.preferences.UserPrefs
import com.example.data.preferences.UserPrefsDataStore
import com.example.data.repository.NotesRepository
import com.example.data.repository.WorkspaceRepository
import com.example.service.AiOrganizerService
import com.example.service.OrganizedNoteResult
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

sealed interface NotesUiState {
    data object Loading : NotesUiState
    data class Success(val notes: List<VoiceNote>) : NotesUiState
    data class Error(val message: String) : NotesUiState
}

sealed interface AiOrganizeState {
    data object Idle : AiOrganizeState
    data object Processing : AiOrganizeState
    data class Ready(val draft: CaptureDraft) : AiOrganizeState
    data class Error(val message: String, val fallbackDraft: CaptureDraft) : AiOrganizeState
}

data class CaptureDraft(
    val title: String = "",
    val transcript: String = "",
    val aiSummary: String = "",
    val category: String = "Personal",
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val actionItem: String = "",
    val priority: String = NotePriority.MEDIUM.value,
    val workspaceId: String = "",
    val assigneeName: String = "",
    val localAudioFileName: String = "",
    val audioDurationSec: Int = 0,
    val dueAtMs: Long = 0L,
    val reminderMinutesBefore: Int = 60
)

data class DashboardKpis(
    val openCount: Int = 0,
    val agingOver3DaysCount: Int = 0,
    val dueSoonCount: Int = 0,
    val closedCount: Int = 0
)

data class DashboardViewState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val allNotes: List<VoiceNote> = emptyList(),
    val filteredNotes: List<VoiceNote> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val selectedWorkspaceId: String = "", // "" = All, "__personal__" = Personal only, else workspaceId
    val selectedTimeWindow: TimeWindow = TimeWindow.MONTH,
    val selectedCategory: String = "All",
    val selectedStatus: String = "active", // "active" (open + in_progress), "closed", "all"
    val onlyAgingOver3Days: Boolean = false,
    val availableCategories: List<String> = listOf("All") + DefaultCategories.presets,
    val topKeywords: List<String> = emptyList(),
    val kpis: DashboardKpis = DashboardKpis(),
    val nowMs: Long = System.currentTimeMillis()
)

class VoiceLedgerViewModel(
    private val notesRepository: NotesRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val aiOrganizerService: AiOrganizerService,
    private val prefsDataStore: UserPrefsDataStore,
    private val currentUserId: String,
    private val currentUserName: String
) : ViewModel() {

    private val tickerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    private val rawNotesState: StateFlow<NotesUiState> = notesRepository.observeNotes(currentUserId)
        .map<List<VoiceNote>, NotesUiState> { NotesUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing notes", rootError)
            emit(NotesUiState.Error(rootError.message ?: "Failed to load voice notes"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = NotesUiState.Loading
        )

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeWorkspaces(currentUserId)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing workspaces", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val userPrefs: StateFlow<UserPrefs> = prefsDataStore.prefsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserPrefs()
        )

    private val _selectedWorkspaceFilter = MutableStateFlow("")
    private val _selectedTimeWindow = MutableStateFlow(TimeWindow.MONTH)
    private val _selectedCategory = MutableStateFlow("All")
    private val _selectedStatus = MutableStateFlow("active")
    private val _onlyAgingOver3Days = MutableStateFlow(false)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _aiOrganizeState = MutableStateFlow<AiOrganizeState>(AiOrganizeState.Idle)
    val aiOrganizeState: StateFlow<AiOrganizeState> = _aiOrganizeState.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _scheduleNoteReminder = MutableSharedFlow<VoiceNote>(extraBufferCapacity = 8)
    val scheduleNoteReminder: SharedFlow<VoiceNote> = _scheduleNoteReminder.asSharedFlow()

    private val _cancelNoteReminder = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val cancelNoteReminder: SharedFlow<String> = _cancelNoteReminder.asSharedFlow()

    private val filterParams = combine(
        _selectedWorkspaceFilter,
        _selectedTimeWindow,
        _selectedCategory,
        _selectedStatus,
        _onlyAgingOver3Days
    ) { ws, tw, cat, st, agingOnly ->
        FilterParams(ws, tw, cat, st, agingOnly)
    }

    val dashboardState: StateFlow<DashboardViewState> = combine(
        rawNotesState,
        workspaces,
        filterParams,
        tickerFlow
    ) { notesState, wsList, params, nowMs ->
        when (notesState) {
            is NotesUiState.Loading -> DashboardViewState(
                isLoading = true,
                workspaces = wsList,
                selectedWorkspaceId = params.workspaceFilter,
                selectedTimeWindow = params.timeWindow,
                selectedCategory = params.category,
                selectedStatus = params.status,
                onlyAgingOver3Days = params.onlyAgingOver3Days,
                nowMs = nowMs
            )
            is NotesUiState.Error -> DashboardViewState(
                isLoading = false,
                errorMessage = notesState.message,
                workspaces = wsList,
                selectedWorkspaceId = params.workspaceFilter,
                selectedTimeWindow = params.timeWindow,
                selectedCategory = params.category,
                selectedStatus = params.status,
                onlyAgingOver3Days = params.onlyAgingOver3Days,
                nowMs = nowMs
            )
            is NotesUiState.Success -> {
                val all = notesState.notes
                val workspaceScoped = when (params.workspaceFilter) {
                    "" -> all
                    PERSONAL_FILTER -> all.filter { it.workspaceId.isBlank() }
                    else -> all.filter { it.workspaceId == params.workspaceFilter }
                }

                val timeScoped = filterByTimeWindow(workspaceScoped, params.timeWindow, nowMs)

                val openItems = timeScoped.filter { !it.isClosed }
                val aging3Days = openItems.count { it.computeAging(nowMs).days >= 3 }
                val dueSoon = openItems.count {
                    it.dueAtMs > 0L && it.dueAtMs <= nowMs + TimeUnit.HOURS.toMillis(48)
                }
                val closedItems = timeScoped.count { it.isClosed }

                val categoryFiltered = if (params.category == "All") {
                    timeScoped
                } else {
                    timeScoped.filter { it.category.equals(params.category, ignoreCase = true) }
                }

                val statusFiltered = when (params.status) {
                    "active" -> categoryFiltered.filter { !it.isClosed }
                    "closed" -> categoryFiltered.filter { it.isClosed }
                    else -> categoryFiltered
                }

                val finalNotes = if (params.onlyAgingOver3Days) {
                    statusFiltered.filter { !it.isClosed && it.computeAging(nowMs).days >= 3 }
                } else {
                    statusFiltered
                }

                val dynamicCats = (DefaultCategories.presets + all.map { it.category })
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }

                val topKw = all.flatMap { it.keywords + it.tags }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .groupingBy { it.lowercase() }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .map { it.key }

                DashboardViewState(
                    isLoading = false,
                    allNotes = all,
                    filteredNotes = finalNotes,
                    workspaces = wsList,
                    selectedWorkspaceId = params.workspaceFilter,
                    selectedTimeWindow = params.timeWindow,
                    selectedCategory = params.category,
                    selectedStatus = params.status,
                    onlyAgingOver3Days = params.onlyAgingOver3Days,
                    availableCategories = listOf("All") + dynamicCats,
                    topKeywords = topKw,
                    kpis = DashboardKpis(
                        openCount = openItems.size,
                        agingOver3DaysCount = aging3Days,
                        dueSoonCount = dueSoon,
                        closedCount = closedItems
                    ),
                    nowMs = nowMs
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = DashboardViewState()
    )

    init {
        // Automatically keep workspace note memberUids synced when teammates join
        viewModelScope.launch {
            combine(workspaces, rawNotesState) { wsList, notesState ->
                wsList to (notesState as? NotesUiState.Success)?.notes.orEmpty()
            }.collect { (wsList, notes) ->
                if (notes.isNotEmpty()) {
                    wsList.forEach { ws ->
                        notesRepository.syncWorkspaceMembersOnNotes(ws.id, ws.memberUids, notes)
                    }
                }
            }
        }
    }

    fun setWorkspaceFilter(filterId: String) {
        _selectedWorkspaceFilter.value = filterId
    }

    fun setTimeWindow(window: TimeWindow) {
        _selectedTimeWindow.value = window
    }

    fun setCategoryFilter(category: String) {
        _selectedCategory.value = category
    }

    fun setStatusFilter(status: String) {
        _selectedStatus.value = status
        if (status == "closed") {
            _onlyAgingOver3Days.value = false
        }
    }

    fun toggleAgingOver3DaysOnly() {
        val next = !_onlyAgingOver3Days.value
        _onlyAgingOver3Days.value = next
        if (next) _selectedStatus.value = "active"
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun searchNotes(query: String, category: String, status: String, timeWindow: TimeWindow): List<VoiceNote> {
        val state = dashboardState.value
        val nowMs = state.nowMs
        val timeScoped = filterByTimeWindow(state.allNotes, timeWindow, nowMs)
        val catScoped = if (category == "All") timeScoped else timeScoped.filter {
            it.category.equals(category, ignoreCase = true)
        }
        val statusScoped = when (status) {
            "active" -> catScoped.filter { !it.isClosed }
            "closed" -> catScoped.filter { it.isClosed }
            else -> catScoped
        }
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return statusScoped

        return statusScoped.filter { note ->
            val searchable = buildString {
                append(note.title).append(' ')
                append(note.transcript).append(' ')
                append(note.aiSummary).append(' ')
                append(note.category).append(' ')
                append(note.actionItem).append(' ')
                append(note.resolutionNote).append(' ')
                append(note.ownerName).append(' ')
                append(note.assigneeName).append(' ')
                append(note.tags.joinToString(" ")).append(' ')
                append(note.keywords.joinToString(" "))
            }.lowercase()
            tokens.all { token -> searchable.contains(token) }
        }
    }

    fun organizeCapturedNote(
        transcript: String,
        audioFile: File?,
        audioDurationSec: Int,
        preferredCategory: String,
        defaultWorkspaceId: String
    ) {
        val cleanTranscript = transcript.trim()
        val fallbackTitle = cleanTranscript
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString(" ")
            .ifBlank { "Voice Note" }
        val fallbackDraft = CaptureDraft(
            title = fallbackTitle,
            transcript = cleanTranscript,
            category = preferredCategory.ifBlank { "Personal" },
            workspaceId = defaultWorkspaceId,
            localAudioFileName = audioFile?.name.orEmpty(),
            audioDurationSec = audioDurationSec,
            reminderMinutesBefore = userPrefs.value.defaultLeadMinutes
        )

        viewModelScope.launch {
            _aiOrganizeState.value = AiOrganizeState.Processing
            val aiResult: Result<OrganizedNoteResult> = if (audioFile != null && audioFile.exists()) {
                aiOrganizerService.transcribeAndOrganizeAudio(
                    audioFile = audioFile,
                    existingTranscript = cleanTranscript,
                    preferredCategory = preferredCategory
                )
            } else if (cleanTranscript.isNotBlank()) {
                aiOrganizerService.organizeFromTranscript(
                    transcript = cleanTranscript,
                    preferredCategory = preferredCategory
                )
            } else {
                Result.failure(IllegalArgumentException("No voice audio or transcript captured."))
            }

            aiResult.onSuccess { organized ->
                val dueMs = if (organized.suggestedDueHours > 0) {
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(organized.suggestedDueHours.toLong())
                } else 0L
                _aiOrganizeState.value = AiOrganizeState.Ready(
                    CaptureDraft(
                        title = organized.title,
                        transcript = organized.transcript,
                        aiSummary = organized.summary,
                        category = organized.category,
                        tags = organized.tags,
                        keywords = organized.keywords,
                        actionItem = organized.actionItem,
                        priority = organized.priority,
                        workspaceId = defaultWorkspaceId,
                        localAudioFileName = audioFile?.name.orEmpty(),
                        audioDurationSec = audioDurationSec,
                        dueAtMs = dueMs,
                        reminderMinutesBefore = userPrefs.value.defaultLeadMinutes
                    )
                )
            }.onFailure { err ->
                _aiOrganizeState.value = AiOrganizeState.Error(
                    message = err.message ?: "AI audio-to-text conversion failed; retry below or review manually.",
                    fallbackDraft = fallbackDraft
                )
            }
        }
    }

    fun retryTranscribeDraft(draft: CaptureDraft, audioFile: File?) {
        viewModelScope.launch {
            _aiOrganizeState.value = AiOrganizeState.Processing
            val aiResult: Result<OrganizedNoteResult> = if (audioFile != null && audioFile.exists()) {
                aiOrganizerService.transcribeAndOrganizeAudio(
                    audioFile = audioFile,
                    existingTranscript = draft.transcript,
                    preferredCategory = draft.category
                )
            } else if (draft.transcript.isNotBlank()) {
                aiOrganizerService.organizeFromTranscript(
                    transcript = draft.transcript,
                    preferredCategory = draft.category
                )
            } else {
                Result.failure(IllegalArgumentException("No recorded audio file or text found to transcribe."))
            }

            aiResult.onSuccess { organized ->
                val dueMs = if (draft.dueAtMs > 0L) {
                    draft.dueAtMs
                } else if (organized.suggestedDueHours > 0) {
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(organized.suggestedDueHours.toLong())
                } else {
                    0L
                }
                _aiOrganizeState.value = AiOrganizeState.Ready(
                    draft.copy(
                        title = organized.title,
                        transcript = organized.transcript,
                        aiSummary = organized.summary,
                        category = organized.category,
                        tags = organized.tags,
                        keywords = organized.keywords,
                        actionItem = organized.actionItem,
                        priority = organized.priority,
                        dueAtMs = dueMs
                    )
                )
            }.onFailure { err ->
                _aiOrganizeState.value = AiOrganizeState.Error(
                    message = err.message ?: "AI audio-to-text conversion failed.",
                    fallbackDraft = draft
                )
            }
        }
    }

    fun resetAiOrganizeState() {
        _aiOrganizeState.value = AiOrganizeState.Idle
    }

    fun saveCapturedDraft(draft: CaptureDraft, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            val ws = workspaces.value.firstOrNull { it.id == draft.workspaceId }
            val members = if (ws != null) {
                (ws.memberUids + currentUserId).distinct()
            } else {
                listOf(currentUserId)
            }
            val note = VoiceNote(
                ownerId = currentUserId,
                ownerName = currentUserName,
                workspaceId = ws?.id ?: "",
                memberUids = members,
                title = draft.title.trim().ifBlank { "Voice Note" },
                transcript = draft.transcript.trim(),
                aiSummary = draft.aiSummary.trim(),
                category = draft.category.trim().ifBlank { "Personal" },
                tags = draft.tags,
                keywords = draft.keywords,
                actionItem = draft.actionItem.trim(),
                priority = draft.priority,
                status = NoteStatus.OPEN.value,
                assigneeName = draft.assigneeName.trim(),
                localAudioFileName = draft.localAudioFileName,
                audioDurationSec = draft.audioDurationSec,
                dueAtMs = draft.dueAtMs,
                reminderMinutesBefore = draft.reminderMinutesBefore
            )
            notesRepository.createNote(note)
                .onSuccess { newId ->
                    _aiOrganizeState.value = AiOrganizeState.Idle
                    if (note.dueAtMs > 0L && userPrefs.value.remindersEnabled) {
                        _scheduleNoteReminder.tryEmit(note.copy(id = newId))
                    }
                    _events.tryEmit("Voice note saved to cloud.")
                    onSaved(newId)
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Failed to save voice note.")
                }
            _isBusy.value = false
        }
    }

    fun updateExistingNote(updated: VoiceNote, onUpdated: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            notesRepository.updateNoteDetails(updated)
                .onSuccess {
                    if (updated.dueAtMs > 0L && !updated.isClosed && userPrefs.value.remindersEnabled) {
                        _scheduleNoteReminder.tryEmit(updated)
                    } else {
                        _cancelNoteReminder.tryEmit(updated.id)
                    }
                    _events.tryEmit("Note updated.")
                    onUpdated()
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Failed to update note.")
                }
            _isBusy.value = false
        }
    }

    fun reOrganizeExistingNote(note: VoiceNote, audioFile: File?, onOrganized: (VoiceNote) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val res = if (audioFile != null && audioFile.exists()) {
                aiOrganizerService.transcribeAndOrganizeAudio(
                    audioFile = audioFile,
                    existingTranscript = note.transcript,
                    preferredCategory = note.category
                )
            } else {
                aiOrganizerService.organizeFromTranscript(
                    transcript = note.transcript.ifBlank { note.title },
                    preferredCategory = note.category
                )
            }
            res.onSuccess { organized ->
                val enriched = note.copy(
                    title = organized.title,
                    transcript = organized.transcript,
                    aiSummary = organized.summary,
                    category = organized.category,
                    tags = organized.tags,
                    keywords = organized.keywords,
                    actionItem = organized.actionItem.ifBlank { note.actionItem },
                    priority = organized.priority
                )
                onOrganized(enriched)
                _events.tryEmit("Gemini AI refreshed tags & summary.")
            }.onFailure { err ->
                _events.tryEmit(err.message ?: "AI refresh failed.")
            }
            _isBusy.value = false
        }
    }

    fun resolveNote(noteId: String, resolutionNote: String, onResolved: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            notesRepository.resolveAndCloseNote(
                noteId = noteId,
                resolutionNote = resolutionNote,
                resolvedByName = currentUserName
            ).onSuccess {
                _cancelNoteReminder.tryEmit(noteId)
                _events.tryEmit("Item marked as Resolved & Closed.")
                onResolved()
            }.onFailure { err ->
                _events.tryEmit(err.message ?: "Failed to close item.")
            }
            _isBusy.value = false
        }
    }

    fun createFollowUpFromClosed(closedNote: VoiceNote, onCreated: (String) -> Unit) {
        val followUp = VoiceNote(
            ownerId = currentUserId,
            ownerName = currentUserName,
            workspaceId = closedNote.workspaceId,
            memberUids = closedNote.memberUids.ifEmpty { listOf(currentUserId) },
            title = "Follow-up: ${closedNote.title}".take(160),
            transcript = "Follow-up from closed note (${closedNote.title}). Previous resolution: ${closedNote.resolutionNote}",
            aiSummary = "Follow-up item based on prior resolution: ${closedNote.resolutionNote}".take(400),
            category = closedNote.category,
            tags = closedNote.tags,
            keywords = closedNote.keywords,
            actionItem = "Review follow-up next steps",
            priority = closedNote.priority,
            status = NoteStatus.OPEN.value,
            assigneeName = closedNote.assigneeName
        )
        viewModelScope.launch {
            _isBusy.value = true
            notesRepository.createNote(followUp)
                .onSuccess { newId ->
                    _events.tryEmit("Follow-up note created.")
                    onCreated(newId)
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Could not create follow-up.")
                }
            _isBusy.value = false
        }
    }

    fun deleteNote(noteId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            notesRepository.deleteNote(noteId)
                .onSuccess {
                    _cancelNoteReminder.tryEmit(noteId)
                    _events.tryEmit("Note deleted.")
                    onDeleted()
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Delete failed.")
                }
            _isBusy.value = false
        }
    }

    fun createWorkspace(name: String) {
        viewModelScope.launch {
            _isBusy.value = true
            workspaceRepository.createWorkspace(name, currentUserName)
                .onSuccess { ws ->
                    _selectedWorkspaceFilter.value = ws.id
                    _events.tryEmit("Workspace '${ws.name}' created. Invite code: ${ws.joinCode}")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Failed to create workspace.")
                }
            _isBusy.value = false
        }
    }

    fun joinWorkspaceByCode(code: String) {
        viewModelScope.launch {
            _isBusy.value = true
            workspaceRepository.joinWorkspaceByCode(code, currentUserName)
                .onSuccess { name ->
                    _events.tryEmit("Joined workspace '$name'!")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Could not join workspace.")
                }
            _isBusy.value = false
        }
    }

    fun deleteWorkspace(workspace: Workspace) {
        viewModelScope.launch {
            _isBusy.value = true
            workspaceRepository.deleteWorkspace(workspace)
                .onSuccess {
                    if (_selectedWorkspaceFilter.value == workspace.id) {
                        _selectedWorkspaceFilter.value = ""
                    }
                    _events.tryEmit("Workspace '${workspace.name}' removed.")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Could not remove workspace.")
                }
            _isBusy.value = false
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsDataStore.setRemindersEnabled(enabled) }
    }

    fun setDefaultLeadMinutes(minutes: Int) {
        viewModelScope.launch { prefsDataStore.setDefaultLeadMinutes(minutes) }
    }

    fun setDailyDigestEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsDataStore.setDailyDigestEnabled(enabled) }
    }

    private fun filterByTimeWindow(
        notes: List<VoiceNote>,
        window: TimeWindow,
        nowMs: Long
    ): List<VoiceNote> {
        if (window == TimeWindow.ALL) return notes
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val cutoffMs = when (window) {
            TimeWindow.DAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            TimeWindow.WEEK -> nowMs - TimeUnit.DAYS.toMillis(7)
            TimeWindow.MONTH -> nowMs - TimeUnit.DAYS.toMillis(30)
            TimeWindow.ALL -> 0L
        }
        return notes.filter { note ->
            val created = note.createdAt?.toDate()?.time ?: nowMs
            created >= cutoffMs
        }
    }

    private data class FilterParams(
        val workspaceFilter: String,
        val timeWindow: TimeWindow,
        val category: String,
        val status: String,
        val onlyAgingOver3Days: Boolean
    )

    companion object {
        private const val TAG = "VoiceLedgerVM"
        const val PERSONAL_FILTER = "__personal__"
    }
}
