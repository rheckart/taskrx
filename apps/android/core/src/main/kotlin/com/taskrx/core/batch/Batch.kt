package com.taskrx.core.batch

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isAnswerable
import com.taskrx.core.timeline.TimelineRow

/*
 * Answering several doses due at the same moment in one go. Today's slot header and the
 * notification shade both ask this file, so "which doses count as a batch" has one answer.
 */

/** Two doses at one moment is already a batch: one tap beats two. */
const val MIN_BATCH_SIZE: Int = 2

/** What a batch button does to every dose in the batch. */
enum class BatchAction(val wire: String) {
    Take("take"),
    Snooze("snooze"),
    Skip("skip"),
    ;

    companion object {
        /** `null` for a word nobody sent, so a stray intent does nothing. */
        fun fromWire(wire: String?): BatchAction? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The moments on a day that hold a batch: doses still to answer, grouped by the instant
 * they were scheduled for, kept only where there are [MIN_BATCH_SIZE] or more.
 *
 * Medications only. A task is not a dose, and the shade can only know that two tasks are
 * due together by luck of their work requests, so "take all" never reaches one.
 * Answerable includes Missed and Snoozed: a person back at the phone at noon wants one
 * tap for the 9:00 doses they have not logged.
 */
fun batchSlots(rows: List<TimelineRow>): Map<Long, List<TimelineRow>> =
    rows
        .filter { it.kind == ReminderKind.Medication && isAnswerable(it.status) }
        .groupBy { it.scheduledTime }
        .filterValues { it.size >= MIN_BATCH_SIZE }

/** The id of the one notification that stands for every dose due at [scheduledTime]. */
fun batchNotificationId(scheduledTime: Long): String = "batch-$scheduledTime"

data class BatchText(val title: String, val body: String)

/** The words on a batch notification: how many, and which. */
fun batchText(names: List<String>): BatchText = BatchText("${names.size} doses due", names.sorted().joinToString(", "))
