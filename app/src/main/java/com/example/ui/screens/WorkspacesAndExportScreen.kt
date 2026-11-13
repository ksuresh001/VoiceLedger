package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.model.VoiceNote
import com.example.data.model.Workspace
import com.example.data.preferences.UserPrefs
import com.example.ui.viewmodel.DashboardViewState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspacesAndExportScreen(
    state: DashboardViewState,
    userPrefs: UserPrefs,
    currentUserId: String,
    isBusy: Boolean,
    onCreateWorkspace: (String) -> Unit,
    onJoinWorkspace: (String) -> Unit,
    onDeleteWorkspace: (Workspace) -> Unit,
    onExportPdf: (List<VoiceNote>, String) -> Unit,
    onExportExcel: (List<VoiceNote>, String) -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
    onLeadMinutesChange: (Int) -> Unit,
    onDailyDigestToggle: (Boolean) -> Unit,
    onSendTestDigest: (Int, Int) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var newWorkspaceName by remember { mutableStateOf("") }
    var joinCodeInput by remember { mutableStateOf("") }
    var exportScope by remember { mutableStateOf("current") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onRemindersToggle(true)
            onShowMessage("Notification alerts enabled.")
        } else {
            onShowMessage("Notification permission denied.")
        }
    }

    val (notesToExport, exportTitle) = remember(exportScope, state) {
        when (exportScope) {
            "open" -> state.allNotes.filter { !it.isClosed } to "All Open Items"
            "closed" -> state.allNotes.filter { it.isClosed } to "Resolved & Closed Items"
            "official" -> state.allNotes.filter { it.category.equals("Official", ignoreCase = true) } to "Official Items"
            "personal" -> state.allNotes.filter { it.category.equals("Personal", ignoreCase = true) } to "Personal Items"
            "all" -> state.allNotes to "Complete Ledger"
            else -> state.filteredNotes to "Current Dashboard (${state.selectedTimeWindow.label})"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Team & Export Hub",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1. Reports & Share Sheet Export Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Shareable Reports (PDF & Excel)", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "Export ${notesToExport.size} items from '$exportTitle' and share directly with team members.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "current" to "Dashboard View",
                        "open" to "All Open",
                        "closed" to "Closed",
                        "official" to "Official",
                        "personal" to "Personal",
                        "all" to "All History"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = exportScope == key,
                            onClick = { exportScope = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onExportPdf(notesToExport, exportTitle) },
                        enabled = notesToExport.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_pdf_button")
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PDF Report", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = { onExportExcel(notesToExport, exportTitle) },
                        enabled = notesToExport.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_excel_button")
                    ) {
                        Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Excel (.xlsx)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 2. Cloud Team Workspaces Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("Cloud Team Workspaces", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "Collaborate in real time. Create a workspace and share its 6-character code, or join a teammate's workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        label = { Text("New team workspace") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("create_workspace_input")
                    )
                    Button(
                        onClick = {
                            onCreateWorkspace(newWorkspaceName)
                            newWorkspaceName = ""
                        },
                        enabled = !isBusy && newWorkspaceName.trim().length >= 2,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("create_workspace_button")
                    ) {
                        Text("Create")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = joinCodeInput,
                        onValueChange = { joinCodeInput = it.uppercase().take(10) },
                        label = { Text("Enter 6-char invite code") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("join_workspace_input")
                    )
                    OutlinedButton(
                        onClick = {
                            onJoinWorkspace(joinCodeInput)
                            joinCodeInput = ""
                        },
                        enabled = !isBusy && joinCodeInput.trim().length >= 6,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("join_workspace_button")
                    ) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Join")
                    }
                }

                if (state.workspaces.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No team workspaces yet. Create one above to share voice notes with your team.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    state.workspaces.forEach { ws ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ws.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Code: ${ws.joinCode} • ${ws.memberUids.size} member(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (ws.memberNames.isNotEmpty()) {
                                        Text(
                                            text = ws.memberNames.joinToString(", "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                Row {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Invite Code", ws.joinCode))
                                            onShowMessage("Copied invite code ${ws.joinCode}")
                                        }
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy invite code")
                                    }
                                    IconButton(
                                        onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "Join my VoiceLedger workspace '${ws.name}' with invite code: ${ws.joinCode}"
                                                )
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Invite Team Member"))
                                        }
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = "Share invite code")
                                    }
                                    if (ws.ownerId == currentUserId) {
                                        IconButton(onClick = { onDeleteWorkspace(ws) }) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete workspace",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Deadline Push Notifications Card
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Deadline Push Notifications", style = MaterialTheme.typography.titleMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Upcoming Deadline Alerts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Receive local push alerts before tasks are due.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPrefs.remindersEnabled,
                        onCheckedChange = { wantEnable ->
                            if (wantEnable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    onRemindersToggle(true)
                                } else {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                onRemindersToggle(wantEnable)
                            }
                        }
                    )
                }

                Text("Default Reminder Lead Time", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15 to "15m", 30 to "30m", 60 to "1 hour", 1440 to "1 day").forEach { (mins, label) ->
                        FilterChip(
                            selected = userPrefs.defaultLeadMinutes == mins,
                            onClick = { onLeadMinutesChange(mins) },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Aging Digest Alerts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Highlight items aging 3+ days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPrefs.dailyDigestEnabled,
                        onCheckedChange = onDailyDigestToggle
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onSendTestDigest(state.kpis.openCount, state.kpis.agingOver3DaysCount)
                            onShowMessage("Aging digest notification sent.")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_digest_notification_button")
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send Aging Digest Alert Now")
                }
            }
        }

        Spacer(modifier = Modifier.height(92.dp))
    }
}
