package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.timeline.TimelineRow
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/*
 * The Tasks tab's sections — the rule underneath Overdue / Due today / Upcoming.
 *
 * These state the rules the plan settled: what counts as Overdue, how far back
 * it reaches, why one row stands for a task's several misses, and what belongs
 * to today and to the week ahead. They do not assert a shipped shape (no case's
 * subject is "returns three lists"), so a rewrite of the grouping below can keep
 * them.
 *
 * The build pins the JVM to UTC (as vitest.config.ts pins TZ=UTC), so local
 * calendar arithmetic and UTC agree and the fixtures are plain UTC values.
 */

private fun utc(day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

private const val MS_PER_DAY = 86_400_000L

private val MIDDAY = utc(5, 12)

private fun row(
    reminderId: String = "task",
    scheduledTime: Long,
    status: InstanceStatus = InstanceStatus.Pending,
    kind: ReminderKind = ReminderKind.Task,
    isNext: Boolean = false,
): TimelineRow =
    TimelineRow(
        reminderId = reminderId,
        instanceId = null,
        name = reminderId,
        dosage = null,
        kind = kind,
        scheduledTime = scheduledTime,
        status = status,
        snoozedUntil = null,
        isNext = isNext,
    )

private fun sections(vararg rows: TimelineRow, now: Long = MIDDAY) = sectionTasks(rows.toList(), now)

class TaskSectionsTest {
    @Test
    fun `a past-due task is overdue whether the sweep recorded it or not`() {
        val result = sections(row("pending", utc(5, 9)), row("missed", utc(5, 10), InstanceStatus.Missed))

        assertEquals(listOf("pending", "missed"), result.overdue.map { it.reminderId })
        assertEquals(emptyList(), result.dueToday)
    }

    @Test
    fun `overdue reaches back the seven days Catch up reaches`() {
        val atFloor = row("edge", MIDDAY - SWEEP_LOOKBACK_MS)
        val justBefore = row("gone", MIDDAY - SWEEP_LOOKBACK_MS - 1)

        val result = sections(atFloor, justBefore)

        assertEquals(listOf("edge"), result.overdue.map { it.reminderId })
    }

    @Test
    fun `a moment exactly now is not overdue`() {
        val result = sections(row("now", MIDDAY))

        assertEquals(emptyList(), result.overdue)
        assertEquals(listOf("now"), result.dueToday.map { it.reminderId })
    }

    @Test
    fun `a task overdue once is one row, at its latest miss, most overdue first`() {
        val result =
            sections(
                row("weekly", utc(2, 9)),
                row("weekly", utc(4, 9)),
                row("weekly", utc(3, 9)),
                row("daily", utc(5, 9)),
            )

        assertEquals(listOf(utc(4, 9), utc(5, 9)), result.overdue.map { it.scheduledTime })
        assertEquals(listOf("weekly", "daily"), result.overdue.map { it.reminderId })
    }

    @Test
    fun `older misses of the same task are left out of Overdue, not deleted`() {
        val result = sections(row("weekly", utc(2, 9), InstanceStatus.Missed), row("weekly", utc(4, 9), InstanceStatus.Missed))

        assertEquals(listOf(utc(4, 9)), result.overdue.map { it.scheduledTime })
    }

    @ParameterizedTest
    @EnumSource(value = InstanceStatus::class, names = ["Completed", "Skipped", "Snoozed"])
    fun `a past task that was answered or deferred is not overdue`(status: InstanceStatus) {
        val result = sections(row("past", utc(3, 9), status))

        assertEquals(emptyList(), result.overdue)
        assertEquals(emptyList(), result.dueToday)
        assertEquals(emptyList(), result.upcoming)
    }

    @Test
    fun `due today is the whole local day, answered rows included`() {
        val result =
            sections(
                row("done", utc(5, 8), InstanceStatus.Completed),
                row("skipped", utc(5, 9), InstanceStatus.Skipped),
                row("open", utc(5, 10)),
                row("later", utc(5, 18)),
                row("midnight", utc(5, 0)),
                row("last", utc(5, 23, 59)),
            )

        // A past-due unanswered row is Overdue, not Due today; an answered one stays on the day.
        assertEquals(listOf("midnight", "open"), result.overdue.map { it.reminderId })
        assertEquals(listOf("done", "skipped", "later", "last"), result.dueToday.map { it.reminderId })
    }

    @Test
    fun `a past-due row chosen for Overdue is not also due today`() {
        val result = sections(row("late", utc(5, 9)), row("ahead", utc(5, 18)))

        assertEquals(listOf("late"), result.overdue.map { it.reminderId })
        assertEquals(listOf("ahead"), result.dueToday.map { it.reminderId })
    }

    @Test
    fun `upcoming is tomorrow through day seven, grouped by local day`() {
        val result =
            sections(
                row("tomorrow", utc(6, 9)),
                row("week", utc(12, 9)),
                row("tomorrow-late", utc(6, 18)),
                row("past-day", utc(4, 9), InstanceStatus.Completed),
                row("too-far", utc(13, 9)),
            )

        assertEquals(listOf(utc(6, 0), utc(12, 0)), result.upcoming.map { it.dayStart })
        assertEquals(listOf("tomorrow", "tomorrow-late"), result.upcoming.first().rows.map { it.reminderId })
        assertEquals(listOf("week"), result.upcoming.last().rows.map { it.reminderId })
    }

    @Test
    fun `the end of today belongs to today and the first millisecond of tomorrow to Upcoming`() {
        val todayEnd = utc(6, 0) - 1
        val result = sections(row("end", todayEnd), row("start", utc(6, 0)))

        assertEquals(listOf("end"), result.dueToday.map { it.reminderId })
        assertEquals(listOf("start"), result.upcoming.single().rows.map { it.reminderId })
    }

    @Test
    fun `day seven is the last day Upcoming shows`() {
        val lastDay = MIDDAY + 7 * MS_PER_DAY
        val dayAfter = MIDDAY + 8 * MS_PER_DAY

        val result = sections(row("last", lastDay), row("beyond", dayAfter))

        assertEquals(listOf("last"), result.upcoming.flatMap { day -> day.rows }.map { it.reminderId })
    }

    @Test
    fun `a task answered before its day arrives still appears in Upcoming`() {
        val result = sections(row("early", utc(6, 9), InstanceStatus.Completed))

        assertEquals(listOf("early"), result.upcoming.single().rows.map { it.reminderId })
    }

    @Test
    fun `medications are left to the day view`() {
        val result = sections(row("med", utc(5, 9), kind = ReminderKind.Medication), row("task", utc(5, 15)))

        assertEquals(emptyList(), result.overdue)
        assertEquals(listOf("task"), result.dueToday.map { it.reminderId })
    }

    @Test
    fun `no section marks a row as next`() {
        val result =
            sections(
                row("late", utc(5, 9), isNext = true),
                row("today", utc(5, 18), isNext = true),
                row("ahead", utc(6, 9), isNext = true),
            )

        val returned = result.overdue + result.dueToday + result.upcoming.flatMap { it.rows }
        assertEquals(3, returned.size)
        assertFalse(returned.any { it.isNext })
    }

    @Test
    fun `rows at one moment are ordered by task so a re-read never reshuffles them`() {
        val result = sections(row("b", utc(5, 19)), row("a", utc(5, 19)), row("c", utc(5, 18)), row("d", utc(5, 18)))

        assertEquals(listOf("c", "d", "a", "b"), result.dueToday.map { it.reminderId })
    }

    @Test
    fun `an empty set of rows is three empty sections`() {
        val result = sectionTasks(emptyList(), MIDDAY)

        assertEquals(TaskSections(emptyList(), emptyList(), emptyList()), result)
    }
}
