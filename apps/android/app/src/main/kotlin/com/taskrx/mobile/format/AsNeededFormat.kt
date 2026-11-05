package com.taskrx.mobile.format

import com.taskrx.core.asneeded.AsNeededStatus
import com.taskrx.core.asneeded.AsNeededWait
import com.taskrx.core.domain.AsNeededTimes
import java.time.ZoneId
import java.util.Locale

private const val MINUTE_MS = 60_000L

/**
 * "3 h 12 min", "45 min": how long until the next dose is allowed. Rounded UP to the minute, so
 * the countdown never says it is ready a moment before it is, and a wait of seconds reads "1 min".
 */
fun formatCountdown(ms: Long): String {
    val minutes = maxOf(1L, (ms + MINUTE_MS - 1) / MINUTE_MS)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** What an as-needed medication's card says about when the next dose can be taken. */
data class AsNeededLines(val headline: String, val detail: String)

/**
 * The words on the card. [headline] is the answer ("Ready now", "Next dose in 3 h 12 min"); [detail]
 * says why and how much has been taken. When the daily limit is what is holding a dose back it says
 * so, because "next dose in 5 h" with a 4 hour gap would otherwise look like a mistake.
 */
fun asNeededLines(
    schedule: AsNeededTimes,
    status: AsNeededStatus,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): AsNeededLines {
    val taken =
        when {
            schedule.maxPer24h != null -> "${status.takenIn24h} of ${schedule.maxPer24h} doses in the last 24 hours"
            status.takenIn24h == 1 -> "1 dose in the last 24 hours"
            else -> "${status.takenIn24h} doses in the last 24 hours"
        }
    val last = status.lastTakenAt?.let { "Last dose ${formatTime(it, zone, locale)}" }
    val readyAt = status.readyAt
    return when {
        status.isReady(now) -> AsNeededLines("Ready now", listOfNotNull(last, taken.takeIf { status.takenIn24h > 0 }).joinToString(" · "))
        // `isReady` is false only with a readyAt in hand.
        status.wait == AsNeededWait.Limit ->
            AsNeededLines(
                "Daily limit reached · next dose in ${formatCountdown(readyAt!! - now)}",
                "Allowed again at ${formatTime(readyAt, zone, locale)} · $taken",
            )
        else ->
            AsNeededLines(
                "Next dose in ${formatCountdown(readyAt!! - now)}",
                listOfNotNull("Allowed at ${formatTime(readyAt, zone, locale)}", taken).joinToString(" · "),
            )
    }
}
