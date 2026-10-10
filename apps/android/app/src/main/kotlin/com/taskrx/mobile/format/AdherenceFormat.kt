package com.taskrx.mobile.format

import com.taskrx.core.adherence.AdherenceStats
import com.taskrx.core.adherence.DayAdherence
import java.time.ZoneId
import java.util.Locale

/*
 * The words on the Adherence screen. Mirrors apps/mobile/app/(app)/adherence.tsx.
 *
 * Every figure is :core's (`computeAdherence`); what is here is how a figure is said. The
 * sentences are the content: a day with nothing due must not read as a day of misses, and
 * a count must not call a recorded taking a "dose" (CONTEXT.md).
 */

fun periodLabel(days: Int): String = "Last $days days"

/**
 * "14 of 18 taken", not "14 of 18 doses": CONTEXT.md reserves Dose for a quantity of a
 * drug, and a count of Instances is not one. The verb carries it without the noun.
 */
fun fractionLabel(stats: AdherenceStats): String = "${stats.completed} of ${stats.total} taken"

/** What a screen reader hears for the headline, in place of the bare percentage. */
fun headlineDescription(stats: AdherenceStats, days: Int): String =
    "${formatPercent(stats.percent)} — ${stats.completed} of ${stats.total} taken over the last $days days"

/**
 * Said only when there is a snooze to explain. A snoozed medication lowers the figure the
 * way a miss does, but it is not reported as one, so it gets its own count and its own
 * sentence for what it did to the number.
 */
fun snoozeNote(stats: AdherenceStats): String? =
    when (stats.snoozed) {
        0 -> null
        1 -> "1 snooze counts against the figure — a medication put off is one not yet taken."
        else -> "${stats.snoozed} snoozes count against the figure — a medication put off is one not yet taken."
    }

/**
 * A day with nothing recorded is told apart from a 0% day by `total`, never by `percent`
 * (an empty day's percent is 0 only because 0/0 has to be some number).
 */
fun isEmptyDay(day: DayAdherence): Boolean = day.total == 0

fun dayDescription(day: DayAdherence, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val name = formatDayKey(day.date, zone, locale)
    return if (isEmptyDay(day)) {
        "$name: nothing recorded"
    } else {
        "$name: ${formatPercent(day.percent)}, ${day.completed} of ${day.total} taken"
    }
}

/** The weights of a day's bar: how much is filled and how much is not, summing to 100. */
fun barWeights(day: DayAdherence): Pair<Float, Float> {
    val filled = day.percent.coerceIn(0.0, 100.0).toFloat()
    return filled to (100f - filled)
}
