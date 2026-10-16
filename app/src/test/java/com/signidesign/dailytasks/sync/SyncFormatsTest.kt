package com.signidesign.dailytasks.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * A spreadsheet cell is a text field a tired person will type into, and
 * Sheets itself rewrites what it thinks are dates. Both sides of that are
 * tested here, because the failure is silent: a feed arrives on the wrong
 * day and the totals are quietly wrong for ever after.
 */
class SyncFormatsTest {

    private val london: ZoneId = ZoneId.of("Europe/London")
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    @Test
    fun `an instant round-trips through the sheet format`() {
        val instant = Instant.parse("2026-10-16T13:20:00Z")
        val written = SyncFormats.formatInstant(instant, london)
        // British Summer Time in October: 14:20 at +01:00.
        assertEquals("2026-10-16T14:20:00+01:00", written)
        assertEquals(instant, SyncFormats.parseInstant(written, london))
    }

    @Test
    fun `the offset is what makes it unambiguous across zones`() {
        val instant = Instant.parse("2026-10-16T13:20:00Z")
        val written = SyncFormats.formatInstant(instant, london)
        // A second phone in another zone reads back the same moment.
        assertEquals(instant, SyncFormats.parseInstant(written, sydney))
    }

    @Test
    fun `a hand-typed local time is read in the reader's zone`() {
        // No offset: the person typing meant their own clock.
        assertEquals(
            Instant.parse("2026-10-16T13:20:00Z"),
            SyncFormats.parseInstant("2026-10-16 14:20", london)
        )
        assertEquals(
            Instant.parse("2026-10-16T13:20:00Z"),
            SyncFormats.parseInstant("2026-10-16T14:20", london)
        )
        assertEquals(
            Instant.parse("2026-10-16T13:20:00Z"),
            SyncFormats.parseInstant("2026-10-16T14:20:00", london)
        )
    }

    @Test
    fun `zulu and offset forms both parse`() {
        val instant = Instant.parse("2026-10-16T13:20:00Z")
        assertEquals(instant, SyncFormats.parseInstant("2026-10-16T13:20:00Z", london))
        assertEquals(instant, SyncFormats.parseInstant("2026-10-16T15:20:00+02:00", london))
    }

    @Test
    fun `epoch millis still parse, for a cell that lost its formatting`() {
        val instant = Instant.parse("2026-10-16T13:20:00Z")
        assertEquals(
            instant,
            SyncFormats.parseInstant(instant.toEpochMilli().toString(), london)
        )
        // Sheets hands numbers back as floats often enough to matter.
        assertEquals(
            instant,
            SyncFormats.parseInstant("${instant.toEpochMilli()}.0", london)
        )
    }

    @Test
    fun `a date alone means midnight local`() {
        assertEquals(
            Instant.parse("2026-10-15T23:00:00Z"),
            SyncFormats.parseInstant("2026-10-16", london)
        )
    }

    @Test
    fun `nonsense is refused rather than guessed at`() {
        assertNull(SyncFormats.parseInstant("", london))
        assertNull(SyncFormats.parseInstant(null, london))
        assertNull(SyncFormats.parseInstant("   ", london))
        assertNull(SyncFormats.parseInstant("tomorrow morning", london))
        assertNull(SyncFormats.parseInstant("not a date", london))
        // A stray small number is not an instant three seconds after 1970.
        assertNull(SyncFormats.parseInstant("2026", london))
        assertNull(SyncFormats.parseInstant("120", london))
    }

    @Test
    fun `winter dates carry the winter offset`() {
        val instant = Instant.parse("2026-01-15T09:05:00Z")
        assertEquals(
            "2026-01-15T09:05:00Z",
            SyncFormats.formatInstant(instant, london)
        )
        assertEquals(
            instant,
            SyncFormats.parseInstant(SyncFormats.formatInstant(instant, london), london)
        )
    }
}
