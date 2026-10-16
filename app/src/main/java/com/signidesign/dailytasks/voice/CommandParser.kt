package com.signidesign.dailytasks.voice

import java.time.LocalDate

/**
 * Turns a recognised utterance into a [VoiceCommand].
 *
 * Two rules shape the grammar. First, an utterance with no recognised verb
 * is never silently turned into a task — a mis-heard fragment becoming a
 * line in someone's day is worse than being asked to repeat it. The one
 * exception is the dictation entry point, which is explicitly "take this
 * down". Second, every branch strips its date / time / duration phrases out
 * of the title, so "add call Delta Controls tomorrow at 3 for 45 minutes"
 * stores the title alone.
 */
object CommandParser {

    // Order matters: the short, whole-utterance responses are tested before
    // anything that takes a free-text tail.
    private val affirm = Regex(
        "^(?:yes|yeah|yep|yup|sure|ok|okay|do it|confirm|confirmed|go ahead|" +
            "please do|correct|that's right|affirmative)\\.?$"
    )
    private val deny = Regex(
        "^(?:no|nope|nah|don't|do not|negative|cancel that|forget it|leave it)\\.?$"
    )
    private val stop = Regex(
        "^(?:stop|cancel|quit|close|exit|never ?mind|nothing|that's all|thats all|done)\\.?$"
    )
    private val help = Regex(
        "^(?:help|what can you do|what can i say|what do you do|commands|" +
            "which commands|what are my options)\\??$"
    )
    private val choice = Regex(
        "^(?:the )?(?:first|1st|one|second|2nd|two|third|3rd|three|number \\d)(?: one)?\\.?$"
    )

    private val syncNow = Regex("^(?:sync|synchronise|synchronize)(?: now| up| the sheet| with .*)?\\.?$")
    private val rollover = Regex(
        "(?:roll ?over|bring (?:everything|them|it|the rest)?.*(?:forward|to today)|" +
            "move (?:everything|all|the) unfinished|catch up (?:my|the) list)"
    )

    private val openCalendar = Regex("^(?:open|show|go to|jump to|take me to)?\\s*(?:the )?(?:calendar|month|month view)\\.?$")
    private val openSettings = Regex("^(?:open|show|go to|jump to|take me to)?\\s*(?:the )?settings\\.?$")
    private val openDay = Regex("^(?:open|show|show me|go to|jump to|take me to|switch to)\\b")

    private val readDay = Regex(
        "^(?:what(?:'s| is| are)?|whats)?\\s*(?:on|up|happening|planned|my (?:day|plan|list|schedule|tasks))\\b|" +
            "^(?:read|run through|go through|brief me on|tell me about|summari[sz]e|recap)\\b|" +
            "^how many tasks\\b|^what do i have\\b|^what have i got\\b|^do i have anything\\b"
    )
    private val whatsNext = Regex(
        "^(?:what(?:'s| is)?|whats)\\s*(?:up )?next\\b|^what(?:'s| is) coming up\\b|" +
            "^next (?:task|up|thing)\\b|^what am i doing next\\b"
    )

    private val addVerb = Regex(
        "^(?:add|create|make|new|note down|put down|write down|jot down|" +
            "remind me to|remember to|don'?t forget to|i need to|i have to|" +
            "i must|log)\\b(?: a| an| the)?(?: new)?(?: task| item| to-?do| reminder)?(?: to| that| called| for)?\\b"
    )

    private val doneVerb = Regex(
        "^(?:complete|completed|finish|finished|check off|check|tick off|tick|" +
            "cross off|mark off|close out|done with|i (?:finished|completed|did|have done))\\b(?: the| my)?"
    )
    private val markDone = Regex(
        "^(?:mark|set|flag)\\b(?: the| my)? (.+?) (?:as )?(?:done|complete|completed|finished|off)\\.?$"
    )
    private val markNotDone = Regex(
        "^(?:mark|set|flag)\\b(?: the| my)? (.+?) (?:as )?(?:not done|undone|incomplete|unfinished|open)\\.?$"
    )
    private val undoneVerb = Regex(
        "^(?:uncheck|un-?check|unmark|un-?mark|un-?complete|reopen|re-?open|untick|un-?tick)\\b(?: the| my)?"
    )
    private val trailingDone = Regex("^(.+?) (?:is|are|was) (?:done|complete|completed|finished)\\.?$")

    private val deleteVerb = Regex(
        "^(?:delete|remove|drop|get rid of|scratch|bin|throw out|take off)\\b(?: the| my)?"
    )
    private val cancelTask = Regex("^cancel\\b(?: the| my)? (.+)$")

