package com.taskrx.core.sweep

import com.taskrx.core.adherence.computeAdherence
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

/** A fixed instant to hang every fixture off, so failures read as offsets. */
private val NOW: Long =
    ZonedDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

private fun reminder(
    id: String,
    schedule: ScheduleTimes,
    active: Boolean? = null,
    kind: ReminderKind = ReminderKind.Medication,
    // Long before any window these cases use, so a fixture only constrains the
    // "not before it existed" rule when it says so.
    createdAt: Long = NOW - 365 * DAY,
): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        kind = kind,
        name = "Reminder $id",
        schedule = schedule,
        createdAt = createdAt,
        updatedAt = 0,
        active = active ?: true,
    )

private fun instance(
    id: String,
    reminderId: String,
    scheduledTime: Long,
    status: InstanceStatus,
): ReminderInstance =
    createReminderInstance(
        id = id,
        reminderId = reminderId,
        userId = "user-1",
        reminderKind = ReminderKind.Medication,
        scheduledTime = scheduledTime,
        status = status,
        createdAt = 0,
        updatedAt = 0,
    )

/** A reminder that comes due once, [msAgo] before `NOW`. */
private fun due(id: String, msAgo: Long): Reminder = reminder(id, onceTimes(NOW - msAgo))

private fun sweep(
    reminders: List<Reminder>,
    instances: List<ReminderInstance>,
    now: Long = NOW,
    gracePeriodMs: Long = GRACE_PERIOD_MS,
    lookbackMs: Long = SWEEP_LOOKBACK_MS,
    zone: java.time.ZoneId = ZoneOffset.UTC,
): List<MissedVerdict> =
    planMissedSweep(
        PlanMissedSweepInput(
            reminders = reminders,
            instances = instances,
            now = now,
            gracePeriodMs = gracePeriodMs,
            lookbackMs = lookbackMs,
            zone = zone,
        ),
    )

/**
 * ADR 0005 is binding, and these two constants are the only place its numbers
 * live in code. The subject here is conformance to that decision, not the
 * sweep's behaviour - the behaviour is pinned by the boundary cases below,
 * which state their own values rather than reading these.
 */
class ValuesAdr0005SettledTest {
    @Test
    fun `the values ADR 0005 settled - keeps an occurrence answerable for two hours`() {
        assertEquals(2 * HOUR, GRACE_PERIOD_MS)
    }

    @Test
    fun `the values ADR 0005 settled - reaches seven days back in one sweep`() {
        assertEquals(7 * DAY, SWEEP_LOOKBACK_MS)
    }
}

class PlannerTest {
    @Test
    fun `planMissedSweep misses an occurrence nobody answered once its grace has elapsed`() {
        val r = due("r1", 3 * HOUR)

        assertEquals(
            listOf(
                MissedVerdict(
                    reminderId = "r1",
                    reminderKind = ReminderKind.Medication,
                    scheduledTime = NOW - 3 * HOUR,
                    instanceId = null,
                ),
            ),
            sweep(listOf(r), emptyList()),
        )
    }

    @Test
    fun `planMissedSweep leaves an occurrence alone while it is still answerable`() {
        assertEquals(emptyList(), sweep(listOf(due("r1", HOUR)), emptyList()))
    }

    @Test
    fun `planMissedSweep holds the grace boundary - answerable at the edge, missed one ms past it`() {
        val atEdge = reminder("r1", onceTimes(NOW - 2 * HOUR))
        val pastEdge = reminder("r2", onceTimes(NOW - 2 * HOUR - 1))

        assertEquals(emptyList(), sweep(listOf(atEdge), emptyList(), gracePeriodMs = 2 * HOUR))
        assertEquals(
            listOf("r2"),
            sweep(listOf(pastEdge), emptyList(), gracePeriodMs = 2 * HOUR).map { it.reminderId },
        )
    }

    @Test
    fun `planMissedSweep leaves an occurrence that has not come due at all`() {
        val r = reminder("r1", onceTimes(NOW + HOUR))

        assertEquals(emptyList(), sweep(listOf(r), emptyList()))
    }

