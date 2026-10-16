package com.signidesign.dailytasks.data

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes and reads for baby tracking.
 *
 * Two rules live here. **One open session per lane**: starting the right
 * breast while the left is running ends the left one, because that is what
 * switching sides means — but starting a feed never ends a sleep, because
 * dream feeds exist. **Reads are widened**: a day's query reaches back far
 * enough to catch the sleep that began last night, since that stretch is
 * part of this morning.
 */
class BabyRepository(private val dao: BabyDao) {

    private fun now(): Long = System.currentTimeMillis()

    /** How far back a day read reaches, to catch a session already running. */
    private val spillHours = 24L

    private fun lanesOf(lane: BabyLane): List<BabyEventKind> =
        BabyEventKind.entries.filter { it.lane == lane && it.timed }

    fun eventsForDay(day: LocalDate, zone: ZoneId): Flow<List<BabyEventEntity>> {
        val start = day.atStartOfDay(zone).minusHours(spillHours).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        return dao.overlapping(start, end)
    }

    suspend fun eventsForDayOnce(day: LocalDate, zone: ZoneId): List<BabyEventEntity> {
        val start = day.atStartOfDay(zone).minusHours(spillHours).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        return dao.overlappingOnce(start, end)
    }

    fun openFeed(): Flow<BabyEventEntity?> = dao.openSession(lanesOf(BabyLane.FEED))

    fun openSleep(): Flow<BabyEventEntity?> = dao.openSession(lanesOf(BabyLane.SLEEP))

    suspend fun openFeedOnce(): BabyEventEntity? =
        dao.openSessionOnce(lanesOf(BabyLane.FEED))

    suspend fun openSleepOnce(): BabyEventEntity? =
        dao.openSessionOnce(lanesOf(BabyLane.SLEEP))

    suspend fun openSessionOnce(lane: BabyLane): BabyEventEntity? =
        dao.openSessionOnce(lanesOf(lane))

    suspend fun lastOf(vararg kinds: BabyEventKind): BabyEventEntity? =
        dao.mostRecent(kinds.toList())

    suspend fun recent(limit: Int = 50): List<BabyEventEntity> = dao.recent(limit)

    suspend fun byId(id: Long): BabyEventEntity? = dao.byId(id)

    /**
     * Starts a timed session, closing whatever was open in the same lane.
     * Returns the new row's id.
     */
    suspend fun startSession(
        kind: BabyEventKind,
        at: Instant = Instant.now(),
        note: String? = null
    ): Long {
        require(kind.timed) { "${kind.name} is not a timed session" }
        closeLane(kind.lane, at)
        return dao.insert(
            BabyEventEntity(
                kind = kind,
                startAt = at,
                note = note,
                updatedAt = now()
            )
        )
    }

    /**
     * Ends the open session in [lane] and returns it as it now stands, or
     * null when nothing was running.
     */
    suspend fun endSession(lane: BabyLane, at: Instant = Instant.now()): BabyEventEntity? {
        val open = dao.openSessionOnce(lanesOf(lane)) ?: return null
        // Guard against a clock that went backwards, or a session ended in
        // the same second it began: never store a negative stretch.
        val end = if (at.isBefore(open.startAt)) open.startAt else at
        val closed = open.copy(endAt = end, updatedAt = now())
        dao.update(closed)
        return closed
    }

    private suspend fun closeLane(lane: BabyLane, at: Instant) {
        if (lane == BabyLane.EVENT) return
        endSession(lane, at)
    }

    /** A moment with no duration: a bottle, a nappy, solids. */
    suspend fun logEvent(
        kind: BabyEventKind,
        at: Instant = Instant.now(),
        amountMl: Int? = null,
        note: String? = null
    ): Long = dao.insert(
        BabyEventEntity(
            kind = kind,
            startAt = at,
            endAt = at,
            amountMl = amountMl,
            note = note,
            updatedAt = now()
        )
    )

    /** A completed session logged after the fact ("she slept two hours"). */
    suspend fun logSession(
        kind: BabyEventKind,
        startAt: Instant,
        endAt: Instant,
        amountMl: Int? = null,
        note: String? = null
    ): Long = dao.insert(
        BabyEventEntity(
            kind = kind,
            startAt = startAt,
            endAt = if (endAt.isBefore(startAt)) startAt else endAt,
            amountMl = amountMl,
            note = note,
            updatedAt = now()
        )
    )

    suspend fun setAmount(event: BabyEventEntity, amountMl: Int?) {
        dao.update(event.copy(amountMl = amountMl, updatedAt = now()))
    }

    suspend fun setNote(event: BabyEventEntity, note: String?) {
        dao.update(event.copy(note = note?.ifBlank { null }, updatedAt = now()))
    }

    suspend fun adjustStart(event: BabyEventEntity, startAt: Instant) {
        val end = event.endAt
        val safeStart = if (end != null && startAt.isAfter(end)) end else startAt
        dao.update(event.copy(startAt = safeStart, updatedAt = now()))
    }

    suspend fun delete(event: BabyEventEntity) = dao.delete(event)

    suspend fun deleteById(id: Long): BabyEventEntity? {
        val event = dao.byId(id) ?: return null
        dao.delete(event)
        return event
    }
}
