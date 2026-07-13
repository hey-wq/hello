package com.signidesign.dailytasks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.data.DayNoteEntity
import com.signidesign.dailytasks.data.DaySummary
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.data.ThemeRepository
import com.signidesign.dailytasks.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
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
    private val theme: ThemeRepository
) : ViewModel() {

    val themeMode: Flow<ThemeMode> = theme.themeMode

    fun tasksFor(date: LocalDate): Flow<List<TaskEntity>> = tasks.tasksForDay(date)

    fun dayNoteFor(date: LocalDate): Flow<DayNoteEntity?> = tasks.dayNote(date)

    fun summariesFor(month: YearMonth): Flow<List<DaySummary>> =
        tasks.daySummaries(month.atDay(1), month.atEndOfMonth())

    fun addTask(title: String, date: LocalDate) {
        if (title.isBlank()) return
        viewModelScope.launch { tasks.addTask(title, date) }
    }

    fun setDone(task: TaskEntity, done: Boolean) =
        viewModelScope.launch { tasks.setDone(task, done) }

    fun setNote(task: TaskEntity, note: String) =
        viewModelScope.launch { tasks.setNote(task, note) }

    fun setSchedule(task: TaskEntity, start: LocalTime?, durationMinutes: Int?) =
        viewModelScope.launch { tasks.setSchedule(task, start, durationMinutes) }

    fun deleteTask(task: TaskEntity) =
        viewModelScope.launch { tasks.delete(task) }

    fun saveDayNote(date: LocalDate, content: String) =
        viewModelScope.launch { tasks.saveDayNote(date, content) }

    fun setThemeMode(mode: ThemeMode) =
        viewModelScope.launch { theme.setThemeMode(mode) }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] as TaskApp
                return AppViewModel(
                    app.container.taskRepository,
                    app.container.themeRepository
                ) as T
            }
        }
    }
}
