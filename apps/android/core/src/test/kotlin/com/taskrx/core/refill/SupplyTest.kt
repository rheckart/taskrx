package com.taskrx.core.refill

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun spend(
    kind: ReminderKind = ReminderKind.Medication,
    from: InstanceStatus? = InstanceStatus.Pending,
    to: InstanceStatus = InstanceStatus.Completed,
    remaining: Double? = 30.0,
    perDose: Double? = null,
) = supplyAfterDose(kind, from, to, remaining, perDose)

class SupplyTest {
    @Test
    fun `a taken dose spends one pill when no dose size is set`() = assertEquals(29.0, spend())

    @Test
    fun `a taken dose spends the dose size`() = assertEquals(28.0, spend(perDose = 2.0))

    @Test
    fun `a dose size that is zero or negative falls back to one`() {
        assertEquals(29.0, spend(perDose = 0.0))
        assertEquals(29.0, spend(perDose = -3.0))
    }

    @Test
    fun `half a pill is half a pill`() = assertEquals(29.5, spend(perDose = 0.5))

    @Test
    fun `the count stops at zero`() {
        assertEquals(0.0, spend(remaining = 1.0, perDose = 2.0))
        assertEquals(0.0, spend(remaining = 0.0))
    }

    @Test
    fun `a dose that settles on the last pill reads zero`() = assertEquals(0.0, spend(remaining = 1.0))

    @Test
    fun `skipped, missed, snoozed and pending cost nothing`() {
        for (to in listOf(InstanceStatus.Skipped, InstanceStatus.Missed, InstanceStatus.Snoozed, InstanceStatus.Pending)) {
            assertNull(spend(to = to), "$to")
        }
    }

    @Test
    fun `a dose already completed does not spend again`() = assertNull(spend(from = InstanceStatus.Completed))

    @Test
    fun `a dose that starts settled spends once, from no previous status`() = assertEquals(29.0, spend(from = null))

    @Test
    fun `from a snooze, a skip or a miss into taken, it spends`() {
        for (from in listOf(InstanceStatus.Snoozed, InstanceStatus.Skipped, InstanceStatus.Missed)) {
            assertEquals(29.0, spend(from = from), "$from")
        }
    }

    @Test
    fun `a medication that tracks no supply spends nothing`() = assertNull(spend(remaining = null))

    @Test
    fun `a task spends nothing even if it carries a count`() = assertNull(spend(kind = ReminderKind.Task))
}
