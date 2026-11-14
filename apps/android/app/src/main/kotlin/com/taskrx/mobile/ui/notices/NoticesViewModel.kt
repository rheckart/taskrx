package com.taskrx.mobile.ui.notices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.moveRefusal
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.withOverride
import com.taskrx.core.notices.DEFAULT_NOTICE_TIME
import com.taskrx.core.notices.Proposal
import com.taskrx.core.notices.ReadDate
import com.taskrx.core.notices.noticeText
import com.taskrx.core.notices.occurrenceOptionsAround
import com.taskrx.core.notices.proposalFor
import com.taskrx.core.notices.proposalsFor
import com.taskrx.core.notices.readNoticeDate
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A notice the person is turning into a task: the notice, and the moment they picked. */
data class NoticeTaskDraft(
    val notice: Notice,
    val at: Long,
)

/** The task picker for a notice with no rule proposal: which task the person wants to apply it to. */
data class NoticeTaskPick(
    val notice: Notice,
    val tasks: List<Reminder>,
)

/** Choosing a different Occurrence of a task than the one D11 picked. */
data class NoticeOccurrencePick(
    val notice: Notice,
    val proposal: Proposal,
    val options: List<Long>,
)

data class NoticesState(
    val notices: List<Notice> = emptyList(),
    /** What the reader made of each notice's kept sentence: a date, or why there is none. */
    val readings: Map<String, ReadDate> = emptyMap(),
    /** The moves each notice's rules propose, in task order. A person can add a task by hand. */
    val proposals: Map<String, List<Proposal>> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val draft: NoticeTaskDraft? = null,
    val pick: NoticeTaskPick? = null,
    val occurrencePick: NoticeOccurrencePick? = null,
    val saving: Boolean = false,
) {
    /** The notices still waiting for a decision: what the More menu's count adds up. */
    val unresolved: Int get() = notices.count { it.resolution == NoticeResolution.None }
}

private const val LOOKAROUND_MS = 400L * 24 * 60 * 60_000L

/**
 * The Notices screen: outside messages a watched source admitted, what date each one names, and
 * the moves its rules propose.
 *
 * Nothing is applied here without the person confirming it. The reader and the rules are
 * `:core`'s (`readNoticeDate`, `proposalsFor`); this class only reads the tables, hands the
 * result to the screen, and writes the override the person agreed to. A notice with no rule
 * can still be applied to a task by hand (D13), and can still become a task of its own.
 */
