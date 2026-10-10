package com.taskrx.core.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.StatusTracked
import com.taskrx.core.domain.Timestamped
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun stamped(updatedAt: Long): Timestamped =
    object : Timestamped {
        override val updatedAt = updatedAt
    }

private fun row(updatedAt: Long, status: InstanceStatus): StatusTracked =
    object : StatusTracked {
        override val updatedAt = updatedAt
        override val status = status
    }

class ShouldApplyRemoteReminderTest {
    @Test
    fun `applies a remote reminder this device has never seen`() {
        assertTrue(shouldApplyRemoteReminder(null, stamped(100)))
    }

    @Test
    fun `applies a remote edit made after the local one`() {
        assertTrue(shouldApplyRemoteReminder(stamped(100), stamped(200)))
    }

    @Test
    fun `keeps a local edit made after the remote one`() {
        assertFalse(shouldApplyRemoteReminder(stamped(200), stamped(100)))
    }

    @Test
    fun `keeps the local row when both were edited at the same instant`() {
        assertFalse(shouldApplyRemoteReminder(stamped(100), stamped(100)))
    }
}

class ShouldApplyRemoteReminderInstanceTest {
    @Test
    fun `applies a remote instance this device has never seen`() {
        assertTrue(shouldApplyRemoteReminderInstance(null, row(100, InstanceStatus.Pending)))
    }

    @Test
    fun `applies a newer remote answer over an unanswered local one`() {
        assertTrue(
            shouldApplyRemoteReminderInstance(
                row(100, InstanceStatus.Pending),
                row(200, InstanceStatus.Skipped),
            ),
        )
    }

    @Test
    fun `keeps a local answer made after the remote one`() {
        assertFalse(
            shouldApplyRemoteReminderInstance(
                row(200, InstanceStatus.Pending),
                row(100, InstanceStatus.Skipped),
            ),
        )
    }

    @Test
    fun `keeps the local row when both were answered at the same instant`() {
        assertFalse(
            shouldApplyRemoteReminderInstance(
                row(100, InstanceStatus.Pending),
                row(100, InstanceStatus.Skipped),
            ),
        )
    }

    /**
     * The safety-critical rule, and the reason this function is not just
     * `shouldApplyRemoteReminder` with an extra field. Story 44: "As someone
     * whose dose was recorded on one device, I want that record never
     * overwritten by stale data from another, so that a taken dose stays taken."
     * The status is spelled `completed` since ADR 0001; the story's wording is
     * quoted as written.
     *
     * Both directions are pinned because only the second one is load-bearing: a
     * rule that merely compared timestamps would pass the first case by accident
     * and fail the second.
     */
    @Test
    fun `never lets a remote pull erase an Instance this device recorded as completed`() {
        assertFalse(
            shouldApplyRemoteReminderInstance(
                row(100, InstanceStatus.Completed),
                row(200, InstanceStatus.Pending),
            ),
        )
    }

    @Test
    fun `keeps the local completed record even when the remote is also completed and newer`() {
        assertFalse(
            shouldApplyRemoteReminderInstance(
                row(100, InstanceStatus.Completed),
                row(200, InstanceStatus.Completed),
            ),
        )
    }

    /**
     * A snooze is not an answer, so it does not get the completed row's
     * protection — it is an ordinary last-write-wins field. Pinned because
     * `snoozed` is the one status the unified vocabulary has and the legacy
     * `dose_logs` did not, so it is the one place this table could silently
     * diverge from the prior art it was copied from.
     */
    @Test
    fun `lets a newer remote answer replace a local snooze`() {
        assertTrue(
            shouldApplyRemoteReminderInstance(
                row(100, InstanceStatus.Snoozed),
                row(200, InstanceStatus.Completed),
            ),
        )
    }

    @Test
    fun `a remote completion replaces a local row stamped later, as the server will not take it back`() {
        for (status in listOf(InstanceStatus.Missed, InstanceStatus.Snoozed, InstanceStatus.Skipped, InstanceStatus.Pending)) {
            assertTrue(
                shouldApplyRemoteReminderInstance(row(200, status), row(100, InstanceStatus.Completed)),
                "$status",
            )
        }
    }
}
