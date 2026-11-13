package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.DefaultCategories
import com.example.data.model.NotePriority
import com.example.data.model.NoteStatus
import com.example.data.model.VoiceNote
import com.example.service.AudioPlaybackState
import com.example.ui.components.AgingBadge
import com.example.ui.components.CategoryBadge
import com.example.ui.components.formatSeconds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val fullDateFmt = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(
    note: VoiceNote?,
    currentUserId: String,
    nowMs: Long,
    audioFile: File?,
    playbackState: AudioPlaybackState,
    isBusy: Boolean,
    onToggleAudio: (File) -> Unit,
    onSeekAudio: (Int) -> Unit,
    onStopAudio: () -> Unit,
    onReOrganizeWithAi: (VoiceNote, File?, (VoiceNote) -> Unit) -> Unit,
    onSaveChanges: (VoiceNote) -> Unit,
    onResolveNote: (String, String) -> Unit,
    onCreateFollowUp: (VoiceNote) -> Unit,
    onDeleteNote: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { onStopAudio() }
    }

    if (note == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Note Details") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center
            ) {
                Text("This voice note is no longer available.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    var title by remember(note.id, note.updatedAt) { mutableStateOf(note.title) }
    var transcript by remember(note.id, note.updatedAt) { mutableStateOf(note.transcript) }
    var aiSummary by remember(note.id, note.updatedAt) { mutableStateOf(note.aiSummary) }
    var category by remember(note.id, note.updatedAt) { mutableStateOf(note.category) }
    var tagsText by remember(note.id, note.updatedAt) { mutableStateOf(note.tags.joinToString(", ")) }
    var keywordsText by remember(note.id, note.updatedAt) { mutableStateOf(note.keywords.joinToString(", ")) }
    var actionItem by remember(note.id, note.updatedAt) { mutableStateOf(note.actionItem) }
    var priority by remember(note.id, note.updatedAt) { mutableStateOf(note.priority) }
    var status by remember(note.id, note.updatedAt) { mutableStateOf(note.status) }
    var assigneeName by remember(note.id, note.updatedAt) { mutableStateOf(note.assigneeName) }
    var dueAtMs by remember(note.id, note.updatedAt) { mutableStateOf(note.dueAtMs) }
    var resolutionInput by remember(note.id) { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val voiceResolveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                resolutionInput = if (resolutionInput.isBlank()) spoken else "$resolutionInput $spoken"
            }
        }
    }

    val aging = note.computeAging(nowMs)
    val isClosed = note.isClosed

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text("Entry Details", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val shareBody = buildString {
                                appendLine("VoiceLedger Note: ${note.title}")
                                appendLine("Category: ${note.category} | Status: ${note.noteStatus.label} | Age: ${aging.label}")
                                if (note.actionItem.isNotBlank()) appendLine("Action: ${note.actionItem}")
                                if (note.isClosed && note.resolutionNote.isNotBlank()) {
                                    appendLine("Resolution: ${note.resolutionNote}")
                                }
                                if (note.transcript.isNotBlank()) {
                                    appendLine()
                                    appendLine(note.transcript)
                                }
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, note.title)
                                putExtra(Intent.EXTRA_TEXT, shareBody)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Voice Note"))
                        }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share note text")
                    }
                    if (note.ownerId == currentUserId) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.testTag("detail_delete_button")
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Delete note",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Status + Aging Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryBadge(category = category)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = note.noteStatus.label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                AgingBadge(aging = aging)
            }

            // Voice Audio Player Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Original Voice Recording",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = formatSeconds(
                                if (playbackState.durationMs > 0) playbackState.durationMs / 1000 else note.audioDurationSec
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (audioFile != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { onToggleAudio(audioFile) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("audio_play_pause_button")
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }

                            val maxMs = playbackState.durationMs.coerceAtLeast(note.audioDurationSec * 1000).coerceAtLeast(1000)
                            Slider(
                                value = playbackState.currentPositionMs.toFloat().coerceIn(0f, maxMs.toFloat()),
                                onValueChange = { onSeekAudio(it.toInt()) },
                                valueRange = 0f..maxMs.toFloat(),
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = formatSeconds(playbackState.currentPositionMs / 1000),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = if (note.localAudioFileName.isBlank()) {
                                "Captured as text note (no audio clip attached)."
                            } else {
                                "Audio file (${formatSeconds(note.audioDurationSec)}) was recorded on another device; full transcript synced."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!isClosed) {
                        OutlinedButton(
                            onClick = {
                                onReOrganizeWithAi(note.copy(transcript = transcript, title = title, category = category), audioFile) { enriched ->
                                    title = enriched.title
                                    transcript = enriched.transcript
                                    aiSummary = enriched.aiSummary
                                    category = enriched.category
                                    tagsText = enriched.tags.joinToString(", ")
                                    keywordsText = enriched.keywords.joinToString(", ")
                                    actionItem = enriched.actionItem
                                    priority = enriched.priority
                                }
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Processing with Gemini AI...")
                            } else {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (audioFile != null && transcript.isBlank()) {
                                        "Convert Audio to Text with AI"
                                    } else {
                                        "Re-Transcribe & Refresh AI Tags"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Resolution Section (Closed vs Open)
            if (isClosed) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Closed",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Resolved & Closed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = note.resolutionNote.ifBlank { "Closed without extra notes." },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        val closedAtText = note.closedAt?.toDate()?.let { fullDateFmt.format(it) } ?: ""
                        Text(
                            text = "Closed by ${note.resolvedByName.ifBlank { "Owner" }} ${if (closedAtText.isNotBlank()) "on $closedAtText" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { onCreateFollowUp(note) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("create_followup_button")
                        ) {
                            Icon(Icons.Filled.AddTask, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Follow-up Note")
                        }
                    }
                }
            } else {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Action & Resolution", style = MaterialTheme.typography.titleMedium)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(NoteStatus.OPEN, NoteStatus.IN_PROGRESS).forEach { st ->
                                FilterChip(
                                    selected = status == st.value,
                                    onClick = { status = st.value },
                                    label = { Text(st.label) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = resolutionInput,
                            onValueChange = { resolutionInput = it },
                            label = { Text("Resolution note (to close this item)") },
                            placeholder = { Text("Describe the outcome or dictate via mic...") },
                            minLines = 2,
                            maxLines = 4,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate resolution summary")
                                        }
                                        runCatching { voiceResolveLauncher.launch(intent) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = "Dictate resolution note",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_resolution_input")
                        )

                        Button(
                            onClick = {
                                onResolveNote(
                                    note.id,
                                    resolutionInput.trim().ifBlank { "Completed and closed." }
                                )
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("detail_resolve_button")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Resolve & Mark Closed")
                        }
                    }
                }
            }

            // Content & Metadata Editor Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Note Content & Organization", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        enabled = !isClosed,
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = transcript,
                        onValueChange = { transcript = it },
                        enabled = !isClosed,
                        label = { Text("Voice-to-Text Transcript") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = aiSummary,
                        onValueChange = { aiSummary = it },
                        enabled = !isClosed,
                        label = { Text("AI Summary") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = actionItem,
                        onValueChange = { actionItem = it },
                        enabled = !isClosed,
                        label = { Text("Next Action") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isClosed) {
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
                    }

                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        enabled = !isClosed,
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotePriority.entries.forEach { p ->
                            FilterChip(
                                selected = priority == p.value,
                                onClick = { if (!isClosed) priority = p.value },
                                enabled = !isClosed,
                                label = { Text(p.label) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = assigneeName,
                        onValueChange = { assigneeName = it },
                        enabled = !isClosed,
                        label = { Text("Assignee") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        enabled = !isClosed,
                        label = { Text("AI Tags") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = { keywordsText = it },
                        enabled = !isClosed,
                        label = { Text("Searchable Keywords") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Deadline Editor
                    if (!isClosed) {
                        Text("Deadline Alert", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = dueAtMs == 0L,
                                onClick = { dueAtMs = 0L },
                                label = { Text("None") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { dueAtMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(3) },
                                label = { Text("In 3h") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { showDatePicker = true },
                                label = { Text("Pick Date & Time") }
                            )
                        }
                    }

                    if (dueAtMs > 0L) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Due: ${fullDateFmt.format(Date(dueAtMs))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (!isClosed) {
                        Button(
                            onClick = {
                                val parsedTags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(8)
                                val parsedKw = keywordsText.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(12)
                                onSaveChanges(
                                    note.copy(
                                        title = title.trim().ifBlank { note.title },
                                        transcript = transcript.trim(),
                                        aiSummary = aiSummary.trim(),
                                        category = category.trim().ifBlank { "Personal" },
                                        tags = parsedTags,
                                        keywords = parsedKw,
                                        actionItem = actionItem.trim(),
                                        priority = priority,
                                        status = status,
                                        assigneeName = assigneeName.trim(),
                                        dueAtMs = dueAtMs
                                    )
                                )
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("detail_save_changes_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Changes")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Voice Note?") },
            text = { Text("This permanently deletes \"${note.title}\" from cloud sync.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteNote(note.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
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
        AlertDialog(
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
