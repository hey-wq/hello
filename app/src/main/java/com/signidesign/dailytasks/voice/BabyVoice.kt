package com.signidesign.dailytasks.voice

import com.signidesign.dailytasks.data.BabyEventKind
import com.signidesign.dailytasks.data.BabyLane

/** What a spoken baby question is asking about. */
enum class BabyTopic { SUMMARY, SLEEP, FEEDS, LAST_FEED, NAPPIES, STATUS }

/** Start a timed session. */
data class BabyStart(val kind: BabyEventKind) : VoiceCommand

/** End whatever is running in a lane. */
data class BabyStop(val lane: BabyLane) : VoiceCommand

/** Log a moment: a bottle (with volume), a nappy, solids. */
data class BabyLog(val kind: BabyEventKind, val amountMl: Int? = null) : VoiceCommand

/** "nappy change" with no kind named — the session asks which. */
data object BabyNappyUnspecified : VoiceCommand

data class BabyAsk(val topic: BabyTopic) : VoiceCommand

/** Open the baby screen. */
data object BabyOpen : VoiceCommand

/**
 * The baby half of the grammar.
 *
 * Checked before the task verbs, because the collisions all run that way:
 * "log a bottle" would otherwise become a task called "a bottle", and
 * "nappy change" no task verb would claim at all.
 *
 * The guard that matters is the other direction — "feed the cat" must stay a
 * task. So a feed only registers with a side named (left, right, breast) or
 * an explicit start/stop around it; "feeding" on its own is not enough.
 */
object BabyVoice {

    private const val SHE = "(?:she|he|they|baby|the baby|she's|he's|shes|hes)"

    // ------------------------------------------------------------------ feeds

    // The second group is the evidence: "left breast" or "the right side"
    // names a feed on its own, where a bare "left" does not.
    private val breastSide = Regex(
        "\\b(?:on the |the |her |his |to the )?(left|right)\\b( side| breast| boob)?"
    )
    private val feedStart = Regex(
        "^(?:start(?:ing)?|begin|resume)?\\s*(?:a )?(?:feed|feeding|nursing|nurse|" +
            "$SHE (?:is |'s )?(?:feeding|nursing|on)|latch(?:ed)? on)"
    )
    private val feedWord = Regex("\\b(?:feed|feeding|nursing|nurse|breast|boob|latch)\\b")
    private val startWord = Regex(
        "^(?:start(?:ing)?|begin|resume|switch(?:ing)?|swap(?:ping)?|change|other)\\b"
    )

    /**
     * A task verb at the front means the utterance is about the day, not the
     * baby — "move it to the right side" must not log a feed. Only the
     * breast branch needs this guard; the bottle and nappy patterns are
     * anchored at both ends and cannot reach that far.
     */
    private val taskVerbLead = Regex(
        "^(?:add|create|make|new|remind|remember|move|push|shift|bump|reschedule|" +
            "postpone|defer|schedule|book|block|delete|remove|drop|mark|set|flag|" +
            "complete|finish|check|tick|cross|uncheck|unmark|note|open|show|read)\\b"
    )
    private val stopWord = Regex(
        "^(?:stop|end|ended|finish|finished|done|she's done|shes done|he's done|" +
            "hes done|unlatch(?:ed)?|that's it|thats it)\\b"
    )
    private val feedStop = Regex(
        "^(?:stop|end|ended|finish|finished|done)(?: the| her| his)? " +
            "(?:feed|feeding|nursing|nurse|breast ?feed(?:ing)?|side)\\b|" +
            "^(?:she|he|they)(?:'s| is| has)? (?:done|finished|unlatched)\\b|" +
            "^unlatched\\b"
    )

