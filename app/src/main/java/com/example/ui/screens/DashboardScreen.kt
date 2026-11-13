package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TimeWindow
import com.example.data.model.VoiceNote
import com.example.ui.components.VoiceNoteCard
import com.example.ui.theme.LocalVoiceLedgerColors
import com.example.ui.viewmodel.DashboardViewState
import com.example.ui.viewmodel.VoiceLedgerViewModel

@Composable
fun DashboardScreen(
    state: DashboardViewState,
    userName: String,
    userEmail: String,
    onSelectWorkspaceFilter: (String) -> Unit,
    onSelectTimeWindow: (TimeWindow) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectStatus: (String) -> Unit,
    onToggleAgingOnly: () -> Unit,
    onOpenNote: (VoiceNote) -> Unit,
    onResolveNote: (String, String) -> Unit,
    onOpenCapture: () -> Unit,
    onOpenExportHub: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var quickResolveNote by remember { mutableStateOf<VoiceNote?>(null) }
    var quickResolutionText by remember { mutableStateOf("") }

    val workspaceMap = remember(state.workspaces) {
        state.workspaces.associateBy { it.id }
    }

    val currentSpaceLabel = when (state.selectedWorkspaceId) {
        "" -> "All Spaces"
        VoiceLedgerViewModel.PERSONAL_FILTER -> "Personal Only"
        else -> workspaceMap[state.selectedWorkspaceId]?.name ?: "Workspace"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VoiceLedger",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Box {
                    Surface(
                        onClick = { workspaceMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.testTag("workspace_selector_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderShared,
                                contentDescription = "Active workspace",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = currentSpaceLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Switch workspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = workspaceMenuExpanded,
                        onDismissRequest = { workspaceMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Spaces") },
                            onClick = {
                                onSelectWorkspaceFilter("")
                                workspaceMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Personal Only") },
                            onClick = {
                                onSelectWorkspaceFilter(VoiceLedgerViewModel.PERSONAL_FILTER)
                                workspaceMenuExpanded = false
                            }
                        )
                        state.workspaces.forEach { ws ->
                            DropdownMenuItem(
                                text = { Text("Team: ${ws.name} (${ws.memberUids.size})") },
                                onClick = {
                                    onSelectWorkspaceFilter(ws.id)
                                    workspaceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenExportHub,
                    modifier = Modifier.testTag("dashboard_export_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = "Export PDF or Excel report",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Box {
                    IconButton(
                        onClick = { accountMenuExpanded = true },
                        modifier = Modifier.testTag("account_menu_button")
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "Account menu",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = accountMenuExpanded,
                        onDismissRequest = { accountMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(userName.ifBlank { "VoiceLedger User" }, style = MaterialTheme.typography.titleSmall)
                                    if (userEmail.isNotBlank()) {
                                        Text(
                                            userEmail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = { accountMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            },
                            text = { Text("Sign out") },
                            onClick = {
                                accountMenuExpanded = false
                                onSignOut()
                            }
                        )
                    }
                }
            }
        }

        // Day / Week / Month / All Segmented Selector
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("time_window_selector")
        ) {
            TimeWindow.entries.forEachIndexed { index, window ->
                SegmentedButton(
                    selected = state.selectedTimeWindow == window,
                    onClick = { onSelectTimeWindow(window) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TimeWindow.entries.size
                    ),
                    modifier = Modifier.testTag("time_window_${window.name.lowercase()}")
                ) {
                    Text(
                        text = window.label,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // KPI Aging Strip
        val semantic = LocalVoiceLedgerColors.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KpiTile(
                label = "Open",
                value = state.kpis.openCount.toString(),
                bgColor = MaterialTheme.colorScheme.surface,
                fgColor = MaterialTheme.colorScheme.onSurface,
                isSelected = state.selectedStatus == "active" && !state.onlyAgingOver3Days,
                onClick = {
                    if (state.onlyAgingOver3Days) onToggleAgingOnly()
                    onSelectStatus("active")
                },
                modifier = Modifier.weight(1f)
            )
            KpiTile(
                label = "Aging 3d+",
                value = state.kpis.agingOver3DaysCount.toString(),
                bgColor = semantic.agingBg,
                fgColor = semantic.agingFg,
                isSelected = state.onlyAgingOver3Days,
                onClick = onToggleAgingOnly,
                modifier = Modifier.weight(1f)
            )
            KpiTile(
                label = "Due Soon",
                value = state.kpis.dueSoonCount.toString(),
                bgColor = semantic.warmBg,
                fgColor = semantic.warmFg,
                isSelected = false,
                onClick = { onSelectStatus("active") },
                modifier = Modifier.weight(1f)
            )
            KpiTile(
                label = "Closed",
                value = state.kpis.closedCount.toString(),
                bgColor = semantic.closedBg,
                fgColor = semantic.closedFg,
                isSelected = state.selectedStatus == "closed",
                onClick = { onSelectStatus("closed") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.availableCategories.forEach { cat ->
                val selected = state.selectedCategory.equals(cat, ignoreCase = true)
                FilterChip(
                    selected = selected,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.testTag("category_chip_${cat.lowercase()}")
                )
            }
        }

        // Status Quick Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("active" to "Open Items", "closed" to "Resolved", "all" to "All").forEach { (key, label) ->
                FilterChip(
                    selected = state.selectedStatus == key && !state.onlyAgingOver3Days,
                    onClick = {
                        if (state.onlyAgingOver3Days) onToggleAgingOnly()
                        onSelectStatus(key)
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // Main Content Area
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            !state.errorMessage.isNullOrBlank() -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            state.filteredNotes.isEmpty() -> {
                EmptyDashboardState(
                    hasAnyNotes = state.allNotes.isNotEmpty(),
                    onStartRecording = onOpenCapture,
                    onResetFilters = {
                        onSelectCategory("All")
                        onSelectStatus("all")
                        onSelectTimeWindow(TimeWindow.ALL)
                    }
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.filteredNotes, key = { it.id }) { note ->
                        VoiceNoteCard(
                            note = note,
                            nowMs = state.nowMs,
                            workspaceName = workspaceMap[note.workspaceId]?.name,
                            onClick = { onOpenNote(note) },
                            onQuickResolve = if (!note.isClosed) {
                                {
                                    quickResolveNote = note
                                    quickResolutionText = ""
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }

    if (quickResolveNote != null) {
        val target = quickResolveNote!!
        AlertDialog(
            onDismissRequest = { quickResolveNote = null },
            icon = {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            title = { Text("Resolve & Close Item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = target.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = quickResolutionText,
                        onValueChange = { quickResolutionText = it },
                        label = { Text("Resolution notes") },
                        placeholder = { Text("How was this resolved or completed?") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_resolve_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResolveNote(
                            target.id,
                            quickResolutionText.ifBlank { "Completed and closed." }
                        )
                        quickResolveNote = null
                    },
                    modifier = Modifier.testTag("confirm_quick_resolve")
                ) {
                    Text("Mark Closed")
                }
            },
            dismissButton = {
                TextButton(onClick = { quickResolveNote = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun KpiTile(
    label: String,
    value: String,
    bgColor: Color,
    fgColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = fgColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDashboardState(
    hasAnyNotes: Boolean,
    onStartRecording: () -> Unit,
    onResetFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_studio_hero_1794589499986),
            contentDescription = "VoiceLedger studio empty state",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (hasAnyNotes) "No items match these filters" else "Capture your first voice note",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (hasAnyNotes) {
                "Try expanding to Month or All History, or record a new note."
            } else {
                "Tap the mic below to speak naturally. VoiceLedger saves your audio, converts speech to text, and organizes tags automatically."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasAnyNotes) {
                TextButton(onClick = onResetFilters) {
                    Text("Show All History")
                }
            }
            Button(
                onClick = onStartRecording,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("empty_record_button")
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Record Voice Note")
            }
        }
    }
}
