package com.signidesign.dailytasks

import android.app.Application
import android.content.Context
import com.signidesign.dailytasks.data.AppDatabase
import com.signidesign.dailytasks.data.SettingsRepository
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.notifications.ReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI container — deliberately no Hilt/Koin for an app this size.
 * Everything hangs off two repositories, so a framework would add build
 * complexity without buying anything. Swappable later if the graph grows.
 */
class AppContainer(context: Context) {
    private val database = AppDatabase.get(context)
    val taskRepository = TaskRepository(database.taskDao(), database.dayNoteDao())
    val settingsRepository = SettingsRepository(context)
    val reminderManager = ReminderManager(context, taskRepository, settingsRepository)
}

class TaskApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Outlives any screen; used by receivers and fire-and-forget scheduling. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.reminderManager.ensureChannels()
        applicationScope.launch {
            container.reminderManager.resyncAll()
            container.reminderManager.scheduleNextDigest()
        }
    }
}