    // ------------------------------------------------------------------ sleep
    private val sleepStart = Regex(
        "^(?:$SHE )?(?:is |'s |has )?(?:asleep|sleeping|gone down|down for a nap|" +
            "down|napping)\\b|" +
            "^(?:start(?:ing)?|begin) (?:a )?(?:sleep|nap)\\b|" +
            "^(?:put(?:ting)? )(?:her|him|them|the baby|baby) (?:down|to sleep)\\b|" +
            "^(?:sleep|nap) start(?:ed)?\\b"
    )
    private val sleepStop = Regex(
        "^(?:$SHE )?(?:is |'s |has )?(?:awake|woken|woke|woken up|woke up|up)\\b|" +
            "^(?:stop|end|ended) (?:the )?(?:sleep|nap)\\b|" +
            "^wake(?: up)?\\b|^(?:she|he|they) woke\\b"
    )

    // ------------------------------------------------------------------- pump
    private val pumpStart = Regex("^(?:start(?:ing)?|begin) (?:to )?(?:pump(?:ing)?|express(?:ing)?)\\b|^pump(?:ing)? start(?:ed)?\\b")
    private val pumpStop = Regex("^(?:stop|end|ended|finish(?:ed)?|done) (?:the )?(?:pump(?:ing)?|express(?:ing)?)\\b")

    // ----------------------------------------------------------------- events
    // Anchored at both ends on purpose: "add bottle of wine to the list" is a
    // task, not a feed.
    private val bottle = Regex(
        "^(?:log |record |add |give |gave |had )?(?:a |an )?bottle" +
            "(?: of (?:milk|formula|ebm|breast ?milk|expressed milk))?" +
            "(?:[, ]+(\\d{2,4}) ?(?:ml|mls|millilitres|milliliters)?)?$|" +
            "^(?:log |record |add |gave |had )?(?:a |an )?(\\d{2,4}) ?" +
            "(?:ml|mls|millilitres|milliliters)" +
            "(?: bottle| of (?:milk|formula|ebm|breast ?milk|expressed milk))?$"
    )
    private val nappy = Regex(
        "^(?:log |record |add |change[d]? )?(?:a |an )?" +
            "(wet and dirty|dirty and wet|wet|dirty|soiled|poo(?:ey|py)?|mixed|both)?" +
            " ?(?:nappy|diaper)(?: change)?$"
    )
    private val nappyAnswer = Regex(
        "^(?:it was |it's |its )?(wet and dirty|dirty and wet|both|wet|dirty|soiled|" +
            "poo(?:ey|py)?|mixed)\\b"
    )
    private val solids =
        Regex("^(?:log |record |add |gave |had )?(?:some )?(?:solids|puree)$")

    // --------------------------------------------------------------- questions
    private val askSleep = Regex(
        "\\bhow (?:much|long)\\b.*\\b(?:sleep|slept|nap)\\b|" +
            "\\bsleep (?:total|today|so far)\\b|\\bhow did (?:she|he|they) sleep\\b"
    )
    private val askLastFeed = Regex(
        "\\b(?:when|how long).*\\blast (?:feed|fed|feeding|nursed)\\b|" +
            "\\blast feed\\b|\\bwhen did (?:she|he|they) (?:last )?(?:feed|eat)\\b|" +
            "\\bhow long since (?:the )?(?:last )?(?:feed|she fed|he fed)\\b"
    )
    private val askFeeds = Regex(
        "\\bhow many (?:feeds|feedings|times.*(?:fed|feed))\\b|\\bfeeds today\\b"
    )
    private val askNappies = Regex(
        "\\bhow many (?:nappies|diapers|changes)\\b|\\bnappies today\\b|\\bdiapers today\\b"
    )
    private val askStatus = Regex(
        "\\bwhat(?:'s| is) (?:she|he|they|the baby) doing\\b|\\bis (?:she|he|they) " +
            "(?:still )?(?:asleep|sleeping|feeding)\\b|\\bwhat(?:'s| is) running\\b|" +
            "\\bhow long has (?:she|he|they) been\\b"
    )
    private val askSummary = Regex(
        "\\bbaby (?:summary|report|stats|day)\\b|\\bhow (?:was|is) (?:the )?baby(?:'s)? day\\b|" +
            "\\bhow(?:'s| is) (?:she|he|they|baby|the baby) doing today\\b|" +
            "\\bread (?:me )?the baby(?:'s)? day\\b"
    )
    private val openBaby = Regex("^(?:open|show|go to|jump to)(?: the)? baby\\b")

