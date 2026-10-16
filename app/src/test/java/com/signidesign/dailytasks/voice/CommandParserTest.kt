package com.signidesign.dailytasks.voice

import com.signidesign.dailytasks.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The grammar is the part of the voice layer that can go wrong silently —
 * a mis-parsed date puts a task on the wrong day and nobody notices until
 * the meeting is missed — so it carries the tests. Everything below runs on
 * the JVM: no device, no recognizer, no synthesiser.
 */
class CommandParserTest {

    /** A Friday, so weekday arithmetic has something to get wrong. */
    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun parse(utterance: String) = CommandParser.parse(utterance, today)

    // ------------------------------------------------------------------- adds

    @Test
    fun `add with date time and duration strips all three from the title`() {
        val command = parse("add call Delta Controls tomorrow at 3 for 45 minutes")
        command as VoiceCommand.AddTask
        assertEquals("Call Delta Controls", command.title)
        assertEquals(today.plusDays(1), command.date)
        assertEquals(LocalTime.of(15, 0), command.start)
        assertEquals(45, command.durationMinutes)
    }

    @Test
    fun `remind me to is an add`() {
        val command = parse("remind me to send the Novonix invoice on monday")
        command as VoiceCommand.AddTask
        assertEquals("Send the Novonix invoice", command.title)
        assertEquals(LocalDate.of(2026, 10, 19), command.date)
        assertNull(command.start)
    }

    @Test
    fun `next friday skips the current week`() {
        val command = parse("add board pack next friday")
        command as VoiceCommand.AddTask
        assertEquals(LocalDate.of(2026, 10, 23), command.date)
    }

    @Test
    fun `bare weekday resolves forwards`() {
        val command = parse("add board pack on monday")
        command as VoiceCommand.AddTask
        assertEquals(LocalDate.of(2026, 10, 19), command.date)
    }

    @Test
    fun `number words parse as durations and hours`() {
        val command = parse("add deck review at half past three for forty five minutes")
        command as VoiceCommand.AddTask
        assertEquals("Deck review", command.title)
        assertEquals(LocalTime.of(15, 30), command.start)
        assertEquals(45, command.durationMinutes)
    }

    @Test
    fun `an hour and a half is ninety minutes`() {
        val command = parse("add studio time at 10am for an hour and a half")
        command as VoiceCommand.AddTask
        assertEquals(LocalTime.of(10, 0), command.start)
        assertEquals(90, command.durationMinutes)
    }

    @Test
    fun `morning hours stay in the morning and afternoon hours move to pm`() {
        val morning = parse("add standup at 9") as VoiceCommand.AddTask
        assertEquals(LocalTime.of(9, 0), morning.start)
        val afternoon = parse("add client call at 4") as VoiceCommand.AddTask
        assertEquals(LocalTime.of(16, 0), afternoon.start)
    }

    @Test
    fun `explicit 24 hour time is taken as given`() {
        val command = parse("add retro at 15:30") as VoiceCommand.AddTask
        assertEquals(LocalTime.of(15, 30), command.start)
    }

    @Test
    fun `a verbless utterance is never silently turned into a task`() {
        assertTrue(parse("the Nestle thing") is VoiceCommand.NotUnderstood)
    }

