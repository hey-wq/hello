package com.signidesign.dailytasks.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signidesign.dailytasks.data.DaySummary
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())

@Composable
fun CalendarScreen(
    viewModel: AppViewModel,
    onDaySelected: (LocalDate) -> Unit,
    onBack: () -> Unit
) {
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthValue)

    val summaries by remember(month) { viewModel.summariesFor(month) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byDate = summaries.associateBy { it.dayDate }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Dimens.screenPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { monthValue = month.minusMonths(1).toString() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { monthValue = month.plusMonths(1).toString() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            Text(
                text = month.atDay(1).format(monthFormatter),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = month.year.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.sectionGap))

            WeekdayHeader()
            Spacer(Modifier.height(Dimens.innerGap))
            MonthGrid(
                month = month,
                summaries = byDate,
                onDaySelected = onDaySelected
            )
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        orderedDaysOfWeek().forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun orderedDaysOfWeek(): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    summaries: Map<LocalDate, DaySummary>,
    onDaySelected: (LocalDate) -> Unit
) {
    val days = orderedDaysOfWeek()
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = days.indexOf(firstOfMonth.dayOfWeek)
    val cells: List<LocalDate?> =
        List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                summary = summaries[date],
                                onClick = { onDaySelected(date) }
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    summary: DaySummary?,
    onClick: () -> Unit
) {
    val accent = AppTheme.accent
    val isToday = date == LocalDate.now()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (isToday) Modifier.border(
                    1.5.dp,
                    MaterialTheme.colorScheme.onBackground,
                    MaterialTheme.shapes.small
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(top = 8.dp)
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(5.dp))
        if (summary != null && summary.total > 0) {
            DensityDots(summary = summary, accentColor = accent.accent)
        }
    }
}

/**
 * Density indicator: one dot per task up to four, then a "+N". Timed tasks
 * render as accent dots, untimed as neutral — timed first so the accent
 * reads as "this day has scheduled commitments".
 */
@Composable
private fun DensityDots(summary: DaySummary, accentColor: androidx.compose.ui.graphics.Color) {
    val maxDots = 4
    val timedDots = summary.timed.coerceAtMost(maxDots)
    val untimedDots = (summary.total - summary.timed).coerceAtMost(maxDots - timedDots)
    val overflow = summary.total - timedDots - untimedDots

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(timedDots) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
        repeat(untimedDots) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
