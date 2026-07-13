package com.signidesign.dailytasks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.data.DayNoteEntity
import com.signidesign.dailytasks.data.DaySummary
import com.signidesign.dailytasks.data.SettingsRepository
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.notifications.ReminderManager
import com.signidesign.dailytasks.sync.SyncEngine
import com.signidesign.dailytasks.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * One ViewModel for the whole app. The screens are thin projections over the
 * same two repositories, and day pages need flows keyed by arbitrary dates
 * (the pager renders neighbours), so per-screen ViewModels would just
 * duplicate this surface.
 */
class AppViewModel(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderManager,
    private val syncEngine: SyncEngine
) : ViewModel() {

    val themeMode: Flow<ThemeMode> = settings.themeMode
    val remindersEnabled: Flow<Boolean> = settings.remindersEnabled
    val digestEnabled: Flow<Boolean> = settings.digestEnabled
    val syncUrl: Flow<String> = settings.syncUrl
    val syncToken: Flow<String> = settings.syncToken
    val lastSyncAt: Flow<Long> = settings.lastSyncAt

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus

    fun tasksFor(date: LocalDate): Flow<List<TaskEntity>> = tasks.tasksForDay(date)

    fun dayNoteFor(date: LocalDate): Flow<DayNoteEntity?> = tasks.dayNote(date)

    fun summariesFor(month: YearMonth): Flow<List<DaySummary>> =
        tasks.daySummaries(month.atDay(1), month.atEndOfMonth())

    fun addTask(title: String, date: LocalDate) {
        if (title.isBlank()) return
        viewModelScope.launch { tasks.addTask(title, date) }
    }

    fun setDone(task: TaskEntity, done: Boolean) = viewModelScope.launch {
        tasks.setDone(task, done)
        reminders.resyncTask(task.id)
    }

    fun setNote(task: TaskEntity, note: String) =
        viewModelScope.launch { tasks.setNote(task, note) }

    fun setSchedule(task: TaskEntity, start: LocalTime?, durationMinutes: Int?) =
        viewModelScope.launch {
            tasks.setSchedule(task, start, durationMinutes)
            reminders.resyncTask(task.id)
        }

    fun deleteTask(task: TaskEntity) = viewModelScope.launch {
        tasks.delete(task)
        reminders.cancelTask(task.id)
    }

    fun pastUnfinishedCount(before: LocalDate): Flow<Int> =
        tasks.pastUnfinishedCount(before)

    fun rolloverTo(date: LocalDate) = viewModelScope.launch {
        val moved = tasks.rolloverPastTo(date)
        moved.filter { it.isTimed }.forEach { reminders.resyncTask(it.id) }
    }

    fun moveToTomorrow(task: TaskEntity) = viewModelScope.launch {
        tasks.moveTask(task, task.dayDate.plusDays(1))
        if (task.isTimed) reminders.resyncTask(task.id)
    }

    fun reorder(orderedIds: List<Long>) = viewModelScope.launch {
        tasks.reorder(orderedIds)
    }

    fun saveDayNote(date: LocalDate, content: String) =
        viewModelScope.launch { tasks.saveDayNote(date, content) }

    fun setThemeMode(mode: ThemeMode) =
        viewModelScope.launch { settings.setThemeMode(mode) }

    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setRemindersEnabled(enabled)
        if (enabled) reminders.resyncAll()
    }

    fun setDigestEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setDigestEnabled(enabled)
        if (enabled) reminders.scheduleNextDigest() else reminders.cancelDigest()
    }

    fun saveSyncConfigAndSync(url: String, token: String) = viewModelScope.launch {
        settings.setSyncConfig(url, token)
        runSync()
    }

    fun syncNow() = viewModelScope.launch { runSync() }

    private suspend fun runSync() {
        _syncStatus.value = "Syncing…"
        when (val result = syncEngine.sync()) {
            is SyncEngine.SyncResult.Success -> {
                _syncStatus.value = "Synced — sent ${result.pushed}, received ${result.pulled}"
                reminders.resyncAll()
            }
            is SyncEngine.SyncResult.Error ->
                _syncStatus.value = "Sync failed: ${result.message}"
            SyncEngine.SyncResult.NotConfigured ->
                _syncStatus.value = "Enter the web app URL and token first"
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] as TaskApp
                return AppViewModel(
                    app.container.taskRepository,
                    app.container.settingsRepository,
                    app.container.reminderManager,
                    app.container.syncEngine
                ) as T
            }
        }
    }
}
