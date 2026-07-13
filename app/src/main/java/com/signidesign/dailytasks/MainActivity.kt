package com.signidesign.dailytasks

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signidesign.dailytasks.notifications.ReminderManager
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.navigation.AppNavHost
import com.signidesign.dailytasks.ui.theme.DailyTasksTheme
import com.signidesign.dailytasks.ui.theme.ThemeMode
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Notification taps carry the day they refer to.
        val epochDayExtra = intent.getLongExtra(ReminderManager.EXTRA_EPOCH_DAY, -1L)
        val initialDate =
            if (epochDayExtra >= 0) LocalDate.ofEpochDay(epochDayExtra) else LocalDate.now()

        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

            NotificationPermissionRequest()

            // Keep system bar icon contrast in sync with the effective theme,
            // including when the user overrides the system setting.
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, Color.TRANSPARENT
                    ) { darkTheme }
                )
            }

            DailyTasksTheme(themeMode = themeMode) {
                AppNavHost(viewModel = viewModel, initialDate = initialDate)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun NotificationPermissionRequest() {
        if (Build.VERSION.SDK_INT < 33) return
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
