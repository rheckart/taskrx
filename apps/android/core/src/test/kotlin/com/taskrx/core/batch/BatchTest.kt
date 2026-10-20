package com.taskrx.core.batch

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.timeline.TimelineRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchTest {
    private fun row(
        name: String,
        at: Long = 100,
        status: InstanceStatus = InstanceStatus.Pending,
        kind: ReminderKind = ReminderKind.Medication,
    ) = TimelineRow("r-$name", null, name, null, kind, at, status, null, false, null)

    @Test
    fun `two doses at one moment are a batch`() {
        val slots = batchSlots(listOf(row("A"), row("B")))

        assertEquals(setOf(100L), slots.keys)
        assertEquals(listOf("A", "B"), slots.getValue(100).map { it.name })
    }

    @Test
    fun `a lone dose is not a batch`() {
        assertEquals(emptyMap(), batchSlots(listOf(row("A"), row("B", at = 200))))
    }

    @Test
    fun `different moments are different batches`() {
        val slots = batchSlots(listOf(row("A"), row("B"), row("C", at = 200), row("D", at = 200)))

        assertEquals(setOf(100L, 200L), slots.keys)
    }

    @Test
    fun `answered doses do not count, answerable ones do`() {
        val rows =
            listOf(
                row("done", status = InstanceStatus.Completed),
                row("skipped", status = InstanceStatus.Skipped),
                row("pending"),
                row("snoozed", status = InstanceStatus.Snoozed),
                row("missed", status = InstanceStatus.Missed),
            )

        assertEquals(listOf("pending", "snoozed", "missed"), batchSlots(rows).getValue(100).map { it.name })
    }

    @Test
    fun `one dose left after the others are answered is not a batch`() {
        assertEquals(emptyMap(), batchSlots(listOf(row("done", status = InstanceStatus.Completed), row("left"))))
    }

    @Test
    fun `tasks never join a batch`() {
        assertEquals(emptyMap(), batchSlots(listOf(row("dose"), row("chore", kind = ReminderKind.Task))))
        assertEquals(listOf("A", "B"), batchSlots(listOf(row("A"), row("B"), row("chore", kind = ReminderKind.Task))).getValue(100).map { it.name })
    }

    @Test
    fun `an action is read back from its word, and a stray word is nothing`() {
        for (action in BatchAction.entries) assertEquals(action, BatchAction.fromWire(action.wire))
        assertNull(BatchAction.fromWire("explode"))
        assertNull(BatchAction.fromWire(null))
    }

    @Test
    fun `the batch notification has an id of its own per moment`() {
        assertEquals("batch-100", batchNotificationId(100))
    }

    @Test
    fun `the words count the doses and name them in order`() {
        assertEquals(BatchText("3 doses due", "A, B, C"), batchText(listOf("C", "A", "B")))
    }
}
