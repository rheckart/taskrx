package com.taskrx.core.notifications

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun reminder(
    kind: ReminderKind,
    priority: Priority? = null,
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
): Reminder =
    if (priority == null) {
        createReminder(
            id = "r-${kind.wire}",
            userId = "user-1",
            kind = kind,
            name = "Reminder ${kind.wire}",
            schedule = schedule,
            createdAt = 0,
            updatedAt = 0,
        )
    } else {
        createReminder(
            id = "r-${kind.wire}",
            userId = "user-1",
            kind = kind,
            name = "Reminder ${kind.wire}",
            schedule = schedule,
            priority = priority,
            createdAt = 0,
            updatedAt = 0,
        )
    }

class TierForTest {
    @Test
    fun `puts a medication in the medication Tier`() {
        assertEquals(Tier.Medication, tierFor(ReminderKind.Medication))
    }

    @Test
    fun `puts a task in the task Tier`() {
        assertEquals(Tier.Task, tierFor(ReminderKind.Task))
    }

    @Test
    fun `lists both Tiers, by their stored spelling`() {
        assertEquals(listOf("medication", "task"), TIERS.map { it.wire })
    }
}

class EntersMedicationHorizonTest {
    @Test
    fun `admits a medication`() {
        assertTrue(entersMedicationHorizon(reminder(ReminderKind.Medication)))
    }

    @Test
    fun `refuses a task`() {
        assertFalse(entersMedicationHorizon(reminder(ReminderKind.Task)))
    }

    @Test
    fun `refuses a critical task, because Tier is Kind and not Priority`() {
        assertFalse(entersMedicationHorizon(reminder(ReminderKind.Task, Priority.Critical)))
    }

    @Test
    fun `admits a low-priority medication, for the same reason`() {
        assertTrue(entersMedicationHorizon(reminder(ReminderKind.Medication, Priority.Low)))
    }
}

class EntersTaskHorizonTest {
    @Test
    fun `admits a task`() {
        assertTrue(entersTaskHorizon(reminder(ReminderKind.Task)))
    }

    @Test
    fun `refuses a medication`() {
        assertFalse(entersTaskHorizon(reminder(ReminderKind.Medication)))
    }

    @Test
    fun `refuses a low-priority medication, because Tier is Kind and not Priority`() {
        assertFalse(entersTaskHorizon(reminder(ReminderKind.Medication, Priority.Low)))
    }

    @Test
    fun `admits a low-priority task, for the same reason`() {
        assertTrue(entersTaskHorizon(reminder(ReminderKind.Task, Priority.Low)))
    }
}

/**
 * The two budgets partition the Reminders rather than sharing them. This is
 * what ADR 0003 means by "by construction": there is no Kind that competes for
 * both, and none that falls through and is armed by neither.
 */
class TheTwoHorizonsTogetherTest {
    @Test
    fun `admits every Kind to exactly one of them`() {
        for (kind in ReminderKind.entries) {
            val subject = reminder(kind)
            assertTrue(entersMedicationHorizon(subject) || entersTaskHorizon(subject))
            assertNotEquals(entersTaskHorizon(subject), entersMedicationHorizon(subject))
        }
    }
}
