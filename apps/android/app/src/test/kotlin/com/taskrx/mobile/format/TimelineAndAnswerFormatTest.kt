package com.taskrx.mobile.format

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.timeline.TimelineRow
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelineAndAnswerFormatTest {
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US
    private val nine = 9 * 3_600_000L

    private fun row(
        kind: ReminderKind = ReminderKind.Medication,
        status: InstanceStatus = InstanceStatus.Pending,
        snoozedUntil: Long? = null,
        completedAt: Long? = null,
    ) = TimelineRow("r", null, "Metformin", null, kind, nine, status, snoozedUntil, false, completedAt)

    @Test
    fun `a Completed medication is shown as Taken and a Completed task as Done`() {
        assertEquals("Taken", statusLabel(row(ReminderKind.Medication, InstanceStatus.Completed), utc, us))
        assertEquals("Done", statusLabel(row(ReminderKind.Task, InstanceStatus.Completed), utc, us))
    }

    @Test
    fun `a Completed row says when, once that is known`() {
        val at = nine + 12 * 60_000
        assertEquals("Taken 9:12 AM", statusLabel(row(ReminderKind.Medication, InstanceStatus.Completed, completedAt = at), utc, us))
        assertEquals("Done 9:12 AM", statusLabel(row(ReminderKind.Task, InstanceStatus.Completed, completedAt = at), utc, us))
    }

    @Test
    fun `the other answers read the same for both Kinds`() {
        for (kind in ReminderKind.entries) {
            assertEquals("Skipped", statusLabel(row(kind, InstanceStatus.Skipped), utc, us))
            assertEquals("Missed", statusLabel(row(kind, InstanceStatus.Missed), utc, us))
            assertEquals("", statusLabel(row(kind, InstanceStatus.Pending), utc, us))
        }
    }

    @Test
    fun `a snooze names the moment it comes back, because that is its whole content`() {
        val text = statusLabel(row(status = InstanceStatus.Snoozed, snoozedUntil = nine + 15 * 60_000), utc, us)
        assertTrue(text.startsWith("Snoozed until "), text)
        assertTrue(text.contains("9:15"), text)
    }

    @Test
    fun `a snooze with no recorded moment still says it is snoozed`() {
        assertEquals("Snoozed", statusLabel(row(status = InstanceStatus.Snoozed), utc, us))
    }

    @Test
    fun `a Kind is named in words so colour is never the only signal`() {
        assertEquals("Medication", kindLabel(ReminderKind.Medication))
        assertEquals("Task", kindLabel(ReminderKind.Task))
    }

    @Test
    fun `a row is described as one phrase for a screen reader`() {
        val pending = rowDescription(row(), utc, us)
        assertTrue(pending.startsWith("Medication: Metformin at "), pending)
        assertFalse(pending.contains(","), pending)

        val taken = rowDescription(row(status = InstanceStatus.Completed), utc, us)
        assertTrue(taken.endsWith(", Taken"), taken)
        assertTrue(rowDescription(row(ReminderKind.Task, InstanceStatus.Missed), utc, us).startsWith("Task: Metformin at "))
    }

    @Test
    fun `the empty day says today in the present and other days in the past`() {
        assertEquals("No medications or tasks due today.", emptyDayBody(0, "Today"))
        assertEquals("You had nothing due yesterday.", emptyDayBody(-1, "Yesterday"))
        assertEquals("You had nothing due tue, aug 4.", emptyDayBody(-3, "Tue, Aug 4"))
    }

    @Test
    fun `a medication reads taken and a task reads done, and neither says dose`() {
        val med = wordsFor(ReminderKind.Medication)
        val task = wordsFor(ReminderKind.Task)

        assertEquals("Mark taken", med.complete)
        assertEquals("Recorded as taken", med.completed)
        assertEquals("Mark done", task.complete)
        assertEquals("Recorded as done", task.completed)
        assertEquals("MEDICATION", med.badge)
        assertEquals("TASK", task.badge)
        assertEquals("Time to take", med.due)
        assertEquals("Time to do it", task.due)
        for (words in listOf(med, task)) {
            assertEquals("Recorded as skipped", words.skipped)
            for (text in listOf(words.badge, words.due, words.complete, words.completed, words.skipped)) {
                assertFalse(text.contains("dose", ignoreCase = true), text)
            }
        }
    }

    @Test
    fun `urgency counts ahead, within a minute, and overdue`() {
        val words = wordsFor(ReminderKind.Medication)
        val minute = 60_000L
        val moment = 10 * 3_600_000L

        assertEquals("Due in 10 minutes", urgencyLabel(moment, moment - 10 * minute, words))
        assertEquals("Due in 2 minutes", urgencyLabel(moment, moment - 2 * minute, words))
        assertEquals("Time to take", urgencyLabel(moment, moment - minute, words))
        assertEquals("Time to take", urgencyLabel(moment, moment, words))
        assertEquals("Time to take", urgencyLabel(moment, moment + minute, words))
        assertEquals("Overdue by 2 minutes", urgencyLabel(moment, moment + 2 * minute, words))
        assertEquals("Overdue by 90 minutes", urgencyLabel(moment, moment + 90 * minute, words))
    }

    @Test
    fun `urgency rounds halves up like the Expo screen did`() {
        val words = wordsFor(ReminderKind.Task)
        // 1.5 minutes late rounds to 2, which is past the "now" band.
        assertEquals("Overdue by 2 minutes", urgencyLabel(0, 90_000, words))
        // 1.5 minutes early rounds to -1, which is inside it.
        assertEquals("Time to do it", urgencyLabel(0, -90_000, words))
        // 2.5 minutes early rounds to -2.
        assertEquals("Due in 2 minutes", urgencyLabel(0, -150_000, words))
    }

    @Test
    fun `a moment is overdue only after five minutes`() {
        val moment = 1_000_000L
        assertFalse(isOverdue(moment, moment + 5 * 60_000L))
        assertTrue(isOverdue(moment, moment + 5 * 60_000L + 1))
    }

    @Test
    fun `snooze is fifteen minutes`() {
        assertEquals(15, SNOOZE_MINUTES)
    }
}
