package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val UTC = ZoneOffset.UTC

private fun utc(day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

private val NOW = utc(6, 12, 0)

private fun task(
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
    pauses: List<PauseWindow> = emptyList(),
    overrides: List<OccurrenceOverride> = emptyList(),
    kind: ReminderKind = ReminderKind.Task,
): Reminder =
    createReminder(
        id = "task-1",
        userId = "u",
        kind = kind,
        name = "Call pharmacy",
        createdAt = utc(1, 0),
        updatedAt = utc(1, 0),
        schedule = schedule,
        priority = Priority.Normal,
        pauses = pauses,
        overrides = overrides,
    )

private fun instance(
    scheduledTime: Long,
    status: InstanceStatus,
    snoozedUntil: Long? = null,
): ReminderInstance =
    createReminderInstance(
        id = "inst-1",
        reminderId = "task-1",
        userId = "u",
        reminderKind = ReminderKind.Task,
        scheduledTime = scheduledTime,
        createdAt = scheduledTime,
        updatedAt = scheduledTime,
        status = status,
        snoozedUntil = snoozedUntil,
    )

class TaskRescheduleTest {
    @Test
    fun `a medication is refused as NotATask`() {
        val med = task(kind = ReminderKind.Medication)

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.NotATask),
            planTaskReschedule(med, utc(6, 15), utc(6, 16), NOW),
        )
    }

    @Test
    fun `a completed or skipped occurrence is refused as AlreadyAnswered`() {
        val r = task()
        val at = utc(6, 15)

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.AlreadyAnswered),
            planTaskReschedule(r, at, utc(6, 16), NOW, listOf(instance(at, InstanceStatus.Completed))),
        )
        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.AlreadyAnswered),
            planTaskReschedule(r, at, utc(6, 16), NOW, listOf(instance(at, InstanceStatus.Skipped))),
        )
    }

    @Test
    fun `targeting the current moment is refused as Unchanged`() {
        val r = task()
        val at = utc(6, 15)

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.Unchanged),
            planTaskReschedule(r, at, at, NOW),
        )

        val snoozed = instance(utc(6, 9), InstanceStatus.Snoozed, snoozedUntil = utc(6, 16))
        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.Unchanged),
            planTaskReschedule(r, utc(6, 9), utc(6, 16), NOW, listOf(snoozed)),
        )

        val snoozedNoUntil = instance(utc(6, 9), InstanceStatus.Snoozed, snoozedUntil = null)
        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.Unchanged),
            planTaskReschedule(r, utc(6, 9), utc(6, 9), NOW, listOf(snoozedNoUntil)),
        )
    }

    @Test
    fun `a target in the past or at now is refused as InThePast`() {
        val r = task()
        val past = utc(5, 9)

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.InThePast),
            planTaskReschedule(r, past, NOW, NOW),
        )
        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.InThePast),
            planTaskReschedule(r, past, NOW - 1_000, NOW),
        )
    }

    @Test
    fun `a target inside a pause is refused as InAPause`() {
        val paused = task(pauses = listOf(PauseWindow(utc(6, 14), utc(6, 18))))

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.InAPause),
            planTaskReschedule(paused, utc(6, 15), utc(6, 16), NOW),
        )
    }

    @Test
    fun `an open future or within-grace occurrence uses MoveOverride`() {
        val r = task()
        val target = utc(7, 9)

        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.MoveOverride(utc(6, 15), target)),
            planTaskReschedule(r, utc(6, 15), target, NOW),
        )
        // Inside 2h grace period (10:30 at 12:00) also moves via override.
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.MoveOverride(utc(6, 10, 30), target)),
            planTaskReschedule(r, utc(6, 10, 30), target, NOW, emptyList(), 2 * 3_600_000L),
        )
    }

    @Test
    fun `an already-overridden occurrence updates its original base override`() {
        val orig = utc(6, 13)
        val firstMove = utc(6, 15)
        val secondMove = utc(7, 10)
        val r = task(overrides = listOf(OccurrenceOverride(utc(4, 9), utc(4, 15)), OccurrenceOverride(orig, firstMove)))

        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.MoveOverride(orig, secondMove)),
            planTaskReschedule(r, firstMove, secondMove, NOW),
        )

        // When the original base occurrence is already past its grace period, moving again defers via DeferInstance.
        val pastOrig = utc(5, 9)
        val pastMoved = task(overrides = listOf(OccurrenceOverride(pastOrig, firstMove)))
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(firstMove, secondMove)),
            planTaskReschedule(pastMoved, firstMove, secondMove, NOW),
        )
    }

    @Test
    fun `an untouched OnceTimes task moved earlier in the future uses RescheduleOnce`() {
        val scheduled = utc(7, 12)
        val r = task(schedule = onceTimes(scheduled))
        val earlier = utc(6, 18)

        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.RescheduleOnce(earlier)),
            planTaskReschedule(r, scheduled, earlier, NOW),
        )
    }

    @Test
    fun `a recurring task or opened OnceTimes cannot move earlier than its scheduled moment`() {
        val scheduled = utc(7, 12)
        val earlier = utc(6, 18)

        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.NotAfter),
            planTaskReschedule(task(), scheduled, earlier, NOW),
        )
        assertEquals(
            TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.NotAfter),
            planTaskReschedule(task(onceTimes(scheduled)), scheduled, earlier, NOW, listOf(instance(scheduled, InstanceStatus.Pending))),
        )
    }

    @Test
    fun `an overdue, missed, pending, or snoozed occurrence defers via DeferInstance`() {
        val target = utc(6, 18)

        // Overdue past-grace OnceTimes with no instance yet.
        val overdueOnce = task(onceTimes(utc(5, 9)))
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(5, 9), target)),
            planTaskReschedule(overdueOnce, utc(5, 9), target, NOW),
        )

        // Overdue recurring past grace with no instance.
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(6, 9), target)),
            planTaskReschedule(task(), utc(6, 9), target, NOW),
        )

        // Overdue Missed instance.
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(6, 9), target)),
            planTaskReschedule(task(), utc(6, 9), target, NOW, listOf(instance(utc(6, 9), InstanceStatus.Missed))),
        )

        // Already-opened Pending instance.
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(6, 15), target)),
            planTaskReschedule(task(), utc(6, 15), target, NOW, listOf(instance(utc(6, 15), InstanceStatus.Pending))),
        )

        // Already-Snoozed instance moved earlier or later (still after scheduledTime and now).
        val snoozed = instance(utc(6, 9), InstanceStatus.Snoozed, snoozedUntil = utc(6, 16))
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(6, 9), utc(6, 14))),
            planTaskReschedule(task(), utc(6, 9), utc(6, 14), NOW, listOf(snoozed)),
        )
        assertEquals(
            TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(utc(6, 9), utc(7, 9))),
            planTaskReschedule(task(), utc(6, 9), utc(7, 9), NOW, listOf(snoozed)),
        )
    }

    @Test
    fun `tomorrowAtSameTime and plusDaysAtSameTime preserve wall-clock time across DST`() {
        val ny = ZoneId.of("America/New_York")
        // Saturday Nov 5, 2026 09:00 EDT; Sunday Nov 6, 2026 falls back 1h -> 09:00 EST.
        val sat = ZonedDateTime.of(2026, 11, 7, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 11, 7, 14, 0, 0, 0, ny).toInstant().toEpochMilli()
        val sun = ZonedDateTime.of(2026, 11, 8, 9, 0, 0, 0, ny).toInstant().toEpochMilli()

        assertEquals(sun, tomorrowAtSameTime(sat, now, ny))
        assertEquals(sun, plusDaysAtSameTime(sat, 1, ny))
        // Default zone overloads (JVM pinned to UTC).
        assertEquals(utc(7, 9), tomorrowAtSameTime(utc(5, 9), NOW))
        assertEquals(utc(7, 9), plusDaysAtSameTime(utc(6, 9), 1))
    }
}
