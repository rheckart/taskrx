package com.taskrx.core.catchup

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.TimelineRow
import java.time.ZoneId

/*
 * Catching up on doses nobody answered. Missed is the app's guess (CONTEXT.md), so a person
 * who forgot to log for a few days wants to put it right in one go, not row by row. This file
 * decides which rows are those and how they group; the screen and the Today banner both ask it.
 */

/**
 * How far back Catch up reaches: the sweep's own lookback, because nothing older was ever
 * recorded as Missed, so there is nothing older to answer.
 */
const val CATCH_UP_WINDOW_MS: Long = SWEEP_LOOKBACK_MS

/** One local day's unanswered rows, oldest moment first. */
data class CatchUpDay(val dayStart: Long, val rows: List<TimelineRow>)

/**
 * The rows that need an answer: Missed ones. A pending row past its Grace Period is not listed
 * until the sweep has recorded it; a snooze still running is a deferral the person chose.
 */
fun needsCatchUp(rows: List<TimelineRow>): List<TimelineRow> = rows.filter { it.status == InstanceStatus.Missed }

/** [rows] grouped by the local day they were scheduled on, newest day first. */
fun catchUpDays(rows: List<TimelineRow>, zone: ZoneId): List<CatchUpDay> =
    rows
        .groupBy { localDayBounds(it.scheduledTime, 0, zone).start }
        .map { (dayStart, day) -> CatchUpDay(dayStart, day.sortedBy { it.scheduledTime }) }
        .sortedByDescending { it.dayStart }

/** [rows] grouped by the moment they were due. A batch answer is one write per moment. */
fun catchUpSlots(rows: List<TimelineRow>): Map<Long, List<TimelineRow>> = rows.groupBy { it.scheduledTime }
