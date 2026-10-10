package com.taskrx.core.schedule

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun reminder(id: String, schedule: ScheduleTimes, active: Boolean = true): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        kind = ReminderKind.Medication,
        name = "Reminder $id",
        dosage = "10mg",
        schedule = schedule,
        active = active,
        createdAt = 0,
        updatedAt = 0,
    )

private fun task(id: String, schedule: ScheduleTimes, active: Boolean = true): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        kind = ReminderKind.Task,
        name = "Task $id",
        schedule = schedule,
        active = active,
        createdAt = 0,
        updatedAt = 0,
    )

private fun instant(iso: String): Long = Instant.parse(iso).toEpochMilli()

/** All tests use UTC (the Gradle test task pins `user.timezone`, as vitest pins `TZ`). */
private val NOON_UTC = instant("2026-07-26T12:00:00Z")

class SelectMedicationHorizonTest {
    @Test
    fun `returns an empty array when maxTotal is 0`() {
        val result = selectMedicationHorizon(listOf(reminder("r1", dailyTimes(listOf("09:00")))), NOON_UTC, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `returns an empty array when maxTotal is negative`() {
        val result = selectMedicationHorizon(listOf(reminder("r1", dailyTimes(listOf("09:00")))), NOON_UTC, -5)
        assertEquals(0, result.size)
    }

    @Test
    fun `returns an empty array when there are no reminders`() {
        assertEquals(0, selectMedicationHorizon(emptyList(), NOON_UTC, 64).size)
    }

    @Test
    fun `skips inactive reminders entirely`() {
        val active = reminder("active", dailyTimes(listOf("09:00")))
        val inactive = reminder("inactive", dailyTimes(listOf("09:30")), false)
        val result = selectMedicationHorizon(listOf(active, inactive), NOON_UTC, 64)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.reminderId == "active" })
    }

    @Test
    fun `caps the returned list at maxTotal even with many reminders`() {
        val reminders = List(10) { i -> reminder("r$i", dailyTimes(listOf("09:00"))) }
        assertEquals(64, selectMedicationHorizon(reminders, NOON_UTC, 64).size)
    }

