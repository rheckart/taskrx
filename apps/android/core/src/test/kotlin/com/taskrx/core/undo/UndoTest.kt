package com.taskrx.core.undo

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val UTC = ZoneOffset.UTC

private fun at(hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 8, 6, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

private fun daily(): Reminder =
    createReminder("r1", "u", ReminderKind.Medication, "Metformin", 0, 0, schedule = dailyTimes(listOf("09:00")))

class UndoVerdictTest {
    @Test
    fun `a take, a skip and a snooze can be taken back`() {
        for (status in listOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Snoozed)) {
            assertEquals(UndoVerdict.Allowed, undoVerdict(status, daily(), at(9), otherOpenAtMoment = false, zone = UTC), "$status")
        }
    }

    @Test
    fun `a pending or missed row is not an answer, so there is nothing to take back`() {
        for (status in listOf(InstanceStatus.Pending, InstanceStatus.Missed)) {
            assertEquals(UndoVerdict.NotAnAnswer, undoVerdict(status, daily(), at(9), otherOpenAtMoment = false, zone = UTC), "$status")
        }
    }

    @Test
    fun `a moment the schedule does not predict is not offered, so no phantom miss is made`() {
        // An extra dose at 14:30 on a medication scheduled for 09:00.
        assertEquals(UndoVerdict.NotScheduled, undoVerdict(InstanceStatus.Completed, daily(), at(14, 30), otherOpenAtMoment = false, zone = UTC))
        // A Backlog reminder has no moments at all.
        val backlog = createReminder("b", "u", ReminderKind.Task, "Call", 0, 0, schedule = noneTimes())
        assertEquals(UndoVerdict.NotScheduled, undoVerdict(InstanceStatus.Completed, backlog, at(9), otherOpenAtMoment = false, zone = UTC))
    }

    @Test
    fun `a moment already open again is not undone into a second open row`() {
        assertEquals(UndoVerdict.MomentOpenAgain, undoVerdict(InstanceStatus.Skipped, daily(), at(9), otherOpenAtMoment = true, zone = UTC))
    }

    @Test
    fun `what is wrong is reported in a fixed order, the answer first`() {
        assertEquals(UndoVerdict.NotAnAnswer, undoVerdict(InstanceStatus.Pending, daily(), at(14), otherOpenAtMoment = true, zone = UTC))
        assertEquals(UndoVerdict.NotScheduled, undoVerdict(InstanceStatus.Skipped, daily(), at(14), otherOpenAtMoment = true, zone = UTC))
    }

    @Test
    fun `uses the device zone when none is given`() {
        // The suite pins UTC (see LocalDayBoundsTest).
        assertEquals(UndoVerdict.Allowed, undoVerdict(InstanceStatus.Completed, daily(), at(9), otherOpenAtMoment = false))
    }
}

class ScheduledOccurrenceTest {
    @Test
    fun `is true on an occurrence and false a millisecond either side`() {
        assertTrue(isScheduledOccurrence(daily(), at(9), UTC))
        assertFalse(isScheduledOccurrence(daily(), at(9) + 1, UTC))
        assertFalse(isScheduledOccurrence(daily(), at(9) - 1, UTC))
    }

    @Test
    fun `holds for a once reminder at its instant`() {
        val once = createReminder("o", "u", ReminderKind.Task, "Chore", 0, 0, schedule = onceTimes(at(15)))
        assertTrue(isScheduledOccurrence(once, at(15), UTC))
        assertFalse(isScheduledOccurrence(once, at(16), UTC))
    }

    @Test
    fun `uses the device zone when none is given`() {
        assertTrue(isScheduledOccurrence(daily(), at(9)))
    }
}

class UndoWillBeMissedTest {
    @Test
    fun `is false inside the grace period, and exactly on its edge`() {
        assertFalse(undoWillBeMissed(at(9), at(9) + 30 * 60_000L))
        assertFalse(undoWillBeMissed(at(9), at(9) + GRACE_PERIOD_MS))
    }

    @Test
    fun `is true once the grace period is over`() {
        assertTrue(undoWillBeMissed(at(9), at(9) + GRACE_PERIOD_MS + 1))
    }

    @Test
    fun `is false for a moment still ahead`() {
        assertFalse(undoWillBeMissed(at(21), at(9)))
    }

    @Test
    fun `follows the grace period it is given`() {
        assertTrue(undoWillBeMissed(at(9), at(9) + 31 * 60_000L, gracePeriodMs = 30 * 60_000L))
    }
}
