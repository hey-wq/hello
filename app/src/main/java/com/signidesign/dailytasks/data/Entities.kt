package com.signidesign.dailytasks.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "tasks", indices = [Index("dayDate")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val dayDate: LocalDate,
    val isDone: Boolean = false,
    val createdAt: Instant,
    val createdBy: String = "local",
    val isTimed: Boolean = false,
    val startTime: LocalTime? = null,
    val durationMinutes: Int? = null,
    val note: String? = null
)

@Entity(tableName = "day_notes")
data class DayNoteEntity(
    @PrimaryKey val dayDate: LocalDate,
    val content: String
)

/** Per-day aggregate for the calendar grid; computed in SQL, never stored. */
data class DaySummary(
    val dayDate: LocalDate,
    val total: Int,
    val timed: Int,
    val done: Int
)
