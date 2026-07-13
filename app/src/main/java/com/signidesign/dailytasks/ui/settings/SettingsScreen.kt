package com.signidesign.dailytasks.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
