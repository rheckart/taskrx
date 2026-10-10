package com.taskrx.core.refill

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.schedule.nextOccurrences
import com.taskrx.core.time.localCalendarDaysBetween
import java.time.ZoneId
import kotlin.math.floor

/*
 * The refill projection: when a finite supply runs out, and whether that is
 * close enough to act on.
 *
 * `planner.ts` was ported from `RefillPlanner.cs` and answers only the second
 * half of that, with a formula this one deliberately does not carry:
 * `pillsRemaining <= dosesInLookaheadWindow * 3`. Two things are wrong with it
 * as the whole rule, and issue #17 names both.
 *
 * - **It compares pills to doses.** `pillsPerDose` is checked for being
 *   positive and then never used, so a medication taken two at a time is
 *   projected to last exactly as long as one taken singly. The acceptance
 *   criterion is explicit: the projection must account for how many are taken
 *   per Occurrence, not just the number of Occurrences.
 * - **It has no notion of when the supply actually ends.** The lookahead is a
 *   dose count handed in by the caller, so the C# never has a date to show, and
 *   "how many days are left" - the other half of the ticket - cannot be
 *   answered from it at all.
 *
 * So the threshold is replaced rather than wrapped: the supply is converted to
 * doses, the doses are walked against the Reminder's real Schedule to find the
 * moment the supply first cannot cover, and the raise happens when that moment
 * is inside the warning window the Reminder carries. One rule, and [planRefill]
 * now reads its verdict from here rather than keeping a second one that could
 * disagree with what the screen is showing.
 *
 * Medication-only by nature: a task has no supply. That is stated once, here.
 *
 * Pure: the caller supplies `now`. No clock, no ids, no database. The zone
 * defaults to the device's and is a parameter so a test can pin one that has a
 * clock change.
 */

/**
 * The warning window used when the Reminder names none.
 *
 * Seven days, which is what it takes to reach a prescriber who is not open at
 * the weekend and a pharmacy that has to order something in. The alternative -
 * no warning at all unless someone typed "refill reminder 7 days" into the entry
 * box - makes the feature reachable only by people who already know it exists,
 * and the failure mode of the default is a reminder a week early rather than a
 * medication that ran out.
 */
const val DEFAULT_REFILL_REMINDER_DAYS: Int = 7

/**
 * How many Occurrences ahead the projection will walk before it answers "longer
 * than I can see".
 *
 * A cap is needed because `interval` schedules have no horizon of their own and
 * will return exactly as many Occurrences as they are asked for - a year's
 * supply at one dose an hour is a projection nobody reads and 8,760 objects
 * nobody frees. The day-based variants stop themselves at 60 and 56 days, so in
 * practice this only binds on `interval`, and only for supplies far past any
 * warning window.
 */
const val REFILL_PROJECTION_MAX_OCCURRENCES: Int = 512

/**
 * Why a Reminder has no refill projection. Each one is a different sentence on
 * the surface, which is the point of distinguishing them: "this is a task" and
 * "you have not said how many you take at a time" are not the same news, and
 * neither is a number.
 */
enum class UnprojectableReason(val wire: String) {
    NotAMedication("not-a-medication"),
    Stopped("stopped"),
    NoSupply("no-supply"),
    NoDoseSize("no-dose-size"),
}

sealed interface RefillProjection {
    /** There was nothing to project from; never a zero. */
    data class Unprojectable(val reason: UnprojectableReason) : RefillProjection

    data class RefillOutlook(
        /**
         * How many further Occurrences the supply covers - `pillsRemaining` divided
         * by `pillsPerDose`, rounded down, because half a dose is not one.
         */
        val occurrencesRemaining: Long,
        /**
         * The moment (Unix ms) of the first Occurrence the supply cannot cover, or
         * null when no such Occurrence is within reach - either the supply outlasts
         * the schedule horizon, or the Reminder has no Schedule and so consumes
         * nothing.
         */
        val runsOutAt: Long?,
        /**
         * Local calendar days from today to the day of [runsOutAt]; `0` means
         * today. Null exactly when [runsOutAt] is.
         */
        val daysRemaining: Int?,
        /** The warning window this projection was judged against, in days. */
        val warningWindowDays: Int,
        /** Whether the supply runs out inside that window. */
        val needsRefill: Boolean,
    ) : RefillProjection
}

/**
 * Project [medication]'s remaining supply forward from [now].
 *
 * Returns [RefillProjection.Unprojectable] - never a zero - when there is
 * nothing to project from. A medication whose supply nobody recorded is not a
 * medication with none left, and rendering the second as the first is the
 * difference between silence and an alarm.
 */
fun projectRefill(
    medication: Reminder,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): RefillProjection {
    if (medication.kind != ReminderKind.Medication) {
        return RefillProjection.Unprojectable(UnprojectableReason.NotAMedication)
    }
    // A stopped medication is not being consumed, so its supply is not running
    // out and nothing should be raised about it. `nextOccurrences` does not know
    // that - it reads the Schedule and not `active` - so the guard is here, the
    // same place `projectTimeline` keeps its own.
    if (!medication.active) {
        return RefillProjection.Unprojectable(UnprojectableReason.Stopped)
    }
    val pillsRemaining = medication.pillsRemaining
        ?: return RefillProjection.Unprojectable(UnprojectableReason.NoSupply)
    val pillsPerDose = medication.pillsPerDose
    if (pillsPerDose == null || pillsPerDose <= 0) {
        return RefillProjection.Unprojectable(UnprojectableReason.NoDoseSize)
    }

    // The whole of AC "accounts for how many are taken per Occurrence": thirty
    // pills taken two at a time is fifteen Occurrences, not thirty.
    val occurrencesRemaining = floor(pillsRemaining / pillsPerDose).toLong().coerceAtLeast(0)
    val warningWindowDays = medication.refillReminderDays ?: DEFAULT_REFILL_REMINDER_DAYS

    // One past the doses the supply covers: that Occurrence is the first one
    // there is nothing left for, which is the moment being asked about.
    // Capped before the +1: a saturated Long (a huge supply over a tiny dose) must not wrap.
    val wanted = minOf(occurrencesRemaining, REFILL_PROJECTION_MAX_OCCURRENCES.toLong()).toInt() + 1
    val occurrences = nextOccurrences(medication, now, wanted, zone)
    val runsOutAt =
        if (occurrencesRemaining < occurrences.size) occurrences[occurrencesRemaining.toInt()].fireAt else null

    val daysRemaining = runsOutAt?.let { localCalendarDaysBetween(now, it, zone) }

    return RefillProjection.RefillOutlook(
        occurrencesRemaining = occurrencesRemaining,
        runsOutAt = runsOutAt,
        daysRemaining = daysRemaining,
        warningWindowDays = warningWindowDays,
        // Null days is a supply that outlasts what the schedule can be walked
        // for, which is the opposite of one about to run out.
        needsRefill = daysRemaining != null && daysRemaining <= warningWindowDays,
    )
}
