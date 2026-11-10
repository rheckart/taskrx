package com.taskrx.core.asneeded

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.asNeededTimes
import com.taskrx.core.domain.createReminderInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private val NOON = 1_800_000_000_000L - (1_800_000_000_000L % DAY) + 12 * HOUR

class AsNeededStatusTest {
    private val fourHours = asNeededTimes(240)
    private val fourHoursFiveADay = asNeededTimes(240, 5)

    @Test
    fun `with nothing taken a dose is allowed now`() {
        val status = asNeededStatus(fourHours, emptyList(), NOON)

        assertNull(status.lastTakenAt)
        assertNull(status.readyAt)
        assertEquals(0, status.takenIn24h)
        assertTrue(status.isReady(NOON))
    }

    @Test
    fun `the next dose is allowed one gap after the last`() {
        val status = asNeededStatus(fourHours, listOf(NOON - HOUR), NOON)

        assertEquals(NOON - HOUR, status.lastTakenAt)
        assertEquals(NOON + 3 * HOUR, status.readyAt)
        assertEquals(AsNeededWait.Gap, status.wait)
        assertFalse(status.isReady(NOON))
        assertFalse(status.isReady(NOON + 3 * HOUR - 1))
        assertTrue(status.isReady(NOON + 3 * HOUR))
    }

    @Test
    fun `the gap runs from the latest taking, whatever order they arrive in`() {
        val status = asNeededStatus(fourHours, listOf(NOON - 2 * HOUR, NOON - 9 * HOUR, NOON - HOUR), NOON)

        assertEquals(NOON - HOUR, status.lastTakenAt)
        assertEquals(NOON + 3 * HOUR, status.readyAt)
    }

    @Test
    fun `below the limit only the gap holds a dose back`() {
        val takings = listOf(NOON - 20 * HOUR, NOON - 16 * HOUR, NOON - 12 * HOUR, NOON - 8 * HOUR)
        val status = asNeededStatus(fourHoursFiveADay, takings, NOON)

        assertEquals(4, status.takenIn24h)
        assertEquals(NOON - 4 * HOUR, status.readyAt)
        assertTrue(status.isReady(NOON))
    }

    @Test
    fun `at the limit the dose waits for the oldest to leave the 24 hours, even when the gap is long past`() {
        // Five doses an hour apart, then nothing: the gap is satisfied long before the window frees up.
        val takings = listOf(NOON - 23 * HOUR, NOON - 22 * HOUR, NOON - 21 * HOUR, NOON - 20 * HOUR, NOON - 19 * HOUR)
        val status = asNeededStatus(fourHoursFiveADay, takings, NOON)

        assertEquals(5, status.takenIn24h)
        assertEquals(NOON + HOUR, status.readyAt)
        assertEquals(AsNeededWait.Limit, status.wait)
        assertFalse(status.isReady(NOON))
        assertTrue(status.isReady(NOON + HOUR))
    }

    @Test
    fun `the gap decides when it ends later than the limit does`() {
        // Five doses, the oldest leaving the window in 10 minutes, but the last was taken 10 minutes ago.
        val takings = listOf(NOON - 24 * HOUR + 10 * 60_000, NOON - 18 * HOUR, NOON - 12 * HOUR, NOON - 6 * HOUR, NOON - 10 * 60_000)
        val status = asNeededStatus(fourHoursFiveADay, takings, NOON)

        assertEquals(NOON - 10 * 60_000 + 4 * HOUR, status.readyAt)
        assertEquals(AsNeededWait.Gap, status.wait)
    }

    @Test
    fun `a dose exactly 24 hours old is out of the window`() {
        val takings = listOf(NOON - 24 * HOUR, NOON - 18 * HOUR, NOON - 12 * HOUR, NOON - 6 * HOUR, NOON - 5 * HOUR)
        val status = asNeededStatus(fourHoursFiveADay, takings, NOON)

        assertEquals(4, status.takenIn24h)
        assertTrue(status.isReady(NOON))
    }

