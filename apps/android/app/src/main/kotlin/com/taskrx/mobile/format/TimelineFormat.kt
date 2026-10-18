package com.taskrx.mobile.format

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.timeline.TimelineRow
import java.time.ZoneId
import java.util.Locale

/*
 * The words on the Today timeline. Mirrors statusLabel / kindLabel in
 * archive/expo-rn:apps/mobile/app/(app)/index.tsx.
 */

/**
 * "A dose and a task are distinguishable at a glance." The word is carried three ways
 * (a coloured edge, this word beside the name, and the accessibility description),
 * because a single signal always fails somebody.
 */
fun kindLabel(kind: ReminderKind): String =
    when (kind) {
        ReminderKind.Medication -> "Medication"
        ReminderKind.Task -> "Task"
    }

/**
 * The one place a Kind check on status is allowed. ADR 0001 made the stored vocabulary
 * Kind-neutral precisely so that this check happens once, in the UI layer: a medication
 * that is Completed is *shown* as taken, a task as done.
 *
 * A snooze is named with its new moment, because that moment is the whole content of a
 * snooze: an unqualified "Snoozed" would leave the person no way to see what they decided.
 */
fun statusLabel(
    row: TimelineRow,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    when (row.status) {
        InstanceStatus.Completed -> {
            val word = if (row.kind == ReminderKind.Medication) "Taken" else "Done"
            // When it was recorded, if known: the time can be corrected, so it has to be visible.
            row.completedAt?.let { "$word ${formatTime(it, zone, locale)}" } ?: word
        }
        InstanceStatus.Skipped -> "Skipped"
        InstanceStatus.Missed -> "Missed"
        InstanceStatus.Snoozed ->
            row.snoozedUntil?.let { "Snoozed until ${formatTime(it, zone, locale)}" } ?: "Snoozed"
        InstanceStatus.Pending -> ""
    }

/**
 * What a screen reader hears for a whole row: Kind, name, time and status as one phrase,
 * since the row is a single target and not four.
 */
fun rowDescription(
    row: TimelineRow,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val status = statusLabel(row, zone, locale)
    val time = formatTime(row.scheduledTime, zone, locale)
    return "${kindLabel(row.kind)}: ${row.name} at $time${if (status.isEmpty()) "" else ", $status"}"
}

/** The empty-day sentence under "Nothing scheduled". */
fun emptyDayBody(offset: Int, heading: String): String =
    if (offset == 0) "No medications or tasks due today." else "You had nothing due ${heading.lowercase()}."
