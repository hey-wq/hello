package com.signidesign.dailytasks.voice

import com.signidesign.dailytasks.baby.BabyStats
import com.signidesign.dailytasks.data.BabyEventKind
import com.signidesign.dailytasks.data.BabyLane
import com.signidesign.dailytasks.data.BabyRepository
import com.signidesign.dailytasks.data.SettingsRepository
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.data.TaskRepository
import com.signidesign.dailytasks.notifications.ReminderManager
import com.signidesign.dailytasks.sync.SyncEngine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Applies a parsed command to the same repositories the screens use, and
 * returns what to say about it.
 *
 * The executor owns every write a voice session can make, so the rules that
 * matter live in one place: a spoken title is resolved against a bounded
 * window of days, an unresolved title never falls back to the nearest
 * guess, and a deletion asks first.
 */
class VoiceCommandExecutor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderManager,
    private val syncEngine: SyncEngine,
    private val baby: BabyRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {
    /** How far either side of today a spoken task title is looked for. */
    private val lookBehindDays = 14L
    private val lookAheadDays = 60L

    suspend fun execute(command: VoiceCommand, pending: PendingAction?): VoiceOutcome {
        // A follow-up answer only makes sense against the question that is open.
        if (pending != null) {
            resolvePending(command, pending)?.let { return it }
        }
        return when (command) {
            is VoiceCommand.AddTask -> addTask(command)
            is VoiceCommand.ReadDay -> readDay(command.date)
            VoiceCommand.WhatsNext -> whatsNext()
            is VoiceCommand.SetDone -> withTask(command.query, command) { setDone(it, command.done) }
            is VoiceCommand.DeleteTask -> withTask(command.query, command) { requestDelete(it) }
            is VoiceCommand.MoveTask -> withTask(command.query, command) { move(it, command.date) }
            is VoiceCommand.ScheduleTask -> schedule(command)
            is VoiceCommand.ClearSchedule ->
                withTask(command.query, command) { clearSchedule(it) }
            is VoiceCommand.NoteOnTask ->
                withTask(command.query, command) { noteOnTask(it, command.note) }
            is VoiceCommand.NoteOnDay -> noteOnDay(command)
            is VoiceCommand.Rollover -> rollover(command.date)
            VoiceCommand.SyncNow -> sync()
            is VoiceCommand.OpenDay -> VoiceOutcome(
                speech = "Opening ${Speech.date(command.date, today())}.",
                navigation = VoiceNavigation.Day(command.date),
                close = true
            )
            VoiceCommand.OpenCalendar -> VoiceOutcome(
                speech = "Opening the calendar.",
                navigation = VoiceNavigation.Calendar,
                close = true
            )
            VoiceCommand.OpenSettings -> VoiceOutcome(
                speech = "Opening settings.",
                navigation = VoiceNavigation.Settings,
                close = true
            )
            is BabyStart -> babyStart(command.kind)
            is BabyStop -> babyStop(command.lane)
            is BabyLog -> babyLog(command.kind, command.amountMl)
            BabyNappyUnspecified -> VoiceOutcome(
                speech = "Wet, dirty, or both?",
                pending = PendingAction.AskNappyKind
            )
            is BabyAsk -> babyAsk(command.topic)
            BabyOpen -> VoiceOutcome(
                speech = "Opening the baby log.",
                navigation = VoiceNavigation.Baby,
                close = true
            )
            VoiceCommand.Help -> VoiceOutcome(Speech.help)
            VoiceCommand.Stop -> VoiceOutcome("Okay.", close = true)
            VoiceCommand.Affirm, VoiceCommand.Deny, is VoiceCommand.Choice ->
                VoiceOutcome("There's nothing waiting on an answer.")
            is VoiceCommand.NotUnderstood -> VoiceOutcome(
                "I didn't catch a command there. Say help to hear what I can do."
            )
        }
    }

    private fun today(): LocalDate = clock().toLocalDate()

    // ------------------------------------------------------------- follow-ups

    /** Returns non-null when [command] answers the open [pending] question. */
    private suspend fun resolvePending(
        command: VoiceCommand,
        pending: PendingAction
    ): VoiceOutcome? = when (pending) {
        is PendingAction.ConfirmDelete -> when (command) {
            VoiceCommand.Affirm -> {
                val task = tasks.taskById(pending.taskId)
                if (task == null) {
                    VoiceOutcome("That task is already gone.")
                } else {
                    tasks.delete(task)
                    reminders.cancelTask(task.id)
                    VoiceOutcome("Deleted ${task.title}.")
                }
            }
            VoiceCommand.Deny, VoiceCommand.Stop ->
                VoiceOutcome("Left ${pending.title} where it is.")
            else -> null // a new command supersedes the question
        }

        PendingAction.AskNappyKind -> {
            val kind = BabyVoice.parseNappyAnswer(utteranceOf(command))
            when {
                kind != null -> babyLog(kind, null)
                command == VoiceCommand.Deny || command == VoiceCommand.Stop ->
                    VoiceOutcome("Dropped it.")
                else -> null // a different command supersedes the question
            }
        }

        is PendingAction.Disambiguate -> when (command) {
            is VoiceCommand.Choice -> {
                val id = pending.taskIds.getOrNull(command.index)
                val task = id?.let { tasks.taskById(it) }
                if (task == null) VoiceOutcome("I only had ${pending.taskIds.size} to pick from.")
                else applyToTask(pending.original, task)
            }
            VoiceCommand.Deny, VoiceCommand.Stop -> VoiceOutcome("Dropped it.")
            else -> {
                // Treat a repeat as a narrower query against the same options.
                val query = queryOf(command)
                if (query == null) null else {
                    val options = pending.taskIds.mapNotNull { tasks.taskById(it) }
                    when (val match = TaskMatcher.match(query, options)) {
                        is TaskMatcher.Match.One -> applyToTask(pending.original, match.task)
                        else -> null
                    }
                }
            }
        }
    }

    private fun queryOf(command: VoiceCommand): String? = when (command) {
        is VoiceCommand.SetDone -> command.query
        is VoiceCommand.DeleteTask -> command.query
        is VoiceCommand.MoveTask -> command.query
        is VoiceCommand.ScheduleTask -> command.query
        is VoiceCommand.ClearSchedule -> command.query
        is VoiceCommand.NoteOnTask -> command.query
        else -> null
    }

    // ------------------------------------------------------- task resolution

    /**
     * Resolves [query] to a single task and runs [action] on it. Today's
     * tasks win: "mark the deck review done" means the one in front of you,
     * even when an identically named task sits next week.
     */
    private suspend fun withTask(
        query: String,
        original: VoiceCommand,
        action: suspend (TaskEntity) -> VoiceOutcome
    ): VoiceOutcome {
        if (query.isBlank()) return VoiceOutcome("Which task did you mean?")
        val today = today()

        val todays = tasks.tasksForDayOnce(today)
        when (val match = TaskMatcher.match(query, todays)) {
            is TaskMatcher.Match.One -> return action(match.task)
            is TaskMatcher.Match.Several -> return ambiguous(match.tasks, original)
            TaskMatcher.Match.None -> Unit
        }

        val window = tasks.tasksInRange(
            today.minusDays(lookBehindDays),
            today.plusDays(lookAheadDays)
        ).filter { it.dayDate != today }
        return when (val match = TaskMatcher.match(query, window)) {
            is TaskMatcher.Match.One -> action(match.task)
            is TaskMatcher.Match.Several -> ambiguous(match.tasks, original)
            TaskMatcher.Match.None ->
                VoiceOutcome("I couldn't find a task like \"$query\".")
        }
    }

    private fun ambiguous(options: List<TaskEntity>, original: VoiceCommand): VoiceOutcome {
        val titles = options.map { it.title }
        return VoiceOutcome(
            speech = "I found ${Speech.count(options.size, "match", "matches")}: " +
                "${Speech.list(titles)}. Which one?",
            pending = PendingAction.Disambiguate(
                taskIds = options.map { it.id },
                titles = titles,
                original = original
            )
        )
    }

    /** Re-runs a command once disambiguation has picked the task. */
    private suspend fun applyToTask(command: VoiceCommand, task: TaskEntity): VoiceOutcome =
        when (command) {
            is VoiceCommand.SetDone -> setDone(task, command.done)
            is VoiceCommand.DeleteTask -> requestDelete(task)
            is VoiceCommand.MoveTask -> move(task, command.date)
            is VoiceCommand.ScheduleTask ->
                applySchedule(task, command.start, command.durationMinutes, command.date)
            is VoiceCommand.ClearSchedule -> clearSchedule(task)
            is VoiceCommand.NoteOnTask -> noteOnTask(task, command.note)
            else -> VoiceOutcome("I've lost track of what that was for.")
        }

    // ------------------------------------------------------------- the actions

    private suspend fun addTask(command: VoiceCommand.AddTask): VoiceOutcome {
        val id = tasks.addTask(
            title = command.title,
            date = command.date,
            start = command.start,
            durationMinutes = command.durationMinutes
        )
        if (command.start != null) reminders.resyncTask(id)

        val today = today()
        val whenPart = buildString {
            append(Speech.date(command.date, today))
            command.start?.let { append(" at ${Speech.time(it)}") }
            command.durationMinutes?.let { append(" for ${Speech.duration(it)}") }
        }
        return VoiceOutcome("Added ${command.title}, $whenPart.")
    }

    private suspend fun setDone(task: TaskEntity, done: Boolean): VoiceOutcome {
        if (task.isDone == done) {
            return VoiceOutcome(
                if (done) "${task.title} was already done."
                else "${task.title} is already open."
            )
        }
        tasks.setDone(task, done)
        reminders.resyncTask(task.id)
        return VoiceOutcome(
            if (done) "Done — ${task.title}." else "Reopened ${task.title}."
        )
    }

    private suspend fun requestDelete(task: TaskEntity): VoiceOutcome {
        val confirm = settings.voiceConfirmDeletions.first()
        if (!confirm) {
            tasks.delete(task)
            reminders.cancelTask(task.id)
            return VoiceOutcome("Deleted ${task.title}.")
        }
        return VoiceOutcome(
            speech = "Delete ${task.title}? Yes or no.",
            pending = PendingAction.ConfirmDelete(task.id, task.title)
        )
    }

    private suspend fun move(task: TaskEntity, date: LocalDate): VoiceOutcome {
        if (task.dayDate == date) {
            return VoiceOutcome("${task.title} is already on ${Speech.date(date, today())}.")
        }
        tasks.moveTask(task, date)
        if (task.isTimed) reminders.resyncTask(task.id)
        return VoiceOutcome("Moved ${task.title} to ${Speech.date(date, today())}.")
    }

    /**
     * "Schedule X at 3" where X doesn't exist yet is an add, not an error —
     * it is how people actually speak when the task is new.
     */
    private suspend fun schedule(command: VoiceCommand.ScheduleTask): VoiceOutcome {
        if (command.start == null && command.durationMinutes == null) {
            return VoiceOutcome("What time should I put it at?")
        }
        val today = today()
        val candidates = tasks.tasksInRange(
            today.minusDays(lookBehindDays),
            today.plusDays(lookAheadDays)
        )
        return when (val match = TaskMatcher.match(command.query, candidates)) {
            is TaskMatcher.Match.One ->
                applySchedule(match.task, command.start, command.durationMinutes, command.date)
            is TaskMatcher.Match.Several -> ambiguous(match.tasks, command)
            TaskMatcher.Match.None -> addTask(
                VoiceCommand.AddTask(
                    title = command.query.replaceFirstChar { it.uppercaseChar() },
                    date = command.date ?: today,
                    start = command.start,
                    durationMinutes = command.durationMinutes
                )
            )
        }
    }

    private suspend fun applySchedule(
        task: TaskEntity,
        start: LocalTime?,
        durationMinutes: Int?,
        date: LocalDate?
    ): VoiceOutcome {
        // "move the deck review to Friday at 10" is one utterance, two writes.
        val target = if (date != null && date != task.dayDate) {
            tasks.moveTask(task, date)
            date
        } else {
            task.dayDate
        }

        val current = tasks.taskById(task.id) ?: task
        val newStart = start ?: current.startTime
        if (newStart == null) return VoiceOutcome("What time should I put it at?")
        val newDuration = durationMinutes ?: current.durationMinutes
        tasks.setSchedule(current, newStart, newDuration)
        reminders.resyncTask(task.id)

        val detail = buildString {
            append(Speech.date(target, today()))
            append(" at ${Speech.time(newStart)}")
            newDuration?.let { append(" for ${Speech.duration(it)}") }
        }
        return VoiceOutcome("${task.title}, $detail.")
    }

    private suspend fun clearSchedule(task: TaskEntity): VoiceOutcome {
        if (!task.isTimed) return VoiceOutcome("${task.title} has no time on it.")
        tasks.setSchedule(task, null, null)
        reminders.cancelTask(task.id)
        return VoiceOutcome("Cleared the time on ${task.title}.")
    }

    private suspend fun noteOnTask(task: TaskEntity, note: String): VoiceOutcome {
        val existing = task.note
        val merged = if (existing.isNullOrBlank()) note else "$existing\n$note"
        tasks.setNote(task, merged)
        return VoiceOutcome("Noted on ${task.title}.")
    }

    private suspend fun noteOnDay(command: VoiceCommand.NoteOnDay): VoiceOutcome {
        val existing = tasks.dayNoteOnce(command.date)?.content
        val merged =
            if (existing.isNullOrBlank()) command.note else "$existing\n${command.note}"
        tasks.saveDayNote(command.date, merged)
        return VoiceOutcome("Added to the notes for ${Speech.date(command.date, today())}.")
    }

    private suspend fun rollover(date: LocalDate): VoiceOutcome {
        val moved = tasks.rolloverPastTo(date)
        moved.filter { it.isTimed }.forEach { reminders.resyncTask(it.id) }
        return if (moved.isEmpty()) VoiceOutcome("Nothing unfinished to bring forward.")
        else VoiceOutcome(
            "Brought ${Speech.count(moved.size, "task")} forward to " +
                "${Speech.date(date, today())}."
        )
    }

    private suspend fun sync(): VoiceOutcome = when (val result = syncEngine.sync()) {
        is SyncEngine.SyncResult.Success ->
            VoiceOutcome("Synced. Sent ${result.pushed}, received ${result.pulled}.")
        is SyncEngine.SyncResult.Error -> VoiceOutcome("Sync failed. ${result.message}")
        SyncEngine.SyncResult.NotConfigured ->
            VoiceOutcome("Sheets sync isn't set up yet. Add the URL and token in settings.")
    }

    // ------------------------------------------------------------------- baby

    private fun nowInstant(): Instant = clock().atZone(zone).toInstant()

    private suspend fun babyStart(kind: BabyEventKind): VoiceOutcome {
        val at = nowInstant()
        // Switching sides mid-feed is one spoken command; say what it closed
        // so an accidental switch is obvious immediately.
        val previous = if (kind.lane != BabyLane.EVENT) {
            baby.openSessionOnce(kind.lane)?.takeIf { it.kind != kind }
        } else {
            null
        }
        baby.startSession(kind, at)

        val closed = previous?.let { earlier ->
            " Ended ${earlier.kind.label.lowercase()} after " +
                BabyStats.speakDuration(Duration.between(earlier.startAt, at)) + "."
        } ?: ""

        return VoiceOutcome(
            when (kind) {
                BabyEventKind.SLEEP -> "Sleep started.$closed"
                BabyEventKind.PUMP -> "Pumping started.$closed"
                else -> "${kind.label} side, started.$closed"
            }
        )
    }

    private suspend fun babyStop(lane: BabyLane): VoiceOutcome {
        val at = nowInstant()
        val closed = baby.endSession(lane, at)
            ?: return VoiceOutcome(
                if (lane == BabyLane.SLEEP) "Nothing was logged as sleeping."
                else "Nothing was logged as feeding."
            )
        val length = BabyStats.speakDuration(
            Duration.between(closed.startAt, closed.endAt ?: at)
        )
        return VoiceOutcome(
            when (closed.kind) {
                BabyEventKind.SLEEP -> "Slept $length."
                BabyEventKind.PUMP -> "Pumped for $length."
                else -> "$length on the ${closed.kind.label.lowercase()}."
            }
        )
    }

    private suspend fun babyLog(kind: BabyEventKind, amountMl: Int?): VoiceOutcome {
        baby.logEvent(kind, nowInstant(), amountMl)
        return VoiceOutcome(
            when {
                kind == BabyEventKind.BOTTLE && amountMl != null ->
                    "Logged a ${amountMl}ml bottle."
                kind == BabyEventKind.BOTTLE -> "Bottle logged."
                kind.isNappy -> "${kind.label} nappy logged."
                else -> "${kind.label} logged."
            }
        )
    }

    private suspend fun babyAsk(topic: BabyTopic): VoiceOutcome {
        val now = nowInstant()
        val day = clock().toLocalDate()
        val events = baby.eventsForDayOnce(day, zone)
        val summary = BabyStats.summarise(events, day, zone, now)

        return VoiceOutcome(
            when (topic) {
                BabyTopic.SLEEP -> if (summary.sleepTime.isZero) {
                    "No sleep logged today yet."
                } else {
                    "${BabyStats.speakDuration(summary.sleepTime)} of sleep today, " +
                        "across ${summary.sleepSessions} " +
                        (if (summary.sleepSessions == 1) "sleep" else "sleeps") +
                        ", the longest ${BabyStats.speakDuration(summary.longestSleep)}."
                }

                BabyTopic.FEEDS -> if (summary.feedCount == 0) {
                    "No feeds logged today yet."
                } else {
                    buildString {
                        append("${summary.feedCount} feeds today")
                        if (!summary.breastTime.isZero) {
                            append(", ${BabyStats.speakDuration(summary.breastTime)} at the breast")
                        }
                        if (summary.bottleMl > 0) append(", ${summary.bottleMl}ml by bottle")
                        append(".")
                    }
                }

                BabyTopic.LAST_FEED -> lastFeedAnswer(now)

                BabyTopic.NAPPIES -> if (summary.nappyTotal == 0) {
                    "No nappies logged today yet."
                } else {
                    "${summary.nappyTotal} nappies today, " +
                        "${summary.nappyWet} wet and ${summary.nappyDirty} dirty."
                }

                BabyTopic.STATUS -> statusAnswer(now)

                BabyTopic.SUMMARY -> buildString {
                    append("${summary.feedCount} feeds")
                    if (!summary.breastTime.isZero) {
                        append(", ${BabyStats.speakDuration(summary.breastTime)} at the breast")
                    }
                    if (summary.bottleMl > 0) append(", ${summary.bottleMl}ml by bottle")
                    append(". ${BabyStats.speakDuration(summary.sleepTime)} of sleep")
                    if (!summary.longestSleep.isZero) {
                        append(", longest ${BabyStats.speakDuration(summary.longestSleep)}")
                    }
                    append(". ${summary.nappyTotal} nappies.")
                    summary.lastFeedAt?.let { last ->
                        append(
                            " Last feed " +
                                BabyStats.speakDuration(Duration.between(last, now)) + " ago."
                        )
                    }
                }
            }
        )
    }

    private suspend fun lastFeedAnswer(now: Instant): String {
        val last = baby.lastOf(
            BabyEventKind.BREAST_LEFT,
            BabyEventKind.BREAST_RIGHT,
            BabyEventKind.BOTTLE,
            BabyEventKind.SOLIDS
        ) ?: return "No feed logged yet."

        if (last.endAt == null) {
            return "Still feeding on the ${last.kind.label.lowercase()}, " +
                "${BabyStats.speakDuration(Duration.between(last.startAt, now))} so far."
        }
        val since = BabyStats.speakDuration(Duration.between(last.endAt, now))
        val what = when {
            last.kind.isBreast -> "the ${last.kind.label.lowercase()} breast"
            last.kind == BabyEventKind.BOTTLE ->
                last.amountMl?.let { "a ${it}ml bottle" } ?: "a bottle"
            else -> last.kind.label.lowercase()
        }
        return "Last feed was $since ago, $what."
    }

    private suspend fun statusAnswer(now: Instant): String {
        val sleep = baby.openSleepOnce()
        val feed = baby.openFeedOnce()
        val parts = mutableListOf<String>()
        sleep?.let {
            parts += "asleep for " +
                BabyStats.speakDuration(Duration.between(it.startAt, now))
        }
        feed?.let {
            val label = when (it.kind) {
                BabyEventKind.PUMP -> "pumping for "
                else -> "feeding on the ${it.kind.label.lowercase()} for "
            }
            parts += label + BabyStats.speakDuration(Duration.between(it.startAt, now))
        }
        return if (parts.isEmpty()) "Nothing running right now."
        else Speech.list(parts).replaceFirstChar { it.uppercaseChar() } + "."
    }

    /** The raw words behind a command, for answering a free-form question. */
    private fun utteranceOf(command: VoiceCommand): String = when (command) {
        is VoiceCommand.NotUnderstood -> command.utterance
        is VoiceCommand.AddTask -> command.title
        is BabyLog -> command.kind.label
        else -> ""
    }

    // ------------------------------------------------------------- the reads

    private suspend fun readDay(date: LocalDate): VoiceOutcome {
        val today = today()
        val list = tasks.tasksForDayOnce(date)
        val label = Speech.date(date, today)
        if (list.isEmpty()) return VoiceOutcome("Nothing on $label.")

        val open = list.filter { !it.isDone }
        val doneCount = list.size - open.size
        val headline = buildString {
            append("${Speech.count(list.size, "task")} $label")
            if (doneCount > 0) append(", $doneCount done")
            append(".")
        }
        if (open.isEmpty()) return VoiceOutcome("$headline All clear.")

        val spoken = open.take(6).map { task ->
            val start = task.startTime
            if (start != null) "${Speech.time(start)}, ${task.title}" else task.title
        }
        val overflow = open.size - spoken.size
        val body = Speech.list(spoken) +
            if (overflow > 0) ", and $overflow more" else ""
        return VoiceOutcome("$headline $body.")
    }

    private suspend fun whatsNext(): VoiceOutcome {
        val now = clock()
        val today = now.toLocalDate()
        val next = tasks.upcomingTimedTasks(today)
            .filter { task ->
                val start = task.startTime ?: return@filter false
                LocalDateTime.of(task.dayDate, start).isAfter(now)
            }
            .minByOrNull { task ->
                LocalDateTime.of(task.dayDate, task.startTime ?: LocalTime.MIDNIGHT)
            }

        if (next != null) {
            val start = next.startTime ?: LocalTime.MIDNIGHT
            val whenPart =
                if (next.dayDate == today) "at ${Speech.time(start)}"
                else "${Speech.date(next.dayDate, today)} at ${Speech.time(start)}"
            return VoiceOutcome("Next up, ${next.title}, $whenPart.")
        }

        val openToday = tasks.tasksForDayOnce(today).filter { !it.isDone }
        return if (openToday.isEmpty()) VoiceOutcome("Nothing scheduled ahead of you.")
        else VoiceOutcome(
            "Nothing timed left today. Still open: ${Speech.list(openToday.take(3).map { it.title })}."
        )
    }
}
