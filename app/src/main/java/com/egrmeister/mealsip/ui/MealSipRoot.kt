package com.egrmeister.mealsip.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.OpenPeriodRequest
import com.egrmeister.mealsip.ui.editor.EntryEditorRoute
import com.egrmeister.mealsip.ui.history.HistoryRoute
import com.egrmeister.mealsip.ui.periods.PeriodsRoute
import com.egrmeister.mealsip.ui.settings.PrivacyScreen
import com.egrmeister.mealsip.ui.settings.SettingsRoute
import com.egrmeister.mealsip.ui.setup.SetupRoute
import com.egrmeister.mealsip.ui.theme.MealSipTheme
import com.egrmeister.mealsip.ui.today.DayBoardRoute
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

object Routes {
    const val TODAY = "today"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DAY = "day/{date}"
    const val PERIODS = "periods"
    const val PRIVACY = "privacy"
    const val ENTRY = "entry?entryId={entryId}&date={date}&periodId={periodId}"

    fun day(date: LocalDate) = "day/$date"

    fun newEntry(date: LocalDate, periodId: Long?) = "entry?entryId=-1&date=$date&periodId=${periodId ?: -1}"

    fun editEntry(entryId: Long) = "entry?entryId=$entryId&date=&periodId=-1"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevels = listOf(
    TopLevel(Routes.TODAY, "Today", Icons.Filled.Home),
    TopLevel(Routes.HISTORY, "History", Icons.Filled.DateRange),
    TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun MealSipRoot(container: AppContainer, openPeriodRequests: StateFlow<OpenPeriodRequest?>) {
    MealSipTheme {
        val prefs by container.preferences.prefs.collectAsStateWithLifecycle(initialValue = null)
        Surface(modifier = Modifier.fillMaxSize()) {
            val current = prefs
            when {
                current == null -> Box(Modifier.fillMaxSize())
                !current.setupDone -> SetupRoute(container)
                else -> MainScaffold(container, openPeriodRequests)
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, openPeriodRequests: StateFlow<OpenPeriodRequest?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pendingUndo by container.undo.pending.collectAsStateWithLifecycle()
    val openRequest by openPeriodRequests.collectAsStateWithLifecycle()

    // Undo snackbar: one action at a time; consume() guarantees a single execution.
    LaunchedEffect(pendingUndo?.token) {
        val action = pendingUndo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(action.message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            container.undo.consume(action.token)?.let { scope.launch { container.undoExecutor.perform(it) } }
        } else {
            container.undo.dismiss(action.token)
        }
    }

    // A reminder tap opens Today.
    LaunchedEffect(openRequest?.serial) {
        if (openRequest != null && nav.currentBackStackEntry != null) nav.navigateTopLevel(Routes.TODAY)
    }

    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val showNav = destination?.route in topLevels.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showNav && !wide) {
                NavigationBar {
                    topLevels.forEach { item ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (showNav && wide) {
                NavigationRail {
                    topLevels.forEach { item ->
                        NavigationRailItem(
                            selected = destination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                MealSipNavHost(container, nav, openRequest)
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun MealSipNavHost(container: AppContainer, nav: NavHostController, openRequest: OpenPeriodRequest?) {
    NavHost(navController = nav, startDestination = Routes.TODAY) {
        composable(Routes.TODAY) {
            DayBoardRoute(
                container = container,
                fixedDate = null,
                expandRequest = openRequest,
                onAddManual = { date, periodId -> nav.navigate(Routes.newEntry(date, periodId)) },
                onEditEntry = { nav.navigate(Routes.editEntry(it)) },
                onBack = null,
            )
        }
        composable(
            Routes.DAY,
            arguments = listOf(navArgument("date") { type = NavType.StringType }),
        ) { entry ->
            val date = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: container.todayProvider.current()
            DayBoardRoute(
                container = container,
                fixedDate = date,
                expandRequest = null,
                onAddManual = { d, periodId -> nav.navigate(Routes.newEntry(d, periodId)) },
                onEditEntry = { nav.navigate(Routes.editEntry(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.HISTORY) {
            HistoryRoute(container = container, onOpenDay = { nav.navigate(Routes.day(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                container = container,
                onManagePeriods = { nav.navigate(Routes.PERIODS) },
                onPrivacy = { nav.navigate(Routes.PRIVACY) },
            )
        }
        composable(Routes.PERIODS) {
            PeriodsRoute(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.ENTRY,
            arguments = listOf(
                navArgument("entryId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("date") { type = NavType.StringType; defaultValue = "" },
                navArgument("periodId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            EntryEditorRoute(container = container, onClose = { nav.popBackStack() })
        }
    }
}
