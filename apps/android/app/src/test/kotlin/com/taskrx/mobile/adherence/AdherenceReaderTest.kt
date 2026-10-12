package com.taskrx.mobile.adherence

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.withFixture
import java.time.ZoneOffset
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Executable coverage for the Adherence read (archive/expo-rn:apps/mobile/src/adherence/read.test.ts).
 *
 * :core runs every rule about the figure and the window. What it cannot reach is what the
 * ticket is about: whether the doses nobody answered have been recorded by the time the
 * figure is computed - the difference between 1-of-30 reading as 3% and as 100%. The
 * assertions read the FIGURE that came back, not the order functions were called in.
 * Test JVMs run at UTC, matching :core's suite.
 */
private fun utc(year: Int, month: Int, day: Int, hour: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

/** Midday, so a whole-day window has room either side of it. */
private val NOW = utc(2026, 8, 13, 12)

@RunWith(RobolectricTestRunner::class)
class AdherenceReaderTest {
    /** A medication and a task, both unscheduled, to hang recorded Instances on. */
    private suspend fun Fixture.parents() {
        reminder("r1")
        reminder("t1", ReminderKind.Task)
    }

    private suspend fun Fixture.record(
        scheduledTime: Long,
        status: InstanceStatus,
        kind: ReminderKind = ReminderKind.Medication,
    ) {
        container.instances.create(
            CreateReminderInstanceInput(
                USER,
                if (kind == ReminderKind.Task) "t1" else "r1",
                kind,
                scheduledTime,
                status,
                completedAt = if (status == InstanceStatus.Completed) scheduledTime else null,
            ),
        )
    }

    private suspend fun Fixture.read() = container.adherence.read(USER, NOW)

    @Test
    fun `reports the proportion of doses met over the period`() =
        withFixture(NOW) {
            parents()
            record(NOW - DAY, InstanceStatus.Completed)
            record(NOW - DAY - HOUR, InstanceStatus.Completed)
            record(NOW - 2 * DAY, InstanceStatus.Missed)
            record(NOW - 2 * DAY - HOUR, InstanceStatus.Skipped)

            val stats = read().stats

            assertEquals(2, stats.completed)
            assertEquals(4, stats.total)
            assertEquals(50.0, stats.percent)
        }

    /**
     * The reason the ticket was blocked on the sweep, as the number a person sees: one
     * dose taken out of thirty scheduled must not read as success, and before the sweep
     * there are no rows for the twenty-nine.
     */
    @Test
    fun `counts the doses nobody ever answered, so 1-of-30 does not read as success`() =
        withFixture(NOW) {
            reminder("r1", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 30 * DAY)
            record(utc(2026, 8, 13, 9), InstanceStatus.Completed)

            val stats = read().stats

            assertTrue(stats.percent < 50.0)
            assertTrue(stats.missed > 0)
            assertEquals(1, stats.completed)
        }

    @Test
    fun `excludes tasks, so a productive week does not move the figure`() =
        withFixture(NOW) {
            parents()
            record(NOW - DAY, InstanceStatus.Completed)
            record(NOW - 2 * DAY, InstanceStatus.Missed)
            val withoutTasks = read().stats

            record(NOW - DAY - HOUR, InstanceStatus.Completed, ReminderKind.Task)
            record(NOW - 2 * DAY - HOUR, InstanceStatus.Completed, ReminderKind.Task)
            record(NOW - 3 * DAY, InstanceStatus.Completed, ReminderKind.Task)
            val withTasks = read().stats

            assertEquals(50.0, withoutTasks.percent)
            assertEquals(withoutTasks, withTasks)
        }

    @Test
    fun `breaks the period down by day, every day and in order`() =
        withFixture(NOW) {
            parents()
            record(NOW - DAY, InstanceStatus.Completed)
            record(NOW - 3 * DAY, InstanceStatus.Missed)

            val days = read().stats.daily.map { it.date }

            assertEquals(
                listOf("2026-08-07", "2026-08-08", "2026-08-09", "2026-08-10", "2026-08-11", "2026-08-12", "2026-08-13"),
                days,
            )
        }

    @Test
    fun `shows a day with nothing recorded rather than stepping over it`() =
        withFixture(NOW) {
            parents()
            record(NOW - 3 * DAY, InstanceStatus.Completed) // the 10th
            record(NOW - DAY, InstanceStatus.Completed) // the 12th

            val stats = read().stats

            val eleventh = assertNotNull(stats.daily.find { it.date == "2026-08-11" })
            assertEquals(0, eleventh.total)
            // A day with nothing due, not a day of total failure.
            assertEquals(100.0, stats.percent)
        }

    @Test
    fun `surfaces snoozed doses in their own right`() =
        withFixture(NOW) {
            parents()
            record(NOW - DAY, InstanceStatus.Completed)
            record(NOW - 2 * DAY, InstanceStatus.Snoozed)

            val stats = read().stats

            assertEquals(1, stats.snoozed)
            assertEquals(0, stats.missed)
            assertEquals(2, stats.total)
            assertEquals(50.0, stats.percent)
        }

    @Test
    fun `leaves out what happened before the period began`() =
        withFixture(NOW) {
            parents()
            record(NOW - DAY, InstanceStatus.Completed)
            record(NOW - 30 * DAY, InstanceStatus.Missed)

            val stats = read().stats

            assertEquals(1, stats.total)
            assertEquals(100.0, stats.percent)
        }

    @Test
    fun `reports the period it covered, so the surface can name it`() =
        withFixture(NOW) {
            val period = read().period

            assertEquals(utc(2026, 8, 7), period.fromMs)
            assertEquals(utc(2026, 8, 14) - 1, period.toMs)
        }

    @Test
    fun `reports nothing rather than failing when there is no history at all`() =
        withFixture(NOW) {
            val stats = read().stats

            assertEquals(0, stats.total)
            assertEquals(0.0, stats.percent)
            assertEquals(7, stats.daily.size)
            assertTrue(stats.daily.all { it.total == 0 })
        }

    @Test
    fun `a failed sweep fails the read rather than returning a flattering figure`() =
        withFixture(NOW) {
            reminder("r1", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 30 * DAY)
            database.close()

            assertFailsWith<Exception> { read() }
        }

}