    @Test
    fun `planMissedSweep names the stored instance when one already exists, so it is settled rather than duplicated`() {
        val r = due("r1", 3 * HOUR)
        val stored = instance("i1", "r1", NOW - 3 * HOUR, InstanceStatus.Pending)

        assertEquals(
            listOf(
                MissedVerdict(
                    reminderId = "r1",
                    reminderKind = ReminderKind.Medication,
                    scheduledTime = NOW - 3 * HOUR,
                    instanceId = "i1",
                ),
            ),
            sweep(listOf(r), listOf(stored)),
        )
    }

    @Test
    fun `planMissedSweep holds the grace boundary for a stored instance too`() {
        val r = reminder("r1", noneTimes())
        val atEdge = instance("i1", "r1", NOW - 2 * HOUR, InstanceStatus.Pending)
        val pastEdge = instance("i2", "r1", NOW - 2 * HOUR - 1, InstanceStatus.Pending)

        assertEquals(emptyList(), sweep(listOf(r), listOf(atEdge), gracePeriodMs = 2 * HOUR))
        assertEquals(
            listOf("i2"),
            sweep(listOf(r), listOf(pastEdge), gracePeriodMs = 2 * HOUR).map { it.instanceId },
        )
    }

    @Test
    fun `planMissedSweep holds the lookback boundary for a stored instance too`() {
        val r = reminder("r1", noneTimes())
        val onBound = instance("i1", "r1", NOW - 7 * DAY, InstanceStatus.Pending)

        assertEquals(
            listOf("i1"),
            sweep(listOf(r), listOf(onBound), lookbackMs = 7 * DAY).map { it.instanceId },
        )
    }

    @Test
    fun `planMissedSweep settles both rows when two pending instances claim one moment`() {
        // Two should not exist. Two do: answering mints a duplicate `pending`
        // instance beside the answered one, which the timeline projection collapses
        // for display. Each row still has to be settled, or the survivor sits
        // `pending` forever and stays out of adherence.
        val r = due("r1", 3 * HOUR)
        val first = instance("i1", "r1", NOW - 3 * HOUR, InstanceStatus.Pending)
        val second = instance("i2", "r1", NOW - 3 * HOUR, InstanceStatus.Pending)

        assertEquals(
            listOf("i1", "i2"),
            sweep(listOf(r), listOf(first, second)).map { it.instanceId!! }.sorted(),
        )
    }

    @Test
    fun `planMissedSweep settles a stored pending instance the schedule no longer predicts`() {
        // The reminder was rescheduled after the person opened the answer screen,
        // so the moment has an Instance but no Occurrence behind it any more.
        val r = reminder("r1", onceTimes(NOW + DAY))
        val stored = instance("i1", "r1", NOW - 3 * HOUR, InstanceStatus.Pending)

        assertEquals(listOf("i1"), sweep(listOf(r), listOf(stored)).map { it.instanceId })
    }

    @Test
    fun `planMissedSweep never overrules an answer the person gave`() {
        val answered =
            listOf(
                InstanceStatus.Completed,
                InstanceStatus.Skipped,
                InstanceStatus.Snoozed,
                InstanceStatus.Missed,
            )

        for (status in answered) {
            val r = due("r1", 3 * HOUR)
            val stored = instance("i1", "r1", NOW - 3 * HOUR, status)

            assertEquals(emptyList(), sweep(listOf(r), listOf(stored)), "status $status")
        }
    }

    @Test
    fun `planMissedSweep holds the lookback boundary - on the bound is swept, older than it is not`() {
        val onBound = reminder("r1", onceTimes(NOW - 7 * DAY))
        val older = reminder("r2", onceTimes(NOW - 7 * DAY - 1))

        assertEquals(
            listOf(NOW - 7 * DAY),
            sweep(listOf(onBound), emptyList(), lookbackMs = 7 * DAY).map { it.scheduledTime },
        )
        assertEquals(emptyList(), sweep(listOf(older), emptyList(), lookbackMs = 7 * DAY))
    }

