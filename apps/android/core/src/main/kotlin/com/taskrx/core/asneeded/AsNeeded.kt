package com.taskrx.core.asneeded

import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance

/*
 * When the next dose of an as-needed medication is allowed (ADR 0010).
 *
 * An as-needed medication has no Occurrences, so there is no moment that was "due" and nothing
 * to miss. What it has is a rule about spacing: at least `minGapMinutes` between takings, and
 * when set, no more than `maxPer24h` in any 24 hours. Both are read from what was TAKEN, so
 * the answer is the same whichever door a dose came through (the card on Today, the Add Dose
 * form, the notification's Take button).
 *
 * Pure: the caller supplies the takings and `now`.
 */

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24 * 60 * MINUTE_MS

/** What decides the wait. Only meaningful while [AsNeededStatus.readyAt] is still ahead. */
enum class AsNeededWait {
    /** Too soon after the last dose. */
    Gap,

    /** The most doses in 24 hours have been taken; the oldest has to leave the window. */
    Limit,
}

data class AsNeededStatus(
    /** When the last dose was taken, or null if none ever was. */
    val lastTakenAt: Long?,
    /** Doses taken in the 24 hours up to now. */
    val takenIn24h: Int,
    /** The first moment another dose is allowed; null when nothing holds it back (none taken yet). */
    val readyAt: Long?,
    val wait: AsNeededWait?,
) {
    /** Whether a dose is allowed at [now]. */
    fun isReady(now: Long): Boolean = readyAt == null || now >= readyAt
}

/**
 * [takings] are the moments doses were taken (a Completed Instance's `completedAt`), in any
 * order. The window is the 24 hours ending at [now], and a taking exactly 24 hours old is out
 * of it: the dose that left it is the one that makes room.
 */
fun asNeededStatus(schedule: AsNeededTimes, takings: List<Long>, now: Long): AsNeededStatus {
    val last = takings.maxOrNull() ?: return AsNeededStatus(null, 0, null, null)
    val inWindow = takings.filter { it > now - DAY_MS }.sorted()

    val gapReady = last + schedule.minGapMinutes * MINUTE_MS
    // At the limit, the (count - max + 1) oldest doses must leave the window; the newest of them
    // leaves 24 hours after it was taken. Below the limit, nothing but the gap holds a dose back.
    val limitReady =
        schedule.maxPer24h
            ?.takeIf { inWindow.size >= it }
            ?.let { inWindow[inWindow.size - it] + DAY_MS }

    val limitDecides = limitReady != null && limitReady > gapReady
    return AsNeededStatus(
        lastTakenAt = last,
        takenIn24h = inWindow.size,
        readyAt = if (limitDecides) limitReady else gapReady,
        wait = if (limitDecides) AsNeededWait.Limit else AsNeededWait.Gap,
    )
}

/** The moments [reminder]'s doses were taken, from its Instances. A skip, a miss or a snooze is not a taking. */
fun takings(instances: List<ReminderInstance>, reminderId: String): List<Long> =
    instances.filter { it.reminderId == reminderId && it.status == InstanceStatus.Completed }.mapNotNull { it.completedAt }

/** The schedule if [reminder] is taken as needed. */
fun asNeededOf(reminder: Reminder): AsNeededTimes? = reminder.schedule as? AsNeededTimes