    @Test
    fun `over the limit, enough must leave the window to get back under it`() {
        // Six taken (one logged by hand) against a limit of five: two must leave, so the wait is for the second oldest.
        val takings = listOf(NOON - 23 * HOUR, NOON - 22 * HOUR, NOON - 21 * HOUR, NOON - 20 * HOUR, NOON - 19 * HOUR, NOON - 18 * HOUR)
        val status = asNeededStatus(fourHoursFiveADay, takings, NOON)

        assertEquals(NOON + 2 * HOUR, status.readyAt)
    }

    @Test
    fun `without a limit any number of doses a day is only held back by the gap`() {
        val takings = (1..9).map { NOON - it * 2 * HOUR }
        val status = asNeededStatus(fourHours, takings, NOON)

        assertEquals(NOON - 2 * HOUR + 4 * HOUR, status.readyAt)
        assertEquals(AsNeededWait.Gap, status.wait)
    }
}

class ShowsOnTodayTest {
    private val fourHours = asNeededTimes(240)

    private fun shows(schedule: com.taskrx.core.domain.AsNeededTimes, takings: List<Long>, now: Long) = showsOnToday(asNeededStatus(schedule, takings, now), now)

    @Test
    fun `a medication with nothing taken has no card`() {
        assertFalse(shows(fourHours, emptyList(), NOON))
    }

    @Test
    fun `the card stays off the screen through the wait`() {
        val takings = listOf(NOON)

        assertFalse(shows(fourHours, takings, NOON + HOUR))
        assertFalse(shows(fourHours, takings, NOON + 4 * HOUR - 1))
    }

    @Test
    fun `the card appears when a dose is allowed and stays for an hour`() {
        val takings = listOf(NOON)

        assertTrue(shows(fourHours, takings, NOON + 4 * HOUR))
        assertTrue(shows(fourHours, takings, NOON + 5 * HOUR - 1))
        assertFalse(shows(fourHours, takings, NOON + 5 * HOUR))
    }

    @Test
    fun `a daily limit that holds the dose back keeps the card off until the window opens`() {
        val schedule = asNeededTimes(240, 5)
        val takings = listOf(NOON - 23 * HOUR, NOON - 22 * HOUR, NOON - 21 * HOUR, NOON - 20 * HOUR, NOON - 19 * HOUR)
        // The last dose was 19 hours ago, but the oldest does not leave the window until NOON + 1 h.
        assertFalse(shows(schedule, takings, NOON + HOUR - 1))
        assertTrue(shows(schedule, takings, NOON + HOUR))
        assertFalse(shows(schedule, takings, NOON + 2 * HOUR))
    }

    @Test
    fun `a longer gap still only shows the card once the dose is allowed`() {
        val eightHours = asNeededTimes(480)

        assertFalse(shows(eightHours, listOf(NOON), NOON + 7 * HOUR))
        assertTrue(shows(eightHours, listOf(NOON), NOON + 8 * HOUR))
    }
}

class TakingsTest {
    private fun instance(reminderId: String, status: InstanceStatus, completedAt: Long?) =
        createReminderInstance(
            id = "i-$reminderId-$completedAt-$status",
            reminderId = reminderId,
            userId = "u",
            reminderKind = ReminderKind.Medication,
            scheduledTime = completedAt ?: 0,
            createdAt = 0,
            updatedAt = 0,
            status = status,
            completedAt = completedAt,
        )

    @Test
    fun `only this medication's completed doses are takings`() {
        val instances =
            listOf(
                instance("a", InstanceStatus.Completed, 100),
                instance("a", InstanceStatus.Skipped, 200),
                instance("a", InstanceStatus.Missed, 300),
                instance("a", InstanceStatus.Pending, null),
                instance("b", InstanceStatus.Completed, 400),
            )

        assertEquals(listOf(100L), takings(instances, "a"))
    }

    @Test
    fun `a taking that was undone is no longer one`() {
        // Undo reopens a Completed Instance to Pending (ADR 0008).
        val undone = instance("a", InstanceStatus.Pending, null)

        assertEquals(emptyList(), takings(listOf(undone), "a"))
    }
}
