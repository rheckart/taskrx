package com.taskrx.mobile.sync

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/**
 * A `reminders.list` document in the JSON shape the Convex client library hands over: system
 * fields `_id` / `_creationTime`, whole numbers spelled as float64 (`1780000000000.0`), and
 * optional fields simply absent (Convex omits an undefined field; it never sends null).
 */
private fun reminderDoc(
    id: String = "cr1",
    localId: String = "r1",
    updatedAt: String = "1780000000000.0",
    extra: String = "",
    schedule: String = """{"type":"daily","times":["09:00"]}""",
    kind: String = "medication",
) =
    """{"_id":"$id","_creationTime":1779999999999.123,"localId":"$localId","userId":"$USER","kind":"$kind",
    "name":"Metformin","schedule":$schedule,"priority":"normal","tags":[],"caregiverIds":[],"active":true,
    "createdAt":1780000000000.0,"updatedAt":$updatedAt $extra}"""

private fun instanceDoc(
    id: String = "ci1",
    localId: String = "i1",
    reminderId: String = "cr1",
    status: String = "pending",
    updatedAt: String = "1780000000000.0",
    extra: String = "",
    kind: String = "medication",
) =
    """{"_id":"$id","_creationTime":1779999999999.5,"localId":"$localId","userId":"$USER","reminderId":"$reminderId",
    "reminderKind":"$kind","scheduledTime":1780000100000.0,"status":"$status","snoozeCount":0.0,
    "createdAt":1780000000000.0,"updatedAt":$updatedAt $extra}"""

@RunWith(RobolectricTestRunner::class)
class PullTest {
    private fun pull(fx: Fixture) = PullApplier(fx.container.reminders, fx.container.instances)

    /** What a sync does to this device's rows: the queue is drained, so no change is waiting to be pushed. */
    private suspend fun Fixture.pushed() = (container.syncQueue.pending() + container.syncQueue.deadLettered()).forEach { container.syncQueue.remove(it.id) }

    private suspend fun PullApplier.run(reminders: List<String>, instances: List<String> = emptyList()) =
        applyJson(json(reminders.joinToString(prefix = "[", postfix = "]")), json(instances.joinToString(prefix = "[", postfix = "]")))

    // ---- typing ----------------------------------------------------------------------------

