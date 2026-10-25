package com.taskrx.core.adherence

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The adherence calculator, ported from `AdherenceCalculator.Compute` in
 * `TaskRx.Core/AdherenceCalculator.cs` in the retired .NET app.
 *
 * ## Adherence is medication-only
 *
 * A task never contributes, in any status. CONTEXT.md defines Adherence as "the
 * proportion of a person's medication commitments they actually met" and says
 * so outright: "a medication concept only — tasks never contribute to it." The
 * spec's user story 37 gives the reason from the person's side — "I want my
 * tasks excluded from that figure, so that a productive week cannot disguise a
 * bad medication week" — and a completed task counted in both halves of
 * `completed / total` does exactly that disguising, pulling the figure toward
 * 100% for a week in which no dose was taken at all.
 *
 * This was latent until the fused timeline: `reminderKind` has been on every
 * Instance since the unified tables landed, but no task could be created, so
 * every row this saw was a medication one.
 *
 * ## Divergence from the C#: the Snoozed denominator
 *
 * Kind-neutral in the C# because the C# has one Kind. What follows is now the
 * medication half of the rule; the task half is "excluded", above.
 *
 * `AdherenceCalculator.cs:7-15` filters out `Pending`, then counts only
 * `Taken`/`Missed`/`Skipped` toward `total`. A `Snoozed` instance therefore
 * falls into neither the numerator nor the denominator and vanishes —
 * snoozing a dose silently inflates the adherence score. `AdherenceCalculator.cs:24`
 * compounds this: the daily grouping's `dayTotal = g.Count()` DOES include
 * `Snoozed`, so the overall percentage and the daily percentages end up
 * computed against different denominators.
 *
 * The README states the intent directly: "a snoozed medication affects the
 * streak; a snoozed task just reschedules." This port implements that
 * explicitly, using `reminderKind` to split the treatment:
 *
 * - `snoozed` + `medication` -> counts in the DENOMINATOR only. It lowers the
 *   percentage the same way a miss would, without being reported as one.
 * - `snoozed` + `task` -> excluded entirely, from both numerator and
 *   denominator — same treatment as `pending`. Snoozing a task just
 *   reschedules it.
 * - `pending` -> excluded entirely regardless of kind, as in the C#.
 *
 * The daily buckets use this same denominator rule as the overall total, so
 * the two figures can never disagree the way the C#'s did. `snoozed` is
 * surfaced as its own field on [AdherenceStats] — counting only the
 * medication snoozes that actually affected `total` — so the treatment is
 * visible in the output rather than an implicit side effect of the
 * completed/missed/skipped math.
 *
 * Local-calendar-date grouping follows the pattern established in
 * `schedule/Occurrences.kt`: built from the zone's calendar date, not the UTC
 * date and not epoch-ms division (UTC, and DST-unsafe).
 *
 * Pure function: no DB calls, no clock, no globals.
 */

/**
 * `TaskRx.Core.DayAdherence`. [date] is a `DateOnly` day key, `"YYYY-MM-DD"`.
 *
 * Every local calendar day in the window gets one of these, including days on
 * which nothing was recorded — see [computeAdherence]. Such a day carries
 * `total: 0`, and **`total` is the field that identifies it**. Its [percent] is
 * `0` only because `0/0` has to be some number; read as a percentage it would
 * say every dose that day was missed, which is the opposite of what an empty day
 * means. Test `total`, never `percent`.
 */
data class DayAdherence(
    val date: String,
    val completed: Int,
    val total: Int,
    val percent: Double,
)

/**
 * `TaskRx.Core.AdherenceStats`, plus [snoozed] — see the header for why that
 * field exists here but not on the C# record.
 *
 * [completed] is what the C# record calls `Taken`. Renamed with the status it
 * counts (ADR 0001): the glossary lists "Taken" under _Avoid_ for the Completed
 * concept, and Adherence has no carve-out from that. Adherence being a
 * medication-only concept makes the medication word defensible, not correct.
 */
data class AdherenceStats(
    val percent: Double,
    val completed: Int,
    val missed: Int,
    val skipped: Int,
    val snoozed: Int,
    val total: Int,
    val daily: List<DayAdherence>,
)

