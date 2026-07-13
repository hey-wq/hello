package com.signidesign.dailytasks.util

import com.signidesign.dailytasks.data.TaskEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/** Render state of a timed task relative to `now` — always derived, never stored. */
sealed interface TimedState {
    data class Upcoming(val startsIn: Duration) : TimedState
    data class Running(val remaining: Duration) : TimedState
    data object Ended : TimedState
}

fun timedState(task: TaskEntity, now: LocalDateTime): TimedState? {
    val start = task.startTime ?: return null
    val startAt = LocalDateTime.of(task.dayDate, start)
    val endAt = startAt.plusMinutes((task.durationMinutes ?: 0).toLong())
    return when {
        now.isBefore(startAt) -> TimedState.Upcoming(Duration.between(now, startAt))
        now.isBefore(endAt) -> TimedState.Running(Duration.between(now, endAt))
        else -> TimedState.Ended
    }
}

fun formatDurationShort(duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

fun timedLabel(task: TaskEntity, now: LocalDateTime): String? = when (val state = timedState(task, now)) {
    is TimedState.Upcoming -> "in ${formatDurationShort(state.startsIn)}"
    is TimedState.Running -> "${formatDurationShort(state.remaining)} left"
    TimedState.Ended -> "ended"
    null -> null
}

fun isToday(date: LocalDate): Boolean = date == LocalDate.now()