    @Test
    fun `planMissedSweep bounds a stored instance by the same lookback as an occurrence`() {
        val r = reminder("r1", noneTimes())
        val older = instance("i1", "r1", NOW - 7 * DAY - 1, InstanceStatus.Pending)

        assertEquals(emptyList(), sweep(listOf(r), listOf(older), lookbackMs = 7 * DAY))
    }

    @Test
    fun `planMissedSweep predicts nothing for a reminder the person stopped`() {
        val r = reminder("r1", onceTimes(NOW - 3 * HOUR), active = false)

        assertEquals(emptyList(), sweep(listOf(r), emptyList()))
    }

    @Test
    fun `planMissedSweep still settles a stopped reminder's stored instance`() {
        // Stopping a medication does not unmake the moments already recorded
        // against it - the same rule the timeline projection follows.
        val r = reminder("r1", onceTimes(NOW - 3 * HOUR), active = false)
        val stored = instance("i1", "r1", NOW - 3 * HOUR, InstanceStatus.Pending)

        assertEquals(listOf("i1"), sweep(listOf(r), listOf(stored)).map { it.instanceId })
    }

    @Test
    fun `planMissedSweep never misses a backlog reminder, which has no moments to miss`() {
        val r = reminder("r1", noneTimes())

        assertEquals(emptyList(), sweep(listOf(r), emptyList()))
    }

    @Test
    fun `planMissedSweep carries the kind, so adherence can tell a dose from a chore`() {
        val r = reminder("r1", onceTimes(NOW - 3 * HOUR), kind = ReminderKind.Task)

        assertEquals(
            listOf(ReminderKind.Task),
            sweep(listOf(r), emptyList()).map { it.reminderKind },
        )
    }

    @Test
    fun `planMissedSweep takes a stored instance's own kind rather than looking for its reminder`() {
        val stored =
            createReminderInstance(
                id = "i1",
                reminderId = "r-gone",
                userId = "user-1",
                reminderKind = ReminderKind.Task,
                scheduledTime = NOW - 3 * HOUR,
                status = InstanceStatus.Pending,
                createdAt = 0,
                updatedAt = 0,
            )

        assertEquals(
            listOf(
                MissedVerdict(
                    reminderId = "r-gone",
                    reminderKind = ReminderKind.Task,
                    scheduledTime = NOW - 3 * HOUR,
                    instanceId = "i1",
                ),
            ),
            sweep(emptyList(), listOf(stored)),
        )
    }

    @Test
    fun `planMissedSweep orders chronologically, tie-breaking on the reminder`() {
        val early = due("r2", 5 * HOUR)
        val lateB = due("b", 3 * HOUR)
        val lateA = due("a", 3 * HOUR)

        assertEquals(
            listOf("r2", "a", "b"),
            sweep(listOf(lateB, lateA, early), emptyList()).map { it.reminderId },
        )
    }

    @Test
    fun `planMissedSweep misses every unanswered moment of a recurring schedule inside the window`() {
        // NOW is 12:00, so each of the three 09:00s inside a three-day lookback is
        // both past its grace and inside the window - and the 09:00 four days back
        // is outside it.
        val r = reminder("r1", dailyTimes(listOf("09:00")))

        assertEquals(
            listOf(
                NOW - 3 * DAY + 21 * HOUR,
                NOW - 2 * DAY + 21 * HOUR,
                NOW - DAY + 21 * HOUR,
            ),
            sweep(listOf(r), emptyList(), lookbackMs = 3 * DAY).map { it.scheduledTime },
        )
    }

    @Test
    fun `planMissedSweep invents no moment from before the reminder existed`() {
        // A daily medication added an hour ago. Day-based generation has no start of
        // its own, so without the createdAt bound this reports a week of doses the
        // person never had the medication for - and adherence falls for a treatment
        // that had not begun.
        val r = reminder("r1", dailyTimes(listOf("09:00")), createdAt = NOW - HOUR)

        assertEquals(emptyList(), sweep(listOf(r), emptyList()))
    }

