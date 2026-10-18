package com.taskrx.core.timeline

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.InstanceStatus.Completed
import com.taskrx.core.domain.InstanceStatus.Missed
import com.taskrx.core.domain.InstanceStatus.Pending
import com.taskrx.core.domain.InstanceStatus.Skipped
import com.taskrx.core.domain.InstanceStatus.Snoozed
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/*
 * The timeline projection — the rule underneath issue #10's screen.
 *
 * These cases state rules, per `docs/specs/fused-timeline.md`: what the day
 * contains, in what order, and which entry the person should act on. They do
 * not assert shipped shapes — no case says "returns three rows" as its subject,
 * and none reaches into how the union was assembled.
 *
 * The two halves being fused are of genuinely different kinds. An Occurrence is
 * computed from a Schedule and has no identity; an Instance is a stored record
 * of what became of one. The interesting rules are all at the seam: the same
 * moment must not appear twice, and when both exist the stored answer is the
 * one that counts.
 *
 * The build pins the JVM to UTC (as vitest.config.ts pins TZ=UTC), so local
 * calendar arithmetic and UTC agree and the fixtures below are plain UTC values.
 */

private fun utc(day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0): Long =
    ZonedDateTime.of(2026, 8, day, hour, minute, second, nano, ZoneOffset.UTC).toInstant().toEpochMilli()

private val DAY_START = utc(5, 0, 0)
private val DAY_END = utc(5, 23, 59, 59, 999_000_000)
private val DAY = TimelineWindow(DAY_START, DAY_END)

private val PREVIOUS_DAY = TimelineWindow(utc(4, 0, 0), utc(4, 23, 59, 59, 999_000_000))

private const val MS_PER_DAY = 86_400_000L

private fun at(hour: Int, minute: Int = 0): Long = utc(5, hour, minute)

private fun daily(vararg times: String): ScheduleTimes = dailyTimes(times.toList())

private fun medication(
    id: String = "rem-metformin",
    schedule: ScheduleTimes = daily("09:00"),
): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        kind = ReminderKind.Medication,
        name = "Metformin",
        dosage = "500mg",
        schedule = schedule,
        createdAt = 0,
        updatedAt = 0,
    )

private fun task(
    id: String = "rem-pharmacy",
    schedule: ScheduleTimes = daily("10:00"),
): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        kind = ReminderKind.Task,
        name = "Call the pharmacy",
        schedule = schedule,
        createdAt = 0,
        updatedAt = 0,
    )

private fun answered(
    id: String = "inst-1",
    reminderId: String = "rem-metformin",
    reminderKind: ReminderKind = ReminderKind.Medication,
    scheduledTime: Long = at(9),
    status: InstanceStatus = Completed,
    snoozedUntil: Long? = null,
    updatedAt: Long = 0,
    completedAt: Long? = null,
): ReminderInstance =
    createReminderInstance(
        id = id,
        reminderId = reminderId,
        userId = "user-1",
        reminderKind = reminderKind,
        scheduledTime = scheduledTime,
        status = status,
        snoozedUntil = snoozedUntil,
        completedAt = completedAt,
        createdAt = 0,
        updatedAt = updatedAt,
    )

private fun project(
    reminders: List<Reminder>,
    instances: List<ReminderInstance> = emptyList(),
    window: TimelineWindow = DAY,
    now: Long = DAY_START,
): List<TimelineRow> = projectTimeline(ProjectTimelineInput(reminders, instances, window, now))

/** The moment/status pairs a row reduces to, which is what the rules are about. */
private fun shape(rows: List<TimelineRow>): List<Pair<Long, InstanceStatus>> =
    rows.map { it.scheduledTime to it.status }

class ProjectTimelineOrderingTest {
    @Test
    fun `returns the day in chronological order regardless of input order`() {
        val rows =
            project(
                listOf(
                    medication("rem-evening", daily("21:00")),
                    medication("rem-morning", daily("09:00")),
                    medication("rem-midday", daily("13:00")),
                ),
            )

        assertEquals(listOf(at(9), at(13), at(21)), rows.map { it.scheduledTime })
    }

