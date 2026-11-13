package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.concurrent.TimeUnit

enum class NoteStatus(val value: String, val label: String) {
    OPEN("open", "Open"),
    IN_PROGRESS("in_progress", "In Progress"),
    CLOSED("closed", "Closed");

    companion object {
        fun fromValue(raw: String): NoteStatus =
            entries.firstOrNull { it.value == raw } ?: OPEN
    }
}

enum class NotePriority(val value: String, val label: String) {
    HIGH("high", "High"),
    MEDIUM("medium", "Medium"),
    LOW("low", "Low");

    companion object {
        fun fromValue(raw: String): NotePriority =
            entries.firstOrNull { it.value == raw } ?: MEDIUM
    }
}

enum class TimeWindow(val label: String) {
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    ALL("All")
}

enum class AgingTier(val label: String) {
    FRESH("Fresh"),
    WARM("Warm"),
    AGING("Aging"),
    CRITICAL("Critical"),
    CLOSED("Resolved")
}

data class NoteAging(
    val label: String,
    val totalHours: Long,
    val days: Long,
    val remainingHours: Long,
    val tier: AgingTier,
    val isOverdue: Boolean
)

@IgnoreExtraProperties
data class VoiceNote(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val workspaceId: String = "",
    val memberUids: List<String> = emptyList(),
    val title: String = "",
    val transcript: String = "",
    val aiSummary: String = "",
    val category: String = "Personal",
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val actionItem: String = "",
    val priority: String = NotePriority.MEDIUM.value,
    val status: String = NoteStatus.OPEN.value,
    val resolutionNote: String = "",
    val resolvedByUid: String = "",
    val resolvedByName: String = "",
    val assigneeName: String = "",
    val localAudioFileName: String = "",
    val audioDurationSec: Int = 0,
    val dueAtMs: Long = 0L,
    val reminderMinutesBefore: Int = 60,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val closedAt: Timestamp? = null
) {
    val noteStatus: NoteStatus
        get() = NoteStatus.fromValue(status)

    val notePriority: NotePriority
        get() = NotePriority.fromValue(priority)

    val isClosed: Boolean
        get() = noteStatus == NoteStatus.CLOSED

    fun computeAging(nowMs: Long = System.currentTimeMillis()): NoteAging {
        val createdMs = createdAt?.toDate()?.time ?: nowMs
        val isOverdue = !isClosed && dueAtMs > 0L && nowMs > dueAtMs

        if (isClosed) {
            val closedMs = closedAt?.toDate()?.time ?: updatedAt?.toDate()?.time ?: nowMs
            val elapsedMs = (closedMs - createdMs).coerceAtLeast(0L)
            val totalHours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
            val days = totalHours / 24
            val remHours = totalHours % 24
            val label = when {
                days > 0 -> "Closed in ${days}d ${remHours}h"
                totalHours > 0 -> "Closed in ${totalHours}h"
                else -> "Closed < 1h"
            }
            return NoteAging(label, totalHours, days, remHours, AgingTier.CLOSED, false)
        }

        val elapsedMs = (nowMs - createdMs).coerceAtLeast(0L)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
        val totalHours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val days = totalHours / 24
        val remHours = totalHours % 24

        val label = when {
            totalHours < 1 -> "${totalMinutes.coerceAtLeast(1)}m"
            days == 0L -> "${totalHours}h"
            remHours == 0L -> "${days}d"
            else -> "${days}d ${remHours}h"
        }

        val tier = when {
            isOverdue || days >= 7 -> AgingTier.CRITICAL
            days >= 3 -> AgingTier.AGING
            days >= 1 -> AgingTier.WARM
            else -> AgingTier.FRESH
        }

        return NoteAging(label, totalHours, days, remHours, tier, isOverdue)
    }
}

@IgnoreExtraProperties
data class Workspace(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val joinCode: String = "",
    val memberUids: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WorkspaceInvite(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val workspaceName: String = "",
    val ownerId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

object DefaultCategories {
    val presets = listOf("Official", "Personal", "Client", "Finance", "Health", "Ideas")
}
