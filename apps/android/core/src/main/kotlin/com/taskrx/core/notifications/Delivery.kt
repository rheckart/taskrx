package com.taskrx.core.notifications

import com.taskrx.core.domain.Reminder
import com.taskrx.core.schedule.ScheduledOccurrence
import com.taskrx.core.schedule.selectMedicationHorizon
import com.taskrx.core.schedule.selectTaskHorizon
import java.time.ZoneId

/*
 * How each Tier is delivered, and the identity of every notification the app arms.
 * Ported from archive/expo-rn:apps/mobile/src/notifications/scheduler.ts and snooze.ts: everything
 * there that was a decision rather than a call into the operating system.
 *
 * The :app module owns the calls (AlarmManager, WorkManager, NotificationManager);
 * this file owns which call each Tier gets, what the ids are, and what the text says.
 * "If a rule ends up inside a repository, the extraction was incomplete."
 */

/**
 * The channel ids are persisted on the device the first time a channel is created, and
 * the user's per-channel sound / importance / Do Not Disturb overrides are keyed to
 * them. Both id STRINGS are frozen: changing one orphans the channel and silently
 * resets every override the person set.
 */
const val MEDICATION_CHANNEL_ID: String = "medication_reminders"
const val MEDICATION_CHANNEL_NAME: String = "Medication Reminders"
const val TASK_CHANNEL_ID: String = "task_reminders"
const val TASK_CHANNEL_NAME: String = "Task Reminders"

/** The medication Horizon's budget: a rolling 64 (the iOS cap, kept for parity). */
const val MAX_MEDICATION_TRIGGERS: Int = 64

/**
 * The task Horizon's budget. NOT a share of the 64: a dose occupies an exact-alarm
 * slot and a task occupies a work request, and neither subsystem knows the other
 * exists (ADR 0007). 32 bounds the diff pass, it is not an OS limit.
 */
const val MAX_TASK_TRIGGERS: Int = 32

/**
 * Namespace markers. The id spaces must stay disjoint: the cancel pass sees every
 * armed notification of both Tiers, so a pass that did not recognise one namespace
 * would cancel it as stale (ADR 0003 arriving from the opposite direction).
 */
const val TASK_ID_PREFIX: String = "task-"
const val SNOOZE_ID_PREFIX: String = "snooze-"

/** A dose's notification id: `${reminderId}-${scheduledTime}`. */
fun medicationNotificationId(reminderId: String, scheduledTime: Long): String = "$reminderId-$scheduledTime"

/** Every id a task-Tier notification for [reminderId] can have, as a prefix. */
fun taskIdPrefixFor(reminderId: String): String = "$TASK_ID_PREFIX$reminderId-"

/** A task's notification id: `task-${reminderId}-${scheduledTime}`. */
fun taskNotificationId(reminderId: String, scheduledTime: Long): String =
    "${taskIdPrefixFor(reminderId)}$scheduledTime"

/** A snooze is deterministic too, so snoozing the same Occurrence to the same instant re-arms rather than duplicates. */
fun snoozeNotificationId(reminderId: String, scheduledTime: Long, snoozeUntil: Long): String =
    "$SNOOZE_ID_PREFIX${medicationNotificationId(reminderId, scheduledTime)}-$snoozeUntil"

/**
 * Whether [id] belongs to [reminderId] in either namespace. Both are checked rather
 * than looking the Kind up, because this is the path a DELETED reminder is cancelled
 * from: the row may already be gone, and an unknown Kind must still leave nothing armed.
 */
fun belongsToReminder(id: String, reminderId: String): Boolean =
    id.startsWith("$reminderId-") ||
        id.startsWith(taskIdPrefixFor(reminderId)) ||
        id.startsWith("$SNOOZE_ID_PREFIX$reminderId-")

/** Which operating-system subsystem carries a Tier (ADR 0003, ADR 0007). */
enum class Mechanism {
    /** `AlarmManager.setAlarmClock`: exact, wakes, bypasses Doze, survives aggressive OEM kill policies. */
    AlarmClock,

    /** WorkManager `OneTimeWorkRequest` with an initial delay. Never AlarmManager. */
    WorkRequest,
}

