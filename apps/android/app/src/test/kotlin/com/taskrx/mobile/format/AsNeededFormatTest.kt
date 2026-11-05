package com.taskrx.mobile.format

import com.taskrx.core.asneeded.asNeededStatus
import com.taskrx.core.domain.asNeededTimes
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private val NOON = 1_800_000_000_000L - (1_800_000_000_000L % (24 * HOUR)) + 12 * HOUR

class CountdownTest {
    @Test
    fun `says hours and minutes, dropping the part that is zero`() {
        assertEquals("3 h 12 min", formatCountdown(3 * HOUR + 12 * MINUTE))
        assertEquals("2 h", formatCountdown(2 * HOUR))
        assertEquals("45 min", formatCountdown(45 * MINUTE))
    }

    @Test
    fun `rounds up so it never says ready early, and never says zero`() {
        assertEquals("1 min", formatCountdown(1))
        assertEquals("1 min", formatCountdown(MINUTE))
        assertEquals("2 min", formatCountdown(MINUTE + 1))
        assertEquals("1 h", formatCountdown(HOUR - 1 + 1))
        assertEquals("1 h 1 min", formatCountdown(HOUR + 1))
        assertEquals("1 min", formatCountdown(-5))
    }
}

@RunWith(RobolectricTestRunner::class)
class AsNeededLinesTest {
    private val utc = ZoneOffset.UTC
    private val us = Locale.US

    private fun at(ms: Long) = formatTime(ms, utc, us)

    private fun lines(schedule: com.taskrx.core.domain.AsNeededTimes, takings: List<Long>) =
        asNeededLines(schedule, asNeededStatus(schedule, takings, NOON), NOON, utc, us)

    @Test
    fun `before a first dose it is ready and says nothing else`() {
        val lines = lines(asNeededTimes(240), emptyList())

        assertEquals("Ready now", lines.headline)
        assertEquals("", lines.detail)
    }

    @Test
    fun `after a dose it counts down and says when the next is allowed`() {
        val lines = lines(asNeededTimes(240), listOf(NOON - HOUR))

        assertEquals("Next dose in 3 h", lines.headline)
        assertEquals("Allowed at ${at(NOON + 3 * HOUR)} · 1 dose in the last 24 hours", lines.detail)
    }

    @Test
    fun `with a limit it says how many of how many`() {
        val lines = lines(asNeededTimes(240, 5), listOf(NOON - 5 * HOUR, NOON - HOUR))

        assertEquals("Next dose in 3 h", lines.headline)
        assertEquals("Allowed at ${at(NOON + 3 * HOUR)} · 2 of 5 doses in the last 24 hours", lines.detail)
    }

    @Test
    fun `when the gap is over it is ready again, and says when the last dose was`() {
        val lines = lines(asNeededTimes(240), listOf(NOON - 5 * HOUR))

        assertEquals("Ready now", lines.headline)
        assertEquals("Last dose ${at(NOON - 5 * HOUR)} · 1 dose in the last 24 hours", lines.detail)
    }

    @Test
    fun `at the daily limit it says that is why, not just a time`() {
        val takings = listOf(NOON - 23 * HOUR, NOON - 22 * HOUR, NOON - 21 * HOUR, NOON - 20 * HOUR, NOON - 19 * HOUR)
        val lines = lines(asNeededTimes(240, 5), takings)

        assertEquals("Daily limit reached · next dose in 1 h", lines.headline)
        assertEquals("Allowed again at ${at(NOON + HOUR)} · 5 of 5 doses in the last 24 hours", lines.detail)
    }
}
