package com.taskrx.mobile.data

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.mobile.Session
import com.taskrx.mobile.USER
import com.taskrx.mobile.sync.FakeConnectivity
import com.taskrx.mobile.sync.FakeGateway
import com.taskrx.mobile.sync.PushWorker
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L
private const val LOCAL = Session.LOCAL_USER_ID

@RunWith(RobolectricTestRunner::class)
class LocalAdoptionTest {
    @Test
    fun `what was written signed out reaches the server once the account takes it`() =
        withFixture(T0) {
            val reminder =
                container.reminders.create(CreateReminderInput(id = "m1", userId = LOCAL, kind = ReminderKind.Medication, name = "Metformin", schedule = dailyTimes(listOf("09:00"))))
            container.instances.create(CreateReminderInstanceInput(userId = LOCAL, reminderId = reminder.id, reminderKind = ReminderKind.Medication, scheduledTime = T0, status = InstanceStatus.Completed, completedAt = T0))
            val gateway = FakeGateway()
            val worker = PushWorker(container.syncQueue, container.reminders, container.instances, gateway, FakeConnectivity(), { USER }, { })

            worker.drain()
            assertTrue(gateway.mutations.isEmpty(), "the server refuses another user's rows, so nothing goes up for them")

            assertEquals(1, LocalAdoption(database).adopt(LOCAL, USER))
            worker.drain()

            assertEquals(listOf("reminders:create", "reminderInstances:create"), gateway.mutations.map { it.function })
            assertTrue(container.syncQueue.pending().isEmpty())
            assertEquals(listOf("Metformin"), container.reminders.list(USER).map { it.name })
            assertEquals(listOf(InstanceStatus.Completed), container.instances.list(InstanceFilter(userId = USER)).map { it.status })
            assertTrue(container.reminders.list(LOCAL).isEmpty())
        }

    @Test
    fun `a queued entry keeps everything but its owner`() =
        withFixture(T0) {
            container.reminders.create(CreateReminderInput(id = "m1", userId = LOCAL, kind = ReminderKind.Medication, name = "Metformin", schedule = dailyTimes(listOf("09:00"))))
            val before = queuePayloads().single()

            LocalAdoption(database).adopt(LOCAL, USER)

            val after = queuePayloads().single()
            assertEquals(USER, after["userId"]!!.jsonPrimitive.content)
            assertEquals(before - "userId", after - "userId")
        }

    @Test
    fun `another account's rows are left where they are`() =
        withFixture(T0) {
            reminder("theirs")

            assertEquals(0, LocalAdoption(database).adopt(LOCAL, "user-2"))

            assertEquals(listOf("theirs"), container.reminders.list(USER).map { it.id })
            assertEquals(USER, queuePayloads().single()["userId"]!!.jsonPrimitive.content)
        }
}
