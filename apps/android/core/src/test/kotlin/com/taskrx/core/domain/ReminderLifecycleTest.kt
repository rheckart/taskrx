package com.taskrx.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NOW = 1_780_000_000_000L
private const val DAY = 86_400_000L

private fun reminder(
    kind: ReminderKind = ReminderKind.Medication,
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
    active: Boolean = true,
    pauses: List<PauseWindow> = emptyList(),
): Reminder =
    createReminder("r1", "u", kind, "Metformin", 0, 0, schedule = schedule, active = active, pauses = pauses)

class ReminderLifecycleTest {
    @Test
    fun `a stopped Reminder is stopped, whatever windows it still carries`() {
        assertEquals(ReminderPhase.Stopped, reminderPhase(reminder(active = false), NOW))
        assertEquals(
            ReminderPhase.Stopped,
            reminderPhase(reminder(active = false, pauses = listOf(PauseWindow(NOW, null))), NOW),
        )
    }

    @Test
    fun `a running window makes an active Reminder paused until it ends`() {
        val paused = reminder(pauses = listOf(PauseWindow(NOW - DAY, NOW + DAY)))

        assertIs<ReminderPhase.Paused>(reminderPhase(paused, NOW))
        assertIs<ReminderPhase.Paused>(reminderPhase(paused, NOW + DAY - 1))
        assertEquals(ReminderPhase.Active, reminderPhase(paused, NOW + DAY))
    }

    @Test
    fun `a window that has not started is a pause waiting to begin`() {
        val upcoming = reminder(pauses = listOf(PauseWindow(NOW + DAY, null)))

        assertIs<ReminderPhase.Paused>(reminderPhase(upcoming, NOW))
    }

    @Test
    fun `a finished window is not a phase any more, though the row still keeps it`() {
        val finished = reminder(pauses = listOf(PauseWindow(NOW - 2 * DAY, NOW - DAY)))

        assertEquals(ReminderPhase.Active, reminderPhase(finished, NOW))
        assertEquals(1, finished.pauses.size)
    }

    @Test
    fun `only a live scheduled medication can be paused`() {
        assertTrue(canPause(reminder()))
        assertFalse(canPause(reminder(active = false)))
        assertFalse(canPause(reminder(kind = ReminderKind.Task)))
        assertFalse(canPause(reminder(schedule = asNeededTimes(4 * 60))))
        // A Backlog medication has no moments to hold back, but pausing it is still allowed:
        // that is the same treatment the list has always given it, and it changes nothing.
        assertTrue(canPause(reminder(schedule = noneTimes())))
    }

    @Test
    fun `an active Reminder offers Pause and Stop`() {
        assertEquals(setOf(ReminderAction.Pause, ReminderAction.Stop), reminderActions(reminder(), ReminderPhase.Active))
    }

    @Test
    fun `an active Reminder with nothing to pause offers only Stop`() {
        assertEquals(
            setOf(ReminderAction.Stop),
            reminderActions(reminder(kind = ReminderKind.Task), ReminderPhase.Active),
        )
    }

    @Test
    fun `a paused Reminder offers Resume and Stop`() {
        assertEquals(
            setOf(ReminderAction.Resume, ReminderAction.Stop),
            reminderActions(reminder(), ReminderPhase.Paused(PauseWindow(NOW, null))),
        )
    }

    @Test
    fun `a stopped Reminder offers nothing`() {
        assertEquals(emptySet(), reminderActions(reminder(active = false), ReminderPhase.Stopped))
    }

    @Test
    fun `the actions can be read from the phase the Reminder is in now`() {
        val paused = reminder(pauses = listOf(PauseWindow(NOW, null)))

        assertEquals(setOf(ReminderAction.Resume, ReminderAction.Stop), reminderActions(paused, NOW))
        assertEquals(setOf(ReminderAction.Pause, ReminderAction.Stop), reminderActions(reminder(), NOW))
    }
}