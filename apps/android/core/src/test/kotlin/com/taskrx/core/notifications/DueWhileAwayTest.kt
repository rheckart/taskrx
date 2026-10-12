package com.taskrx.core.notifications

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val UTC = ZoneId.of("UTC")
private fun at(h: Int, m: Int = 0): Long = ZonedDateTime.of(2026, 10, 12, h, m, 0, 0, UTC).toInstant().toEpochMilli()

private fun reminder(
    id: String,
    kind: ReminderKind = ReminderKind.Medication,
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
    active: Boolean = true,
): Reminder = createReminder(id = id, userId = "u", kind = kind, name = "R $id", schedule = schedule, createdAt = 0, updatedAt = 0).copy(active = active)

private fun away(reminders: List<Reminder>, now: Long, handled: Set<Pair<String, Long>> = emptySet()) =
    dueWhileAway(reminders, now, handled, UTC)

class DueWhileAwayTest {
    @Test
    fun `a dose that came due while the phone was off is posted, as the ordinary dose notification`() {
        val specs = away(listOf(reminder("m1")), now = at(9, 40))

        assertEquals(1, specs.size)
        assertEquals(medicationSpec(com.taskrx.core.schedule.nextOccurrences(reminder("m1"), at(8)).first()), specs.single())
        assertEquals(medicationNotificationId("m1", at(9)), specs.single().id)
    }

    @Test
    fun `a dose that is still ahead is not posted`() = assertTrue(away(listOf(reminder("m1")), now = at(8, 59)).isEmpty())

    @Test
    fun `a dose due this very moment is posted`() = assertEquals(1, away(listOf(reminder("m1")), now = at(9)).size)

    @Test
    fun `the Grace Period is inclusive at its edge, and an older dose is the sweep's`() {
        assertEquals(1, away(listOf(reminder("m1")), now = at(9) + GRACE_PERIOD_MS).size)
        assertTrue(away(listOf(reminder("m1")), now = at(9) + GRACE_PERIOD_MS + 1).isEmpty())
    }

    @Test
    fun `a dose already answered, skipped or snoozed is left alone`() {
        assertTrue(away(listOf(reminder("m1")), now = at(9, 40), handled = setOf("m1" to at(9))).isEmpty())
    }

    @Test
    fun `an Instance for a different moment or Reminder does not hide it`() {
        val handled = setOf("m1" to at(8), "other" to at(9))
        assertEquals(1, away(listOf(reminder("m1")), now = at(9, 40), handled = handled).size)
    }

    @Test
    fun `tasks, stopped Reminders and the Backlog are not posted`() {
        val reminders =
            listOf(
                reminder("task", ReminderKind.Task),
                reminder("stopped", active = false),
                reminder("backlog", schedule = noneTimes()),
            )
        assertTrue(away(reminders, now = at(9, 40)).isEmpty())
    }

    @Test
    fun `several doses come oldest first, ties by Reminder`() {
        val reminders =
            listOf(
                reminder("c", schedule = dailyTimes(listOf("09:00"))),
                reminder("a", schedule = dailyTimes(listOf("09:30"))),
                reminder("b", schedule = dailyTimes(listOf("09:00", "10:00"))),
            )

        val specs = away(reminders, now = at(10, 15))

        assertEquals(listOf("b" to at(9), "c" to at(9), "a" to at(9, 30), "b" to at(10)), specs.map { it.reminderId to it.scheduledTime })
    }

    @Test
    fun `an hourly interval Schedule yields every dose in the window`() {
        val specs = away(listOf(reminder("m1", schedule = intervalTimes(1, at(0)))), now = at(9))

        // Both ends of the two hours are inside it.
        assertEquals(listOf(at(7), at(8), at(9)), specs.map { it.scheduledTime })
    }

    @Test
    fun `a once Reminder due in the gap is posted, and one already past the window is not`() {
        assertEquals(1, away(listOf(reminder("o", schedule = onceTimes(at(9)))), now = at(9, 30)).size)
        assertTrue(away(listOf(reminder("o", schedule = onceTimes(at(6)))), now = at(9, 30)).isEmpty())
    }
}