    /**
     * Returns a baby command, or null to let the task grammar try.
     * [text] is already normalised to lowercase by [CommandParser].
     */
    fun parse(text: String): VoiceCommand? {
        if (openBaby.containsMatchIn(text)) return BabyOpen

        // Questions first: "how long since the last feed" must not be read as
        // an instruction to start one.
        if (askSummary.containsMatchIn(text)) return BabyAsk(BabyTopic.SUMMARY)
        if (askSleep.containsMatchIn(text)) return BabyAsk(BabyTopic.SLEEP)
        if (askLastFeed.containsMatchIn(text)) return BabyAsk(BabyTopic.LAST_FEED)
        if (askFeeds.containsMatchIn(text)) return BabyAsk(BabyTopic.FEEDS)
        if (askNappies.containsMatchIn(text)) return BabyAsk(BabyTopic.NAPPIES)
        if (askStatus.containsMatchIn(text)) return BabyAsk(BabyTopic.STATUS)

        if (pumpStop.containsMatchIn(text)) return BabyStop(BabyLane.FEED)
        if (pumpStart.containsMatchIn(text)) return BabyStart(BabyEventKind.PUMP)

        if (sleepStop.containsMatchIn(text)) return BabyStop(BabyLane.SLEEP)
        if (sleepStart.containsMatchIn(text)) return BabyStart(BabyEventKind.SLEEP)

        // A bottle is a feed too, but it is logged, never started.
        bottle.find(text)?.let { match ->
            val amount = match.groupValues.drop(1)
                .firstOrNull { it.isNotBlank() }
                ?.toIntOrNull()
            return BabyLog(BabyEventKind.BOTTLE, amount)
        }

        nappy.find(text)?.let { match ->
            return when (val which = match.groupValues[1]) {
                "" -> BabyNappyUnspecified
                "wet" -> BabyLog(BabyEventKind.NAPPY_WET)
                "dirty", "soiled" -> BabyLog(BabyEventKind.NAPPY_DIRTY)
                "wet and dirty", "dirty and wet", "mixed", "both" ->
                    BabyLog(BabyEventKind.NAPPY_BOTH)
                else ->
                    if (which.startsWith("poo")) BabyLog(BabyEventKind.NAPPY_DIRTY)
                    else BabyNappyUnspecified
            }
        }

        if (solids.containsMatchIn(text)) return BabyLog(BabyEventKind.SOLIDS)

        // Feeds last, and only with a side or an explicit start/stop, so
        // "feed the cat" stays a task.
        if (feedStop.containsMatchIn(text)) return BabyStop(BabyLane.FEED)
        val side = breastSide.find(text)
        val mentionsFeed = feedWord.containsMatchIn(text)
        val namesAnatomy = side != null && side.groupValues[2].isNotBlank()
        val taskish = taskVerbLead.containsMatchIn(text)
        if (side != null && !taskish &&
            (mentionsFeed || namesAnatomy || startWord.containsMatchIn(text) ||
                feedStart.containsMatchIn(text))
        ) {
            return BabyStart(
                if (side.groupValues[1] == "left") BabyEventKind.BREAST_LEFT
                else BabyEventKind.BREAST_RIGHT
            )
        }
        if (mentionsFeed && stopWord.containsMatchIn(text)) return BabyStop(BabyLane.FEED)

        return null
    }

    /** Resolves "wet", "dirty", "both" when the session asked which. */
    fun parseNappyAnswer(text: String): BabyEventKind? {
        val match = nappyAnswer.find(text) ?: return null
        val which = match.groupValues[1]
        return when {
            which == "wet" -> BabyEventKind.NAPPY_WET
            which == "dirty" || which == "soiled" || which.startsWith("poo") ->
                BabyEventKind.NAPPY_DIRTY
            else -> BabyEventKind.NAPPY_BOTH
        }
    }
}
