package com.signidesign.dailytasks.voice

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Three short cues — opening the mic, an operation that landed, one that
 * didn't — so the state of a session is legible without looking at the
 * screen (the assist gesture is often used face-down on a desk).
 *
 * Synthesised rather than shipped as audio: no assets, no licensing, and
 * nothing to keep in sync with the theme. The tones are deliberately plain;
 * an assistant that announces itself with a fanfare wears out in a day.
 */
class Earcons {

    private var generator: ToneGenerator? = null

    private fun obtain(): ToneGenerator? {
        generator?.let { return it }
        val created = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
        }.getOrNull()
        generator = created
        return created
    }

    /** Rising two-tone: the microphone is open. */
    fun listening() {
        play(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    /** Short ack: the operation was applied. */
    fun done() {
        play(ToneGenerator.TONE_PROP_ACK, 110)
    }

    /** Lower double tone: nothing was applied. */
    fun failed() {
        play(ToneGenerator.TONE_PROP_NACK, 150)
    }

    private fun play(tone: Int, durationMs: Int) {
        runCatching { obtain()?.startTone(tone, durationMs) }
    }

    fun release() {
        runCatching { generator?.release() }
        generator = null
    }

    private companion object {
        /** Out of 100; cues sit under the spoken reply, not over it. */
        const val VOLUME = 55
    }
}
