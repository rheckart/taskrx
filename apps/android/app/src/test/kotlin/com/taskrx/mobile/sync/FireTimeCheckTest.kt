package com.taskrx.mobile.sync

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.auth.FakeAuth
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val AT = 1_780_000_100_000L

private fun row(reminderId: String = "cr-med", status: String, time: Long = AT, localId: String = "i-$status-$reminderId") =
    """{"_id":"c$localId","_creationTime":1.0,"localId":"$localId","userId":"$USER","reminderId":"$reminderId",
    "reminderKind":"medication","scheduledTime":$time.0,"status":"$status","snoozeCount":0.0,
    "createdAt":1780000000000.0,"updatedAt":1780000000000.0}"""

@RunWith(RobolectricTestRunner::class)
class FireTimeCheckTest {
    private val gateway = FakeGateway()

    private suspend fun Fixture.check(
        rows: List<String>,
        auth: FakeAuth = FakeAuth(initial = AuthStatus.SignedIn(USER)),
        onServer: Boolean = true,
    ): FireCheck {
        if (container.reminders.get("med") == null) {
            container.reminders.create(CreateReminderInput(id = "med", userId = USER, kind = ReminderKind.Medication, name = "Metformin", schedule = onceTimes(AT)))
        }
        if (onServer) container.reminders.setConvexId("med", "cr-med")
        gateway.onQuery = { _, _ -> Json.parseToJsonElement("[${rows.joinToString(",")}]") }
        return FireTimeCheck(auth, MutableStateFlow(true), gateway, container.reminders).check("med", AT)
    }

    @Test
    fun `a dose taken on another device is answered, read from the one instant with times as doubles`() =
        withFixture(AT) {
            assertEquals(FireCheck.Answered, check(listOf(row(status = "completed"))))
            val call = gateway.calls.single()
            assertEquals(ConvexFunctions.INSTANCES_LIST, call.function)
            // A Long would go as an Int64, which `v.number()` refuses: the check would never answer.
            assertEquals(mapOf<String, Any?>("since" to AT.toDouble(), "until" to AT.toDouble()), call.args)
        }

    @Test
    fun `a skip elsewhere is an answer too`() = withFixture(AT) { assertEquals(FireCheck.Answered, check(listOf(row(status = "skipped")))) }

    @Test
    fun `no row, an open row, a snooze or a guessed miss leaves the moment open`() =
        withFixture(AT) {
            assertEquals(FireCheck.Open, check(emptyList()))
            assertEquals(FireCheck.Open, check(listOf(row(status = "completed"), row(status = "pending"))))
            assertEquals(FireCheck.Open, check(listOf(row(status = "snoozed"))))
            assertEquals(FireCheck.Open, check(listOf(row(status = "missed"))))
        }

    @Test
    fun `another reminder answered at the same instant is not this one`() =
        withFixture(AT) { assertEquals(FireCheck.Open, check(listOf(row(reminderId = "cr-other", status = "completed")))) }

    @Test
    fun `a status this build cannot read is not trusted as an answer`() =
        withFixture(AT) { assertEquals(FireCheck.Open, check(listOf(row(status = "completed"), row(status = "deferred")))) }

    @Test
    fun `signed out, nothing is asked`() =
        withFixture(AT) {
            assertEquals(FireCheck.SignedOut, check(listOf(row(status = "completed")), auth = FakeAuth(initial = AuthStatus.SignedOut)))
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun `a reminder that never reached the server cannot have been answered elsewhere`() =
        withFixture(AT) {
            assertEquals(FireCheck.NotOnServer, check(listOf(row(status = "completed")), onServer = false))
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun `two reminders due at one instant share one read and keep their own answers`() =
        withFixture(AT) {
            container.reminders.create(CreateReminderInput(id = "med", userId = USER, kind = ReminderKind.Medication, name = "Metformin", schedule = onceTimes(AT)))
            container.reminders.create(CreateReminderInput(id = "other", userId = USER, kind = ReminderKind.Medication, name = "Lisinopril", schedule = onceTimes(AT)))
            container.reminders.setConvexId("med", "cr-med")
            container.reminders.setConvexId("other", "cr-other")
            gateway.onQuery = { _, _ -> Json.parseToJsonElement("[${row(status = "completed")},${row(reminderId = "cr-other", status = "pending")}]") }
            val check = FireTimeCheck(FakeAuth(initial = AuthStatus.SignedIn(USER)), MutableStateFlow(true), gateway, container.reminders)
            val gate = FireGate()

            assertEquals(FireCheck.Answered, gate.decide(AT) { check.check("med", AT, it) })
            assertEquals(FireCheck.Open, gate.decide(AT) { check.check("other", AT, it) })
            assertEquals(1, gateway.calls.size)
        }
}
