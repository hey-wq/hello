package com.signidesign.dailytasks.voice

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A value lifted out of an utterance, plus the span it occupied. */
data class Extraction<T>(val value: T, val range: IntRange)

/**
 * Hand-written grammar for the date / time / duration fragments of a spoken
 * command.
 *
 * Deliberately rule-based rather than model-based: recognition already
 * introduces one layer of uncertainty, and a deterministic grammar is the
 * only part of the chain that can be reasoned about offline, with no key, no
 * network and no per-utterance cost. Anything it cannot resolve is reported
 * as not understood rather than guessed at.
 */
object Phrases {

    // ------------------------------------------------------------------ numbers

    private const val NUM_WORDS =
        "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
            "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
            "twenty|thirty|forty|fourty|fifty|sixty|ninety"

    /** Digits, or one number word, or a compound like "forty five". */
    private const val NUM = "(?:\\d+|(?:$NUM_WORDS)(?:[ -](?:$NUM_WORDS))?)"

    private val numberWord = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90
    )

    /** "45" / "forty five" / "forty-five" -> 45. Null when unparseable. */
    fun numberOf(raw: String): Int? {
        val text = raw.trim().lowercase()
        text.toIntOrNull()?.let { return it }
        val parts = text.split(' ', '-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val values = parts.map { numberWord[it] ?: return null }
        if (values.size == 1) return values[0]
        // "forty five" = 45; anything else takes the leading value.
        return if (values[0] >= 20 && values[1] < 10) values[0] + values[1] else values[0]
    }

    private fun decimalOf(raw: String): Double? =
        raw.trim().toDoubleOrNull() ?: numberOf(raw)?.toDouble()

    // -------------------------------------------------------------------- dates

    private val dayOfWeekByName = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY
    )

    private val monthByName = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2,
        "march" to 3, "mar" to 3, "april" to 4, "apr" to 4, "may" to 5,
        "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12
    )

    private const val MONTHS =
        "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|" +
            "august|aug|september|sept|sep|october|oct|november|nov|december|dec"

    private val dayAfterTomorrow = Regex("\\bthe day after tomorrow\\b")
    private val todayWords =
        Regex("\\b(?:today|tonight|this (?:morning|afternoon|evening))\\b")
    private val tomorrowWords = Regex("\\b(?:tomorrow|tomorow|tmrw|tmr)\\b")
    private val yesterdayWords = Regex("\\byesterday\\b")
    private val nextWeekWords = Regex("\\bnext week\\b")
    private val inNDays = Regex("\\bin ($NUM) days?\\b")
    private val inNWeeks = Regex("\\bin ($NUM) weeks?\\b")
    private val fullDayName = Regex(
        "\\b(?:(next|this|on|coming|for) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"
    )
    private val shortDayName = Regex(
        "\\b(next|this|on|coming|for) (mon|tue|tues|wed|thu|thurs|fri|sat|sun)\\b"
    )
    private val monthFirst = Regex("\\b($MONTHS)\\.? (\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val dayFirst = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTHS)\\b")
    private val onTheNth = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)?\\b")

    /**
     * Resolves the first date phrase in [text] (already lowercased) against
     * [today]. Bare weekday names resolve forwards: "friday" is the coming
     * Friday, "next friday" skips to the following week when the coming one
     * still falls inside this week.
     */
    fun extractDate(text: String, today: LocalDate): Extraction<LocalDate>? {
        dayAfterTomorrow.find(text)?.let {
            return Extraction(today.plusDays(2), it.range)
        }
        todayWords.find(text)?.let { return Extraction(today, it.range) }
        tomorrowWords.find(text)?.let { return Extraction(today.plusDays(1), it.range) }
        yesterdayWords.find(text)?.let { return Extraction(today.minusDays(1), it.range) }
        nextWeekWords.find(text)?.let { return Extraction(today.plusWeeks(1), it.range) }

        inNDays.find(text)?.let { match ->
            numberOf(match.groupValues[1])?.let { n ->
                return Extraction(today.plusDays(n.toLong()), match.range)
            }
        }
        inNWeeks.find(text)?.let { match ->
            numberOf(match.groupValues[1])?.let { n ->
                return Extraction(today.plusWeeks(n.toLong()), match.range)
            }
        }

        monthFirst.find(text)?.let { match ->
            val month = monthByName[match.groupValues[1]]
            val day = match.groupValues[2].toIntOrNull()
            if (month != null && day != null) {
                resolveMonthDay(month, day, today)?.let {
                    return Extraction(it, match.range)
                }
            }
        }
        dayFirst.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull()
            val month = monthByName[match.groupValues[2]]
            if (month != null && day != null) {
                resolveMonthDay(month, day, today)?.let {
                    return Extraction(it, match.range)
                }
            }
        }
        onTheNth.find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { day ->
                resolveDayOfMonth(day, today)?.let { return Extraction(it, match.range) }
            }
        }

        weekdayMatch(fullDayName.find(text), today)?.let { return it }
        weekdayMatch(shortDayName.find(text), today)?.let { return it }
        return null
    }

    private fun weekdayMatch(
        match: MatchResult?,
        today: LocalDate
    ): Extraction<LocalDate>? {
        if (match == null) return null
        val qualifier = match.groupValues[1]
        val dow = dayOfWeekByName[match.groupValues[2]] ?: return null
        var date = today.with(TemporalAdjusters.nextOrSame(dow))
        if (qualifier == "next") {
            val weekEnd = today
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusDays(6)
            if (!date.isAfter(weekEnd)) date = date.plusWeeks(1)
        }
        return Extraction(date, match.range)
    }

    /** Nearest future (or current) occurrence of month/day. */
    private fun resolveMonthDay(month: Int, day: Int, today: LocalDate): LocalDate? {
        val candidate = safeDate(today.year, month, day) ?: return null
        return if (candidate.isBefore(today)) safeDate(today.year + 1, month, day)
        else candidate
    }

    /** Nearest future (or current) occurrence of a bare day-of-month. */
    private fun resolveDayOfMonth(day: Int, today: LocalDate): LocalDate? {
        val thisMonth = safeDate(today.year, today.monthValue, day)
        if (thisMonth != null && !thisMonth.isBefore(today)) return thisMonth
        val next = today.plusMonths(1)
        return safeDate(next.year, next.monthValue, day)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (e: java.time.DateTimeException) {
            null
        }

    // -------------------------------------------------------------------- times

    private val dayPartTime = Regex(
        "\\b(?:at noon|noon|midday|midnight|this morning|this afternoon|this evening|tonight)\\b"
    )
    private val pastOrTo = Regex("\\b(half|quarter) (past|to) ($NUM)\\b")
    private val hourMinute = Regex(
        "\\b(?:at |@ ?)?(\\d{1,2})[:.](\\d{2}) ?(am|pm|a\\.m\\.|p\\.m\\.)?\\b"
    )
    private val hourMeridiem = Regex("\\b(\\d{1,2}) ?(am|pm|a\\.m\\.|p\\.m\\.)\\b")
    private val atHour = Regex("\\b(?:at|@) ?($NUM) ?(o'? ?clock)?\\b")

    /**
     * Resolves the first clock phrase in [text]. A bare hour with no
     * meridiem is read the way a person means it: 1–6 is afternoon, 7–11 is
     * morning, 12 is noon, 13+ is already 24-hour.
     */
    fun extractTime(text: String): Extraction<LocalTime>? {
        dayPartTime.find(text)?.let { match ->
            val time = when (match.value) {
                "midnight" -> LocalTime.MIDNIGHT
                "this morning" -> LocalTime.of(9, 0)
                "this afternoon" -> LocalTime.of(14, 0)
                "this evening" -> LocalTime.of(18, 0)
                "tonight" -> LocalTime.of(20, 0)
                else -> LocalTime.NOON // "noon", "at noon", "midday"
            }
            return Extraction(time, match.range)
        }

        pastOrTo.find(text)?.let { match ->
            val offset = if (match.groupValues[1] == "half") 30 else 15
            val hour = numberOf(match.groupValues[3])
            if (hour != null && hour in 1..23) {
                val base = LocalTime.of(normalizeHour(hour, null), 0)
                val time =
                    if (match.groupValues[2] == "past") base.plusMinutes(offset.toLong())
                    else base.minusMinutes(offset.toLong())
                return Extraction(time, match.range)
            }
        }

        hourMinute.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
            val minute = match.groupValues[2].toIntOrNull()
            val meridiem = match.groupValues[3].ifBlank { null }
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                return Extraction(
                    LocalTime.of(normalizeHour(hour, meridiem, assumeAfternoon = false), minute),
                    match.range
                )
            }
        }

        hourMeridiem.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
            if (hour != null && hour in 0..23) {
                return Extraction(
                    LocalTime.of(normalizeHour(hour, match.groupValues[2]), 0),
                    match.range
                )
            }
        }

        atHour.find(text)?.let { match ->
            val hour = numberOf(match.groupValues[1])
            if (hour != null && hour in 0..23) {
                return Extraction(LocalTime.of(normalizeHour(hour, null), 0), match.range)
            }
        }
        return null
    }

    private fun normalizeHour(
        hour: Int,
        meridiem: String?,
        assumeAfternoon: Boolean = true
    ): Int {
        val pm = meridiem?.startsWith("p") == true
        val am = meridiem?.startsWith("a") == true
        return when {
            pm -> if (hour == 12) 12 else (hour % 12) + 12
            am -> if (hour == 12) 0 else hour
            hour in 13..23 -> hour
            // No meridiem: a bare "at 3" means the afternoon, "at 9" the morning.
            assumeAfternoon && hour in 1..6 -> hour + 12
            else -> hour % 24
        }
    }

    // ---------------------------------------------------------------- durations

    private val hoursAndMinutes = Regex(
        "\\bfor ($NUM) ?(?:hours?|hrs?|h) ?(?:and )?($NUM) ?(?:minutes?|mins?|m)\\b"
    )
    private val hourAndAHalf = Regex("\\bfor (?:an?|1|one) hour and a half\\b")
    private val halfHour = Regex("\\bfor half an hour\\b")
    private val quarterHour = Regex("\\bfor a quarter of an hour\\b")
    private val hours = Regex("\\bfor (\\d+(?:\\.\\d+)?|$NUM) ?(?:hours?|hrs?|h)\\b")
    private val anHour = Regex("\\bfor (?:an|a) hour\\b")
    private val minutes = Regex("\\bfor ($NUM) ?(?:minutes?|mins?|m)\\b")
    private val bareMinutes = Regex("\\b($NUM) (?:minutes?|mins?)\\b")

    /** Resolves the first duration phrase in [text], in minutes. */
    fun extractDuration(text: String): Extraction<Int>? {
        hoursAndMinutes.find(text)?.let { match ->
            val h = numberOf(match.groupValues[1])
            val m = numberOf(match.groupValues[2])
            if (h != null && m != null) return Extraction(h * 60 + m, match.range)
        }
        hourAndAHalf.find(text)?.let { return Extraction(90, it.range) }
        halfHour.find(text)?.let { return Extraction(30, it.range) }
        quarterHour.find(text)?.let { return Extraction(15, it.range) }
        anHour.find(text)?.let { return Extraction(60, it.range) }
        hours.find(text)?.let { match ->
            decimalOf(match.groupValues[1])?.let { value ->
                val mins = (value * 60).toInt()
                if (mins > 0) return Extraction(mins, match.range)
            }
        }
        minutes.find(text)?.let { match ->
            numberOf(match.groupValues[1])?.let { if (it > 0) return Extraction(it, match.range) }
        }
        bareMinutes.find(text)?.let { match ->
            numberOf(match.groupValues[1])?.let { if (it > 0) return Extraction(it, match.range) }
        }
        return null
    }

    // ------------------------------------------------------------------ cleanup

    private val filler = Regex(
        "^(?:please |could you |can you |would you )|" +
            "(?: please| thanks| thank you| for me)$"
    )
    private val danglingPreposition = Regex("(?:^| )(?:on|at|for|to|in|the|a|an)$")

    /** Drops the spans consumed by date/time/duration phrases. */
    fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val keep = StringBuilder()
        text.forEachIndexed { index, ch ->
            if (ranges.none { index in it }) keep.append(ch)
        }
        return keep.toString()
    }

    private val leadingArticle = Regex("^(?:the|a|an|my|our|this)\\s+")

    /**
     * A spoken reference to an existing task: same cleanup as a title, minus
     * the leading article, so the echo back reads "deck review" rather than
     * "the deck review".
     */
    fun tidyQuery(raw: String): String =
        leadingArticle.replace(tidyTitle(raw), "").trim()

    /**
     * Puts the original capitalisation back on a title the grammar had to
     * lowercase to parse. Client and product names are the whole point —
     * "call delta controls" sitting in someone's day looks broken.
     */
    fun restoreCase(title: String, original: String): String {
        val byLowercase = HashMap<String, String>()
        original.split(Regex("\\s+")).forEach { word ->
            val cleaned = word.trim('.', ',', ';', ':', '!', '?', '"', '\'')
            if (cleaned.isNotEmpty()) {
                byLowercase.putIfAbsent(cleaned.lowercase(), cleaned)
            }
        }
        return title.split(" ").joinToString(" ") { word ->
            byLowercase[word.lowercase()] ?: word
        }
    }

    /** Collapses whitespace and trims the scaffolding words a title never needs. */
    fun tidyTitle(raw: String): String {
        var text = raw.replace(Regex("\\s+"), " ").trim().trim(',', '.', ';', ':')
        text = filler.replace(text, "").trim()
        while (true) {
            val trimmed = danglingPreposition.replace(text, "").trim()
            if (trimmed == text) break
            text = trimmed
        }
        return text.trim().trim(',', '.', ';', ':').trim()
    }
}
