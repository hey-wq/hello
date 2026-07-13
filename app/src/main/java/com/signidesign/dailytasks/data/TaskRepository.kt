package com.signidesign.dailytasks.data

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class TaskRepository(
    private val taskDao: TaskDao,
    private val dayNoteDao: DayNoteDao
) {
    fun tasksForDay(date: LocalDate): Flow<List<TaskEntity>> = taskDao.tasksForDay(date)

    fun daySummaries(start: LocalDate, end: LocalDate): Flow<List<DaySummary>> =
        taskDao.daySummaries(start, end)

    fun dayNote(date: LocalDate): Flow<DayNoteEntity?> = dayNoteDao.noteForDay(date)

    suspend fun addTask(title: String, date: LocalDate) {
        taskDao.insert(
            TaskEntity(title = title.trim(), dayDate = date, createdAt = Instant.now())
        )
    }

    suspend fun setDone(task: TaskEntity, done: Boolean) =
        taskDao.update(task.copy(isDone = done))

    suspend fun setTitle(task: TaskEntity, title: String) =
        taskDao.update(task.copy(title = title))

    suspend fun setNote(task: TaskEntity, note: String) =
        taskDao.update(task.copy(note = note.ifBlank { null }))

    suspend fun setSchedule(task: TaskEntity, start: LocalTime?, durationMinutes: Int?) =
        taskDao.update(
            task.copy(
                isTimed = start != null,
                startTime = start,
                durationMinutes = if (start != null) durationMinutes else null
            )
        )

    suspend fun delete(task: TaskEntity) = taskDao.delete(task)

    suspend fun saveDayNote(date: LocalDate, content: String) {
        if (content.isBlank()) dayNoteDao.delete(date)
        else dayNoteDao.upsert(DayNoteEntity(dayDate = date, content = content))
    }
}
