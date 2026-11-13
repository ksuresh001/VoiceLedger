package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.model.VoiceNote

class NotificationScheduler(private val context: Context) {

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deadline & Aging Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for upcoming voice note deadlines and aging tasks."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun scheduleDeadlineReminder(note: VoiceNote) {
        if (note.id.isBlank() || note.isClosed || note.dueAtMs <= 0L) return
        ensureNotificationChannel()

        val leadMs = note.reminderMinutesBefore.coerceAtLeast(0) * 60_000L
        val triggerAtMs = (note.dueAtMs - leadMs).coerceAtLeast(System.currentTimeMillis() + 5_000L)
        if (note.dueAtMs < System.currentTimeMillis() - 60_000L) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DeadlineNotificationReceiver::class.java).apply {
            putExtra(DeadlineNotificationReceiver.EXTRA_NOTE_ID, note.id)
            putExtra(DeadlineNotificationReceiver.EXTRA_TITLE, note.title)
            putExtra(
                DeadlineNotificationReceiver.EXTRA_BODY,
                note.actionItem.ifBlank { "Action due soon (${note.category})" }
            )
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            note.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
    }

    fun cancelReminder(noteId: String) {
        if (noteId.isBlank()) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DeadlineNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun sendAgingDigestNotification(openCount: Int, agingOver3DaysCount: Int) {
        ensureNotificationChannel()
        val intent = Intent(context, DeadlineNotificationReceiver::class.java).apply {
            putExtra(DeadlineNotificationReceiver.EXTRA_NOTE_ID, "aging_digest")
            putExtra(DeadlineNotificationReceiver.EXTRA_TITLE, "VoiceLedger Aging Digest")
            putExtra(
                DeadlineNotificationReceiver.EXTRA_BODY,
                "You have $openCount open voice items ($agingOver3DaysCount aging 3+ days)."
            )
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val CHANNEL_ID = "voiceledger_deadlines"
    }
}
