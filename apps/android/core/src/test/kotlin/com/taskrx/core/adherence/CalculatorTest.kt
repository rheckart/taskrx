package com.taskrx.core.adherence

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminderInstance
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = MS_PER_HOUR * 24

/** Local wall-clock helper, in the zone the suite runs at (`UTC`) unless told otherwise. */
private fun local(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

/** Mirrors the `Instance(...)` helper in `AdherenceCalculatorTests.cs`. */
private fun instance(
    scheduledTime: Long,
    status: InstanceStatus,
    reminderKind: ReminderKind = ReminderKind.Medication,
): ReminderInstance =
    createReminderInstance(
        id = "instance",
        reminderId = "reminder-1",
        userId = "user-1",
        reminderKind = reminderKind,
        scheduledTime = scheduledTime,
        status = status,
        createdAt = 0,
        updatedAt = 0,
    )

private fun AdherenceStats.dayDate(index: Int): String = daily[index].date
private fun AdherenceStats.dayTaken(index: Int): Int = daily[index].completed
private fun AdherenceStats.dayTotal(index: Int): Int = daily[index].total
private fun AdherenceStats.dayPercent(index: Int): Double = daily[index].percent

class ComputeAdherenceTest {
    // Compute_AllTakenInWindow_ReturnsOneHundredPercent
    @Test
    fun `returns 100 percent when everything in the window was taken`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(100.0, stats.percent)
        assertEquals(2, stats.completed)
        assertEquals(0, stats.missed)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.snoozed)
        assertEquals(2, stats.total)
    }

    // Compute_AllMissed_ReturnsZeroPercent
    @Test
    fun `returns 0 percent when everything in the window was missed`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Missed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(0.0, stats.percent)
        assertEquals(0, stats.completed)
        assertEquals(2, stats.missed)
        assertEquals(0, stats.skipped)
        assertEquals(2, stats.total)
    }

    // Compute_MixedStatuses_ReturnsFiftyPercent
    @Test
    fun `returns 50 percent for a mix of taken, missed, and skipped`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 10 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 12 * MS_PER_HOUR, InstanceStatus.Missed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Skipped),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(50.0, stats.percent)
        assertEquals(2, stats.completed)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.skipped)
        assertEquals(4, stats.total)
    }

    // Compute_PendingOnly_ReturnsZeroPercentAndZeroTotal
    // Unaffected by the Snoozed fix: Pending stays excluded from both sides,
    // regardless of reminderKind.
    @Test
    fun `returns 0 percent and 0 total when everything is still pending`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Pending),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Pending),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(0.0, stats.percent)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.missed)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.snoozed)
        assertEquals(0, stats.total)
        // The days are still listed — they are days of the period. What a pending
        // instance must not do is put anything in one.
        assertTrue(stats.daily.all { it.total == 0 })
    }

    // Compute_InstancesOutsideWindow_AreExcluded
    @Test
    fun `excludes instances scheduled outside the inclusive window`() {
        val from = local(2026, 7, 2)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from - MS_PER_DAY + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Missed),
                instance(to + MS_PER_DAY + 8 * MS_PER_HOUR, InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(0.0, stats.percent)
        assertEquals(0, stats.completed)
        assertEquals(1, stats.missed)
        assertEquals(0, stats.skipped)
        assertEquals(1, stats.total)
    }

    // Compute_DailyGrouping_CountsPerDateCorrectly
    // Instances are given day-2-before-day-1 so the assertion on ascending
    // order is real rather than a trivial reflection of already-sorted input.
    // Added in the port: the TS cases only put instances well inside or well outside the
    // window, so neither inclusive bound was pinned.
    @Test
    fun `counts an instance landing exactly on either bound and none one millisecond beyond`() {
        val from = local(2026, 7, 2)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from - 1, InstanceStatus.Completed),
                instance(from, InstanceStatus.Missed),
                instance(to, InstanceStatus.Skipped),
                instance(to + 1, InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(2, stats.total)
        assertEquals(0, stats.completed)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.skipped)
    }

    @Test
    fun `groups into local calendar days, ordered chronologically ascending`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 2, 23, 59)
        val instances =
            listOf(
                instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + MS_PER_DAY + 21 * MS_PER_HOUR, InstanceStatus.Skipped),
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(2, stats.daily.size)
        assertEquals("2026-07-01", stats.dayDate(0))
        assertEquals(1, stats.dayTaken(0))
        assertEquals(2, stats.dayTotal(0))
        assertEquals(50.0, stats.dayPercent(0))
        assertEquals("2026-07-02", stats.dayDate(1))
        assertEquals(1, stats.dayTaken(1))
        assertEquals(2, stats.dayTotal(1))
        assertEquals(50.0, stats.dayPercent(1))
    }

    // Complements the reversed-input case above: exercises the comparator's
    // other branch, where a day bucket is already ahead of the one before it.
    @Test
    fun `keeps already-ascending day buckets in order`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 2, 23, 59)
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals("2026-07-01", stats.dayDate(0))
        assertEquals("2026-07-02", stats.dayDate(1))
    }

    // Compute_EmptyInput_ReturnsZeroPercentAndZeroTotal
    // The C# built `from` from `DateTimeOffset.UtcNow`; replaced with a fixed
    // instant since this is a pure function and the value doesn't matter when
    // there are no instances to filter against it.
    @Test
    fun `returns 0 percent and 0 total for empty input`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY

        val stats = computeAdherence(emptyList(), from, to)

        assertEquals(0.0, stats.percent)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.missed)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.snoozed)
        assertEquals(0, stats.total)
        assertTrue(stats.daily.all { it.total == 0 })
    }
}

