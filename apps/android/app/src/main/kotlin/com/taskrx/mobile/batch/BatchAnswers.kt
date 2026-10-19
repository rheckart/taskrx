package com.taskrx.mobile.batch

import android.util.Log
import com.taskrx.core.batch.BatchAction
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.format.SNOOZE_MINUTES
import kotlinx.coroutines.CancellationException

/** How a batch went: how many doses it changed, and how many it could not. Already-answered doses are neither. */
data class BatchResult(val changed: Int, val failed: Int)

/**
 * Takes, skips or snoozes every dose due at one moment, from the Today screen or from the
 * notification shade. Neither caller decides anything: which doses form a batch is :core's
 * `batchSlots`; this is only the writes.
 *
 * Three things differ from the answer screen, each on purpose:
 *  - A dose that already has an answer is left alone. The answer screen mints a fresh
 *    pending Instance for a moment it finds answered; a stale "Take all" would do that
 *    and count the dose, and spend its supply, twice.
 *  - One clock reading serves the whole batch, so every snooze fires at the same instant
 *    and comes back as a batch again.
 *  - The reschedule runs once, after every write, not once per dose.
 */
class BatchAnswers(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val clock: Clock,
    private val userId: () -> String,
    /** `(reminderId, scheduledTime, snoozeUntil)` to whether anything was armed. */
    private val armSnooze: suspend (String, Long, Long) -> Boolean,
    private val rescheduleAfterWrite: suspend (String) -> Unit,
    /** Takes the answered doses (and the batch notification) out of the shade. */
    private val dismiss: (scheduledTime: Long, reminderIds: List<String>) -> Unit,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("batch", what, e) },
) {
    suspend fun answer(action: BatchAction, scheduledTime: Long, reminderIds: List<String>): BatchResult {
        val user = userId()
        val now = clock()
        val snoozeUntil = now + SNOOZE_MINUTES * 60_000L
        var changed = 0
        var failed = 0
        val snoozed = mutableListOf<String>()

        for (reminderId in reminderIds.distinct()) {
            try {
                val reminder = reminders.get(reminderId)
                if (reminder == null) {
                    // Deleted since the notification posted: nothing to answer, nothing to wait for.
                    continue
                }
                var instance = instances.findAnswerableForOccurrence(user, reminderId, scheduledTime)
                if (instance == null) {
                    // Answered already (from the app, or by an earlier tap): leave it be.
                    if (instances.findAnyForOccurrence(user, reminderId, scheduledTime) != null) continue
                    instance = instances.create(CreateReminderInstanceInput(user, reminderId, reminder.kind, scheduledTime))
                }
                when (action) {
                    BatchAction.Take -> instances.markCompleted(instance.id, now)
                    BatchAction.Skip -> instances.markSkipped(instance.id)
                    BatchAction.Snooze -> {
                        instances.markSnoozed(instance.id, snoozeUntil)
                        snoozed += reminderId
                    }
                }
                changed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("batch ${action.wire} of $reminderId failed", e)
                failed++
            }
        }

        // The rows are written and are what syncs; a failure to arm or reschedule must not read as a failed answer.
        for (reminderId in snoozed) {
            try {
                armSnooze(reminderId, scheduledTime, snoozeUntil)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("arming the snooze of $reminderId failed", e)
            }
        }
        try {
            rescheduleAfterWrite(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("reschedule after a batch failed", e)
        }
        // A shade that still shows a dose that failed is the only honest thing to leave.
        if (failed == 0) dismiss(scheduledTime, reminderIds)
        return BatchResult(changed, failed)
    }
}
