package com.signidesign.dailytasks.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timestamp formats for the spreadsheet.
 *
 * Tasks store zone-naive wall clock (`dayDate` + `startTime`), which is
 * right for "3pm on Friday". A baby event is not that — it is an absolute
 * moment, and every total depends on it, so baby rows carry a full
 * ISO-8601 timestamp **with offset**.
 *
 * Written with the offset rather than as epoch millis so the sheet stays
 * something a person can read and edit, and parsed permissively on the way
 * back because a human will type into that cell sooner or later: offsets,
 * `Z`, a bare local time, or the epoch number all resolve.
 */
object SyncFormats {

    private val withOffset: DateTimeFormatter = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )

    /** "2026-10-16T14:20:00+01:00" */
    fun formatInstant(instant: Instant, zone: ZoneId): String =
        instant.atZone(zone).format(withOffset)

    /**
     * Accepts what the sheet might hold: an offset timestamp, a `Z`
     * timestamp, a bare local datetime (interpreted in [zone], which is
     * what a person typing into the cell means), a `yyyy-MM-dd HH:mm`
     * variant, or raw epoch millis. Returns null for anything else rather
     * than guessing — a dropped row beats a row landing on the wrong day.
     */
    fun parseInstant(raw: String?, zone: ZoneId): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // Epoch millis, as a bare number or a spreadsheet-formatted float.
        val asNumber = text.toDoubleOrNull()
        if (asNumber != null) {
            val millis = asNumber.toLong()
            // Guard against a plain year or a tiny number being read as an
            // instant moments after the epoch.
            return if (millis > 100_000_000_000L) Instant.ofEpochMilli(millis) else null
        }

        runCatching { return OffsetDateTime.parse(text).toInstant() }
        runCatching { return Instant.parse(text) }

        val normalized = text.replace(' ', 'T')
        runCatching { return OffsetDateTime.parse(normalized).toInstant() }
        runCatching {
            return LocalDateTime.parse(normalized).atZone(zone).toInstant()
        }
        // "2026-10-16T14:20" with no seconds is covered above; a date alone
        // means midnight local.
        runCatching {
            return java.time.LocalDate.parse(normalized.take(10))
                .atStartOfDay(zone).toInstant()
        }
        return null
    }
}
