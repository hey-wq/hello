package com.signidesign.dailytasks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * What a logged moment is. Lanes matter more than types: starting a feed
 * must not end a sleep (dream feeds happen), but tapping the right breast
 * while the left is running obviously ends the left one.
 */
enum class BabyLane { FEED, SLEEP, EVENT }

enum class BabyEventKind(
    val lane: BabyLane,
    /** Runs for a while and has an end, versus a single moment. */
    val timed: Boolean,
    val label: String
) {
    BREAST_LEFT(BabyLane.FEED, true, "Left"),
    BREAST_RIGHT(BabyLane.FEED, true, "Right"),
    BOTTLE(BabyLane.EVENT, false, "Bottle"),
    SOLIDS(BabyLane.EVENT, false, "Solids"),
    PUMP(BabyLane.FEED, true, "Pump"),
    SLEEP(BabyLane.SLEEP, true, "Sleep"),
    NAPPY_WET(BabyLane.EVENT, false, "Wet"),
    NAPPY_DIRTY(BabyLane.EVENT, false, "Dirty"),
    NAPPY_BOTH(BabyLane.EVENT, false, "Wet and dirty");

    /** Counts towards feed totals and resets "time since the last feed". */
    val isFeed: Boolean
        get() = this == BREAST_LEFT || this == BREAST_RIGHT ||
            this == BOTTLE || this == SOLIDS

    val isBreast: Boolean
        get() = this == BREAST_LEFT || this == BREAST_RIGHT

    val isNappy: Boolean
        get() = this == NAPPY_WET || this == NAPPY_DIRTY || this == NAPPY_BOTH
}

/**
 * One logged moment in a baby's day.
 *
 * Deliberately a single table rather than one per kind: every read the app
 * does — the timeline, today's totals, the open session, the last feed, a
 * spoken question — is a time-ordered union across kinds, and four tables
 * would mean four queries merged in Kotlin on every one of those paths. The
 * price is columns that only apply to some kinds ([amountMl] means nothing
 * for sleep), which is cheaper than the merge.
 *
 * Durations are never stored: an open session is [endAt] == null, and every
 * total is derived from the timestamps, the same way the task list derives
 * its time-remaining pill.
 */
@Entity(
    tableName = "baby_events",
    indices = [Index("startAt"), Index("kind"), Index("endAt")]
)
data class BabyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: BabyEventKind,
    val startAt: Instant,
    /** Null while a timed session is still running. */
    val endAt: Instant? = null,
    /** Bottle or expressed volume, millilitres. */
    val amountMl: Int? = null,
    val note: String? = null,
    /** Reserved for twins; one baby today, no migration needed later. */
    @ColumnInfo(defaultValue = "0") val babyId: Long = 0L,
    @ColumnInfo(defaultValue = "''") val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)

/** Tombstone so a deleted baby event propagates to the sheet and back. */
@Entity(tableName = "deleted_baby_events")
data class DeletedBabyEventEntity(
    @PrimaryKey val uuid: String,
    val deletedAt: Long
)
