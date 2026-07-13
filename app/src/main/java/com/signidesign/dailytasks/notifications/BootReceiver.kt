package com.signidesign.dailytasks.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.signidesign.dailytasks.TaskApp
import kotlinx.coroutines.launch

/** Alarms don't survive a reboot; re-arm everything when the device comes up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as TaskApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                app.container.reminderManager.resyncAll()
                app.container.reminderManager.scheduleNextDigest()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
