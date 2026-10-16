package com.signidesign.dailytasks.ui.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
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
    val spokenReplies by viewModel.voiceSpokenReplies.collectAsStateWithLifecycle(true)
    val preferOffline by viewModel.voicePreferOffline.collectAsStateWithLifecycle(false)
    val confirmDeletions by viewModel.voiceConfirmDeletions.collectAsStateWithLifecycle(true)
    val earcons by viewModel.voiceEarcons.collectAsStateWithLifecycle(true)
    val context = LocalContext.current

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
                text = "VOICE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.itemGap))

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.itemGap)) {
                ToggleOption(
                    label = "Spoken replies",
                    description = "Read answers aloud as well as showing them",
                    checked = spokenReplies,
                    onToggle = { viewModel.setVoiceSpokenReplies(it) }
                )
                ToggleOption(
                    label = "Sound cues",
                    description = "Short tones when the mic opens and a command lands",
                    checked = earcons,
                    onToggle = { viewModel.setVoiceEarcons(it) }
                )
                ToggleOption(
                    label = "On-device recognition",
                    description = "Private and works offline, but needs a downloaded " +
                        "language pack — turn it off if nothing is recognised",
                    checked = preferOffline,
                    onToggle = { viewModel.setVoicePreferOffline(it) }
                )
                ToggleOption(
                    label = "Confirm spoken deletions",
                    description = "Ask before a voice command removes a task",
                    checked = confirmDeletions,
                    onToggle = { viewModel.setVoiceConfirmDeletions(it) }
                )
                AssistantRoleCard(onOpen = { openAssistantSettings(context) })
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

/**
 * Daily can take over the device's assist gesture. That is the closest a
 * sideloaded app gets to a hotword: Android gives no free high-quality
 * always-listening keyword, and a continuous recognizer loop costs battery
 * and still gets throttled, so the gesture, the tile and the shortcut are
 * the honest entry points.
 */
@Composable
private fun AssistantRoleCard(onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Text(
                text = "Hold-for-Daily",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Make Daily the device's assistant app and the home gesture " +
                    "or power long-press opens voice straight into your list. " +
                    "There's also a quick-settings tile and a long-press shortcut " +
                    "on the app icon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.itemGap))
            OutlinedButton(onClick = onOpen) {
                Text("Choose the assistant app")
            }
        }
    }
}

/**
 * API 29+ can ask for the assistant role directly; older versions, and
 * devices whose OEM skin refuses the request, get the settings screen.
 */
private fun openAssistantSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null &&
            roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        ) {
            val request = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            if (startSafely(context, request)) return
        }
    }
    val fallbacks = listOf(
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS)
    )
    fallbacks.firstOrNull { startSafely(context, it) }
}

private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

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
