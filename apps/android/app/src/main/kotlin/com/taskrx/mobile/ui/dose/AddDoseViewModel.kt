package com.taskrx.mobile.ui.dose

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isAnswerable
import com.taskrx.core.time.atClockTimeOnDayOf
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddDoseState(
    /** Every medication on record, running ones first: a stopped one is still a medication you may take. */
    val medications: List<Reminder> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** A dose was recorded; the screen goes back. */
    val saved: Boolean = false,
)

/**
 * Records a taking that no Schedule asked for: an extra dose, or one of a medication with
 * no times. It is a Reminder Instance at a moment of the person's choosing (today, not in
 * the future), Completed at that same moment. The timeline already shows an Instance the
 * Schedule did not predict, so nothing else needs to know.
 *
 * It counts as Completed toward Adherence like any other: there is no field to say
 * "unscheduled", so an extra dose adds one to both sides of the figure.
 */
class AddDoseViewModel(
    private val app: AppContainer,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("add-dose", what, e) },
) : ViewModel() {
    private val mutable = MutableStateFlow(AddDoseState())
    val state: StateFlow<AddDoseState> = mutable

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            try {
                val all = app.reminders.list(app.session.userId, ReminderKind.Medication, includeInactive = true)
                mutable.update { it.copy(medications = all.sortedWith(compareBy({ !it.active }, { it.name.lowercase() })), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                mutable.update { it.copy(loading = false, refreshing = false) }
            }
        }

    /** Records [medication] as taken at [hour]:[minute] today. A time that has not happened yet is refused. */
    fun record(medication: Reminder, hour: Int, minute: Int): Job =
        viewModelScope.launch {
            val now = app.clock()
            val at = atClockTimeOnDayOf(now, hour, minute, zone())
            if (at > now) {
                toast(ToastRequest("That time hasn't happened yet", ToastKind.Error))
                return@launch
            }
            val user = app.session.userId
            try {
                val existing = app.instances.findAnyForOccurrence(user, medication.id, at)
                if (existing != null && !isAnswerable(existing.status)) {
                    // Same medication, same minute, already answered: a second row would be hidden behind the first.
                    toast(ToastRequest("Already recorded at that time", ToastKind.Error))
                    return@launch
                }
                val instance =
                    existing ?: app.instances.create(CreateReminderInstanceInput(user, medication.id, medication.kind, at))
                app.instances.markCompleted(instance.id, at)
                app.notifications.rescheduleAfterWrite(user)
                toast(ToastRequest("Recorded as taken", ToastKind.Success))
                mutable.update { it.copy(saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("recording a dose failed", e)
                toast(ToastRequest("Couldn't record it", ToastKind.Error))
            }
        }
}
