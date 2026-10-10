package com.taskrx.mobile.sync

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.core.sync.SyncBadge
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.auth.FakeAuth
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {
    private class Rig(val fx: Fixture, signedIn: Boolean) {
        val job = Job()
        val auth = FakeAuth(initial = if (signedIn) AuthStatus.SignedIn(USER) else AuthStatus.SignedOut)
        val ready = MutableStateFlow(true)
        val gateway = FakeGateway()
        val connectivity = FakeConnectivity()
        val worker = PushWorker(fx.container.syncQueue, fx.container.reminders, fx.container.instances, gateway, connectivity, { USER }, { })
        val coordinator =
            SyncCoordinator(
                CoroutineScope(job + Dispatchers.Default),
                auth,
                ready,
                worker,
                PullSync(gateway, PullApplier(fx.container.reminders, fx.container.instances)) { },
                connectivity,
                pendingPollMs = 20,
            )

        init {
            gateway.feeds[ConvexFunctions.REMINDERS_LIST] = flowOf(Json.parseToJsonElement("[]"))
            gateway.feeds[ConvexFunctions.INSTANCES_LIST] = flowOf(Json.parseToJsonElement("[]"))
        }
    }

    private fun rig(signedIn: Boolean = true, block: suspend Rig.() -> Unit) =
        withFixture(T0) {
            val rig = Rig(this, signedIn)
            try {
                rig.block()
            } finally {
                rig.job.cancel()
            }
        }

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        repeat(500) {
            if (check()) return
            delay(10)
        }
        throw AssertionError("never saw: $what")
    }

    @Test
    fun `signed out it does nothing and the badge says offline`() =
        rig(signedIn = false) {
            fx.reminder("m1")
            coordinator.start()
            delay(200)

            assertTrue(gateway.calls.isEmpty())
            assertEquals(SyncBadge.Offline, coordinator.badge.value)
            coordinator.onForeground()
            delay(100)
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun `signed in and ready it pushes what is waiting, then reads synced`() =
        rig {
            fx.reminder("m1")
            coordinator.start()

            until("the push") { gateway.mutations.isNotEmpty() }
            until("a synced badge") { coordinator.badge.value == SyncBadge.Synced }
            assertEquals("reminders:create", gateway.mutations.single().function)
        }

    @Test
    fun `the badge reads pending while work waits that cannot be pushed`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> error("down") }
            coordinator.start()

            until("a pending badge") { coordinator.badge.value == SyncBadge.Pending }
        }

    @Test
    fun `it waits for the server to hold a token`() =
        rig {
            ready.value = false
            fx.reminder("m1")
            coordinator.start()
            delay(200)
            assertTrue(gateway.calls.isEmpty())

            ready.value = true
            until("the push") { gateway.mutations.isNotEmpty() }
        }

    @Test
    fun `losing the connection reads offline and regaining it drains`() =
        rig {
            coordinator.start()
            until("synced") { coordinator.badge.value == SyncBadge.Synced }

            connectivity.connected.value = false
            until("offline") { coordinator.badge.value == SyncBadge.Offline }

            fx.reminder("m1")
            connectivity.connected.value = true
            until("the push") { gateway.mutations.isNotEmpty() }
        }

    @Test
    fun `a foreground drains while syncing, and signing out stops it`() =
        rig {
            coordinator.start()
            until("synced") { coordinator.badge.value == SyncBadge.Synced }

            fx.reminder("m1")
            coordinator.onForeground()
            until("the push") { gateway.mutations.isNotEmpty() }

            auth.state.value = AuthStatus.SignedOut
            until("offline") { coordinator.badge.value == SyncBadge.Offline }
            gateway.calls.clear()
            fx.container.reminders.create(CreateReminderInput(userId = USER, kind = ReminderKind.Task, name = "later", schedule = dailyTimes(emptyList())))
            coordinator.onForeground()
            delay(150)
            assertTrue(gateway.calls.isEmpty())
        }
}
