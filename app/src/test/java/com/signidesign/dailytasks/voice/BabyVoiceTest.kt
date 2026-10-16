package com.signidesign.dailytasks.voice

import com.signidesign.dailytasks.data.BabyEventKind
import com.signidesign.dailytasks.data.BabyLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Baby commands share the session machinery with task commands, so the
 * tests that matter most are the ones proving the two grammars stay out of
 * each other's way: "feed the cat" is a task, "feeding on the left" is not.
 */
class BabyVoiceTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun parse(utterance: String) = CommandParser.parse(utterance, today)

    @Test
    fun `breast feeds start from several phrasings`() {
        assertEquals(BabyStart(BabyEventKind.BREAST_LEFT), parse("feeding on the left"))
        assertEquals(BabyStart(BabyEventKind.BREAST_LEFT), parse("start feeding left"))
        assertEquals(BabyStart(BabyEventKind.BREAST_LEFT), parse("left breast"))
        assertEquals(
            BabyStart(BabyEventKind.BREAST_RIGHT),
            parse("she's feeding on the right")
        )
        assertEquals(
            BabyStart(BabyEventKind.BREAST_RIGHT),
            parse("switch to the right side")
        )
    }

    @Test
    fun `feeds end from several phrasings`() {
        assertEquals(BabyStop(BabyLane.FEED), parse("stop feeding"))
        assertEquals(BabyStop(BabyLane.FEED), parse("done feeding"))
        assertEquals(BabyStop(BabyLane.FEED), parse("she's done"))
    }

    @Test
    fun `sleep starts and ends the way people say it`() {
        assertEquals(BabyStart(BabyEventKind.SLEEP), parse("she's asleep"))
        assertEquals(BabyStart(BabyEventKind.SLEEP), parse("baby is sleeping"))
        assertEquals(BabyStart(BabyEventKind.SLEEP), parse("down for a nap"))
        assertEquals(BabyStart(BabyEventKind.SLEEP), parse("start a nap"))
        assertEquals(BabyStop(BabyLane.SLEEP), parse("she's awake"))
        assertEquals(BabyStop(BabyLane.SLEEP), parse("she woke up"))
        assertEquals(BabyStop(BabyLane.SLEEP), parse("end the nap"))
    }

    @Test
    fun `pumping is a session too`() {
        assertEquals(BabyStart(BabyEventKind.PUMP), parse("start pumping"))
        assertEquals(BabyStop(BabyLane.FEED), parse("stop pumping"))
    }

    @Test
    fun `bottles carry their volume`() {
        assertEquals(BabyLog(BabyEventKind.BOTTLE, 120), parse("log a bottle 120ml"))
        assertEquals(BabyLog(BabyEventKind.BOTTLE, 120), parse("120ml bottle"))
        assertEquals(BabyLog(BabyEventKind.BOTTLE, 90), parse("gave a bottle, 90"))
        assertEquals(BabyLog(BabyEventKind.BOTTLE, null), parse("bottle"))
    }

    @Test
    fun `nappies resolve by kind, and ask when unnamed`() {
        assertEquals(BabyLog(BabyEventKind.NAPPY_WET), parse("wet nappy"))
        assertEquals(BabyLog(BabyEventKind.NAPPY_DIRTY), parse("log a dirty nappy"))
        assertEquals(BabyLog(BabyEventKind.NAPPY_BOTH), parse("wet and dirty nappy"))
        assertEquals(BabyNappyUnspecified, parse("nappy change"))

        assertEquals(BabyEventKind.NAPPY_WET, BabyVoice.parseNappyAnswer("wet"))
        assertEquals(BabyEventKind.NAPPY_DIRTY, BabyVoice.parseNappyAnswer("dirty"))
        assertEquals(BabyEventKind.NAPPY_BOTH, BabyVoice.parseNappyAnswer("both"))
    }

    @Test
    fun `questions resolve to topics`() {
        assertEquals(BabyAsk(BabyTopic.SLEEP), parse("how much sleep today"))
        assertEquals(BabyAsk(BabyTopic.SLEEP), parse("how long did she sleep"))
        assertEquals(BabyAsk(BabyTopic.LAST_FEED), parse("when was the last feed"))
        assertEquals(BabyAsk(BabyTopic.LAST_FEED), parse("how long since the last feed"))
        assertEquals(BabyAsk(BabyTopic.NAPPIES), parse("how many nappies today"))
        assertEquals(BabyAsk(BabyTopic.STATUS), parse("what's she doing"))
        assertEquals(BabyAsk(BabyTopic.SUMMARY), parse("baby summary"))
        assertEquals(BabyOpen, parse("open baby"))
    }

    // ------------------------------------------------------------ the guards

    @Test
    fun `task phrasings are not swallowed by the baby grammar`() {
        assertTrue(
            "feed the cat should stay a task",
            parse("add feed the cat today") is VoiceCommand.AddTask
        )
        assertTrue(
            "a bottle of wine is shopping, not a feed",
            parse("add bottle of wine to the list") is VoiceCommand.AddTask
        )
        assertTrue(
            "nappy cream is shopping, not a change",
            parse("add nappy cream to the shopping list") is VoiceCommand.AddTask
        )
        assertTrue(
            "a task verb in front wins over a side word",
            parse("move it to the right side") is VoiceCommand.MoveTask
        )
        assertTrue(parse("what's on today") is VoiceCommand.ReadDay)
        assertTrue(parse("mark the deck review as done") is VoiceCommand.SetDone)
    }

    @Test
    fun `chaining works across the two domains`() {
        val chain = CommandParser.parseChain("wet nappy then she's asleep", today)
        assertEquals(2, chain.size)
        assertTrue(chain[0] is BabyLog)
        assertTrue(chain[1] is BabyStart)
    }
}