    @Test
    fun `returns occurrences in non-decreasing fireAt order`() {
        val reminders =
            listOf(
                reminder("a", dailyTimes(listOf("09:00", "21:00"))),
                reminder("b", dailyTimes(listOf("12:00", "18:00"))),
                reminder("c", weeklyTimes(listOf(0, 3), listOf("06:00", "15:00"))),
            )
        val times = selectMedicationHorizon(reminders, NOON_UTC, 12).map { it.fireAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `interleaves occurrences from multiple reminders by time`() {
        val a = reminder("a", intervalTimes(1, instant("2026-07-26T13:00:00Z")))
        val b = reminder("b", intervalTimes(1, instant("2026-07-26T12:30:00Z")))
        val result = selectMedicationHorizon(listOf(a, b), NOON_UTC, 4)
        assertEquals(listOf("b", "a", "b", "a"), result.map { it.reminderId })
    }

    @Test
    fun `prefers closer occurrences from one reminder over farther ones`() {
        val a = reminder("a", dailyTimes(listOf("21:00")))
        val b = reminder("b", intervalTimes(1, instant("2026-07-26T13:00:00Z")))
        val result = selectMedicationHorizon(listOf(a, b), NOON_UTC, 4)
        assertEquals(4, result.count { it.reminderId == "b" })
        assertEquals(0, result.count { it.reminderId == "a" })
    }

    @Test
    fun `never returns an occurrence at or before fromMs`() {
        val past = reminder("past", dailyTimes(listOf("06:00")))
        val result = selectMedicationHorizon(listOf(past), NOON_UTC, 4)
        assertEquals(4, result.size)
        for (occurrence in result) {
            assertTrue(occurrence.fireAt > NOON_UTC)
        }
    }

    @Test
    fun `schedules weekly reminders`() {
        // NOON_UTC is Sunday 2026-07-26; day 3 is Wednesday.
        val weekly = reminder("weekly", weeklyTimes(listOf(3), listOf("08:00")))
        val result = selectMedicationHorizon(listOf(weekly), NOON_UTC, 2)
        assertEquals(
            listOf(instant("2026-07-29T08:00:00Z"), instant("2026-08-05T08:00:00Z")),
            result.map { it.fireAt },
        )
        assertEquals(
            listOf("weekly:${result[0].fireAt}", "weekly:${result[1].fireAt}"),
            result.map { it.occurrenceId },
        )
    }

    @Test
    fun `interleaves a weekly reminder with a daily one by fire time`() {
        val daily = reminder("daily", dailyTimes(listOf("10:00")))
        // Sunday 2026-07-26 is day 0, so this fires later today at 20:00.
        val weekly = reminder("weekly", weeklyTimes(listOf(0), listOf("20:00")))
        val result = selectMedicationHorizon(listOf(daily, weekly), NOON_UTC, 3)
        assertEquals(listOf("weekly", "daily", "daily"), result.map { it.reminderId })
    }

    @Test
    fun `leaves a task out of the medication Horizon entirely`() {
        val errand = task("t-errand", dailyTimes(listOf("13:00")))
        assertEquals(0, selectMedicationHorizon(listOf(errand), NOON_UTC, 64).size)
    }

    /**
     * ADR 0003's whole argument, as a case: a bounded Horizon shared with tasks
     * is one a dozen routine tasks can fill, leaving an evening dose unarmed.
     * The tasks here are all sooner than the dose and there is room for exactly
     * one alarm, so a Kind-blind selector arms a task and drops the dose.
     */
    @Test
    fun `never lets a task displace a dose from a full Horizon`() {
        val tasks = List(12) { i -> task("t-$i", dailyTimes(listOf("13:00"))) }
        val dose = reminder("insulin", dailyTimes(listOf("21:00")))

        val result = selectMedicationHorizon(tasks + dose, NOON_UTC, 1)

        assertEquals(listOf("insulin"), result.map { it.reminderId })
    }

    @Test
    fun `breaks ties on fireAt by reminderId, independent of input order`() {
        val alpha = reminder("alpha", dailyTimes(listOf("15:00")))
        val bravo = reminder("bravo", dailyTimes(listOf("15:00")))
        val forwards = selectMedicationHorizon(listOf(alpha, bravo), NOON_UTC, 2)
        val backwards = selectMedicationHorizon(listOf(bravo, alpha), NOON_UTC, 2)
        assertEquals(listOf("alpha", "bravo"), forwards.map { it.reminderId })
        assertEquals(listOf("alpha", "bravo"), backwards.map { it.reminderId })
        assertEquals(forwards[0].fireAt, forwards[1].fireAt)
    }
}

class SelectTaskHorizonTest {
    @Test
    fun `returns an empty array when maxTotal is 0`() {
        val result = selectTaskHorizon(listOf(task("t1", dailyTimes(listOf("09:00")))), NOON_UTC, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `returns an empty array when maxTotal is negative`() {
        val result = selectTaskHorizon(listOf(task("t1", dailyTimes(listOf("09:00")))), NOON_UTC, -5)
        assertEquals(0, result.size)
    }

    @Test
    fun `returns an empty array when there are no reminders`() {
        assertEquals(0, selectTaskHorizon(emptyList(), NOON_UTC, 32).size)
    }

    @Test
    fun `selects a scheduled task, so that it notifies at its time`() {
        val errand = task("pharmacy", dailyTimes(listOf("14:00")))
        val result = selectTaskHorizon(listOf(errand), NOON_UTC, 1)
        assertEquals(listOf(instant("2026-07-26T14:00:00Z")), result.map { it.fireAt })
    }

    @Test
    fun `selects the single moment of a once task`() {
        val at = instant("2026-07-31T14:00:00Z")
        val result = selectTaskHorizon(listOf(task("insurance", onceTimes(at))), NOON_UTC, 4)
        assertEquals(listOf(at), result.map { it.fireAt })
    }

    @Test
    fun `selects nothing for a Backlog task, which has no moment to fire at`() {
        val result = selectTaskHorizon(listOf(task("someday", noneTimes())), NOON_UTC, 32)
        assertEquals(0, result.size)
    }

    @Test
    fun `skips inactive tasks entirely`() {
        val active = task("active", dailyTimes(listOf("09:00")))
        val inactive = task("inactive", dailyTimes(listOf("09:30")), false)
        val result = selectTaskHorizon(listOf(active, inactive), NOON_UTC, 32)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.reminderId == "active" })
    }

    @Test
    fun `caps the returned list at maxTotal even with many tasks`() {
        val tasks = List(10) { i -> task("t$i", dailyTimes(listOf("09:00"))) }
        assertEquals(32, selectTaskHorizon(tasks, NOON_UTC, 32).size)
    }

    @Test
    fun `returns occurrences in non-decreasing fireAt order`() {
        val tasks =
            listOf(
                task("a", dailyTimes(listOf("09:00", "21:00"))),
                task("b", dailyTimes(listOf("12:30", "18:00"))),
                task("c", weeklyTimes(listOf(0, 3), listOf("06:00", "15:00"))),
            )
        val times = selectTaskHorizon(tasks, NOON_UTC, 12).map { it.fireAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `breaks ties on fireAt by reminderId, independent of input order`() {
        val alpha = task("alpha", dailyTimes(listOf("15:00")))
        val bravo = task("bravo", dailyTimes(listOf("15:00")))
        val forwards = selectTaskHorizon(listOf(alpha, bravo), NOON_UTC, 2)
        val backwards = selectTaskHorizon(listOf(bravo, alpha), NOON_UTC, 2)
        assertEquals(listOf("alpha", "bravo"), forwards.map { it.reminderId })
        assertEquals(listOf("alpha", "bravo"), backwards.map { it.reminderId })
        assertEquals(forwards[0].fireAt, forwards[1].fireAt)
    }

    @Test
    fun `leaves a medication out of the task Horizon entirely`() {
        val dose = reminder("insulin", dailyTimes(listOf("13:00")))
        assertEquals(0, selectTaskHorizon(listOf(dose), NOON_UTC, 32).size)
    }
}

/**
 * ADR 0003's guarantee, stated as the thing a person would observe rather than
 * as the mechanism that delivers it: however many tasks exist, the doses that
 * were going to be armed are still armed.
 *
 * The medication Horizon here has room for exactly one alarm and the tasks are
 * all sooner than the dose, so any selector that ranked the two Kinds together
 * would arm a task and drop the insulin.
 */
class TheTwoHorizonsTogetherTest {
    private val doses =
        listOf(
            reminder("insulin", dailyTimes(listOf("21:00"))),
            reminder("statin", dailyTimes(listOf("22:00"))),
        )
    private val flood =
        List(200) { i -> task("t-${i.toString().padStart(3, '0')}", dailyTimes(listOf("13:00", "13:30"))) }

    @Test
    fun `arms every dose it would have armed, with the tasks present`() {
        val withoutTasks = selectMedicationHorizon(doses, NOON_UTC, 64)
        val withTasks = selectMedicationHorizon(flood + doses, NOON_UTC, 64)
        assertEquals(withoutTasks, withTasks)
    }

    @Test
    fun `keeps a dose armed even when one slot is all there is`() {
        val result = selectMedicationHorizon(flood + doses, NOON_UTC, 1)
        assertEquals(listOf("insulin"), result.map { it.reminderId })
    }

    @Test
    fun `shares no occurrence between the two Horizons`() {
        val all = flood + doses
        val medication = selectMedicationHorizon(all, NOON_UTC, 64)
        val tasks = selectTaskHorizon(all, NOON_UTC, 32)
        val medicationIds = medication.map { it.occurrenceId }.toSet()
        assertTrue(medication.isNotEmpty())
        assertTrue(tasks.isNotEmpty())
        assertFalse(tasks.any { it.occurrenceId in medicationIds })
    }

    @Test
    fun `selects the tasks on their own budget rather than on what the doses left over`() {
        val all = flood + doses
        assertEquals(32, selectTaskHorizon(all, NOON_UTC, 32).size)
    }
}