class NoticesViewModel(
    private val app: AppContainer,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("notices", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(NoticesState())
    val state: StateFlow<NoticesState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    /**
     * Opens the "new task from this notice" form at the date the notice named, or tomorrow at
     * 09:00 when it named none. A date that has already passed moves to the next day rather
     * than creating a task that is born missed.
     */
    fun beginTask(notice: Notice) {
        mutable.update { it.copy(draft = NoticeTaskDraft(notice, defaultTaskMoment(mutable.value.readings[notice.id]))) }
    }

    fun setDraftAt(at: Long) = mutable.update { it.copy(draft = it.draft?.copy(at = at)) }

    fun cancelTask() = mutable.update { it.copy(draft = null) }

    /** Creates a one-off task at the moment the person picked, then settles the notice. */
    fun saveTask(): Job =
        viewModelScope.launch {
            val draft = mutable.value.draft ?: return@launch
            if (mutable.value.saving) return@launch
            mutable.update { it.copy(saving = true) }
            try {
                app.reminders.create(
                    CreateReminderInput(
                        userId = app.session.userId,
                        kind = ReminderKind.Task,
                        name = draft.notice.title.trim().ifEmpty { "From a notice" },
                        schedule = onceTimes(draft.at),
                        instructions = draft.notice.sentence,
                    ),
                )
                app.notices.resolve(draft.notice.id, NoticeResolution.NewTask)
                app.notifications.rescheduleAfterWrite(app.session.userId)
                mutable.update { it.copy(draft = null) }
                toast(ToastRequest("Task added", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("creating a task from a notice failed", e)
                toast(ToastRequest("Couldn't add that task", ToastKind.Error))
            } finally {
                mutable.update { it.copy(saving = false) }
            }
            loads.withLock { load() }
        }

    /** Opens the task picker for a notice the person wants to apply by hand (D13). */
    fun beginPick(notice: Notice): Job =
        viewModelScope.launch {
            try {
                val tasks = app.reminders.list(app.session.userId, kind = ReminderKind.Task).filter { it.active }
                mutable.update { it.copy(pick = NoticeTaskPick(notice, tasks)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("listing tasks for a notice failed", e)
                toast(ToastRequest("Couldn't list your tasks", ToastKind.Error))
            }
        }

    fun cancelPick() = mutable.update { it.copy(pick = null) }

    /** Builds the move the notice asks of the task the person picked, and shows it for confirmation. */
    fun pickTask(reminder: Reminder): Job =
        viewModelScope.launch {
            val pick = mutable.value.pick ?: return@launch
            val reading = mutable.value.readings[pick.notice.id] as? ReadDate.Found
            if (reading == null) {
                toast(ToastRequest("No date found in this notice", ToastKind.Error))
                return@launch
            }
            try {
                val now = app.clock()
                val instances = app.instances.list(InstanceFilter(pick.notice.userId, since = now - LOOKAROUND_MS, until = now + LOOKAROUND_MS))
                val proposal = proposalFor(pick.notice, reminder, instances, now, zone(), reading)
                mutable.update { it.copy(pick = null, proposals = it.proposals.withProposal(pick.notice.id, proposal)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("building a proposal by hand failed", e)
                toast(ToastRequest("Couldn't propose that move", ToastKind.Error))
            }
        }

    /** Opens the list of the task's next occurrences, so the person can move a different one. */
    fun beginOccurrence(notice: Notice, proposal: Proposal) {
        val from = proposal.from ?: return
        val options = occurrenceOptionsAround(proposal.reminder, from, zone())
        mutable.update { it.copy(occurrencePick = NoticeOccurrencePick(notice, proposal, options)) }
    }

    fun cancelOccurrence() = mutable.update { it.copy(occurrencePick = null) }

    /** Rebuilds the proposal around the Occurrence the person chose. */
    fun chooseOccurrence(source: Long): Job =
        viewModelScope.launch {
            val pick = mutable.value.occurrencePick ?: return@launch
            val reading = mutable.value.readings[pick.notice.id] as? ReadDate.Found ?: return@launch
            try {
                val now = app.clock()
                val instances = app.instances.list(InstanceFilter(pick.notice.userId, since = now - LOOKAROUND_MS, until = now + LOOKAROUND_MS))
                val proposal = proposalFor(pick.notice, pick.proposal.reminder, instances, now, zone(), reading, source = source)
                mutable.update { it.copy(occurrencePick = null, proposals = it.proposals.withProposal(pick.notice.id, proposal)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("choosing an occurrence failed", e)
                toast(ToastRequest("Couldn't choose that occurrence", ToastKind.Error))
            }
        }

    /**
     * Applies a proposal: one Occurrence override on the task, then the notice is settled.
     *
     * The refusal is checked again with the clock as it is now and the Instances already
     * minted, because the dialog may have been open for a while and the check is the rule.
     */
    fun applyProposal(notice: Notice, proposal: Proposal): Job =
        viewModelScope.launch {
            val from = proposal.from ?: return@launch
            if (mutable.value.saving) return@launch
            mutable.update { it.copy(saving = true) }
            try {
                val reminder = app.reminders.get(proposal.reminder.id) ?: return@launch
                val now = app.clock()
                val instances = app.instances.allForOccurrence(app.session.userId, reminder.id, from)
                val refusal =
                    if (proposal.to <= now) {
                        MoveRefusal.InThePast
                    } else {
                        moveRefusal(reminder, from, proposal.to, now, instances)
                    }
                if (refusal != null) {
                    toast(ToastRequest("That move can't be made", ToastKind.Error))
                    return@launch
                }

                app.reminders.update(
                    reminder.id,
                    UpdateReminderPatch(
                        overrides = reminder.overrides.withOverride(OccurrenceOverride(from = from, to = proposal.to, noticeId = notice.id)),
                    ),
                )
                app.notices.resolve(notice.id, NoticeResolution.Applied)
                app.notifications.rescheduleAfterWrite(app.session.userId)
                mutable.update { it.copy(pick = null, occurrencePick = null) }
                toast(ToastRequest("${reminder.name} moved", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("applying a notice failed", e)
                toast(ToastRequest("Couldn't apply that notice", ToastKind.Error))
            } finally {
                mutable.update { it.copy(saving = false) }
            }
            loads.withLock { load() }
        }

    /** Puts a notice aside without doing anything with it. */
    fun dismiss(notice: Notice): Job =
        viewModelScope.launch {
            try {
                app.notices.resolve(notice.id, NoticeResolution.Dismissed)
                toast(ToastRequest("Notice dismissed", ToastKind.Info))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("dismissing a notice failed", e)
                toast(ToastRequest("Couldn't dismiss that", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    private fun defaultTaskMoment(reading: ReadDate?): Long {
        val now = app.clock()
        val zone = zone()
        val found = reading as? ReadDate.Found
        val time = found?.time ?: DEFAULT_NOTICE_TIME
        val date = found?.date ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
        return if (at > now) at else ZonedDateTime.of(date.plusDays(1), time, zone).toInstant().toEpochMilli()
    }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val now = app.clock()
            val notices = app.notices.list(userId).sortedByDescending { it.receivedAt }
            val tasks = app.reminders.list(userId, kind = ReminderKind.Task)
            val instances = app.instances.list(InstanceFilter(userId, since = now - LOOKAROUND_MS, until = now + LOOKAROUND_MS))
            val zone = zone()
            val readings = notices.associate { it.id to readNoticeDate(noticeText(it), now, zone) }
            val proposals =
                notices
                    .filter { it.resolution == NoticeResolution.None }
                    .associate { it.id to proposalsFor(it, tasks, instances, now, zone) }
            mutable.update { it.copy(notices = notices, readings = readings, proposals = proposals, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }
}

/** [this] with the proposal for the same task replaced, or added when the task is new to the notice. */
private fun Map<String, List<Proposal>>.withProposal(noticeId: String, proposal: Proposal): Map<String, List<Proposal>> {
    val current = this[noticeId].orEmpty()
    val next =
        if (current.any { it.reminder.id == proposal.reminder.id }) {
            current.map { if (it.reminder.id == proposal.reminder.id) proposal else it }
        } else {
            current + proposal
        }
    return this + (noticeId to next)
}
