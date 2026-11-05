package com.taskrx.core.notifications

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.asNeededTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.schedule.ScheduledOccurrence
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rem(id: String, kind: ReminderKind, dosage: String? = null, times: List<String> = listOf("09:00")): Reminder =
    createReminder(
        id = id,
        userId = "u",
        kind = kind,
        name = "Name $id",
        schedule = dailyTimes(times),
        dosage = dosage,
        createdAt = 0,
        updatedAt = 0,
    )

class ChannelAndIdTest {
    @Test
    fun `channel ids are frozen`() {
        assertEquals("medication_reminders", MEDICATION_CHANNEL_ID)
        assertEquals("task_reminders", TASK_CHANNEL_ID)
    }

    @Test
    fun `budgets are 64 and 32`() {
        assertEquals(64, MAX_MEDICATION_TRIGGERS)
        assertEquals(32, MAX_TASK_TRIGGERS)
    }

    @Test
    fun `builds the three deterministic id shapes`() {
        assertEquals("r1-1000", medicationNotificationId("r1", 1000))
        assertEquals("task-r1-1000", taskNotificationId("r1", 1000))
        assertEquals("snooze-r1-1000-2000", snoozeNotificationId("r1", 1000, 2000))
        assertEquals("ready-r1-2000", readyNotificationId("r1", 2000))
    }

    @Test
    fun `a dose id can never be a task id or a snooze id`() {
        val dose = medicationNotificationId("r1", 5)
        assertFalse(dose.startsWith(TASK_ID_PREFIX))
        assertFalse(dose.startsWith(SNOOZE_ID_PREFIX))
    }

    @Test
    fun `matches a reminder in all three namespaces and no other`() {
        assertTrue(belongsToReminder("r1-5", "r1"))
        assertTrue(belongsToReminder("task-r1-5", "r1"))
        assertTrue(belongsToReminder("snooze-r1-5-9", "r1"))
        assertTrue(belongsToReminder("ready-r1-5", "r1"))
        assertFalse(belongsToReminder("ready-r2-5", "r1"))
        assertFalse(belongsToReminder("r2-5", "r1"))
        assertFalse(belongsToReminder("task-r2-5", "r1"))
        assertFalse(belongsToReminder("snooze-r2-5-9", "r1"))
    }
}

class DeliveryTableTest {
    @Test
    fun `a dose is high importance on the alarm clock`() {
        val d = deliveryFor(Tier.Medication)
        assertEquals(MEDICATION_CHANNEL_ID, d.channelId)
        assertEquals(ChannelImportance.High, d.importance)
        assertEquals(Mechanism.AlarmClock, d.mechanism)
    }

    @Test
    fun `a task is default importance on a work request`() {
        val d = deliveryFor(Tier.Task)
        assertEquals(TASK_CHANNEL_ID, d.channelId)
        assertEquals(ChannelImportance.Default, d.importance)
        assertEquals(Mechanism.WorkRequest, d.mechanism)
    }
}

class SpecTest {
    private val occurrence = ScheduledOccurrence("o", "r1", "Metformin", "500mg", 1000)

    @Test
    fun `medication spec carries the dosage`() {
        val s = medicationSpec(occurrence)
        assertEquals("r1-1000", s.id)
        assertEquals(Tier.Medication, s.tier)
        assertEquals("Metformin", s.title)
        assertEquals("Time to take (500mg)", s.body)
        assertEquals(1000, s.scheduledTime)
        assertFalse(s.snoozed)
    }

    @Test
    fun `medication spec without a dosage has no parentheses`() {
        assertEquals("Time to take", medicationSpec(occurrence.copy(dosage = null)).body)
    }

    @Test
    fun `task spec is task-prefixed`() {
        val s = taskSpec(occurrence.copy(dosage = null))
        assertEquals("task-r1-1000", s.id)
        assertEquals(Tier.Task, s.tier)
        assertEquals("Time to do it", s.body)
    }

    @Test
    fun `snooze of a medication stays on the medication Tier`() {
        val s = snoozeSpec(rem("r1", ReminderKind.Medication, "5mg"), scheduledTime = 1000, snoozeUntil = 1000 + 600_000, now = 1000)
        assertEquals("snooze-r1-1000-601000", s.id)
        assertEquals(Tier.Medication, s.tier)
        assertEquals(601_000, s.fireAt)
        assertEquals(1000, s.scheduledTime)
        assertEquals("Name r1 (5mg) (snoozed)", s.title)
        assertEquals("Reminder snoozed for 10 minutes", s.body)
        assertTrue(s.snoozed)
    }

    @Test
    fun `snooze of a task stays on the task Tier`() {
        val s = snoozeSpec(rem("t1", ReminderKind.Task), 1000, 1000 + 300_000, 1000)
        assertEquals(Tier.Task, s.tier)
        assertEquals("Name t1 (snoozed)", s.title)
    }

    @Test
    fun `snooze minutes never read less than one`() {
        assertEquals("Reminder snoozed for 1 minutes", snoozeSpec(rem("r1", ReminderKind.Task), 1, 5_000, 1_000).body)
    }
}

class DesiredNotificationsTest {
    private val day = 86_400_000L

