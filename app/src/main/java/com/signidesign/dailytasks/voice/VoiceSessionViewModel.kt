package com.signidesign.dailytasks.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the session is doing right now; drives the whole sheet UI. */
enum class VoicePhase { IDLE, LISTENING, WORKING, SPEAKING, ERROR }

/** One line of the on-screen transcript. */
data class VoiceTurn(val fromUser: Boolean, val text: String)

/**
 * Entry points differ in one respect only: dictation takes the whole
 * utterance as a task title, the assistant requires a command verb.
 */
enum class VoiceMode { ASSISTANT, DICTATION }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val partial: String = "",
    val level: Float = 0f,
    val turns: List<VoiceTurn> = emptyList(),
    val error: String? = null,
    val retryable: Boolean = false,
    /** A question is open; the next utterance answers it. */
    val awaitingAnswer: Boolean = false,
    val navigation: VoiceNavigation? = null,
    val finished: Boolean = false
)

/**
 * Drives one voice session: listen, parse, execute, speak, and — when the
 * executor asked a question — listen again.
 *
 * Held by both entry activities so the behaviour is identical whether the
 * session was opened by the assist gesture, the quick-settings tile or the
 * microphone in the app.
 */
class VoiceSessionViewModel(
    private val executor: VoiceCommandExecutor,
    private val speechToText: SpeechToText,
    private val speaker: Speaker,
    private val earcons: Earcons,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var mode: VoiceMode = VoiceMode.ASSISTANT
    private var anchorDate: LocalDate = LocalDate.now()
    private var pending: PendingAction? = null
    private var listenJob: Job? = null
    private var started = false

    val recognitionAvailable: Boolean get() = speechToText.isAvailable()

    /** Called once, when the screen has the microphone permission. */
    fun begin(mode: VoiceMode, anchorDate: LocalDate) {
        this.mode = mode
        this.anchorDate = anchorDate
        if (started) return
        started = true
        listen()
    }

    fun listen() {
        listenJob?.cancel()
        speaker.stop()
        listenJob = viewModelScope.launch {
            val preferOffline = settings.voicePreferOffline.first()
            if (settings.voiceEarcons.first()) earcons.listening()
            _state.update {
                it.copy(
                    phase = VoicePhase.LISTENING,
                    partial = "",
                    level = 0f,
                    error = null,
                    retryable = false
                )
            }
            speechToText.listen(preferOffline).collect { event ->
                when (event) {
                    SpeechToText.Event.Ready ->
                        _state.update { it.copy(phase = VoicePhase.LISTENING) }
                    is SpeechToText.Event.Level ->
                        _state.update { it.copy(level = event.value) }
                    is SpeechToText.Event.Partial ->
                        _state.update { it.copy(partial = event.text) }
                    SpeechToText.Event.EndOfSpeech ->
                        _state.update { it.copy(phase = VoicePhase.WORKING, level = 0f) }
                    is SpeechToText.Event.Final -> handle(event.text)
                    is SpeechToText.Event.Failed -> fail(event.message, event.recoverable)
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(phase = VoicePhase.IDLE, level = 0f, partial = "") }
    }

    /** Keyboard fallback: same pipeline, no microphone. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        listenJob?.cancel()
        viewModelScope.launch { handle(text) }
    }

    private suspend fun handle(utterance: String) {
        _state.update {
            it.copy(
                phase = VoicePhase.WORKING,
                partial = "",
                level = 0f,
                error = null,
                retryable = false,
                turns = it.turns + VoiceTurn(fromUser = true, text = utterance)
            )
        }

        val commands = CommandParser.parseChain(
            utterance = utterance,
            today = anchorDate,
            dictation = mode == VoiceMode.DICTATION
        )

        val replies = mutableListOf<String>()
        var navigation: VoiceNavigation? = null
        var close = false
        var questionOpen = false

        for ((index, command) in commands.withIndex()) {
            val outcome = runCatching { executor.execute(command, pending) }
                .getOrElse { error ->
                    VoiceOutcome("Something went wrong: ${error.message ?: "unknown error"}.")
                }
            pending = outcome.pending
            replies += outcome.speech
            navigation = outcome.navigation ?: navigation
            close = close || outcome.close

            if (outcome.pending != null) {
                questionOpen = true
                // A question has to be settled before anything else runs, and
                // the unrun steps are not queued — say so rather than
                // silently dropping half of what was asked for.
                if (index < commands.lastIndex) {
                    replies += "I stopped there. Say the rest once that's settled."
                }
                break
            }
        }

        val understood = commands.none { it is VoiceCommand.NotUnderstood }
        if (settings.voiceEarcons.first()) {
            if (understood) earcons.done() else earcons.failed()
        }

        _state.update { current ->
            current.copy(
                phase = VoicePhase.SPEAKING,
                turns = current.turns + replies.map { VoiceTurn(fromUser = false, text = it) },
                awaitingAnswer = questionOpen,
                navigation = navigation ?: current.navigation
            )
        }

        if (settings.voiceSpokenReplies.first()) {
            speaker.say(replies.joinToString(" "))
        }

        when {
            close -> _state.update { it.copy(phase = VoicePhase.IDLE, finished = true) }
            // A question was asked, so take the answer without another tap.
            questionOpen -> listen()
            else -> _state.update { it.copy(phase = VoicePhase.IDLE) }
        }
    }

    private fun fail(message: String, retryable: Boolean) {
        _state.update {
            it.copy(
                phase = VoicePhase.ERROR,
                partial = "",
                level = 0f,
                error = message,
                retryable = retryable
            )
        }
    }

    fun navigationHandled() {
        _state.update { it.copy(navigation = null) }
    }

    fun dismiss() {
        listenJob?.cancel()
        speaker.stop()
        _state.update { it.copy(finished = true, phase = VoicePhase.IDLE) }
    }

    override fun onCleared() {
        listenJob?.cancel()
        speaker.shutdown()
        earcons.release()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] as TaskApp
                val container = app.container
                return VoiceSessionViewModel(
                    executor = VoiceCommandExecutor(
                        tasks = container.taskRepository,
                        settings = container.settingsRepository,
                        reminders = container.reminderManager,
                        syncEngine = container.syncEngine
                    ),
                    speechToText = SpeechToText(app),
                    speaker = Speaker(app),
                    earcons = Earcons(),
                    settings = container.settingsRepository
                ) as T
            }
        }
    }
}
