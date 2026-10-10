package com.taskrx.core.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.floor

/*
 * The bounds of a local calendar day, and the distance between two of them.
 *
 * One definition rather than two: the timeline picks a day to render and the
 * adherence period picks the day its window ends on, and both were computing
 * the same midnight-to-last-millisecond span — one of them inside a screen,
 * where the spec puts no rules and no test could reach it.
 *
 * ## Why it is not `now - offset * 86_400_000`
 *
 * A local day is 23 or 25 hours twice a year, and both callers are wrong in a
 * way nobody notices if they assume otherwise. The timeline would shift its
 * window an hour and drop a 23:00 row off the end of the day it belongs to, or
 * show a 00:30 one on two days running. The adherence period would begin an hour
 * off midnight, clipping the first day of its own window so that day's bucket
 * disagreed with every other one.
 *
 * Built from the calendar date instead, the boundary is midnight because it was
 * asked for midnight, whatever the offset did in between. `LocalDate` rolls the
 * month and the year over on its own, so no arithmetic is needed for them here.
 *
 * Pure: no clock. The caller supplies `now`; the zone defaults to the device's
 * (the TypeScript original read it from `Date`'s local fields) and is a
 * parameter so a test can pin a zone that has a clock change.
 */

/** Midnight to the last millisecond of a local calendar day. */
data class LocalDayBounds(val start: Long, val end: Long)

private val LAST_MILLISECOND_OF_DAY: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)

/**
 * The local calendar day [offsetDays] from the day holding [now] — `0` for that
 * day itself, negative for earlier, positive for later.
 *
 * `end` is the day's last millisecond rather than the next day's midnight, so
 * that a caller comparing with `<=` does not catch a moment scheduled at exactly
 * 00:00 tomorrow. Both of this repo's consumers compare inclusively.
 */
fun localDayBounds(
    now: Long,
    offsetDays: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDayBounds {
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(offsetDays.toLong())

    return LocalDayBounds(
        start = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        end = day.atTime(LAST_MILLISECOND_OF_DAY).atZone(zone).toInstant().toEpochMilli(),
    )
}

/** The length of a local calendar day on the 363 days it is one. */
private const val MS_PER_ORDINARY_DAY = 86_400_000.0

/**
 * How many local calendar days separate the day holding [fromMs] from the day
 * holding [toMs] — `0` when both fall on the same day, negative when [toMs] is
 * on an earlier one.
 *
 * Counted between midnights rather than between the two instants, so "how many
 * days until this runs out" answers with the day it lands on and not with how
 * far into today the question was asked: a supply exhausted at 21:00 tomorrow is
 * one day away whether it is now 08:00 or 20:00.
 *
 * The rounding is what makes the transition days behave. Two midnights 23 or 25
 * hours apart divide to 0.958 or 1.042 days, and truncating either way loses or
 * invents a day exactly once every spring and autumn — which on this surface is
 * a refill warning arriving a day late.
 */
fun localCalendarDaysBetween(
    fromMs: Long,
    toMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val from = localDayBounds(fromMs, 0, zone).start
    val to = localDayBounds(toMs, 0, zone).start

    // Rounds half up, as `Math.round` does.
    return floor((to - from) / MS_PER_ORDINARY_DAY + 0.5).toInt()
}
