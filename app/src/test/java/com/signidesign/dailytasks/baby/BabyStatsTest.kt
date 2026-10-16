package com.signidesign.dailytasks.baby

import com.signidesign.dailytasks.data.BabyEventEntity
import com.signidesign.dailytasks.data.BabyEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day boundary is where baby trackers get it wrong, so it is where the
 * tests are. A sleep from 19:40 to 06:20 is four hours of one day and six of
 * the next, and "sleep today" has to mean the six.
 */
class BabyStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val day: LocalDate = LocalDate.of(2026, 10, 16)
    private val now: Instant = at(12, 0)

    private fun at(hour: Int, minute: Int, onDay: LocalDate = day): Instant =
        onDay.atTime(hour, minute).atZone(zone).toInstant()

    private fun event(
        kind: BabyEventKind,
        start: Instant,
        end: Instant? = null,
        amountMl: Int? = null
    ) = BabyEventEntity(
        id = 0,
        kind = kind,
        startAt = start,
        endAt = end,
        amountMl = amountMl
    )

    private val nightSleep = event(
        BabyEventKind.SLEEP,
        at(19, 40, day.minusDays(1)),
        at(6, 20)
    )

    @Test
    fun `a sleep across midnight is split between the two days`() {
        assertEquals(
            Duration.ofHours(6).plusMinutes(20),
            BabyStats.durationOn(nightSleep, day, zone, now)
        )
        assertEquals(
            Duration.ofHours(4).plusMinutes(20),
            BabyStats.durationOn(nightSleep, day.minusDays(1), zone, now)
        )
        assertEquals(Duration.ofHours(10).plusMinutes(40), BabyStats.duration(nightSleep, now))
    }

    @Test
    fun `a day it never touched gets nothing`() {
        assertEquals(
            Duration.ZERO,
            BabyStats.durationOn(nightSleep, day.plusDays(3), zone, now)
        )
    }

    @Test
    fun `an open session is measured up to now`() {
        val running = event(BabyEventKind.SLEEP, at(11, 30))
        assertEquals(Duration.ofMinutes(30), BabyStats.duration(running, now))
    }

    @Test
    fun `a full day totals up by overlap`() {
        val events = listOf(
            nightSleep,
            event(BabyEventKind.BREAST_LEFT, at(6, 25), at(6, 45)),
            event(BabyEventKind.BREAST_RIGHT, at(9, 0), at(9, 15)),
            event(BabyEventKind.BOTTLE, at(10, 0), at(10, 0), amountMl = 120),
            event(BabyEventKind.SLEEP, at(10, 30), at(11, 15)),
            event(BabyEventKind.NAPPY_WET, at(6, 50), at(6, 50)),
            event(BabyEventKind.NAPPY_BOTH, at(9, 30), at(9, 30)),
            event(BabyEventKind.PUMP, at(11, 20), at(11, 40), amountMl = 90)
        )
        val summary = BabyStats.summarise(events, day, zone, now)

        assertEquals(3, summary.feedCount)
        assertEquals(Duration.ofMinutes(35), summary.breastTime)
        assertEquals(Duration.ofMinutes(20), summary.leftTime)
        assertEquals(Duration.ofMinutes(15), summary.rightTime)
        assertEquals(120, summary.bottleMl)
        assertEquals(1, summary.bottleCount)
        assertEquals(90, summary.pumpedMl)
        // 6h20 spilled in from the night, plus a 45 minute nap.
        assertEquals(Duration.ofHours(7).plusMinutes(5), summary.sleepTime)
        assertEquals(2, summary.sleepSessions)
        // The longest stretch is the whole night, not just today's share.
        assertEquals(Duration.ofHours(10).plusMinutes(40), summary.longestSleep)
        // A mixed nappy counts on both tallies.
        assertEquals(2, summary.nappyWet)
        assertEquals(1, summary.nappyDirty)
        assertEquals(3, summary.nappyTotal)
        assertEquals(at(10, 0), summary.lastFeedAt)
    }

    @Test
    fun `a night feed belongs to the day it happened on`() {
        val summary = BabyStats.summarise(
            listOf(event(BabyEventKind.BREAST_LEFT, at(2, 10), at(2, 30))),
            day, zone, now
        )
        assertEquals(1, summary.feedCount)
        assertEquals(Duration.ofMinutes(20), summary.breastTime)
    }

    @Test
    fun `a feed running in from last night adds time but not a count`() {
        val summary = BabyStats.summarise(
            listOf(event(BabyEventKind.BREAST_LEFT, at(23, 50, day.minusDays(1)), at(0, 10))),
            day, zone, now
        )
        assertEquals(0, summary.feedCount)
        assertEquals(Duration.ofMinutes(10), summary.breastTime)
    }

    @Test
    fun `sleep bands fill the hours actually slept`() {
        val bands = BabyStats.sleepBands(listOf(nightSleep), day, zone, now)
        assertEquals(24, bands.size)
        assertEquals(1f, bands[0], 0.001f)
        assertEquals(1f, bands[5], 0.001f)
        // 06:00-06:20 is a third of that hour.
        assertTrue("hour 6 was ${bands[6]}", bands[6] > 0.3f && bands[6] < 0.35f)
        assertEquals(0f, bands[7], 0.001f)
    }

    @Test
    fun `time since the last feed measures from its end`() {
        assertEquals(
            Duration.ofMinutes(20),
            BabyStats.sinceLast(
                listOf(event(BabyEventKind.BREAST_LEFT, at(11, 20), at(11, 40))),
                setOf(BabyEventKind.BREAST_LEFT),
                now
            )
        )
    }

    @Test
    fun `durations read correctly on screen and aloud`() {
        assertEquals("4h 20m", BabyStats.formatDuration(Duration.ofMinutes(260)))
        assertEquals("4h", BabyStats.formatDuration(Duration.ofHours(4)))
        assertEquals("35m", BabyStats.formatDuration(Duration.ofMinutes(35)))
        assertEquals("4 hours 20 minutes", BabyStats.speakDuration(Duration.ofMinutes(260)))
        assertEquals("an hour", BabyStats.speakDuration(Duration.ofHours(1)))
        assertEquals("35 minutes", BabyStats.speakDuration(Duration.ofMinutes(35)))
        assertEquals("under a minute", BabyStats.speakDuration(Duration.ZERO))
    }

    @Test
    fun `lanes keep a dream feed from ending a sleep`() {
        // A data-level invariant the repository relies on.
        assertEquals(BabyEventKind.SLEEP.lane, BabyEventKind.SLEEP.lane)
        assertTrue(BabyEventKind.BREAST_LEFT.lane != BabyEventKind.SLEEP.lane)
        assertTrue(BabyEventKind.PUMP.lane == BabyEventKind.BREAST_LEFT.lane)
        assertTrue(BabyEventKind.BOTTLE.isFeed)
        assertTrue(!BabyEventKind.PUMP.isFeed)
        assertTrue(BabyEventKind.NAPPY_BOTH.isNappy)
    }
}
