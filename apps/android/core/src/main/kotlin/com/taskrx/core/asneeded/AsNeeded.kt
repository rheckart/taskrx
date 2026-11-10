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
    /**
     * The most recent moment a dose became allowed, or null if none ever has. [readyAt] can be in
     * the past once a dose has been allowed for a while, so this is what a one-hour window hangs
     * from; it is also the moment the daily limit releases, which [readyAt] no longer names then.
     */
    val openedAt: Long? = null,
) {
    /** Whether a dose is allowed at [now]. */
    fun isReady(now: Long): Boolean = readyAt == null || now >= readyAt
}

/** The next moment a dose is allowed, and what holds it back until then. */
private data class Wait(val readyAt: Long, val wait: AsNeededWait)

private fun waitAt(schedule: AsNeededTimes, takings: List<Long>, now: Long, last: Long): Wait {
    val inWindow = takings.filter { it > now - DAY_MS }.sorted()

    val gapReady = last + schedule.minGapMinutes * MINUTE_MS
    // At the limit, the (count - max + 1) oldest doses must leave the window; the newest of them
    // leaves 24 hours after it was taken. Below the limit, nothing but the gap holds a dose back.
    val limitReady =
        schedule.maxPer24h
            ?.takeIf { inWindow.size >= it }
            ?.let { inWindow[inWindow.size - it] + DAY_MS }

    val limitDecides = limitReady != null && limitReady > gapReady
    return if (limitDecides) Wait(limitReady, AsNeededWait.Limit) else Wait(gapReady, AsNeededWait.Gap)
}

/**
 * [takings] are the moments doses were taken (a Completed Instance's `completedAt`), in any
 * order. The window is the 24 hours ending at [now], and a taking exactly 24 hours old is out
 * of it: the dose that left it is the one that makes room.
 */
fun asNeededStatus(schedule: AsNeededTimes, takings: List<Long>, now: Long): AsNeededStatus {
    val last = takings.maxOrNull() ?: return AsNeededStatus(null, 0, null, null)
    val inWindow = takings.filter { it > now - DAY_MS }.sorted()
    val wait = waitAt(schedule, takings, now, last)
    return AsNeededStatus(
        lastTakenAt = last,
        takenIn24h = inWindow.size,
        readyAt = wait.readyAt,
        wait = wait.wait,
        openedAt = lastOpenedAt(schedule, takings, now, last),
    )
}

/**
 * A dose becomes allowed when the gap since the last taking runs out, or when a taking leaves the
 * 24-hour window and the count drops below the limit. Those are the only moments a wait can end,
 * so the latest one at or before [now] that changes the answer is when the current window opened.
 */
private fun lastOpenedAt(schedule: AsNeededTimes, takings: List<Long>, now: Long, last: Long): Long? {
    fun ready(at: Long): Boolean = at >= waitAt(schedule, takings, at, last).readyAt
    val gap = schedule.minGapMinutes * MINUTE_MS
    return takings
        .flatMap { listOf(it + gap, it + DAY_MS) }
        .distinct()
        .filter { it <= now }
        .sortedDescending()
        .firstOrNull { ready(it) && !ready(it - 1) }
}

/** How long the card stays after a dose becomes allowed: one hour. */
const val AS_NEEDED_WINDOW_MS = 60 * MINUTE_MS

/**
 * Whether the card for this medication belongs on Today at [now]. Only once a dose is allowed,
 * and for an hour after: a medication waiting hours for its next dose is not on the screen, and
 * one whose window has passed is not clutter. Nothing taken at all means no card; Log a dose is
 * how it starts, and that screen says when the next one is allowed.
 */
fun showsOnToday(status: AsNeededStatus, now: Long): Boolean {
    val opened = status.openedAt ?: return false
    return status.isReady(now) && now < opened + AS_NEEDED_WINDOW_MS
}

/** The moments [reminder]'s doses were taken, from its Instances. A skip, a miss or a snooze is not a taking. */
fun takings(instances: List<ReminderInstance>, reminderId: String): List<Long> =
    instances.filter { it.reminderId == reminderId && it.status == InstanceStatus.Completed }.mapNotNull { it.completedAt }

/** The schedule if [reminder] is taken as needed. */
fun asNeededOf(reminder: Reminder): AsNeededTimes? = reminder.schedule as? AsNeededTimes
