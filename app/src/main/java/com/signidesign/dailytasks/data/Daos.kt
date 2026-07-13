package com.signidesign.dailytasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TaskDao {
    // Timed tasks in chronological order first, then untimed in creation order.
    @Query(
        """SELECT * FROM tasks WHERE dayDate = :date
           ORDER BY CASE WHEN startTime IS NULL THEN 1 ELSE 0 END, startTime ASC, createdAt ASC"""
    )
    fun tasksForDay(date: LocalDate): Flow<List<TaskEntity>>

    @Query(
        """SELECT dayDate,
                  COUNT(*) AS total,
                  SUM(CASE WHEN isTimed != 0 THEN 1 ELSE 0 END) AS timed,
                  SUM(CASE WHEN isDone != 0 THEN 1 ELSE 0 END) AS done
           FROM tasks
           WHERE dayDate BETWEEN :start AND :end
           GROUP BY dayDate"""
    )
    fun daySummaries(start: LocalDate, end: LocalDate): Flow<List<DaySummary>>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)
}

@Dao
interface DayNoteDao {
    @Query("SELECT * FROM day_notes WHERE dayDate = :date")
    fun noteForDay(date: LocalDate): Flow<DayNoteEntity?>

    @Upsert
    suspend fun upsert(note: DayNoteEntity)

    @Query("DELETE FROM day_notes WHERE dayDate = :date")
    suspend fun delete(date: LocalDate)
}