    /**
     * Two reminders due at the same instant need a total order, or the list
     * reshuffles between renders of identical data. Same tie-break as
     * `selectMedicationHorizon`, and for the same reason.
     */
    @Test
    fun `breaks a tie on reminder id so equal moments do not reshuffle`() {
        val forwards = project(listOf(medication("rem-b", daily("09:00")), medication("rem-a", daily("09:00"))))
        val backwards = project(listOf(medication("rem-a", daily("09:00")), medication("rem-b", daily("09:00"))))

        assertEquals(listOf("rem-a", "rem-b"), forwards.map { it.reminderId })
        assertEquals(listOf("rem-a", "rem-b"), backwards.map { it.reminderId })
    }
}

class ProjectTimelineUnionTest {
    @Test
    fun `shows an occurrence nobody has answered as still pending`() {
        val rows = project(listOf(medication()))

        assertEquals(listOf(at(9) to Pending), shape(rows))
        assertNull(rows[0].instanceId)
    }

    /**
     * The deduplication rule, and the reason the projection is a union rather
     * than a concatenation: the 09:00 moment is both one the Schedule predicts
     * and one the person answered. It is one entry in their day.
     */
    @Test
    fun `shows an answered occurrence once, in the state it was answered`() {
        val rows = project(listOf(medication()), listOf(answered(status = Completed)))

        assertEquals(listOf(at(9) to Completed), shape(rows))
        assertEquals("inst-1", rows[0].instanceId)
    }

    @Test
    fun `carries when a completed dose was taken, and nothing for any other row`() {
        val rows =
            project(
                listOf(medication()),
                listOf(
                    answered(id = "taken", status = Completed, completedAt = at(9, 12)),
                    answered(id = "skipped", scheduledTime = at(11), status = Skipped, completedAt = at(11, 3)),
                ),
            )

        assertEquals(listOf(at(9, 12), null), rows.map { it.completedAt })
    }

    @Test
    fun `pairs an instance to its occurrence by reminder and moment together`() {
        // Same reminder, a moment the schedule does not predict: this is a distinct
        // entry, not the 09:00 one relabelled. Pairing on reminder alone would
        // swallow it; pairing on moment alone would attach it to another reminder.
        val rows = project(listOf(medication()), listOf(answered(id = "inst-late", scheduledTime = at(11))))

        assertEquals(listOf(at(9) to Pending, at(11) to Completed), shape(rows))
    }

    /**
     * Found on a device, not in a test: the timeline rendered the 17:36 moment
     * twice, once `pending` and once `completed`, and raised "Encountered two
     * children with the same key".
     *
     * Two Instances sharing one pairing is not hypothetical — answering currently
     * mints a duplicate `pending` Instance beside the `completed` one, a defect
     * recorded in `docs/CHANGELOG.md` on 2026-08-03 and still open. The pairing is
     * what identifies a moment on the timeline, so it must hold across stored rows
     * too, not only between a stored row and a predicted one. Rendering a broken
     * list because the data upstream is broken is a second defect, not a symptom.
     *
     * The duplicate here is deliberately the *newer* row and the one that sorts
     * first by id — the shape the live defect actually produces, since the stray
     * `pending` is minted after the answer. An implementation resolving this on
     * recency or on id alone would pick the wrong one and show a medication that
     * was taken as still outstanding.
     */
    @ParameterizedTest(name = "keeps the answer, not the stray pending row ({0})")
    @MethodSource("strayPendingOrders")
    fun `keeps the answer, not the stray pending row`(order: String, ids: List<String>) {
        val byId =
            mapOf(
                "inst-a-done" to answered(id = "inst-a-done", status = Completed, updatedAt = 10),
                "inst-b-pending" to answered(id = "inst-b-pending", status = Pending, updatedAt = 99),
            )

        val rows = project(listOf(medication()), ids.map { byId.getValue(it) })

        assertEquals(listOf(at(9) to Completed), shape(rows), order)
        assertEquals("inst-a-done", rows[0].instanceId)
    }