class SnoozedFixTest {
    @Test
    fun `counts a snoozed medication instance in the denominator, not the numerator`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Snoozed), // medication, default kind
            )

        val stats = computeAdherence(instances, from, to)

        // Fixed behaviour: the snooze counts in total (2) but not in taken (1),
        // giving 50%.
        assertEquals(2, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.snoozed)
        assertEquals(50.0, stats.percent)

        // The C# `AdherenceCalculator` drops Snoozed from both numerator and
        // denominator, so it would have reported total=1, taken=1 -> 100%.
        // Pin that number here so the inflation this port fixes stays visible.
        val inflatedPercentTheCSharpWouldHaveReported = 100.0
        assertTrue(stats.percent < inflatedPercentTheCSharpWouldHaveReported)
    }

    @Test
    fun `excludes a snoozed task instance from both the numerator and denominator`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Snoozed, ReminderKind.Task),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(1, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(0, stats.snoozed)
        assertEquals(100.0, stats.percent)
    }

    @Test
    fun `gives a mixed day's bucket the same denominator as the overall total`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 1, 23, 59)
        val instances =
            listOf(
                instance(from + 6 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Snoozed), // medication: counts in the denominator
                instance(from + 10 * MS_PER_HOUR, InstanceStatus.Snoozed, ReminderKind.Task), // excluded entirely
                instance(from + 12 * MS_PER_HOUR, InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to)

        // Overall: taken(1) + medication-snoozed(1) + missed(1) = 3; the
        // task-snoozed instance is excluded entirely, like Pending.
        assertEquals(3, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.snoozed)

        // The single day bucket must use the identical rule: 3, not 4 (which
        // is what the C#'s `dayTotal = g.Count()` would give by counting the
        // task snooze too) and not 2 (which is what it would be if a snooze
        // were silently dropped from the day bucket the way the C# drops it
        // from the overall total).
        assertEquals(1, stats.daily.size)
        assertEquals(3, stats.dayTotal(0))
        assertEquals(1, stats.dayTaken(0))
    }
}

class MedicationOnlyTest {
    /**
     * The rule stated as the person experiences it: a productive week must not
     * disguise a bad medication week. Half the doses were taken, so the figure
     * is 50% — and stays 50% however many tasks were ticked off beside them.
     */
    @Test
    fun `does not move the medication figure when a week of tasks is completed`() {
        val from = local(2026, 7, 1)
        val to = from + 7 * MS_PER_DAY

        val doses =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 20 * MS_PER_HOUR, InstanceStatus.Missed),
            )
        val tasks =
            List(7) { day ->
                instance(
                    from + day * MS_PER_DAY + 10 * MS_PER_HOUR,
                    InstanceStatus.Completed,
                    ReminderKind.Task,
                )
            }

        val withoutTasks = computeAdherence(doses, from, to)
        val withTasks = computeAdherence(doses + tasks, from, to)

        assertEquals(50.0, withoutTasks.percent)
        assertEquals(withoutTasks, withTasks)
    }

    @Test
    fun `does not let a missed task count as a missed dose`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Missed, ReminderKind.Task),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(1, stats.total)
        assertEquals(0, stats.missed)
        assertEquals(100.0, stats.percent)
    }

    @Test
    fun `does not let a skipped task count as a skipped dose`() {
        val from = local(2026, 7, 1)
        val to = from + MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 14 * MS_PER_HOUR, InstanceStatus.Skipped, ReminderKind.Task),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(1, stats.total)
        assertEquals(0, stats.skipped)
        assertEquals(100.0, stats.percent)
    }

    @Test
    fun `gives a day of nothing but tasks no medication commitments at all`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 2, 23, 59)
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Completed, ReminderKind.Task),
            )

        val stats = computeAdherence(instances, from, to)

        // The 2nd is listed, because it is a day of the period — but a completed
        // task put nothing in it, so it reads as a day with no doses rather than
        // as a day that went well.
        assertEquals("2026-07-02", stats.dayDate(1))
        assertEquals(0, stats.dayTotal(1))
        assertEquals(0, stats.dayTaken(1))
    }
}

