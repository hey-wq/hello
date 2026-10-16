package com.signidesign.dailytasks.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.signidesign.dailytasks.R
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import com.signidesign.dailytasks.voice.VoiceMode
import com.signidesign.dailytasks.voice.VoicePhase
import com.signidesign.dailytasks.voice.VoiceTurn
import com.signidesign.dailytasks.voice.VoiceUiState

private val suggestions = listOf(
    "What's on today",
    "What's next",
    "Add call Delta Controls tomorrow at 3 for 45 minutes",
    "Help"
)

/**
 * The whole voice surface: transcript, live partial text, the microphone,
 * and a keyboard fallback for the times speech is the wrong tool (a loud
 * room, a brand name the recognizer refuses to hear).
 *
 * Deliberately a full screen rather than a floating dialog — it is reached
 * from the assist gesture and the lock screen, where a small sheet over
 * whatever happened to be behind it reads as an accident.
 */
@Composable
fun VoiceSheet(
    state: VoiceUiState,
    mode: VoiceMode,
    micGranted: Boolean,
    recognitionAvailable: Boolean,
    onRequestPermission: () -> Unit,
    onListen: () -> Unit,
    onStopListening: () -> Unit,
    onSubmitText: (String) -> Unit,
    onClose: () -> Unit
) {
    var typing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.lastIndex)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = Dimens.screenPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (mode == VoiceMode.DICTATION) "DICTATE" else "VOICE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.turns.isEmpty()) {
                Spacer(Modifier.height(Dimens.sectionGap))
                Text(
                    text = if (mode == VoiceMode.DICTATION) "Say the task." else "Go ahead.",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(Dimens.itemGap))
                SuggestionList(onPick = { onSubmitText(it) })
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = Dimens.itemGap,
                        bottom = Dimens.itemGap
                    )
                ) {
                    items(state.turns) { turn -> TurnBubble(turn) }
                }
            }

            // Live area: what is being heard, or what went wrong.
            AnimatedVisibility(
                visible = state.partial.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = state.partial,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = Dimens.innerGap)
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = Dimens.innerGap)
                )
            }

            Spacer(Modifier.height(Dimens.itemGap))

            if (typing) {
                TypedCommandField(
                    onSubmit = { text ->
                        typing = false
                        onSubmitText(text)
                    },
                    onDismiss = { typing = false }
                )
            } else {
                MicRow(
                    state = state,
                    micGranted = micGranted,
                    recognitionAvailable = recognitionAvailable,
                    onRequestPermission = onRequestPermission,
                    onListen = onListen,
                    onStopListening = onStopListening,
                    onType = { typing = true }
                )
            }

            Spacer(Modifier.navigationBarsPadding().height(Dimens.sectionGap))
        }
    }
}

@Composable
private fun MicRow(
    state: VoiceUiState,
    micGranted: Boolean,
    recognitionAvailable: Boolean,
    onRequestPermission: () -> Unit,
    onListen: () -> Unit,
    onStopListening: () -> Unit,
    onType: () -> Unit
) {
    val statusText = when {
        !micGranted -> "Microphone access needed"
        !recognitionAvailable -> "No speech recognition installed"
        state.phase == VoicePhase.LISTENING && state.awaitingAnswer -> "Listening for your answer"
        state.phase == VoicePhase.LISTENING -> "Listening"
        state.phase == VoicePhase.WORKING -> "Working"
        state.phase == VoicePhase.SPEAKING -> "Answering"
        state.retryable -> "Tap to try again"
        else -> "Tap to speak"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusText.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dimens.itemGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onType) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = "Type instead",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(Dimens.sectionGap))
            MicButton(
                listening = state.phase == VoicePhase.LISTENING,
                level = state.level,
                enabled = recognitionAvailable,
                onClick = {
                    when {
                        !micGranted -> onRequestPermission()
                        state.phase == VoicePhase.LISTENING -> onStopListening()
                        else -> onListen()
                    }
                }
            )
            Spacer(Modifier.width(Dimens.sectionGap))
            IconButton(
                onClick = onListen,
                enabled = recognitionAvailable && micGranted
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Listen again",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * The microphone, as a pulsing accent disc. The outer ring tracks the
 * measured input level so it is obvious, without reading a word, that the
 * phone is actually hearing something.
 */
@Composable
private fun MicButton(
    listening: Boolean,
    level: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val accent = AppTheme.accent
    val ringScale by animateFloatAsState(
        targetValue = if (listening) 1f + (level * 0.5f) else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "micRing"
    )

    Box(contentAlignment = Alignment.Center) {
        if (listening) {
            Surface(
                shape = CircleShape,
                color = accent.accentContainer,
                modifier = Modifier
                    .size(104.dp)
                    .scale(ringScale)
            ) {}
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (listening) accent.accent else MaterialTheme.colorScheme.onBackground,
            contentColor = if (listening) accent.onAccent
            else MaterialTheme.colorScheme.background,
            modifier = Modifier.size(76.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = if (listening) "Stop listening" else "Speak",
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

@Composable
private fun TurnBubble(turn: VoiceTurn) {
    val accent = AppTheme.accent
    if (turn.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Text(
                    text = turn.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Dimens.cardPadding)
                )
            }
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = accent.accentContainer,
            contentColor = accent.onAccentContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(Dimens.cardPadding)
            )
        }
    }
}

@Composable
private fun SuggestionList(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.innerGap)) {
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onPick(suggestion) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "“$suggestion”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun TypedCommandField(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Type a command…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { if (text.isNotBlank()) onSubmit(text.trim()) }
            ),
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Back to voice",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
