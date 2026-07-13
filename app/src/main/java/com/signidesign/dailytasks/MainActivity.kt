package com.signidesign.dailytasks

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.navigation.AppNavHost
import com.signidesign.dailytasks.ui.theme.DailyTasksTheme
import com.signidesign.dailytasks.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

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
                AppNavHost(viewModel = viewModel)
            }
        }
    }
}
