package com.taskrx.mobile.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sync.SyncWorkerStatus
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

@RunWith(RobolectricTestRunner::class)
class StuckChangesTest {
    private class Rig(val fx: Fixture) {
        val gateway = FakeGateway()
        val worker = PushWorker(fx.container.syncQueue, fx.container.reminders, fx.container.instances, gateway, FakeConnectivity(), { USER }, { })
        val stuck = StuckChanges(fx.container.syncQueue, fx.container.reminders, fx.container.instances) { USER }

        /** Five failed drains: the push worker gives up and sets the change aside. */
        suspend fun failFiveTimes() {
            gateway.onMutation = { _, _ -> error("rejected") }
            repeat(5) { worker.drain() }
        }
    }

    private fun rig(block: suspend Rig.() -> Unit) = withFixture(T0) { Rig(this).block() }

    private suspend fun Fixture.rowStatus(id: String) = database.reminderDao().get(id)!!.syncStatus

    private suspend fun Fixture.instanceStatuses() = container.instances.list(com.taskrx.mobile.data.InstanceFilter(USER)).map { database.reminderInstanceDao().get(it.id)!!.syncStatus }.toSet()

    @Test
    fun `a dose that failed for good is listed in words, then goes up when tried again`() =
        rig {
            fx.reminder("m1", name = "Metformin")
            val dose = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            fx.container.instances.markCompleted(dose.id, T0)
            val waiting = fx.container.syncQueue.pending().size
            failFiveTimes()

            val listed = stuck.list()
            assertEquals(waiting, listed.size)
            assertTrue(listed.any { it.what == "Reminder: Metformin" }, listed.toString())
            assertTrue(listed.any { it.what.startsWith("Answer to Metformin, due ") }, listed.toString())
            // The Reminder's own refusal, and a dose that then could not find its Reminder on the server.
            assertEquals("rejected", listed.single { it.what == "Reminder: Metformin" }.error)
            assertTrue(listed.all { it.error.isNotBlank() })
            assertEquals("error", fx.rowStatus("m1"))
            assertEquals(setOf("error"), fx.instanceStatuses())
            assertTrue(fx.container.syncQueue.pending().isEmpty(), "set aside, so not waiting")

            gateway.calls.clear()
            gateway.onMutation = { _, args -> JsonPrimitive("cid-" + args["localId"]) }
            assertEquals(waiting, stuck.retryAll())
            assertEquals("pending", fx.rowStatus("m1"), "back to waiting, no longer marked as failed")
            assertEquals(setOf("pending"), fx.instanceStatuses())
            worker.drain()

            assertEquals(0, stuck.count())
            assertTrue(fx.container.syncQueue.pending().isEmpty())
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)
            assertEquals(
                listOf("reminders:create", "reminderInstances:create", "reminderInstances:markCompleted"),
                gateway.mutations.map { it.function }.take(3),
                "in the order they were written, so the dose follows its Reminder",
            )
            assertEquals("synced", fx.rowStatus("m1"))
        }

    @Test
    fun `another user's stuck changes are not listed or retried`() =
        rig {
            fx.reminder("m1")
            failFiveTimes()
            val other = StuckChanges(fx.container.syncQueue, fx.container.reminders, fx.container.instances) { "someone-else" }

            assertEquals(0, other.count())
            assertEquals(0, other.retryAll())
            assertTrue(stuck.count() > 0)
        }

    @Test
    fun `nobody signed in has nothing stuck`() =
        rig {
            fx.reminder("m1")
            failFiveTimes()

            assertEquals(0, StuckChanges(fx.container.syncQueue, fx.container.reminders, fx.container.instances) { null }.count())
        }
}
