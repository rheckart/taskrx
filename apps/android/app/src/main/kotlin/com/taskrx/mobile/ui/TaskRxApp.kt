package com.taskrx.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskrx.core.domain.ReminderKind
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.ui.add.AddContent
import com.taskrx.mobile.ui.add.AddViewModel
import com.taskrx.mobile.ui.answer.AnswerContent
import com.taskrx.mobile.ui.answer.AnswerViewModel
import com.taskrx.mobile.ui.components.PermissionBanner
import com.taskrx.mobile.ui.components.ToastHost
import com.taskrx.mobile.ui.forms.NewReminderContent
import com.taskrx.mobile.ui.forms.NewReminderViewModel
import com.taskrx.mobile.ui.today.TodayActions
import com.taskrx.mobile.ui.today.TodayContent
import com.taskrx.mobile.ui.today.TodayViewModel

/**
 * The app shell: one activity, one NavHost, a top bar, and the toast. Mirrors the Expo
 * `(app)` stack.
 *
 * It is local-only until sync lands: there is no sign-in route, and whose data is shown is
 * `AppContainer.session` (see `Session`, the seam sign-in replaces).
 *
 * A notification tap arrives as a route string on `TapHandler.route`, resolved by :core's
 * `resolveTap`. It is collected here, once the NavHost exists, so a tap that cold-starts
 * the app is held until there is somewhere to navigate it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskRxApp(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val tapRoute by container.notifications.taps.route.collectAsState()

    LaunchedEffect(tapRoute) {
        val route = tapRoute ?: return@LaunchedEffect
        // On top of the day, so Back from the answer returns to it.
        navController.navigate(route) { launchSingleTop = true }
        container.notifications.taps.consume()
    }

    // Read here, where `backStack` is observed, and not inside the top bar's slot: the
    // slot is composed lazily and `previousBackStackEntry` is not state, so a read there
    // would never be redone when the stack changes.
    val canGoBack = backStack != null && navController.previousBackStackEntry != null

    TaskRxTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(Routes.titleOf(backStack?.destination?.route)) },
                    navigationIcon = {
                        if (canGoBack) {
                            TextButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.semantics { contentDescription = "Back" },
                            ) { Text("←") }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).imePadding()) {
                AppNavHost(container, navController)
                ToastHost(container.toaster, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer, navController: NavController) {
    val host = navController as androidx.navigation.NavHostController

    /** Back to the day, wherever the person came from: the timeline is where both Kinds live. */
    fun backToToday() {
        if (!host.popBackStack(Routes.TODAY, inclusive = false)) host.navigate(Routes.TODAY)
    }

    NavHost(host, startDestination = Routes.TODAY) {
        composable(Routes.TODAY) {
            val vm = viewModel<TodayViewModel>(factory = viewModelFactory { initializer { TodayViewModel(container) } })
            val state by vm.state.collectAsState()
            val permission by container.notifications.permissions.state.collectAsState()

            // On resume, not on first composition: answering leaves this screen, writes the
            // Instance and comes back, and a first-composition read would show the row
            // still pending. This also picks up rows a background pull merged meanwhile.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

            TodayContent(
                state = state,
                actions =
                    TodayActions(
                        onPreviousDay = { vm.previousDay() },
                        onNextDay = { vm.nextDay() },
                        onToday = { vm.today() },
                        onRefresh = { vm.refresh(pulled = true) },
                        onOpenAnswer = { host.navigate(Routes.answer(it.reminderId, it.scheduledTime)) },
                        onAdd = { host.navigate(Routes.ADD) },
                        onNewTask = { host.navigate(Routes.NEW_TASK) },
                        onNewMedication = { host.navigate(Routes.NEW_MEDICATION) },
                    ),
                banner = {
                    PermissionBanner(permission?.gap, onResolve = { container.notifications.permissions.resolve(it.remedy) })
                },
            )
        }

        composable(
            Routes.ANSWER_PATTERN,
            arguments =
                listOf(
                    navArgument(Routes.ARG_REMINDER_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_SCHEDULED_TIME) { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
        ) { entry ->
            val vm = viewModel<AnswerViewModel>(factory = viewModelFactory { initializer { AnswerViewModel(container, container.toaster::show) } })
            val state by vm.state.collectAsState()
            val reminderId = entry.arguments?.getString(Routes.ARG_REMINDER_ID)
            val scheduledTime = entry.arguments?.getString(Routes.ARG_SCHEDULED_TIME)?.toLongOrNull()

            LaunchedEffect(reminderId, scheduledTime) { vm.load(reminderId, scheduledTime) }

            AnswerContent(
                state = state,
                now = container.clock(),
                onComplete = { vm.complete() },
                onSkip = { vm.skip() },
                onSnooze = { vm.snooze() },
                onDone = ::backToToday,
            )
        }

        composable(Routes.ADD) {
            val vm = viewModel<AddViewModel>(factory = viewModelFactory { initializer { AddViewModel(container) } })
            val state by vm.state.collectAsState()
            // To the day, for both Kinds. One with no time lands in the Backlog.
            LaunchedEffect(state.saved) { if (state.saved) backToToday() }
            AddContent(state, vm, now = container.clock::invoke)
        }

        composable(Routes.NEW_TASK) { NewReminder(container, ReminderKind.Task, ::backToToday) }
        composable(Routes.NEW_MEDICATION) { NewReminder(container, ReminderKind.Medication, ::backToToday) }
    }
}

@Composable
private fun NewReminder(container: AppContainer, kind: ReminderKind, onSaved: () -> Unit) {
    val vm = viewModel<NewReminderViewModel>(factory = viewModelFactory { initializer { NewReminderViewModel(container, kind) } })
    val state by vm.state.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    NewReminderContent(state, vm, now = container.clock::invoke)
}

