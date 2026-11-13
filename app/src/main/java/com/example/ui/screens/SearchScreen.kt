package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.TimeWindow
import com.example.data.model.VoiceNote
import com.example.ui.components.VoiceNoteCard
import com.example.ui.viewmodel.DashboardViewState

@Composable
fun SearchScreen(
    state: DashboardViewState,
    query: String,
    onQueryChange: (String) -> Unit,
    searchResultsProvider: (String, String, String, TimeWindow) -> List<VoiceNote>,
    onOpenNote: (VoiceNote) -> Unit,
    modifier: Modifier = Modifier
) {
    var categoryFilter by remember { mutableStateOf("All") }
    var statusFilter by remember { mutableStateOf("all") }
    var timeFilter by remember { mutableStateOf(TimeWindow.ALL) }

    val workspaceMap = remember(state.workspaces) {
        state.workspaces.associateBy { it.id }
    }

    val results = remember(state.allNotes, query, categoryFilter, statusFilter, timeFilter) {
        searchResultsProvider(query, categoryFilter, statusFilter, timeFilter)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Historical Search",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Retrieve transcripts, AI keywords, tags, and closed resolutions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search keywords, tags, people, resolutions...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("historical_search_input")
        )

        if (state.topKeywords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "Top keywords",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                state.topKeywords.forEach { kw ->
                    SuggestionChip(
                        onClick = { onQueryChange(kw) },
                        label = { Text(kw, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        // Category + Status Filter Rows
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.availableCategories.forEach { cat ->
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { categoryFilter = cat },
                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("all" to "All Statuses", "active" to "Open", "closed" to "Closed").forEach { (key, label) ->
                FilterChip(
                    selected = statusFilter == key,
                    onClick = { statusFilter = key },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            TimeWindow.entries.forEach { tw ->
                FilterChip(
                    selected = timeFilter == tw,
                    onClick = { timeFilter = tw },
                    label = { Text(tw.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Text(
            text = "${results.size} matching ${if (results.size == 1) "entry" else "entries"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No historical entries match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(results, key = { it.id }) { note ->
                    VoiceNoteCard(
                        note = note,
                        nowMs = state.nowMs,
                        workspaceName = workspaceMap[note.workspaceId]?.name,
                        onClick = { onOpenNote(note) }
                    )
                }
            }
        }
    }
}
