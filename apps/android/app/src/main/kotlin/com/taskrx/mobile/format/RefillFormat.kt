package com.taskrx.mobile.format

import com.taskrx.core.backlog.isBacklog
import com.taskrx.core.domain.Reminder
import com.taskrx.core.refill.RefillProjection
import com.taskrx.core.refill.UnprojectableReason
import java.time.ZoneId
import java.util.Locale

/*
 * How the Supplies surface says what a projection means. Mirrors
 * archive/expo-rn:apps/mobile/src/format/refill.ts. Every line is a judgement about what a number
 * means, and on this surface the wrong sentence over the right number is the whole
 * failure mode: "Plenty left" over an empty bottle, "0 days" over a supply nobody
 * recorded.
 *
 * The vocabulary is CONTEXT.md's. A scheduled taking is an Occurrence, and the glossary
 * says to avoid "dose" for it, so a supply is "enough for 12 more", never "12 more doses".
 */

private fun notTracked(reason: UnprojectableReason): String =
    when (reason) {
        UnprojectableReason.NoSupply -> "Add how many you have to see how long it lasts."
        UnprojectableReason.NoDoseSize -> "Add how many you take at a time to see how long it lasts."
        UnprojectableReason.Stopped -> "This medication is stopped, so nothing is being used up."
        UnprojectableReason.NotAMedication -> "Only medications have a supply."
    }

/**
 * The headline: how long there is, in the fewest words that are true. A missing
 * `daysRemaining` has two causes that must not share a sentence: a supply that outlasts
 * the walk really is plenty, while a Reminder with no times set has no run-out date
 * because nothing is being taken.
 */
fun refillHeadline(medication: Reminder, projection: RefillProjection): String {
    if (projection !is RefillProjection.RefillOutlook) return "Supply not tracked"

    val days = projection.daysRemaining ?: return if (isBacklog(medication)) "No times set" else "Plenty left"
    return when {
        days <= 0 -> "Runs out today"
        days == 1 -> "1 day left"
        else -> "$days days left"
    }
}

private fun amountPhrase(occurrences: Long): String =
    when (occurrences) {
        0L -> "nothing left"
        1L -> "enough for 1 more"
        else -> "enough for $occurrences more"
    }

/**
 * The line under the headline, where the working is shown rather than asserted. An empty
 * bottle has not "run out on" a future date; that date is when the next one is wanted.
 */
fun refillDetail(
    medication: Reminder,
    projection: RefillProjection,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (projection is RefillProjection.Unprojectable) return notTracked(projection.reason)
    projection as RefillProjection.RefillOutlook

    val amount = amountPhrase(projection.occurrencesRemaining)
    val runsOutAt = projection.runsOutAt
    if (runsOutAt != null) {
        val day = formatDay(runsOutAt, zone, locale)
        return if (projection.occurrencesRemaining == 0L) "$amount — the next is due $day" else "$amount, runs out $day"
    }

    return if (isBacklog(medication)) {
        "$amount, and no times set — so none are being used up"
    } else {
        "$amount, further off than this can project"
    }
}

/** The badge, or nothing: a standing "you're fine" on every row hides the two that are not. */
enum class RefillUrgency { None, Reorder, Out }

fun refillUrgency(projection: RefillProjection): RefillUrgency {
    if (projection !is RefillProjection.RefillOutlook || !projection.needsRefill) return RefillUrgency.None
    return if (projection.occurrencesRemaining == 0L) RefillUrgency.Out else RefillUrgency.Reorder
}
