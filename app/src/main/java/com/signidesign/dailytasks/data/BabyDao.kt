package com.signidesign.dailytasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface BabyDao {

    /**
     * Everything that overlaps a window, not just everything that started in
     * it — a sleep from 7pm last night is part of this morning.
     */
    @Query(
        """SELECT * FROM baby_events
           WHERE startAt < :end AND (endAt IS NULL OR endAt > :start)
           ORDER BY startAt DESC"""
    )
    fun overlapping(start: Instant, end: Instant): Flow<List<BabyEventEntity>>

    @Query(
        """SELECT * FROM baby_events
           WHERE startAt < :end AND (endAt IS NULL OR endAt > :start)
           ORDER BY startAt DESC"""
    )
    suspend fun overlappingOnce(start: Instant, end: Instant): List<BabyEventEntity>

    /** The open session in a lane, if any. At most one is ever expected. */
    @Query(
        """SELECT * FROM baby_events WHERE endAt IS NULL AND kind IN (:kinds)
           ORDER BY startAt DESC LIMIT 1"""
    )
    fun openSession(kinds: List<BabyEventKind>): Flow<BabyEventEntity?>

    @Query(
        """SELECT * FROM baby_events WHERE endAt IS NULL AND kind IN (:kinds)
           ORDER BY startAt DESC LIMIT 1"""
    )
    suspend fun openSessionOnce(kinds: List<BabyEventKind>): BabyEventEntity?

    @Query("SELECT * FROM baby_events WHERE endAt IS NULL ORDER BY startAt DESC")
    suspend fun allOpenSessions(): List<BabyEventEntity>

    @Query(
        """SELECT * FROM baby_events WHERE kind IN (:kinds)
           ORDER BY startAt DESC LIMIT 1"""
    )
    suspend fun mostRecent(kinds: List<BabyEventKind>): BabyEventEntity?

    @Query("SELECT * FROM baby_events WHERE id = :id")
    suspend fun byId(id: Long): BabyEventEntity?

    @Query("SELECT * FROM baby_events ORDER BY startAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BabyEventEntity>

    @Insert
    suspend fun insert(event: BabyEventEntity): Long

    @Update
    suspend fun update(event: BabyEventEntity)

    @Delete
    suspend fun delete(event: BabyEventEntity)
}
