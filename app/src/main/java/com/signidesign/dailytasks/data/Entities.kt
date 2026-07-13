package com.signidesign.dailytasks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

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
    val note: String? = null,
    // Manual list position within a day; assigned as max+1 on insert/move.
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    // Stable cross-device identity for sync.
    @ColumnInfo(defaultValue = "''") val uuid: String = UUID.randomUUID().toString(),
    // Last local mutation, epoch millis; drives last-write-wins sync.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)

@Entity(tableName = "day_notes")
data class DayNoteEntity(
    @PrimaryKey val dayDate: LocalDate,
    val content: String,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)

/** Tombstone so deletions propagate to the sheet and other devices. */
@Entity(tableName = "deleted_tasks")
data class DeletedTaskEntity(
    @PrimaryKey val uuid: String,
    val deletedAt: Long
)

/** Per-day aggregate for the calendar grid; computed in SQL, never stored. */
data class DaySummary(
    val dayDate: LocalDate,
    val total: Int,
    val timed: Int,
    val done: Int
)