    private val moveVerb = Regex(
        "^(?:move|push|shift|bump|reschedule|postpone|defer|carry)\\b(?: the| my)?"
    )
    private val scheduleVerb = Regex(
        "^(?:schedule|set|book|block|block out|put|pencil in|pencil)\\b(?: the| my| in)?"
    )
    private val unscheduleVerb = Regex(
        "^(?:unschedule|un-?schedule|clear the time (?:on|for|from)|remove the time (?:on|for|from)|" +
            "untime|make .* untimed)\\b"
    )

    private val noteOnTask = Regex(
        "^(?:add |make |leave |put )?(?:a )?note (?:on|to|for|against) (.+?) " +
            "(?:saying|that says|that reads|with|:) (.+)$"
    )
    private val noteOnDay = Regex(
        "^(?:add |make |leave |put )?(?:a )?(?:day )?note (?:for|on) " +
            "(today|tomorrow|[a-z0-9 ]+?) (?:saying|that says|that reads|with|:) (.+)$"
    )

    /**
     * Splits a chained utterance into its steps.
     *
     * Only an explicit sequencer separates steps — "then", "after that",
     * "also". Bare "and" is left alone on purpose: "add call Delta and
     * Nestlé" is one task title, and splitting it would quietly shred the
     * title rather than fail visibly. If any step comes back unparsed the
     * whole utterance is re-read as a single command, so a title that
     * happens to contain "then" still works.
     */
    fun parseChain(
        utterance: String,
        today: LocalDate,
        dictation: Boolean = false
    ): List<VoiceCommand> {
        val single = parse(utterance, today, dictation)
        if (dictation) return listOf(single)

        val steps = chainSeparator.split(normalize(utterance))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (steps.size < 2) return listOf(single)

        val parsed = steps.map { parse(it, today, dictation = false) }
        return if (parsed.any { it is VoiceCommand.NotUnderstood }) listOf(single)
        else parsed
    }

    private val chainSeparator = Regex(
        ",? (?:and then|then|after that|and after that|and also|also|next,|next up,) "
    )