    @Test
    fun `keeps the newer answer when two answers claim the same moment`() {
        val older = answered(id = "inst-old", status = Skipped, updatedAt = 10)
        val newer = answered(id = "inst-new", status = Completed, updatedAt = 20)

        // Both orders, because which one the caller happens to list first is not
        // part of the rule.
        for (instances in listOf(listOf(older, newer), listOf(newer, older))) {
            val rows = project(listOf(medication()), instances)

            assertEquals(1, rows.size)
            assertEquals("inst-new", rows[0].instanceId)
        }
    }

    /** Equal timestamps still have to resolve, or the row flickers between renders. */
    @Test
    fun `settles on one row when the duplicates are the same age`() {
        val instances =
            listOf(
                answered(id = "inst-b", status = Completed, updatedAt = 10),
                answered(id = "inst-a", status = Skipped, updatedAt = 10),
            )
        val forwards = project(listOf(medication()), instances)
        val backwards = project(listOf(medication()), instances.reversed())

        assertEquals(1, forwards.size)
        assertEquals(backwards[0].instanceId, forwards[0].instanceId)
    }

    // Added in the port: the case above only requires the two orders to agree, which a
    // flipped tie-break also satisfies.
    @Test
    fun `settles an exact tie on the lower instance id`() {
        val instances =
            listOf(
                answered(id = "inst-b", status = Completed, updatedAt = 10),
                answered(id = "inst-a", status = Skipped, updatedAt = 10),
            )

        for (order in listOf(instances, instances.reversed())) {
            assertEquals("inst-a", project(listOf(medication()), order)[0].instanceId)
        }
    }

    @Test
    fun `does not let one reminder's answer settle another's occurrence`() {
        val rows =
            project(
                listOf(medication("rem-a", daily("09:00")), medication("rem-b", daily("09:00"))),
                listOf(answered(id = "inst-a", reminderId = "rem-a", scheduledTime = at(9))),
            )

        assertEquals(
            listOf("rem-a" to Completed, "rem-b" to Pending),
            rows.map { it.reminderId to it.status },
        )
    }

    @ParameterizedTest(name = "keeps {0} on the timeline - {1}")
    @MethodSource("keptStatuses")
    fun `keeps status on the timeline`(status: InstanceStatus, meaning: String) {
        val rows = project(listOf(medication()), listOf(answered(status = status)), now = DAY_END)

        assertEquals(listOf(at(9) to status), shape(rows), meaning)
    }

    @Test
    fun `carries the reminder's identity onto a row so it can be rendered`() {
        val rows = project(listOf(medication().copy(name = "Metformin XR", dosage = "750mg")))

        assertEquals("Metformin XR", rows[0].name)
        assertEquals("750mg", rows[0].dosage)
        assertEquals(ReminderKind.Medication, rows[0].kind)
        assertEquals("rem-metformin", rows[0].reminderId)
    }

    /**
     * The projection is the *fused* one. Tasks have no screen until #13, but the
     * rule must not be medication-shaped, or #13 becomes a rewrite rather than a
     * caller.
     */
    @Test
    fun `projects a task by the same rules, carrying its kind`() {
        val rows =
            project(
                listOf(
                    medication("rem-task", daily("14:00")).copy(
                        kind = ReminderKind.Task,
                        name = "Call pharmacy",
                        dosage = null,
                    ),
                ),
            )

        assertEquals(ReminderKind.Task, rows[0].kind)
        assertEquals(Pending, rows[0].status)
    }