/**
 * The two figures on the Adherence surface are the headline percentage and the
 * day-by-day list beneath it, and a person reading them can add the second up.
 * The C# could not survive that: `AdherenceCalculator.cs:24`'s
 * `dayTotal = g.Count()` counted Snoozed while the overall total did not, so
 * the days summed to more than the whole.
 *
 * Stated as the invariant rather than as one arithmetic example, because it is
 * the invariant a reader is checking — no combination of statuses and Kinds may
 * make the parts disagree with the sum.
 */
class DailyBreakdownAgreesTest {
    @Test
    fun `sums the daily buckets back to the overall totals`() {
        val from = local(2026, 7, 1)
        val to = from + 4 * MS_PER_DAY
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 20 * MS_PER_HOUR, InstanceStatus.Snoozed),
                instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Missed),
                instance(from + MS_PER_DAY + 21 * MS_PER_HOUR, InstanceStatus.Skipped),
                instance(from + 2 * MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + 2 * MS_PER_DAY + 10 * MS_PER_HOUR, InstanceStatus.Pending),
                instance(from + 2 * MS_PER_DAY + 11 * MS_PER_HOUR, InstanceStatus.Completed, ReminderKind.Task),
                instance(from + 3 * MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Snoozed, ReminderKind.Task),
            )

        val stats = computeAdherence(instances, from, to)

        val summedCompleted = stats.daily.sumOf { it.completed }
        val summedTotal = stats.daily.sumOf { it.total }

        assertEquals(stats.total, summedTotal)
        assertEquals(stats.completed, summedCompleted)
        // And the headline is that sum expressed as a percentage, so a person who
        // averages the days they can see arrives where the app already is.
        assertEquals(summedCompleted.toDouble() / summedTotal * 100, stats.percent)
    }

    @Test
    fun `leaves no day out, so a day of pure failure still lands in the list`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 2, 23, 59)
        val instances =
            listOf(
                instance(from + 8 * MS_PER_HOUR, InstanceStatus.Completed),
                instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(listOf("2026-07-01", "2026-07-02"), stats.daily.map { it.date })
        assertEquals(0.0, stats.dayPercent(1))
    }
}

/**
 * The daily breakdown covers the PERIOD, not just the parts of it that have
 * data.
 *
 * A day on which nothing was recorded used to be absent from `daily` entirely,
 * which left the surface rendering it no differently from a day the reader had
 * scrolled past — the list simply skipped from Monday to Wednesday. ADR 0005
 * endorses the underlying reading ("a gap reads as no data rather than as a wall
 * of Missed"), but a gap nobody can see is not a reading, it is an omission.
 *
 * The empty day carries `total: 0`, which is what distinguishes it. `percent` is
 * `0` there only because `0/0` has to be something — a consumer must test
 * `total` and not `percent`, or it will render a day with nothing due as a day on
 * which every dose was missed. Those are opposite facts.
 *
 * This is deliberately NOT "every day a dose was due": the calculator sees
 * Instances and never Reminders, so it cannot tell a day with nothing scheduled
 * from a day whose history the sweep could not reach. Both are honestly "no
 * doses recorded", and neither is invented as a miss.
 */
