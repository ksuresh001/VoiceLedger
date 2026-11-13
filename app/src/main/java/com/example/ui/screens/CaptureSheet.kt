package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.DefaultCategories
import com.example.data.model.NotePriority
import com.example.data.model.Workspace
import com.example.service.CaptureLiveState
import com.example.ui.components.LiveWaveformCanvas
import com.example.ui.components.formatSeconds
import com.example.ui.viewmodel.AiOrganizeState
import com.example.ui.viewmodel.CaptureDraft
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val dueDisplayFormatter = SimpleDateFormat("EEE, MMM dd • h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureBottomSheet(
    liveState: CaptureLiveState,
    aiOrganizeState: AiOrganizeState,
    workspaces: List<Workspace>,
    defaultWorkspaceId: String,
    isSaving: Boolean,
    onStartCapture: () -> Unit,
    onStopAndOrganize: (preferredCategory: String) -> Unit,
    onOrganizeTypedDraft: (transcript: String, preferredCategory: String) -> Unit,
    onRetryTranscribe: (CaptureDraft) -> Unit,
    onSaveDraft: (CaptureDraft) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var preferredCategory by remember { mutableStateOf("Official") }
    var manualTextMode by remember { mutableStateOf(false) }
    var manualTranscript by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            onStartCapture()
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(Unit) {
        if (aiOrganizeState is AiOrganizeState.Idle && !liveState.isRecording) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                onStartCapture()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = when (aiOrganizeState) {
                            is AiOrganizeState.Idle -> "Voice Studio Capture"
                            is AiOrganizeState.Processing -> "Gemini AI Organizing..."
                            is AiOrganizeState.Ready, is AiOrganizeState.Error -> "Review & Save Entry"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close capture sheet")
                }
            }

            when (aiOrganizeState) {
                is AiOrganizeState.Processing -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Converting voice audio to text & extracting category, AI tags, keywords & action item...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is AiOrganizeState.Ready -> {
                    DraftReviewEditor(
                        initialDraft = aiOrganizeState.draft,
                        workspaces = workspaces,
                        isSaving = isSaving,
                        bannerMessage = null,
                        onRetryTranscribe = onRetryTranscribe,
                        onSave = onSaveDraft,
                        onDiscard = onDismiss
                    )
                }

                is AiOrganizeState.Error -> {
                    DraftReviewEditor(
                        initialDraft = aiOrganizeState.fallbackDraft,
                        workspaces = workspaces,
                        isSaving = isSaving,
                        bannerMessage = aiOrganizeState.message,
                        onRetryTranscribe = onRetryTranscribe,
                        onSave = onSaveDraft,
                        onDiscard = onDismiss
                    )
                }

                is AiOrganizeState.Idle -> {
                    if (permissionDenied) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Microphone permission is needed to record voice audio and live speech.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                    ) {
                                        Text("Grant Mic Access")
                                    }
                                    OutlinedButton(onClick = { manualTextMode = true }) {
                                        Text("Type Note Instead")
                                    }
                                }
                            }
                        }
                    }

                    // Category Hint Chips
                    Text(
                        text = "Category Hint",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DefaultCategories.presets.forEach { cat ->
                            FilterChip(
                                selected = preferredCategory == cat,
                                onClick = { preferredCategory = cat },
                                label = { Text(cat) }
                            )
                        }
                    }

                    if (!manualTextMode) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(22.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.GraphicEq,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = liveState.speechStatusMessage.ifBlank { "Ready to record" },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = formatSeconds(liveState.elapsedSec),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                LiveWaveformCanvas(
                                    amplitude = liveState.amplitude,
                                    isActive = liveState.isRecording
                                )

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = liveState.fullTranscript.ifBlank {
                                            if (liveState.isRecording && liveState.hasDetectedSpeechSignal) {
                                                "Speech audio detected — keep speaking. Gemini AI will convert your recording to full text when you tap Stop."
                                            } else {
                                                "Speak your note, decision, or task naturally... Your voice audio is recorded and converted to text."
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (liveState.fullTranscript.isBlank()) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (liveState.isRecording) {
                                Button(
                                    onClick = { onStopAndOrganize(preferredCategory) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("stop_and_organize_button")
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Stop & AI Organize")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val hasMic = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasMic) onStartCapture()
                                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("start_recording_button")
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Start Recording")
                                }
                            }
                        }

                        TextButton(
                            onClick = { manualTextMode = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Prefer typing or pasting text? Switch to Text Mode")
                        }
                    } else {
                        OutlinedTextField(
                            value = manualTranscript,
                            onValueChange = { manualTranscript = it },
                            label = { Text("Note transcript or voice memo text") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_transcript_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { manualTextMode = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Back to Mic")
                            }
                            Button(
                                onClick = {
                                    onOrganizeTypedDraft(manualTranscript, preferredCategory)
                                },
                                enabled = manualTranscript.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("organize_typed_button")
                            ) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI Organize")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DraftReviewEditor(
    initialDraft: CaptureDraft,
    workspaces: List<Workspace>,
    isSaving: Boolean,
    bannerMessage: String?,
    onRetryTranscribe: (CaptureDraft) -> Unit,
    onSave: (CaptureDraft) -> Unit,
    onDiscard: () -> Unit
) {
    var title by remember(initialDraft) { mutableStateOf(initialDraft.title) }
    var transcript by remember(initialDraft) { mutableStateOf(initialDraft.transcript) }
    var aiSummary by remember(initialDraft) { mutableStateOf(initialDraft.aiSummary) }
    var category by remember(initialDraft) { mutableStateOf(initialDraft.category) }
    var tagsText by remember(initialDraft) { mutableStateOf(initialDraft.tags.joinToString(", ")) }
    var keywordsText by remember(initialDraft) { mutableStateOf(initialDraft.keywords.joinToString(", ")) }
    var actionItem by remember(initialDraft) { mutableStateOf(initialDraft.actionItem) }
    var priority by remember(initialDraft) { mutableStateOf(initialDraft.priority) }
    var workspaceId by remember(initialDraft) { mutableStateOf(initialDraft.workspaceId) }
    var assigneeName by remember(initialDraft) { mutableStateOf(initialDraft.assigneeName) }
    var dueAtMs by remember(initialDraft) { mutableStateOf(initialDraft.dueAtMs) }
    var reminderMinutes by remember(initialDraft) { mutableStateOf(initialDraft.reminderMinutesBefore) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMs by remember { mutableStateOf(System.currentTimeMillis()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!bannerMessage.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = bannerMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (initialDraft.localAudioFileName.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Original voice recording attached (${formatSeconds(initialDraft.audioDurationSec)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (!bannerMessage.isNullOrBlank() || transcript.isBlank()) {
                        OutlinedButton(
                            onClick = {
                                onRetryTranscribe(
                                    initialDraft.copy(
                                        title = title,
                                        transcript = transcript,
                                        category = category
                                    )
                                )
                            },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("retry_transcribe_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Convert Audio to Text with AI")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Headline") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("draft_title_input")
        )

        OutlinedTextField(
            value = transcript,
            onValueChange = { transcript = it },
            label = { Text("Voice-to-Text Transcript") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = actionItem,
            onValueChange = { actionItem = it },
            label = { Text("Next Action Required") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Category presets + custom text
        Text("Category", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DefaultCategories.presets.forEach { preset ->
                FilterChip(
                    selected = category.equals(preset, ignoreCase = true),
                    onClick = { category = preset },
                    label = { Text(preset) }
                )
            }
        }
        OutlinedTextField(
            value = category,
            onValueChange = { category = it },
            label = { Text("Custom or Selected Category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Priority
        Text("Priority", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotePriority.entries.forEach { p ->
                FilterChip(
                    selected = priority == p.value,
                    onClick = { priority = p.value },
                    label = { Text(p.label) }
                )
            }
        }

        // Workspace destination
        Text("Destination Space", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = workspaceId.isBlank(),
                onClick = { workspaceId = "" },
                label = { Text("Personal Cloud") }
            )
            workspaces.forEach { ws ->
                FilterChip(
                    selected = workspaceId == ws.id,
                    onClick = { workspaceId = ws.id },
                    label = { Text(ws.name) }
                )
            }
        }

        if (workspaceId.isNotBlank()) {
            OutlinedTextField(
                value = assigneeName,
                onValueChange = { assigneeName = it },
                label = { Text("Assignee (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Deadline & Reminder
        Text("Deadline & Push Reminder", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = dueAtMs == 0L,
                onClick = { dueAtMs = 0L },
                label = { Text("No Deadline") }
            )
            FilterChip(
                selected = false,
                onClick = { dueAtMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(3) },
                label = { Text("In 3h") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, 1)
                        set(Calendar.HOUR_OF_DAY, 9)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                    }
                    dueAtMs = cal.timeInMillis
                },
                label = { Text("Tomorrow 9 AM") }
            )
            FilterChip(
                selected = false,
                onClick = { showDatePicker = true },
                label = { Text("Pick Date & Time") }
            )
        }

        if (dueAtMs > 0L) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Due: ${dueDisplayFormatter.format(Date(dueAtMs))} (alert ${reminderMinutes}m prior)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedTextField(
            value = tagsText,
            onValueChange = { tagsText = it },
            label = { Text("AI Tags (comma-separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = keywordsText,
            onValueChange = { keywordsText = it },
            label = { Text("Searchable Keywords (comma-separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDiscard,
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("Discard")
            }
            Button(
                onClick = {
                    val parsedTags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(8)
                    val parsedKw = keywordsText.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(12)
                    onSave(
                        initialDraft.copy(
                            title = title.trim().ifBlank { "Voice Note" },
                            transcript = transcript.trim(),
                            aiSummary = aiSummary.trim(),
                            category = category.trim().ifBlank { "Personal" },
                            tags = parsedTags,
                            keywords = parsedKw,
                            actionItem = actionItem.trim(),
                            priority = priority,
                            workspaceId = workspaceId,
                            assigneeName = assigneeName.trim(),
                            dueAtMs = dueAtMs,
                            reminderMinutesBefore = reminderMinutes
                        )
                    )
                },
                enabled = !isSaving && title.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp)
                    .testTag("save_note_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save to Cloud")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (dueAtMs > 0L) dueAtMs else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDateMs = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                        showDatePicker = false
                        showTimePicker = true
                    }
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = 10, initialMinute = 0)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Deadline Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = pendingDateMs
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                            set(Calendar.SECOND, 0)
                        }
                        dueAtMs = cal.timeInMillis
                        showTimePicker = false
                    }
                ) { Text("Set Deadline") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}
