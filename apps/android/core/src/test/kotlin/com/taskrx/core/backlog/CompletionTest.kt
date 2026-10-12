package com.taskrx.core.backlog

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Date.UTC(2026, 7, 6, 15, 42, 17)
private val ACTED_AT = Instant.parse("2026-08-06T15:42:17Z").toEpochMilli()

private fun reminder(schedule: ScheduleTimes, kind: ReminderKind = ReminderKind.Task): Reminder =
    createReminder(
        id = "r1",
        userId = "user-1",
        kind = kind,
        name = "Call the pharmacy",
        schedule = schedule,
        createdAt = 0,
        updatedAt = 0,
    )

class IsBacklogTest {
    @Test
    fun `is a reminder with no schedule at all`() {
        assertTrue(isBacklog(reminder(noneTimes())))
    }

    @Test
    fun `is not a reminder whose daily schedule merely has no times`() {
        // The misconfiguration `none` replaces: it also produces no Occurrences,
        // and it is not a Backlog commitment.
        assertFalse(isBacklog(reminder(dailyTimes(emptyList()))))
    }
}

class PlanBacklogCompletionTest {
    @Test
    fun `mints the instance at the moment the person acted`() {
        assertEquals(
            BacklogCompletion(
                reminderId = "r1",
                reminderKind = ReminderKind.Task,
                scheduledTime = ACTED_AT,
                status = InstanceStatus.Completed,
                completedAt = ACTED_AT,
            ),
            planBacklogCompletion(reminder(noneTimes()), ACTED_AT),
        )
    }

    @Test
    fun `gives the instance the moment it occupied, which is when it was done`() {
        // ADR 0002: `scheduledTime` means "the instant this Instance occupied on the
        // timeline", and for the Backlog that is the completion time. Keeping it
        // non-null is what leaves all four time-ordered indexes valid.
        val plan = planBacklogCompletion(reminder(noneTimes()), ACTED_AT)

        assertEquals(plan.completedAt, plan.scheduledTime)
    }

    @Test
    fun `carries the kind, so a backlog medication still reaches adherence`() {
        assertEquals(
            ReminderKind.Medication,
            planBacklogCompletion(reminder(noneTimes(), ReminderKind.Medication), ACTED_AT).reminderKind,
        )
    }

    @Test
    fun `refuses a reminder that has a schedule`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                planBacklogCompletion(reminder(dailyTimes(listOf("09:00"))), ACTED_AT)
            }

        assertTrue(Regex("backlog", RegexOption.IGNORE_CASE).containsMatchIn(error.message.orEmpty()))
        // The message names the schedule type and the reminder, as the TS one does.
        assertTrue("r1 has a daily schedule" in error.message.orEmpty().replace("Reminder ", ""))
    }

    @Test
    fun `names the schedule type of every variant in the refusal`() {
        val cases =
            mapOf(
                dailyTimes(listOf("09:00")) to "daily",
                intervalTimes(8, 0L) to "interval",
                weeklyTimes(listOf(1), listOf("09:00")) to "weekly",
                onceTimes(ACTED_AT) to "once",
            )
        for ((schedule, type) in cases) {
            val error = assertFailsWith<IllegalArgumentException> { planBacklogCompletion(reminder(schedule), ACTED_AT) }
            assertTrue("has a $type schedule, so it is not in the Backlog" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `spells the none variant as the wire does, though a refusal never reaches it`() {
        assertEquals("none", scheduleTypeName(noneTimes()))
    }
}
