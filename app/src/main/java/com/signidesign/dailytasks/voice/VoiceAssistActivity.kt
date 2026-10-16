package com.signidesign.dailytasks.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signidesign.dailytasks.MainActivity
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.ui.theme.DailyTasksTheme
import com.signidesign.dailytasks.ui.theme.ThemeMode
import com.signidesign.dailytasks.ui.voice.VoiceSheet
import java.time.LocalDate

/**
 * The voice session's own screen.
 *
 * It is a separate activity rather than a sheet inside MainActivity because
 * it is reached from outside the app: the assist gesture, the
 * quick-settings tile, a launcher shortcut, and the lock screen. Showing
 * over the keyguard is the point — a spoken task shouldn't need an unlock
 * first — so nothing on this screen reveals existing content until an
 * answer is asked for.
 */
class VoiceAssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_DICTATION) {
            VoiceMode.DICTATION
        } else {
            VoiceMode.ASSISTANT
        }
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
        val anchorDate =
            if (epochDay >= 0) LocalDate.ofEpochDay(epochDay) else LocalDate.now()

        val settings = (application as TaskApp).container.settingsRepository

        setContent {
            val viewModel: VoiceSessionViewModel =
                viewModel(factory = VoiceSessionViewModel.Factory)
            val state by viewModel.state.collectAsStateWithLifecycle()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

            var micGranted by remember { mutableStateOf(hasMicPermission()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                micGranted = granted
                if (granted) viewModel.begin(mode, anchorDate)
            }

            LaunchedEffect(micGranted) {
                if (micGranted) viewModel.begin(mode, anchorDate)
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // "Open Friday" / "open the calendar" hand off to the main screen.
            LaunchedEffect(state.navigation) {
                when (val destination = state.navigation) {
                    is VoiceNavigation.Day -> openMain(epochDay = destination.date.toEpochDay())
                    VoiceNavigation.Calendar -> openMain(route = ROUTE_CALENDAR)
                    VoiceNavigation.Settings -> openMain(route = ROUTE_SETTINGS)
                    null -> return@LaunchedEffect
                }
                viewModel.navigationHandled()
                finish()
            }

            LaunchedEffect(state.finished) {
                if (state.finished && state.navigation == null) finish()
            }

            DailyTasksTheme(themeMode = themeMode) {
                VoiceSheet(
                    state = state,
                    mode = mode,
                    micGranted = micGranted,
                    recognitionAvailable = viewModel.recognitionAvailable,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onListen = viewModel::listen,
                    onStopListening = viewModel::stopListening,
                    onSubmitText = viewModel::submitText,
                    onClose = { finish() }
                )
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openMain(epochDay: Long? = null, route: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            epochDay?.let { putExtra(EXTRA_EPOCH_DAY, it) }
            route?.let { putExtra(EXTRA_ROUTE, it) }
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_MODE = "com.signidesign.dailytasks.voice.MODE"
        const val EXTRA_EPOCH_DAY = "com.signidesign.dailytasks.voice.EPOCH_DAY"
        const val EXTRA_ROUTE = "com.signidesign.dailytasks.voice.ROUTE"

        const val MODE_ASSISTANT = "assistant"
        const val MODE_DICTATION = "dictation"

        const val ROUTE_CALENDAR = "calendar"
        const val ROUTE_SETTINGS = "settings"

        /** [date] anchors relative dates; pass the day the user is looking at. */
        fun intent(
            context: Context,
            mode: VoiceMode = VoiceMode.ASSISTANT,
            date: LocalDate? = null
        ): Intent = Intent(context, VoiceAssistActivity::class.java).apply {
            putExtra(
                EXTRA_MODE,
                if (mode == VoiceMode.DICTATION) MODE_DICTATION else MODE_ASSISTANT
            )
            date?.let { putExtra(EXTRA_EPOCH_DAY, it.toEpochDay()) }
        }
    }
}
