package com.taskrx.mobile.ui.dose

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.asneeded.takings
import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.time.atClockTimeOnDayOf
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.asneeded.AsNeededEntry
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.dose.RecordResult
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DAY_MS = 24 * 60 * 60_000L

/** How far back to look for a last dose: long enough that Log a dose can always show when it was. */
private const val HISTORY_MS = 30 * DAY_MS

data class AddDoseState(
    /** Every medication on record, running ones first: a stopped one is still a medication you may take. */
    val medications: List<Reminder> = emptyList(),
    /** The running medications taken as needed, with their takings, so the list can say when the next dose is allowed. */
    val asNeeded: List<AsNeededEntry> = emptyList(),
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
                val userId = app.session.userId
                val all = app.reminders.list(userId, ReminderKind.Medication, includeInactive = true)
                val asNeededMeds = all.filter { it.active && it.schedule is AsNeededTimes }
                // By the Instance's own moment, and far enough back that a last dose taken a while
                // ago still shows. A time moved after the fact is inside this too.
                val recent =
                    if (asNeededMeds.isEmpty()) {
                        emptyList()
                    } else {
                        app.instances.list(InstanceFilter(userId, since = app.clock() - HISTORY_MS))
                    }
                val asNeeded = asNeededMeds.map { AsNeededEntry(it, it.schedule as AsNeededTimes, takings(recent, it.id)) }
                mutable.update {
                    it.copy(
                        medications = all.sortedWith(compareBy({ !it.active }, { it.name.lowercase() })),
                        asNeeded = asNeeded,
                        error = null,
                    )
                }
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
            try {
                if (app.doses.record(medication, at) == RecordResult.AlreadyRecorded) {
                    toast(ToastRequest("Already recorded at that time", ToastKind.Error))
                    return@launch
                }
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
