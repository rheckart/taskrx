package com.taskrx.mobile.dose

import android.util.Log
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.isAnswerable
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.ReminderInstancesRepository
import kotlinx.coroutines.CancellationException

/** How recording a dose went. */
enum class RecordResult { Recorded, AlreadyRecorded }

/**
 * Records a medication as taken at a moment of the person's choosing: the Add Dose screen's
 * form and the Supplies screen's button. Neither caller decides anything; this is the write
 * they share. The moment is an Instance's scheduled time and its Completed time both, so the
 * timeline shows it where it was taken. Whether that moment has happened yet is the caller's
 * to check, because each words the refusal its own way.
 *
 * A dose already answered at that same minute is refused: a second row would sit hidden behind
 * the first. After the write the shade entry for that moment is taken out, so a dose recorded
 * here is not still asking to be taken.
 */
class DoseRecorder(
    private val instances: ReminderInstancesRepository,
    private val userId: () -> String,
    private val rescheduleAfterWrite: suspend (String) -> Unit,
    /** Takes the doses (and the batch notification) for one moment out of the shade. */
    private val dismiss: (scheduledTime: Long, reminderIds: List<String>) -> Unit,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("dose", what, e) },
) {
    suspend fun record(medication: Reminder, at: Long): RecordResult {
        val user = userId()
        val existing = instances.findAnyForOccurrence(user, medication.id, at)
        if (existing != null && !isAnswerable(existing.status)) return RecordResult.AlreadyRecorded
        val instance = existing ?: instances.create(CreateReminderInstanceInput(user, medication.id, medication.kind, at))
        instances.markCompleted(instance.id, at)
        rescheduleAfterWrite(user)
        // The row is written and is what syncs; a failure to clear the shade must not read as a failed dose.
        try {
            dismiss(at, listOf(medication.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("clearing the shade after recording ${medication.id} failed", e)
        }
        return RecordResult.Recorded
    }
}
