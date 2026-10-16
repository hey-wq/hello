package com.signidesign.dailytasks.ui.baby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signidesign.dailytasks.baby.BabyStats
import com.signidesign.dailytasks.data.BabyEventEntity
import com.signidesign.dailytasks.data.BabyEventKind
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import com.signidesign.dailytasks.util.isToday
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val fullDateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private val bottlePresets = listOf(30, 60, 90, 120, 150, 180, 210, 240)

/** Live sessions need a real clock, not the 30-second one the task list uses. */
@Composable
private fun rememberSecondTicker(): State<Instant> =
    produceState(initialValue = Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(1_000)
        }
    }

@Composable
fun BabyScreen(
    viewModel: BabyViewModel,
    onBack: () -> Unit
) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val now by rememberSecondTicker()
    val zone = viewModel.zone

    val events by remember(day) { viewModel.eventsFor(day) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val openFeed by viewModel.openFeed.collectAsStateWithLifecycle(initialValue = null)
    val openSleep by viewModel.openSleep.collectAsStateWithLifecycle(initialValue = null)
    val lastLogged by viewModel.lastLogged.collectAsStateWithLifecycle()

    val summary = remember(events, day, now) {
        BabyStats.summarise(events, day, zone, now)
    }
    val bands = remember(events, day, now) {
        BabyStats.sleepBands(events, day, zone, now)
    }

    var bottleDialog by remember { mutableStateOf(false) }
    var nappyDialog by remember { mutableStateOf(false) }
    var expandedId by remember(day) { mutableStateOf<Long?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                bottom = Dimens.sectionGap
            )
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { day = day.minusDays(1) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous day",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { day = day.plusDays(1) },
                        enabled = day.isBefore(LocalDate.now())
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next day",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(Dimens.itemGap))
                DayHeading(day = day)
                Spacer(Modifier.height(Dimens.sectionGap))
            }

            // Whatever is running, large enough to read across a dark room.
            item {
                AnimatedVisibility(
                    visible = openFeed != null || openSleep != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        openFeed?.let { session ->
                            LiveSessionCard(
                                session = session,
                                now = now,
                                onStop = { viewModel.endFeed() }
                            )
                            Spacer(Modifier.height(Dimens.itemGap))
                        }
                        openSleep?.let { session ->
                            LiveSessionCard(
                                session = session,
                                now = now,
                                onStop = { viewModel.endSleep() }
                            )
                            Spacer(Modifier.height(Dimens.itemGap))
                        }
                    }
                }
            }

            item {
                ActionGrid(
                    openFeed = openFeed,
                    openSleep = openSleep,
                    now = now,
                    onBreast = { viewModel.tapBreast(it) },
                    onSleep = { viewModel.tapSleep() },
                    onPump = { viewModel.tapPump() },
                    onBottle = { bottleDialog = true },
                    onNappy = { nappyDialog = true }
                )
            }

            item {
                AnimatedVisibility(
                    visible = lastLogged != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    lastLogged?.let { event ->
                        Column {
                            Spacer(Modifier.height(Dimens.itemGap))
                            UndoBar(
                                event = event,
                                zone = zone,
                                onUndo = { viewModel.undoLast() },
                                onDismiss = { viewModel.clearUndo() }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionLabel(if (isToday(day)) "TODAY" else "THAT DAY")
                Spacer(Modifier.height(Dimens.itemGap))
                SummaryCard(summary = summary, now = now)
            }

            item {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionLabel("SLEEP PATTERN")
                Spacer(Modifier.height(Dimens.itemGap))
                SleepBands(bands = bands)
            }

            item {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionLabel("TIMELINE")
                Spacer(Modifier.height(Dimens.itemGap))
            }

            val timeline = events.filter { event ->
                // The spill-in query reaches back a day; only show what
                // actually touches the day on screen.
                !BabyStats.durationOn(event, day, zone, now).isZero ||
                    event.startAt.atZone(zone).toLocalDate() == day
            }

            if (timeline.isEmpty()) {
                item {
                    Text(
                        text = "Nothing logged.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.sectionGap)
                    )
                }
            } else {
                items(timeline, key = { it.id }) { event ->
                    TimelineRow(
                        event = event,
                        now = now,
                        zone = zone,
                        expanded = expandedId == event.id,
                        onToggle = {
                            expandedId = if (expandedId == event.id) null else event.id
                        },
                        onShiftStart = { minutes -> viewModel.shiftStart(event, minutes) },
                        onSetAmount = { amount -> viewModel.setAmount(event, amount) },
                        onDelete = {
                            expandedId = null
                            viewModel.delete(event)
                        }
                    )
                    Spacer(Modifier.height(Dimens.innerGap))
                }
            }

            item { Spacer(Modifier.navigationBarsPadding().height(Dimens.sectionGap)) }
        }
    }

    if (bottleDialog) {
        AmountDialog(
            title = "Bottle",
            presets = bottlePresets,
            onDismiss = { bottleDialog = false },
            onConfirm = { amount ->
                bottleDialog = false
                viewModel.logBottle(amount)
            }
        )
    }

    if (nappyDialog) {
        NappyDialog(
            onDismiss = { nappyDialog = false },
            onPick = { kind ->
                nappyDialog = false
                viewModel.logNappy(kind)
            }
        )
    }
}

@Composable
private fun DayHeading(day: LocalDate) {
    val accent = AppTheme.accent
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = accent.accentContainer,
                contentColor = accent.onAccentContainer
            ) {
                Text(
                    text = "BABY",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (isToday(day)) {
                Spacer(Modifier.width(Dimens.innerGap))
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Dimens.innerGap))
        Text(
            text = day.format(dayOfWeekFormatter),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = day.format(fullDateFormatter),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The running session. Oversized timer on the accent, because this is the
 * one thing on the screen you need to read without your glasses at 4am.
 */
@Composable
private fun LiveSessionCard(
    session: BabyEventEntity,
    now: Instant,
    onStop: () -> Unit
) {
    val accent = AppTheme.accent
    Surface(
        shape = MaterialTheme.shapes.large,
        color = accent.accent,
        contentColor = accent.onAccent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dimens.cardPadding)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.kind.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = liveClock(BabyStats.duration(session, now)),
                    style = MaterialTheme.typography.displaySmall
                )
            }
            TextButton(onClick = onStop) {
                Text(
                    text = "STOP",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent.onAccent
                )
            }
        }
    }
}

