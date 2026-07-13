@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.signidesign.dailytasks.ui.day

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import com.signidesign.dailytasks.util.TimedState
import com.signidesign.dailytasks.util.timeFormatter
import com.signidesign.dailytasks.util.timedLabel
import com.signidesign.dailytasks.util.timedState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

private val DEFAULT_START: LocalTime = LocalTime.of(9, 0)
private const val DEFAULT_DURATION_MINUTES = 30

@Composable
fun TaskRow(
    task: TaskEntity,
    now: LocalDateTime,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSetDone: (Boolean) -> Unit,
    onSetNote: (String) -> Unit,
    onSetSchedule: (LocalTime?, Int?) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = AppTheme.accent
    val haptic = LocalHapticFeedback.current

    val borderColor by animateColorAsState(
        targetValue = when {
            task.isDone -> accent.accent.copy(alpha = 0.35f)
            expanded -> MaterialTheme.colorScheme.onBackground
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "taskBorder"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onToggleExpanded)
                .padding(Dimens.cardPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DoneToggle(
                    done = task.isDone,
                    onToggle = {
                        val nowDone = !task.isDone
                        if (nowDone) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onSetDone(nowDone)
                    }
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                        color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (!task.note.isNullOrBlank() && !expanded) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = task.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                if (task.isTimed && task.startTime != null) {
                    Spacer(Modifier.width(Dimens.innerGap))
                    TimePill(task = task, now = now)
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ExpandedContent(
                    task = task,
                    onSetNote = onSetNote,
                    onSetSchedule = onSetSchedule,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun DoneToggle(done: Boolean, onToggle: () -> Unit) {
    val accent = AppTheme.accent
    // Small spring pulse as the completion feedback.
    val scale by animateFloatAsState(
        targetValue = if (done) 1f else 0.99f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
        label = "doneScale"
    )
    val fill by animateColorAsState(
        targetValue = if (done) accent.accent else MaterialTheme.colorScheme.surface,
        label = "doneFill"
    )
    val ring by animateColorAsState(
        targetValue = if (done) accent.accent else MaterialTheme.colorScheme.outline,
        label = "doneRing"
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, ring, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Done",
                tint = accent.onAccent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun TimePill(task: TaskEntity, now: LocalDateTime) {
    val accent = AppTheme.accent
    val state = timedState(task, now)
    val label = timedLabel(task, now) ?: return
    val start = task.startTime ?: return

    val running = state is TimedState.Running
    Surface(
        shape = CircleShape,
        color = if (running) accent.accentContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (running) accent.onAccentContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (running) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = "${start.format(timeFormatter)} · $label",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ExpandedContent(
    task: TaskEntity,
    onSetNote: (String) -> Unit,
    onSetSchedule: (LocalTime?, Int?) -> Unit,
    onDelete: () -> Unit
) {
    val accent = AppTheme.accent
    var noteDraft by remember(task.id) { mutableStateOf<String?>(null) }
    val noteText = noteDraft ?: task.note.orEmpty()
    var showTimePicker by remember { mutableStateOf(false) }
    var durationDraft by remember(task.id) { mutableStateOf<String?>(null) }
    val durationText = durationDraft ?: task.durationMinutes?.toString().orEmpty()

    // Debounced note autosave.
    LaunchedEffect(noteDraft, task.id) {
        val pending = noteDraft ?: return@LaunchedEffect
        delay(500)
        onSetNote(pending)
    }

    Column(modifier = Modifier.padding(top = Dimens.cardPadding)) {
        OutlinedTextField(
            value = noteText,
            onValueChange = { noteDraft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Note…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            minLines = 2,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(Modifier.height(Dimens.itemGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SCHEDULE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = task.isTimed,
                onCheckedChange = { on ->
                    if (on) {
                        onSetSchedule(
                            task.startTime ?: DEFAULT_START,
                            task.durationMinutes ?: DEFAULT_DURATION_MINUTES
                        )
                    } else {
                        onSetSchedule(null, null)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = accent.accent,
                    checkedThumbColor = accent.onAccent
                )
            )
        }

        AnimatedVisibility(
            visible = task.isTimed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                modifier = Modifier.padding(top = Dimens.innerGap)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    onClick = { showTimePicker = true }
                ) {
                    Text(
                        text = (task.startTime ?: DEFAULT_START).format(timeFormatter),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { raw ->
                        val filtered = raw.filter(Char::isDigit).take(4)
                        durationDraft = filtered
                        filtered.toIntOrNull()?.let { minutes ->
                            if (minutes > 0) onSetSchedule(task.startTime, minutes)
                        }
                    },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    suffix = { Text("min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }

        Spacer(Modifier.height(Dimens.innerGap))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showTimePicker) {
        StartTimePickerDialog(
            initial = task.startTime ?: DEFAULT_START,
            onDismiss = { showTimePicker = false },
            onConfirm = { picked ->
                showTimePicker = false
                onSetSchedule(picked, task.durationMinutes ?: DEFAULT_DURATION_MINUTES)
            }
        )
    }
}

@Composable
private fun StartTimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
