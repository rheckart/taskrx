package com.taskrx.mobile

import android.content.Context
import com.taskrx.mobile.adherence.AdherenceReader
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.batch.BatchAnswers
import com.taskrx.mobile.auth.ClerkAuthGateway
import com.taskrx.mobile.auth.ConvexSession
import com.taskrx.mobile.auth.LocalOnlyAuth
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.IdGenerator
import com.taskrx.mobile.data.LocalAdoption
import com.taskrx.mobile.data.RandomUuid
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.SyncQueueRepository
import com.taskrx.mobile.data.SystemClock
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.notifications.NotificationsModule
import com.taskrx.mobile.settings.AppSettings
import com.taskrx.mobile.refill.RefillReader
import com.taskrx.mobile.sync.AndroidConnectivity
import com.taskrx.mobile.sync.Connectivity
import com.taskrx.mobile.sync.ConvexDrugSearch
import com.taskrx.mobile.sync.ConvexGateway
import com.taskrx.mobile.sync.PullApplier
import com.taskrx.mobile.sync.PullSync
import com.taskrx.mobile.sync.PushWorker
import com.taskrx.mobile.sync.RealConvexGateway
import com.taskrx.mobile.sync.ReauthenticatingGateway
import com.taskrx.mobile.sync.StuckChanges
import com.taskrx.mobile.sync.SyncCoordinator
import com.taskrx.mobile.sweep.MissedSweepRunner
import com.taskrx.mobile.sweep.RoomSweepStore
import com.taskrx.mobile.ui.components.Toaster
import com.taskrx.mobile.ui.search.DrugSearchService
import com.taskrx.mobile.ui.search.OfflineDrugSearch
import com.taskrx.mobile.undo.UndoAnswers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual dependency injection: one object that builds the graph once.
 *
 * Everything here is a singleton for the life of the process, and that matters for one
 * member: the [sweep] runner's lock is what stops two foregrounds minting one moment
 * twice, so every caller must reach the same instance.
 *
 * The database is a parameter so tests build the same graph over an in-memory Room.
 */
class AppContainer(
    context: Context,
    val database: TaskRxDatabase,
    val clock: Clock = SystemClock,
    newId: IdGenerator = RandomUuid,
    /** The RxNorm lookup. Null means "the Convex-backed one when there is a [remote], else offline". */
    drugSearch: DrugSearchService? = null,
    /** Sign-in. [LocalOnlyAuth] in a build with no Clerk key. */
    val auth: AuthGateway = LocalOnlyAuth,
    /** The server, when this build has one. Null runs local-only. */
    remote: Remote? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What talks to the server: the gateway, whether Convex holds a valid token, and the network. */
    class Remote(val gateway: ConvexGateway, val ready: StateFlow<Boolean>, val connectivity: Connectivity)

    val syncQueue = SyncQueueRepository(database.syncQueueDao(), clock)
    val reminders = RemindersRepository(database, syncQueue, clock, newId)
    val instances = ReminderInstancesRepository(database, syncQueue, reminders, clock, newId)

    val sweep = MissedSweepRunner(RoomSweepStore(reminders, instances))
    val adherence = AdherenceReader(sweep, instances)
    val refills = RefillReader(reminders, newId)

    val notifications = NotificationsModule(context, reminders, instances, clock)

    /** The person's choices from Settings, on this phone only. */
    val settings = AppSettings(context)

    /** Whose data this is: the Clerk user when signed in, else the local user (see [Session]). */
    val session = Session(notifications.currentUser, auth) { from, to -> LocalAdoption(database).adopt(from, to) }

    /** Take, snooze or skip every dose due at one moment: Today's slot buttons and the shade's batch notification. */
    val batch =
        BatchAnswers(
            reminders,
            instances,
            clock,
            snoozeMinutes = { settings.snoozeMinutes.value },
            userId = { session.userId },
            armSnooze = notifications.scheduler::snooze,
            rescheduleAfterWrite = notifications::rescheduleAfterWrite,
            dismiss = notifications::dismissPosted,
        )

    /** Takes a take, a skip or a snooze back: the confirmation card's and the timeline row's Undo. */
    val undo =
        UndoAnswers(
            reminders,
            instances,
            clock,
            userId = { session.userId },
            cancelSnooze = notifications.scheduler::cancelSnooze,
            rescheduleAfterWrite = notifications::rescheduleAfterWrite,
        )

    val drugSearch: DrugSearchService =
        drugSearch
            ?: remote?.let { r ->
                ConvexDrugSearch(r.gateway) { session.signedInUserId != null && r.ready.value && r.connectivity.isConnected() }
            }
            ?: OfflineDrugSearch

    /** The changes the push worker gave up on, and the way to try them again. */
    val stuckChanges = StuckChanges(syncQueue, reminders, instances) { session.signedInUserId }

    /** Push and pull, running while signed in. Null in a local-only build. */
    val sync: SyncCoordinator? =
        remote?.let { r ->
            val worker = PushWorker(syncQueue, reminders, instances, r.gateway, r.connectivity, userId = { session.signedInUserId })
            SyncCoordinator(scope, auth, r.ready, worker, stuckChanges, PullSync(r.gateway, PullApplier(reminders, instances) { notifications.rescheduleAfterWrite(session.userId) }), r.connectivity)
        }

    init {
        session.follow(scope) { notifications.onForeground() }
        sync?.start()
    }

    /** The one toast, shown by whichever screen is up when a write confirms. */
    val toaster = Toaster()

    companion object {
        fun create(context: Context): AppContainer {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clerkKey = BuildConfig.CLERK_PUBLISHABLE_KEY
            val convexUrl = BuildConfig.CONVEX_URL
            // Both or neither: sync needs a signed-in user AND a server, so a half-configured
            // build is a local-only build rather than one that signs in and goes nowhere.
            if (clerkKey.isEmpty() || convexUrl.isEmpty()) return AppContainer(context, TaskRxDatabase.open(context), scope = scope)

            val auth = ClerkAuthGateway(context, clerkKey, scope)
            val convex = ConvexSession(convexUrl, auth, context.applicationContext, scope)
            val remote = Remote(ReauthenticatingGateway(RealConvexGateway(convex.client), convex::reauthenticate), convex.ready, AndroidConnectivity(context))
            return AppContainer(context, TaskRxDatabase.open(context), auth = auth, remote = remote, scope = scope)
        }
    }
}
