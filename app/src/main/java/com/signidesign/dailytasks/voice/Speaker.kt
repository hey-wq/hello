package com.signidesign.dailytasks.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Text-to-speech as a suspending call, so a session can say something and
 * then listen again without racing itself — the follow-up capture starts
 * when the reply has actually finished playing, not when it was queued.
 */
class Speaker(context: Context) {

    private val engine: TextToSpeech
    private val ready = CompletableDeferred<Boolean>()
    private val counter = AtomicLong(0)
    private val pending = HashMap<String, (Boolean) -> Unit>()

    init {
        engine = TextToSpeech(context.applicationContext) { status -> onEngineInit(status) }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            override fun onError(utteranceId: String?) = finish(utteranceId, false)

            override fun onError(utteranceId: String?, errorCode: Int) =
                finish(utteranceId, false)

            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                finish(utteranceId, false)
        })
    }

    private fun onEngineInit(status: Int) {
        val ok = status == TextToSpeech.SUCCESS
        if (ok) {
            runCatching {
                val locale = Locale.getDefault()
                if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = locale
                }
            }
        }
        ready.complete(ok)
    }

    private fun finish(utteranceId: String?, success: Boolean) {
        val callback = synchronized(pending) { pending.remove(utteranceId) }
        callback?.invoke(success)
    }

    /**
     * Speaks [text] and suspends until it finishes. Returns false when the
     * engine never initialised or the utterance was cut short — callers
     * carry on either way, since the transcript is always on screen too.
     */
    suspend fun say(text: String): Boolean {
        if (text.isBlank()) return true
        if (!ready.await()) return false
        val id = "daily-${counter.incrementAndGet()}"

        // Hard ceiling: a stuck engine must not strand the session.
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { continuation ->
                synchronized(pending) {
                    pending[id] = { success ->
                        if (continuation.isActive) continuation.resume(success)
                    }
                }
                continuation.invokeOnCancellation {
                    synchronized(pending) { pending.remove(id) }
                    runCatching { engine.stop() }
                }
                val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (queued != TextToSpeech.SUCCESS) finish(id, false)
            }
        } ?: false
    }

    fun stop() {
        runCatching { engine.stop() }
    }

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }
}