    companion object {
        @JvmStatic
        fun strayPendingOrders() =
            listOf(
                org.junit.jupiter.params.provider.Arguments.of("answer first", listOf("inst-a-done", "inst-b-pending")),
                org.junit.jupiter.params.provider.Arguments.of("duplicate first", listOf("inst-b-pending", "inst-a-done")),
            )

        @JvmStatic
        fun keptStatuses() =
            listOf(
                org.junit.jupiter.params.provider.Arguments.of(Completed, "an Occurrence the person answered"),
                org.junit.jupiter.params.provider.Arguments.of(Skipped, "one deliberately declined"),
                org.junit.jupiter.params.provider.Arguments.of(Missed, "one that ran out of time"),
                org.junit.jupiter.params.provider.Arguments.of(Snoozed, "one pushed back to later"),
            )
    }
}

class ProjectTimelineWindowTest {
    @Test
    fun `leaves out moments beyond the day being viewed`() {
        val rows = project(listOf(medication(schedule = intervalTimes(6, at(3)))))

        for (row in rows) {
            assertTrue(row.scheduledTime >= DAY_START)
            assertTrue(row.scheduledTime <= DAY_END)
        }
        assertTrue(rows.isNotEmpty())
    }

    /**
     * Both bounds are inclusive, and both halves of the union have to honour
     * that — a predicted moment and a recorded one are filtered by different
     * code. All four combinations are pinned across the next two cases; covering
     * only the diagonal let an exclusive bound survive on each side.
     *
     * Midnight is why the lower bound matters: a moment at 00:00 belongs to the day
     * that begins then, and an exclusive bound would drop it from every day it
     * could appear on.
     */
    @Test
    fun `includes a predicted moment landing exactly on either bound`() {
        val rows =
            project(
                listOf(medication("rem-midnight", daily("00:00")), medication("rem-evening", daily("21:00"))),
                // Ends exactly on the evening Occurrence, which `DAY_END` cannot express: no
                // clock time lands on 23:59:59.999.
                window = TimelineWindow(DAY_START, at(21)),
            )

        assertEquals(listOf(DAY_START, at(21)), rows.map { it.scheduledTime })
    }

    @Test
    fun `includes a recorded moment landing exactly on either bound`() {
        val rows =
            project(
                listOf(medication().copy(active = false)),
                listOf(
                    answered(id = "inst-start", scheduledTime = DAY_START),
                    answered(id = "inst-end", scheduledTime = DAY_END),
                ),
            )

        assertEquals(listOf(DAY_START, DAY_END), rows.map { it.scheduledTime })
    }

    @Test
    fun `leaves out a recorded answer from another day`() {
        val rows =
            project(
                listOf(medication()),
                listOf(answered(id = "inst-yesterday", scheduledTime = at(9) - MS_PER_DAY)),
            )

        assertEquals(listOf(at(9) to Pending), shape(rows))
    }

    // Added in the port: v8 does not split `a && b` into branches, Kover does, so the
    // upper bound needs a recorded moment past it to be exercised on its own.
    @Test
    fun `leaves out a recorded answer from the day after`() {
        val rows =
            project(
                listOf(medication()),
                listOf(answered(id = "inst-tomorrow", scheduledTime = at(9) + MS_PER_DAY)),
            )

        assertEquals(listOf(at(9) to Pending), shape(rows))
    }

    /** The "view a previous day" criterion, as a property of the window alone. */
    @Test
    fun `projects whichever day it is given, not the one containing now`() {
        val yesterdayNineAm = at(9) - MS_PER_DAY
        val rows =
            project(
                listOf(medication()),
                listOf(answered(id = "inst-yesterday", scheduledTime = yesterdayNineAm)),
                window = PREVIOUS_DAY,
                now = at(15),
            )

        assertEquals(listOf(yesterdayNineAm to Completed), shape(rows))
    }

    @Test
    fun `says nothing is scheduled by returning nothing at all`() {
        assertEquals(emptyList(), project(emptyList()))
    }
}

class ProjectTimelineNextTest {
    private fun nextTimes(rows: List<TimelineRow>): List<Long> =
        rows.filter { it.isNext }.map { it.scheduledTime }

