package com.signidesign.dailytasks.baby

import com.signidesign.dailytasks.data.BabyEventEntity
import com.signidesign.dailytasks.data.BabyEventKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything derived from logged moments. Pure functions over timestamps —
 * no Android, no database — because this is where a baby tracker actually
 * goes wrong.
 *
 * The trap is the day boundary. A sleep from 19:40 to 06:20 belongs partly
 * to each of two days, and a feed at 02:10 belongs to the night you
 * remember, not the calendar date the row happens to carry. Totals here
 * attribute by **overlap with the local day**, so "4h 20m of sleep today"
 * means four hours and twenty minutes that actually fell inside today.
 */
object BabyStats {

    /** A session still running is measured up to [now]. */
    fun endOrNow(event: BabyEventEntity, now: Instant): Instant =
        event.endAt ?: now

    fun duration(event: BabyEventEntity, now: Instant): Duration =
        Duration.between(event.startAt, endOrNow(event, now)).coerceAtLeast(Duration.ZERO)

    /**
     * How much of [event] fell inside [day]. Zero when it didn't touch it,
     * which is what makes a total meaningful across midnight.
     */
    fun durationOn(
        event: BabyEventEntity,
        day: LocalDate,
        zone: ZoneId,
        now: Instant
    ): Duration {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val from = maxOf(event.startAt, dayStart)
        val to = minOf(endOrNow(event, now), dayEnd)
        return if (to.isAfter(from)) Duration.between(from, to) else Duration.ZERO
    }

    /** An instant event counts on the day it happened, in local time. */
    private fun happenedOn(event: BabyEventEntity, day: LocalDate, zone: ZoneId): Boolean =
        event.startAt.atZone(zone).toLocalDate() == day

    data class DaySummary(
        val day: LocalDate,
        val feedCount: Int,
        /** Time actually at the breast inside this day. */
        val breastTime: Duration,
        val leftTime: Duration,
        val rightTime: Duration,
        val bottleMl: Int,
        val bottleCount: Int,
        val pumpedMl: Int,
        val sleepTime: Duration,
        val longestSleep: Duration,
        val sleepSessions: Int,
        val nappyWet: Int,
        val nappyDirty: Int,
        /** Null when nothing has been fed yet within the window given. */
        val lastFeedAt: Instant?,
        val lastSleepEndedAt: Instant?
    ) {
        val nappyTotal: Int get() = nappyWet + nappyDirty
    }

    /**
     * [events] should cover the day plus whatever spills in from the night
     * before; the repository widens the query for exactly that reason.
     */
    fun summarise(
        events: List<BabyEventEntity>,
        day: LocalDate,
        zone: ZoneId,
        now: Instant
    ): DaySummary {
        var breast = Duration.ZERO
        var left = Duration.ZERO
        var right = Duration.ZERO
        var sleep = Duration.ZERO
        var longest = Duration.ZERO
        var sleepSessions = 0
        var feeds = 0
        var bottleMl = 0
        var bottles = 0
        var pumped = 0
        var wet = 0
        var dirty = 0
        var lastFeed: Instant? = null
        var lastSleepEnd: Instant? = null

        events.forEach { event ->
            val onDay = durationOn(event, day, zone, now)
            when (event.kind) {
                BabyEventKind.BREAST_LEFT, BabyEventKind.BREAST_RIGHT -> {
                    breast += onDay
                    if (event.kind == BabyEventKind.BREAST_LEFT) left += onDay
                    else right += onDay
                }
                BabyEventKind.SLEEP -> {
                    sleep += onDay
                    if (!onDay.isZero) {
                        sleepSessions++
                        // "Longest sleep" means the whole stretch, even when
                        // part of it fell before midnight — that is the
                        // number a parent is actually asking about.
                        val whole = duration(event, now)
                        if (whole > longest) longest = whole
                        if (event.endAt != null) {
                            lastSleepEnd = maxOfNullable(lastSleepEnd, event.endAt)
                        }
                    }
                }
                BabyEventKind.PUMP -> if (!onDay.isZero) pumped += event.amountMl ?: 0
                BabyEventKind.BOTTLE -> if (happenedOn(event, day, zone)) {
                    bottleMl += event.amountMl ?: 0
                    bottles++
                }
                BabyEventKind.SOLIDS -> Unit
                BabyEventKind.NAPPY_WET -> if (happenedOn(event, day, zone)) wet++
                BabyEventKind.NAPPY_DIRTY -> if (happenedOn(event, day, zone)) dirty++
                BabyEventKind.NAPPY_BOTH -> if (happenedOn(event, day, zone)) {
                    wet++
                    dirty++
                }
            }

            if (event.kind.isFeed) {
                // A breast session that only started today still counts as a
                // feed today; one that ran in from last night does not.
                if (happenedOn(event, day, zone)) feeds++
                lastFeed = maxOfNullable(lastFeed, event.startAt)
            }
        }

        return DaySummary(
            day = day,
            feedCount = feeds,
            breastTime = breast,
            leftTime = left,
            rightTime = right,
            bottleMl = bottleMl,
            bottleCount = bottles,
            pumpedMl = pumped,
            sleepTime = sleep,
            longestSleep = longest,
            sleepSessions = sleepSessions,
            nappyWet = wet,
            nappyDirty = dirty,
            lastFeedAt = lastFeed,
            lastSleepEndedAt = lastSleepEnd
        )
    }