    @Test
    fun `writes a pulled reminder under its local id and keeps the server id as its handle`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc(extra = ""","rxcui":"860975","dosage":"500 mg","instructions":"with food","pillsRemaining":30.0,"pillsPerDose":1.5,"refillReminderDays":7.0""")))

            val local = assertNotNull(container.reminders.get("r1"))
            assertEquals("Metformin", local.name)
            assertEquals(ReminderKind.Medication, local.kind)
            assertEquals(DailyTimes(listOf("09:00")), local.schedule)
            assertEquals(Priority.Normal, local.priority)
            assertEquals(T0, local.createdAt)
            assertEquals(T0, local.updatedAt)
            assertEquals(30.0, local.pillsRemaining)
            assertEquals(1.5, local.pillsPerDose)
            assertEquals(7, local.refillReminderDays)
            assertEquals("500 mg", local.dosage)
            assertEquals("cr1", container.reminders.getConvexId("r1"))
            assertEquals("860975", container.reminders.getRxcui("r1"))
            assertEquals("synced", database.reminderDao().get("r1")!!.syncStatus)
            assertTrue(container.syncQueue.pending().isEmpty(), "a pull must not re-enqueue what it wrote")
        }

    @Test
    fun `re-arms notifications after reminders are pulled, and survives that failing`() =
        withFixture(T0) {
            var calls = 0
            val applier = PullApplier(container.reminders, container.instances) { calls++; error("native failure") }
            applier.run(listOf(reminderDoc()))
            assertNotNull(container.reminders.get("r1"))
            assertEquals(1, calls)

            applier.run(emptyList()) // nothing written: nothing to re-arm
            assertEquals(1, calls)
        }

    @Test
    fun `reads whole numbers however the library spells them`() =
        withFixture(T0) {
            // 1.78E12, 1780000000000 and 1780000000000.0 are one number to the server.
            pull(this).run(
                listOf(
                    reminderDoc(localId = "a", id = "ca", updatedAt = "1.78E12"),
                    reminderDoc(localId = "b", id = "cb", updatedAt = "1780000000000"),
                    reminderDoc(localId = "c", id = "cc", updatedAt = "1780000000000.0"),
                ),
            )
            listOf("a", "b", "c").forEach { assertEquals(T0, container.reminders.get(it)!!.updatedAt, it) }
        }

    @Test
    fun `an absent optional field reads as null and unknown fields are ignored`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc(extra = ""","somethingNew":{"a":1}""")))
            val local = container.reminders.get("r1")!!
            assertNull(local.dosage)
            assertNull(local.pillsRemaining)
            assertNull(local.refillReminderDays)
            assertNull(container.reminders.getRxcui("r1"))
        }

    @Test
    fun `reads each schedule type`() =
        withFixture(T0) {
            pull(this).run(
                listOf(
                    reminderDoc(localId = "daily", id = "c1", schedule = """{"type":"daily","times":["07:30","21:00"]}"""),
                    reminderDoc(localId = "interval", id = "c2", schedule = """{"type":"interval","hours":6.0,"startAt":1780000000000.0}"""),
                    reminderDoc(localId = "weekly", id = "c3", schedule = """{"type":"weekly","daysOfWeek":[1.0,3.0],"times":["08:00"]}"""),
                    reminderDoc(localId = "once", id = "c4", schedule = """{"type":"once","at":1780000500000.0}"""),
                    reminderDoc(localId = "none", id = "c5", schedule = """{"type":"none"}"""),
                ),
            )
            suspend fun schedule(id: String) = container.reminders.get(id)!!.schedule
            assertEquals(DailyTimes(listOf("07:30", "21:00")), schedule("daily"))
            assertEquals(IntervalTimes(6, T0), schedule("interval"))
            assertEquals(WeeklyTimes(listOf(1, 3), listOf("08:00")), schedule("weekly"))
            assertEquals(OnceTimes(T0 + 500_000), schedule("once"))
            assertEquals(NoneTimes, schedule("none"))
        }

    @Test
    fun `a schedule it cannot read lands in the Backlog instead of reaching the occurrence engine`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc(schedule = """{"type":"daily","times":["25:99"]}""")))
            assertEquals(NoneTimes, container.reminders.get("r1")!!.schedule)
        }

    @Test
    fun `skips a document of a kind this build does not know`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc(kind = "appointment"), reminderDoc(id = "cr2", localId = "r2")))
            assertNull(container.reminders.get("r1"))
            assertNotNull(container.reminders.get("r2"))
        }

    @Test
    fun `an unknown priority reads as normal`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc().replace(""""priority":"normal"""", """"priority":"urgent"""")))
            assertEquals(Priority.Normal, container.reminders.get("r1")!!.priority)
        }

    // ---- instances ---------------------------------------------------------------------------

    @Test
    fun `resolves an instance's parent to the LOCAL reminder id`() =
        withFixture(T0) {
            pull(this).run(
                listOf(reminderDoc()),
                listOf(instanceDoc(extra = ""","completedAt":1780000200000.0,"snoozedUntil":1780000300000.0,"notes":"ok"""")),
            )
            val local = assertNotNull(container.instances.get("i1"))
            assertEquals("r1", local.reminderId, "the local UUID, never the server id")
            assertEquals(T0 + 100_000, local.scheduledTime)
            assertEquals(T0 + 200_000, local.completedAt)
            assertEquals(T0 + 300_000, local.snoozedUntil)
            assertEquals("ok", local.notes)
            assertEquals(0, local.snoozeCount)
            assertEquals(ReminderKind.Medication, local.reminderKind)
            assertEquals("ci1", container.instances.getConvexId("i1"))
            assertEquals("synced", database.reminderInstanceDao().get("i1")!!.syncStatus)
        }

    @Test
    fun `resolves parents that only exist because this pass wrote them`() =
        withFixture(T0) {
            // A fresh install: nothing local yet, so the instance's parent is found only if the
            // reminder write was finished before the lookup.
            pull(this).run(listOf(reminderDoc()), listOf(instanceDoc()))
            assertNotNull(container.instances.get("i1"))
        }

    @Test
    fun `skips an instance whose parent did not come down and keeps the ones that did`() =
        withFixture(T0) {
            pull(this).run(listOf(reminderDoc()), listOf(instanceDoc(localId = "orphan", id = "co", reminderId = "gone"), instanceDoc()))
            assertNull(container.instances.get("orphan"))
            assertNotNull(container.instances.get("i1"))
        }

    @Test
    fun `skips an instance with a status or kind this build does not know`() =
        withFixture(T0) {
            pull(this).run(
                listOf(reminderDoc()),
                listOf(instanceDoc(localId = "s", id = "cs", status = "abandoned"), instanceDoc(localId = "k", id = "ck", kind = "appointment"), instanceDoc()),
            )
            assertNull(container.instances.get("s"))
            assertNull(container.instances.get("k"))
            assertNotNull(container.instances.get("i1"))
        }

    @Test
    fun `still works when nothing came down`() =
        withFixture(T0) {
            pull(this).run(emptyList(), emptyList())
            assertTrue(container.reminders.list(USER).isEmpty())
        }

    // ---- conflict rules, through :core ---------------------------------------------------------

    @Test
    fun `never lets a remote row overwrite a locally completed instance`() =
        withFixture(T0) {
            reminder("r1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 10
            container.instances.markCompleted(instance.id, T0 + 10)
            pushed()
            val local = container.instances.get(instance.id)!!

            // A NEWER remote copy that says skipped: still refused.
            pull(this).run(
                listOf(reminderDoc(id = "cr1", localId = "r1", updatedAt = "1780000000000.0")),
                listOf(instanceDoc(id = "ci1", localId = instance.id, status = "skipped", updatedAt = "${T0 + 999_999}.0")),
            )

            assertEquals(local, container.instances.get(instance.id))
            assertEquals(InstanceStatus.Completed, container.instances.get(instance.id)!!.status)
        }

    @Test
    fun `applies a remote answer to an instance still pending here`() =
        withFixture(T0) {
            reminder("r1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            container.reminders.setConvexId("r1", "cr1")
            pushed()

            pull(this).run(
                emptyList(),
                listOf(instanceDoc(localId = instance.id, status = "completed", updatedAt = "${T0 + 5000}.0", extra = ""","completedAt":${T0 + 4000}.0""")),
            )

            val after = container.instances.get(instance.id)!!
            assertEquals(InstanceStatus.Completed, after.status)
            assertEquals(T0 + 4000, after.completedAt)
        }

    @Test
    fun `lets a newer remote answer replace a local snooze but not an older one`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 100
            container.instances.markSnoozed(instance.id, T0 + 600_000)
            pushed()

            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "skipped", updatedAt = "${T0 + 50}.0")))
            assertEquals(InstanceStatus.Snoozed, container.instances.get(instance.id)!!.status)

            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "skipped", updatedAt = "${T0 + 500}.0")))
            assertEquals(InstanceStatus.Skipped, container.instances.get(instance.id)!!.status)
        }

    @Test
    fun `local wins a tie on an instance`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 100
            container.instances.markSkipped(instance.id)
            pushed()

            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "missed", updatedAt = "${T0 + 100}.0")))

            assertEquals(InstanceStatus.Skipped, container.instances.get(instance.id)!!.status)
        }

    @Test
    fun `keeps a local reminder edit newer than the server's, and a tie, and takes a newer remote one`() =
        withFixture(T0) {
            now = T0 + 100
            reminder("r1", createdAt = T0 + 100, name = "Local")

            pull(this).run(listOf(reminderDoc(localId = "r1", updatedAt = "${T0 + 50}.0").replace("Metformin", "Older")))
            assertEquals("Local", container.reminders.get("r1")!!.name)

            pull(this).run(listOf(reminderDoc(localId = "r1", updatedAt = "${T0 + 100}.0").replace("Metformin", "Tie")))
            assertEquals("Local", container.reminders.get("r1")!!.name)

            pull(this).run(listOf(reminderDoc(localId = "r1", updatedAt = "${T0 + 200}.0").replace("Metformin", "Newer")))
            assertEquals("Newer", container.reminders.get("r1")!!.name)
        }

    @Test
    fun `refuses only the conflicting instance, not the batch`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val done = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 1))
            val open = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 2))
            now = T0 + 10
            container.instances.markCompleted(done.id, T0 + 10)
            pushed()

            pull(this).run(
                emptyList(),
                listOf(
                    instanceDoc(id = "c-done", localId = done.id, status = "missed", updatedAt = "${T0 + 900}.0"),
                    instanceDoc(id = "c-open", localId = open.id, status = "completed", updatedAt = "${T0 + 900}.0"),
                ),
            )

            assertEquals(InstanceStatus.Completed, container.instances.get(done.id)!!.status)
            assertEquals(InstanceStatus.Completed, container.instances.get(open.id)!!.status)
            assertEquals(T0 + 10, container.instances.get(done.id)!!.completedAt)
        }

    // ---- an undo, through the pull -----------------------------------------------------------------

    @Test
    fun `an undo made before it was pushed survives a pull that still carries the take`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 10
            container.instances.markCompleted(instance.id, T0 + 10)
            pushed() // the take reached the server
            now = T0 + 20
            container.instances.reopen(instance.id) // the undo is made offline: queued, not pushed

            // A pull lands before the undo is pushed. The server still says completed.
            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "completed", updatedAt = "${T0 + 15}.0", extra = ""","completedAt":${T0 + 10}.0""")))

            assertEquals(InstanceStatus.Pending, container.instances.get(instance.id)!!.status)
        }

    @Test
    fun `once the undo is pushed the pull converges on pending, and a later stale take cannot undo it`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 10
            container.instances.markCompleted(instance.id, T0 + 10)
            now = T0 + 20
            container.instances.reopen(instance.id)
            pushed() // both reached the server, in order

            // The server now says pending, stamped by the server's clock after the undo.
            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "pending", updatedAt = "${T0 + 25}.0")))

            assertEquals(InstanceStatus.Pending, container.instances.get(instance.id)!!.status)
        }

    @Test
    fun `an undo made on another device replaces a taking here, and only a newer one`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 100
            container.instances.markCompleted(instance.id, T0 + 100)
            pushed()

            // Pending, but older than the taking: a stale copy of the row from before it was answered.
            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "pending", updatedAt = "${T0 + 50}.0")))
            assertEquals(InstanceStatus.Completed, container.instances.get(instance.id)!!.status)

            // Pending and newer: the other device's undo.
            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "pending", updatedAt = "${T0 + 500}.0")))
            val after = container.instances.get(instance.id)!!
            assertEquals(InstanceStatus.Pending, after.status)
            assertNull(after.completedAt)
        }

    @Test
    fun `a change the server refused and gave up on is still not replaced by a pull`() =
        withFixture(T0) {
            reminder("r1")
            container.reminders.setConvexId("r1", "cr1")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, T0 + 100_000))
            now = T0 + 10
            container.instances.markSkipped(instance.id)
            container.syncQueue.pending().forEach { container.syncQueue.markDeadLettered(it.id) }

            pull(this).run(emptyList(), listOf(instanceDoc(localId = instance.id, status = "completed", updatedAt = "${T0 + 900}.0")))

            assertEquals(InstanceStatus.Skipped, container.instances.get(instance.id)!!.status)
        }

    // ---- the subscription ------------------------------------------------------------------------

    private suspend fun <T> withScope(block: suspend (CoroutineScope) -> T): T = coroutineScope { block(this) }

    private suspend fun awaitUntil(check: suspend () -> Boolean) {
        repeat(400) {
            if (check()) return
            delay(10)
        }
        throw AssertionError("condition not reached")
    }

    @Test
    fun `applies what both subscriptions deliver`() =
        withFixture(T0) {
            val gateway = FakeGateway()
            gateway.feeds[ConvexFunctions.REMINDERS_LIST] = flowOf(json("[${reminderDoc()}]"))
            gateway.feeds[ConvexFunctions.INSTANCES_LIST] = flowOf(json("[${instanceDoc()}]"))

            PullSync(gateway, pull(this)).run()

            assertNotNull(container.instances.get("i1"))
        }

    @Test
    fun `a pass that fails is logged and the next delivery heals it`() =
        withFixture(T0) {
            val gateway = FakeGateway()
            gateway.feeds[ConvexFunctions.REMINDERS_LIST] = flow {
                emit(json("""[{"_id":"x"}]""")) // not a reminder document
                emit(json("[${reminderDoc()}]"))
            }
            gateway.feeds[ConvexFunctions.INSTANCES_LIST] = flow {
                emit(json("[]"))
            }

            PullSync(gateway, pull(this)).run()

            assertNotNull(container.reminders.get("r1"))
        }

    @Test
    fun `resubscribes after the subscription fails, pausing for the backoff`() =
        withFixture(T0) {
            val gateway = FakeGateway()
            var subscriptions = 0
            gateway.feeds[ConvexFunctions.REMINDERS_LIST] = flow {
                if (++subscriptions < 3) throw IllegalStateException("socket closed")
                emit(json("[${reminderDoc()}]"))
            }
            gateway.feeds[ConvexFunctions.INSTANCES_LIST] = flowOf(json("[]"))
            val pauses = mutableListOf<Long>()

            PullSync(gateway, pull(this)) { pauses += it }.run()

            assertEquals(listOf(1_000L, 2_000L), pauses)
            assertNotNull(container.reminders.get("r1"))
        }

    @Test
    fun `cancelling the pull is not retried`() =
        withFixture(T0) {
            val gateway = FakeGateway()
            val never = CompletableDeferred<Unit>()
            gateway.feeds[ConvexFunctions.REMINDERS_LIST] = flow { never.await() }
            gateway.feeds[ConvexFunctions.INSTANCES_LIST] = flowOf(json("[]"))
            var paused = false

            withScope { scope ->
                val job = scope.launch(Dispatchers.Default) { PullSync(gateway, pull(this@withFixture)) { paused = true }.run() }
                delay(100)
                job.cancel()
                job.join()
            }

            assertTrue(!paused)
        }
}
