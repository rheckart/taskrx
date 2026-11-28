package com.taskrx.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
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
import com.taskrx.core.notices.readSharedMoment
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.ui.auth.AuthActions
import com.taskrx.mobile.ui.auth.AuthContent
import com.taskrx.mobile.ui.auth.AuthViewModel
import com.taskrx.mobile.ui.components.Centered
import com.taskrx.mobile.ui.components.SyncStatusBadge
import kotlinx.coroutines.launch
import com.taskrx.mobile.ui.adherence.AdherenceContent
import com.taskrx.mobile.ui.adherence.AdherenceViewModel
import com.taskrx.mobile.ui.backlog.BacklogActions
import com.taskrx.mobile.ui.backlog.BacklogContent
import com.taskrx.mobile.ui.backlog.BacklogViewModel
import com.taskrx.mobile.ui.history.HistoryContent
import com.taskrx.mobile.ui.history.HistoryViewModel
import com.taskrx.mobile.ui.notices.NoticesContent
import com.taskrx.mobile.ui.notices.NoticesViewModel
import com.taskrx.mobile.ui.notices.ShareContent
import com.taskrx.mobile.ui.notices.ShareDestination
import com.taskrx.mobile.ui.notices.ShareIntake
import com.taskrx.mobile.ui.notices.ShareViewModel
import com.taskrx.mobile.ui.notices.taskDraftFromShare
import com.taskrx.mobile.ui.notices.taskDraftFromText
import com.taskrx.mobile.ui.reminders.EditReminderContent
import com.taskrx.mobile.ui.reminders.EditReminderViewModel
import com.taskrx.mobile.ui.reminders.EditState
import com.taskrx.mobile.ui.reminders.RemindersActions
import com.taskrx.mobile.ui.reminders.RemindersContent
import com.taskrx.mobile.ui.reminders.RemindersViewModel
import com.taskrx.mobile.ui.search.DrugSearchContent
import com.taskrx.mobile.ui.settings.SettingsContent
import com.taskrx.mobile.ui.settings.SettingsViewModel
import com.taskrx.mobile.ui.search.DrugSearchViewModel
import com.taskrx.mobile.ui.sync.SyncProblemsContent
import com.taskrx.mobile.ui.sync.SyncProblemsViewModel
import com.taskrx.mobile.ui.dose.AddDoseContent
import com.taskrx.mobile.ui.dose.AddDoseViewModel
import com.taskrx.mobile.ui.supplies.SuppliesActions
import com.taskrx.mobile.ui.supplies.SuppliesContent
import com.taskrx.mobile.ui.supplies.SuppliesViewModel
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
import com.taskrx.mobile.ui.catchup.CatchUpContent
import com.taskrx.mobile.ui.catchup.CatchUpViewModel
import com.taskrx.mobile.ui.today.TodayViewModel

/**
 * The app shell: one activity, one NavHost, a top bar, and the toast. Mirrors the Expo
 * `(app)` stack.
 *
 * With a Clerk key configured, a signed-out person sees the sign-in / sign-up screens and
 * nothing else; signed in, the day. Without one the app is local-only. Whose data is shown is
 * `AppContainer.session` (see `Session`).
 *
 * A notification tap arrives as a route string on `TapHandler.route`, resolved by :core's
 * `resolveTap`. It is collected here, once the NavHost exists, so a tap that cold-starts
 * the app is held until there is somewhere to navigate it.
 */
@Composable
fun TaskRxApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    shareIntake: ShareIntake = remember { ShareIntake() },
) {
    val status by container.auth.status.collectAsState()
    // A build with no Clerk key has no sign-in and runs local-only, as the Expo app did.
    when {
        !container.auth.configured || status is AuthStatus.SignedIn -> AppShell(container, status is AuthStatus.SignedIn, shareIntake, modifier)
        status == AuthStatus.Loading -> TaskRxTheme { Centered { CircularProgressIndicator() } }
        else -> TaskRxTheme { AuthScreen(container.auth, modifier) }
    }
}