    private fun maxOfNullable(current: Instant?, candidate: Instant): Instant =
        if (current == null || candidate.isAfter(current)) candidate else current

    /** Time since the most recent event of any of [kinds]. */
    fun sinceLast(
        events: List<BabyEventEntity>,
        kinds: Set<BabyEventKind>,
        now: Instant
    ): Duration? {
        val latest = events.filter { it.kind in kinds }
            .maxByOrNull { endOrNow(it, now) }
            ?: return null
        return Duration.between(endOrNow(latest, now), now).coerceAtLeast(Duration.ZERO)
    }

    /**
     * Per-hour fraction of a local day spent asleep, 24 values of 0f..1f —
     * the pattern strip. Reading a night's shape off 24 bars is immediate in
     * a way a list of timestamps never is.
     */
    fun sleepBands(
        events: List<BabyEventEntity>,
        day: LocalDate,
        zone: ZoneId,
        now: Instant
    ): List<Float> {
        val sleeps = events.filter { it.kind == BabyEventKind.SLEEP }
        return (0 until 24).map { hour ->
            val hourStart = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()
            val hourEnd = day.atStartOfDay(zone).plusHours(hour + 1L).toInstant()
            val hourLength = Duration.between(hourStart, hourEnd).seconds.toFloat()
            if (hourLength <= 0f) return@map 0f
            val asleep = sleeps.sumOf { event ->
                val from = maxOf(event.startAt, hourStart)
                val to = minOf(endOrNow(event, now), hourEnd)
                if (to.isAfter(from)) Duration.between(from, to).seconds else 0L
            }
            (asleep.toFloat() / hourLength).coerceIn(0f, 1f)
        }
    }

    /** Hours and minutes, for the ear and the eye: "4h 20m", "35m". */
    fun formatDuration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> "${hours}h ${rest}m"
            hours > 0 -> "${hours}h"
            else -> "${rest}m"
        }
    }

    /** Spoken form: "4 hours 20 minutes", "35 minutes", "just under a minute". */
    fun speakDuration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        val hourPart = when (hours) {
            0L -> null
            1L -> "an hour"
            else -> "$hours hours"
        }
        val minutePart = when (rest) {
            0L -> null
            1L -> "1 minute"
            else -> "$rest minutes"
        }
        return when {
            hourPart != null && minutePart != null -> "$hourPart $minutePart"
            hourPart != null -> hourPart
            minutePart != null -> minutePart
            else -> "under a minute"
        }
    }
}