    @Test
    fun `points at the soonest thing still unanswered`() {
        val rows =
            project(
                listOf(
                    medication("rem-a", daily("09:00")),
                    medication("rem-b", daily("13:00")),
                    medication("rem-c", daily("21:00")),
                ),
                now = at(10),
            )

        assertEquals(listOf(at(13)), nextTimes(rows))
    }

    @Test
    fun `looks past an answered moment to the next unanswered one`() {
        val rows =
            project(
                listOf(medication("rem-a", daily("13:00")), medication("rem-b", daily("21:00"))),
                listOf(answered(id = "inst-early", reminderId = "rem-a", scheduledTime = at(13))),
                now = at(10),
            )

        assertEquals(listOf(at(21)), nextTimes(rows))
    }

    @Test
    fun `points at nothing once the day holds nothing unanswered ahead`() {
        val rows = project(listOf(medication()), listOf(answered(status = Completed)), now = at(23))

        assertFalse(rows.any { it.isNext })
    }

    /**
     * An Occurrence whose moment has passed but which nobody answered is overdue,
     * not next. Deliberately independent of the Grace Period, which
     * `docs/specs/fused-timeline.md` leaves open for the sweep in #11 — this rule
     * must not quietly pick a value for it.
     */
    @Test
    fun `does not call a moment that has already passed the next one`() {
        val rows = project(listOf(medication()), now = at(17))

        assertEquals(listOf(at(9) to Pending), shape(rows))
        assertFalse(rows.any { it.isNext })
    }

    @Test
    fun `points at an Occurrence due at this very moment`() {
        val rows = project(listOf(medication()), now = at(9))

        assertTrue(rows[0].isNext)
    }

    @Test
    fun `points at one row only when several share the soonest moment`() {
        val rows = project(listOf(medication("rem-a", daily("09:00")), medication("rem-b", daily("09:00"))))

        assertEquals(listOf("rem-a"), rows.filter { it.isNext }.map { it.reminderId })
    }

    @Test
    fun `does not treat a deferred Occurrence as the next thing to do`() {
        // A snooze is an answer to "not now". Treating it as next would put the
        // person straight back where they declined to be.
        val rows =
            project(
                listOf(medication("rem-a", daily("13:00")), medication("rem-b", daily("21:00"))),
                listOf(
                    answered(id = "inst-snoozed", reminderId = "rem-a", scheduledTime = at(13), status = Snoozed),
                ),
                now = at(10),
            )

        assertEquals(listOf(at(21)), nextTimes(rows))
    }
}

class ProjectTimelineUnscheduledTest {
    @Test
    fun `stops predicting moments for a reminder that is no longer active`() {
        val rows = project(listOf(medication().copy(active = false)))

        assertEquals(emptyList(), rows)
    }

    /** Stopping a medication does not unmake the Instances already recorded. */
    @Test
    fun `still shows what was recorded against a stopped reminder`() {
        val rows =
            project(
                listOf(medication().copy(active = false)),
                listOf(answered(status = Completed)),
                now = DAY_END,
            )

        assertEquals(listOf(at(9) to Completed), shape(rows))
    }

    @Test
    fun `keeps a backlog reminder off the day entirely`() {
        // A Reminder with no Schedule has no moments to place, so it belongs to the
        // Backlog surface rather than to any day. It reaches the timeline only once
        // it has been acted on, as the Instance that act mints.
        val rows = project(listOf(medication(schedule = noneTimes())))

        assertEquals(emptyList(), rows)
    }

    @Test
    fun `places a single-moment reminder on the day it falls on`() {
        val rows = project(listOf(medication(schedule = onceTimes(at(14)))))

        assertEquals(listOf(at(14) to Pending), shape(rows))
    }