/** mm:ss under an hour, h:mm:ss over it. */
private fun liveClock(duration: Duration): String {
    val total = duration.seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ActionGrid(
    openFeed: BabyEventEntity?,
    openSleep: BabyEventEntity?,
    now: Instant,
    onBreast: (BabyEventKind) -> Unit,
    onSleep: () -> Unit,
    onPump: () -> Unit,
    onBottle: () -> Unit,
    onNappy: () -> Unit
) {
    fun runningLabel(kind: BabyEventKind): String? {
        val session = when (kind) {
            BabyEventKind.SLEEP -> openSleep
            else -> openFeed
        }
        return if (session?.kind == kind) liveClock(BabyStats.duration(session, now)) else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
            ActionTile(
                label = "Left",
                detail = runningLabel(BabyEventKind.BREAST_LEFT) ?: "breast",
                active = openFeed?.kind == BabyEventKind.BREAST_LEFT,
                onClick = { onBreast(BabyEventKind.BREAST_LEFT) },
                modifier = Modifier.weight(1f)
            )
            ActionTile(
                label = "Right",
                detail = runningLabel(BabyEventKind.BREAST_RIGHT) ?: "breast",
                active = openFeed?.kind == BabyEventKind.BREAST_RIGHT,
                onClick = { onBreast(BabyEventKind.BREAST_RIGHT) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
            ActionTile(
                label = "Sleep",
                detail = runningLabel(BabyEventKind.SLEEP) ?: "start or end",
                active = openSleep != null,
                onClick = onSleep,
                modifier = Modifier.weight(1f)
            )
            ActionTile(
                label = "Bottle",
                detail = "with amount",
                active = false,
                onClick = onBottle,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
            ActionTile(
                label = "Nappy",
                detail = "wet or dirty",
                active = false,
                onClick = onNappy,
                modifier = Modifier.weight(1f)
            )
            ActionTile(
                label = "Pump",
                detail = runningLabel(BabyEventKind.PUMP) ?: "expressing",
                active = openFeed?.kind == BabyEventKind.PUMP,
                onClick = onPump,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    detail: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = AppTheme.accent
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (active) accent.accentContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (active) accent.onAccentContainer
        else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) accent.accent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Text(text = label, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) accent.onAccentContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UndoBar(
    event: BabyEventEntity,
    zone: ZoneId,
    onUndo: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                text = "${event.kind.label} at " +
                    event.startAt.atZone(zone).format(clockFormatter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndo) {
                Text("Undo", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: BabyStats.DaySummary, now: Instant) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.innerGap)
        ) {
            SummaryRow(
                label = "Feeds",
                value = summary.feedCount.toString(),
                detail = buildString {
                    if (!summary.breastTime.isZero) {
                        append(BabyStats.formatDuration(summary.breastTime))
                        append(" at the breast")
                        append(
                            " (L ${BabyStats.formatDuration(summary.leftTime)}" +
                                " / R ${BabyStats.formatDuration(summary.rightTime)})"
                        )
                    }
                    if (summary.bottleMl > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${summary.bottleMl}ml in ${summary.bottleCount} bottles")
                    }
                }
            )
            SummaryRow(
                label = "Sleep",
                value = BabyStats.formatDuration(summary.sleepTime),
                detail = buildString {
                    append("${summary.sleepSessions} sleeps")
                    if (!summary.longestSleep.isZero) {
                        append(" · longest ${BabyStats.formatDuration(summary.longestSleep)}")
                    }
                }
            )
            SummaryRow(
                label = "Nappies",
                value = summary.nappyTotal.toString(),
                detail = "${summary.nappyWet} wet · ${summary.nappyDirty} dirty"
            )
            if (summary.pumpedMl > 0) {
                SummaryRow(
                    label = "Pumped",
                    value = "${summary.pumpedMl}ml",
                    detail = ""
                )
            }
            summary.lastFeedAt?.let { last ->
                val since = Duration.between(last, now).coerceAtLeast(Duration.ZERO)
                SummaryRow(
                    label = "Last feed",
                    value = "${BabyStats.formatDuration(since)} ago",
                    detail = ""
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, detail: String) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Twenty-four bars, one per hour, filled by the fraction of that hour spent
 * asleep. A night's shape reads instantly; a list of timestamps never does.
 */
@Composable
private fun SleepBands(bands: List<Float>) {
    val accent = AppTheme.accent
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            bands.forEach { fraction ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(3.dp)
                        ),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceIn(0.06f, 1f))
                                .background(
                                    color = accent.accent,
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "24").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    event: BabyEventEntity,
    now: Instant,
    zone: ZoneId,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShiftStart: (Long) -> Unit,
    onSetAmount: (Int?) -> Unit,
    onDelete: () -> Unit
) {
    val accent = AppTheme.accent
    val running = event.endAt == null
    var amountDialog by remember { mutableStateOf(false) }

    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (running) 1.5.dp else 1.dp,
            color = if (running) accent.accent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.startAt.atZone(zone).format(clockFormatter),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Dimens.itemGap))
                Text(
                    text = event.kind.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val trailing = when {
                    event.kind.timed && running ->
                        liveClock(BabyStats.duration(event, now))
                    event.kind.timed ->
                        BabyStats.formatDuration(BabyStats.duration(event, now))
                    event.amountMl != null -> "${event.amountMl}ml"
                    else -> ""
                }
                if (trailing.isNotBlank()) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (running) accent.accent
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            event.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(Dimens.innerGap))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Dimens.innerGap)
                ) {
                    // Logging always happens a few minutes after the fact.
                    TextButton(onClick = { onShiftStart(-5) }) {
                        Text("−5 min", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { onShiftStart(5) }) {
                        Text("+5 min", style = MaterialTheme.typography.labelMedium)
                    }
                    if (event.kind == BabyEventKind.BOTTLE ||
                        event.kind == BabyEventKind.PUMP
                    ) {
                        TextButton(onClick = { amountDialog = true }) {
                            Text("Amount", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Text(
                            "Delete",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (amountDialog) {
        AmountDialog(
            title = event.kind.label,
            presets = bottlePresets,
            onDismiss = { amountDialog = false },
            onConfirm = { amount ->
                amountDialog = false
                onSetAmount(amount)
            }
        )
    }
}

@Composable
private fun AmountDialog(
    title: String,
    presets: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(
                    text = "Millilitres",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dimens.innerGap))
                presets.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.innerGap)) {
                        row.forEach { amount ->
                            Surface(
                                onClick = { onConfirm(amount) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = amount.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Dimens.innerGap))
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { entered ->
                        custom = entered.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("Other") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(custom.toIntOrNull()) },
                enabled = custom.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null) }) {
                Text("No amount")
            }
        }
    )
}

@Composable
private fun NappyDialog(onDismiss: () -> Unit, onPick: (BabyEventKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nappy", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.innerGap)) {
                listOf(
                    BabyEventKind.NAPPY_WET,
                    BabyEventKind.NAPPY_DIRTY,
                    BabyEventKind.NAPPY_BOTH
                ).forEach { kind ->
                    Surface(
                        onClick = { onPick(kind) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(Dimens.cardPadding)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
