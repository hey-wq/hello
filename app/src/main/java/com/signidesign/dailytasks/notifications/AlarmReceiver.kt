package com.signidesign.dailytasks.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.signidesign.dailytasks.TaskApp
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                when (intent.action) {
                    ReminderManager.ACTION_TASK_REMINDER -> {
                        val taskId = intent.getLongExtra(ReminderManager.EXTRA_TASK_ID, -1L)
                        val kind = intent.getIntExtra(
                            ReminderManager.EXTRA_KIND, ReminderManager.KIND_AT_START
                        )
                        if (taskId >= 0) {
                            app.container.reminderManager.showTaskReminder(taskId, kind)
                        }
                    }
                    ReminderManager.ACTION_DIGEST ->
                        app.container.reminderManager.showDigestAndReschedule()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
