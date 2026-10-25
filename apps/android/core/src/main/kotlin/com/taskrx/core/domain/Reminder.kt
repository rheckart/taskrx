package com.taskrx.core.domain

/*
 * The unified reminder aggregate, ported from `TaskRx.Core/Reminder.cs`,
 * `ReminderInstance.cs`, and `Caregiver.cs` in the retired .NET app.
 *
 * "Unified" is the point: a single [Reminder] carries a [ReminderKind] of
 * medication or task, where the POC's `Medication` type can only express the
 * former.
 *
 * C# `Guid` is a `String`, and `DateTimeOffset` is Unix epoch milliseconds
 * (`Long`), matching how the local SQLite schema and the Convex payloads store
 * identifiers and instants.
 */

/** Anything with a last-write timestamp; what the sync conflict rules read. */
interface Timestamped {
    val updatedAt: Long
}

/** A row with a last-write timestamp and an answer status. */
interface StatusTracked : Timestamped {
    val status: InstanceStatus
}

data class Reminder(
    val id: String,
    val userId: String,
    val kind: ReminderKind,
    val name: String,
    val dosage: String?,
    val instructions: String?,
    val schedule: ScheduleTimes,
    val priority: Priority,
    val tags: List<String>,
    val pillsRemaining: Double?,
    val pillsPerDose: Double?,
    val refillReminderDays: Int?,
    val caregiverIds: List<String>,
    val active: Boolean,
    val createdAt: Long,
    override val updatedAt: Long,
) : Timestamped

/**
 * Builds a [Reminder], applying the defaults `ReminderParser.BuildReminder`
 * uses rather than C#'s accidental zero-value defaults: priority is `normal`
 * (not the enum's zero value `critical`) and a new reminder is `active`.
 *
 * Collections are copied, so a later mutation of the caller's list cannot reach
 * into the constructed reminder.
 */
fun createReminder(
    id: String,
    userId: String,
    kind: ReminderKind,
    name: String,
    createdAt: Long,
    updatedAt: Long,
    dosage: String? = null,
    instructions: String? = null,
    schedule: ScheduleTimes = dailyTimes(emptyList()),
    priority: Priority = Priority.Normal,
    tags: List<String> = emptyList(),
    pillsRemaining: Double? = null,
    pillsPerDose: Double? = null,
    refillReminderDays: Int? = null,
    caregiverIds: List<String> = emptyList(),
    active: Boolean = true,
): Reminder =
    Reminder(
        id = id,
        userId = userId,
        kind = kind,
        name = name,
        dosage = dosage,
        instructions = instructions,
        schedule = schedule,
        priority = priority,
        tags = tags.toList(),
        pillsRemaining = pillsRemaining,
        pillsPerDose = pillsPerDose,
        refillReminderDays = refillReminderDays,
        caregiverIds = caregiverIds.toList(),
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

data class ReminderInstance(
    val id: String,
    val reminderId: String,
    val userId: String,
    val reminderKind: ReminderKind,
    val scheduledTime: Long,
    override val status: InstanceStatus,
    val completedAt: Long?,
    val snoozedUntil: Long?,
    val snoozeCount: Int,
    val notes: String?,
    val createdAt: Long,
    override val updatedAt: Long,
) : StatusTracked

/**
 * Builds a [ReminderInstance]. A freshly materialized instance is `pending`
 * with no snoozes, which is the state the adherence calculator excludes from
 * its denominator.
 */
fun createReminderInstance(
    id: String,
    reminderId: String,
    userId: String,
    reminderKind: ReminderKind,
    scheduledTime: Long,
    createdAt: Long,
    updatedAt: Long,
    status: InstanceStatus = InstanceStatus.Pending,
    completedAt: Long? = null,
    snoozedUntil: Long? = null,
    snoozeCount: Int = 0,
    notes: String? = null,
): ReminderInstance =
    ReminderInstance(
        id = id,
        reminderId = reminderId,
        userId = userId,
        reminderKind = reminderKind,
        scheduledTime = scheduledTime,
        status = status,
        completedAt = completedAt,
        snoozedUntil = snoozedUntil,
        snoozeCount = snoozeCount,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

data class Caregiver(
    val id: String,
    val userId: String,
    val caregiverUserId: String,
    val relationship: String?,
    val notifyForMissedMedication: Boolean,
    val notifyForMissedTask: Boolean,
    val channels: List<String>,
    val deviceTokens: List<String>,
    val createdAt: Long,
)

/**
 * Builds a [Caregiver] link. Both notification opt-ins default to off: sending
 * a third party someone's medication activity is opt-in, never inherited from
 * a default.
 */
fun createCaregiver(
    id: String,
    userId: String,
    caregiverUserId: String,
    createdAt: Long,
    relationship: String? = null,
    notifyForMissedMedication: Boolean = false,
    notifyForMissedTask: Boolean = false,
    channels: List<String> = emptyList(),
    deviceTokens: List<String> = emptyList(),
): Caregiver =
    Caregiver(
        id = id,
        userId = userId,
        caregiverUserId = caregiverUserId,
        relationship = relationship,
        notifyForMissedMedication = notifyForMissedMedication,
        notifyForMissedTask = notifyForMissedTask,
        channels = channels.toList(),
        deviceTokens = deviceTokens.toList(),
        createdAt = createdAt,
    )