@Composable
private fun AuthScreen(auth: AuthGateway, modifier: Modifier) {
    val vm = screenViewModel { AuthViewModel(auth) }
    val state by vm.state.collectAsState()
    AuthContent(
        state,
        AuthActions(
            onEmail = vm::setEmail,
            onCode = vm::setCode,
            onSend = vm::sendCode,
            onVerify = vm::verify,
            onChangeEmail = vm::changeEmail,
            onMode = vm::switchMode,
        ),
        modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    container: AppContainer,
    signedIn: Boolean,
    shareIntake: ShareIntake,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val navReady = backStack != null
    val tapRoute by container.notifications.taps.route.collectAsState()
    val pendingShare by shareIntake.pending.collectAsState()

    // Scaffold subcomposes its body (NavHost) during layout, after the outer composition's
    // first effects run. Wait until the graph is attached (navReady) before navigating so a
    // cold-start tap or share neither crashes nor consumes its payload too early.
    LaunchedEffect(tapRoute, navReady) {
        if (!navReady) return@LaunchedEffect
        val route = tapRoute ?: return@LaunchedEffect
        // On top of the day, so Back from the answer returns to it.
        navController.navigate(route) { launchSingleTop = true }
        container.notifications.taps.consume()
    }

    LaunchedEffect(pendingShare, navReady) {
        if (!navReady) return@LaunchedEffect
        val incoming = pendingShare ?: return@LaunchedEffect
        shareIntake.consume()
        when (incoming.destination) {
            ShareDestination.Share ->
                navController.navigate(Routes.share(incoming.text)) {
                    if (navController.currentDestination?.route == Routes.SHARE_PATTERN) {
                        popUpTo(Routes.SHARE_PATTERN) { inclusive = true }
                    }
                }
            ShareDestination.NewTask -> {
                val draft = taskDraftFromText(incoming.text)
                val detectedAt = readSharedMoment(incoming.text, container.clock())?.at
                navController.navigate(Routes.newTask(draft.name, draft.notes, detectedAt)) {
                    if (navController.currentDestination?.route == Routes.NEW_TASK_PATTERN) {
                        popUpTo(Routes.NEW_TASK_PATTERN) { inclusive = true }
                    }
                }
            }
        }
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
                    actions = {
                        if (signedIn) {
                            container.sync?.let { sync ->
                                val badge by sync.badge.collectAsState()
                                SyncStatusBadge(badge, onProblems = { navController.navigate(Routes.SYNC_PROBLEMS) })
                            }
                            if (backStack?.destination?.route == Routes.TODAY) {
                                val scope = rememberCoroutineScope()
                                TextButton(onClick = { scope.launch { container.auth.signOut() } }) { Text("Sign out") }
                            }
                        }
                    },
                    navigationIcon = {
                        if (canGoBack) {
                            TextButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.semantics { contentDescription = "Back" },
                            ) { Text("←", fontSize = 32.sp) }
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
                now = container.clock::invoke,
                snoozeMinutes = container.settings.snoozeMinutes.collectAsState().value,
                actions =
                    TodayActions(
                        onPreviousDay = { vm.previousDay() },
                        onNextDay = { vm.nextDay() },
                        onToday = { vm.today() },
                        onToggleHideCompleted = { vm.toggleHideCompleted() },
                        onSelectKind = vm::selectKind,
                        onTakeAsNeeded = { vm.takeAsNeeded(it) },
                        onAnswerTask = { row, action -> vm.answerTask(row, action) },
                        onMoveTask = { vm.beginMove(it) },
                        onEditReminder = { host.navigate(Routes.edit(it)) },
                        onConfirmMove = { vm.confirmMove(it) },
                        onCancelMove = { vm.cancelMove() },
                        onAnswerSlot = { rows, action, hour, minute -> vm.answerSlot(rows, action, hour, minute) },
                        onRefresh = { vm.refresh(pulled = true) },
                        onOpenAnswer = { host.navigate(Routes.answer(it.reminderId, it.scheduledTime)) },
                        onChangeTime = { row, hour, minute -> vm.changeTakenTime(row, hour, minute) },
                        onUndo = { vm.undo(it) },
                        onAdd = { host.navigate(Routes.ADD) },
                        onNewTask = { host.navigate(Routes.NEW_TASK) },
                        onNewMedication = { host.navigate(Routes.NEW_MEDICATION) },
                        onAddDose = { host.navigate(Routes.ADD_DOSE) },
                        onReminders = { host.navigate(Routes.REMINDERS) },
                        onBacklog = { host.navigate(Routes.BACKLOG) },
                        onAdherence = { host.navigate(Routes.ADHERENCE) },
                        onSupplies = { host.navigate(Routes.SUPPLIES) },
                        onHistory = { host.navigate(Routes.HISTORY) },
                        onCatchUp = { host.navigate(Routes.CATCH_UP) },
                        onNotices = { host.navigate(Routes.NOTICES) },
                        onSettings = { host.navigate(Routes.SETTINGS) },
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
                onCompleteAt = { hour, minute -> vm.complete(hour, minute) },
                onSkip = { vm.skip() },
                snoozeMinutes = container.settings.snoozeMinutes.collectAsState().value,
                onSnooze = { vm.snooze() },
                onReschedule = { hour, minute -> vm.rescheduleTo(hour, minute) },
                onRescheduleToInstant = { vm.rescheduleToInstant(it) },
                onUndo = { vm.undo() },
                onDone = ::backToToday,
            )
        }

        composable(Routes.SETTINGS) {
            val vm = screenViewModel { SettingsViewModel(container) }
            val state by vm.state.collectAsState()
            val snoozeMinutes by container.settings.snoozeMinutes.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            SettingsContent(
                snoozeMinutes = snoozeMinutes,
                onSnoozeMinutes = container.settings::setSnoozeMinutes,
                watchedSources = state.sources,
                watchedLoading = state.loading,
                watchedError = state.error,
                adding = state.adding,
                onStartAdd = vm::startAdd,
                onCancelAdd = vm::cancelAdd,
                newKind = state.kind,
                onNewKind = vm::setKind,
                newValue = state.value,
                onNewValue = vm::setValue,
                newLabel = state.label,
                onNewLabel = vm::setLabel,
                canSaveSource = state.canSave,
                onSaveSource = vm::saveSource,
                onRemoveSource = vm::remove,
                listenerAllowed = state.listenerAllowed,
                onOpenListenerSettings = vm::openListenerSettings,
                seenApps = state.seenApps,
                onApproveSeenApp = { vm.approveSeenApp(it) },
            )
        }

        composable(Routes.NOTICES) {
            val vm = screenViewModel { NoticesViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            NoticesContent(
                state = state,
                onRefresh = { vm.refresh(pulled = true) },
                onBeginTask = vm::beginTask,
                onDismiss = vm::dismiss,
                onDraftAt = vm::setDraftAt,
                onSaveTask = vm::saveTask,
                onCancelTask = vm::cancelTask,
                onApply = vm::applyProposal,
                onBeginPick = vm::beginPick,
                onPickTask = vm::pickTask,
                onCancelPick = vm::cancelPick,
                onBeginOccurrence = vm::beginOccurrence,
                onChooseOccurrence = vm::chooseOccurrence,
                onCancelOccurrence = vm::cancelOccurrence,
                now = container.clock::invoke,
            )
        }

        composable(
            Routes.SHARE_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_TEXT) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val initialText = entry.arguments?.getString(Routes.ARG_TEXT).orEmpty()
            val vm = screenViewModel { ShareViewModel(container, initialText = initialText) }
            val state by vm.state.collectAsState()
            LaunchedEffect(state.saved) {
                if (state.saved) {
                    host.navigate(Routes.NOTICES) { popUpTo(Routes.SHARE_PATTERN) { inclusive = true } }
                }
            }
            ShareContent(
                state = state,
                onTitle = vm::setTitle,
                onSave = vm::save,
                onCreateTask = {
                    val draft = taskDraftFromShare(state.title, state.text)
                    host.navigate(Routes.newTask(draft.name, draft.notes, state.detectedAt))
                },
                onDetectedAt = vm::setDetectedAt,
                now = container.clock::invoke,
            )
        }

        composable(Routes.ADD) {
            val vm = viewModel<AddViewModel>(factory = viewModelFactory { initializer { AddViewModel(container) } })
            val state by vm.state.collectAsState()
            // To the day, for both Kinds. One with no time lands in the Backlog.
            LaunchedEffect(state.saved) { if (state.saved) backToToday() }
            AddContent(state, vm, now = container.clock::invoke)
        }

        composable(
            Routes.NEW_TASK_PATTERN,
            arguments =
                listOf(
                    navArgument(Routes.ARG_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_NOTES) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_AT) { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
        ) { entry ->
            NewReminder(
                container,
                ReminderKind.Task,
                ::backToToday,
                initialName = entry.arguments?.getString(Routes.ARG_NAME).orEmpty(),
                initialNotes = entry.arguments?.getString(Routes.ARG_NOTES).orEmpty(),
                initialAt = entry.arguments?.getString(Routes.ARG_AT)?.toLongOrNull(),
            )
        }
        composable(
            Routes.NEW_MEDICATION_PATTERN,
            arguments =
                listOf(
                    navArgument(Routes.ARG_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_RXCUI) { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
        ) { entry ->
            NewReminder(
                container,
                ReminderKind.Medication,
                ::backToToday,
                initialName = entry.arguments?.getString(Routes.ARG_NAME).orEmpty(),
                rxcui = entry.arguments?.getString(Routes.ARG_RXCUI),
            )
        }

        composable(Routes.BACKLOG) {
            val vm = screenViewModel { BacklogViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            BacklogContent(
                state,
                BacklogActions(
                    onRefresh = { vm.refresh(pulled = true) },
                    onComplete = { vm.complete(it) },
                    onMoveToTimeline = { reminder, schedule -> vm.moveToTimeline(reminder, schedule) },
                ),
                now = container.clock::invoke,
            )
        }

        composable(Routes.CATCH_UP) {
            val vm = screenViewModel { CatchUpViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            CatchUpContent(
                state = state,
                onRefresh = { vm.refresh(pulled = true) },
                onAnswer = { rows, action -> vm.answer(rows, action) },
                onUndo = { vm.undo() },
            )
        }

        composable(Routes.HISTORY) {
            val vm = screenViewModel { HistoryViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            HistoryContent(state, onRefresh = { vm.refresh(pulled = true) }, now = container.clock())
        }

        composable(Routes.SYNC_PROBLEMS) {
            val vm = screenViewModel { SyncProblemsViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            SyncProblemsContent(state, onRetry = vm::retry)
        }

        composable(Routes.REMINDERS) {
            val vm = screenViewModel { RemindersViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            RemindersContent(
                state,
                RemindersActions(
                    onRefresh = { vm.refresh(pulled = true) },
                    onEdit = { host.navigate(Routes.edit(it.id)) },
                    onStop = { vm.stop(it) },
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onSearchDrug = { host.navigate(Routes.DRUG_SEARCH) },
                ),
                now = container.clock::invoke,
            )
        }

        composable(
            Routes.EDIT_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_EDIT_ID) { type = NavType.StringType }),
        ) { entry ->
            val vm = screenViewModel { EditReminderViewModel(container) }
            val state by vm.state.collectAsState()
            val id = entry.arguments?.getString(Routes.ARG_EDIT_ID)
            LaunchedEffect(id) { vm.load(id) }
            LaunchedEffect((state as? EditState.Editing)?.saved) {
                if ((state as? EditState.Editing)?.saved == true) host.popBackStack()
            }
            EditReminderContent(state, vm, now = container.clock::invoke)
        }

        composable(Routes.ADHERENCE) {
            val vm = screenViewModel { AdherenceViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            AdherenceContent(state, onRefresh = { vm.refresh(pulled = true) })
        }

        composable(Routes.ADD_DOSE) {
            val vm = screenViewModel { AddDoseViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            LaunchedEffect(state.saved) { if (state.saved) host.popBackStack() }
            AddDoseContent(
                state,
                onRefresh = { vm.refresh(pulled = true) },
                onRecord = { medication, hour, minute -> vm.record(medication, hour, minute) },
                now = container.clock::invoke,
            )
        }

        composable(Routes.SUPPLIES) {
            val vm = screenViewModel { SuppliesViewModel(container) }
            val state by vm.state.collectAsState()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            SuppliesContent(
                state,
                SuppliesActions(
                    onRefresh = { vm.refresh(pulled = true) },
                    onSort = vm::sortBy,
                    onEdit = vm::edit,
                    onCancelEdit = vm::cancelEdit,
                    onSave = { id, draft -> vm.saveSupply(id, draft) },
                    onOpenBacklog = { host.navigate(Routes.BACKLOG) },
                    onRecordDose = { medication, hour, minute -> vm.recordDose(medication, hour, minute) },
                    onEditMedication = { host.navigate(Routes.edit(it.id)) },
                ),
                now = container.clock::invoke,
            )
        }

        composable(Routes.DRUG_SEARCH) {
            val vm = screenViewModel { DrugSearchViewModel(container.drugSearch) }
            val state by vm.state.collectAsState()
            DrugSearchContent(
                state,
                onQuery = { vm.setQuery(it) },
                onPick = { host.navigate(Routes.newMedication(it.name, it.rxcui)) },
            )
        }
    }
}

@Composable
private fun NewReminder(
    container: AppContainer,
    kind: ReminderKind,
    onSaved: () -> Unit,
    initialName: String = "",
    rxcui: String? = null,
    initialNotes: String = "",
    initialAt: Long? = null,
) {
    val vm = screenViewModel { NewReminderViewModel(container, kind, initialName, rxcui, initialNotes, initialAt) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    NewReminderContent(state, vm, now = container.clock::invoke)
}


/** A ViewModel scoped to the destination it is created in, built by [create]. */
@Composable
private inline fun <reified T : ViewModel> screenViewModel(crossinline create: () -> T): T =
    viewModel<T>(factory = viewModelFactory { initializer { create() } })