    @Test
    fun `drops a recorded answer whose reminder is not in hand`() {
        // There is no name or dosage to render it with, and inventing one would put
        // a row on the timeline that names nothing.
        val rows = project(emptyList(), listOf(answered(reminderId = "rem-vanished")))

        assertEquals(emptyList(), rows)
    }
}

/**
 * The fusion itself — issue #13, and the reason every ticket before it existed.
 *
 * The projection was written Kind-neutral from the start, so these cases pin a
 * property rather than introduce one: the day is ordered by time and by nothing
 * else, and a row carries its Kind so the surface can tell one from the other.
 * A regression here is somebody reintroducing a Kind filter or a Kind sort.
 */
class ProjectTimelineFusesBothKindsTest {
    @Test
    fun `interleaves a task with a dose in time order`() {
        val rows =
            project(
                listOf(
                    task("rem-pharmacy", daily("10:00")),
                    medication("rem-metformin", daily("09:00")),
                    task("rem-walk", daily("18:00")),
                    medication("rem-statin", daily("21:00")),
                ),
            )

        assertEquals(
            listOf(
                at(9) to ReminderKind.Medication,
                at(10) to ReminderKind.Task,
                at(18) to ReminderKind.Task,
                at(21) to ReminderKind.Medication,
            ),
            rows.map { it.scheduledTime to it.kind },
        )
    }

    @Test
    fun `carries each row's Kind, so a dose and a task can be told apart`() {
        val rows = project(listOf(task(), medication()))

        assertEquals(
            listOf("Metformin" to ReminderKind.Medication, "Call the pharmacy" to ReminderKind.Task),
            rows.map { it.name to it.kind },
        )
    }

    @Test
    fun `gives a task no dosage to render`() {
        val rows = project(listOf(task()))

        assertEquals(listOf(null), rows.map { it.dosage })
    }

    /**
     * "Next" is the soonest unanswered moment, full stop. A dose does not jump
     * the queue ahead of a task that comes first — the person is being told what
     * to do now, and at 09:00 that is the task.
     */
    @Test
    fun `badges the soonest unanswered moment as next whichever Kind it is`() {
        val rows =
            project(listOf(task("rem-pharmacy", daily("09:00")), medication("rem-metformin", daily("13:00"))))

        assertEquals(listOf(ReminderKind.Task), rows.filter { it.isNext }.map { it.kind })
    }

    /**
     * A snooze is the one answer that names a second moment, and a Kind whose
     * Tier has no trigger yet has nothing else to show for it. The row stays where
     * it was — `scheduledTime` is the instant it occupies, and half the
     * deduplication key — and carries the deferral so a surface can say when.
     */
    @Test
    fun `reports where a snooze deferred a moment to, without moving it`() {
        val rows =
            project(
                listOf(task()),
                listOf(
                    answered(
                        reminderId = "rem-pharmacy",
                        reminderKind = ReminderKind.Task,
                        scheduledTime = at(10),
                        status = Snoozed,
                        snoozedUntil = at(14),
                    ),
                ),
            )

        assertEquals(
            listOf(Triple(at(10), Snoozed, at(14))),
            rows.map { Triple(it.scheduledTime, it.status, it.snoozedUntil) },
        )
    }

    @Test
    fun `reports no deferral for a moment nobody has deferred`() {
        val rows = project(listOf(task()))

        assertEquals(listOf(null), rows.map { it.snoozedUntil })
    }

    @Test
    fun `keeps a task's answer on its own moment rather than settling a dose`() {
        val rows =
            project(
                listOf(task("rem-pharmacy", daily("09:00")), medication("rem-metformin", daily("09:00"))),
                listOf(
                    answered(
                        id = "inst-task",
                        reminderId = "rem-pharmacy",
                        reminderKind = ReminderKind.Task,
                        scheduledTime = at(9),
                        status = Completed,
                    ),
                ),
            )

        assertEquals(
            listOf("rem-metformin" to Pending, "rem-pharmacy" to Completed),
            rows.map { it.reminderId to it.status },
        )
    }
}
