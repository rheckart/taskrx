package com.taskrx.mobile.format

import com.taskrx.core.adherence.AdherenceStats
import com.taskrx.core.adherence.DayAdherence
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryAndAdherenceFormatTest {
    private val zone = ZoneId.of("America/New_York")
    private val us = Locale.US

    private fun at(m: Int, d: Int, h: Int = 12, y: Int = 2026) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    // 2026-08-12 is a Wednesday; its week started Sunday 2026-08-09.
    private val now = at(8, 12)

    private fun label(ms: Long) = historyDayLabel(ms, now, zone, us)

    @Test
    fun `a status reads in the Kind's own word, and a snooze is never a miss`() {
        assertEquals("Taken", historyStatusLabel(InstanceStatus.Completed, ReminderKind.Medication))
        assertEquals("Done", historyStatusLabel(InstanceStatus.Completed, ReminderKind.Task))
        assertEquals("Snoozed", historyStatusLabel(InstanceStatus.Snoozed, ReminderKind.Medication))
        assertEquals("Skipped", historyStatusLabel(InstanceStatus.Skipped, ReminderKind.Task))
        assertEquals("Missed", historyStatusLabel(InstanceStatus.Missed, ReminderKind.Medication))
        assertEquals("Pending", historyStatusLabel(InstanceStatus.Pending, ReminderKind.Task))
        assertEquals("Taken", completedAtLabel(ReminderKind.Medication))
        assertEquals("Done", completedAtLabel(ReminderKind.Task))
    }

    @Test
    fun `today and yesterday are named, by calendar day and not by 24 hours`() {
        assertEquals("Today", label(at(8, 12, 0)))
        assertEquals("Today", label(at(8, 12, 23)))
        assertEquals("Yesterday", label(at(8, 11, 23)))
        assertEquals("Yesterday", label(at(8, 11, 0)))
    }

    @Test
    fun `earlier this week is This week, from Sunday`() {
        assertEquals("This week", label(at(8, 10)))
        assertEquals("This week", label(at(8, 9, 0)))
    }

    @Test
    fun `the day before this week began is dated`() {
        assertEquals(formatMonthDay(at(8, 8, 23), zone, us), label(at(8, 8, 23)))
        assertEquals("Aug 8", label(at(8, 8, 23)))
        assertEquals("Jan 2", label(at(1, 2)))
    }

    @Test
    fun `on a Sunday the week has only just begun`() {
        val sunday = at(8, 9)
        assertEquals("Today", historyDayLabel(sunday, sunday, zone, us))
        assertEquals("Yesterday", historyDayLabel(at(8, 8), sunday, zone, us))
        assertEquals("Aug 7", historyDayLabel(at(8, 7), sunday, zone, us))
    }

    @Test
    fun `on a Monday the week holds only the Sunday`() {
        val monday = at(8, 10)
        assertEquals("Yesterday", historyDayLabel(at(8, 9), monday, zone, us))
        assertEquals("Aug 8", historyDayLabel(at(8, 8), monday, zone, us))
    }

    @Test
    fun `on a Saturday the whole week back to Sunday is This week`() {
        val saturday = at(8, 15)
        assertEquals("This week", historyDayLabel(at(8, 9), saturday, zone, us))
        assertEquals("Aug 8", historyDayLabel(at(8, 8), saturday, zone, us))
    }

    @Test
    fun `grouping keeps the order headings first appear in`() {
        val items = listOf(at(8, 12, 9), at(8, 12, 8), at(8, 11, 20), at(8, 10, 9), at(8, 1, 9))

        val groups = groupByDay(items, now, zone, us) { it }

        assertEquals(listOf("Today", "Yesterday", "This week", "Aug 1"), groups.map { it.title })
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.items.size })
        assertEquals(emptyList(), groupByDay(emptyList<Long>(), now, zone, us) { it })
    }

    // ---- adherence ----

    private fun stats(completed: Int = 14, total: Int = 18, snoozed: Int = 0, percent: Double = 77.0) =
        AdherenceStats(percent, completed, missed = 0, skipped = 0, snoozed = snoozed, total = total, daily = emptyList())

    @Test
    fun `the count is said as taken, never as doses`() {
        assertEquals("14 of 18 taken", fractionLabel(stats()))
        assertEquals("Last 7 days", periodLabel(7))
        assertEquals("78% — 14 of 18 taken over the last 7 days", headlineDescription(stats(percent = 77.8), 7))
    }

    @Test
    fun `a near-perfect period is never read as perfect`() {
        assertEquals("99% — 249 of 250 taken over the last 7 days", headlineDescription(stats(249, 250, percent = 99.6), 7))
        assertEquals("100% — 250 of 250 taken over the last 7 days", headlineDescription(stats(250, 250, percent = 100.0), 7))
    }

    @Test
    fun `a snooze is explained only when there is one, singular and plural`() {
        assertNull(snoozeNote(stats(snoozed = 0)))
        assertEquals("1 snooze counts against the figure — a medication put off is one not yet taken.", snoozeNote(stats(snoozed = 1)))
        assertEquals("3 snoozes count against the figure — a medication put off is one not yet taken.", snoozeNote(stats(snoozed = 3)))
    }

    @Test
    fun `a day with nothing due is not a day of misses`() {
        val empty = DayAdherence("2026-08-04", completed = 0, total = 0, percent = 0.0)
        val bad = DayAdherence("2026-08-05", completed = 0, total = 3, percent = 0.0)
        val partial = DayAdherence("2026-08-06", completed = 2, total = 3, percent = 66.67)

        assertEquals(true, isEmptyDay(empty))
        assertEquals(false, isEmptyDay(bad))
        assertEquals("${formatDayKey("2026-08-04", zone, us)}: nothing recorded", dayDescription(empty, zone, us))
        assertEquals("${formatDayKey("2026-08-05", zone, us)}: 0%, 0 of 3 taken", dayDescription(bad, zone, us))
        assertEquals("${formatDayKey("2026-08-06", zone, us)}: 67%, 2 of 3 taken", dayDescription(partial, zone, us))
    }

    @Test
    fun `a bar's two parts sum to the whole and are never negative`() {
        assertEquals(0f to 100f, barWeights(DayAdherence("d", 0, 3, 0.0)))
        assertEquals(100f to 0f, barWeights(DayAdherence("d", 3, 3, 100.0)))
        assertEquals(25f to 75f, barWeights(DayAdherence("d", 1, 4, 25.0)))
        assertEquals(100f to 0f, barWeights(DayAdherence("d", 1, 1, 130.0)))
        assertEquals(0f to 100f, barWeights(DayAdherence("d", 1, 1, -5.0)))
    }
}