class BreakdownSpansWholePeriodTest {
    @Test
    fun `includes a day in the middle of the period on which nothing was recorded`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 3, 23, 59)
        val instances =
            listOf(
                instance(local(2026, 7, 1, 8), InstanceStatus.Completed),
                // Nothing at all on the 2nd.
                instance(local(2026, 7, 3, 8), InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(listOf("2026-07-01", "2026-07-02", "2026-07-03"), stats.daily.map { it.date })
        assertEquals(0, stats.dayTotal(1))
        assertEquals(0, stats.dayTaken(1))
    }

    @Test
    fun `gives a period with no history at all one row per day, not none`() {
        val stats = computeAdherence(emptyList(), local(2026, 7, 1), local(2026, 7, 7, 23, 59))

        assertEquals(7, stats.daily.size)
        assertTrue(stats.daily.all { it.total == 0 })
        // The overall figure is still the "no data" one, not a failing one.
        assertEquals(0, stats.total)
        assertEquals(0.0, stats.percent)
    }

    @Test
    fun `counts an empty day as no commitments rather than as missed ones`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 2, 23, 59)
        val instances = listOf(instance(local(2026, 7, 1, 8), InstanceStatus.Completed))

        val stats = computeAdherence(instances, from, to)

        // The empty day must not drag the overall figure down: nothing was due, so
        // nothing was missed, and the week was in fact perfect.
        assertEquals(100.0, stats.percent)
        assertEquals(1, stats.total)
        assertEquals(0, stats.dayTotal(1))
    }

    @Test
    fun `still sums the daily buckets back to the overall totals`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 5, 23, 59)
        val instances =
            listOf(
                instance(local(2026, 7, 1, 8), InstanceStatus.Completed),
                instance(local(2026, 7, 4, 8), InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(5, stats.daily.size)
        assertEquals(stats.total, stats.daily.sumOf { it.total })
    }

    @Test
    fun `orders the days chronologically whatever order the instances arrive in`() {
        val from = local(2026, 7, 1)
        val to = local(2026, 7, 3, 23, 59)
        val instances =
            listOf(
                instance(local(2026, 7, 3, 8), InstanceStatus.Completed),
                instance(local(2026, 7, 1, 8), InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to)

        assertEquals(listOf("2026-07-01", "2026-07-02", "2026-07-03"), stats.daily.map { it.date })
    }

    @Test
    fun `walks across a month boundary`() {
        val stats = computeAdherence(emptyList(), local(2026, 1, 30), local(2026, 2, 2, 23, 59))

        assertEquals(
            listOf("2026-01-30", "2026-01-31", "2026-02-01", "2026-02-02"),
            stats.daily.map { it.date },
        )
    }

    @Test
    fun `emits nothing for a window that ends before it starts`() {
        val stats = computeAdherence(emptyList(), local(2026, 7, 3), local(2026, 7, 1))

        assertEquals(emptyList(), stats.daily)
    }
}

/**
 * Days are local calendar days on both sides of a clock change.
 *
 * The two implementations that fail here are the ones a reader reaches for
 * first: grouping by the UTC date, which puts a 21:00 dose on tomorrow for every
 * North American offset; and `ms / 86_400_000`, which groups by UTC day-number,
 * does the same and additionally shifts by an hour when the offset changes, so
 * one calendar day's doses split across two buckets. Either one makes the
 * day-by-day list disagree with the day the person remembers.
 */
class DaylightSavingTest {
    private val newYork = ZoneId.of("America/New_York")

    private fun ny(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        local(year, month, day, hour, minute, newYork)

    @Test
    fun `keeps a spring-forward day's doses on that one day`() {
        // 2027-03-14 is the US spring-forward date: a 23-hour local day.
        val from = ny(2027, 3, 13)
        val to = ny(2027, 3, 15, 23, 59)
        val instances =
            listOf(
                instance(ny(2027, 3, 14, 9), InstanceStatus.Completed),
                instance(ny(2027, 3, 14, 21), InstanceStatus.Missed),
            )

        val stats = computeAdherence(instances, from, to, newYork)

        // Three rows for three calendar days: the 23-hour day is one day, not two
        // and not a fraction of one, which is also what pins the day-walk itself as
        // DST-safe — a fixed 24-hour step would slip and emit a duplicate key.
        assertEquals(3, stats.daily.size)
        assertEquals("2027-03-14", stats.dayDate(1))
        assertEquals(2, stats.dayTotal(1))
    }

    @Test
    fun `keeps a fall-back day's doses on that one day`() {
        // 2027-11-07 is the US fall-back date: a 25-hour local day, and the one
        // where 01:30 happens twice.
        val from = ny(2027, 11, 6)
        val to = ny(2027, 11, 8, 23, 59)
        val instances =
            listOf(
                instance(ny(2027, 11, 7, 9), InstanceStatus.Completed),
                instance(ny(2027, 11, 7, 21), InstanceStatus.Completed),
            )

        val stats = computeAdherence(instances, from, to, newYork)

        // And three for the 25-hour day, where a fixed step slips the other way.
        assertEquals(3, stats.daily.size)
        assertEquals("2027-11-07", stats.dayDate(1))
        assertEquals(2, stats.dayTotal(1))
    }

    @Test
    fun `separates a late dose from the next morning's across a transition`() {
        val from = ny(2027, 3, 13)
        val to = ny(2027, 3, 15, 23, 59)
        val instances =
            listOf(
                instance(ny(2027, 3, 13, 23), InstanceStatus.Completed), // late on the eve
                instance(ny(2027, 3, 14, 9), InstanceStatus.Missed), // the morning after
            )

        val stats = computeAdherence(instances, from, to, newYork)

        assertEquals(listOf("2027-03-13", "2027-03-14", "2027-03-15"), stats.daily.map { it.date })
        assertEquals(1, stats.dayTotal(0))
        assertEquals(1, stats.dayTotal(1))
    }
}