    @Test
    fun `dictation takes the whole utterance as a title`() {
        val command = CommandParser.parse("the Nestle thing", today, dictation = true)
        command as VoiceCommand.AddTask
        assertEquals("The Nestle thing", command.title)
        assertEquals(today, command.date)
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `reads resolve their own day`() {
        assertEquals(
            VoiceCommand.ReadDay(today),
            parse("what's on today")
        )
        assertEquals(
            VoiceCommand.ReadDay(today.plusDays(1)),
            parse("what do I have tomorrow")
        )
        assertEquals(VoiceCommand.ReadDay(today), parse("read my day"))
    }

    @Test
    fun `whats next is its own command`() {
        assertEquals(VoiceCommand.WhatsNext, parse("what's next"))
        assertEquals(VoiceCommand.WhatsNext, parse("what's coming up"))
    }

    // ----------------------------------------------------------------- edits

    @Test
    fun `completion phrasings all resolve to set done`() {
        listOf(
            "mark the deck review as done",
            "complete the deck review",
            "check off the deck review",
            "the deck review is done"
        ).forEach { utterance ->
            val command = parse(utterance)
            command as VoiceCommand.SetDone
            assertTrue(command.done)
            assertTrue(command.query.contains("deck review"))
        }
    }

    @Test
    fun `uncheck reopens`() {
        val command = parse("uncheck the deck review")
        command as VoiceCommand.SetDone
        assertEquals(false, command.done)
    }

    @Test
    fun `move without a named day means tomorrow`() {
        val command = parse("push the invoice")
        command as VoiceCommand.MoveTask
        assertEquals("invoice", command.query)
        assertEquals(today.plusDays(1), command.date)
    }

    @Test
    fun `move to a named day keeps the title clean`() {
        val command = parse("move the board pack to next friday")
        command as VoiceCommand.MoveTask
        assertEquals("board pack", command.query)
        assertEquals(LocalDate.of(2026, 10, 23), command.date)
    }

    @Test
    fun `delete and cancel both delete`() {
        assertEquals(VoiceCommand.DeleteTask("invoice"), parse("delete the invoice"))
        assertEquals(VoiceCommand.DeleteTask("invoice"), parse("cancel the invoice"))
    }

    @Test
    fun `schedule carries time and duration`() {
        val command = parse("schedule the deck review at 2pm for 30 minutes")
        command as VoiceCommand.ScheduleTask
        assertEquals("deck review", command.query)
        assertEquals(LocalTime.of(14, 0), command.start)
        assertEquals(30, command.durationMinutes)
    }

    @Test
    fun `notes attach to a task or a day`() {
        val onTask = parse("add a note to the deck review saying send the fonts too")
        onTask as VoiceCommand.NoteOnTask
        assertEquals("deck review", onTask.query)
        assertEquals("Send the fonts too", onTask.note)

        val onDay = parse("note for tomorrow saying studio closed")
        onDay as VoiceCommand.NoteOnDay
        assertEquals(today.plusDays(1), onDay.date)
        assertEquals("Studio closed", onDay.note)
    }

    // ----------------------------------------------------------- housekeeping

    @Test
    fun `short answers are recognised`() {
        assertEquals(VoiceCommand.Affirm, parse("yes"))
        assertEquals(VoiceCommand.Deny, parse("no"))
        assertEquals(VoiceCommand.Stop, parse("never mind"))
        assertEquals(VoiceCommand.Help, parse("what can you do"))
        assertEquals(VoiceCommand.SyncNow, parse("sync now"))
        assertEquals(VoiceCommand.Choice(1), parse("the second one"))
    }

    // ----------------------------------------------------------------- chains

    @Test
    fun `then splits an utterance into steps`() {
        val chain = CommandParser.parseChain(
            "add call Fidelity at 11 then mark the invoice done then what's next",
            today
        )
        assertEquals(3, chain.size)
        assertTrue(chain[0] is VoiceCommand.AddTask)
        assertTrue(chain[1] is VoiceCommand.SetDone)
        assertEquals(VoiceCommand.WhatsNext, chain[2])
    }

    @Test
    fun `and alone does not split a title`() {
        val chain = CommandParser.parseChain("add call Delta and Nestle", today)
        assertEquals(1, chain.size)
        val command = chain[0] as VoiceCommand.AddTask
        assertEquals("Call Delta and Nestle", command.title)
    }

    @Test
    fun `an unparsable step falls back to the whole utterance`() {
        // "then" inside a title must not shred the command.
        val chain = CommandParser.parseChain("add review then sign the contract", today)
        assertEquals(1, chain.size)
        assertTrue(chain[0] is VoiceCommand.AddTask)
    }

    // ------------------------------------------------------------- date edges

    @Test
    fun `absolute and offset dates resolve`() {
        fun dateOf(utterance: String) = (parse(utterance) as VoiceCommand.AddTask).date
        assertEquals(today.plusDays(3), dateOf("add site visit in 3 days"))
        assertEquals(today.plusDays(2), dateOf("add budget call the day after tomorrow"))
        assertEquals(LocalDate.of(2026, 10, 24), dateOf("add quarterly review on the 24th"))
        assertEquals(LocalDate.of(2026, 12, 3), dateOf("add holiday party december 3"))
    }

    @Test
    fun `day parts carry both a day and a time`() {
        val command = parse("i need to approve the AstraZeneca artwork tonight")
        command as VoiceCommand.AddTask
        assertEquals("Approve the AstraZeneca artwork", command.title)
        assertEquals(today, command.date)
        assertEquals(LocalTime.of(20, 0), command.start)
    }

    @Test
    fun `noon and spelled durations resolve`() {
        val noon = parse("add lunch at noon") as VoiceCommand.AddTask
        assertEquals(LocalTime.NOON, noon.start)
        val half = parse("add press release for half an hour at 2pm") as VoiceCommand.AddTask
        assertEquals(30, half.durationMinutes)
        assertEquals(LocalTime.of(14, 0), half.start)
        assertEquals("Press release", half.title)
    }

    @Test
    fun `schedule can move a task and time it in one utterance`() {
        val command = parse("schedule the site audit tomorrow at 10 for an hour")
        command as VoiceCommand.ScheduleTask
        assertEquals("site audit", command.query)
        assertEquals(today.plusDays(1), command.date)
        assertEquals(LocalTime.of(10, 0), command.start)
        assertEquals(60, command.durationMinutes)
    }

    @Test
    fun `clearing a time has two phrasings`() {
        assertEquals(
            VoiceCommand.ClearSchedule("deck review"),
            parse("unschedule the deck review")
        )
        assertEquals(
            VoiceCommand.ClearSchedule("deck review"),
            parse("clear the time on the deck review")
        )
    }

    @Test
    fun `navigation commands resolve`() {
        assertEquals(VoiceCommand.OpenCalendar, parse("open the calendar"))
        assertEquals(VoiceCommand.OpenSettings, parse("open settings"))
        assertEquals(
            VoiceCommand.OpenDay(LocalDate.of(2026, 10, 19)),
            parse("show me monday")
        )
        assertEquals(VoiceCommand.Rollover(today), parse("move everything unfinished to today"))
    }

    // ---------------------------------------------------------------- matcher

    private fun task(id: Long, title: String) = TaskEntity(
        id = id,
        title = title,
        dayDate = today,
        createdAt = Instant.EPOCH
    )

    @Test
    fun `matcher tolerates dropped and mangled words`() {
        val tasks = listOf(
            task(1, "Polish the Delta Controls deck"),
            task(2, "Send Novonix invoice"),
            task(3, "AstraZeneca compliance review")
        )
        val match = TaskMatcher.match("delta deck", tasks)
        match as TaskMatcher.Match.One
        assertEquals(1L, match.task.id)
    }

    @Test
    fun `matcher refuses rather than guessing`() {
        val tasks = listOf(task(1, "Polish the Delta Controls deck"))
        assertEquals(TaskMatcher.Match.None, TaskMatcher.match("book the flights", tasks))
    }

    @Test
    fun `matcher reports a tie instead of picking one`() {
        val tasks = listOf(
            task(1, "Client review"),
            task(2, "Client review")
        )
        assertTrue(TaskMatcher.match("client review", tasks) is TaskMatcher.Match.Several)
    }
}
