/**
 * The unified reminder aggregate, ported from `TaskRx.Core/Reminder.cs`,
 * `ReminderInstance.cs`, and `Caregiver.cs` on the `archive/dotnet-maui`
 * branch.
 *
 * "Unified" is the point: a single `Reminder` carries a `kind` of `medication`
 * or `task`, where the POC's `Medication` type can only express the former.
 * Both live side by side for now — `@taskrx/shared` still exports `Medication`
 * and `DoseLog`, and the cutover is a separate step.
 *
 * C# `Guid` becomes `string`, and `DateTimeOffset` becomes unix ms, matching
 * how the POC types and the local SQLite schema already store identifiers and
 * instants.
 */

import type { InstanceStatus, Priority, ReminderKind } from "./enums";
import type { ScheduleTimes } from "./schedule-times";
import { dailyTimes } from "./schedule-times";

export interface Reminder {
  readonly id: string;
  readonly userId: string;
  readonly kind: ReminderKind;
  readonly name: string;
  readonly dosage: string | undefined;
  readonly instructions: string | undefined;
  readonly schedule: ScheduleTimes;
  readonly priority: Priority;
  readonly tags: readonly string[];
  readonly pillsRemaining: number | undefined;
  readonly pillsPerDose: number | undefined;
  readonly refillReminderDays: number | undefined;
  readonly caregiverIds: readonly string[];
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface CreateReminderInput {
  readonly id: string;
  readonly userId: string;
  readonly kind: ReminderKind;
  readonly name: string;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly dosage?: string;
  readonly instructions?: string;
  readonly schedule?: ScheduleTimes;
  readonly priority?: Priority;
  readonly tags?: readonly string[];
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly caregiverIds?: readonly string[];
  readonly active?: boolean;
}

/**
 * Builds a `Reminder`, applying the defaults `ReminderParser.BuildReminder`
 * uses rather than C#'s accidental zero-value defaults: priority is `normal`
 * (not the enum's zero value `critical`) and a new reminder is `active`.
 *
 * Collections are copied, so a later mutation of the caller's array cannot
 * reach into the constructed reminder.
 */
export function createReminder(input: CreateReminderInput): Reminder {
  return {
    id: input.id,
    userId: input.userId,
    kind: input.kind,
    name: input.name,
    dosage: input.dosage,
    instructions: input.instructions,
    schedule: input.schedule ?? dailyTimes([]),
    priority: input.priority ?? "normal",
    tags: [...(input.tags ?? [])],
    pillsRemaining: input.pillsRemaining,
    pillsPerDose: input.pillsPerDose,
    refillReminderDays: input.refillReminderDays,
    caregiverIds: [...(input.caregiverIds ?? [])],
    active: input.active ?? true,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
  };
}

export interface ReminderInstance {
  readonly id: string;
  readonly reminderId: string;
  readonly userId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
  readonly status: InstanceStatus;
  readonly completedAt: number | undefined;
  readonly snoozedUntil: number | undefined;
  readonly snoozeCount: number;
  readonly notes: string | undefined;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface CreateReminderInstanceInput {
  readonly id: string;
  readonly reminderId: string;
  readonly userId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly status?: InstanceStatus;
  readonly completedAt?: number;
  readonly snoozedUntil?: number;
  readonly snoozeCount?: number;
  readonly notes?: string;
}

/**
 * Builds a `ReminderInstance`. A freshly materialized instance is `pending`
 * with no snoozes, which is the state the adherence calculator excludes from
 * its denominator.
 */
export function createReminderInstance(
  input: CreateReminderInstanceInput
): ReminderInstance {
  return {
    id: input.id,
    reminderId: input.reminderId,
    userId: input.userId,
    reminderKind: input.reminderKind,
    scheduledTime: input.scheduledTime,
    status: input.status ?? "pending",
    completedAt: input.completedAt,
    snoozedUntil: input.snoozedUntil,
    snoozeCount: input.snoozeCount ?? 0,
    notes: input.notes,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
  };
}

export interface Caregiver {
  readonly id: string;
  readonly userId: string;
  readonly caregiverUserId: string;
  readonly relationship: string | undefined;
  readonly notifyForMissedMedication: boolean;
  readonly notifyForMissedTask: boolean;
  readonly channels: readonly string[];
  readonly deviceTokens: readonly string[];
  readonly createdAt: number;
}

export interface CreateCaregiverInput {
  readonly id: string;
  readonly userId: string;
  readonly caregiverUserId: string;
  readonly createdAt: number;
  readonly relationship?: string;
  readonly notifyForMissedMedication?: boolean;
  readonly notifyForMissedTask?: boolean;
  readonly channels?: readonly string[];
  readonly deviceTokens?: readonly string[];
}

/**
 * Builds a `Caregiver` link. Both notification opt-ins default to off: sending
 * a third party someone's medication activity is opt-in, never inherited from
 * a default.
 */
export function createCaregiver(input: CreateCaregiverInput): Caregiver {
  return {
    id: input.id,
    userId: input.userId,
    caregiverUserId: input.caregiverUserId,
    relationship: input.relationship,
    notifyForMissedMedication: input.notifyForMissedMedication ?? false,
    notifyForMissedTask: input.notifyForMissedTask ?? false,
    channels: [...(input.channels ?? [])],
    deviceTokens: [...(input.deviceTokens ?? [])],
    createdAt: input.createdAt,
  };
}
