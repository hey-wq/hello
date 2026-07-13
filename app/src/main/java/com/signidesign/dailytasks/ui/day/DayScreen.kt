package com.signidesign.dailytasks.ui.day

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import com.signidesign.dailytasks.util.isToday
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// "Infinite" pager anchored so START_PAGE maps to the screen's initial date.
private const val PAGE_COUNT = 40_000
private const val START_PAGE = PAGE_COUNT / 2

private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val fullDateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())

/** Shared clock for time-remaining labels; ticks every 30 seconds. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(initialValue = LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(30_000)
    }
}

@Composable
fun DayScreen(
    viewModel: AppViewModel,
    initialDate: LocalDate,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = START_PAGE) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val now by rememberNow()

    fun dateFor(page: Int): LocalDate = initialDate.plusDays((page - START_PAGE).toLong())

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding - 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous day",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next day",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenCalendar) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = "Calendar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                DayPage(
                    date = dateFor(page),
                    viewModel = viewModel,
                    now = now
                )
            }
        }
    }
}

@Composable
private fun DayPage(
    date: LocalDate,
    viewModel: AppViewModel,
    now: LocalDateTime
) {
    val tasks by remember(date) { viewModel.tasksFor(date) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var notesExpanded by rememberSaveable(date) { mutableStateOf(false) }
    var expandedTaskId by remember(date) { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.screenPadding)
            .imePadding()
    ) {
        Spacer(Modifier.height(Dimens.sectionGap))
        DayHeader(
            date = date,
            notesExpanded = notesExpanded,
            onToggleNotes = { notesExpanded = !notesExpanded }
        )

        AnimatedVisibility(
            visible = notesExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            DayNotesPanel(date = date, viewModel = viewModel)
        }

        Spacer(Modifier.height(Dimens.sectionGap))
        AddTaskField(onAdd = { title -> viewModel.addTask(title, date) })
        Spacer(Modifier.height(Dimens.itemGap))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing planned.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 96.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Dimens.itemGap)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        now = now,
                        expanded = expandedTaskId == task.id,
                        onToggleExpanded = {
                            expandedTaskId = if (expandedTaskId == task.id) null else task.id
                        },
                        onSetDone = { done -> viewModel.setDone(task, done) },
                        onSetNote = { note -> viewModel.setNote(task, note) },
                        onSetSchedule = { start, duration ->
                            viewModel.setSchedule(task, start, duration)
                        },
                        onDelete = { viewModel.deleteTask(task) },
                        modifier = Modifier.animateItem()
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding().height(Dimens.sectionGap)) }
            }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    notesExpanded: Boolean,
    onToggleNotes: () -> Unit
) {
    val accent = AppTheme.accent
    Column {
        if (isToday(date)) {
            Surface(
                shape = CircleShape,
                color = accent.accentContainer,
                contentColor = accent.onAccentContainer
            ) {
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(Dimens.innerGap))
        }
        Text(
            text = date.format(dayOfWeekFormatter),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = date.format(fullDateFormatter),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            NotesPill(active = notesExpanded, onClick = onToggleNotes)
        }
    }
}

@Composable
private fun NotesPill(active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.onBackground
        else MaterialTheme.colorScheme.background,
        contentColor = if (active) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = "NOTES", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DayNotesPanel(date: LocalDate, viewModel: AppViewModel) {
    val storedNote by remember(date) { viewModel.dayNoteFor(date) }
        .collectAsStateWithLifecycle(initialValue = null)
    // null = not yet initialized from the database; avoids clobbering stored
    // content with an empty first frame.
    var draft by remember(date) { mutableStateOf<String?>(null) }
    val text = draft ?: storedNote?.content ?: ""

    // Debounced autosave.
    LaunchedEffect(draft, date) {
        val pending = draft ?: return@LaunchedEffect
        delay(500)
        viewModel.saveDayNote(date, pending)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { draft = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.itemGap),
        placeholder = {
            Text(
                "Notes for this day…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        minLines = 3,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onBackground,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
private fun AddTaskField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text("Add a task…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onBackground,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}