    /**
     * @param utterance raw recognition output.
     * @param today the day the session started on; every relative date
     *   ("tomorrow") resolves against it.
     * @param dictation true for the "take this down" entry point, where the
     *   whole utterance is a task title with no verb required.
     */
    fun parse(
        utterance: String,
        today: LocalDate,
        dictation: Boolean = false
    ): VoiceCommand {
        val text = normalize(utterance)
        if (text.isBlank()) return VoiceCommand.NotUnderstood(utterance)

        if (dictation) return addFrom(text, today, utterance)

        if (affirm.matches(text)) return VoiceCommand.Affirm
        if (deny.matches(text)) return VoiceCommand.Deny
        if (stop.matches(text)) return VoiceCommand.Stop
        if (help.matches(text)) return VoiceCommand.Help
        choice.find(text)?.let { return VoiceCommand.Choice(choiceIndex(it.value)) }

        // Baby grammar first: the collisions all run that way round
        // ("log a bottle" would otherwise become a task called "a bottle").
        BabyVoice.parse(text)?.let { return it }

        if (syncNow.matches(text)) return VoiceCommand.SyncNow
        if (rollover.containsMatchIn(text)) return VoiceCommand.Rollover(today)
        if (openCalendar.matches(text)) return VoiceCommand.OpenCalendar
        if (openSettings.matches(text)) return VoiceCommand.OpenSettings

        // Day notes first: "note for tomorrow saying X" also satisfies the
        // task pattern, and only the date test tells them apart.
        noteOnDay.find(text)?.let { match ->
            val date = Phrases.extractDate(match.groupValues[1], today)?.value
            val note = match.groupValues[2].trim()
            if (date != null && note.isNotBlank()) {
                return VoiceCommand.NoteOnDay(date, prose(note, utterance))
            }
        }
        noteOnTask.find(text)?.let { match ->
            val query = Phrases.tidyQuery(match.groupValues[1])
            val note = match.groupValues[2].trim()
            if (query.isNotBlank() && note.isNotBlank()) {
                return VoiceCommand.NoteOnTask(query, prose(note, utterance))
            }
        }

        if (whatsNext.containsMatchIn(text)) return VoiceCommand.WhatsNext
        if (readDay.containsMatchIn(text)) {
            val date = Phrases.extractDate(text, today)?.value ?: today
            return VoiceCommand.ReadDay(date)
        }

        markDone.find(text)?.let {
            return VoiceCommand.SetDone(Phrases.tidyQuery(it.groupValues[1]), done = true)
        }
        markNotDone.find(text)?.let {
            return VoiceCommand.SetDone(Phrases.tidyQuery(it.groupValues[1]), done = false)
        }
        trailingDone.find(text)?.let {
            return VoiceCommand.SetDone(Phrases.tidyQuery(it.groupValues[1]), done = true)
        }
        undoneVerb.find(text)?.let {
            val tail = Phrases.tidyQuery(text.removeRange(it.range))
            if (tail.isNotBlank()) return VoiceCommand.SetDone(tail, done = false)
        }
        doneVerb.find(text)?.let {
            val tail = Phrases.tidyQuery(text.removeRange(it.range))
            if (tail.isNotBlank()) return VoiceCommand.SetDone(tail, done = true)
        }

        unscheduleVerb.find(text)?.let { match ->
            val tail = Phrases.tidyQuery(
                text.removeRange(match.range).replace("untimed", "")
            )
            if (tail.isNotBlank()) return VoiceCommand.ClearSchedule(tail)
        }

        cancelTask.find(text)?.let {
            return VoiceCommand.DeleteTask(Phrases.tidyQuery(it.groupValues[1]))
        }
        deleteVerb.find(text)?.let { match ->
            val tail = Phrases.tidyQuery(text.removeRange(match.range))
            if (tail.isNotBlank()) return VoiceCommand.DeleteTask(tail)
        }

        moveVerb.find(text)?.let { match ->
            val rest = text.removeRange(match.range)
            val date = Phrases.extractDate(rest, today)
            // "push the deck review" with no day named means tomorrow.
            val target = date?.value ?: today.plusDays(1)
            val query = Phrases.tidyQuery(
                Phrases.removeRanges(rest, listOfNotNull(date?.range))
                    .replace(Regex("\\b(?:to|until|till|over to|into)\\b"), " ")
            )
            if (query.isNotBlank()) return VoiceCommand.MoveTask(query, target)
        }

        scheduleVerb.find(text)?.let { match ->
            val rest = text.removeRange(match.range)
            val time = Phrases.extractTime(rest)
            val duration = Phrases.extractDuration(rest)
            val date = Phrases.extractDate(rest, today)
            if (time != null || duration != null) {
                val query = Phrases.tidyQuery(
                    Phrases.removeRanges(
                        rest,
                        listOfNotNull(time?.range, duration?.range, date?.range)
                    )
                )
                if (query.isNotBlank()) {
                    return VoiceCommand.ScheduleTask(
                        query = query,
                        start = time?.value,
                        durationMinutes = duration?.value,
                        date = date?.value
                    )
                }
            }
        }

        if (openDay.containsMatchIn(text)) {
            Phrases.extractDate(text, today)?.let { return VoiceCommand.OpenDay(it.value) }
        }

        addVerb.find(text)?.let { match ->
            return addFrom(text.removeRange(match.range), today, utterance)
        }

        return VoiceCommand.NotUnderstood(utterance)
    }

    /** Builds an AddTask from a verb-stripped tail (or a dictated phrase). */
    private fun addFrom(tail: String, today: LocalDate, original: String): VoiceCommand {
        val date = Phrases.extractDate(tail, today)
        val time = Phrases.extractTime(tail)
        val duration = Phrases.extractDuration(tail)
        val title = Phrases.tidyTitle(
            Phrases.removeRanges(
                tail,
                listOfNotNull(date?.range, time?.range, duration?.range)
            )
        )
        if (title.isBlank()) return VoiceCommand.NotUnderstood(tail)
        return VoiceCommand.AddTask(
            title = prose(title, original),
            date = date?.value ?: today,
            start = time?.value,
            durationMinutes = if (time?.value != null) duration?.value else null
        )
    }

    private fun choiceIndex(raw: String): Int = when {
        raw.contains("second") || raw.contains("2nd") || raw.contains("two") -> 1
        raw.contains("third") || raw.contains("3rd") || raw.contains("three") -> 2
        else -> Regex("\\d").find(raw)?.value?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
    }

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace(Regex("[!?]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')

    /**
     * User-visible text: original capitalisation restored from [original]
     * (brand names matter), then a capital on the first word.
     */
    private fun prose(text: String, original: String): String {
        val restored = Phrases.restoreCase(text, original)
        return if (restored.isEmpty()) restored
        else restored[0].uppercaseChar() + restored.substring(1)
    }
}
