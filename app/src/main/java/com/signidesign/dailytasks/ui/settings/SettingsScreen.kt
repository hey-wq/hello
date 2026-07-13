package com.signidesign.dailytasks.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.theme.AppTheme
import com.signidesign.dailytasks.ui.theme.Dimens
import com.signidesign.dailytasks.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle(true)
    val digestEnabled by viewModel.digestEnabled.collectAsStateWithLifecycle(true)
    val storedUrl by viewModel.syncUrl.collectAsStateWithLifecycle("")
    val storedToken by viewModel.syncToken.collectAsStateWithLifecycle("")
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle(0L)
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
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
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Dimens.sectionGap))

            Text(
                text = "APPEARANCE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.itemGap))

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
                ThemeOption(
                    label = "System",
                    description = "Follow the device theme",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onSelect = { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                )
                ThemeOption(
                    label = "Light",
                    description = "Always light",
                    selected = themeMode == ThemeMode.LIGHT,
                    onSelect = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                )
                ThemeOption(
                    label = "Dark",
                    description = "Always dark",
                    selected = themeMode == ThemeMode.DARK,
                    onSelect = { viewModel.setThemeMode(ThemeMode.DARK) }
                )
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            Text(
                text = "NOTIFICATIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.itemGap))

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
                ToggleOption(
                    label = "Task reminders",
                    description = "An hour before and at the start of scheduled tasks",
                    checked = remindersEnabled,
                    onToggle = { viewModel.setRemindersEnabled(it) }
                )
                ToggleOption(
                    label = "Morning digest",
                    description = "A summary of the day at 8:00",
                    checked = digestEnabled,
                    onToggle = { viewModel.setDigestEnabled(it) }
                )
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            Text(
                text = "GOOGLE SHEETS SYNC",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.itemGap))
            SyncSection(
                storedUrl = storedUrl,
                storedToken = storedToken,
                lastSyncAt = lastSyncAt,
                status = syncStatus,
                onSaveAndSync = { url, token -> viewModel.saveSyncConfigAndSync(url, token) }
            )
            Spacer(Modifier.height(Dimens.sectionGap))
        }
    }
}

@Composable
private fun SyncSection(
    storedUrl: String,
    storedToken: String,
    lastSyncAt: Long,
    status: String?,
    onSaveAndSync: (String, String) -> Unit
) {
    var urlDraft by remember { mutableStateOf<String?>(null) }
    var tokenDraft by remember { mutableStateOf<String?>(null) }
    val url = urlDraft ?: storedUrl
    val token = tokenDraft ?: storedToken

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            OutlinedTextField(
                value = url,
                onValueChange = { urlDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Apps Script web app URL") },
                placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
            Spacer(Modifier.height(Dimens.itemGap))
            OutlinedTextField(
                value = token,
                onValueChange = { tokenDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Token") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
            Spacer(Modifier.height(Dimens.itemGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val statusText = status ?: if (lastSyncAt > 0L) {
                        "Last synced ${
                            lastSyncFormatter.format(
                                java.time.Instant.ofEpochMilli(lastSyncAt)
                                    .atZone(java.time.ZoneId.systemDefault())
                            )
                        }"
                    } else {
                        "Not synced yet"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Dimens.innerGap))
                Button(onClick = { onSaveAndSync(url, token) }) {
                    Text("Save & sync")
                }
            }
        }
    }
}

private val lastSyncFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")

@Composable
private fun ToggleOption(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val accent = AppTheme.accent
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dimens.cardPadding)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Dimens.innerGap))
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = accent.accent,
                    checkedThumbColor = accent.onAccent
                )
            )
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val accent = AppTheme.accent
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dimens.cardPadding)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Spacer(Modifier.width(Dimens.innerGap))
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = accent.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
