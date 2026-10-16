package com.signidesign.dailytasks.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.signidesign.dailytasks.ui.AppViewModel
import com.signidesign.dailytasks.ui.calendar.CalendarScreen
import com.signidesign.dailytasks.ui.day.DayScreen
import com.signidesign.dailytasks.ui.settings.SettingsScreen
import java.time.LocalDate

object Routes {
    const val DAY = "day?epochDay={epochDay}"
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"

    fun day(date: LocalDate? = null): String =
        if (date == null) "day" else "day?epochDay=${date.toEpochDay()}"
}

/**
 * @param initialRoute a destination to open on top of the day view, used
 *   when a voice session says "open the calendar" and hands off to here.
 */
@Composable
fun AppNavHost(
    viewModel: AppViewModel,
    initialDate: LocalDate = LocalDate.now(),
    initialRoute: String? = null
) {
    val navController = rememberNavController()

    LaunchedEffect(initialRoute) {
        when (initialRoute) {
            Routes.CALENDAR -> navController.navigate(Routes.CALENDAR)
            Routes.SETTINGS -> navController.navigate(Routes.SETTINGS)
        }
    }

    NavHost(navController = navController, startDestination = Routes.DAY) {
        composable(
            route = Routes.DAY,
            arguments = listOf(
                navArgument("epochDay") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val epochDay = backStackEntry.arguments?.getLong("epochDay") ?: -1L
            val pageDate =
                if (epochDay >= 0) LocalDate.ofEpochDay(epochDay) else initialDate
            DayScreen(
                viewModel = viewModel,
                initialDate = pageDate,
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(
                viewModel = viewModel,
                onDaySelected = { date ->
                    navController.navigate(Routes.day(date)) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
