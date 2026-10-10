package com.taskrx.core.schedule

/**
 * A single computed occurrence of a reminder, ported from
 * `TaskRx.Core/ScheduledOccurrence.cs`.
 *
 * It carries the display fields the notification payload needs, so the
 * scheduler does not have to re-read the reminder to build a notification.
 * `dosage` is null for `task` reminders.
 */
data class ScheduledOccurrence(
    val occurrenceId: String,
    val reminderId: String,
    val name: String,
    val dosage: String?,
    /** Unix ms. */
    val fireAt: Long,
)
