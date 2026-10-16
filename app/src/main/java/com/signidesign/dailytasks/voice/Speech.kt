package com.signidesign.dailytasks.voice

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phrasing for spoken replies. Written for the ear, not the eye: 12-hour
 * clock, relative day names, no symbols a synthesiser reads out as words.
 * Replies stay short — a confirmation, not a recital.
 */
object Speech {

    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
    private val monthDay = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())

    fun time(value: LocalTime): String = when {
        value == LocalTime.NOON -> "noon"
        value == LocalTime.MIDNIGHT -> "midnight"
        value.minute == 0 -> "${hour12(value)}${meridiem(value)}"
        else -> "${hour12(value)}:${"%02d".format(value.minute)}${meridiem(value)}"
    }

    private fun hour12(value: LocalTime): Int =
        when (val hour = value.hour % 12) {
            0 -> 12
            else -> hour
        }

    private fun meridiem(value: LocalTime): String = if (value.hour < 12) "am" else "pm"

    fun date(value: LocalDate, today: LocalDate): String = when {
        value == today -> "today"
        value == today.plusDays(1) -> "tomorrow"
        value == today.minusDays(1) -> "yesterday"
        value.isAfter(today) && value.isBefore(today.plusDays(7)) -> value.format(weekday)
        else -> value.format(monthDay)
    }

    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest minutes"
            rest == 0 && hours == 1 -> "an hour"
            rest == 0 -> "$hours hours"
            hours == 1 -> "an hour and $rest minutes"
            else -> "$hours hours $rest minutes"
        }
    }

    fun count(n: Int, singular: String, plural: String = "${singular}s"): String =
        if (n == 1) "1 $singular" else "$n $plural"

    /** "a, b and c" */
    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    val help: String =
        "For your day: add call Delta Controls tomorrow at 3 for 45 minutes. " +
            "What's on Friday. Mark the Nestlé deck as done. " +
            "Push the invoice to Monday. What's next. " +
            "For the baby: feeding on the left. She's asleep. She's awake. " +
            "Log a 120ml bottle. Wet nappy. How much sleep today. " +
            "When was the last feed."
}
