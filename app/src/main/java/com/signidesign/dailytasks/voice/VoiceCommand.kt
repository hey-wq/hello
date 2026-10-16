package com.signidesign.dailytasks.voice

import java.time.LocalDate
import java.time.LocalTime

/**
 * The resolved meaning of one utterance. The parser produces these; the
 * executor is the only thing that touches the database, which keeps the
 * grammar pure and testable.
 */
sealed interface VoiceCommand {

    data class AddTask(
        val title: String,
        val date: LocalDate,
        val start: LocalTime?,
        val durationMinutes: Int?
    ) : VoiceCommand

    /** Read back a day's list. */
    data class ReadDay(val date: LocalDate) : VoiceCommand

    /** The next unfinished timed task from now on. */
    data object WhatsNext : VoiceCommand

    data class SetDone(val query: String, val done: Boolean) : VoiceCommand

    data class DeleteTask(val query: String) : VoiceCommand

    data class MoveTask(val query: String, val date: LocalDate) : VoiceCommand

    /**
     * Put a time on a task. Falls back to creating the task when nothing
     * matches, so "schedule the Delta call at 3" works whether or not the
     * task exists yet.
     */
    data class ScheduleTask(
        val query: String,
        val start: LocalTime?,
        val durationMinutes: Int?,
        val date: LocalDate?
    ) : VoiceCommand

    data class ClearSchedule(val query: String) : VoiceCommand

    data class NoteOnTask(val query: String, val note: String) : VoiceCommand

    data class NoteOnDay(val date: LocalDate, val note: String) : VoiceCommand

    /** Pull every unfinished task from earlier days onto [date]. */
    data class Rollover(val date: LocalDate) : VoiceCommand

    data object SyncNow : VoiceCommand

    data class OpenDay(val date: LocalDate) : VoiceCommand

    data object OpenCalendar : VoiceCommand

    data object OpenSettings : VoiceCommand

    data object Help : VoiceCommand

    /** Close the session without doing anything. */
    data object Stop : VoiceCommand

    data object Affirm : VoiceCommand

    data object Deny : VoiceCommand

    /** "the second one" — answers a disambiguation prompt. */
    data class Choice(val index: Int) : VoiceCommand

    data class NotUnderstood(val utterance: String) : VoiceCommand
}

/** Where the session wants the UI to go once it has spoken. */
sealed interface VoiceNavigation {
    data class Day(val date: LocalDate) : VoiceNavigation
    data object Calendar : VoiceNavigation
    data object Settings : VoiceNavigation
    data object Baby : VoiceNavigation
}

/** A follow-up the session is waiting on; resolved by the next utterance. */
sealed interface PendingAction {
    data class ConfirmDelete(val taskId: Long, val title: String) : PendingAction
    /** "nappy change" with no kind named; answered with wet / dirty / both. */
    data object AskNappyKind : PendingAction

    data class Disambiguate(
        val taskIds: List<Long>,
        val titles: List<String>,
        val original: VoiceCommand
    ) : PendingAction
}

/** The executor's answer: what to say, where to go, what to wait for. */
data class VoiceOutcome(
    val speech: String,
    val pending: PendingAction? = null,
    val navigation: VoiceNavigation? = null,
    val close: Boolean = false
)
