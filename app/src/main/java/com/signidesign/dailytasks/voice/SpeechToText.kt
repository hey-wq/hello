package com.signidesign.dailytasks.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * The platform recognizer as a cold flow: one collection is one utterance,
 * cancelling the collection cancels the capture.
 *
 * Android's SpeechRecognizer is main-thread-only and single-shot, which is
 * exactly what a callbackFlow is for — the listener is bound on start and
 * the recognizer is destroyed in awaitClose, so a cancelled session never
 * leaves the microphone hot.
 */
class SpeechToText(private val context: Context) {

    sealed interface Event {
        /** The recognizer is live and the beep has played. */
        data object Ready : Event

        /** Microphone level, 0f..1f, for the listening indicator. */
        data class Level(val value: Float) : Event

        data class Partial(val text: String) : Event

        data class Final(val text: String) : Event

        /** Speech stopped; results follow. */
        data object EndOfSpeech : Event

        /** [recoverable] means "worth offering another try". */
        data class Failed(val message: String, val recoverable: Boolean) : Event
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(preferOffline: Boolean): Flow<Event> = callbackFlow {
        val recognizer = createRecognizer(preferOffline)
        if (recognizer == null) {
            trySend(
                Event.Failed(
                    "No speech recognition on this device. Install Google's speech " +
                        "services, or use the keyboard.",
                    recoverable = false
                )
            )
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(Event.Ready)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The API reports roughly -2..10 dB; map to a 0..1 level.
                trySend(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(Event.EndOfSpeech)
            }

            override fun onError(error: Int) {
                trySend(errorEvent(error))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (text.isNullOrBlank()) {
                    trySend(Event.Failed("I didn't catch that.", recoverable = true))
                } else {
                    trySend(Event.Final(text))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(Event.Partial(it)) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(recognitionIntent(preferOffline))

        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun createRecognizer(preferOffline: Boolean): SpeechRecognizer? = runCatching {
        if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }.getOrNull()

    private fun recognitionIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Commands are short; don't make the user wait out a long tail.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L
            )
        }

    private fun errorEvent(error: Int): Event.Failed = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH ->
            Event.Failed("I didn't catch that.", recoverable = true)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            Event.Failed("I didn't hear anything.", recoverable = true)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            Event.Failed(
                "Recognition needs the network right now. Turn on on-device " +
                    "recognition in settings to work offline.",
                recoverable = true
            )
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Event.Failed("Microphone access is off for Daily.", recoverable = false)
        SpeechRecognizer.ERROR_AUDIO ->
            Event.Failed("Something's wrong with the microphone.", recoverable = true)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            Event.Failed("The recognizer is busy. Try again.", recoverable = true)
        SpeechRecognizer.ERROR_CLIENT ->
            Event.Failed("Recognition stopped.", recoverable = true)
        SpeechRecognizer.ERROR_SERVER ->
            Event.Failed("The recognition service errored.", recoverable = true)
        else -> Event.Failed("Recognition failed.", recoverable = true)
    }
}
