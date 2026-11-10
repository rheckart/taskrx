package com.taskrx.core.domain

/*
 * The explicit lifecycle of a Reminder.
 *
 * A Reminder is in exactly one of three phases at any moment: Active, Paused or Stopped.
 * Before this file each surface derived that from `active` and `pauses` for itself — the
 * Reminders row looked for a live pause and separately tested the Kind and the Schedule
 * before offering Pause, and the repository's `pause`/`resume` trusted its caller. This
 * is the one place the phase and the actions it offers are decided.
 *
 * Pausing is a window, not a flag (ADR 0009), so [ReminderPhase.Paused] carries the live
 * window. A window is live from the moment it starts until it ends or until the person
 * resumes; one that has finished is not a phase any more, though it stays on the Reminder
 * because the Missed sweep still has to read it.
 */

/** Where a Reminder is in its life, as of some instant. */
sealed interface ReminderPhase {
    /** It produces Occurrences and can be answered. */
    data object Active : ReminderPhase

    /** It has no Occurrences until [window] ends. */
    data class Paused(val window: PauseWindow) : ReminderPhase

    /** `active = false`: gone for good, its history kept (Stop). */
    data object Stopped : ReminderPhase
}

/** What a person may do to a Reminder. Editing is not a lifecycle change. */
enum class ReminderAction {
    /** Start a pause, if the Reminder has scheduled moments to hold back. */
    Pause,

    /** End a pause now, or cancel one that has not started. */
    Resume,

    /** End the Reminder for good. */
    Stop,
}

/**
 * The phase of [reminder] at [now].
 *
 * A stopped Reminder is Stopped even if it still carries a pause window: nothing about it
 * will ever run again, and a finished course with an open pause is still a finished course.
 * An active one is Paused from the first moment of a window up to its end, or for ever
 * while the window is open, and Active otherwise.
 */
fun reminderPhase(reminder: Reminder, now: Long): ReminderPhase =
    when {
        !reminder.active -> ReminderPhase.Stopped
        else -> livePause(reminder.pauses, now)?.let(ReminderPhase::Paused) ?: ReminderPhase.Active
    }

/**
 * Whether [reminder] has moments a pause could hold back.
 *
 * Only a scheduled medication does. A task has no doses, and an as-needed medication has
 * no times at all (ADR 0010), so a pause on either would be a window with nothing inside
 * it. The rule lives here rather than in the Reminders row that used to spell it out.
 */
fun canPause(reminder: Reminder): Boolean =
    reminder.active &&
        reminder.kind == ReminderKind.Medication &&
        reminder.schedule !is AsNeededTimes

/**
 * What [reminder] offers while in [phase].
 *
 * Stop is offered in both live phases and never once Stopped. Pause is offered only while
 * Active and only when there is something to pause. Resume is offered exactly while
 * Paused: it covers both ending a running pause and cancelling one that has not started,
 * which the screen words as "Resume" or "Cancel pause".
 */
fun reminderActions(reminder: Reminder, phase: ReminderPhase): Set<ReminderAction> =
    buildSet {
        when (phase) {
            ReminderPhase.Active -> if (canPause(reminder)) add(ReminderAction.Pause)
            is ReminderPhase.Paused -> add(ReminderAction.Resume)
            ReminderPhase.Stopped -> Unit
        }
        if (phase != ReminderPhase.Stopped) add(ReminderAction.Stop)
    }

/** [reminderActions] for the phase [reminder] is in at [now]. */
fun reminderActions(reminder: Reminder, now: Long): Set<ReminderAction> = reminderActions(reminder, reminderPhase(reminder, now))