    @Test
    fun `planMissedSweep counts a moment the reminder was created for`() {
        val at = NOW - 3 * HOUR
        val r = reminder("r1", onceTimes(at), createdAt = at)

        assertEquals(listOf(at), sweep(listOf(r), emptyList()).map { it.scheduledTime })
    }

    @Test
    fun `planMissedSweep returns a bounded plan for a schedule dense enough to fill the window`() {
        // The TypeScript case uses an interval of 0.01 hours, a corrupt or hostile
        // schedule column. Kotlin's IntervalTimes.hours is an Int, so that value
        // cannot be built; the densest schedule it can hold is hourly, which is
        // bounded by the window alone. The cap itself is pinned by the next case.
        val r = reminder("r1", intervalTimes(1, NOW - 7 * DAY))

        val verdicts = sweep(listOf(r), emptyList())

        assertTrue(verdicts.isNotEmpty())
        assertTrue(verdicts.size <= MAX_MISSED_PER_REMINDER)
    }

    @Test
    fun `planMissedSweep stops at the per-reminder cap and keeps the oldest moments`() {
        // Added over the TypeScript suite to cover what its 0.01-hour interval
        // exercised: a minute-by-minute daily schedule asks for 10,080 moments in
        // the window, so the cap binds and the oldest are the ones kept.
        val everyMinute = (0 until 1440).map { "%02d:%02d".format(it / 60, it % 60) }
        val r = reminder("r1", dailyTimes(everyMinute))

        val verdicts = sweep(listOf(r), emptyList())

        assertEquals(MAX_MISSED_PER_REMINDER, verdicts.size)
        assertEquals(NOW - 7 * DAY, verdicts.first().scheduledTime)
    }
}

class SweepAdherenceTest {
    @Test
    fun `what the sweep does to adherence - stops one dose in thirty from reading as perfect`() {
        // The defect issue #11 exists for. Instances are minted only when someone
        // acts, and adherence excludes `pending`, so before the sweep the single
        // dose this person took was the only row that existed - and it scored 100%.
        val start = NOW - 30 * DAY
        val r = reminder("r1", dailyTimes(listOf("09:00")), createdAt = start)
        val taken = instance("i1", "r1", NOW - 21 * HOUR, InstanceStatus.Completed)

        val swept =
            planMissedSweep(
                PlanMissedSweepInput(
                    reminders = listOf(r),
                    instances = listOf(taken),
                    now = NOW,
                    gracePeriodMs = GRACE_PERIOD_MS,
                    // The whole month, so the arithmetic is about the rule and not about the
                    // bound - which ADR 0005 already documents as leaving an older gap absent.
                    lookbackMs = 30 * DAY,
                ),
            )

        val minted =
            swept.map { verdict ->
                instance(
                    "swept-${verdict.scheduledTime}",
                    verdict.reminderId,
                    verdict.scheduledTime,
                    InstanceStatus.Missed,
                )
            }

        val before = computeAdherence(listOf(taken), start, NOW)
        val after = computeAdherence(listOf(taken) + minted, start, NOW)

        assertEquals(100.0, before.percent)
        assertTrue(after.percent < 10)
        assertEquals(after.total - 1, after.missed)
    }
}

class SweepZoneTest {
    @Test
    fun `reads a daily wall-clock time in the zone it is given`() {
        // 09:00 in Tokyo is 00:00Z; 09:00 in UTC is 09:00Z. Twenty hours of lookback
        // from 12:00Z reaches 16:00Z yesterday, so each zone finds exactly one.
        val daily = reminder("a", dailyTimes(listOf("09:00")))
        val lookback = 20 * HOUR

        val utc = sweep(listOf(daily), emptyList(), lookbackMs = lookback)
        val tokyo = sweep(listOf(daily), emptyList(), lookbackMs = lookback, zone = java.time.ZoneId.of("Asia/Tokyo"))

        assertEquals(listOf(NOW - 3 * HOUR), utc.map { it.scheduledTime })
        assertEquals(listOf(NOW - 12 * HOUR), tokyo.map { it.scheduledTime })
    }
}