/**
 * Whether [instance] counts toward the denominator, per the two rules
 * documented above. `AdherenceCalculator.cs:8`'s
 * `i.Status != InstanceStatus.Pending`, refined by Kind.
 *
 * The Kind test comes first and covers every status, so a completed task cannot
 * inflate the figure and a missed one cannot deflate it. That matters for
 * `missed` in particular: nobody records a missed task deliberately — the sweep
 * mints those on its own for any Occurrence that ran out of time, so without
 * this a forgotten task would read as a medication failure.
 */
private fun countsTowardTotal(instance: ReminderInstance): Boolean {
    if (instance.reminderKind == ReminderKind.Task) return false
    if (instance.status == InstanceStatus.Pending) return false
    return true
}

/** The local calendar date [ms] falls on. */
private fun localDate(ms: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** `completed / total * 100`, or `0` when [total] is `0` — `AdherenceCalculator.cs:15,25`. */
private fun percentOf(completed: Int, total: Int): Double =
    if (total > 0) completed.toDouble() / total * 100 else 0.0

/**
 * Every local calendar day the window touches, from the day holding [fromMs]
 * through the day holding [toMs], ascending. Empty when the window is inverted.
 *
 * Stepped by calendar day rather than by adding 86_400_000, for the reason
 * `schedule/Occurrences.kt` gives: a fixed 24-hour step walks off midnight at a
 * daylight-saving transition and then emits either a duplicate day key or a
 * missing one for the rest of the period. The calendar increment lands on the
 * next local midnight whatever the offset did.
 *
 * The result is already ascending, which is why nothing below sorts: the days
 * are enumerated in order rather than discovered from the data and put back in
 * order afterwards.
 */
private fun eachLocalDay(fromMs: Long, toMs: Long, zone: ZoneId): List<LocalDate> {
    var cursor = localDate(fromMs, zone)

    val days = ArrayList<LocalDate>()
    while (cursor.atStartOfDay(zone).toInstant().toEpochMilli() <= toMs) {
        days.add(cursor)
        cursor = cursor.plusDays(1)
    }
    return days
}

private class DayBucket(var completed: Int = 0, var total: Int = 0)

/**
 * Adherence over `[fromMs, toMs]` (both ends inclusive, matching the C#'s
 * `scheduledTime >= fromUtc && scheduledTime <= toUtc`), grouped into local
 * calendar days ordered chronologically ascending.
 */
fun computeAdherence(
    instances: List<ReminderInstance>,
    fromMs: Long,
    toMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): AdherenceStats {
    val counted =
        instances.filter {
            it.scheduledTime >= fromMs && it.scheduledTime <= toMs && countsTowardTotal(it)
        }

    val completed = counted.count { it.status == InstanceStatus.Completed }
    val missed = counted.count { it.status == InstanceStatus.Missed }
    val skipped = counted.count { it.status == InstanceStatus.Skipped }
    val snoozed = counted.count { it.status == InstanceStatus.Snoozed }
    val total = counted.size
    val percent = percentOf(completed, total)

    val buckets = HashMap<LocalDate, DayBucket>()
    for (instance in counted) {
        val bucket = buckets.getOrPut(localDate(instance.scheduledTime, zone)) { DayBucket() }
        bucket.total += 1
        if (instance.status == InstanceStatus.Completed) bucket.completed += 1
    }

    // Driven by the window's days rather than by the days the data happens to
    // mention, so a day nobody recorded anything on is a row with `total: 0`
    // instead of a hole the reader cannot see. Every counted instance falls inside
    // the window, so its day is always one of these — no bucket is orphaned.
    val daily =
        eachLocalDay(fromMs, toMs, zone).map { day ->
            val bucket = buckets[day] ?: DayBucket()
            DayAdherence(
                // ISO-8601 `YYYY-MM-DD`, the `DateOnly` day key.
                date = day.toString(),
                completed = bucket.completed,
                total = bucket.total,
                percent = percentOf(bucket.completed, bucket.total),
            )
        }

    return AdherenceStats(percent, completed, missed, skipped, snoozed, total, daily)
}
