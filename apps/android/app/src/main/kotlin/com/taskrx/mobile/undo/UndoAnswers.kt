package com.taskrx.mobile.undo

import android.util.Log
import com.taskrx.core.domain.ANSWERABLE_STATUSES
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.undo.UndoVerdict
import com.taskrx.core.undo.undoVerdict
import com.taskrx.core.undo.undoWillBeMissed
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** How an undo went. */
sealed interface UndoResult {
    /**
     * The answer is taken back and the moment is open again. [willBeMissed] is whether its
     * Grace Period is already over, so the next sweep will record it as Missed straight away.
     */
    data class Reopened(val instance: ReminderInstance, val willBeMissed: Boolean) : UndoResult

    /** Not offered; [verdict] says why. */
    data class Refused(val verdict: UndoVerdict) : UndoResult

    /** The Instance, or the Reminder it belongs to, is gone. */
    data object NotFound : UndoResult
}

/**
 * Takes an answer back, from the confirmation card or from a timeline row (ADR 0008). Neither
 * caller decides anything: whether an undo is offered is `undoVerdict` in :core, and the write
 * is [ReminderInstancesRepository.reopen]. This is the glue that is the same for both.
 *
 * After the write it cancels that moment's deferred reminder, if there was one, and reschedules,
 * so that what is armed never lags what is stored. Both come after the row is written and a
 * failure in either is logged and not reported: the undo happened, and the next foreground
 * repairs the alarms.
 */
class UndoAnswers(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val clock: Clock,
    private val userId: () -> String,
    private val cancelSnooze: suspend (reminderId: String, scheduledTime: Long) -> Unit,
    private val rescheduleAfterWrite: suspend (String) -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("undo", what, e) },
) {
    suspend fun undo(instanceId: String): UndoResult {
        val instance = instances.get(instanceId) ?: return UndoResult.NotFound
        val reminder = reminders.get(instance.reminderId) ?: return UndoResult.NotFound
        val user = userId()

        // Reopening this row beside another that is open at the same moment would leave two
        // pending rows at one pairing, and the sweep records a miss for each (issue #21).
        val otherOpen =
            instances.allForOccurrence(user, instance.reminderId, instance.scheduledTime)
                .any { it.id != instance.id && it.status in ANSWERABLE_STATUSES }
        val verdict = undoVerdict(instance.status, reminder, instance.scheduledTime, otherOpen, zone())
        if (verdict != UndoVerdict.Allowed) return UndoResult.Refused(verdict)

        val reopened = instances.reopen(instanceId)
        try {
            cancelSnooze(instance.reminderId, instance.scheduledTime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cancelling the snooze of $instanceId failed", e)
        }
        try {
            rescheduleAfterWrite(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("reschedule after an undo failed", e)
        }
        return UndoResult.Reopened(reopened, undoWillBeMissed(instance.scheduledTime, clock()))
    }
}