    @Test
    fun `puts every dose before every task`() {
        val reminders = listOf(rem("t", ReminderKind.Task, times = listOf("00:30")), rem("m", ReminderKind.Medication, times = listOf("23:00")))
        val desired = desiredNotifications(reminders, now = 0, zone = ZoneOffset.UTC)
        val tiers = desired.map { it.tier }
        assertEquals(tiers.sortedBy { it.ordinal }, tiers)
        assertTrue(desired.first().tier == Tier.Medication)
        assertTrue(desired.any { it.tier == Tier.Task })
    }

    @Test
    fun `a flood of tasks cannot shorten the dose set`() {
        val doses = rem("m", ReminderKind.Medication, times = listOf("08:00", "20:00"))
        val tasks = (1..50).map { rem("t$it", ReminderKind.Task, times = listOf("01:00", "02:00", "03:00")) }
        val alone = desiredNotifications(listOf(doses), 0, ZoneOffset.UTC).filter { it.tier == Tier.Medication }
        val flooded = desiredNotifications(listOf(doses) + tasks, 0, ZoneOffset.UTC)
        assertEquals(alone, flooded.filter { it.tier == Tier.Medication })
        assertEquals(MAX_TASK_TRIGGERS, flooded.count { it.tier == Tier.Task })
        assertTrue(day > 0)
    }

    @Test
    fun `leaves out a moment already answered`() {
        val med = rem("m", ReminderKind.Medication, times = listOf("08:00", "20:00"))
        val task = rem("t", ReminderKind.Task, times = listOf("08:00", "20:00"))
        val eight = 8 * 3_600_000L
        val desired = desiredNotifications(listOf(med, task), 0, ZoneOffset.UTC, setOf("m" to eight, "t" to eight))
        assertTrue(desired.none { it.reminderId == "m" && it.scheduledTime == eight })
        assertTrue(desired.none { it.reminderId == "t" && it.scheduledTime == eight })
        assertTrue(desired.any { it.reminderId == "m" && it.scheduledTime == 20 * 3_600_000L })
    }

    @Test
    fun `answering moments does not shorten a full budget`() {
        val med = rem("m", ReminderKind.Medication, times = listOf("08:00", "20:00"))
        val task = rem("t", ReminderKind.Task, times = listOf("08:00", "20:00"))
        val answered = setOf("m" to 8 * 3_600_000L, "t" to 8 * 3_600_000L)
        val desired = desiredNotifications(listOf(med, task), 0, ZoneOffset.UTC, answered)
        assertEquals(MAX_MEDICATION_TRIGGERS, desired.count { it.tier == Tier.Medication })
        assertEquals(MAX_TASK_TRIGGERS, desired.count { it.tier == Tier.Task })
    }

    @Test
    fun `defaults to the system zone`() {
        assertTrue(desiredNotifications(listOf(rem("m", ReminderKind.Medication)), 0).isNotEmpty())
    }
}

class AsNeededReadyTest {
    private val hour = 3_600_000L
    private val now = 1_000 * hour

    private fun tylenol(active: Boolean = true, max: Int? = null) =
        createReminder(
            id = "t",
            userId = "u",
            kind = ReminderKind.Medication,
            name = "Tylenol",
            dosage = "500mg",
            schedule = asNeededTimes(240, max),
            createdAt = 0,
            updatedAt = 0,
            active = active,
        )

    private fun desired(reminder: Reminder, takings: List<Long>) =
        desiredNotifications(listOf(reminder), now, ZoneOffset.UTC, takings = mapOf("t" to takings))

    @Test
    fun `a dose just taken is prompted for again when the gap is over`() {
        val specs = desired(tylenol(), listOf(now - hour))

        val spec = specs.single()
        assertEquals("ready-t-${now + 3 * hour}", spec.id)
        assertEquals(now + 3 * hour, spec.fireAt)
        assertEquals(Tier.Medication, spec.tier)
        assertEquals("Tylenol", spec.title)
        assertEquals("You can take another dose (500mg)", spec.body)
        assertTrue(spec.asNeeded)
    }

    @Test
    fun `nothing is armed once the moment has passed, so an ignored prompt is not re-armed`() {
        assertEquals(emptyList(), desired(tylenol(), listOf(now - 5 * hour)))
    }

    @Test
    fun `nothing is armed before a first dose`() {
        assertEquals(emptyList(), desired(tylenol(), emptyList()))
        assertEquals(emptyList(), desiredNotifications(listOf(tylenol()), now, ZoneOffset.UTC))
    }

    @Test
    fun `at the daily limit the prompt waits for the limit, not the gap`() {
        val takings = listOf(now - 23 * hour, now - 22 * hour, now - 21 * hour, now - 20 * hour, now - 19 * hour)

        assertEquals(now + hour, desired(tylenol(max = 5), takings).single().fireAt)
    }

    @Test
    fun `a stopped medication prompts for nothing`() {
        assertEquals(emptyList(), desired(tylenol(active = false), listOf(now - hour)))
    }

    @Test
    fun `taking another dose moves the prompt rather than adding a second`() {
        val first = desired(tylenol(), listOf(now - hour)).single().id
        val second = desired(tylenol(), listOf(now - hour, now)).single().id

        assertFalse(first == second)
    }

    @Test
    fun `a prompt does not use up a scheduled dose's place in the budget`() {
        val doses = rem("m", ReminderKind.Medication)
        val withPrompt = desiredNotifications(listOf(doses, tylenol()), 0, ZoneOffset.UTC, takings = mapOf("t" to listOf(0L)))

        assertEquals(desiredNotifications(listOf(doses), 0, ZoneOffset.UTC).size + 1, withPrompt.size)
    }
}
