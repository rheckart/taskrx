package com.taskrx.core.catchup

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.timeline.TimelineRow
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CatchUpTest {
    private val utc = ZoneOffset.UTC
    private val day = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private fun row(name: String, at: Long, status: InstanceStatus = InstanceStatus.Missed, kind: ReminderKind = ReminderKind.Medication) =
        TimelineRow("r-$name", null, name, null, kind, at, status, null, false, null)

    @Test
    fun `the window is the sweep's lookback`() {
        assertEquals(SWEEP_LOOKBACK_MS, CATCH_UP_WINDOW_MS)
    }

    @Test
    fun `only missed rows need catching up, of both kinds`() {
        val rows =
            InstanceStatus.entries.map { row(it.name, day, it) } + row("task", day, kind = ReminderKind.Task)

        assertEquals(listOf("Missed", "task"), needsCatchUp(rows).map { it.name })
    }

    @Test
    fun `days are newest first and rows within a day are oldest first`() {
        val rows =
            listOf(
                row("late", day + 20 * hour),
                row("old", day - 30 * hour),
                row("early", day + 8 * hour),
            )

        val days = catchUpDays(rows, utc)

        assertEquals(listOf(day, day - 2 * 24 * hour), days.map { it.dayStart })
        assertEquals(listOf("early", "late"), days[0].rows.map { it.name })
        assertEquals(listOf("old"), days[1].rows.map { it.name })
    }

    @Test
    fun `no rows make no days`() {
        assertEquals(emptyList(), catchUpDays(emptyList(), utc))
    }

    @Test
    fun `slots group rows by their moment`() {
        val slots = catchUpSlots(listOf(row("A", day), row("B", day), row("C", day + hour)))

        assertEquals(setOf(day, day + hour), slots.keys)
        assertEquals(listOf("A", "B"), slots.getValue(day).map { it.name })
    }
}
