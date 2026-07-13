package com.signidesign.dailytasks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.signidesign.dailytasks.MainActivity
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.util.timeFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val widgetDateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
private const val MAX_ROWS = 6

/**
 * Home-screen "today" widget. The task list is collected as a Room flow
 * inside the Glance composition, so checking a task off (from the widget or
 * the app) updates it live while the process is alive; updatePeriodMillis
 * refreshes it across day boundaries.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as TaskApp).container.taskRepository
        provideContent {
            GlanceTheme {
                val today = LocalDate.now()
                val tasks by repository.tasksForDay(today)
                    .collectAsState(initial = emptyList())
                WidgetContent(today, tasks)
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        val container = (context.applicationContext as TaskApp).container
        val task = container.taskRepository.taskById(taskId) ?: return
        container.taskRepository.setDone(task, !task.isDone)
        container.reminderManager.resyncTask(taskId)
    }

    companion object {
        val TaskIdKey = ActionParameters.Key<Long>("taskId")
    }
}

@Composable
private fun WidgetContent(date: LocalDate, tasks: List<TaskEntity>) {
    val open = tasks.filter { !it.isDone }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Text(
            text = date.format(widgetDateFormatter),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.height(8.dp))
        if (open.isEmpty()) {
            Text(
                text = "Nothing planned.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
            )
        } else {
            open.take(MAX_ROWS).forEach { task ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CheckBox(
                        checked = false,
                        onCheckedChange = actionRunCallback<ToggleTaskAction>(
                            actionParametersOf(ToggleTaskAction.TaskIdKey to task.id)
                        )
                    )
                    Text(
                        text = task.title,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp
                        )
                    )
                    task.startTime?.let { start ->
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = start.format(timeFormatter),
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
            if (open.size > MAX_ROWS) {
                Text(
                    text = "+${open.size - MAX_ROWS} more",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.padding(top = 2.dp)
                )
            }
        }
    }
}