enum class ChannelImportance { High, Default }

/**
 * Everything the operating system is told differently about the two Tiers, as a
 * table rather than a branch at each site. Reading it side by side is the point: this
 * is where the last `SET_EXACT_AND_ALLOW_WHILE_IDLE` survived a migration, because one
 * path named its own primitive.
 */
data class TierDelivery(
    val channelId: String,
    val channelName: String,
    val importance: ChannelImportance,
    val mechanism: Mechanism,
)

fun deliveryFor(tier: Tier): TierDelivery =
    when (tier) {
        Tier.Medication ->
            TierDelivery(MEDICATION_CHANNEL_ID, MEDICATION_CHANNEL_NAME, ChannelImportance.High, Mechanism.AlarmClock)
        Tier.Task ->
            TierDelivery(TASK_CHANNEL_ID, TASK_CHANNEL_NAME, ChannelImportance.Default, Mechanism.WorkRequest)
    }

/**
 * One notification to be armed: identity, Tier, the instant, and the text. It is also the
 * payload carried to the receiver or worker that posts it; :app owns its wire encoding.
 */
data class NotificationSpec(
    val id: String,
    val tier: Tier,
    val fireAt: Long,
    val title: String,
    val body: String,
    val reminderId: String,
    /** The Occurrence this answers; for a snooze, the moment it was deferred FROM. */
    val scheduledTime: Long,
    val snoozed: Boolean = false,
)

private fun dosageLabel(dosage: String?): String = if (dosage != null) " ($dosage)" else ""

fun medicationSpec(occurrence: ScheduledOccurrence): NotificationSpec =
    NotificationSpec(
        id = medicationNotificationId(occurrence.reminderId, occurrence.fireAt),
        tier = Tier.Medication,
        fireAt = occurrence.fireAt,
        title = occurrence.name,
        body = "Time to take${dosageLabel(occurrence.dosage)}",
        reminderId = occurrence.reminderId,
        scheduledTime = occurrence.fireAt,
    )

fun taskSpec(occurrence: ScheduledOccurrence): NotificationSpec =
    NotificationSpec(
        id = taskNotificationId(occurrence.reminderId, occurrence.fireAt),
        tier = Tier.Task,
        fireAt = occurrence.fireAt,
        title = occurrence.name,
        body = "Time to do it",
        reminderId = occurrence.reminderId,
        scheduledTime = occurrence.fireAt,
    )

/**
 * The deferred reminder for a snoozed Occurrence. The Tier follows the Kind here as it
 * does in the scheduler: the snooze is the likeliest side door for a task to borrow the
 * alarm-clock primitive, so no argument passed in can swap them.
 *
 * [snoozeUntil] is an instant, not a duration, because the caller has already written
 * it to the Instance; the displayed minutes are derived from the same [now] the caller
 * decided, never from a second reading of the clock.
 */
fun snoozeSpec(reminder: Reminder, scheduledTime: Long, snoozeUntil: Long, now: Long): NotificationSpec {
    val minutes = maxOf(1L, Math.round((snoozeUntil - now) / 60_000.0))
    return NotificationSpec(
        id = snoozeNotificationId(reminder.id, scheduledTime, snoozeUntil),
        tier = tierFor(reminder.kind),
        fireAt = snoozeUntil,
        title = "${reminder.name}${dosageLabel(reminder.dosage)} (snoozed)",
        body = "Reminder snoozed for $minutes minutes",
        reminderId = reminder.id,
        scheduledTime = scheduledTime,
        snoozed = true,
    )
}

/**
 * Both Tiers' desired sets, doses first: if arming throws part-way (a permission
 * revoked, a native failure), what is already armed is the medication set.
 *
 * Each Tier is selected from its own disjoint set of Reminders on its own budget, so
 * neither can shorten the other.
 */
fun desiredNotifications(reminders: List<Reminder>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<NotificationSpec> =
    selectMedicationHorizon(reminders, now, MAX_MEDICATION_TRIGGERS, zone).map(::medicationSpec) +
        selectTaskHorizon(reminders, now, MAX_TASK_TRIGGERS, zone).map(::taskSpec)
