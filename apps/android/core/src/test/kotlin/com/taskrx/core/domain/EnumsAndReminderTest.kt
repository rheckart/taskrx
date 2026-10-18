package com.taskrx.core.domain

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CREATED_AT: Long =
    ZonedDateTime.of(2026, 7, 27, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

class EnumsTest {
    @Test
    fun `carries every member of the C# enums`() {
        assertEquals(listOf("medication", "task"), ReminderKind.entries.map { it.wire })
        assertEquals(listOf("critical", "high", "normal", "low"), Priority.entries.map { it.wire })
        assertEquals(
            listOf("pending", "completed", "snoozed", "skipped", "missed"),
            InstanceStatus.entries.map { it.wire },
        )
        // `taken`, not `completed`. ADR 0001 renamed the member on
        // `InstanceStatus`, which both Kinds share; `NotificationType` is a
        // separate enum recording what happened to a notification, and the rename
        // deliberately did not reach it.
        assertEquals(
            listOf("fired", "taken", "snoozed", "skipped", "missed", "caregiver_pinged"),
            NotificationType.entries.map { it.wire },
        )
    }

    @Test
    fun `keeps snoozed distinct from skipped, which adherence depends on`() {
        assertTrue(InstanceStatus.Snoozed in InstanceStatus.entries)
        assertTrue(InstanceStatus.Skipped in InstanceStatus.entries)
    }

    @Test
    fun `reads each enum back from its stored spelling`() {
        ReminderKind.entries.forEach { assertEquals(it, ReminderKind.fromWire(it.wire)) }
        Priority.entries.forEach { assertEquals(it, Priority.fromWire(it.wire)) }
        InstanceStatus.entries.forEach { assertEquals(it, InstanceStatus.fromWire(it.wire)) }
        NotificationType.entries.forEach { assertEquals(it, NotificationType.fromWire(it.wire)) }
    }

    @Test
    fun `reads an unknown spelling as null rather than guessing`() {
        assertNull(ReminderKind.fromWire("Medication"))
        assertNull(Priority.fromWire("urgent"))
        assertNull(InstanceStatus.fromWire("taken"))
        assertNull(NotificationType.fromWire("completed"))
    }
}

class IsAnswerableTest {
    @Test
    fun `leaves an unanswered moment open`() {
        assertTrue(isAnswerable(InstanceStatus.Pending))
    }

    /**
     * The rule that earns this function. A snooze says "not now", so the moment
     * is still outstanding — treating it as answered would make snoozing the one
     * way to put something permanently out of reach.
     */
    @Test
    fun `leaves a snoozed moment open, because a deferral is not an answer`() {
        assertTrue(isAnswerable(InstanceStatus.Snoozed))
    }

    @Test
    fun `closes a moment the person has answered`() {
        assertFalse(isAnswerable(InstanceStatus.Completed))
        assertFalse(isAnswerable(InstanceStatus.Skipped))
    }

    @Test
    fun `reopens a moment that ran out of time, because missed is only the sweep's guess`() {
        assertTrue(isAnswerable(InstanceStatus.Missed))
    }

    @Test
    fun `has an opinion about every status there is`() {
        // Adding a status without deciding whether it is answerable would leave the
        // on-device lookup and the timeline's tappability to guess separately.
        for (status in InstanceStatus.entries) {
            assertEquals(status in ANSWERABLE_STATUSES, isAnswerable(status))
        }
        assertTrue(ANSWERABLE_STATUSES.all { it in InstanceStatus.entries })
    }
}

class CreateReminderTest {
    @Test
    fun `applies the defaults BuildReminder uses`() {
        assertEquals(
            Reminder(
                id = "rem-1",
                userId = "user-1",
                kind = ReminderKind.Medication,
                name = "Metformin",
                dosage = null,
                instructions = null,
                schedule = dailyTimes(emptyList()),
                priority = Priority.Normal,
                tags = emptyList(),
                pillsRemaining = null,
                pillsPerDose = null,
                refillReminderDays = null,
                caregiverIds = emptyList(),
                active = true,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
            createReminder(
                id = "rem-1",
                userId = "user-1",
                kind = ReminderKind.Medication,
                name = "Metformin",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
    }

    @Test
    fun `carries a task kind, which the POC's medication-only types cannot`() {
        val reminder =
            createReminder(
                id = "rem-2",
                userId = "user-1",
                kind = ReminderKind.Task,
                name = "Check blood pressure",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )

        assertEquals(ReminderKind.Task, reminder.kind)
    }

    @Test
    fun `carries a weekly schedule, which the POC's types cannot express`() {
        val reminder =
            createReminder(
                id = "rem-3",
                userId = "user-1",
                kind = ReminderKind.Medication,
                name = "Methotrexate",
                schedule = weeklyTimes(listOf(1), listOf("09:00")),
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )

        assertEquals(WeeklyTimes(daysOfWeek = listOf(1), times = listOf("09:00")), reminder.schedule)
    }

    @Test
    fun `keeps every supplied field`() {
        val reminder =
            createReminder(
                id = "rem-4",
                userId = "user-1",
                kind = ReminderKind.Medication,
                name = "Warfarin",
                dosage = "5 mg",
                instructions = "with food",
                schedule = intervalTimes(8, CREATED_AT),
                priority = Priority.Critical,
                tags = listOf("blood-thinner"),
                pillsRemaining = 30.0,
                pillsPerDose = 1.5,
                refillReminderDays = 7,
                caregiverIds = listOf("care-1"),
                active = false,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT + 1000,
            )

        assertEquals(
            Reminder(
                id = "rem-4",
                userId = "user-1",
                kind = ReminderKind.Medication,
                name = "Warfarin",
                dosage = "5 mg",
                instructions = "with food",
                schedule = IntervalTimes(hours = 8, startAt = CREATED_AT),
                priority = Priority.Critical,
                tags = listOf("blood-thinner"),
                pillsRemaining = 30.0,
                pillsPerDose = 1.5,
                refillReminderDays = 7,
                caregiverIds = listOf("care-1"),
                active = false,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT + 1000,
            ),
            reminder,
        )
    }

    @Test
    fun `does not alias the caller's arrays`() {
        val tags = mutableListOf("a")
        val caregiverIds = mutableListOf("care-1")
        val reminder =
            createReminder(
                id = "rem-5",
                userId = "user-1",
                kind = ReminderKind.Task,
                name = "Walk",
                tags = tags,
                caregiverIds = caregiverIds,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )

        tags.add("b")
        caregiverIds.add("care-2")

        assertEquals(listOf("a"), reminder.tags)
        assertEquals(listOf("care-1"), reminder.caregiverIds)
    }
}

class CreateReminderInstanceTest {
    @Test
    fun `defaults to a pending, never-snoozed instance`() {
        assertEquals(
            ReminderInstance(
                id = "inst-1",
                reminderId = "rem-1",
                userId = "user-1",
                reminderKind = ReminderKind.Medication,
                scheduledTime = CREATED_AT,
                status = InstanceStatus.Pending,
                completedAt = null,
                snoozedUntil = null,
                snoozeCount = 0,
                notes = null,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
            createReminderInstance(
                id = "inst-1",
                reminderId = "rem-1",
                userId = "user-1",
                reminderKind = ReminderKind.Medication,
                scheduledTime = CREATED_AT,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
    }

    @Test
    fun `keeps every supplied field`() {
        val instance =
            createReminderInstance(
                id = "inst-2",
                reminderId = "rem-1",
                userId = "user-1",
                reminderKind = ReminderKind.Task,
                scheduledTime = CREATED_AT,
                status = InstanceStatus.Snoozed,
                completedAt = CREATED_AT + 60_000,
                snoozedUntil = CREATED_AT + 600_000,
                snoozeCount = 2,
                notes = "after lunch",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT + 1000,
            )

        assertEquals(InstanceStatus.Snoozed, instance.status)
        assertEquals(CREATED_AT + 60_000, instance.completedAt)
        assertEquals(CREATED_AT + 600_000, instance.snoozedUntil)
        assertEquals(2, instance.snoozeCount)
        assertEquals("after lunch", instance.notes)
    }
}

class CreateCaregiverTest {
    @Test
    fun `defaults notification opt-ins to off and collections to empty`() {
        assertEquals(
            Caregiver(
                id = "care-1",
                userId = "user-1",
                caregiverUserId = "user-2",
                relationship = null,
                notifyForMissedMedication = false,
                notifyForMissedTask = false,
                channels = emptyList(),
                deviceTokens = emptyList(),
                createdAt = CREATED_AT,
            ),
            createCaregiver(
                id = "care-1",
                userId = "user-1",
                caregiverUserId = "user-2",
                createdAt = CREATED_AT,
            ),
        )
    }

    @Test
    fun `keeps every supplied field`() {
        val caregiver =
            createCaregiver(
                id = "care-2",
                userId = "user-1",
                caregiverUserId = "user-3",
                relationship = "daughter",
                notifyForMissedMedication = true,
                notifyForMissedTask = true,
                channels = listOf("push"),
                deviceTokens = listOf("token-1"),
                createdAt = CREATED_AT,
            )

        assertEquals("daughter", caregiver.relationship)
        assertTrue(caregiver.notifyForMissedMedication)
        assertTrue(caregiver.notifyForMissedTask)
        assertEquals(listOf("push"), caregiver.channels)
        assertEquals(listOf("token-1"), caregiver.deviceTokens)
    }
}
