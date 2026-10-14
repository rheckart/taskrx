package com.taskrx.core.notifications

import com.taskrx.core.domain.Reminder
import com.taskrx.core.schedule.nextOccurrences
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.ZoneId

/*
 * Doses that came due while the phone was off.
 *
 * An alarm does not survive a reboot, so a dose due while the phone was off never fired. On boot
 * the app re-arms what is still ahead (`desiredNotifications`), and a dose that fell in the gap
 * is behind it: no alarm, no notification, nothing until the person opens the app and finds the
 * day's timeline. For a medication that is the one reminder that must not vanish quietly.
 *
 * So after a boot, each such dose is posted at once. The rule:
 *
 *  - Medications only. A task's work request is kept by WorkManager across a reboot and runs
 *    when the phone is next up, so it is already late rather than lost (ADR 0007).
 *  - Active Reminders only, and only Occurrences inside the Grace Period (the same window the
 *    missed sweep uses, inclusive at its edge). An older one is the sweep's: it becomes Missed
 *    and shows in History and the Backlog, and a notification for it hours late would only
 *    nag about something already settled.
 *  - Not one the person already dealt with. [handled] holds an `(reminderId, scheduledTime)`
 *    pair for every Instance that exists, whatever its status: answered, skipped, or snoozed
 *    (a snooze has its own notification, re-armed separately).
 *  - The spec is the ordinary dose notification with its ordinary id, so a dose that had
 *    already fired before the reboot is replaced, not shown twice.
 *
 * Doses are returned oldest first.
 */
fun dueWhileAway(
    reminders: List<Reminder>,
    now: Long,
    handled: Set<Pair<String, Long>>,
    zone: ZoneId,
): List<NotificationSpec> {
    val windowStart = now - GRACE_PERIOD_MS
    return reminders
        .filter { it.active && entersMedicationHorizon(it) }
        // Strictly after the instant before the window, so a dose exactly one Grace Period old is in.
        .flatMap { reminder -> nextOccurrences(reminder, windowStart - 1, LOOKBACK_OCCURRENCES, zone) }
        .filter { it.fireAt <= now && (it.reminderId to it.fireAt) !in handled }
        .sortedWith(compareBy({ it.fireAt }, { it.reminderId }))
        .map(::medicationSpec)
}

/** Far more than a Grace Period holds of the densest Schedule there is (an hourly interval, or a day of times). */
private const val LOOKBACK_OCCURRENCES = 64
