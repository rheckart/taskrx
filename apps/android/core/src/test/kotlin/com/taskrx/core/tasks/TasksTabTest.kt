package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.undo.undoableInstanceIds
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The Tasks tab's rows (`assembleTasksTab`): what it reaches past its fortnight, and which rows
 * offer Undo. The golden `today` fixtures pin the full output; these state the rules.
 * The build pins the JVM to UTC.
 */

private val UTC = ZoneOffset.UTC

private fun utc(month: Int, day: Int, hour: Int = 0): Long = ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, UTC).toInstant().toEpochMilli()

private val NOW = utc(8, 20, 12)
private val CREATED = utc(6, 1)

private fun task(id: String, schedule: ScheduleTimes, active: Boolean = true, createdAt: Long = CREATED) =
    Reminder(id, "u", ReminderKind.Task, id, null, null, schedule, Priority.Normal, emptyList(), null, null, null, emptyList(), active, createdAt, createdAt)

private fun stored(reminderId: String, at: Long, status: InstanceStatus, snoozedUntil: Long? = null, id: String = "$reminderId-$at") =
    ReminderInstance(id, reminderId, "u", ReminderKind.Task, at, status, at.takeIf { status == InstanceStatus.Completed }, snoozedUntil, 0, null, at, at)

private fun tab(reminders: List<Reminder>, older: List<ReminderInstance> = emptyList(), inside: List<ReminderInstance> = emptyList()) =
    assembleTasksTab(TasksTabInput(reminders, inside, older, NOW, UTC))

class TasksTabTest {
    @Test
    fun `the window runs from seven local days back to the end of day seven ahead`() {
        val window = taskWindow(NOW, UTC)
        assertEquals(utc(8, 13), window.start)
        assertEquals(utc(8, 28) - 1, window.end)
    }

    @Test
    fun `a task with no row at its last moment, long before the window, still shows as overdue`() {
        val overdue = tab(listOf(task("passport", onceTimes(utc(7, 1, 9))))).overdue.single()
        assertEquals(utc(7, 1, 9), overdue.scheduledTime)
        assertEquals(InstanceStatus.Pending, overdue.status)
        assertNull(overdue.instanceId, "invented, not stored")
    }

    @Test
    fun `a stored row at that moment says what became of it, so none is invented`() {
        val taxes = task("taxes", onceTimes(utc(7, 1, 9)))
        assertEquals(emptyList(), tab(listOf(taxes), older = listOf(stored("taxes", utc(7, 1, 9), InstanceStatus.Completed))).overdue)
        val open = tab(listOf(taxes), older = listOf(stored("taxes", utc(7, 1, 9), InstanceStatus.Pending))).overdue.single()
        assertEquals("taxes-${utc(7, 1, 9)}", open.instanceId)
    }

    @Test
    fun `a stopped task, or one created after its moment, gets no invented row`() {
        assertEquals(emptyList(), tab(listOf(task("books", onceTimes(utc(7, 1, 9)), active = false))).overdue)
        assertEquals(emptyList(), tab(listOf(task("dentist", onceTimes(utc(7, 1, 9)), createdAt = utc(7, 2)))).overdue)
    }

    @Test
    fun `an old snooze nobody came back to reads as the miss it was`() {
        val fence = task("fence", onceTimes(utc(7, 1, 9)))
        val row = tab(listOf(fence), older = listOf(stored("fence", utc(7, 1, 9), InstanceStatus.Snoozed, snoozedUntil = utc(7, 1, 10)))).overdue.single()
        assertEquals(InstanceStatus.Missed, row.status)
    }

    @Test
    fun `a medication's row is not read as an older task row`() {
        val call = task("call", onceTimes(utc(7, 1, 9)))
        val medication = stored("call", utc(7, 1, 9), InstanceStatus.Completed).copy(reminderKind = ReminderKind.Medication)
        val overdue = tab(listOf(call), older = listOf(medication)).overdue.single()
        assertNull(overdue.instanceId, "the moment still has no task row, so one is invented")
    }

    @Test
    fun `an old snooze still inside its Grace Period stays snoozed`() {
        val call = task("call", onceTimes(utc(7, 1, 9)))
        val snoozed = stored("call", utc(7, 1, 9), InstanceStatus.Snoozed, snoozedUntil = NOW - 60_000)
        // Read as missed it would be Overdue; a live snooze is not, and no row is invented over it.
        assertEquals(emptyList(), tab(listOf(call), older = listOf(snoozed)).overdue)
    }

    @Test
    fun `a row inside the window is the projection's, not read again as older`() {
        val water = task("water", dailyTimes(listOf("18:00")))
        val ahead = stored("water", utc(8, 25, 18), InstanceStatus.Completed)
        val day = tab(listOf(water), older = listOf(ahead)).upcoming.single { it.dayStart == utc(8, 25) }
        assertEquals(1, day.rows.size)
    }

    @Test
    fun `an older row whose Reminder is gone is dropped`() {
        val call = task("call", onceTimes(utc(7, 1, 9)))
        val orphan = stored("deleted", utc(7, 1, 9), InstanceStatus.Pending)
        assertNull(tab(listOf(call), older = listOf(orphan)).overdue.single().instanceId, "only the invented row for the task that exists")
    }

    @Test
    fun `undo is offered for an answer on a scheduled moment, and nothing else`() {
        val water = task("water", dailyTimes(listOf("18:00")))
        fun row(id: String?, reminderId: String, at: Long, status: InstanceStatus) =
            TimelineRow(reminderId, id, reminderId, null, ReminderKind.Task, at, status, null, false)
        val rows =
            listOf(
                row("done", "water", utc(8, 21, 18), InstanceStatus.Completed),
                row("skipped", "water", utc(8, 19, 18), InstanceStatus.Skipped),
                row("missed", "water", utc(8, 18, 18), InstanceStatus.Missed),
                row("off-schedule", "water", utc(8, 19, 9), InstanceStatus.Completed),
                row(null, "water", utc(8, 17, 18), InstanceStatus.Completed),
                row("gone", "deleted", utc(8, 19, 18), InstanceStatus.Completed),
            )
        val undoable = undoableInstanceIds(rows, listOf(water), UTC)
        assertEquals(setOf("done", "skipped"), undoable)
        assertTrue("off-schedule" !in undoable, "an answer at a moment the Schedule never made")
    }
}
