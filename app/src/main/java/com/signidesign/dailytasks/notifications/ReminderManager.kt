package com.signidesign.dailytasks.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.signidesign.dailytasks.MainActivity
import com.signidesign.dailytasks.R
import com.signidesign.dailytasks.data.SettingsRepository
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.util.timeFormatter
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules and posts all app notifications.
 *
 * Alarms are fire-and-verify: the alarm payload is just a task id, and the
 * receiver re-reads the task from Room before posting, so completed, deleted,
 * or rescheduled tasks never produce stale notifications. That also means
 * disabling reminders in settings silently no-ops any already-armed alarms.
 */
class ReminderManager(
    private val context: Context,
    private val repository: TaskRepository,
    private val settings: SettingsRepository
) {
    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_DIGEST = "digest"

        const val ACTION_TASK_REMINDER = "com.signidesign.dailytasks.TASK_REMINDER"
        const val ACTION_DIGEST = "com.signidesign.dailytasks.DIGEST"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_EPOCH_DAY = "epochDay"

        const val KIND_HOUR_BEFORE = 0
        const val KIND_AT_START = 1

        private const val DIGEST_REQUEST_CODE = 1_000_000
        private const val DIGEST_NOTIFICATION_ID = 1_000_001
        private val DIGEST_TIME: LocalTime = LocalTime.of(8, 0)
    }

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureChannels() {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Task reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "One hour before and at the start of scheduled tasks" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIGEST,
                "Morning digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "A morning summary of the day's tasks" }
        )
    }

    // ------------------------------------------------------------------ tasks

    /** Re-arm (or clear) both alarms for a task after any mutation. */
    suspend fun resyncTask(taskId: Long) {
        cancelTask(taskId)
        val task = repository.taskById(taskId) ?: return
        if (!task.isTimed || task.isDone) return
        val start = task.startTime ?: return

        val zone = ZoneId.systemDefault()
        val startAt = LocalDateTime.of(task.dayDate, start).atZone(zone)
        val now = LocalDateTime.now().atZone(zone)

        val hourBefore = startAt.minusHours(1)
        if (hourBefore.isAfter(now)) {
            setAlarm(hourBefore.toInstant().toEpochMilli(), taskPendingIntent(taskId, KIND_HOUR_BEFORE))
        }
        if (startAt.isAfter(now)) {
            setAlarm(startAt.toInstant().toEpochMilli(), taskPendingIntent(taskId, KIND_AT_START))
        }
    }

    fun cancelTask(taskId: Long) {
        alarmManager.cancel(taskPendingIntent(taskId, KIND_HOUR_BEFORE))
        alarmManager.cancel(taskPendingIntent(taskId, KIND_AT_START))
    }

    /** Re-arm everything, e.g. after boot. */
    suspend fun resyncAll() {
        repository.upcomingTimedTasks(LocalDate.now()).forEach { resyncTask(it.id) }
    }

    /** Called by the receiver when a task alarm fires. */
    suspend fun showTaskReminder(taskId: Long, kind: Int) {
        if (!settings.remindersEnabled.first()) return
        val task = repository.taskById(taskId) ?: return
        if (!task.isTimed || task.isDone) return
        val start = task.startTime ?: return

        val text = when (kind) {
            KIND_HOUR_BEFORE -> "Starts at ${start.format(timeFormatter)} — in about an hour"
            else -> task.durationMinutes?.let { "Starting now · $it min" } ?: "Starting now"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text)
            .setContentIntent(openDayIntent(task.dayDate, taskId.toInt()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        notifySafely((taskId * 2 + kind).toInt(), notification)
    }

    // ----------------------------------------------------------------- digest

    suspend fun scheduleNextDigest() {
        if (!settings.digestEnabled.first()) return
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        var next = LocalDateTime.of(LocalDate.now(), DIGEST_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        setAlarm(next.atZone(zone).toInstant().toEpochMilli(), digestPendingIntent())
    }

    fun cancelDigest() {
        alarmManager.cancel(digestPendingIntent())
    }

    /** Called by the receiver at digest time; posts (if useful) and re-arms. */
    suspend fun showDigestAndReschedule() {
        try {
            if (!settings.digestEnabled.first()) return
            val today = LocalDate.now()
            val tasks = repository.tasksForDayOnce(today)
            val open = tasks.filter { !it.isDone }
            if (open.isEmpty()) return

            val timed = open.count { it.isTimed }
            val summary = buildString {
                append(open.size)
                append(if (open.size == 1) " task today" else " tasks today")
                if (timed > 0) append(" · $timed scheduled")
            }
            val titles = open.take(5).joinToString("\n") { task ->
                val prefix = task.startTime?.let { "${it.format(timeFormatter)}  " } ?: ""
                "$prefix${task.title}"
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_DIGEST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(summary)
                .setContentText(open.first().title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
                .setContentIntent(openDayIntent(today, DIGEST_REQUEST_CODE))
                .setAutoCancel(true)
                .build()
            notifySafely(DIGEST_NOTIFICATION_ID, notification)
        } finally {
            scheduleNextDigest()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val canExact =
            Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        }
    }

    private fun taskPendingIntent(taskId: Long, kind: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_TASK_REMINDER)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            context,
            (taskId * 2 + kind).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun digestPendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_DIGEST)
        return PendingIntent.getBroadcast(
            context,
            DIGEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openDayIntent(date: LocalDate, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySafely(id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between check and post; nothing to do.
        }
    }
}
